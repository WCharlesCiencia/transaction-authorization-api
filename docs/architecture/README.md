# Arquitectura de FinCard

FinCard autoriza transacciones con tarjeta, registra el resultado y envía las aprobaciones a auditoría. Estos diagramas C4 describen la implementación del repositorio.

| Nivel | Pregunta | Diagrama |
| --- | --- | --- |
| 1. Contexto | ¿Quién usa FinCard y de qué sistema de identidad depende? | [Contexto](c4-level-1-context.md) |
| 2. Contenedores | ¿Qué procesos y almacenes intervienen y cómo se comunican? | [Contenedores](c4-level-2-containers.md) |
| 3. Componentes | ¿Cómo procesa y publica una autorización el servicio principal? | [Componentes](c4-level-3-components.md) |

Los recuadros delimitan el sistema o el servicio bajo estudio. Las flechas indican la dirección de una llamada o del flujo de datos. Los diagramas usan bloques Mermaid `flowchart`, visibles en GitHub o en un editor Markdown con soporte Mermaid.
