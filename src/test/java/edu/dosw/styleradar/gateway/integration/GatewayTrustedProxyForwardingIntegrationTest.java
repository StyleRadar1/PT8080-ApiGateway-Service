package edu.dosw.styleradar.gateway.integration;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Verifica que cuando el Gateway recibe conexiones desde un proxy autorizado
 * (styleradar.gateway.trusted-proxies), resuelve y envía únicamente la IP del cliente
 * real hacia ms-auth sin permitir spoofing del cliente ni propagación de X-Real-IP / Forwarded.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"styleradar.gateway.trusted-proxies=127.0.0.1,::1"}
)
@ActiveProfiles("test")
@DirtiesContext
class GatewayTrustedProxyForwardingIntegrationTest {

    private static final HttpServer AUTH_MOCK;

    static {
        try {
            AUTH_MOCK = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            AUTH_MOCK.createContext("/", exchange -> {
                try {
                    exchange.getRequestBody().readAllBytes();
                    String body = "{"
                            + "\"service\":\"ms-auth\","
                            + "\"forwardedFor\":\"" + headerValue(exchange, "X-Forwarded-For") + "\","
                            + "\"realIp\":\"" + headerValue(exchange, "X-Real-IP") + "\","
                            + "\"forwarded\":\"" + headerValue(exchange, "Forwarded") + "\""
                            + "}";
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
            AUTH_MOCK.start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Autowired
    private WebTestClient webTestClient;

    private static String headerValue(com.sun.net.httpserver.HttpExchange exchange, String name) {
        List<String> values = exchange.getRequestHeaders().get(name);
        if (values == null || values.isEmpty()) {
            return "none";
        }
        return String.join(", ", values)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    @AfterAll
    static void stopMockServer() {
        AUTH_MOCK.stop(0);
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("AUTH_SERVICE_URL", () -> "http://127.0.0.1:" + AUTH_MOCK.getAddress().getPort());
    }

    @Test
    @DisplayName("Proxy confiable: extrae la IP derecha de X-Forwarded-For (formato Azure ARR con puerto) y descarta saltos spoofed")
    void shouldForwardOnlyRightmostUntrustedIpFromTrustedProxy() {
        webTestClient.post().uri("/api/v1/auth/login")
                .header("X-Forwarded-For", "1.2.3.4, 203.0.113.45:54321")
                .header("X-Real-IP", "9.9.9.9")
                .header("Forwarded", "for=8.8.8.8")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo("ms-auth")
                .jsonPath("$.forwardedFor").isEqualTo("203.0.113.45")
                .jsonPath("$.realIp").isEqualTo("none")
                .jsonPath("$.forwarded").isEqualTo("none");
    }
}
