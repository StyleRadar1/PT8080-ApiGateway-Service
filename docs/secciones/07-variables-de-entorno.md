[⬅ Volver al README Principal](../../README.md)

---

## 7. VARIABLES DE ENTORNO

El microservicio utiliza variables de entorno controladas a través de la plantilla oficial `.env.example`:

| Variable de Entorno | Descripción | Valor por Defecto Local | Ámbito |
| :--- | :--- | :--- | :---: |
| `SERVER_PORT` | Puerto de escucha HTTP del Gateway | `8080` | Sistema |
| `SPRING_PROFILES_ACTIVE` | Perfil de configuración de Spring Boot (`dev`, `qa`, `prod`) | `dev` | Entorno |
| `TRYON_SERVICE_URL` | URL de destino del microservicio de Probador Virtual (:8500) | `http://localhost:8500` | Enrutamiento |
| `DONACIONES_SERVICE_URL` | URL de destino del microservicio de Donaciones (:8520) | `http://localhost:8520` | Enrutamiento |
| `AUTH_SERVICE_URL` | URL de destino del microservicio de Autenticación (:8100) | `http://localhost:8100` | Enrutamiento |
| `USUARIOS_SERVICE_URL` | URL de destino del microservicio de Usuarios (:8120) | `http://localhost:8120` | Enrutamiento |
| `TIENDAS_SERVICE_URL` | URL de destino del microservicio de Tiendas (:8140) | `http://localhost:8140` | Enrutamiento |
| `INVENTARIO_SERVICE_URL` | URL de destino del microservicio de Inventario (:8200) | `http://localhost:8200` | Enrutamiento |
| `CATALOGO_SERVICE_URL` | URL de destino del microservicio de Catálogo de Ropa (:8220) | `http://localhost:8220` | Enrutamiento |
| `GEOLOCALIZACION_SERVICE_URL` | URL de destino del microservicio de Geolocalización (:8240) | `http://localhost:8240` | Enrutamiento |
| `PLAYLIST_SERVICE_URL` | URL de destino del microservicio de Playlist (:8260) | `http://localhost:8260` | Enrutamiento |
| `NOTIFICACIONES_SERVICE_URL` | URL de destino del microservicio de Notificaciones (:8300) | `http://localhost:8300` | Enrutamiento |
| `CHAT_SERVICE_URL` | URL de destino del microservicio de Chat (:8320) | `http://localhost:8320` | Enrutamiento |
| `PAGOS_SERVICE_URL` | URL de destino del microservicio de Pagos (:8400) | `http://localhost:8400` | Enrutamiento |
| `ESTADISTICAS_SERVICE_URL` | URL de destino del microservicio de Estadísticas (:8420) | `http://localhost:8420` | Enrutamiento |
| `JWT_SECRET_BASE64` | Clave secreta HMAC en Base64 para validación perimetral de JWT | Inyectada por entorno | Seguridad |

---

[⬅ Volver al README Principal](../../README.md)
