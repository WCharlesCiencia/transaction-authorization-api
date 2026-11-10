# C4 nivel 1 · Contexto del sistema

El diagrama sitúa a FinCard entre el consumidor de la API y el proveedor de identidad.

```mermaid
flowchart LR
    cliente["Cliente de la API"]
    identidad["Proveedor de identidad · Keycloak"]
    subgraph limite ["Límite del sistema"]
        fincard["FinCard · Autorización de transacciones y auditoría"]
    end
    cliente -->|"Obtiene un JWT mediante OAuth2"| identidad
    cliente -->|"Solicita una autorización por HTTP con JWT"| fincard
    fincard -->|"Consulta las claves públicas para validar el JWT"| identidad
```

El cliente representa cualquier consumidor autorizado de la API. Envía el identificador de la tarjeta, el importe y un identificador de solicitud, y recibe el resultado de la autorización. FinCard registra la decisión y audita de forma asíncrona las transacciones aprobadas.

Keycloak queda fuera de la frontera funcional de FinCard como proveedor de identidad, aunque se despliega con el mismo archivo Compose. El cliente dispone de un JWT emitido por el realm `fincard`.
