[⬅ Volver al README Principal](../../README.md)

---

## 7. VARIABLES DE ENTORNO

### Matriz Comparativa de Variables: Ejecución Maven vs Docker Compose

Las URLs de destino difieren según el entorno de ejecución del API Gateway. Cuando Spring corre directamente sobre la máquina anfitriona con Maven, los servicios se resuelven en `localhost` mediante sus puertos expuestos en el host. Cuando corre dentro de Docker, el Gateway se comunica a través de la red interna `styleradar-network`.

> [!IMPORTANT]
> **Diferencia entre Destinos Predeterminados en Compose y Destinos Sugeridos:**
> * **7 Destinos Predeterminados en `docker-compose.yml`:** El archivo `docker-compose.yml` inyecta activamente en el bloque `environment` del Gateway las URLs de: **Auth, Tiendas, Catálogo, Notificaciones, Pagos, Probador y Donaciones**.
> * **7 Destinos Sugeridos (Requieren Configuración Explícita):** Los microservicios de **Usuarios, Inventario, Geolocalización, Playlist, Chat (HTTP/WS) y Estadísticas** NO están incluidos en el bloque `environment` del Compose actual ni este declara la directiva `env_file`. Si alguien coloca una de estas variables únicamente en el archivo `.env`, Docker Compose **no** la transferirá al contenedor y Spring seguirá utilizando su valor por defecto (`http://localhost:<puerto>`), el cual apunta al propio Gateway dentro del contenedor. Por ello, estas siete variables se presentan como **valores sugeridos que requieren configuración explícita**.

#### 1. Variables Base y Destinos Predeterminados en `docker-compose.yml`

| Variable de Entorno | Microservicio / Destino | Valor con Maven (Host Local) | Valor Predeterminado en Docker Compose (`styleradar-network`) | Inyección en `docker-compose.yml` |
| :--- | :--- | :--- | :--- | :---: |
| `SERVER_PORT` | `api-gateway` | `8080` | `8080` (Mapeo `8080:8080`) | Preconfigurado (`environment`) |
| `SPRING_PROFILES_ACTIVE` | Entorno Spring Boot | `dev` | `dev` | Preconfigurado (`environment`) |
| `TRYON_SERVICE_URL` | `ms-probador-virtual` | `http://localhost:8500` | `http://sr-probador-virtual:8500` | Preconfigurado (`environment`) |
| `DONACIONES_SERVICE_URL` | `ms-segunda-vida-donaciones` | `http://localhost:8520` | `http://ms-donaciones:8520` | Preconfigurado (`environment`) |
| `AUTH_SERVICE_URL` | `ms-auth` | `http://localhost:8100` | `http://ms-auth:8081` | Preconfigurado (`environment`) |
| `TIENDAS_SERVICE_URL` | `ms-tiendas` | `http://localhost:8140` | `http://ms-tiendas:8082` | Preconfigurado (`environment`) |
| `CATALOGO_SERVICE_URL` | `ms-item-catalogo` | `http://localhost:8220` | `http://ms-catalogo:8084` | Preconfigurado (`environment`) |
| `NOTIFICACIONES_SERVICE_URL` | `ms-notificaciones` | `http://localhost:8300` | `http://ms-notificaciones:8091` | Preconfigurado (`environment`) |
| `PAGOS_SERVICE_URL` | `ms-pagos-suscripciones` | `http://localhost:8400` | `http://ms-pagos:8090` | Preconfigurado (`environment`) |
| `JWT_SECRET_BASE64` | Validación Perimetral | *Secreto local en base64* | *Secreto local en base64* | Preconfigurado (`environment`) |

#### 2. Variables Sugeridas para Servicios Restantes (Requieren Configuración Explícita)

| Variable de Entorno | Microservicio / Destino | Valor con Maven (Host Local) | Dirección Sugerida para `styleradar-network` | Estado en Docker Compose |
| :--- | :--- | :--- | :--- | :---: |
| `USUARIOS_SERVICE_URL` | `ms-usuarios` | `http://localhost:8120` | `http://ms-usuarios:8120` | ⚠️ *Sugerido · Requiere configuración explícita* |
| `INVENTARIO_SERVICE_URL` | `ms-inventario` | `http://localhost:8200` | `http://ms-inventario:8200` | ⚠️ *Sugerido · Requiere configuración explícita* |
| `GEOLOCALIZACION_SERVICE_URL` | `ms-geolocalizacion` | `http://localhost:8240` | `http://ms-geolocalizacion:8240` | ⚠️ *Sugerido · Requiere configuración explícita* |
| `PLAYLIST_SERVICE_URL` | `ms-playlist` | `http://localhost:8260` | `http://ms-playlist:8260` | ⚠️ *Sugerido · Requiere configuración explícita* |
| `CHAT_SERVICE_URL` | `ms-chat` (HTTP REST) | `http://localhost:8320` | `http://ms-chat:8320` | ⚠️ *Sugerido · Requiere configuración explícita* |
| `CHAT_WS_SERVICE_URL` | `ms-chat` (WebSocket) | `ws://localhost:8320` | `ws://ms-chat:8320` | ⚠️ *Sugerido · Requiere configuración explícita* |
| `ESTADISTICAS_SERVICE_URL` | `ms-estadisticas` | `http://localhost:8420` | `http://ms-estadisticas:8420` | ⚠️ *Sugerido · Requiere configuración explícita* |

#### 3. Variables de Resiliencia, CircuitBreaker y Red Confiable

| Variable de Entorno | Propósito / Configuración | Valor por Defecto | Impacto Operativo |
| :--- | :--- | :---: | :--- |
| `GATEWAY_CIRCUIT_BREAKER_TIMEOUT_SECONDS` | Tiempo límite antes de corte por TimeLimiter | `30` | Previene cortes prematuros ante arranques en frío |
| `GATEWAY_CIRCUIT_BREAKER_SLIDING_WINDOW_SIZE` | Tamaño de ventana deslizante de llamadas | `10` | Muestreo para cálculo de tasa de fallos |
| `GATEWAY_CIRCUIT_BREAKER_MIN_CALLS` | Llamadas mínimas antes de evaluar apertura | `5` | Número mínimo de intentos para abrir el circuito |
| `GATEWAY_CIRCUIT_BREAKER_FAILURE_RATE_THRESHOLD`| Porcentaje umbral de fallos para abrir circuito | `50.0` | Porcentaje de fallos (ej. 50%) para transición a OPEN |
| `GATEWAY_CIRCUIT_BREAKER_WAIT_DURATION_OPEN_SECONDS` | Tiempo de espera en estado OPEN antes de half-open | `10` | Ventana de enfriamiento antes de reintentar llamadas |
| `GATEWAY_TRUSTED_PROXIES` | Lista CIDR de proxies inversos confiables | Redes privadas RFC1918 | Validación de cabecera `X-Forwarded-For` perimetral |

### Cómo Pasar Variables hacia el Contenedor Docker

> [!WARNING]
> **Atención al uso del archivo `.env`:**
> Docker Compose usa el archivo `.env` para interpolar valores dentro de `docker-compose.yml`. Como `docker-compose.yml` no incluye estas siete variables en su bloque `environment` ni declara la directiva `env_file`, **definir una variable únicamente en `.env` no la hace accesible para Spring dentro del contenedor**.

Para pasar cualquiera de estas variables al contenedor del Gateway, agrégala en el bloque `services.api-gateway.environment` dentro de `docker-compose.yml`:

```yaml
services:
  api-gateway:
    environment:
      SERVER_PORT: 8080
      SPRING_PROFILES_ACTIVE: dev
      TRYON_SERVICE_URL: ${TRYON_SERVICE_URL:-http://sr-probador-virtual:8500}
      AUTH_SERVICE_URL: ${AUTH_SERVICE_URL:-http://ms-auth:8081}
      CATALOGO_SERVICE_URL: ${CATALOGO_SERVICE_URL:-http://ms-catalogo:8084}
      TIENDAS_SERVICE_URL: ${TIENDAS_SERVICE_URL:-http://ms-tiendas:8082}
      NOTIFICACIONES_SERVICE_URL: ${NOTIFICACIONES_SERVICE_URL:-http://ms-notificaciones:8091}
      PAGOS_SERVICE_URL: ${PAGOS_SERVICE_URL:-http://ms-pagos:8090}
      DONACIONES_SERVICE_URL: ${DONACIONES_SERVICE_URL:-http://ms-donaciones:8520}
      # Ejemplo: Agregar una de las variables sugeridas con valor por defecto
      USUARIOS_SERVICE_URL: ${USUARIOS_SERVICE_URL:-http://ms-usuarios:8120}
```

*(Opcionalmente, se puede agregar `env_file: .env` al servicio `api-gateway` si el equipo prefiere inyectar automáticamente todas las variables del `.env` al contenedor).*

### Verificación del Destino Inyectado en el Gateway

Para comprobar qué destino recibió efectivamente el Gateway antes de probar las rutas de negocio, recrea el servicio y consulta la variable con `printenv`:

```bash
# 1. Recrear el contenedor del Gateway para aplicar la nueva variable
docker compose up -d api-gateway

# 2. Verificar el valor que realmente tiene el contenedor en ejecución
docker compose exec api-gateway printenv USUARIOS_SERVICE_URL
# Salida esperada: http://ms-usuarios:8120
```

> [!TIP]
> **Si un servicio corre en el equipo anfitrión (Host Local) fuera de Docker:**  
> Se debe sobrescribir la variable usando el alias `host.docker.internal` para que el contenedor alcance el puerto del host:
> ```bash
> USUARIOS_SERVICE_URL=http://host.docker.internal:8120 docker compose up -d api-gateway
> docker compose exec api-gateway printenv USUARIOS_SERVICE_URL
> ```

---

[⬅ Volver al README Principal](../../README.md)
