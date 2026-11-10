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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GatewayRoutingAndSecurityIntegrationTest {

    private static final String TEST_SECRET_BASE64 = "c3R5bGVyYWRhci1qd3Qtc2VjcmV0LWtleS0yMDI2LXBvcnRhbDgwODAtc3VwZXItc2VjdXJlCg==";

    private static HttpServer mockHttpServer;
    private static ExecutorService serverExecutor;
    private static int mockPort;

    @Autowired
    private WebTestClient webTestClient;

    private SecretKey secretKey;

    @BeforeAll
    static void startMockBackend() throws IOException {
        serverExecutor = Executors.newFixedThreadPool(4);
        mockHttpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockHttpServer.setExecutor(serverExecutor);
        mockPort = mockHttpServer.getAddress().getPort();

        // 1. Endpoint mock para Probador Virtual (/api/v1/try-on/health)
        mockHttpServer.createContext("/api/v1/try-on/health", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                String userId = exchange.getRequestHeaders().getFirst("X-User-Id");
                String userRole = exchange.getRequestHeaders().getFirst("X-User-Role");
                String response = "{"
                        + "\"service\":\"ms-probador-virtual\","
                        + "\"status\":\"UP\","
                        + "\"receivedUserId\":\"" + (userId != null ? userId : "none") + "\","
                        + "\"receivedUserRole\":\"" + (userRole != null ? userRole : "none") + "\""
                        + "}";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Connection", "close");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
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
                exchange.getResponseHeaders().set("Connection", "close");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } finally {
                exchange.close();
            }
        });

        // 3. Endpoint mock para Donaciones (/api/v1/donaciones/health)
        mockHttpServer.createContext("/api/v1/donaciones/health", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                String response = "{"
                        + "\"service\":\"ms-donaciones\","
                        + "\"status\":\"UP\""
                        + "}";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Connection", "close");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } finally {
                exchange.close();
            }
        });

        mockHttpServer.start();
    }

    @AfterAll
    static void stopMockBackend() {
        if (mockHttpServer != null) {
            mockHttpServer.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("TRYON_SERVICE_URL", () -> "http://127.0.0.1:" + mockPort);
        registry.add("DONACIONES_SERVICE_URL", () -> "http://127.0.0.1:" + mockPort);
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
    @DisplayName("Integración Enrutamiento: /api/v1/try-on/** se enruta correctamente al backend del Probador Virtual")
    void shouldRouteTryOnServiceCorrectly() {
        webTestClient.get()
                .uri("/api/v1/try-on/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo("ms-probador-virtual")
                .jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    @DisplayName("Integración Swagger: /v3/api-docs/probador se reescribe como /v3/api-docs y entrega la especificación OpenAPI")
    void shouldRewriteAndRouteSwaggerProbadorCorrectly() {
        webTestClient.get()
                .uri("/v3/api-docs/probador")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.openapi").isEqualTo("3.0.1")
                .jsonPath("$.info.title").isEqualTo("PT8080-VirtualTryOn-Service API");
    }

    @Test
    @DisplayName("Integración Enrutamiento: /api/v1/donaciones/** se enruta hacia el microservicio de Donaciones (8520)")
    void shouldRouteDonacionesServiceCorrectly() {
        webTestClient.get()
                .uri("/api/v1/donaciones/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo("ms-donaciones");
    }

    @Test
    @DisplayName("Integración Seguridad: Petición con Bearer Token válido elimina spoofing e inyecta identidad autenticada")
    void shouldAuthenticateValidTokenAndSanitizeHeaders() {
        String token = generateValidToken("user-portal8080-real", "ROLE_CLIENTE");

        webTestClient.get()
                .uri("/api/v1/try-on/health")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-User-Id", "spoofed-user-id")
                .header("X-User-Role", "ROLE_SUPERADMIN")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.receivedUserId").isEqualTo("user-portal8080-real")
                .jsonPath("$.receivedUserRole").isEqualTo("ROLE_CLIENTE");
    }

    @Test
    @DisplayName("Integración Seguridad: Petición con Token JWT inválido es rechazada con 401 por el Gateway")
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
    @DisplayName("Integración Seguridad: Petición anónima con cabeceras X-User-* falsificadas son eliminadas por el Gateway")
    void shouldStripSpoofedHeadersOnAnonymousRequests() {
        webTestClient.get()
                .uri("/api/v1/try-on/health")
                .header("X-User-Id", "attacker-admin-id")
                .header("X-User-Role", "ROLE_ADMIN")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.receivedUserId").isEqualTo("none")
                .jsonPath("$.receivedUserRole").isEqualTo("none");
    }
}
