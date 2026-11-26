[⬅ Volver al README Principal](../../README.md)

---

## 11. SEGURIDAD Y ANÁLISIS OWASP TOP 10:2025

El microservicio implementa las directrices oficiales de seguridad perimetral, saneamiento estricto de cabeceras de usuario (`X-User-Id`, `X-User-Role`, `X-User-Roles`, `X-Almacen-Id`, `X-Store-Id`, `X-Foundation-Id`) y protección contra ataques OWASP Top 10:2025.

* **Documento Técnico Oficial:** [../security/SR_PT8080_Seguridad_y_OWASP_2025_Gateway.md](../security/SR_PT8080_Seguridad_y_OWASP_2025_Gateway.md)
* **Puntos de Control Principales:**
  1. **Header Stripping & Anti-Spoofing (A01 - Broken Access Control):** Eliminación perimetral incondicional de cabeceras `X-User-*`, `X-Almacen-Id`, `X-Store-Id` y `X-Foundation-Id` inyectadas por el cliente antes del enrutamiento.
  2. **Stateless JWT Criptográfico (A02/A07):** Firma HMAC-SHA256 con claves `>= 256 bits` inyectadas vía `${JWT_SECRET_BASE64}`. Rechazo con `401 Unauthorized` ante firmas inválidas o tokens expirados.
  3. **BUG-005 (Aislamiento de Identidad Organizacional):** Extracción de `storeId` y `foundationId` exclusivamente desde claims firmados de tokens JWT. Se elimina cualquier fallback inseguro a `sub` (subject del JWT). **Nota de coordinación con NQS (Pagos y Estadísticas):** Se coordinó que los servicios downstream no recurran a `sub` cuando falte el identificador real de la organización.
  4. **BUG-011 (Content-Security-Policy & Cabeceras Perimetrales Inviolables):** Inyección forzada de `Content-Security-Policy` estricto para APIs (`default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`) y compatible para Swagger UI (`default-src 'self'; script-src 'self' 'unsafe-inline'; ...; frame-ancestors 'self'`) junto con `X-Frame-Options: SAMEORIGIN` (Swagger) y `DENY` (APIs). Las cabeceras perimetrales se fuerzan mediante `headers.set(...)` para impedir que respuestas downstream debiliten la política de seguridad perimetral.
  5. **Resilience4j Circuit Breakers Independientes (A04):** Aislamiento de fallos mediante circuitos dedicados por microservicio (`authCircuitBreaker`, `storesCircuitBreaker`, `catalogoCircuitBreaker`, `inventoryCircuitBreaker`, `tryonCircuitBreaker`, `donacionesCircuitBreaker`, etc.), evitando efecto dominó ante caídas parciales. Respuestas de contingencia en `503 Service Unavailable` con cabecera `Retry-After: 30` y preservación íntegra de errores funcionales 4xx (`400`, `401`, `403`, `404`).
  6. **Observabilidad y CORS (A09):** Inyección no bloqueante de `X-Correlation-Id` y `X-Request-Id`, exposición en `exposedHeaders` de CORS, y soporte sin interrupciones para SSE (`/api/v1/users/*/notifications/stream`) y WebSockets (`/ws/**`).

---

[⬅ Volver al README Principal](../../README.md)
