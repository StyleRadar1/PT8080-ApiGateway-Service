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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifica el enrutamiento de /api/v1/almacenes/** (Geolocalizacion, Catalogo y Tiendas con prioridad correcta)
 * y la homologacion de roles / X-Almacen-Id hacia los microservicios downstream.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext
class GatewayAlmacenesRoutingAndRolesIntegrationTest {

    private static final String SECRET = "c3R5bGVyYWRhci1qd3Qtc2VjcmV0LWtleS0yMDI2LXBvcnRhbDgwODAtc3VwZXItc2VjdXJlCg==";
    private static final HttpServer AUTH = newServer("ms-auth");
    private static final HttpServer GEO = newServer("ms-geolocalizacion");
    private static final HttpServer CATALOGO = newServer("ms-item-catalogo");
    private static final HttpServer TIENDAS = newServer("ms-tiendas");
    private static final HttpServer PAGOS = newServer("ms-pagos");
    private static final HttpServer USUARIOS = newServer("ms-usuarios");
    private static final HttpServer NOTIFICACIONES = newServer("ms-notificaciones");
    private static final HttpServer CHAT = newServer("ms-chat");
    private static final HttpServer INVENTARIO = newServer("ms-inventario");
    private static final HttpServer PLAYLIST = newServer("ms-playlist");
    private static final HttpServer DONACIONES = newServer("ms-donaciones");
    private static final HttpServer ESTADISTICAS = newServer("ms-estadisticas");

    static {
        USUARIOS.createContext("/v3/api-docs", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                String openApiBody = "{"
                        + "\"openapi\":\"3.0.1\","
                        + "\"servers\":[{\"url\":\"http://localhost:8120\",\"description\":\"Local ms-usuarios\"}],"
                        + "\"paths\":{\"/api/v1/usuarios\":{\"post\":{\"summary\":\"Registrar usuario\"}}}"
                        + "}";
                byte[] bytes = openApiBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } finally {
                exchange.close();
            }
        });
        NOTIFICACIONES.createContext("/api/v1/users/99/notifications/stream", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                String rawQuery = exchange.getRequestURI().getRawQuery();
                String ssePayload = "id: evt-1\ndata: {\"lastEventId\":\"" + h(exchange, "Last-Event-ID")
                        + "\",\"query\":\"" + (rawQuery != null ? rawQuery : "") + "\"}\n\n";
                byte[] bytes = ssePayload.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream;charset=UTF-8");
                exchange.getResponseHeaders().set("Cache-Control", "no-cache");
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

    @Autowired
    private WebTestClient webTestClient;

    private static HttpServer newServer(String name) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try {
                    exchange.getRequestBody().readAllBytes();
                    String rawQuery = exchange.getRequestURI().getRawQuery();
                    String body = "{\"service\":\"" + name + "\","
                            + "\"path\":\"" + exchange.getRequestURI().getPath() + "\","
                            + "\"query\":\"" + (rawQuery != null ? rawQuery : "") + "\","
                            + "\"role\":\"" + h(exchange, "X-User-Role") + "\","
                            + "\"roles\":\"" + h(exchange, "X-User-Roles") + "\","
                            + "\"almacen\":\"" + h(exchange, "X-Almacen-Id") + "\","
                            + "\"forwardedFor\":\"" + h(exchange, "X-Forwarded-For") + "\","
                            + "\"lastEventId\":\"" + h(exchange, "Last-Event-ID") + "\","
                            + "\"storeId\":\"" + h(exchange, "X-Store-Id") + "\","
                            + "\"foundationId\":\"" + h(exchange, "X-Foundation-Id") + "\","
                            + "\"authorization\":\"" + h(exchange, "Authorization") + "\"}";
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
        List<String> values = ex.getRequestHeaders().get(name);
        return (values == null || values.isEmpty()) ? "none" : String.join(", ", values);
    }

    @AfterAll
    static void stop() {
        AUTH.stop(0);
        GEO.stop(0);
        CATALOGO.stop(0);
        TIENDAS.stop(0);
        PAGOS.stop(0);
        USUARIOS.stop(0);
        NOTIFICACIONES.stop(0);
        CHAT.stop(0);
        INVENTARIO.stop(0);
        PLAYLIST.stop(0);
        DONACIONES.stop(0);
        ESTADISTICAS.stop(0);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("AUTH_SERVICE_URL", () -> "http://127.0.0.1:" + AUTH.getAddress().getPort());
        registry.add("GEOLOCALIZACION_SERVICE_URL", () -> "http://127.0.0.1:" + GEO.getAddress().getPort());
        registry.add("CATALOGO_SERVICE_URL", () -> "http://127.0.0.1:" + CATALOGO.getAddress().getPort());
        registry.add("TIENDAS_SERVICE_URL", () -> "http://127.0.0.1:" + TIENDAS.getAddress().getPort());
        registry.add("PAGOS_SERVICE_URL", () -> "http://127.0.0.1:" + PAGOS.getAddress().getPort());
        registry.add("USUARIOS_SERVICE_URL", () -> "http://127.0.0.1:" + USUARIOS.getAddress().getPort());
        registry.add("NOTIFICACIONES_SERVICE_URL", () -> "http://127.0.0.1:" + NOTIFICACIONES.getAddress().getPort());
        registry.add("CHAT_SERVICE_URL", () -> "http://127.0.0.1:" + CHAT.getAddress().getPort());
        registry.add("INVENTARIO_SERVICE_URL", () -> "http://127.0.0.1:" + INVENTARIO.getAddress().getPort());
        registry.add("PLAYLIST_SERVICE_URL", () -> "http://127.0.0.1:" + PLAYLIST.getAddress().getPort());
        registry.add("DONACIONES_SERVICE_URL", () -> "http://127.0.0.1:" + DONACIONES.getAddress().getPort());
        registry.add("ESTADISTICAS_SERVICE_URL", () -> "http://127.0.0.1:" + ESTADISTICAS.getAddress().getPort());
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
    @DisplayName("Rutas en inglés: mapa, distancia y fundaciones van a Geolocalizacion y catálogo del almacén a Item-Catalogo sin token")
    void shouldRouteEnglishGeolocationAndCatalogRoutes() {
        assertRoutedTo("/api/v1/stores/nearby?lat=4.6&lng=-74.0", "ms-geolocalizacion");
        assertRoutedTo("/api/v1/stores/42/distance?lat=4.6&lng=-74.0", "ms-geolocalizacion");
        assertRoutedTo("/api/v1/foundations/nearby?lat=4.6&lng=-74.0", "ms-geolocalizacion");
        assertRoutedTo("/api/v1/stores/42/catalog", "ms-item-catalogo");
        assertRoutedTo("/api/v1/stores/42/catalog/summary", "ms-item-catalogo");
        assertRoutedTo("/api/v1/stores/42/new-arrivals", "ms-item-catalogo");
        assertRoutedTo("/api/v1/garments", "ms-item-catalogo");
        assertRoutedTo("/api/v1/garments/42", "ms-item-catalogo");
        assertRoutedTo("/api/v1/garments/42/similar", "ms-item-catalogo");
        assertRoutedTo("/api/v1/garments/42/size-guide", "ms-item-catalogo");
    }

    @Test
    @DisplayName("Rutas en inglés: publicar y moderar prendas siguen exigiendo token")
    void shouldProtectEnglishGarmentWritesAndPending() {
        webTestClient.post().uri("/api/v1/garments").exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri("/api/v1/garments/pending").exchange().expectStatus().isUnauthorized();
        String adminToken = token("7", List.of("STYLERADAR_ADMIN"), null);
        webTestClient.get().uri("/api/v1/garments/pending")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.service").isEqualTo("ms-item-catalogo");
        webTestClient.get().uri("/api/v1/stores/900555777-1/catalog-settings")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.service").isEqualTo("ms-tiendas");
    }

    @Test
    @DisplayName("Tiendas: rutas reales (/stores) llegan a Tiendas y rutas obsoletas (/almacenes/{id}) no caen alli")
    void shouldKeepOtherAlmacenesRoutesInTiendas() {
        String token = token("7", List.of("ADMIN_STYLERADAR"), null);
        webTestClient.get().uri("/api/v1/stores/900555777-1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.service").isEqualTo("ms-tiendas");
        webTestClient.post().uri("/api/v1/stores")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.service").isEqualTo("ms-tiendas");
        webTestClient.get().uri("/api/v1/almacenes/42")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange()
                .expectStatus().isNotFound();
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
    @DisplayName("X-Almacen-Id: soporta UUID canónico firmado en JWT y elimina cabeceras cliente no confiables")
    void shouldDeriveAlmacenIdOnlyFromSignedClaim() {
        String canonicalUuid = java.util.UUID.randomUUID().toString();
        String withClaim = token("9", List.of("ADMIN_ALMACEN"), canonicalUuid);
        webTestClient.get().uri("/api/v1/almacenes/" + canonicalUuid + "/catalogo")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + withClaim)
                .header("X-Almacen-Id", "spoofed-client-uuid-999")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.almacen").isEqualTo(canonicalUuid);

        // Sin claim en JWT (comportamiento actual de Auth): Gateway no inventa cabecera y limpia la del cliente
        String withoutClaim = token("9", List.of("ADMIN_ALMACEN"), null);
        webTestClient.get().uri("/api/v1/almacenes/" + canonicalUuid + "/catalogo")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + withoutClaim)
                .header("X-Almacen-Id", "spoofed-client-uuid-999")
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
    @DisplayName("Pagos: rutas reales en inglés llegan a Pagos y admin/payments exige rol ADMIN_STYLERADAR")
    void shouldRoutePagosRealPaths() {
        String adminToken = token("7", List.of("STYLERADAR_ADMIN"), null);
        for (String uri : List.of(
                "/api/v1/subscriptions/5",
                "/api/v1/payments/history",
                "/api/v1/plans",
                "/api/v1/admin/payments"
        )) {
            webTestClient.get().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange()
                    .expectStatus().isOk().expectBody()
                    .jsonPath("$.service").isEqualTo("ms-pagos")
                    .jsonPath("$.role").value(role -> assertTrue(role.toString().contains("ADMIN_STYLERADAR")));
        }

        String multiRoleBuyerToken = token("12", List.of("BUYER", "STORE_ADMIN"), null);
        webTestClient.get().uri("/api/v1/subscriptions/12")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + multiRoleBuyerToken).exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.service").isEqualTo("ms-pagos")
                .jsonPath("$.role").isEqualTo("BUYER,STORE_ADMIN");

        webTestClient.get().uri("/api/v1/admin/payments")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + multiRoleBuyerToken).exchange()
                .expectStatus().isForbidden();
    }

    @Test
    @DisplayName("Pagos: webhook de MercadoPago es publico (sin JWT) pero las operaciones de pago privadas y /plans exigen token")
    void shouldAllowWebhookAndProtectPrivatePagos() {
        webTestClient.post().uri("/api/v1/webhooks/mercadopago").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.service").isEqualTo("ms-pagos");
        webTestClient.post().uri("/api/v1/subscriptions/start").exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri("/api/v1/payments/history").exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri("/api/v1/plans").exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri("/api/v1/admin/payments").exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Tiendas y Usuarios: /users/{id}/store va a Tiendas, /usuarios, /admin/usuarios e /internal/users/* retornan 404 con JWT y 401 sin JWT")
    void shouldRouteUsersStoreAndPublicFoundationsToTiendas() {
        String token = token("42", List.of("BUYER"), null);
        webTestClient.get().uri("/api/v1/users/42/store")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.service").isEqualTo("ms-tiendas");
        webTestClient.get().uri("/api/v1/usuarios/42")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange()
                .expectStatus().isNotFound();
        webTestClient.get().uri("/api/v1/admin/usuarios")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange()
                .expectStatus().isNotFound();
        webTestClient.get().uri("/api/v1/internal/users/42/measurements")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange()
                .expectStatus().isNotFound();
        webTestClient.post().uri("/api/v1/internal/users/verify-credentials")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange()
                .expectStatus().isNotFound();
        webTestClient.get().uri("/api/v1/admin/inexistente")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange()
                .expectStatus().isNotFound();
        assertRoutedTo("/api/v1/foundations", "ms-tiendas");
        assertRoutedTo("/api/v1/stores", "ms-tiendas");
        assertRoutedTo("/api/v1/stores/900555777-1", "ms-tiendas");
        assertRoutedTo("/api/v1/stores/900555777-1/photos", "ms-tiendas");
        webTestClient.get().uri("/api/v1/users/42/store").exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri("/api/v1/users/42").exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri("/api/v1/admin/users").exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri("/api/v1/usuarios/42").exchange().expectStatus().isNotFound();
        webTestClient.get().uri("/api/v1/admin/usuarios").exchange().expectStatus().isNotFound();
        webTestClient.get().uri("/api/v1/internal/users/42/measurements").exchange().expectStatus().isNotFound();
        webTestClient.post().uri("/api/v1/internal/users/verify-credentials").exchange().expectStatus().isNotFound();
    }

    @Test
    @DisplayName("Notificaciones: alertas, preferencias y alcance estricto del stream SSE en rutas reales con ?ticket= y purpose=sse-stream")
    void shouldRouteNotificationAlertsPreferencesAndSseTicketToNotificaciones() {
        String bearerToken = token("42", List.of("BUYER"), null);
        for (String uri : List.of(
                "/api/v1/users/42/alerts",
                "/api/v1/users/42/alerts/10",
                "/api/v1/users/42/notification-preferences",
                "/api/v1/users/42/notification-preferences/EMAIL"
        )) {
            webTestClient.get().uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken).exchange()
                    .expectStatus().isOk().expectBody().jsonPath("$.service").isEqualTo("ms-notificaciones");
        }

        SecretKey key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(SECRET.trim()));
        String sseTicket = Jwts.builder()
                .subject("42")
                .claim("purpose", "sse-stream")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(key)
                .compact();
        String otherPurposeTicket = Jwts.builder()
                .subject("42")
                .claim("purpose", "other")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(key)
                .compact();

        // 1. Ticket correcto (purpose=sse-stream) abre el stream SSE real
        webTestClient.get().uri("/api/v1/users/42/notifications/stream?ticket=" + sseTicket).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.service").isEqualTo("ms-notificaciones");
        webTestClient.get().uri("/api/v1/users/42/notifications/stream").exchange()
                .expectStatus().isUnauthorized();
        webTestClient.get().uri("/api/v1/usuarios/42/notificaciones/stream?ticket=" + sseTicket).exchange()
                .expectStatus().isNotFound();

        // 2. Ticket sin el propósito esperado recibe 401
        webTestClient.get().uri("/api/v1/users/42/notifications/stream?ticket=" + bearerToken).exchange()
                .expectStatus().isUnauthorized();
        webTestClient.get().uri("/api/v1/users/42/notifications/stream?ticket=" + otherPurposeTicket).exchange()
                .expectStatus().isUnauthorized();

        // 3. Ticket SSE usado en una ruta privada normal recibe 401
        webTestClient.get().uri("/api/v1/users/42/alerts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + sseTicket).exchange()
                .expectStatus().isUnauthorized();
        webTestClient.get().uri("/api/v1/users/42")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + sseTicket).exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Notificaciones (contrato final): reenvía Authorization, Last-Event-ID, query params, SSE sin duplicar CORS y /v3/api-docs/notifications")
    void shouldForwardNotificationsQueriesHeadersSseCorsAndSwagger() {
        String bearerToken = token("42", List.of("BUYER"), null);

        // Todos los endpoints REST requieren JWT
        for (String privatePath : List.of(
                "/api/v1/users/42/notifications?read=false&page=0&size=20",
                "/api/v1/users/42/notifications/unread/count",
                "/api/v1/users/42/alerts/check",
                "/api/v1/users/42/notification-preferences"
        )) {
            webTestClient.get().uri(privatePath).exchange().expectStatus().isUnauthorized();
        }

        // Query params (?read=, ?page=, ?size=, ?ids=) y Authorization se reenvían intactos
        webTestClient.get().uri("/api/v1/users/42/notifications?read=false&page=0&size=20")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo("ms-notificaciones")
                .jsonPath("$.query").isEqualTo("read=false&page=0&size=20")
                .jsonPath("$.authorization").isEqualTo("Bearer " + bearerToken);

        webTestClient.delete().uri("/api/v1/users/42/notifications?ids=1,2")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo("ms-notificaciones")
                .jsonPath("$.query").isEqualTo("ids=1,2");

        // Resto de métodos/rutas del contrato final en inglés
        webTestClient.post().uri("/api/v1/users/42/notifications/stream-ticket")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.service").isEqualTo("ms-notificaciones");
        webTestClient.patch().uri("/api/v1/users/42/notifications/read-all")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.service").isEqualTo("ms-notificaciones");
        webTestClient.patch().uri("/api/v1/users/42/alerts/10/activate")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.service").isEqualTo("ms-notificaciones");
        webTestClient.put().uri("/api/v1/users/42/notification-preferences/EMAIL")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.service").isEqualTo("ms-notificaciones");
        webTestClient.get().uri("/api/v1/users/42/alerts/check")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.service").isEqualTo("ms-notificaciones");
        webTestClient.get().uri("/api/v1/users/42/notifications/unread/count")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.service").isEqualTo("ms-notificaciones");

        // Stream SSE real con ?ticket=, Last-Event-ID y CORS único emitido por el Gateway
        SecretKey key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(SECRET.trim()));
        String sseTicket = Jwts.builder()
                .subject("99")
                .claim("purpose", "sse-stream")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(key)
                .compact();

        var sseResult = webTestClient.get().uri("/api/v1/users/99/notifications/stream?ticket=" + sseTicket)
                .header("Last-Event-ID", "evt-42")
                .header(HttpHeaders.ORIGIN, "https://styleradar-qa.vercel.app")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(org.springframework.http.MediaType.TEXT_EVENT_STREAM)
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://styleradar-qa.vercel.app")
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true")
                .expectBody(String.class)
                .returnResult();

        String sseBody = sseResult.getResponseBody();
        assertTrue(sseBody != null && sseBody.contains("evt-42"), "El stream SSE debe recibir Last-Event-ID");
        assertTrue(sseBody.contains("ticket=" + sseTicket), "El stream SSE debe recibir el query param ?ticket=");

        // Swagger público en inglés y español
        for (String docsUri : List.of("/v3/api-docs/notifications", "/v3/api-docs/notificaciones")) {
            webTestClient.get().uri(docsUri).exchange()
                    .expectStatus().isOk().expectBody().jsonPath("$.service").isEqualTo("ms-notificaciones");
        }
    }

    @Test
    @DisplayName("Estadísticas: exige JWT en los 3 endpoints core, sanea X-Store-Id/X-Foundation-Id y expone Swagger")
    void shouldRouteStatisticsStripSpoofedScopeHeadersAndExposeSwagger() {
        for (String statsPath : List.of(
                "/api/v1/statistics/summary/totals/PLATFORM_MONTHLY_DONATIONS",
                "/api/v1/statistics/time-series/PLATFORM_USER_REGISTRATIONS",
                "/api/v1/statistics/distributions/STORE_INVENTORY_BY_STATE"
        )) {
            webTestClient.get().uri(statsPath).exchange().expectStatus().isUnauthorized();
        }

        String storeAdminToken = token("42", List.of("STORE_ADMIN"), "store-real-900");
        webTestClient.get()
                .uri("/api/v1/statistics/time-series/PLATFORM_USER_REGISTRATIONS?startDate=2026-01-01&endDate=2026-11-01&granularity=MONTHLY")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + storeAdminToken)
                .header("X-Store-Id", "spoofed-store")
                .header("X-Foundation-Id", "spoofed-foundation")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo("ms-estadisticas")
                .jsonPath("$.query").isEqualTo("startDate=2026-01-01&endDate=2026-11-01&granularity=MONTHLY")
                .jsonPath("$.authorization").isEqualTo("Bearer " + storeAdminToken)
                .jsonPath("$.storeId").isEqualTo("none")
                .jsonPath("$.foundationId").isEqualTo("none");

        for (String docsUri : List.of("/v3/api-docs/statistics", "/v3/api-docs/estadisticas")) {
            webTestClient.get().uri(docsUri).exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.service").isEqualTo("ms-estadisticas")
                    .jsonPath("$.path").isEqualTo("/api/v1/v3/api-docs");
        }
    }

    @Test
    @DisplayName("Chat: las 6 rutas de conversaciones exigen JWT y llegan a ms-chat sin colisionar con Usuarios, Tiendas ni Donaciones")
    void shouldRouteAllFiveChatConversationEndpointsToChatAndRequireJwt() {
        String bearerToken = token("42", List.of("BUYER"), null);
        for (String uri : List.of(
                "/api/v1/users/conversations",
                "/api/v1/users/42/conversations",
                "/api/v1/stores/conversations",
                "/api/v1/stores/900555777-1/conversations",
                "/api/v1/donations/10/conversations",
                "/api/v1/second-hand/publications/20/conversations"
        )) {
            webTestClient.get().uri(uri).exchange().expectStatus().isUnauthorized();
            webTestClient.get().uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken).exchange()
                    .expectStatus().isOk().expectBody().jsonPath("$.service").isEqualTo("ms-chat");
            webTestClient.post().uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken).exchange()
                    .expectStatus().isOk().expectBody().jsonPath("$.service").isEqualTo("ms-chat");
        }
    }

    @Test
    @DisplayName("Catalogo, Inventario y Playlists: prendas/pendientes exige JWT y raices /inventario y /playlists enrutan correctamente")
    void shouldProtectPrendasPendientesAndRouteInventarioAndPlaylistsRoots() {
        String adminToken = token("7", List.of("STYLERADAR_ADMIN"), null);
        webTestClient.get().uri("/api/v1/prendas/pendientes").exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri("/api/v1/prendas/pendientes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.service").isEqualTo("ms-item-catalogo");

        String buyerToken = token("42", List.of("BUYER"), null);
        for (String uri : List.of("/api/v1/inventario", "/api/v1/inventario/1")) {
            webTestClient.get().uri(uri).exchange().expectStatus().isUnauthorized();
            webTestClient.get().uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken).exchange()
                    .expectStatus().isOk().expectBody().jsonPath("$.service").isEqualTo("ms-inventario");
        }

        webTestClient.get().uri("/api/v1/playlists")
                .exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.service").isEqualTo("ms-playlist");
        webTestClient.get().uri("/api/v1/playlists")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.service").isEqualTo("ms-playlist");

        for (String uri : List.of("/api/v1/playlists/me", "/api/v1/playlists/me/guardadas")) {
            webTestClient.get().uri(uri).exchange().expectStatus().isUnauthorized();
            webTestClient.get().uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken).exchange()
                    .expectStatus().isOk().expectBody().jsonPath("$.service").isEqualTo("ms-playlist");
        }
    }

    @Test
    @DisplayName("CORS Preflight: autoriza origen exacto del frontend QA y rechaza dominios ajenos del mismo proveedor")
    void shouldAllowAuthorizedQaFrontendCorsPreflightAndRejectForeignProviderOrigin() {
        for (String authorizedQaOrigin : List.of(
                "https://styleradar-qa.azurewebsites.net",
                "https://styleradar-qa.vercel.app"
        )) {
            webTestClient.options().uri("/api/v1/stores")
                    .header(HttpHeaders.ORIGIN, authorizedQaOrigin)
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, authorizedQaOrigin)
                    .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        }

        for (String foreignOrigin : List.of(
                "https://otro-equipo-ajeno.azurewebsites.net",
                "https://app-ajena.vercel.app",
                "https://app-ajena.azurestaticapps.net"
        )) {
            webTestClient.options().uri("/api/v1/stores")
                    .header(HttpHeaders.ORIGIN, foreignOrigin)
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                    .exchange()
                    .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)
                    .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS);
        }
    }

    @Test
    @DisplayName("CORS Preflight en rutas Method=GET: autoriza Vercel/Vite en almacenes/catalogo/distancia/fundaciones y rechaza ajenos")
    void shouldAllowCorsPreflightOnMethodGetAlmacenesRoutesAndRejectForeignOrigin() {
        for (String authorizedOrigin : List.of("https://styleradar-qa.vercel.app", "http://localhost:5173")) {
            for (String path : List.of(
                    "/api/v1/almacenes",
                    "/api/v1/almacenes/123/catalogo",
                    "/api/v1/almacenes/123/distancia",
                    "/api/v1/fundaciones/cercanas"
            )) {
                webTestClient.options().uri(path)
                        .header(HttpHeaders.ORIGIN, authorizedOrigin)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type,authorization")
                        .exchange()
                        .expectStatus().isOk()
                        .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, authorizedOrigin)
                        .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
            }
        }

        webTestClient.options().uri("/api/v1/almacenes")
                .header(HttpHeaders.ORIGIN, "https://app-ajena.vercel.app")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type,authorization")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    @Test
    @DisplayName("Swagger Aggregator: /v3/api-docs/users y /usuarios exponen '/' como primer server; POST /api/v1/users pasa sin JWT y /usuarios retorna 401")
    void shouldRewriteUsuariosOpenApiServersToGatewayAndAllowPublicPostRegistration() {
        for (String docsUri : List.of("/v3/api-docs/users", "/v3/api-docs/usuarios")) {
            webTestClient.get().uri(docsUri)
                    .header(HttpHeaders.ACCEPT_ENCODING, "gzip")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.servers[0].url").isEqualTo("/")
                    .jsonPath("$.servers[1].url").isEqualTo("http://localhost:8120")
                    .jsonPath("$.paths['/api/v1/usuarios'].post.summary").isEqualTo("Registrar usuario");
        }

        webTestClient.post().uri("/api/v1/users")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo("ms-usuarios")
                .jsonPath("$.path").isEqualTo("/api/v1/users");

        webTestClient.post().uri("/api/v1/usuarios")
                .exchange()
                .expectStatus().isNotFound();

        webTestClient.post().uri("/api/v1/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token.invalido.firma")
                .exchange()
                .expectStatus().isUnauthorized();

        webTestClient.get().uri("/v3/api-docs/swagger-config")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("Usuarios en ingles y precedencia: enruta /api/v1/users y /admin/users sin colisionar con Chat, Tiendas ni Notificaciones")
    void shouldRouteEnglishUsersEndpointsAndKeepPrecedenceOverChatTiendasNotifications() {
        webTestClient.options().uri("/api/v1/users")
                .header(HttpHeaders.ORIGIN, "https://styleradar-qa.vercel.app")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://styleradar-qa.vercel.app");

        webTestClient.post().uri("/api/v1/users")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo("ms-usuarios")
                .jsonPath("$.path").isEqualTo("/api/v1/users");

        String buyerToken = token("12", List.of("BUYER"), null);
        String adminToken = token("1", List.of("STYLERADAR_ADMIN"), null);

        for (String userReadUri : List.of(
                "/api/v1/users/12",
                "/api/v1/users/12/feed",
                "/api/v1/users/12/recommendations",
                "/api/v1/users/12/saved-searches",
                "/api/v1/admin/users"
        )) {
            webTestClient.get().uri(userReadUri).exchange().expectStatus().isUnauthorized();
            String authToken = userReadUri.startsWith("/api/v1/admin/") ? adminToken : buyerToken;
            webTestClient.get().uri(userReadUri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + authToken)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.service").isEqualTo("ms-usuarios");
        }

        for (String userPutUri : List.of(
                "/api/v1/users/12",
                "/api/v1/users/12/sizes",
                "/api/v1/users/12/style-preferences"
        )) {
            webTestClient.put().uri(userPutUri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.service").isEqualTo("ms-usuarios");
        }

        webTestClient.patch().uri("/api/v1/admin/users/12/status")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.service").isEqualTo("ms-usuarios");

        webTestClient.delete().uri("/api/v1/users/12/saved-searches/99")
                .exchange()
                .expectStatus().isUnauthorized();
        webTestClient.delete().uri("/api/v1/users/12/saved-searches/99")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.service").isEqualTo("ms-usuarios");

        webTestClient.delete().uri("/api/v1/admin/users/12")
                .exchange()
                .expectStatus().isUnauthorized();
        webTestClient.delete().uri("/api/v1/admin/users/12")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.service").isEqualTo("ms-usuarios");

        webTestClient.get().uri("/api/v1/users/conversations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.service").isEqualTo("ms-chat");

        webTestClient.get().uri("/api/v1/users/12/store")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.service").isEqualTo("ms-tiendas");

        for (String notifUri : List.of(
                "/api/v1/users/me/notifications",
                "/api/v1/users/me/alerts",
                "/api/v1/users/me/notification-preferences"
        )) {
            webTestClient.get().uri(notifUri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.service").isEqualTo("ms-notificaciones");
        }

        webTestClient.get().uri("/api/v1/plans/1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.service").isEqualTo("ms-pagos");

        webTestClient.get().uri("/api/v1/admin/payments/99")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.service").isEqualTo("ms-pagos");
    }

    @Test
    @DisplayName("CADche (ms-auth y ms-tiendas): X-Forwarded-For, Authorization Bearer, rutas DELETE admin y fotos sin prefijo abierto")
    void shouldEnforceCadcheAuthAndStoresFinalContract() {
        String buyerToken = token("7", List.of("BUYER"), null);
        String adminToken = token("1", List.of("STYLERADAR_ADMIN"), null);

        // ms-auth: POST /api/v1/auth/login es público y recibe X-Forwarded-For
        webTestClient.post().uri("/api/v1/auth/login")
                .header("X-Forwarded-For", "203.0.113.45")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo("ms-auth")
                .jsonPath("$.forwardedFor").value(org.hamcrest.Matchers.containsString("203.0.113.45"));

        // ms-auth: POST /api/v1/auth/logout exige Bearer JWT y reenvía Authorization + X-Forwarded-For
        webTestClient.post().uri("/api/v1/auth/logout")
                .exchange()
                .expectStatus().isUnauthorized();

        webTestClient.post().uri("/api/v1/auth/logout")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken)
                .header("X-Forwarded-For", "203.0.113.45")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo("ms-auth")
                .jsonPath("$.authorization").value(org.hamcrest.Matchers.startsWith("Bearer "))
                .jsonPath("$.forwardedFor").value(org.hamcrest.Matchers.containsString("203.0.113.45"));

        // ms-tiendas: nuevas rutas DELETE admin exigen JWT y reenvían Authorization
        for (String deleteUri : List.of(
                "/api/v1/admin/stores/900555777-1",
                "/api/v1/admin/foundations/5"
        )) {
            webTestClient.delete().uri(deleteUri).exchange().expectStatus().isUnauthorized();
            webTestClient.delete().uri(deleteUri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.service").isEqualTo("ms-tiendas")
                    .jsonPath("$.authorization").value(org.hamcrest.Matchers.startsWith("Bearer "));
        }

        // ms-tiendas: GET /stores/{nit}/photos público, pero /photos/{photoId} (GET y DELETE) protegido
        webTestClient.get().uri("/api/v1/stores/900555777-1/photos")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo("ms-tiendas");

        webTestClient.get().uri("/api/v1/stores/900555777-1/photos/42")
                .exchange()
                .expectStatus().isUnauthorized();

        webTestClient.delete().uri("/api/v1/stores/900555777-1/photos/42")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}