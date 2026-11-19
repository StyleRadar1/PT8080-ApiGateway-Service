[⬅ Volver al README Principal](../../README.md)

---

## 1. NOMBRE DEL SERVICIO Y SU BOUNDED CONTEXT

* **Identificador de Servicio:** `PT8080-ApiGateway-Service` (`api-gateway`)
* **Paquete Arquitectónico:** Paquete 5: Experiencia y Probador Virtual AI (Componente Transversal)
* **Puerto de Escucha Oficial:** `8080` (punto único de entrada para clientes web y móviles)
* **Naturaleza Arquitectural:** **Stateless (Sin persistencia en disco)**. No requiere base de datos relacional para garantizar máxima velocidad de reenvío no bloqueante (< 5 ms de sobrecarga).
* **Bounded Context:** `Edge Routing & Security Perimeter (Perímetro de Enrutamiento y Seguridad)`

### Responsabilidades del Componente

Este servicio actúa como el **Reverse Proxy perimetral reactivo** para todo el ecosistema de StyleRadar Bogotá.

| Qué HACE este API Gateway (Alcance) | Qué NO HACE este API Gateway (Límites) |
| :--------------------------------------------------------------------------------------------------------------------------------------------------------- | :------------------------------------------------------------------------------------------------------------ |
| • Punto único de entrada para todas las peticiones frontend hacia los 13 microservicios (`SR-RNF-01`). | • NO almacena datos de negocio en tablas ni maneja persistencia relacional en disco. |
| • Enruta dinámicamente peticiones HTTP hacia servicios downstream preservando contratos de ruta. | • NO ejecuta inferencia de IA ni renderizado de siluetas (delegado a `ms-probador-virtual` en `:8500`). |
| • Inspecciona y valida firmas de tokens JWT Bearer inyectando cabeceras `X-User-Id`, `X-User-Role` y `X-User-Roles` hacia la red interna (`SR-RNF-08`). | • NO emite ni firma credenciales iniciales de autenticación de usuario (delegado a `ms-auth` en `:8081`). |
| • Aplica políticas CORS globales unificadas para clientes React, Angular y móviles (`SR-RNF-03`). | • NO gestiona inventario de prendas ni catálogos de tiendas (delegado a `ms-catalogo` y `ms-tiendas`). |
| • Implementa Circuit Breakers con Resilience4j retornando respuestas de degradación elegante (`Fallback 503`) ante fallos de microservicios downstream. | • NO procesa transacciones monetarias ni pasarelas de pago bancarias (delegado a `ms-pagos` en `:8090`). |
| • Centraliza la trazabilidad perimetral inyectando identificadores únicos de correlación (`X-Correlation-Id`). | • NO implementa lógica pesada síncrona que bloquee hilos de ejecución en el EventLoop de Netty. |

---

[⬅ Volver al README Principal](../../README.md)
