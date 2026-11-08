# Autorización de transacciones

`POST /api/transactions/authorize` recibe un UUID, el ID de tarjeta y un importe positivo compatible con `NUMERIC(19,2)`.

```json
{"requestId":"b2debb7a-3db8-4b64-a799-0b7a939a95df","cardId":1,"amount":300000.00}
```

Una decisión procesada devuelve HTTP 200 con `requestId`, `transactionId`, `status` y `reason`. Los rechazos de negocio se guardan como `DECLINED` con razón genérica `TRANSACTION_DECLINED`, porque la función devuelve un booleano. Una entrada inválida devuelve 400, una tarjeta inexistente 404 y un UUID reutilizado con otros datos 409.

Flyway añade V3 y V4 sobre el esquema existente. La reserva del UUID usa el índice único parcial de V3 y PostgreSQL resuelve los intentos simultáneos mediante `ON CONFLICT`. En `READ COMMITTED`, el intento que pierde la reserva lee el resultado confirmado en una consulta nueva. V4 usa una función PL/pgSQL y comprueba estado, vencimiento y saldo en el mismo `UPDATE`. La reserva, el débito y la decisión se confirman juntos. El punto de extensión `ApprovedTransactionEventPublisher` queda disponible para una futura integración con publicación fiable después de la confirmación.

## Pruebas

Desde `authorization-service`, ejecuta las pruebas HTTP sin servicios externos.

```powershell
mvn '-Dtest=AuthorizationHttpTests,AuthorizationJwtTests' test
```

Para verificar PostgreSQL y aplicar las migraciones, usa una base de pruebas dedicada y configura sus credenciales antes de ejecutar Maven.

```powershell
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://localhost:5432/fincard_test'
$env:SPRING_DATASOURCE_USERNAME = 'fincard_test'
$env:SPRING_DATASOURCE_PASSWORD = '<contraseña local>'
$env:FINCARD_RUN_POSTGRES_TESTS = 'true'
mvn clean package
```

En el entorno restringido de Codex, Maven intentó usar `C:\.m2\repository`. Al apuntar a la caché del usuario, el fallo de `netty-handler` resultó ser un `AccessDeniedException` de Windows al resolver la ruta del JAR durante el cierre del archivo por `javac`. Ejecuta Maven desde tu terminal con acceso a tu caché habitual o indica un repositorio local escribible con `-Dmaven.repo.local=<ruta>`.
