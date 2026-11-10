# FinCard — API de autorización de transacciones

FinCard procesa autorizaciones de tarjetas, descuenta el saldo de manera atómica y registra las aprobaciones en un servicio de auditoría mediante Kafka. Este README es la documentación principal del proyecto: describe la implementación, sus decisiones de arquitectura, la ejecución local y la estrategia propuesta para producción.

## Índice

- [Alcance y arquitectura](#alcance-y-arquitectura)
- [Decisiones de arquitectura — ADRs](#decisiones-de-arquitectura--adrs)
- [Ejecución local con Docker Compose](#ejecución-local-con-docker-compose)
- [Contrato de la API](#contrato-de-la-api)
- [Pruebas](#pruebas)
- [Estrategia propuesta para producción](#estrategia-propuesta-para-producción)
- [Diagnóstico y operación local](#diagnóstico-y-operación-local)

## Alcance y arquitectura

La API recibe `requestId`, `cardId` y `amount`. Una tarjeta activa, no vencida y con saldo suficiente puede aprobar la operación. El débito, el resultado y el evento pendiente de publicación se guardan en una misma transacción de PostgreSQL. Un publicador entrega los eventos confirmados a Kafka y el consumidor los registra en el esquema de auditoría.

| Componente | Tecnología declarada | Responsabilidad |
| --- | --- | --- |
| `authorization-service` | Java 21, Spring Boot 4.1.2, Spring MVC, JPA/JDBC | API REST, validación, JWT, idempotencia, autorización y outbox |
| `audit-service` | Java 21, Spring Boot 4.1.2, Spring Kafka, JPA | Consumo y persistencia idempotente de eventos |
| PostgreSQL | 17 | Tarjetas, transacciones y outbox en `public`; auditoría en `audit` |
| Redis | 7 Alpine | Bloqueo temporal por `requestId` |
| Kafka | 4.1.2, un nodo KRaft local | Transporte de eventos en `transaction-events` |
| Keycloak | 26.8.2 | Emisión de tokens y publicación de claves de verificación |
| Flyway | Dependencias administradas por Spring Boot | Migraciones independientes de cada servicio |
| Docker Compose | Archivo raíz | Entorno local y volúmenes persistentes |

Las versiones corresponden a los POM y al Compose del repositorio; las etiquetas de imágenes no están fijadas por digest.

### Flujo de una autorización

1. Spring Security valida el JWT y Spring MVC valida el cuerpo de la solicitud.
2. Redis intenta adquirir un bloqueo de 30 segundos por `requestId`. Ante un fallo de acceso a Redis, el procesamiento continúa con las garantías de PostgreSQL.
3. PostgreSQL reserva el identificador de solicitud. Si ya existe, se devuelve el resultado previo o un conflicto si los datos son distintos.
4. La función `debit_card` realiza un `UPDATE` condicionado al estado, vencimiento y saldo de la tarjeta. No se hace una lectura de saldo seguida de una escritura independiente.
5. Se persiste `APPROVED` o `DECLINED`. Solo las aprobaciones generan un evento en `transaction_outbox`, dentro del mismo commit.
6. El publicador toma filas con `FOR UPDATE SKIP LOCKED`, envía a Kafka y marca la publicación después de la confirmación.
7. Auditoría inserta el evento con `event_id` único y omite duplicados.

Diagramas complementarios: [C4 contexto](docs/architecture/c4-level-1-context.md), [C4 contenedores](docs/architecture/c4-level-2-containers.md) y [C4 componentes](docs/architecture/c4-level-3-components.md).

### Límites actuales

- Auditoría asíncrona únicamente de aprobaciones. Los rechazos quedan en `public.transactions`.
- Entrega de eventos al menos una vez: puede haber duplicados de transporte, tratados por el consumidor. No se promete entrega exactamente una vez de extremo a extremo.
- Los servicios comparten instancia, base y credenciales de PostgreSQL; la separación de auditoría es por esquema.
- La API exige autenticación, pero no implementa autorización por propietario de tarjeta, roles de negocio ni validación explícita de audiencia.
- No hay API de alta de tarjetas: los datos de demostración se insertan con SQL.
- La función de débito usa PostgreSQL PL/pgSQL; no es una implementación Oracle PL/SQL.
- La tolerancia a fallos de Redis aplica al procesamiento de solicitudes con la aplicación arrancada. Actualmente `RedisConnectionTest` accede a Redis durante el arranque sin manejar el fallo; Redis debe estar disponible para iniciar autorización.
- Compose usa credenciales locales, Keycloak en modo desarrollo y Kafka sin TLS. No constituye un despliegue de producción.

## Decisiones de arquitectura — ADRs

Los siguientes registros describen decisiones presentes en el código y su justificación técnica. Las alternativas se presentan como comparación de diseño, sin atribuirles una evaluación histórica no registrada.

### ADR-001 — Java 21 y Spring Boot

**Estado:** implementado.

**Contexto:** se necesita una API transaccional con seguridad, validación, persistencia y mensajería, además de un consumidor independiente.

**Decisión:** utilizar Java 21 y Spring Boot, con capas de entrada HTTP, servicios, repositorios y entidades. Usar JPA para entidades y SQL/JDBC donde se requiere control explícito de la concurrencia o del outbox.

**Alternativas:** Java sin framework exige integrar manualmente estas capacidades; una pila reactiva añade complejidad sin eliminar el carácter bloqueante de la persistencia actual.

**Consecuencias:** integración coherente y pruebas por capa, a cambio del consumo de memoria de la JVM y del acoplamiento a Spring. Las capas actuales no constituyen una arquitectura hexagonal estricta: el dominio utiliza entidades JPA.

### ADR-002 — PostgreSQL, débito atómico y Flyway

**Estado:** implementado.

**Contexto:** varias solicitudes pueden intentar descontar saldo de la misma tarjeta simultáneamente. El saldo no debe quedar negativo y el resultado debe acompañar al débito.

**Decisión:** ejecutar el débito con una función SQL que actualiza solo si se cumplen las reglas, dentro de una transacción `READ_COMMITTED` que también registra el resultado y el outbox. Versionar el esquema con Flyway y validar su correspondencia con JPA mediante `ddl-auto=validate`.

**Alternativas:** leer y descontar el saldo desde Java sin coordinación permite carreras; un bloqueo optimista requiere gestionar conflictos y reintentos adicionales. La generación automática del esquema no aporta el historial explícito de una migración.

**Consecuencias:** PostgreSQL es la autoridad del saldo y mantiene la atomicidad. La función PL/pgSQL reduce portabilidad y concentra parte de las reglas de negocio en la base; sus cambios deben migrarse y probarse. Tarjetas muy concurridas siguen teniendo contención sobre una misma fila.

### ADR-003 — Auditoría separada mediante Kafka

**Estado:** implementado.

**Contexto:** persistir auditoría no debería requerir una llamada HTTP síncrona adicional dentro de la autorización.

**Decisión:** separar `audit-service` y consumir eventos de `transaction-events`, usando el grupo `audit-service`. Mantener esquemas distintos dentro de la misma base para simplificar el entorno de la prueba.

**Alternativas:** una llamada síncrona acopla latencia y disponibilidad; una tabla de auditoría escrita por el mismo servicio reduce infraestructura, pero también la independencia del procesamiento.

**Consecuencias:** la auditoría puede avanzar a su propio ritmo y recuperarse desde eventos retenidos. Aparecen consistencia eventual, operación de Kafka y necesidad de contratos compatibles. La base compartida sigue siendo un punto común de fallo y de escalabilidad.

### ADR-004 — Transactional Outbox y consumidor idempotente

**Estado:** implementado.

**Contexto:** confirmar un débito y publicar directamente en Kafka son dos operaciones que pueden fallar por separado.

**Decisión:** persistir el evento con el débito, publicarlo posteriormente y marcarlo tras recibir confirmación. El consumidor inserta con `ON CONFLICT (event_id) DO NOTHING`.

**Alternativas:** enviar antes del commit puede publicar operaciones revertidas; enviar después sin outbox puede perder el evento si el proceso cae. CDC es una evolución posible, con infraestructura adicional.

**Consecuencias:** una interrupción de Kafka deja eventos pendientes recuperables en PostgreSQL. Si el envío tuvo éxito pero no se confirmó la marca en la base, el evento puede publicarse otra vez. El diseño exige deduplicación, supervisión del backlog y una política futura de retención. Actualmente se consultan lotes de 5, con pausa predeterminada de 1 segundo entre ejecuciones y reintentos diferidos 30 segundos; la espera de confirmación mantiene abierta la transacción del publicador.

### ADR-005 — Idempotencia persistente y coordinación con Redis

**Estado:** implementado.

**Contexto:** el cliente puede repetir una solicitud después de un timeout o enviar solicitudes concurrentes con el mismo identificador.

**Decisión:** usar `requestId` único en PostgreSQL como garantía persistente y Redis como coordinación temporal. Liberar el bloqueo solo si conserva el token del propietario. Reutilizar un identificador con otra tarjeta o monto devuelve conflicto.

**Alternativas:** un bloqueo en memoria no coordina réplicas; depender exclusivamente del TTL de Redis pierde la protección al expirar o perderse la clave. PostgreSQL sin Redis es viable y ya actúa como respaldo, aunque concentra más contención en la base.

**Consecuencias:** repetir una solicitud terminada con los mismos datos devuelve el mismo resultado sin un nuevo débito. Un bloqueo ocupado devuelve `REQUEST_PENDING`. La corrección no depende de que el TTL cubra toda la operación. El cliente debe conservar el mismo `requestId` al reintentar una operación.

### ADR-006 — Keycloak y API sin sesión con JWT

**Estado:** implementado.

**Contexto:** la API necesita validar identidad sin administrar contraseñas de usuarios.

**Decisión:** delegar emisión de tokens a Keycloak y configurar Spring Security como OAuth2 Resource Server. Compose conserva el issuer público `http://localhost:8081/realms/fincard` y consulta las claves por la dirección interna de Keycloak.

**Alternativas:** implementar autenticación propia agrega responsabilidad de credenciales y rotación; sesiones locales requieren coordinación adicional entre réplicas.

**Consecuencias:** las instancias de la API no guardan sesiones HTTP. Deben gestionarse disponibilidad del proveedor, rotación de claves y expiración. Validar un token no sustituye las reglas de autorización de negocio, pendientes de implementar.

### ADR-007 — Docker Compose para ejecución reproducible local

**Estado:** implementado.

**Contexto:** el evaluador necesita levantar dos servicios y cuatro dependencias con una configuración común.

**Decisión:** usar Compose, imágenes Java con construcción multietapa y volúmenes para datos. Los contenedores de aplicación ejecutan con un usuario sin privilegios.

**Alternativas:** instalar cada dependencia en el equipo aumenta diferencias entre entornos; Kubernetes local requiere más preparación para el alcance de la prueba.

**Consecuencias:** no se necesita Java ni Maven en el host para construir las imágenes. El bootstrap de Keycloak y del tópico sigue siendo manual y está documentado abajo. `depends_on` no comprueba por sí solo la disponibilidad de las dependencias; el arranque se realiza por etapas. Las imágenes actuales omiten tests durante su construcción, por lo que las pruebas deben ejecutarse por separado.

## Ejecución local con Docker Compose

### 1. Prerrequisitos y ubicación

- Docker Engine con Compose v2 y soporte de contenedores Linux; en Windows puede usarse Docker Desktop.
- Acceso a los registros de imágenes y repositorios Maven durante la primera construcción.
- Puertos disponibles: `5432`, `6379`, `8080`, `8081` y `9092`.
- PowerShell para ejecutar literalmente los ejemplos siguientes. Todos parten de la raíz del repositorio y mantienen variables en la misma sesión.
- Java 21 solo es necesario si se ejecutan las pruebas Maven en el host.

Después de clonar o descargar el repositorio, ubicarse en la carpeta que contiene `docker-compose.yml`:

```powershell
docker version
docker compose version
docker compose config --quiet
```

El último comando debe terminar sin errores. `docker version` debe mostrar cliente y servidor: tener solo el cliente instalado no basta.

### 2. Levantar y verificar dependencias

```powershell
docker compose up -d postgres redis kafka keycloak
docker compose ps
docker compose exec -T postgres pg_isready -U fincard -d fincard_db
docker compose exec -T redis redis-cli ping
docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9093 --list
Invoke-RestMethod http://localhost:8081/realms/master/.well-known/openid-configuration
```

Esperar y repetir las comprobaciones si los procesos aún están arrancando. PostgreSQL debe aceptar conexiones, Redis responder `PONG`, Kafka listar tópicos sin error y Keycloak devolver su configuración OIDC. Un listado vacío de tópicos en un entorno nuevo es válido.

### 3. Crear el tópico de Kafka

La creación automática está deshabilitada en Compose. Ejecutar antes de iniciar las aplicaciones:

```powershell
docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9093 --create --if-not-exists --topic transaction-events --partitions 3 --replication-factor 1
docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9093 --describe --topic transaction-events
```

El factor de replicación 1 corresponde al único broker local y no ofrece tolerancia a su pérdida. Si el tópico ya existe, `--if-not-exists` conserva su configuración.

### 4. Configurar Keycloak

Compose no importa automáticamente un realm. Los siguientes comandos crean `fincard` y un cliente confidencial de demostración que obtiene un token con `client_credentials`, sin crear un usuario de negocio:

```powershell
docker compose exec -T keycloak /opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 --realm master --user admin --password admin
docker compose exec -T keycloak /opt/keycloak/bin/kcadm.sh create realms -s realm=fincard -s enabled=true
docker compose exec -T keycloak /opt/keycloak/bin/kcadm.sh create clients -r fincard -s clientId=fincard-demo -s enabled=true -s protocol=openid-connect -s publicClient=false -s serviceAccountsEnabled=true -s standardFlowEnabled=false -s directAccessGrantsEnabled=false -s clientAuthenticatorType=client-secret -s secret=fincard-demo-local-secret
```

Ejecutar las creaciones una sola vez por volumen nuevo. Si ya existen el realm o el cliente, inspeccionarlos desde [la consola de Keycloak](http://localhost:8081) con `admin` / `admin` y usar la configuración y secreto existentes; un error de recurso duplicado no significa que deba borrarse el realm. El cliente debe tener autenticación y cuentas de servicio habilitadas.

Estos valores son exclusivamente de desarrollo. El procedimiento utiliza la [CLI de administración y las cuentas de servicio de Keycloak](https://www.keycloak.org/docs/26.7.0/server_admin/index.html).

### 5. Construir e iniciar las aplicaciones

```powershell
docker compose up -d --build authorization-service audit-service
docker compose ps
docker compose logs --tail 100 authorization-service audit-service
Invoke-RestMethod http://localhost:8080/actuator/health
```

Flyway crea las tablas del servicio de autorización en `public` y las de auditoría en `audit`. Esperar a que los logs indiquen el arranque de ambas aplicaciones y el health de autorización devuelva `UP`. Auditoría es un consumidor sin endpoint HTTP publicado; su verificación funcional se hace consultando su tabla en el paso 8.

Direcciones útiles:

| Recurso | Dirección o acceso |
| --- | --- |
| API | `http://localhost:8080/api/transactions/authorize` |
| Swagger UI | [Abrir Swagger](http://localhost:8080/swagger-ui/index.html) |
| OpenAPI | [Contrato JSON](http://localhost:8080/v3/api-docs) |
| Health de autorización | [Actuator health](http://localhost:8080/actuator/health) |
| Keycloak | [Consola](http://localhost:8081) |
| PostgreSQL desde el host | `localhost:5432`, base `fincard_db`, usuario `fincard`, contraseña `fincard123` |
| Kafka desde el host / contenedores | `localhost:9092` / `kafka:9093` |

### 6. Crear una tarjeta de demostración

Este SQL crea una tarjeta ficticia con saldo `1000.00` y vencimiento relativo a la fecha de ejecución. Si ya existe, conserva su saldo; no recarga una tarjeta utilizada anteriormente.

```powershell
docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U fincard -d fincard_db -c "INSERT INTO public.cards (card_number, expiration_date, status, available_balance, card_type) VALUES ('DEMO-README-001', CURRENT_DATE + 365, 'ACTIVE', 1000.00, 'DEBIT') ON CONFLICT (card_number) DO NOTHING;"
$cardId = [long](docker compose exec -T postgres psql -U fincard -d fincard_db -tAc "SELECT id FROM public.cards WHERE card_number = 'DEMO-README-001';")
$cardId
```

### 7. Obtener un token y autorizar

```powershell
$tokenResponse = Invoke-RestMethod -Method Post -Uri 'http://localhost:8081/realms/fincard/protocol/openid-connect/token' -ContentType 'application/x-www-form-urlencoded' -Body @{ grant_type = 'client_credentials'; client_id = 'fincard-demo'; client_secret = 'fincard-demo-local-secret' }
$headers = @{ Authorization = "Bearer $($tokenResponse.access_token)" }
$requestId = [guid]::NewGuid().ToString()
$body = @{ requestId = $requestId; cardId = $cardId; amount = 100.00 } | ConvertTo-Json -Compress
$result = Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/transactions/authorize' -Headers $headers -ContentType 'application/json' -Body $body
$result | ConvertTo-Json
```

Resultado esperado para una tarjeta con saldo suficiente; los identificadores varían:

```json
{
  "requestId": "7e4f870c-fc1e-481b-9d87-af3542dfb75b",
  "transactionId": 1,
  "status": "APPROVED",
  "reason": null
}
```

Para comprobar idempotencia, repetir exactamente el mismo cuerpo:

```powershell
Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/transactions/authorize' -Headers $headers -ContentType 'application/json' -Body $body
```

Debe devolver el mismo `transactionId` sin descontar de nuevo. Un `requestId` nuevo representa una operación nueva y puede generar otro débito. Si el token vence, repetir su obtención y reconstruir `$headers`.

### 8. Comprobar saldo, outbox y auditoría

```powershell
docker compose exec -T postgres psql -U fincard -d fincard_db -c "SELECT id, available_balance FROM public.cards WHERE id = $cardId;"
docker compose exec -T postgres psql -U fincard -d fincard_db -c "SELECT id, request_id, amount, status FROM public.transactions WHERE request_id = '$requestId';"
docker compose exec -T postgres psql -U fincard -d fincard_db -c "SELECT event_id, published_at, attempts FROM public.transaction_outbox WHERE transaction_id = $($result.transactionId);"
docker compose exec -T postgres psql -U fincard -d fincard_db -c "SELECT event_id, request_id, transaction_id, status FROM audit.transaction_audit WHERE request_id = '$requestId';"
```

En un entorno nuevo, después de la primera aprobación y su repetición, el saldo debe ser `900.00`, debe haber una transacción y una fila de auditoría. `published_at` se completa después de publicar. La auditoría es eventual: si todavía no aparece, repetir la consulta y revisar los logs; un fallo previo de publicación puede diferir el reintento 30 segundos.

### Configuración relevante

| Variable | Servicio | Valor local en Compose / función |
| --- | --- | --- |
| `SPRING_DATASOURCE_URL` | Autorización | `jdbc:postgresql://postgres:5432/fincard_db` |
| `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | Autorización | Credenciales locales de PostgreSQL |
| `SPRING_DATA_REDIS_HOST` | Autorización | `redis` |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | Autorización | `kafka:9093` |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | Autorización | `http://localhost:8081/realms/fincard` |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI` | Autorización | `http://keycloak:8080/realms/fincard/protocol/openid-connect/certs` |
| `AUDIT_DB_URL`, `AUDIT_DB_USER`, `AUDIT_DB_PASSWORD` | Auditoría | Conexión local a PostgreSQL |
| `KAFKA_BOOTSTRAP_SERVERS` | Auditoría | `kafka:9093` |
| `AUDIT_KAFKA_GROUP_ID` | Auditoría | Opcional; predeterminado `audit-service` |
| `TRANSACTION_EVENTS_TOPIC` | Auditoría | Opcional; predeterminado `transaction-events` |

El tópico de autorización se configura con `app.kafka.topics.transaction-events`; ambos servicios deben usar el mismo nombre. No se necesita un archivo `.env` para este recorrido. Las direcciones `postgres`, `redis`, `kafka` y `keycloak` se resuelven dentro de la red de Compose, no desde el host.

## Contrato de la API

`POST /api/transactions/authorize`, con `Authorization: Bearer <token>` y `Content-Type: application/json`.

| Campo | Validación |
| --- | --- |
| `requestId` | UUID obligatorio; identifica una operación lógica |
| `cardId` | Entero positivo obligatorio |
| `amount` | Decimal positivo obligatorio, hasta 17 dígitos enteros y 2 decimales |

| Resultado | HTTP | Comportamiento |
| --- | --- | --- |
| Aprobación | 200 | `status=APPROVED`, `reason=null`; descuenta saldo y genera evento |
| Rechazo de negocio | 200 | `status=DECLINED`, `reason=TRANSACTION_DECLINED`; no descuenta ni genera evento |
| Repetición con los mismos datos | 200 | Devuelve el resultado persistido, incluso si fue rechazado |
| JSON o campos inválidos | 400 | Rechazo por deserialización o validación |
| Token ausente o inválido | 401 | No permite ejecutar la autorización |
| Tarjeta inexistente | 404 | `{"code":"CARD_NOT_FOUND"}` |
| Identificador reutilizado con otros datos | 409 | `{"code":"REQUEST_ID_CONFLICT"}` |
| Solicitud en procesamiento | 409 | `{"code":"REQUEST_PENDING"}`; reintentar después con los mismos datos |

Las condiciones de tarjeta bloqueada, vencida o saldo insuficiente comparten el motivo genérico `TRANSACTION_DECLINED`. La API actual no incluye moneda ni expone el saldo en la respuesta. Los errores de validación y seguridad no usan necesariamente el formato `{"code": ...}` del manejador de errores de negocio.

## Pruebas

Los comandos siguientes se ejecutan desde la raíz, con JDK 21 y `JAVA_HOME` configurado. El wrapper descarga Maven si hace falta. No existe un POM agregador en la raíz.

### Pruebas sin infraestructura externa

```powershell
.\authorization-service\mvnw.cmd -f authorization-service/pom.xml "-Dtest=AuthorizationHttpTests,AuthorizationJwtTests" test
.\audit-service\mvnw.cmd -f audit-service/pom.xml "-Dtest=TransactionEventListenerTests" test
```

Cubren validación y respuestas HTTP, verificación de JWT y comportamiento del listener de auditoría con dependencias simuladas. No acreditan por sí solas la entrega real por Kafka.

### Pruebas con infraestructura local

Completar primero el arranque y bootstrap descritos arriba. Usar datos locales descartables: estas pruebas crean y eliminan fixtures en la base configurada.

```powershell
$env:FINCARD_RUN_POSTGRES_TESTS = 'true'
$env:AUDIT_DB_PASSWORD = 'fincard123'
try {
    .\authorization-service\mvnw.cmd -f authorization-service/pom.xml test
    if ($LASTEXITCODE -ne 0) { throw 'Fallaron las pruebas de autorización' }
    .\audit-service\mvnw.cmd -f audit-service/pom.xml test
    if ($LASTEXITCODE -ne 0) { throw 'Fallaron las pruebas de auditoría' }
} finally {
    Remove-Item Env:FINCARD_RUN_POSTGRES_TESTS -ErrorAction SilentlyContinue
    Remove-Item Env:AUDIT_DB_PASSWORD -ErrorAction SilentlyContinue
}
```

`AuthorizationIntegrationTests` solo se habilita con `FINCARD_RUN_POSTGRES_TESTS=true`. Comprueba idempotencia, rechazos, débitos concurrentes, prevención de sobregiro y rollback. Las pruebas `contextLoads` arrancan contextos Spring y requieren sus dependencias. Los pasos 7 y 8 constituyen una comprobación manual adicional de extremo a extremo.

Las imágenes Docker se construyen con `-Dmaven.test.skip=true`: construir una imagen correctamente no significa que la suite haya pasado. Los reportes Maven quedan en `target/surefire-reports` de cada módulo.

## Estrategia propuesta para producción

**Esta sección es una propuesta de evolución.** El repositorio no incluye manifiestos de Kubernetes, pipeline de CI/CD, stack de observabilidad ni configuración de alta disponibilidad. Los valores de capacidad y objetivos deben definirse mediante pruebas de carga y requisitos de negocio.

### Escalabilidad con Kubernetes

- Desplegar cada servicio en un `Deployment` independiente, con recursos solicitados y límites medidos. Exponer solo la API mediante un servicio e ingreso con TLS; auditoría no necesita exposición pública.
- Mantener las réplicas de autorización sin sesión. Configurar un HPA con CPU y, tras instrumentación, señales de demanda y latencia. El [HPA de Kubernetes](https://kubernetes.io/docs/concepts/workloads/autoscaling/horizontal-pod-autoscale/) admite métricas de recursos y métricas personalizadas.
- Escalar auditoría según retraso de consumo. En un mismo grupo, el paralelismo efectivo queda limitado por las particiones disponibles; dimensionarlas junto con la capacidad del consumidor y de PostgreSQL.
- Medir contención en tarjetas muy activas y conexiones de base de datos. Agregar réplicas de API no elimina el bloqueo de la fila de saldo ni multiplica la capacidad de escritura de PostgreSQL. Limitar el total de conexiones de los pools de todas las réplicas.
- Separar credenciales y permisos por servicio; evaluar una base independiente para auditoría. Preferir servicios de datos administrados o una operación explícita de alta disponibilidad para PostgreSQL, Kafka y Redis.
- El outbox permite repartir filas entre publicadores con `SKIP LOCKED`, pero su espera síncrona de Kafka conserva conexiones y bloqueos. Medir este costo antes de aumentar réplicas; evaluar un publicador dedicado o CDC si el volumen lo justifica.

### Resiliencia y recuperación

| Fallo | Comportamiento actual | Evolución propuesta |
| --- | --- | --- |
| Redis no disponible | Con la aplicación arrancada, intenta continuar con PostgreSQL al fallar la adquisición; el chequeo de arranque todavía requiere Redis | Eliminar o tolerar el chequeo de arranque, medir la degradación y verificarla con pruebas de fallos |
| Kafka no disponible | El outbox conserva aprobaciones y programa reintentos | Alertar por antigüedad y tamaño de pendientes; definir capacidad y retención |
| Auditoría o su base no disponible | El consumidor reintenta fallos recuperables cada 5 segundos, sin límite configurado | Incorporar política de reintentos, tópico de errores (DLQ), alertas y procedimiento de reproceso |
| Evento inválido | `IllegalArgumentException` se clasifica como no reintentable; no existe una DLQ explícita | Guardar eventos fallidos para investigación y reproceso controlado |
| Caída tras enviar un evento | Puede repetirse la publicación | Conservar deduplicación por `event_id` y probar recuperación |
| PostgreSQL no disponible | No se puede confirmar una autorización | Alta disponibilidad, backups, recuperación a un punto en el tiempo y ensayos de restauración |

Definir probes separadas de arranque, disponibilidad y vida. No usar indiscriminadamente el health agregado para reiniciar procesos por fallos de dependencias; una caída de Kafka no debe impedir aceptar autorizaciones que pueden quedar en el outbox. En auditoría habrá que implementar una señal de salud adecuada, porque hoy no publica un servidor HTTP.

Distribuir réplicas entre nodos o zonas, configurar terminación ordenada y presupuestos de interrupción. Para Kafka, proponer al menos tres brokers, replicación 3 y `min.insync.replicas=2` junto con `acks=all`, ajustándolo a disponibilidad y durabilidad requeridas. Definir RPO/RTO con negocio y ensayar restauración de PostgreSQL y recuperación de auditoría desde eventos retenidos. Ningún objetivo de recuperación está acreditado por el entorno local.

### Observabilidad: logs, métricas y trazas

**Estado actual:** logs de las aplicaciones, Actuator en las dependencias y `/actuator/health` público en autorización. No hay registro Prometheus, dashboards, colector de trazas ni métricas de negocio implementados. La configuración local muestra SQL, detalles de salud y logs de seguridad en nivel `TRACE`.

**Propuesta:** adoptar logs JSON a salida estándar y correlacionar `requestId`, `transactionId`, `eventId` y, cuando exista instrumentación, `traceId`. Propagar el contexto a través del outbox y Kafka. OpenTelemetry ofrece un modelo común para [trazas, métricas y logs](https://opentelemetry.io/docs/concepts/signals/); su incorporación requiere instrumentación y exportación explícitas.

| Señal | Medición propuesta | Utilidad |
| --- | --- | --- |
| API | Tasa de solicitudes, p95/p99 de latencia, errores 5xx y 401 | Detectar degradación técnica y problemas de autenticación |
| Negocio | Aprobaciones, rechazos y conflictos de idempotencia | Distinguir decisiones de negocio de fallos operativos |
| Outbox | Pendientes, edad del evento más antiguo, reintentos y duración de publicación | Detectar auditoría retrasada aunque la API siga respondiendo |
| Kafka/auditoría | Consumer lag, errores y tiempo hasta persistir auditoría | Verificar progreso y dimensionar consumidores |
| PostgreSQL/JVM/Redis | Conexiones, bloqueos, uso de recursos, GC y fallos de acceso | Identificar saturación y causas de latencia |

Usar identificadores de solicitud en logs y trazas, no como etiquetas de métricas de alta cardinalidad. Evitar tokens, secretos y números completos de tarjetas en los logs. Reducir el logging de seguridad y SQL en producción, y restringir detalles de salud y endpoints administrativos.

Una opción de implementación es Micrometer con un registro Prometheus, Grafana para tableros, un almacén central de logs y un backend de trazas mediante OpenTelemetry Collector. Esto requiere agregar dependencias, configuración y reglas de acceso; no basta con exponer una URL. Definir alertas por errores sostenidos, latencia, backlog envejecido y falta de progreso de auditoría, con umbrales derivados de SLO acordados y un procedimiento de atención.

### CI/CD y cambios de esquema

Propuesta de pipeline por pull request y despliegue:

1. Compilar con JDK 21, ejecutar pruebas sin infraestructura y verificar formato y dependencias.
2. Crear PostgreSQL, Redis, Kafka y Keycloak efímeros; aplicar bootstrap y ejecutar pruebas de integración y el recorrido de autorización/auditoría. Añadir casos de Kafka caído, recuperación del outbox y duplicados.
3. Analizar dependencias e imágenes, generar un inventario de componentes (SBOM) y construir imágenes con una etiqueta inmutable del commit. Publicarlas en un registro solo si pasan los controles.
4. Aplicar migraciones Flyway de forma controlada antes del despliegue, con credenciales específicas y compatibilidad con la versión anterior. Usar cambios de expansión y contracción para evitar romper réplicas durante una actualización.
5. Desplegar en staging, comprobar salud y ejecutar smoke tests. Promover el mismo digest a producción con actualización gradual o canary y observar los indicadores definidos.
6. Revertir la imagen si falla la validación. Las migraciones no se deshacen automáticamente al revertir un contenedor: preparar correcciones hacia adelante y un plan de restauración para cambios incompatibles.

Almacenar secretos fuera del repositorio, limitar permisos entre servicios y proteger comunicaciones con TLS. Sustituir Keycloak `start-dev` por una configuración de producción con almacenamiento persistente apropiado, hostname HTTPS y rotación de credenciales. Completar validación de audiencia y autorización de negocio antes de exponer operaciones sobre tarjetas reales.

## Diagnóstico y operación local

```powershell
docker compose ps -a
docker compose logs --tail 150 authorization-service audit-service
docker compose logs --tail 100 postgres kafka keycloak redis
```

| Síntoma | Comprobación y acción |
| --- | --- |
| Docker no conecta al servidor | Iniciar Docker Desktop/Engine y verificar acceso al daemon con `docker version` |
| Puerto ocupado | Detener el proceso que lo ocupa o adaptar los mapeos y direcciones del recorrido; cambiar el puerto de Keycloak requiere alinear el issuer |
| Aplicación termina durante el arranque | Comprobar dependencias y migraciones en logs; una vez disponibles, ejecutar `docker compose restart authorization-service audit-service` |
| `invalid_client` al pedir token | Revisar cliente, secreto, autenticación y cuentas de servicio en el realm `fincard` |
| 401 en autorización | Renovar token y comprobar que su issuer coincide exactamente con `http://localhost:8081/realms/fincard` |
| Tópico desconocido o auditoría vacía | Verificar creación del tópico, bootstrap `kafka:9093`, logs del consumidor y estado del outbox |
| `REQUEST_ID_CONFLICT` | Usar el mismo identificador solo con la tarjeta y monto originales |
| `REQUEST_PENDING` | Esperar y reintentar con el mismo cuerpo; no generar otra operación para resolver un timeout |
| Saldo distinto al ejemplo | La tarjeta de demostración conserva el saldo entre ejecuciones y los volúmenes persisten datos |
| Error de checksum de Flyway | Revisar si se modificó una migración ya aplicada; añadir migraciones nuevas en vez de alterar el historial |

Inspección de pendientes y retraso del consumidor:

```powershell
docker compose exec -T postgres psql -U fincard -d fincard_db -c "SELECT COUNT(*) AS pending, MIN(created_at) AS oldest_pending FROM public.transaction_outbox WHERE published_at IS NULL;"
docker compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9093 --describe --group audit-service
```

Para detener el entorno conservando datos:

```powershell
docker compose down
```

Para eliminar también **todos los volúmenes y datos locales de este proyecto**, incluidas tarjetas, auditoría, Kafka y configuración de Keycloak:

```powershell
docker compose down -v
```

Después de eliminar volúmenes, repetir desde el paso 2, incluida la creación del tópico y de los recursos de Keycloak.
