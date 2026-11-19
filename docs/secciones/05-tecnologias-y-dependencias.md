[⬅ Volver al README Principal](../../README.md)

---

## 5. TECNOLOGÍAS Y DEPENDENCIAS

| Capa / Componente | Tecnología | Versión | Justificación Técnica |
| :--- | :--- | :---: | :--- |
| **Lenguaje Core** | Java LTS | `21` | Soporte nativo para hilos virtuales y concurrencia optimizada en Netty. |
| **Framework Base** | Spring Boot | `3.2.5` | Ecosistema empresarial base para microservicios y soporte nativo de Actuator. |
| **Motor de Gateway** | Spring Cloud Gateway | `2023.0.3` | Enrutador perimetral reactivo no bloqueante sobre Project Reactor y Netty. |
| **Tolerancia a Fallos** | Resilience4j Reactor | `2.1.0` | Circuit breaker no bloqueante para aislar microservicios caídos sin saturar hilos. |
| **Seguridad JWT** | JJWT (io.jsonwebtoken) | `0.12.5` | Decodificación y validación de tokens Bearer criptográficos a nivel perimetral. |
| **Documentación API** | SpringDoc WebFlux UI | `2.5.0` | Agregador OpenAPI y Swagger interactivo para entornos reactivos. |
| **Pruebas Automatizadas** | JUnit 5 + WebTestClient | `5.10.2` | Suite de pruebas reactivas de enrutamiento y filtros sin inicializar servidores externos. |
| **Cobertura de Código** | JaCoCo Maven Plugin | `0.8.11` | Verificación automatizada de cobertura mínima exigida (≥ 85%). |

---

[⬅ Volver al README Principal](../../README.md)
