[⬅ Volver al README Principal](../../README.md)

---

## 11. SEGURIDAD Y ANÁLISIS OWASP TOP 10:2025

El microservicio implementa las directrices oficiales de seguridad perimetral, saneamiento estricto de cabeceras de usuario (`X-User-Id`, `X-User-Role`, `X-User-Roles`) y protección contra ataques OWASP Top 10:2025.

* **Documento Técnico Oficial:** [../security/SR_PT8080_Seguridad_y_OWASP_2025_Gateway.md](../security/SR_PT8080_Seguridad_y_OWASP_2025_Gateway.md)
* **Puntos de Control Principales:**
  1. **Header Stripping (A01 - Broken Access Control):** Eliminación perimetral de cabeceras de identidad recibidas de clientes no autenticados.
  2. **Stateless JWT (A02/A07):** Firma HMAC-SHA256 con claves `>= 256 bits` inyectadas dinámicamente vía `${JWT_SECRET_BASE64}`.
  3. **Resilience4j Circuit Breakers (A04):** Aislamiento de caídas hacia el Probador Virtual y Donaciones con respuestas normalizadas RFC 7807.
  4. **Trazabilidad (A09):** Inyección no bloqueante de `X-Correlation-Id` en todas las transacciones HTTP.

---

[⬅ Volver al README Principal](../../README.md)
