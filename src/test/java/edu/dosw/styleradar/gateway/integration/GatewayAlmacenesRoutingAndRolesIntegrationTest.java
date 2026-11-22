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
        NOTIFICACIONES.stop(0);
        CHAT.stop(0);
        INVENTARIO.stop(0);
        PLAYLIST.stop(0);
        DONACIONES.stop(0);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
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
    @DisplayName("Tiendas y Usuarios: /users/{id}/store va a Tiendas, /usuarios y /admin/usuarios a Usuarios, y /admin/* desconocido retorna 404")
    void shouldRouteUsersStoreAndPublicFoundationsToTiendas() {
        String token = token("42", List.of("BUYER"), null);
        webTestClient.get().uri("/api/v1/users/42/store")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.service").isEqualTo("ms-tiendas");
        webTestClient.get().uri("/api/v1/usuarios/42")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.service").isEqualTo("ms-usuarios");
        webTestClient.get().uri("/api/v1/admin/usuarios")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.service").isEqualTo("ms-usuarios");
        webTestClient.get().uri("/api/v1/admin/inexistente")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange()
                .expectStatus().isNotFound();
        assertRoutedTo("/api/v1/foundations", "ms-tiendas");
        assertRoutedTo("/api/v1/stores", "ms-tiendas");
        assertRoutedTo("/api/v1/stores/900555777-1", "ms-tiendas");
        assertRoutedTo("/api/v1/stores/900555777-1/photos", "ms-tiendas");
        webTestClient.get().uri("/api/v1/users/42/store").exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri("/api/v1/usuarios/42").exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri("/api/v1/admin/usuarios").exchange().expectStatus().isUnauthorized();
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
                .expectStatus().isUnauthorized();

        // 2. Ticket sin el propósito esperado recibe 401
        webTestClient.get().uri("/api/v1/users/42/notifications/stream?ticket=" + bearerToken).exchange()
                .expectStatus().isUnauthorized();
        webTestClient.get().uri("/api/v1/users/42/notifications/stream?ticket=" + otherPurposeTicket).exchange()
                .expectStatus().isUnauthorized();

        // 3. Ticket SSE usado en una ruta privada normal recibe 401
        webTestClient.get().uri("/api/v1/users/42/alerts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + sseTicket).exchange()
                .expectStatus().isUnauthorized();
        webTestClient.get().uri("/api/v1/usuarios/42")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + sseTicket).exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Chat: las 5 rutas de conversaciones exigen JWT y llegan a ms-chat sin colisionar con Usuarios, Tiendas ni Donaciones")
    void shouldRouteAllFiveChatConversationEndpointsToChatAndRequireJwt() {
        String bearerToken = token("42", List.of("BUYER"), null);
        for (String uri : List.of(
                "/api/v1/users/conversations",
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
        String authorizedQaOrigin = "https://styleradar-qa.azurewebsites.net";
        webTestClient.options().uri("/api/v1/stores")
                .header(HttpHeaders.ORIGIN, authorizedQaOrigin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, authorizedQaOrigin)
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");

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
}