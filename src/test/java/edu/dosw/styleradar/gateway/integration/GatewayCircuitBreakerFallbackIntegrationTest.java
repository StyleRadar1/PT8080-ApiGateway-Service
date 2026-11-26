package edu.dosw.styleradar.gateway.integration;

import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext
class GatewayCircuitBreakerFallbackIntegrationTest {

    private static final String TEST_SECRET_BASE64 = "c3R5bGVyYWRhci1qd3Qtc2VjcmV0LWtleS0yMDI2LXBvcnRhbDgwODAtc3VwZXItc2VjdXJlCg==";
    private static final int UNUSED_PORT;
    private static final HttpServer HEALTHY_SERVER = createHealthyServer();

    static {
        try (ServerSocket socket = new ServerSocket(0)) {
            UNUSED_PORT = socket.getLocalPort();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static HttpServer createHealthyServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/users/me", exchange -> {
                try {
                    exchange.getRequestBody().readAllBytes();
                    String body = "{\"id\":\"user-ok\",\"name\":\"Test User\"}";
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                } finally {
                    exchange.close();
                }
            });
            server.createContext("/api/v1/users/not-found", exchange -> {
                try {
                    exchange.getRequestBody().readAllBytes();
                    String body = "{\"error\":\"Not Found\",\"message\":\"Usuario no encontrado\"}";
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(404, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                } finally {
                    exchange.close();
                }
            });
            server.createContext("/api/v1/users/bad-request-400", exchange -> {
                try {
                    exchange.getRequestBody().readAllBytes();
                    String body = "{\"error\":\"Bad Request\",\"message\":\"Parámetro inválido\"}";
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(400, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                } finally {
                    exchange.close();
                }
            });
            server.createContext("/api/v1/users/unauthorized-401", exchange -> {
                try {
                    exchange.getRequestBody().readAllBytes();
                    String body = "{\"error\":\"Unauthorized\",\"message\":\"Credencial inválida\"}";
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(401, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                } finally {
                    exchange.close();
                }
            });
            server.createContext("/api/v1/users/forbidden-403", exchange -> {
                try {
                    exchange.getRequestBody().readAllBytes();
                    String body = "{\"error\":\"Forbidden\",\"message\":\"Acceso denegado\"}";
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(403, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                } finally {
                    exchange.close();
                }
            });
            server.createContext("/api/v1/users/server-error-500", exchange -> {
                try {
                    exchange.getRequestBody().readAllBytes();
                    String body = "{\"error\":\"Internal Server Error\",\"message\":\"Fallo de base de datos downstream\"}";
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(500, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                } finally {
                    exchange.close();
                }
            });
            server.createContext("/api/v1/users/service-unavailable-503", exchange -> {
                try {
                    exchange.getRequestBody().readAllBytes();
                    String body = "{\"error\":\"Service Unavailable\",\"message\":\"Servicio sobrecargado downstream\"}";
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(503, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                } finally {
                    exchange.close();
                }
            });
            server.createContext("/api/v1/users/slow", exchange -> {
                try {
                    exchange.getRequestBody().readAllBytes();
                    Thread.sleep(4000L); // 4s excede el TimeLimiter configurado a 2s en test
                    String body = "{\"id\":\"slow-ok\",\"message\":\"Respuesta demorada\"}";
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.createContext("/api/v1/users/test-stream/notifications/stream", exchange -> {
                try {
                    exchange.getRequestBody().readAllBytes();
                    byte[] payload = "data: {\"type\":\"ALERT\",\"content\":\"SSE stream test\"}\n\n".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, payload.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(payload);
                    }
                } finally {
                    exchange.close();
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @AfterAll
    static void tearDown() {
        HEALTHY_SERVER.stop(0);
    }

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ReactiveResilience4JCircuitBreakerFactory circuitBreakerFactory;

    @Autowired
    private RouteLocator routeLocator;

    private SecretKey secretKey;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("TRYON_SERVICE_URL", () -> "http://127.0.0.1:" + UNUSED_PORT);
        registry.add("ESTADISTICAS_SERVICE_URL", () -> "http://127.0.0.1:" + UNUSED_PORT);
        registry.add("USUARIOS_SERVICE_URL", () -> "http://127.0.0.1:" + HEALTHY_SERVER.getAddress().getPort());
        registry.add("NOTIFICACIONES_SERVICE_URL", () -> "http://127.0.0.1:" + HEALTHY_SERVER.getAddress().getPort());
        registry.add("styleradar.gateway.circuit-breaker.timeout-seconds", () -> "2");
        registry.add("styleradar.gateway.circuit-breaker.sliding-window-size", () -> "10");
        registry.add("styleradar.gateway.circuit-breaker.minimum-number-of-calls", () -> "5");
        registry.add("styleradar.gateway.circuit-breaker.failure-rate-threshold", () -> "50.0");
        registry.add("styleradar.gateway.circuit-breaker.wait-duration-open-seconds", () -> "10");
    }

    @BeforeEach
    void setUp() {
        byte[] decodedKey = Base64.getDecoder().decode(TEST_SECRET_BASE64);
        secretKey = Keys.hmacShaKeyFor(decodedKey);
        if (circuitBreakerFactory != null && circuitBreakerFactory.getCircuitBreakerRegistry() != null) {
            circuitBreakerFactory.getCircuitBreakerRegistry().getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        }
    }

    private String generateBuyerToken(String userId) {
        Date now = new Date();
        return Jwts.builder()
                .subject(userId)
                .claim("role", "ROLE_BUYER")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + 3_600_000L))
                .signWith(secretKey)
                .compact();
    }

    @Test
    @DisplayName("Integración Fallback: POST /api/v1/try-on/consents con backend caído responde 503, Retry-After 30 y nunca 405")
    void shouldReturn503WhenTryOnPostTriggersCircuitBreakerFallback() {
        String token = generateBuyerToken("buyer-fallback-1");

        webTestClient.post()
                .uri("/api/v1/try-on/consents")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "30")
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.service").isEqualTo("ms-probador-virtual");
    }

    @Test
    @DisplayName("Integración Fallback: DELETE /api/v1/try-on/consents/{userId} con backend caído responde 503, Retry-After 30 y nunca 405")
    void shouldReturn503WhenTryOnDeleteTriggersCircuitBreakerFallback() {
        String token = generateBuyerToken("buyer-fallback-1");

        webTestClient.delete()
                .uri("/api/v1/try-on/consents/user-1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "30")
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.service").isEqualTo("ms-probador-virtual");
    }

    @Test
    @DisplayName("Integración Fallback: GET /api/v1/statistics con backend caído activa statisticsCircuitBreaker y responde 503 con Retry-After 30 y correlationId")
    void shouldReturn503WhenGeneralRouteTriggersCircuitBreakerFallback() {
        String token = generateBuyerToken("buyer-fallback-2");

        webTestClient.get()
                .uri("/api/v1/statistics")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-Correlation-Id", "corr-cb-503")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "30")
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.error").isEqualTo("Microservicio No Disponible")
                .jsonPath("$.correlationId").isEqualTo("corr-cb-503");
    }

    @Test
    @DisplayName("Aislamiento de CircuitBreakers: Peticiones fallidas consecutivas abren efectivamente statisticsCircuitBreaker (OPEN) sin alterar usersCircuitBreaker (CLOSED)")
    void shouldIsolateCircuitBreakersBetweenServices() {
        String token = generateBuyerToken("buyer-cb-isolation");

        // 1. Provocar 5 fallos consecutivos en Estadísticas (servicio caído en UNUSED_PORT)
        // para alcanzar minimumNumberOfCalls (5) con tasa de fallos del 100% (>= 50%)
        for (int i = 0; i < 5; i++) {
            webTestClient.get()
                    .uri("/api/v1/statistics")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .exchange()
                    .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                    .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "30");
        }

        // 2. Verificar que statisticsCircuitBreaker se encuentra efectivamente en estado OPEN
        CircuitBreaker statsCb = circuitBreakerFactory.getCircuitBreakerRegistry().circuitBreaker("statisticsCircuitBreaker");
        assertEquals(CircuitBreaker.State.OPEN, statsCb.getState(),
                "statisticsCircuitBreaker debe abrirse (OPEN) tras 5 fallos consecutivos");

        // 3. La siguiente petición a Estadísticas con el circuito abierto falla de inmediato
        webTestClient.get()
                .uri("/api/v1/statistics")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "30");

        // 4. Usuarios está saludable -> usersCircuitBreaker permanece CERRADO y responde 200 OK
        webTestClient.get()
                .uri("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("user-ok");

        CircuitBreaker usersCb = circuitBreakerFactory.getCircuitBreakerRegistry().circuitBreaker("usersCircuitBreaker");
        assertEquals(CircuitBreaker.State.CLOSED, usersCb.getState(),
                "usersCircuitBreaker debe permanecer CERRADO y aislado de la caída de Estadísticas");
    }

    @Test
    @DisplayName("Resiliencia 5xx: Respuestas HTTP 500 downstream son contabilizadas por el CircuitBreaker y activan fallback con Retry-After 30")
    void shouldTriggerFallbackWhenDownstreamReturns500InternalServerError() {
        String token = generateBuyerToken("buyer-500-check");

        webTestClient.get()
                .uri("/api/v1/users/server-error-500")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "30")
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.error").isEqualTo("Microservicio No Disponible");
    }

    @Test
    @DisplayName("Resiliencia 5xx: Respuestas HTTP 503 downstream son contabilizadas por el CircuitBreaker y activan fallback con Retry-After 30")
    void shouldTriggerFallbackWhenDownstreamReturns503ServiceUnavailable() {
        String token = generateBuyerToken("buyer-503-check");

        webTestClient.get()
                .uri("/api/v1/users/service-unavailable-503")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "30")
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.error").isEqualTo("Microservicio No Disponible");
    }

    @Test
    @DisplayName("Resiliencia TimeLimiter: Petición downstream lenta que excede el tiempo límite activa fallback con Retry-After 30")
    void shouldTriggerFallbackOnTimeoutWhenDownstreamExceedsTimeLimiter() {
        String token = generateBuyerToken("buyer-timeout-check");

        webTestClient.get()
                .uri("/api/v1/users/slow")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "30")
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.error").isEqualTo("Microservicio No Disponible");
    }

    @Test
    @DisplayName("Preservación de errores funcionales 4xx: Códigos 400, 401, 403 y 404 downstream fluyen sin activar fallback 503")
    void shouldPreserve4xxFunctionalErrorsWithoutTriggeringFallback() {
        String token = generateBuyerToken("buyer-4xx-check");

        // 404 Not Found
        webTestClient.get()
                .uri("/api/v1/users/not-found")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error").isEqualTo("Not Found")
                .jsonPath("$.message").isEqualTo("Usuario no encontrado");

        // 400 Bad Request
        webTestClient.get()
                .uri("/api/v1/users/bad-request-400")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("Bad Request")
                .jsonPath("$.message").isEqualTo("Parámetro inválido");

        // 401 Unauthorized downstream
        webTestClient.get()
                .uri("/api/v1/users/unauthorized-401")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error").isEqualTo("Unauthorized");

        // 403 Forbidden downstream
        webTestClient.get()
                .uri("/api/v1/users/forbidden-403")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.error").isEqualTo("Forbidden");
    }

    @Test
    @DisplayName("Inmunidad SSE y WebSocket: Las rutas de streaming y WS no quedan sometidas a CircuitBreaker ni TimeLimiter")
    void shouldEnsureSseAndWebSocketRoutesAreNotSubjectedToCircuitBreakerOrTimeLimiter() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();
        assertNotNull(routes, "El RouteLocator debe suministrar las rutas configuradas");

        // 1. Validar que la ruta de notificaciones SSE no posee filtro CircuitBreaker
        Route sseRoute = routes.stream()
                .filter(r -> "ms-notificaciones-stream".equals(r.getId()))
                .findFirst()
                .orElse(null);
        assertNotNull(sseRoute, "La ruta ms-notificaciones-stream debe existir en el Gateway");
        boolean sseHasCircuitBreaker = sseRoute.getFilters().stream()
                .anyMatch(f -> f.toString().contains("CircuitBreaker"));
        assertFalse(sseHasCircuitBreaker, "La ruta SSE ms-notificaciones-stream no debe estar envuelta en CircuitBreaker ni TimeLimiter");

        // 2. Validar que la ruta WebSocket no posee filtro CircuitBreaker
        Route wsRoute = routes.stream()
                .filter(r -> "ms-chat-ws".equals(r.getId()))
                .findFirst()
                .orElse(null);
        assertNotNull(wsRoute, "La ruta ms-chat-ws debe existir en el Gateway");
        boolean wsHasCircuitBreaker = wsRoute.getFilters().stream()
                .anyMatch(f -> f.toString().contains("CircuitBreaker"));
        assertFalse(wsHasCircuitBreaker, "La ruta WebSocket ms-chat-ws no debe estar envuelta en CircuitBreaker ni TimeLimiter");

        // 3. Probar petición real a SSE sin ser interceptada por fallback ni interrumpida
        String token = generateBuyerToken("buyer-sse-stream");
        webTestClient.get()
                .uri("/api/v1/users/test-stream/notifications/stream")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("text/event-stream")
                .expectBody(String.class)
                .consumeWith(result -> {
                    String responseBody = result.getResponseBody();
                    assertNotNull(responseBody);
                    assertTrue(responseBody.contains("SSE stream test"), "El stream SSE debe recibirse íntegro downstream");
                });
    }
}

