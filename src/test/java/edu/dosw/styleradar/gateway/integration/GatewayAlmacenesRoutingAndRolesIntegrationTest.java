package edu.dosw.styleradar.gateway.integration;

import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.reactive.server.WebTestClient;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * Verifica el enrutamiento de /api/v1/almacenes/** (Geolocalizacion, Catalogo y Tiendas con prioridad correcta)
 * y la homologacion de roles / X-Almacen-Id hacia los microservicios downstream.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext
class GatewayAlmacenesRoutingAndRolesIntegrationTest {

    private static final String SECRET = "c3R5bGVyYWRhci1qd3Qtc2VjcmV0LWtleS0yMDI2LXBvcnRhbDgwODAtc3VwZXItc2VjdXJlCg==";
    private static final HttpServer GEO = newServer("ms-geolocalizacion");
    private static final HttpServer CATALOGO = newServer("ms-item-catalogo");
    private static final HttpServer TIENDAS = newServer("ms-tiendas");
    private static final HttpServer PAGOS = newServer("ms-pagos");
    private static final HttpServer USUARIOS = newServer("ms-usuarios");

    @Autowired
    private WebTestClient webTestClient;

    private static HttpServer newServer(String name) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try {
                    exchange.getRequestBody().readAllBytes();
                    String body = "{\"service\":\"" + name + "\","
                            + "\"path\":\"" + exchange.getRequestURI().getPath() + "\","
                            + "\"role\":\"" + h(exchange, "X-User-Role") + "\","
                            + "\"roles\":\"" + h(exchange, "X-User-Roles") + "\","
                            + "\"almacen\":\"" + h(exchange, "X-Almacen-Id") + "\"}";
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
            server.start();
            return server;
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static String h(com.sun.net.httpserver.HttpExchange ex, String name) {
        String v = ex.getRequestHeaders().getFirst(name);
        return v == null ? "none" : v;
    }

    @AfterAll
    static void stop() {
        GEO.stop(0);
        CATALOGO.stop(0);
        TIENDAS.stop(0);
        PAGOS.stop(0);
        USUARIOS.stop(0);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("GEOLOCALIZACION_SERVICE_URL", () -> "http://127.0.0.1:" + GEO.getAddress().getPort());
        registry.add("CATALOGO_SERVICE_URL", () -> "http://127.0.0.1:" + CATALOGO.getAddress().getPort());
        registry.add("TIENDAS_SERVICE_URL", () -> "http://127.0.0.1:" + TIENDAS.getAddress().getPort());
        registry.add("PAGOS_SERVICE_URL", () -> "http://127.0.0.1:" + PAGOS.getAddress().getPort());
        registry.add("USUARIOS_SERVICE_URL", () -> "http://127.0.0.1:" + USUARIOS.getAddress().getPort());
        registry.add("security.jwt.secret-base64", () -> SECRET);
    }

    private String token(String subject, Object roles, String almacenId) {
        SecretKey key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(SECRET.trim()));
        var builder = Jwts.builder().subject(subject).claim("roles", roles)
                .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 3600000));
        if (almacenId != null) {
            builder.claim("almacenId", almacenId);
        }
        return builder.signWith(key).compact();
    }

    private void assertRoutedTo(String uri, String service) {
        webTestClient.get().uri(uri).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.service").isEqualTo(service);
    }

    @Test
    @DisplayName("Almacenes: rutas de Geolocalizacion tienen prioridad sobre Tiendas")
    void shouldRouteGeolocationAlmacenesRoutes() {
        assertRoutedTo("/api/v1/almacenes", "ms-geolocalizacion");
        assertRoutedTo("/api/v1/almacenes/42/distancia?lat=4.6&lng=-74.0", "ms-geolocalizacion");
        assertRoutedTo("/api/v1/fundaciones/cercanas?lat=4.6&lng=-74.0", "ms-geolocalizacion");
    }

    @Test
    @DisplayName("Almacenes: rutas de catalogo/resumen/novedades llegan a Item-Catalogo")
    void shouldRouteCatalogoAlmacenesRoutes() {
        assertRoutedTo("/api/v1/almacenes/42/catalogo", "ms-item-catalogo");
        assertRoutedTo("/api/v1/almacenes/42/catalogo/resumen", "ms-item-catalogo");
        assertRoutedTo("/api/v1/almacenes/42/novedades", "ms-item-catalogo");
    }

    @Test
    @DisplayName("Almacenes: el resto de rutas propias de Tiendas continua llegando a Tiendas")
    void shouldKeepOtherAlmacenesRoutesInTiendas() {
        String token = token("7", List.of("ADMIN_STYLERADAR"), null);
        webTestClient.get().uri("/api/v1/almacenes/42")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.service").isEqualTo("ms-tiendas");
        webTestClient.post().uri("/api/v1/almacenes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.service").isEqualTo("ms-tiendas");
    }

    @Test
    @DisplayName("Roles: ADMIN_STYLERADAR se homologa a ADMIN en X-User-Role y se conserva en X-User-Roles")
    void shouldMapAdminStyleradarToAdmin() {
        String token = token("7", List.of("ADMIN_STYLERADAR"), null);
        webTestClient.get().uri("/api/v1/prendas")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo("ms-item-catalogo")
                .jsonPath("$.role").isEqualTo("ADMIN")
                .jsonPath("$.roles").isEqualTo("ADMIN_STYLERADAR,ADMIN");
    }

    @Test
    @DisplayName("X-Almacen-Id: se deriva solo del claim firmado y nunca del cliente")
    void shouldDeriveAlmacenIdOnlyFromSignedClaim() {
        String withClaim = token("9", List.of("ADMIN_ALMACEN"), "55");
        webTestClient.get().uri("/api/v1/almacenes/55/catalogo")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + withClaim)
                .header("X-Almacen-Id", "999")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.almacen").isEqualTo("55");

        String withoutClaim = token("9", List.of("ADMIN_ALMACEN"), null);
        webTestClient.get().uri("/api/v1/almacenes/55/catalogo")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + withoutClaim)
                .header("X-Almacen-Id", "999")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.almacen").isEqualTo("none");
    }

    @Test
    @DisplayName("Acceso publico: GET /api/v1/prendas funciona sin token; operaciones privadas siguen protegidas")
    void shouldAllowPublicPrendasAndProtectWrites() {
        assertRoutedTo("/api/v1/prendas", "ms-item-catalogo");
        webTestClient.post().uri("/api/v1/prendas").exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Pagos: rutas reales publicadas por ms-pagos llegan a Pagos (incluido /admin/pagos sobre /admin/**)")
    void shouldRoutePagosRealPaths() {
        String token = token("7", List.of("ADMIN_STYLERADAR"), null);
        for (String uri : List.of("/api/v1/suscripciones/5", "/api/v1/pagos/historial", "/api/v1/admin/pagos")) {
            webTestClient.get().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange()
                    .expectStatus().isOk().expectBody().jsonPath("$.service").isEqualTo("ms-pagos");
        }
        assertRoutedTo("/api/v1/planes", "ms-pagos");
    }

    @Test
    @DisplayName("Pagos: webhook de MercadoPago es publico (sin JWT) pero las operaciones de pago privadas exigen token")
    void shouldAllowWebhookAndProtectPrivatePagos() {
        webTestClient.post().uri("/api/v1/webhooks/mercadopago").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.service").isEqualTo("ms-pagos");
        webTestClient.post().uri("/api/v1/suscripciones/iniciar").exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri("/api/v1/pagos/historial").exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri("/api/v1/admin/pagos").exchange().expectStatus().isUnauthorized();
    }
}