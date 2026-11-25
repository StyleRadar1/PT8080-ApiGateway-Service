package edu.dosw.styleradar.gateway.integration;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Base64;
import java.util.Date;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext
class GatewayCircuitBreakerFallbackIntegrationTest {

    private static final String TEST_SECRET_BASE64 = "c3R5bGVyYWRhci1qd3Qtc2VjcmV0LWtleS0yMDI2LXBvcnRhbDgwODAtc3VwZXItc2VjdXJlCg==";
    private static final int UNUSED_PORT;

    static {
        try (ServerSocket socket = new ServerSocket(0)) {
            UNUSED_PORT = socket.getLocalPort();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Autowired
    private WebTestClient webTestClient;

    private SecretKey secretKey;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("TRYON_SERVICE_URL", () -> "http://127.0.0.1:" + UNUSED_PORT);
        registry.add("ESTADISTICAS_SERVICE_URL", () -> "http://127.0.0.1:" + UNUSED_PORT);
    }

    @BeforeEach
    void setUp() {
        byte[] decodedKey = Base64.getDecoder().decode(TEST_SECRET_BASE64);
        secretKey = Keys.hmacShaKeyFor(decodedKey);
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
    @DisplayName("Integración Fallback: POST /api/v1/try-on/consents con backend caído responde 503 y nunca 405")
    void shouldReturn503WhenTryOnPostTriggersCircuitBreakerFallback() {
        String token = generateBuyerToken("buyer-fallback-1");

        webTestClient.post()
                .uri("/api/v1/try-on/consents")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.service").isEqualTo("ms-probador-virtual");
    }

    @Test
    @DisplayName("Integración Fallback: DELETE /api/v1/try-on/consents/{userId} con backend caído responde 503 y nunca 405")
    void shouldReturn503WhenTryOnDeleteTriggersCircuitBreakerFallback() {
        String token = generateBuyerToken("buyer-fallback-1");

        webTestClient.delete()
                .uri("/api/v1/try-on/consents/user-1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.service").isEqualTo("ms-probador-virtual");
    }

    @Test
    @DisplayName("Integración Fallback: GET /api/v1/statistics con backend caído activa generalCircuitBreaker y responde 503 con correlationId")
    void shouldReturn503WhenGeneralRouteTriggersCircuitBreakerFallback() {
        String token = generateBuyerToken("buyer-fallback-2");

        webTestClient.get()
                .uri("/api/v1/statistics")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-Correlation-Id", "corr-cb-503")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.error").isEqualTo("Microservicio No Disponible")
                .jsonPath("$.correlationId").isEqualTo("corr-cb-503");
    }
}
