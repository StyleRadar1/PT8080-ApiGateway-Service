[⬅ Volver al README Principal](../../README.md)

---

## 4. EVENTOS QUE PUBLICA Y CONSUME / CONTRATOS DE INTEGRACIÓN

### Contrato de Integración y Dependencia Externa: Identidad de Almacén (`X-Almacen-Id`)

> [!WARNING]
> **Dependencia Pendiente Externa (Equipo Auth / Catálogo · Bloqueante para edición y retiro de prendas):**
> 1. **Comportamiento Actual de Catálogo:** Los endpoints de edición y retiro de prendas en `ms-item-catalogo` (`PUT /api/v1/prendas/{id}`, `DELETE /api/v1/prendas/{id}`) continúan exigiendo la cabecera `X-Almacen-Id` con formato UUID o el rol administrador. La validación interna de titularidad contra `ms-tiendas` a partir de `X-User-Id` **aún no está implementada** en el código de Catálogo y queda registrada como tarea pendiente de dicho equipo para el próximo sprint.
> 2. **Comportamiento Actual de Auth:** El servicio `ms-auth` emite tokens JWT con `sub` (ID numérico del usuario) y lista de `roles` (`ADMIN_ALMACEN`, `COMPRADOR`, etc.), pero **no incluye** los claims `almacenId` ni `storeId`, ya que la persistencia y relación de almacenes reside en `ms-tiendas`.
> 3. **Política Inmutable del Gateway (Zero Trust / Anti-Spoofing):** El Gateway sanea y elimina de forma estricta cualquier cabecera `X-Almacen-Id`, `X-User-Id`, `X-User-Role` y `X-User-Roles` enviada directamente por el cliente externo. El Gateway **no debe inventar identificadores de almacén ni confiar en cabeceras de cliente**.
> 4. **Contrato Requerido a los Equipos Externos para Desbloquear la Integración:**
>    - **Opción 1 (ms-auth):** Que `ms-auth` incorpore en el payload del JWT el claim firmado `"almacenId": "<UUID>"` al autenticar usuarios con rol `ADMIN_ALMACEN`. El Gateway ya tiene implementada y verificada la extracción de este claim en formato UUID y su inyección downstream hacia `X-Almacen-Id`.
>    - **Opción 2 (ms-catalogo):** Que `ms-item-catalogo` elimine la dependencia estricta de `X-Almacen-Id` y valide la titularidad consultando a `ms-tiendas` con el `X-User-Id` seguro inyectado por el Gateway, o que reciba el almacén por la ruta (`/api/v1/almacenes/{almacenId}/prendas`).
> 5. **Prueba de Integración Automatizada:** En el Gateway, este comportamiento está formalmente probado mediante `GatewayAlmacenesRoutingAndRolesIntegrationTest.shouldDeriveAlmacenIdOnlyFromSignedClaim`, garantizando que solo claims válidos firmados generen la cabecera y que las cabeceras externas no confiables sean descartadas.

### Validación Pendiente en Ambiente QA: Consulta de Prendas (`GET /api/v1/prendas`)

> [!NOTE]
> **Seguimiento Post-Merge en QA:**
> En el código y en las pruebas del Gateway se corrigió el enrutamiento y la apertura pública de `GET /api/v1/prendas`. Queda registrada como pendiente la verificación funcional en vivo en el entorno real de QA una vez desplegado el PR #3 en `develop`, para confirmar de manera definitiva con el equipo de Catálogo que el error 500 previamente observado quedó completamente subsanado y responde 200 OK de extremo a extremo.

---

### Topología de Eventos y Naturaleza Stateless (ADR-003)

El **API Gateway** opera como un componente de infraestructura perimetral **estrictamente Stateless y Reactivo** (ADR-003). Por diseño arquitectónico y de acuerdo con los estándares de la industria (Spring Cloud Gateway, Netflix Zuul, AWS API Gateway), el Gateway **no se suscribe como consumidor a colas AMQP de RabbitMQ** para persistir estados ni procesa eventos de negocio asíncronos en colas persistentes.

Sin embargo, el Gateway interactúa con el ecosistema de eventos y observabilidad a través de:

1. **Eventos Perimetrales de Ciclo de Vida HTTP (Filtros Reactivos):**
   * **`RequestReceivedEvent` / `CorrelationAssigned`:** Intercepción de solicitud entrante en `GlobalRequestLoggingFilter` asignando y propagando un identificador de trazabilidad distribuida `X-Correlation-Id` (UUIDv4).
   * **`JwtTokenValidated`:** Inspección en memoria por `JwtTokenValidatorFilter` validando la firma HMAC/RSA del token y propagando las cabeceras `X-User-Id`, `X-User-Role` y `X-User-Roles` hacia la red interna.
   * **`CircuitBreakerTransitionEvent`:** Eventos de conmutación de estado emitidos por Resilience4j (`CLOSED`, `OPEN`, `HALF_OPEN`) redirigiendo peticiones degradadas hacia `FallbackController` (`RFC 7807`).

2. **Propagación Transparente de Eventos hacia RabbitMQ:**
   * Las solicitudes HTTP enrutadas a microservicios como `ms-probador-virtual` (:8500) o `ms-catalogo` (:8084) disparan la publicación de eventos en el exchange central `styleradar.exchange` (e.g. `foto.subida`, `prenda.procesada`). El Gateway garantiza el paso transparente de la cabecera `X-Correlation-Id` para que los logs de RabbitMQ mantengan la trazabilidad distribuida end-to-end.

3. **Políticas de Seguridad Perimetral Centralizadas:**
   * **Manejo Unificado de CORS:** Permite peticiones asíncronas desde los clientes web y móviles (`localhost:3000`, `localhost:4200`, `localhost:5173`, dominios `*.styleradar.co` y Azure), admitiendo credenciales seguras y los métodos `GET, POST, PUT, DELETE, PATCH, OPTIONS`.
   * **Trazabilidad Distribuida:** Inyección bidireccional de `X-Correlation-Id` en cabeceras de request y response.

---

[⬅ Volver al README Principal](../../README.md)
