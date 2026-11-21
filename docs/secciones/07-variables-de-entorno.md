[⬅ Volver al README Principal](../../README.md)

---

## 7. VARIABLES DE ENTORNO

### Matriz Comparativa de Variables: Ejecución Maven vs Docker Compose

Las URLs de destino difieren según el entorno de ejecución del API Gateway. Cuando Spring corre directamente sobre la máquina anfitriona con Maven, los servicios se resuelven en `localhost` mediante sus puertos expuestos. Cuando corre dentro de Docker, el Gateway se comunica a través de la red interna `styleradar-network` resolviendo los nombres de contenedor DNS y sus puertos internos.

| Variable de Entorno | Microservicio / Destino | Valor con Maven (Host Local) | Valor con Docker Compose (`styleradar-network`) | Descripción / Ámbito |
| :--- | :--- | :--- | :--- | :---: |
| `SERVER_PORT` | `api-gateway` | `8080` | `8080` (Mapeo `8080:8080`) | Puerto de escucha HTTP |
| `SPRING_PROFILES_ACTIVE` | Entorno Spring Boot | `dev` | `dev` | Perfil activo |
| `TRYON_SERVICE_URL` | `ms-probador-virtual` | `http://localhost:8500` | `http://sr-probador-virtual:8500` | Probador virtual e IA FASHN |
| `DONACIONES_SERVICE_URL` | `ms-segunda-vida-donaciones` | `http://localhost:8520` | `http://ms-donaciones:8520` | Donaciones y moda sostenible |
| `AUTH_SERVICE_URL` | `ms-auth` | `http://localhost:8100` | `http://ms-auth:8081` | Autenticación y tokens JWT |
| `TIENDAS_SERVICE_URL` | `ms-tiendas` | `http://localhost:8140` | `http://ms-tiendas:8082` | Registro y gestión de tiendas |
| `CATALOGO_SERVICE_URL` | `ms-item-catalogo` | `http://localhost:8220` | `http://ms-catalogo:8084` | Catálogo de prendas y búsqueda |
| `NOTIFICACIONES_SERVICE_URL` | `ms-notificaciones` | `http://localhost:8300` | `http://ms-notificaciones:8091` | Alertas Push y correo |
| `PAGOS_SERVICE_URL` | `ms-pagos-suscripciones` | `http://localhost:8400` | `http://ms-pagos:8090` | Pagos Mercado Pago y suscripciones |
| `USUARIOS_SERVICE_URL` | `ms-usuarios` | `http://localhost:8120` | `http://ms-usuarios:8120` | Perfiles y usuarios |
| `INVENTARIO_SERVICE_URL` | `ms-inventario` | `http://localhost:8200` | `http://ms-inventario:8200` | Stock por talla y almacén |
| `GEOLOCALIZACION_SERVICE_URL` | `ms-geolocalizacion` | `http://localhost:8240` | `http://ms-geolocalizacion:8240` | Coordenadas y geodistancia |
| `PLAYLIST_SERVICE_URL` | `ms-playlist` | `http://localhost:8260` | `http://ms-playlist:8260` | Listas y colecciones de ropa |
| `CHAT_SERVICE_URL` | `ms-chat` (HTTP REST) | `http://localhost:8320` | `http://ms-chat:8320` | Mensajería REST de chat |
| `CHAT_WS_SERVICE_URL` | `ms-chat` (WebSocket) | `ws://localhost:8320` | `ws://ms-chat:8320` | Conexión WebSocket en vivo |
| `ESTADISTICAS_SERVICE_URL` | `ms-estadisticas` | `http://localhost:8420` | `http://ms-estadisticas:8420` | Métricas y analítica |
| `JWT_SECRET_BASE64` | Validación Perimetral | *Secreto local en base64* | *Secreto local en base64* | Firma HMAC-SHA256 |

### Sobrescritura de Variables para Integración del Equipo

> [!TIP]
> **¿Cómo adaptar la conexión cuando cambian los nombres o puertos?**
> - **Por archivo `.env`:** Duplica `.env.example` como `.env` en la raíz del repositorio y modifica los valores de `*_SERVICE_URL` requeridos. Docker Compose los carga automáticamente.
> - **En línea de comandos:** Sobrescribe la variable directamente al ejecutar `docker compose`:
>   ```bash
>   CATALOGO_SERVICE_URL=http://mi-catalogo-personalizado:9000 docker compose up -d
>   ```
> - **Servicios ejecutándose fuera de Docker (en el Host Local):** Si un desarrollador corre su microservicio localmente con Maven o Node.js y desea conectarlo con el Gateway contenerizado, debe usar el alias `host.docker.internal`:
>   ```bash
>   TRYON_SERVICE_URL=http://host.docker.internal:8500 docker compose up -d
>   ```

---

[⬅ Volver al README Principal](../../README.md)
