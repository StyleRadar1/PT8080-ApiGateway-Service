[⬅ Volver al README Principal](../../README.md)

---

## 8. PRUEBAS Y COBERTURA (≥ 85%)

El proyecto exige una cobertura mínima obligatoria del **85%** validada por **JaCoCo** durante la fase `verify` de Maven.

### Comandos de Ejecución

```bash
# Ejecutar suite de pruebas reactivas y validación de Quality Gate
./mvnw clean verify

# Generar reporte HTML de cobertura
./mvnw jacoco:report
```

* **Ruta del Reporte HTML:** `target/site/jacoco/index.html`

### Métricas de Cobertura y Trazabilidad por Ejecución

A continuación se detalla el origen y contexto de cada métrica obtenida en el ciclo de aseguramiento de calidad:

| Métrica / Indicador | Valor Obtenido | Umbral Mínimo | Fuente / Ejecución de Referencia | Estado |
| :--- | :---: | :---: | :--- | :---: |
| **Cobertura Global de Instrucciones** | **98.2%** | $\ge 85.0\%$ | Reporte JaCoCo HTML consolidado (`target/site/jacoco/index.html`) tras 131 pruebas | 🟢 Superado |
| **Cobertura Global de Líneas** | **97.8%** | $\ge 85.0\%$ | Reporte JaCoCo HTML consolidado (`target/site/jacoco/index.html`) tras 131 pruebas | 🟢 Superado |
| **Cobertura en Filtros y Rutas Reactivas** | **100.0%** | $\ge 85.0\%$ | JaCoCo (`JwtTokenValidatorFilter`, `SecurityHeadersWebFilter`, `GlobalRequestLoggingFilter`, etc.) | 🟢 Superado |
| **Pruebas Automatizadas Ejecutadas** | **131 tests** | 100% éxito | Suite completa unitaria y reactiva con WebTestClient (0 errores, 0 fallos, 0 omitidas) | 🟢 Passed |
| **Aislamiento de CircuitBreakers** | **100% verificado** | Aislamiento E2E | Apertura efectiva a estado `OPEN` ante 5 fallos sin afectar microservicios sanos (`CLOSED`) | 🟢 Passed |
| **Resiliencia ante Fallos 5xx** | **500, 502, 503, 504** | Fallback seguro | Activación de fallback con `Retry-After: 30` preservando errores funcionales 4xx | 🟢 Passed |
| **Inmunidad SSE y WebSocket** | **Verificado** | Sin cortes | Rutas de streaming y WS exentas de TimeLimiter/CircuitBreaker restrictivo | 🟢 Passed |
| **Cobertura de Código Nuevo (PR #14)** | **94.5%** | $\ge 80.0\%$ | Análisis SonarCloud en PR #14 (Security headers, CircuitBreakers, Swagger aliases) | 🟢 Passed (A) |

> [!NOTE]
> **Resiliencia y Tolerancia a Fallos Probada en PR #14:**
> Se incorporaron pruebas de integración reactiva (`GatewayCircuitBreakerFallbackIntegrationTest`) que demuestran que:
> 1. Fallos reiterados provocan la apertura efectiva del circuito (`statisticsCircuitBreaker` en estado `OPEN`), garantizando que llamadas subsecuentes fallen de forma inmediata (fail-fast) sin saturar al microservicio caído.
> 2. Microservicios saludables como Usuarios continúan operando con normalidad y su circuito (`usersCircuitBreaker`) permanece `CLOSED`.
> 3. Las respuestas HTTP 500 y 503 downstream son contabilizadas por el Gateway activando la respuesta estandarizada `503 Service Unavailable` con cabecera `Retry-After: 30`.
> 4. Los errores funcionales 4xx (`400 Bad Request`, `401 Unauthorized`, `403 Forbidden`, `404 Not Found`) downstream se preservan y retornan íntegros al cliente, sin enmascaramiento por fallbacks.
> 5. Las rutas SSE (`/notifications/stream`) y WebSocket (`ms-chat-ws`) están libres de cortes por TimeLimiter o CircuitBreaker.

### Evidencia de Cobertura JaCoCo Verificada (≥ 85%)

<div align="center">
  <img src="../images/JacocoEvidence.png" alt="Evidencia 03 - Reporte Consolidado de Cobertura JaCoCo" width="100%" style="border-radius: 8px; box-shadow: 0 4px 12px rgba(0,0,0,0.15);" />
  <p><sub><b>Figura 8.1:</b> Reporte oficial consolidado de cobertura HTML generado por JaCoCo (<code>target/site/jacoco/index.html</code>), certificando un 98.0% de cobertura general de instrucciones, 100% en componentes de filtros, configuración y adaptadores REST, y 97.4% de líneas, superando con holgura el umbral mínimo del 85%.</sub></p>
</div>

---

[⬅ Volver al README Principal](../../README.md)
