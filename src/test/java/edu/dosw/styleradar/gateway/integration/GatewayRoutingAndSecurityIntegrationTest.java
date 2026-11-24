package edu.dosw.styleradar.gateway.integration;

import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.test.StepVerifier;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext
class GatewayRoutingAndSecurityIntegrationTest {

    private static final String TEST_SECRET_BASE64 = "c3R5bGVyYWRhci1qd3Qtc2VjcmV0LWtleS0yMDI2LXBvcnRhbDgwODAtc3VwZXItc2VjdXJlCg==";

    private static final HttpServer mockHttpServer;
    private static final ExecutorService serverExecutor;
    private static final int mockPort;
    private static final DisposableServer mockWsServer;
    private static final int mockWsPort;

    static {
        try {
            serverExecutor = Executors.newFixedThreadPool(4);
            mockHttpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            mockHttpServer.setExecutor(serverExecutor);
            mockPort = mockHttpServer.getAddress().getPort();
            configureMockEndpoints();
            mockHttpServer.start();

            mockWsServer = reactor.netty.http.server.HttpServer.create()
                    .host("127.0.0.1")
                    .port(0)
                    .route(routes -> routes.ws("/ws", (in, out) ->
                            out.sendString(Mono.just("{\"event\":\"CHAT_CONNECTED\",\"status\":\"OK\"}"))
                    ))
                    .bindNow();
            mockWsPort = mockWsServer.port();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @LocalServerPort
    private int gatewayPort;

    @Autowired
    private WebTestClient webTestClient;

    private SecretKey secretKey;

    private static void configureMockEndpoints() {
        // 1. Endpoint mock para Probador Virtual (/api/v1/try-on/health)
        mockHttpServer.createContext("/api/v1/try-on/health", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                String userId = exchange.getRequestHeaders().getFirst("X-User-Id");
                String userRole = exchange.getRequestHeaders().getFirst("X-User-Role");
                String userRoles = exchange.getRequestHeaders().getFirst("X-User-Roles");
                String response = "{"
                        + "\"service\":\"ms-probador-virtual\","
                        + "\"status\":\"UP\","
                        + "\"receivedUserId\":\"" + (userId != null ? userId : "none") + "\","
                        + "\"receivedUserRole\":\"" + (userRole != null ? userRole : "none") + "\","
                        + "\"receivedUserRoles\":\"" + (userRoles != null ? userRoles : "none") + "\""
                        + "}";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                    os.flush();
                }
            } finally {
                exchange.close();
            }
        });

        // 1b. Endpoint mock lento (> 1s) para validar TimeLimiter extendido del CircuitBreaker
        mockHttpServer.createContext("/api/v1/try-on/slow-upload", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                try {
                    Thread.sleep(1300L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                String response = "{\"service\":\"ms-probador-virtual\",\"status\":\"UPLOADED\"}";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                    os.flush();
                }
            } finally {
                exchange.close();
            }
        });

        // 1c. Endpoint mock estricto que rechaza peticiones con header Origin (reproduce fallo real de QA)
        mockHttpServer.createContext("/api/v1/try-on/cors-strict", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                String forwardedOrigin = exchange.getRequestHeaders().getFirst("Origin");
                int status = forwardedOrigin != null ? 403 : 200;
                String contentType = forwardedOrigin != null ? "text/plain;charset=UTF-8" : "application/json";
                String response = forwardedOrigin != null
                        ? "Invalid CORS request"
                        : "{\"service\":\"ms-probador-virtual\",\"receivedOrigin\":\"none\"}";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                    os.flush();
                }
            } finally {
                exchange.close();
            }
        });

        // 1d. Endpoint mock que simula microservicio emitiendo cabeceras CORS duplicadas o en conflicto
        mockHttpServer.createContext("/api/v1/donations/cors-dup", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().set("Access-Control-Allow-Credentials", "true");
                exchange.getResponseHeaders().set("Access-Control-Expose-Headers", "Location, Content-Disposition");
                exchange.getResponseHeaders().add("Vary", "Origin");
                exchange.getResponseHeaders().add("Vary", "Access-Control-Request-Method");
                byte[] bytes = "{\"service\":\"ms-segunda-vida-donaciones\",\"status\":\"OK\"}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                    os.flush();
                }
            } finally {
                exchange.close();
            }
        });

        // 2. Endpoint mock para Swagger OpenAPI del Probador (/v3/api-docs)
        mockHttpServer.createContext("/v3/api-docs", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                String response = "{"
                        + "\"openapi\":\"3.0.1\","
                        + "\"info\":{\"title\":\"PT8080-VirtualTryOn-Service API\",\"version\":\"1.0.0\"}"
                        + "}";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                    os.flush();
                }
            } finally {
                exchange.close();
            }
        });

        // 3. Endpoint mock para Donaciones (/api/v1/donations/foundations)
        mockHttpServer.createContext("/api/v1/donations/foundations", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                String response = "{"
                        + "\"service\":\"ms-segunda-vida-donaciones\","
                        + "\"status\":\"UP\""
                        + "}";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                    os.flush();
                }
            } finally {
                exchange.close();
            }
        });

        // 4. Endpoint mock para consulta y creación de publicaciones (/api/v1/second-hand)
        mockHttpServer.createContext("/api/v1/second-hand", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                String userId = exchange.getRequestHeaders().getFirst("X-User-Id");
                String method = exchange.getRequestMethod();
                String response = "{"
                        + "\"service\":\"ms-segunda-vida-donaciones\","
                        + "\"method\":\"" + method + "\","
                        + "\"receivedUserId\":\"" + (userId != null ? userId : "anonymous") + "\","
                        + "\"status\":\"UP\""
                        + "}";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                    os.flush();
                }
            } finally {
                exchange.close();
            }
        });

        // 5. Endpoint mock para creación/consulta de donaciones (/api/v1/donations)
        mockHttpServer.createContext("/api/v1/donations", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                String userId = exchange.getRequestHeaders().getFirst("X-User-Id");
                String method = exchange.getRequestMethod();
                String response = "{"
                        + "\"service\":\"ms-segunda-vida-donaciones\","
                        + "\"method\":\"" + method + "\","
                        + "\"receivedUserId\":\"" + (userId != null ? userId : "anonymous") + "\","
                        + "\"status\":\"UP\""
                        + "}";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                    os.flush();
                }
            } finally {
                exchange.close();
            }
        });

        // 6. Endpoint mock para consulta privada de donación (/api/v1/donations/don-456)
        mockHttpServer.createContext("/api/v1/donations/don-456", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                String userId = exchange.getRequestHeaders().getFirst("X-User-Id");
                String userRole = exchange.getRequestHeaders().getFirst("X-User-Role");
                String userRoles = exchange.getRequestHeaders().getFirst("X-User-Roles");
                String response = "{"
                        + "\"service\":\"ms-segunda-vida-donaciones\","
                        + "\"endpoint\":\"/api/v1/donations/don-456\","
                        + "\"receivedUserId\":\"" + (userId != null ? userId : "anonymous") + "\","
                        + "\"receivedUserRole\":\"" + (userRole != null ? userRole : "none") + "\","
                        + "\"receivedUserRoles\":\"" + (userRoles != null ? userRoles : "none") + "\","
                        + "\"status\":\"UP\""
                        + "}";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                    os.flush();
                }
            } finally {
                exchange.close();
            }
        });

        // 7. Endpoint mock para catálogo de publicaciones (/api/v1/second-hand/publications)
        mockHttpServer.createContext("/api/v1/second-hand/publications", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                String userId = exchange.getRequestHeaders().getFirst("X-User-Id");
                String response = "{"
                        + "\"service\":\"ms-segunda-vida-donaciones\","
                        + "\"endpoint\":\"/api/v1/second-hand/publications\","
                        + "\"receivedUserId\":\"" + (userId != null ? userId : "anonymous") + "\","
                        + "\"status\":\"UP\""
                        + "}";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                    os.flush();
                }
            } finally {
                exchange.close();
            }
        });

        // 8. Endpoint mock para detalle de publicación (/api/v1/second-hand/publications/pub-123)
        mockHttpServer.createContext("/api/v1/second-hand/publications/pub-123", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                String userId = exchange.getRequestHeaders().getFirst("X-User-Id");
                String response = "{"
                        + "\"service\":\"ms-segunda-vida-donaciones\","
                        + "\"endpoint\":\"/api/v1/second-hand/publications/pub-123\","
                        + "\"receivedUserId\":\"" + (userId != null ? userId : "anonymous") + "\","
                        + "\"status\":\"UP\""
                        + "}";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                    os.flush();
                }
            } finally {
                exchange.close();
            }
        });

        // 9. Endpoint mock para publicaciones privadas por usuario (/api/v1/second-hand/publications/users/101)
        mockHttpServer.createContext("/api/v1/second-hand/publications/users/101", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                String userId = exchange.getRequestHeaders().getFirst("X-User-Id");
                String userRoles = exchange.getRequestHeaders().getFirst("X-User-Roles");
                String response = "{"
                        + "\"service\":\"ms-segunda-vida-donaciones\","
                        + "\"endpoint\":\"/api/v1/second-hand/publications/users/101\","
                        + "\"receivedUserId\":\"" + (userId != null ? userId : "anonymous") + "\","
                        + "\"receivedUserRoles\":\"" + (userRoles != null ? userRoles : "none") + "\","
                        + "\"status\":\"UP\""
                        + "}";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                    os.flush();
                }
            } finally {
                exchange.close();
            }
        });

        // 10. Endpoint mock para chat REST (/api/v1/conversations)
        mockHttpServer.createContext("/api/v1/conversations", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                String userId = exchange.getRequestHeaders().getFirst("X-User-Id");
                String response = "{"
                        + "\"service\":\"ms-chat\","
                        + "\"endpoint\":\"/api/v1/conversations\","
                        + "\"receivedUserId\":\"" + (userId != null ? userId : "anonymous") + "\","
                        + "\"status\":\"UP\""
                        + "}";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                    os.flush();
                }
            } finally {
                exchange.close();
            }
        });

        // 11. Endpoint mock para notificaciones por usuario (/api/v1/users/user-101/notifications)
        mockHttpServer.createContext("/api/v1/users/user-101/notifications", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                String userId = exchange.getRequestHeaders().getFirst("X-User-Id");
                String response = "{"
                        + "\"service\":\"ms-notificaciones\","
                        + "\"endpoint\":\"/api/v1/users/user-101/notifications\","
                        + "\"receivedUserId\":\"" + (userId != null ? userId : "anonymous") + "\","
                        + "\"status\":\"UP\""
                        + "}";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                    os.flush();
                }
            } finally {
                exchange.close();
            }
        });

        // 12. Endpoint mock para Health Check de Donaciones (/api/v1/health)
        mockHttpServer.createContext("/api/v1/health", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                String response = "{"
                        + "\"service\":\"ms-segunda-vida-donaciones\","
                        + "\"endpoint\":\"/api/v1/health\","
                        + "\"status\":\"UP\""
                        + "}";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                    os.flush();
                }
            } finally {
                exchange.close();
            }
        });

        // 13. Endpoint mock para Fotos de Segunda Mano (/api/v1/second-hand/photos)
        mockHttpServer.createContext("/api/v1/second-hand/photos", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                String userId = exchange.getRequestHeaders().getFirst("X-User-Id");
                String method = exchange.getRequestMethod();
                String response = "{"
                        + "\"service\":\"ms-segunda-vida-donaciones\","
                        + "\"method\":\"" + method + "\","
                        + "\"endpoint\":\"/api/v1/second-hand/photos\","
                        + "\"receivedUserId\":\"" + (userId != null ? userId : "anonymous") + "\","
                        + "\"status\":\"UP\""
                        + "}";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                    os.flush();
                }
            } finally {
                exchange.close();
            }
        });

        // 14. Endpoint mock para Representante de Fundación (/api/v1/donations/foundations/representative)
        mockHttpServer.createContext("/api/v1/donations/foundations/representative", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                String userId = exchange.getRequestHeaders().getFirst("X-User-Id");
                String response = "{"
                        + "\"service\":\"ms-segunda-vida-donaciones\","
                        + "\"endpoint\":\"/api/v1/donations/foundations/representative\","
                        + "\"receivedUserId\":\"" + (userId != null ? userId : "anonymous") + "\","
                        + "\"foundationName\":\"Fundacion Abrigo Solidario\","
                        + "\"status\":\"UP\""
                        + "}";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                    os.flush();
                }
            } finally {
                exchange.close();
            }
        });
    }

    @AfterAll
    static void stopMockBackend() {
        if (mockHttpServer != null) {
            mockHttpServer.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
        if (mockWsServer != null) {
            mockWsServer.disposeNow();
        }
    }

    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("TRYON_SERVICE_URL", () -> "http://127.0.0.1:" + mockPort);
        registry.add("DONACIONES_SERVICE_URL", () -> "http://127.0.0.1:" + mockPort);
        registry.add("NOTIFICACIONES_SERVICE_URL", () -> "http://127.0.0.1:" + mockPort);
        registry.add("CHAT_SERVICE_URL", () -> "http://127.0.0.1:" + mockPort);
        registry.add("CHAT_WS_SERVICE_URL", () -> "ws://127.0.0.1:" + mockWsPort);
        registry.add("security.jwt.secret-base64", () -> TEST_SECRET_BASE64);
    }

    @BeforeEach
    void setUp() {
        byte[] keyBytes = Base64.getDecoder().decode(TEST_SECRET_BASE64.trim());
        secretKey = Keys.hmacShaKeyFor(keyBytes);
    }

    private String generateValidToken(String userId, String role) {
        return Jwts.builder()
                .subject(userId)
                .claim("role", role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600000))
                .signWith(secretKey)
                .compact();
    }

    @Test
    @DisplayName("Integración Seguridad: Acceso anónimo a ruta protegida (/api/v1/try-on/health) es rechazado con 401")
    void shouldRejectAnonymousAccessToTryOnServiceWith401() {
        webTestClient.get()
                .uri("/api/v1/try-on/health")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.error").isEqualTo("Unauthorized");
    }

    @Test
    @DisplayName("Integración Seguridad: Creación anónima de donación (POST /api/v1/donations) es rechazada con 401")
    void shouldRejectAnonymousAccessToDonationsCreationWith401() {
        webTestClient.post()
                .uri("/api/v1/donations")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.error").isEqualTo("Unauthorized");
    }

    @Test
    @DisplayName("Integración Seguridad: Creación anónima de publicación (POST /api/v1/second-hand) es rechazada con 401")
    void shouldRejectAnonymousAccessToSecondHandCreationWith401() {
        webTestClient.post()
                .uri("/api/v1/second-hand")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.error").isEqualTo("Unauthorized");
    }

    @Test
    @DisplayName("Integración Consulta Pública: Consulta anónima GET /api/v1/second-hand es permitida (200 OK)")
    void shouldAllowAnonymousAccessToPublicSecondHandConsultation() {
        webTestClient.get()
                .uri("/api/v1/second-hand")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo("ms-segunda-vida-donaciones")
                .jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    @DisplayName("Integración Consulta Pública Canónica: Consulta anónima GET /api/v1/second-hand/publications es permitida (200 OK)")
    void shouldAllowAnonymousAccessToPublicPublicationsCatalog() {
        webTestClient.get()
                .uri("/api/v1/second-hand/publications")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo("ms-segunda-vida-donaciones")
                .jsonPath("$.endpoint").isEqualTo("/api/v1/second-hand/publications")
                .jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    @DisplayName("Integración Consulta Pública Canónica: Consulta anónima GET /api/v1/second-hand/publications/{id} es permitida (200 OK)")
    void shouldAllowAnonymousAccessToPublicPublicationDetail() {
        webTestClient.get()
                .uri("/api/v1/second-hand/publications/pub-123")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo("ms-segunda-vida-donaciones")
                .jsonPath("$.endpoint").isEqualTo("/api/v1/second-hand/publications/pub-123")
                .jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    @DisplayName("Integración Seguridad: Consulta anónima a publicaciones de usuario (/publications/users/{userId}) es rechazada con 401")
    void shouldRejectAnonymousAccessToPrivateUserPublicationsWith401() {
        webTestClient.get()
                .uri("/api/v1/second-hand/publications/users/101")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.error").isEqualTo("Unauthorized");
    }

    @Test
    @DisplayName("Integración Seguridad: Consulta a publicaciones de usuario con token válido es permitida")
    void shouldAllowAuthenticatedAccessToPrivateUserPublications() {
        String token = generateValidToken("user-propietario-101", "ROLE_CLIENTE");

        webTestClient.get()
                .uri("/api/v1/second-hand/publications/users/101")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo("ms-segunda-vida-donaciones")
                .jsonPath("$.receivedUserId").isEqualTo("user-propietario-101");
    }

    @Test
    @DisplayName("Integración Swagger: Ruta pública (/v3/api-docs/probador) permite acceso anónimo y entrega OpenAPI")
    void shouldAllowAnonymousAccessToSwaggerProbador() {
        webTestClient.get()
                .uri("/v3/api-docs/probador")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.openapi").isEqualTo("3.0.1")
                .jsonPath("$.info.title").isEqualTo("PT8080-VirtualTryOn-Service API");
    }

    @Test
    @DisplayName("Integración Enrutamiento con Token: /api/v1/try-on/** se enruta correctamente con token válido")
    void shouldRouteTryOnServiceCorrectlyWithValidToken() {
        String token = generateValidToken("user-tryon-auth", "ROLE_COMPRADOR");

        webTestClient.get()
                .uri("/api/v1/try-on/health")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo("ms-probador-virtual")
                .jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    @DisplayName("Integración Enrutamiento con Token: /api/v1/donations/foundations exige JWT y se enruta con token válido")
    void shouldRouteDonacionesServiceCorrectlyWithValidToken() {
        webTestClient.get()
                .uri("/api/v1/donations/foundations")
                .exchange()
                .expectStatus().isUnauthorized();

        String token = generateValidToken("user-dona-auth", "ROLE_DONANTE");

        webTestClient.get()
                .uri("/api/v1/donations/foundations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo("ms-segunda-vida-donaciones")
                .jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    @DisplayName("Integración Enrutamiento con Token: POST /api/v1/donations se enruta con token válido")
    void shouldRouteDonationsCreationWithValidToken() {
        String token = generateValidToken("user-donante-77", "ROLE_DONANTE");

        webTestClient.post()
                .uri("/api/v1/donations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo("ms-segunda-vida-donaciones")
                .jsonPath("$.receivedUserId").isEqualTo("user-donante-77");
    }

    @Test
    @DisplayName("Integración Enrutamiento con Token: POST /api/v1/second-hand se enruta con token válido")
    void shouldRouteSecondHandCreationWithValidToken() {
        String token = generateValidToken("user-vendedor-88", "ROLE_CLIENTE");

        webTestClient.post()
                .uri("/api/v1/second-hand")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo("ms-segunda-vida-donaciones")
                .jsonPath("$.receivedUserId").isEqualTo("user-vendedor-88");
    }

    @Test
    @DisplayName("Integración Seguridad: Token válido elimina intento de spoofing (X-User-Id, X-User-Role, X-User-Roles) e inyecta identidad auténtica")
    void shouldAuthenticateValidTokenAndSanitizeHeaders() {
        String token = generateValidToken("user-portal8080-real", "ROLE_CLIENTE");

        webTestClient.get()
                .uri("/api/v1/try-on/health")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-User-Id", "spoofed-user-id")
                .header("X-User-Role", "ROLE_SUPERADMIN")
                .header("X-User-Roles", "ROLE_SUPERADMIN,ROLE_OWNER")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.receivedUserId").isEqualTo("user-portal8080-real")
                .jsonPath("$.receivedUserRole").isEqualTo("ROLE_CLIENTE")
                .jsonPath("$.receivedUserRoles").isEqualTo("ROLE_CLIENTE");
    }

    @Test
    @DisplayName("Integración Seguridad: Consulta privada anónima a donación (GET /api/v1/donations/{id}) es rechazada con 401")
    void shouldRejectAnonymousAccessToPrivateDonationQueryWith401() {
        webTestClient.get()
                .uri("/api/v1/donations/don-456")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.error").isEqualTo("Unauthorized");
    }

    @Test
    @DisplayName("Integración Seguridad: Consulta privada anónima a publicaciones por usuario (GET /api/v1/second-hand/user/{id}) es rechazada con 401")
    void shouldRejectAnonymousAccessToPrivateUserSecondHandWith401() {
        webTestClient.get()
                .uri("/api/v1/second-hand/user/101")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.error").isEqualTo("Unauthorized");
    }

    @Test
    @DisplayName("Integración Seguridad: Consulta privada a donación con token válido es permitida e inyecta cabeceras")
    void shouldAllowAuthenticatedAccessToPrivateDonationQuery() {
        String token = generateValidToken("user-donante-99", "ROLE_DONANTE");

        webTestClient.get()
                .uri("/api/v1/donations/don-456")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-User-Roles", "SPOOFED_ROLES")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo("ms-segunda-vida-donaciones")
                .jsonPath("$.receivedUserId").isEqualTo("user-donante-99")
                .jsonPath("$.receivedUserRoles").isEqualTo("ROLE_DONANTE");
    }

    @Test
    @DisplayName("Integración Seguridad: Token JWT manipulado o inválido es rechazado con 401 por el Gateway")
    void shouldRejectInvalidTokenWith401Unauthorized() {
        webTestClient.get()
                .uri("/api/v1/try-on/health")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token-manipulado-o-falso")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.error").isEqualTo("Unauthorized");
    }

    @Test
    @DisplayName("Integración Seguridad: /actuator/health es público y responde 200 OK")
    void shouldAllowPublicAccessToActuatorHealth() {
        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    @DisplayName("Mitigación CVE-2025-41243: El endpoint administrativo /actuator/gateway no está expuesto (retorna 404 Not Found)")
    void shouldVerifyActuatorGatewayIsNotExposed() {
        webTestClient.get()
                .uri("/actuator/gateway/routes")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("Integración Chat/REST: Consulta autenticada (/api/v1/conversations) se enruta a ms-chat")
    void shouldRouteChatConversationsWithValidToken() {
        String token = generateValidToken("user-chat-1", "ROLE_CLIENTE");

        webTestClient.get()
                .uri("/api/v1/conversations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo("ms-chat")
                .jsonPath("$.endpoint").isEqualTo("/api/v1/conversations")
                .jsonPath("$.receivedUserId").isEqualTo("user-chat-1");
    }

    @Test
    @DisplayName("Integración Chat/WebSocket: Petición HTTP Upgrade retorna status 101 Switching Protocols a través del Gateway")
    void shouldReturn101SwitchingProtocolsOnWebSocketUpgrade() {
        HttpResponseStatus status = reactor.netty.http.client.HttpClient.create()
                .headers(h -> {
                    h.set(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET);
                    h.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE);
                    h.set(HttpHeaderNames.SEC_WEBSOCKET_KEY, "dGhlIHNhbXBsZSBub25jZQ==");
                    h.set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13");
                })
                .get()
                .uri("http://127.0.0.1:" + gatewayPort + "/ws")
                .responseSingle((res, bytes) -> Mono.just(res.status()))
                .block(Duration.ofSeconds(5));

        org.junit.jupiter.api.Assertions.assertNotNull(status);
        org.junit.jupiter.api.Assertions.assertEquals(101, status.code(), "El handshake a través del Gateway debe responder 101 Switching Protocols");
    }

    @Test
    @DisplayName("Integración Chat/WebSocket: Handshake WebSocket real con respuesta 101 y comunicación bidireccional")
    void shouldPerformRealWebSocketHandshakeAndReceiveFrames() {
        WebSocketClient client = new ReactorNettyWebSocketClient();
        URI uri = URI.create("ws://127.0.0.1:" + gatewayPort + "/ws");

        AtomicReference<String> messageReceived = new AtomicReference<>();
        AtomicBoolean handshakeCompleted = new AtomicBoolean(false);

        Mono<Void> sessionMono = client.execute(uri, session -> {
            handshakeCompleted.set(true);
            return session.receive()
                    .map(WebSocketMessage::getPayloadAsText)
                    .take(1)
                    .doOnNext(messageReceived::set)
                    .then(session.close());
        });

        StepVerifier.create(sessionMono)
                .expectComplete()
                .verify(Duration.ofSeconds(5));

        org.junit.jupiter.api.Assertions.assertTrue(handshakeCompleted.get(), "El handshake WebSocket (101) debe completarse");
        org.junit.jupiter.api.Assertions.assertEquals("{\"event\":\"CHAT_CONNECTED\",\"status\":\"OK\"}", messageReceived.get());
    }

    @Test
    @DisplayName("Integración Notificaciones: Consulta por usuario (/api/v1/users/{userId}/notifications) se enruta a ms-notificaciones con token válido")
    void shouldRouteUserNotificationsWithValidToken() {
        String token = generateValidToken("user-101", "USER");

        webTestClient.get()
                .uri("/api/v1/users/user-101/notifications")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo("ms-notificaciones")
                .jsonPath("$.receivedUserId").isEqualTo("user-101");
    }

    @Test
    @DisplayName("Integración Enrutamiento: GET /api/v1/health es público y se enruta a ms-segunda-vida-donaciones")
    void shouldRoutePublicHealthCheckToDonaciones() {
        webTestClient.get()
                .uri("/api/v1/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo("ms-segunda-vida-donaciones")
                .jsonPath("$.endpoint").isEqualTo("/api/v1/health");
    }

    @Test
    @DisplayName("Integración Enrutamiento: POST /api/v1/second-hand/photos se enruta a Donaciones con token")
    void shouldRouteSecondHandPhotosWithToken() {
        String token = generateValidToken("user-seller-202", "ROLE_CLIENTE");

        webTestClient.post()
                .uri("/api/v1/second-hand/photos")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo("ms-segunda-vida-donaciones")
                .jsonPath("$.receivedUserId").isEqualTo("user-seller-202");
    }

    @Test
    @DisplayName("Integración Enrutamiento: GET /api/v1/donations/foundations/representative se enruta con titularidad de usuario autenticado")
    void shouldRouteDonationsRepresentativeWithValidToken() {
        String token = generateValidToken("user-rep-505", "REPRESENTANTE_FUNDACION");

        webTestClient.get()
                .uri("/api/v1/donations/foundations/representative")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo("ms-segunda-vida-donaciones")
                .jsonPath("$.receivedUserId").isEqualTo("user-rep-505")
                .jsonPath("$.foundationName").isEqualTo("Fundacion Abrigo Solidario");
    }

    @Test
    @DisplayName("Integración Resiliencia: POST Try-On > 1s completa con 200 OK sin disparar TimeLimiter por defecto")
    void shouldAllowSlowTryOnPostBeyondDefaultOneSecondTimeout() {
        String token = generateValidToken("user-tryon-slow", "ROLE_BUYER");

        webTestClient.post()
                .uri("/api/v1/try-on/slow-upload")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo("ms-probador-virtual")
                .jsonPath("$.status").isEqualTo("UPLOADED");
    }

    @Test
    @DisplayName("Integración CORS Perimetral: remueve Origin hacia downstream estricto y responde un único header CORS para Vercel/Vite")
    void shouldStripOriginTowardsStrictDownstreamAndReturnSingleGatewayCorsHeader() {
        String token = generateValidToken("user-cors-qa", "ROLE_BUYER");

        for (String origin : List.of("https://styleradar-qa.vercel.app", "http://localhost:5173")) {
            var result = webTestClient.get()
                    .uri("/api/v1/try-on/cors-strict")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header(HttpHeaders.ORIGIN, origin)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.receivedOrigin").isEqualTo("none")
                    .returnResult();

            HttpHeaders responseHeaders = result.getResponseHeaders();
            org.junit.jupiter.api.Assertions.assertEquals(
                    List.of(origin),
                    responseHeaders.get(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)
            );
            org.junit.jupiter.api.Assertions.assertEquals(
                    List.of("true"),
                    responseHeaders.get(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)
            );
        }

        webTestClient.get()
                .uri("/api/v1/try-on/cors-strict")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.ORIGIN, "https://sitio-ajeno.vercel.app")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    @Test
    @DisplayName("Integración CORS Perimetral: deduplica cabeceras CORS emitidas por downstream conservando origen del Gateway")
    void shouldDeduplicateDownstreamCorsResponseHeadersKeepingGatewayOrigin() {
        String token = generateValidToken("user-cors-don", "ROLE_BUYER");

        var result = webTestClient.get()
                .uri("/api/v1/donations/cors-dup")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.ORIGIN, "https://styleradar-qa.vercel.app")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo("ms-segunda-vida-donaciones")
                .returnResult();

        HttpHeaders responseHeaders = result.getResponseHeaders();
        org.junit.jupiter.api.Assertions.assertEquals(
                List.of("https://styleradar-qa.vercel.app"),
                responseHeaders.get(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)
        );
        org.junit.jupiter.api.Assertions.assertEquals(
                List.of("true"),
                responseHeaders.get(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)
        );

        List<String> exposeHeaders = responseHeaders.get(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS);
        org.junit.jupiter.api.Assertions.assertNotNull(exposeHeaders);
        org.junit.jupiter.api.Assertions.assertEquals(1, exposeHeaders.size());
        String exposeValue = exposeHeaders.get(0);
        org.junit.jupiter.api.Assertions.assertTrue(exposeValue.contains("Retry-After"));
        org.junit.jupiter.api.Assertions.assertTrue(exposeValue.contains("Authorization"));
        org.junit.jupiter.api.Assertions.assertTrue(exposeValue.contains("WWW-Authenticate"));
        org.junit.jupiter.api.Assertions.assertTrue(exposeValue.contains("Location"));
        org.junit.jupiter.api.Assertions.assertTrue(exposeValue.contains("Content-Disposition"));
        org.junit.jupiter.api.Assertions.assertEquals(
                exposeValue.indexOf("Location"),
                exposeValue.lastIndexOf("Location"),
                "Location no debe aparecer duplicado en Access-Control-Expose-Headers"
        );

        List<String> varyHeaders = responseHeaders.get(HttpHeaders.VARY);
        org.junit.jupiter.api.Assertions.assertNotNull(varyHeaders);
        Set<String> uniqueVary = new LinkedHashSet<>();
        for (String rawVary : varyHeaders) {
            for (String tokenVary : rawVary.split(",")) {
                String trimmed = tokenVary.trim().toLowerCase(Locale.ROOT);
                org.junit.jupiter.api.Assertions.assertTrue(
                        uniqueVary.add(trimmed),
                        "Header Vary contiene valor duplicado: " + tokenVary
                );
            }
        }
    }
}
