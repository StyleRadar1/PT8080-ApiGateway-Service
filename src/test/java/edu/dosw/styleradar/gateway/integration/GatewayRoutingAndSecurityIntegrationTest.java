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
@DirtiesContext
class GatewayRoutingAndSecurityIntegrationTest {

    private static final String TEST_SECRET_BASE64 = "c3R5bGVyYWRhci1qd3Qtc2VjcmV0LWtleS0yMDI2LXBvcnRhbDgwODAtc3VwZXItc2VjdXJlCg==";

    private static final HttpServer mockHttpServer;
    private static final ExecutorService serverExecutor;
    private static final int mockPort;

    static {
        try {
            serverExecutor = Executors.newFixedThreadPool(4);
            mockHttpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            mockHttpServer.setExecutor(serverExecutor);
            mockPort = mockHttpServer.getAddress().getPort();
            configureMockEndpoints();
            mockHttpServer.start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

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
                String response = "{"
                        + "\"service\":\"ms-probador-virtual\","
                        + "\"status\":\"UP\","
                        + "\"receivedUserId\":\"" + (userId != null ? userId : "none") + "\","
                        + "\"receivedUserRole\":\"" + (userRole != null ? userRole : "none") + "\""
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
    @DisplayName("Integración Seguridad: Acceso anónimo a ruta protegida (/api/v1/donaciones/health) es rechazado con 401")
    void shouldRejectAnonymousAccessToDonacionesWith401() {
        webTestClient.get()
                .uri("/api/v1/donaciones/health")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.error").isEqualTo("Unauthorized");
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
    @DisplayName("Integración Enrutamiento con Token: /api/v1/donaciones/** se enruta correctamente con token válido")
    void shouldRouteDonacionesServiceCorrectlyWithValidToken() {
        String token = generateValidToken("user-dona-auth", "ROLE_DONANTE");

        webTestClient.get()
                .uri("/api/v1/donaciones/health")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo("ms-donaciones")
                .jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    @DisplayName("Integración Seguridad: Token válido elimina intento de spoofing e inyecta identidad auténtica")
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
}
