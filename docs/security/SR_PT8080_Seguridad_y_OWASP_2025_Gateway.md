# Matriz de Seguridad Perimetral, Mitigación OWASP Top 10:2025 y Hardening de Enrutamiento

- **Componente:** `PT8080-ApiGateway-Service` (API Gateway Perimetral · Puerto `8080`)
- **Squad:** Portal8080 (`PT8080`)
- **Arquitectura:** Spring Cloud Gateway Reactivo sobre Netty / Project Reactor (Non-blocking I/O)
- **Alcance:** Control de acceso perimetral, saneamiento de cabeceras, resiliencia (Circuit Breaker Resilience4j), agregación Swagger OpenAPI y separación de ambientes (`DEV`, `QA`, `PROD`).
- **Estado de Calidad:** Pruebas de integración reactivas en verde · JaCoCo Line Coverage > 90% · SonarCloud Quality Gate A.

---

## 1. Resumen Ejecutivo de Seguridad Perimetral

El API Gateway es la **única puerta de entrada pública** hacia la malla de microservicios de StyleRadar. Por ende, actúa como la primera línea de defensa perimetral implementando el principio de **Defensa en Profundidad (Defense-in-Depth)** y **Confianza Cero (Zero Trust)**:

1. **Saneamiento Obligatorio de Identidad (Header Cleansing):**
   * El cliente **NUNCA** puede inyectar arbitrariamente cabeceras de identidad como `X-User-Id` o `X-User-Role`.
   * El filtro perimetral `JwtTokenValidatorFilter` elimina explícitamente cualquier cabecera `X-User-Id` y `X-User-Role` que provenga del exterior, previniendo suplantación de identidad (*Header Spoofing* o *Privilege Escalation*).
   * Dichas cabeceras solo se generan y reinyectan hacia los microservicios internos una vez que el token Bearer JWT ha sido criptográficamente verificado con la clave compartida (`JWT_SECRET_BASE64`).

2. **Validación Criptográfica y Stateless JWT:**
   * Las peticiones hacia rutas protegidas que contengan tokens malformados, manipulados o expirados son rechazadas inmediatamente en el perímetro con **HTTP 401 Unauthorized** en formato estándar **RFC 7807 (ProblemDetail)**, impidiendo que el tráfico malicioso alcance los microservicios downstream.
   * La clave secreta HMAC-SHA256 se inyecta exclusivamente mediante la variable de entorno `JWT_SECRET_BASE64`, prohibiendo claves fijas quemadas en el código fuente o repositorios.

3. **Resiliencia y Aislamiento de Fallos (Circuit Breakers):**
   * Implementación de disyuntores reactivos (`ReactiveCircuitBreaker`) con Resilience4j hacia los microservicios críticos (ej. `tryonCircuitBreaker` para `ms-probador-virtual` :8500 y `donacionesCircuitBreaker` para `ms-segunda-vida-donaciones` :8520).
   * Ante latencias excesivas o caídas del backend, el Gateway conmuta a rutas de contingencia (`/fallback/probador`, `/fallback/general`) respondiendo **HTTP 503 Service Unavailable** estructurado, evitando efecto cascada (*Cascading Failure*).

4. **Trazabilidad Global Distribuida (`X-Correlation-Id`):**
   * El filtro `GlobalRequestLoggingFilter` asigna o propaga de forma transparente un identificador único de correlación (`X-Correlation-Id` en formato UUID v4) en cada petición y respuesta HTTP, garantizando la trazabilidad distribuida extremo a extremo sin exponer información confidencial en los registros.

---

## 2. Matriz de Mitigación OWASP Top 10:2025 (`A01` a `A10`)

| Categoría OWASP Top 10:2025 | Riesgo Específico en el API Gateway | Mecanismo de Mitigación Implementado en `PT8080-ApiGateway-Service` | Clases y Archivos de Implementación |
| :--- | :--- | :--- | :--- |
| **A01:2025 – Broken Access Control** | Suplantación de identidad forjando cabeceras `X-User-Id` o `X-User-Role` desde el frontend, o acceso a rutas de administración saltándose el control de roles. | 1. `JwtTokenValidatorFilter.mutate()` despoja obligatoriamente las cabeceras `X-User-Id` y `X-User-Role` enviadas por el cliente antes de procesar el request.<br>2. Solo cuando la firma criptográfica HMAC-SHA256 del token es válida, el Gateway extrae el `sub` y los claims de roles y los agrega de forma segura al request downstream.<br>3. Peticiones a endpoints no públicos con token inválido son abortadas con HTTP 401. | `JwtTokenValidatorFilter.java`<br>`GatewayRoutingAndSecurityIntegrationTest.java` |
| **A02:2025 – Cryptographic Failures** | Exposición de la clave simétrica JWT en texto claro en `application.yml`, o uso de claves débiles menores a 256 bits. | 1. Eliminación total de claves quemadas; soporte exclusivo mediante variables de entorno dinámicas `${JWT_SECRET_BASE64:${JWT_SECRET:}}`.<br>2. Verificación de decodificación Base64 con longitud mínima de 32 bytes (256 bits) para garantizar seguridad contra ataques de fuerza bruta en HMAC-SHA256. | `application.yml`<br>`application-qa.yml`<br>`application-prod.yml`<br>`JwtTokenValidatorFilter.java` |
| **A03:2025 – Injection** | Inyección de rutas maliciosas, CRLF injection en cabeceras o parámetros maliciosos dirigidos a la reescritura de URLs internas. | 1. Predicados estrictos de Spring Cloud Gateway basados en patrones seguros (`Path=/api/v1/try-on/**`, `Path=/v3/api-docs/probador`).<br>2. Reescritura determinística de rutas mediante filtros nativos (`SetPath=/v3/api-docs`), previniendo desbordamientos o manipulación de parámetros de consulta. | `application.yml`<br>`GlobalRequestLoggingFilter.java` |
| **A04:2025 – Insecure Design** | Saturación de servicios internos por saturación de peticiones o caída en cascada ante colapso de un microservicio de negocio. | 1. Patrón Circuit Breaker reactivo con Resilience4j configurado en `application.yml`.<br>2. Redirección automática a fallbacks controlados (`FallbackController`) en caso de timeout, errores HTTP 5xx reiterados o rechazos por disyuntor abierto. | `application.yml`<br>`FallbackController.java`<br>`FallbackControllerTest.java` |
| **A05:2025 – Security Misconfiguration** | Exposición pública de endpoints administrativos (`/actuator/**`), stacktraces detallados en errores HTTP, o CORS permisivo (`*`) con credenciales. | 1. Configuración de CORS estricto en `spring.cloud.gateway.globalcors` restringido a dominios oficiales (`*.styleradar.co`, `*.azurewebsites.net`, `localhost`).<br>2. Endpoints de Actuator restringidos: en PROD `show-details: never`.<br>3. Manejador global de excepciones reactivas `GlobalErrorExceptionHandler` que captura errores no controlados y responde bajo estándar RFC 7807 sin filtrar volcados de memoria ni tecnologías internas. | `application.yml`<br>`application-prod.yml`<br>`GlobalErrorExceptionHandler.java` |
| **A06:2025 – Vulnerable and Outdated Components** | Uso de librerías vulnerables en el ecosistema reactivo o contenedores Docker base con vulnerabilidades críticas conocidas. | 1. Uso de **Java 21 LTS**, **Spring Boot 3.2.5**, **Spring Cloud 2023.0.3** y **JJWT 0.12.5**.<br>2. Contenedor Docker multi-stage basado en la imagen minimalista `eclipse-temurin:21-jre-alpine`.<br>3. Ejecución como usuario sin privilegios `gateway:styleradar` (`USER gateway`).<br>4. Escaneo continuo de calidad y vulnerabilidades con SonarCloud en cada commit. | `pom.xml`<br>`Dockerfile`<br>`.github/workflows/ci.yml` |
| **A07:2025 – Identification and Authentication Failures** | Aceptación de tokens JWT caducados, sin firma o firmados con algoritmos inseguros (`alg: none`). | 1. Verificación obligatoria de la firma del token mediante el parser seguro de JJWT (`Jwts.parser().verifyWith(secretKey).build()`).<br>2. Verificación automática de la marca de tiempo de expiración (`exp`), rechazando tokens vencidos con log de advertencia perimetral y código HTTP 401. | `JwtTokenValidatorFilter.java`<br>`JwtTokenValidatorFilterTest.java` |
| **A08:2025 – Software and Data Integrity Failures** | Despliegue de imágenes desactualizadas en Azure, o falta de trazabilidad del commit fuente en ejecución. | 1. El `Dockerfile` incorpora el argumento `ARG GIT_COMMIT_SHA` durante el empaquetado, generando `git.properties` dentro de `src/main/resources`.<br>2. El endpoint `/actuator/info` expone el commit SHA exacto en ejecución.<br>3. Los pipelines `ci-qa.yml` y `ci-prod.yml` ejecutan smoke tests automáticos tras el despliegue en Azure, comparando el SHA retornado por `/actuator/info` contra el commit `${{ github.sha }}` antes de dar por exitoso el release. | `Dockerfile`<br>`.github/workflows/ci-qa.yml`<br>`.github/workflows/ci-prod.yml` |
| **A09:2025 – Security Logging and Monitoring Failures** | Ausencia de correlación en peticiones entre el cliente, el Gateway y los microservicios backend ante incidentes de seguridad. | 1. Filtro reactivo `GlobalRequestLoggingFilter` con orden de precedencia máxima (`Ordered.HIGHEST_PRECEDENCE`).<br>2. Generación obligatoria de `X-Correlation-Id` para cada flujo y registro del tiempo de procesamiento (ms) y código HTTP de salida.<br>3. Omisión intencional de cuerpos de mensajes confidenciales y tokens JWT en los registros de logging. | `GlobalRequestLoggingFilter.java`<br>`GlobalRequestLoggingFilterTest.java` |
| **A10:2025 – Server-Side Request Forgery (SSRF)** | Peticiones arbitrarias forzadas hacia redes internas a través de rutas manipuladas del Gateway. | 1. Desactivación explícita del Discovery Locator dinámico (`spring.cloud.gateway.discovery.locator.enabled: false`).<br>2. Todas las rutas hacia microservicios downstream están rigurosamente declaradas y tipadas en los archivos de configuración (`application.yml`, `application-qa.yml`, `application-prod.yml`), impidiendo el enrutamiento a hosts arbitrarios o servicios de metadatos de la nube (`169.254.169.254`). | `application.yml`<br>`application-qa.yml`<br>`application-prod.yml` |

---

## 3. Matriz Oficial de Enrutamiento y Puertos por Servicio

| Microservicio | Paquete / Squad | Puerto Local | Prefijo de Ruta Base en Gateway (:8080) | URL QA en Azure | URL PROD en Azure |
| :--- | :--- | :---: | :--- | :--- | :--- |
| **ms-auth** | P1: Identidad | `8100` | `/api/v1/auth/**` | `https://styleradar-backend-qa.azurewebsites.net` | `https://styleradar-backend-prod.azurewebsites.net` |
| **ms-usuarios** | P1: Identidad | `8120` | `/api/v1/usuarios/**`, `/api/v1/admin/**` | `https://styleradar-backend-qa.azurewebsites.net` | `https://styleradar-backend-prod.azurewebsites.net` |
| **ms-tiendas** | P1: Identidad | `8140` | `/api/v1/tiendas/**`, `/api/v1/almacenes/**` | `https://styleradar-tiendas-qa.azurewebsites.net` | `https://styleradar-tiendas-prod.azurewebsites.net` |
| **ms-inventario** | P2: Catálogo | `8200` | `/api/v1/inventario/**` | `https://styleradar-backend-qa.azurewebsites.net` | `https://styleradar-backend-prod.azurewebsites.net` |
| **ms-item-catalogo** | P2: Catálogo | `8220` | `/api/v1/catalogo/**`, `/api/v1/prendas/**` | `https://styleradar-backend-qa.azurewebsites.net` | `https://styleradar-backend-prod.azurewebsites.net` |
| **ms-geolocalizacion**| P2: Catálogo | `8240` | `/api/v1/geolocalizacion/**` | `https://styleradar-backend-qa.azurewebsites.net` | `https://styleradar-backend-prod.azurewebsites.net` |
| **ms-playlist** | P2: Catálogo | `8260` | `/api/v1/playlist/**` | `https://styleradar-backend-qa.azurewebsites.net` | `https://styleradar-backend-prod.azurewebsites.net` |
| **ms-notificaciones**| P3: Mensajería | `8300` | `/api/v1/notificaciones/**` | `https://styleradar-backend-qa.azurewebsites.net` | `https://styleradar-backend-prod.azurewebsites.net` |
| **ms-chat** | P3: Mensajería | `8320` | `/api/v1/conversaciones/**`, `/api/v1/chat/**` | `https://styleradar-backend-qa.azurewebsites.net` | `https://styleradar-backend-prod.azurewebsites.net` |
| **ms-pagos** | P4: Pagos | `8400` | `/api/v1/pagos/**` | `https://styleradar-backend-qa.azurewebsites.net` | `https://styleradar-backend-prod.azurewebsites.net` |
| **ms-estadisticas** | P4: Pagos | `8420` | `/api/v1/estadisticas/**` | `https://styleradar-backend-qa.azurewebsites.net` | `https://styleradar-backend-prod.azurewebsites.net` |
| **ms-probador-virtual** | P5: Experiencia | `8500` | `/api/v1/try-on/**`, `/api/v1/probador/**` | `https://styleradar-virtualtryon-qa.azurewebsites.net` | `https://styleradar-virtualtryon-prod.azurewebsites.net` |
| **ms-donaciones** | P5: Experiencia | `8520` | `/api/v1/donaciones/**`, `/api/v1/segunda-mano/**` | `https://styleradar-donaciones-qa-jmunar.azurewebsites.net` | `https://styleradar-donaciones-prod-jmunar.azurewebsites.net` |
| **api-gateway** | P5: Perímetro | `8080` | `/actuator/**`, `/v3/api-docs/**`, `/swagger-ui/**` | `https://styleradar-gateway-qa.azurewebsites.net` | `https://styleradar-gateway-prod.azurewebsites.net` |

---

## 4. Agregación de Documentación OpenAPI / Swagger

El API Gateway centraliza los contratos JSON de OpenAPI de los microservicios sin requerir que los clientes consulten múltiples puertos o dominios:

1. **Transformación de Rutas:**
   * `/v3/api-docs/probador` $\longrightarrow$ Reescrito con `SetPath=/v3/api-docs` hacia el puerto `8500` (`TRYON_SERVICE_URL`).
   * `/v3/api-docs/donaciones` $\longrightarrow$ Reescrito con `SetPath=/api/v1/v3/api-docs` hacia el puerto `8520` (`DONACIONES_SERVICE_URL`).
   * `/v3/api-docs/auth` $\longrightarrow$ Reescrito con `SetPath=/v3/api-docs` hacia el puerto `8100` (`AUTH_SERVICE_URL`).
   * `/v3/api-docs/tiendas` $\longrightarrow$ Reescrito con `SetPath=/v3/api-docs` hacia el puerto `8140` (`TIENDAS_SERVICE_URL`).
2. **Consola Interactiva Unificada:**
   * Accesible en `http://localhost:8080/swagger-ui.html` (o dominios de QA/PROD), desplegando un selector desplegable con la definición agregada de cada bounded context de StyleRadar.

---

## 5. Estrategia de Separación de Ambientes (`DEV`, `QA`, `PROD`)

| Ambiente | Perfil Spring | Dominio Azure / Host Local | Exposición de Métricas Actuator | Resiliencia y Fallback | Estrategia de Despliegue |
| :--- | :---: | :--- | :---: | :---: | :--- |
| **Desarrollo (DEV)** | `dev` | `http://localhost:8080` | `show-details: always` | Habilitada con logging DEBUG | Local con Docker Compose / IDE |
| **Calidad (QA Cloud)** | `qa` | `https://styleradar-gateway-qa.azurewebsites.net` | `show-details: when_authorized` | Circuit Breaker activo | Push a rama `develop` (`ci-qa.yml`) |
| **Producción (PROD Cloud)** | `prod` | `https://styleradar-gateway-prod.azurewebsites.net` | `show-details: never` | Circuit Breaker endurecido | Push a rama `main` / tags `v*.*.*` (`ci-prod.yml`) |

