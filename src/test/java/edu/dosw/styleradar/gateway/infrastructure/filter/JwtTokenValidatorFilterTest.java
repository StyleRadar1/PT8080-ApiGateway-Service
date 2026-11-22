package edu.dosw.styleradar.gateway.infrastructure.filter;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenValidatorFilterTest {

    private static final String SECRET_BASE64 = "c3R5bGVyYWRhci1qd3Qtc2VjcmV0LWtleS0yMDI2LXBvcnRhbDgwODAtc3VwZXItc2VjdXJlCg==";
    private JwtTokenValidatorFilter filter;
    private SecretKey secretKey;

    @BeforeEach
    void setUp() {
        filter = new JwtTokenValidatorFilter(SECRET_BASE64);
        byte[] keyBytes = Base64.getDecoder().decode(SECRET_BASE64.trim());
        secretKey = Keys.hmacShaKeyFor(keyBytes);
    }

    @Test
    @DisplayName("Ruta Pública sin Authorization: Permite continuar hacia downstream y elimina cabeceras X-User-* inyectadas por el cliente")
    void filter_rutaPublicaSinAuthorizationHeader_eliminaHeadersSpoofedYContinua() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/auth/login")
                .header(JwtTokenValidatorFilter.USER_ID_HEADER, "attacker-spoofed-id")
                .header(JwtTokenValidatorFilter.USER_ROLE_HEADER, "ROLE_ADMIN")
                .header(JwtTokenValidatorFilter.USER_ROLES_HEADER, "ROLE_ADMIN,ROLE_SUPERADMIN")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean chainCalled = new AtomicBoolean(false);

        Mono<Void> result = filter.filter(exchange, ex -> {
            chainCalled.set(true);
            assertNull(ex.getRequest().getHeaders().getFirst(JwtTokenValidatorFilter.USER_ID_HEADER),
                    "La cabecera X-User-Id del cliente debió ser eliminada para prevenir spoofing");
            assertNull(ex.getRequest().getHeaders().getFirst(JwtTokenValidatorFilter.USER_ROLE_HEADER),
                    "La cabecera X-User-Role del cliente debió ser eliminada para prevenir spoofing");
            assertNull(ex.getRequest().getHeaders().getFirst(JwtTokenValidatorFilter.USER_ROLES_HEADER),
                    "La cabecera X-User-Roles del cliente debió ser eliminada para prevenir spoofing");
            return Mono.empty();
        });

        StepVerifier.create(result).verifyComplete();
        assertTrue(chainCalled.get(), "La cadena de filtros debió continuar para petición pública sin token");
    }

    @Test
    @DisplayName("Ruta Protegida sin Authorization: Rechaza inmediatamente con 401 Unauthorized y NO llama a downstream")
    void filter_rutaProtegidaSinAuthorization_retorna401Unauthorized() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/try-on/cuotas")
                .header(JwtTokenValidatorFilter.USER_ID_HEADER, "attacker-spoofed-id")
                .header(JwtTokenValidatorFilter.USER_ROLES_HEADER, "ROLE_ADMIN")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean chainCalled = new AtomicBoolean(false);

        Mono<Void> result = filter.filter(exchange, ex -> {
            chainCalled.set(true);
            return Mono.empty();
        });

        StepVerifier.create(result).verifyComplete();
        assertFalse(chainCalled.get(), "Downstream no debe ser invocado cuando falta el token en ruta protegida");
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("Con Token JWT Válido: Inyecta X-User-Id, X-User-Role y X-User-Roles auténticos y sobreescribe cualquier intento de spoofing")
    void filter_conTokenValido_inyectaHeadersDeUsuarioYRemueveSpoofed() {
        String token = Jwts.builder()
                .subject("user-999-portal8080")
                .claim("role", "ROLE_CLIENTE")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600000))
                .signWith(secretKey)
                .compact();

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/try-on/cuotas")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(JwtTokenValidatorFilter.USER_ID_HEADER, "spoofed-user")
                .header(JwtTokenValidatorFilter.USER_ROLE_HEADER, "ROLE_SUPERADMIN")
                .header(JwtTokenValidatorFilter.USER_ROLES_HEADER, "ROLE_SUPERADMIN")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean chainCalled = new AtomicBoolean(false);

        Mono<Void> result = filter.filter(exchange, ex -> {
            chainCalled.set(true);
            assertEquals("user-999-portal8080", ex.getRequest().getHeaders().getFirst(JwtTokenValidatorFilter.USER_ID_HEADER));
            assertEquals("ROLE_CLIENTE", ex.getRequest().getHeaders().getFirst(JwtTokenValidatorFilter.USER_ROLE_HEADER));
            assertEquals("ROLE_CLIENTE", ex.getRequest().getHeaders().getFirst(JwtTokenValidatorFilter.USER_ROLES_HEADER));
            return Mono.empty();
        });

        StepVerifier.create(result).verifyComplete();
        assertTrue(chainCalled.get(), "La cadena de filtros debió invocarse con petición autenticada");
    }

    @Test
    @DisplayName("Con Token JWT con lista de roles: Inyecta X-User-Roles concatenado y elimina spoofing previo")
    void filter_conTokenValidoListaRoles_inyectaUserRolesConcatenado() {
        String token = Jwts.builder()
                .subject("user-fundacion-555")
                .claim("roles", List.of("ROLE_FOUNDATION", "ROLE_VOLUNTARIO"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600000))
                .signWith(secretKey)
                .compact();

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/donations/foundation/555")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(JwtTokenValidatorFilter.USER_ROLES_HEADER, "ROLE_HACKER")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean chainCalled = new AtomicBoolean(false);

        Mono<Void> result = filter.filter(exchange, ex -> {
            chainCalled.set(true);
            assertEquals("user-fundacion-555", ex.getRequest().getHeaders().getFirst(JwtTokenValidatorFilter.USER_ID_HEADER));
            assertEquals("ROLE_FOUNDATION,ROLE_VOLUNTARIO", ex.getRequest().getHeaders().getFirst(JwtTokenValidatorFilter.USER_ROLE_HEADER));
            assertEquals("ROLE_FOUNDATION,ROLE_VOLUNTARIO", ex.getRequest().getHeaders().getFirst(JwtTokenValidatorFilter.USER_ROLES_HEADER));
            return Mono.empty();
        });

        StepVerifier.create(result).verifyComplete();
        assertTrue(chainCalled.get());
    }

    @Test
    @DisplayName("Con Token JWT Inválido o Expirado: Rechaza con 401 Unauthorized y NO permite continuar hacia downstream")
    void filter_conTokenInvalido_retorna401Unauthorized() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/try-on/cuotas")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token-invalido-o-expirado")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean chainCalled = new AtomicBoolean(false);

        Mono<Void> result = filter.filter(exchange, ex -> {
            chainCalled.set(true);
            return Mono.empty();
        });

        StepVerifier.create(result).verifyComplete();
        assertFalse(chainCalled.get(), "La cadena hacia microservicios NO debe invocarse cuando el token es inválido");
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("Con Token Bearer Vacío: Rechaza con 401 Unauthorized y NO permite continuar")
    void filter_conTokenBearerVacio_retorna401Unauthorized() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/try-on/cuotas")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean chainCalled = new AtomicBoolean(false);

        Mono<Void> result = filter.filter(exchange, ex -> {
            chainCalled.set(true);
            return Mono.empty();
        });

        StepVerifier.create(result).verifyComplete();
        assertFalse(chainCalled.get());
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("Inicialización con clave JWT_SECRET_BASE64: Valida firma correctamente decodificando Base64")
    void filter_inicializacionConClaveBase64_validaTokenExitosamente() {
        JwtTokenValidatorFilter customFilter = new JwtTokenValidatorFilter(SECRET_BASE64);

        String token = Jwts.builder()
                .subject("usuario-base64-test")
                .claim("role", "ROLE_TIENDA")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600000))
                .signWith(secretKey)
                .compact();

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/catalogo/items/1/tallas-medidas")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        Mono<Void> result = customFilter.filter(exchange, ex -> {
            assertEquals("usuario-base64-test", ex.getRequest().getHeaders().getFirst(JwtTokenValidatorFilter.USER_ID_HEADER));
            assertEquals("ROLE_TIENDA", ex.getRequest().getHeaders().getFirst(JwtTokenValidatorFilter.USER_ROLE_HEADER));
            return Mono.empty();
        });

        StepVerifier.create(result).verifyComplete();
    }

    @Test
    @DisplayName("Discriminación de rutas públicas vs protegidas: isPublicPath valida correctamente los patrones")
    void isPublicPath_validaPatronesCorrectamente() {
        assertTrue(filter.isPublicPath("/actuator/health"));
        assertTrue(filter.isPublicPath("/actuator/info"));
        assertTrue(filter.isPublicPath("/swagger-ui.html"));
        assertTrue(filter.isPublicPath("/swagger-ui/index.html"));
        assertTrue(filter.isPublicPath("/v3/api-docs"));
        assertTrue(filter.isPublicPath("/v3/api-docs/probador"));
        assertTrue(filter.isPublicPath("/api/v1/auth/login"));
        assertTrue(filter.isPublicPath("/api/v1/auth/register"));
        assertTrue(filter.isPublicPath("/api/v1/auth/otp/verify"));
        assertTrue(filter.isPublicPath("/api/v1/auth/oauth2/google"));
        assertFalse(filter.isPublicPath("/api/v1/auth/logout"));
        assertFalse(filter.isPublicPath("/api/v1/auth/logout/all"));
        assertTrue(filter.isPublicPath("/fallback/probador"));
        assertTrue(filter.isPublicPath("/ws"));
        assertTrue(filter.isPublicPath("/ws/chat"));
        assertTrue(filter.isPublicPath("/ws-chat"));
        assertTrue(filter.isPublicPath("/ws-chat/info"));

        assertFalse(filter.isPublicPath("/actuator/gateway"));
        assertFalse(filter.isPublicPath("/actuator/gateway/routes"));
        assertFalse(filter.isPublicPath("/actuator/env"));
        assertFalse(filter.isPublicPath("/api/v1/try-on/jobs"));
        assertFalse(filter.isPublicPath("/api/v1/try-on/photos/123"));
        assertFalse(filter.isPublicPath("/api/v1/donations/don-456"));
        assertFalse(filter.isPublicPath("/api/v1/inventario/1"));
        assertFalse(filter.isPublicPath("/api/v1/payments/history"));
        assertFalse(filter.isPublicPath(null));
    }

    @Test
    @DisplayName("Constructor con rutas públicas personalizadas funciona adecuadamente")
    void constructor_conRutasPersonalizadas() {
        JwtTokenValidatorFilter customFilter = new JwtTokenValidatorFilter(SECRET_BASE64, List.of("/custom/public/**"));
        assertTrue(customFilter.isPublicPath("/custom/public/test"));
        assertFalse(customFilter.isPublicPath("/actuator/health"));
    }

    @Test
    @DisplayName("Mitigación CVE-2025-41243: Acceso anónimo a /actuator/gateway/routes es bloqueado con 401 Unauthorized")
    void filter_accesoAnonimoActuatorGateway_retorna401Unauthorized() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/actuator/gateway/routes").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean chainCalled = new AtomicBoolean(false);

        Mono<Void> result = filter.filter(exchange, ex -> {
            chainCalled.set(true);
            return Mono.empty();
        });

        StepVerifier.create(result).verifyComplete();
        assertFalse(chainCalled.get(), "La ruta administrativa /actuator/gateway no debe ser accesible anónimamente");
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("Consulta Pública GET vs Rutas Privadas: Permite catálogo público (/second-hand, /publications) pero bloquea donaciones, pendientes, conversaciones y consultas por usuario")
    void isPublicRequest_permiteCatalogoPublicoYBloqueaConsultasPrivadas() {
        MockServerHttpRequest getSecondHand = MockServerHttpRequest.get("/api/v1/second-hand").build();
        MockServerHttpRequest getPublications = MockServerHttpRequest.get("/api/v1/second-hand/publications").build();
        MockServerHttpRequest getPublicationDetail = MockServerHttpRequest.get("/api/v1/second-hand/publications/pub-456").build();

        assertTrue(filter.isPublicRequest(getSecondHand), "GET /second-hand debe ser público");
        assertTrue(filter.isPublicRequest(getPublications), "GET /second-hand/publications debe ser público");
        assertTrue(filter.isPublicRequest(getPublicationDetail), "GET /second-hand/publications/{id} debe ser público");

        MockServerHttpRequest getHealth = MockServerHttpRequest.get("/api/v1/health").build();
        MockServerHttpRequest getPhotos = MockServerHttpRequest.get("/api/v1/second-hand/photos").build();
        MockServerHttpRequest getPhotoDetail = MockServerHttpRequest.get("/api/v1/second-hand/photos/123").build();
        assertTrue(filter.isPublicRequest(getHealth), "GET /api/v1/health debe ser público");
        assertTrue(filter.isPublicRequest(getPhotos), "GET /second-hand/photos debe ser público");
        assertTrue(filter.isPublicRequest(getPhotoDetail), "GET /second-hand/photos/{id} debe ser público");

        // Rutas privadas: consultas por usuario, moderación pendiente y conversaciones
        assertFalse(filter.isPublicRequest(MockServerHttpRequest.get("/api/v1/second-hand/user/101").build()));
        assertFalse(filter.isPublicRequest(MockServerHttpRequest.get("/api/v1/second-hand/publications/users/101").build()));
        assertFalse(filter.isPublicRequest(MockServerHttpRequest.get("/api/v1/prendas/pendientes").build()));
        assertFalse(filter.isPublicRequest(MockServerHttpRequest.get("/api/v1/prendas/pendientes/almacenes/1").build()));
        assertFalse(filter.isPublicRequest(MockServerHttpRequest.get("/api/v1/users/conversations").build()));
        assertFalse(filter.isPublicRequest(MockServerHttpRequest.get("/api/v1/stores/conversations").build()));
        assertFalse(filter.isPublicRequest(MockServerHttpRequest.get("/api/v1/stores/900555777-1/conversations").build()));
        assertFalse(filter.isPublicRequest(MockServerHttpRequest.get("/api/v1/donations/10/conversations").build()));
        assertFalse(filter.isPublicRequest(MockServerHttpRequest.get("/api/v1/second-hand/publications/20/conversations").build()));

        // Rutas privadas del microservicio: donaciones
        assertFalse(filter.isPublicRequest(MockServerHttpRequest.get("/api/v1/donations").build()));
        assertFalse(filter.isPublicRequest(MockServerHttpRequest.get("/api/v1/donations/don-456").build()));
        assertFalse(filter.isPublicRequest(MockServerHttpRequest.get("/api/v1/donations/donors/101").build()));
        assertFalse(filter.isPublicRequest(MockServerHttpRequest.get("/api/v1/donations/foundations/501").build()));
        assertFalse(filter.isPublicRequest(MockServerHttpRequest.get("/api/v1/donations/foundations/representative").build()));
    }

    @Test
    @DisplayName("Filter Consultas Privadas Anónimas: Rechaza con 401 Unauthorized consultas GET privadas de donaciones, conversaciones, pendientes y usuario")
    void filter_consultasPrivadasAnonimas_retorna401Unauthorized() {
        for (String privateUri : List.of(
                "/api/v1/donations/don-456",
                "/api/v1/donations/donors/101",
                "/api/v1/donations/foundations/501",
                "/api/v1/second-hand/user/101",
                "/api/v1/second-hand/publications/users/101",
                "/api/v1/prendas/pendientes",
                "/api/v1/stores/conversations",
                "/api/v1/stores/900555777-1/conversations",
                "/api/v1/users/conversations",
                "/api/v1/donations/10/conversations",
                "/api/v1/second-hand/publications/20/conversations"
        )) {
            MockServerHttpRequest req = MockServerHttpRequest.get(privateUri).build();
            MockServerWebExchange ex = MockServerWebExchange.from(req);
            StepVerifier.create(filter.filter(ex, chainEx -> Mono.empty())).verifyComplete();
            assertEquals(HttpStatus.UNAUTHORIZED, ex.getResponse().getStatusCode(),
                    "Acceso anónimo a " + privateUri + " debe retornar 401");
        }
    }

    @Test
    @DisplayName("Filter Consultas Privadas con Token Válido: Permite acceso a donaciones e inyecta cabeceras saneadas")
    void filter_consultaPrivadaDonacionesConTokenValido_permiteAccesoEInyectaCabeceras() {
        String token = Jwts.builder()
                .subject("fundacion-bogota-88")
                .claim("role", "ROLE_FOUNDATION")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600000))
                .signWith(secretKey)
                .compact();

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/donations/foundation/88")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(JwtTokenValidatorFilter.USER_ID_HEADER, "spoofed-user")
                .header(JwtTokenValidatorFilter.USER_ROLES_HEADER, "ROLE_ADMIN")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean chainCalled = new AtomicBoolean(false);

        Mono<Void> result = filter.filter(exchange, ex -> {
            chainCalled.set(true);
            assertEquals("fundacion-bogota-88", ex.getRequest().getHeaders().getFirst(JwtTokenValidatorFilter.USER_ID_HEADER));
            assertEquals("ROLE_FOUNDATION", ex.getRequest().getHeaders().getFirst(JwtTokenValidatorFilter.USER_ROLE_HEADER));
            assertEquals("ROLE_FOUNDATION", ex.getRequest().getHeaders().getFirst(JwtTokenValidatorFilter.USER_ROLES_HEADER));
            return Mono.empty();
        });

        StepVerifier.create(result).verifyComplete();
        assertTrue(chainCalled.get(), "La consulta autenticada debe continuar hacia downstream");
    }

    @Test
    @DisplayName("Operaciones Mutantes: Rechaza con 401 peticiones anónimas de creación, edición o donación")
    void isPublicRequest_rechazaOperacionesMutantesSinToken() {
        MockServerHttpRequest postSecondHand = MockServerHttpRequest.post("/api/v1/second-hand").build();
        MockServerHttpRequest putSecondHand = MockServerHttpRequest.put("/api/v1/second-hand/prenda-123").build();
        MockServerHttpRequest patchDonate = MockServerHttpRequest.patch("/api/v1/second-hand/prenda-123/donate").build();
        MockServerHttpRequest postDonations = MockServerHttpRequest.post("/api/v1/donations").build();
        MockServerHttpRequest postDonaciones = MockServerHttpRequest.post("/api/v1/donaciones").build();

        assertFalse(filter.isPublicRequest(postSecondHand));
        assertFalse(filter.isPublicRequest(putSecondHand));
        assertFalse(filter.isPublicRequest(patchDonate));
        assertFalse(filter.isPublicRequest(postDonations));
        assertFalse(filter.isPublicRequest(postDonaciones));
    }

    @Test
    @DisplayName("Filter GET Consulta Publicaciones: Acceso anónimo fluye hacia downstream sin requerir token")
    void filter_consultaGetPublicacionesSinToken_permiteContinuar() {
        // 1. /api/v1/second-hand
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/second-hand").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        Mono<Void> result = filter.filter(exchange, ex -> {
            chainCalled.set(true);
            return Mono.empty();
        });
        StepVerifier.create(result).verifyComplete();
        assertTrue(chainCalled.get(), "La consulta GET pública /second-hand debe fluir hacia downstream");
        assertNotEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());

        // 2. /api/v1/second-hand/publications (catálogo canónico)
        MockServerHttpRequest reqPubs = MockServerHttpRequest.get("/api/v1/second-hand/publications").build();
        MockServerWebExchange exPubs = MockServerWebExchange.from(reqPubs);
        AtomicBoolean chainCalledPubs = new AtomicBoolean(false);
        StepVerifier.create(filter.filter(exPubs, ex -> {
            chainCalledPubs.set(true);
            return Mono.empty();
        })).verifyComplete();
        assertTrue(chainCalledPubs.get(), "La consulta GET pública /second-hand/publications debe fluir");
        assertNotEquals(HttpStatus.UNAUTHORIZED, exPubs.getResponse().getStatusCode());

        // 3. /api/v1/second-hand/publications/pub-123 (detalle de publicación)
        MockServerHttpRequest reqDetail = MockServerHttpRequest.get("/api/v1/second-hand/publications/pub-123").build();
        MockServerWebExchange exDetail = MockServerWebExchange.from(reqDetail);
        AtomicBoolean chainCalledDetail = new AtomicBoolean(false);
        StepVerifier.create(filter.filter(exDetail, ex -> {
            chainCalledDetail.set(true);
            return Mono.empty();
        })).verifyComplete();
        assertTrue(chainCalledDetail.get(), "La consulta GET pública /second-hand/publications/{id} debe fluir");
        assertNotEquals(HttpStatus.UNAUTHORIZED, exDetail.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("Filter Creación/Edición/Donación: Rechaza con 401 si no se envía token")
    void filter_operacionesMutantesSinToken_retorna401Unauthorized() {
        // 1. Creación de publicación
        MockServerHttpRequest reqPost = MockServerHttpRequest.post("/api/v1/second-hand").build();
        MockServerWebExchange exPost = MockServerWebExchange.from(reqPost);
        StepVerifier.create(filter.filter(exPost, ex -> Mono.empty())).verifyComplete();
        assertEquals(HttpStatus.UNAUTHORIZED, exPost.getResponse().getStatusCode());

        // 2. Edición de publicación
        MockServerHttpRequest reqPut = MockServerHttpRequest.put("/api/v1/second-hand/prenda-100").build();
        MockServerWebExchange exPut = MockServerWebExchange.from(reqPut);
        StepVerifier.create(filter.filter(exPut, ex -> Mono.empty())).verifyComplete();
        assertEquals(HttpStatus.UNAUTHORIZED, exPut.getResponse().getStatusCode());

        // 3. Operación de donación
        MockServerHttpRequest reqPatch = MockServerHttpRequest.patch("/api/v1/second-hand/prenda-100/donate").build();
        MockServerWebExchange exPatch = MockServerWebExchange.from(reqPatch);
        StepVerifier.create(filter.filter(exPatch, ex -> Mono.empty())).verifyComplete();
        assertEquals(HttpStatus.UNAUTHORIZED, exPatch.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("Filter Creación con Token Válido: Inyecta X-User-Id y permite continuar")
    void filter_creacionConTokenValido_inyectaHeadersYContinua() {
        String token = Jwts.builder()
                .subject("donante-123")
                .claim("role", "ROLE_DONANTE")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600000))
                .signWith(secretKey)
                .compact();

        MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/donations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean chainCalled = new AtomicBoolean(false);

        Mono<Void> result = filter.filter(exchange, ex -> {
            chainCalled.set(true);
            assertEquals("donante-123", ex.getRequest().getHeaders().getFirst(JwtTokenValidatorFilter.USER_ID_HEADER));
            assertEquals("ROLE_DONANTE", ex.getRequest().getHeaders().getFirst(JwtTokenValidatorFilter.USER_ROLE_HEADER));
            return Mono.empty();
        });

        StepVerifier.create(result).verifyComplete();
        assertTrue(chainCalled.get(), "La creación con token válido debe procesarse e inyectar cabeceras");
    }

    @Test
    @DisplayName("Constructor con rutas GET personalizadas funciona adecuadamente")
    void constructor_conRutasGetPersonalizadas() {
        JwtTokenValidatorFilter customFilter = new JwtTokenValidatorFilter(
                SECRET_BASE64,
                List.of("/custom/all/**"),
                List.of("/custom/get/**")
        );
        MockServerHttpRequest getReq = MockServerHttpRequest.get("/custom/get/items").build();
        MockServerHttpRequest postReq = MockServerHttpRequest.post("/custom/get/items").build();

        assertTrue(customFilter.isPublicRequest(getReq));
        assertFalse(customFilter.isPublicRequest(postReq));
    }

    @Test
    @DisplayName("Constructor con clave plana (no Base64) o secreta inválida")
    void constructor_conClavePlanaYValidaciones() {
        String plainSecret = "SuperSecretPlainStringThatIsAtLeast32BytesLong!";
        JwtTokenValidatorFilter plainFilter = new JwtTokenValidatorFilter(plainSecret, null, null);
        assertNotNull(plainFilter);

        assertThrows(IllegalStateException.class, () -> new JwtTokenValidatorFilter(null));
        assertThrows(IllegalStateException.class, () -> new JwtTokenValidatorFilter("   "));
    }

    @Test
    @DisplayName("normalizeDownstreamRole y normalizeDownstreamRoles: Homologación exacta de ADMIN_STYLERADAR")
    void testNormalizeDownstreamRolesAndRole() {
        assertEquals("ADMIN", JwtTokenValidatorFilter.normalizeDownstreamRole("ADMIN_STYLERADAR", "ADMIN_STYLERADAR"));
        assertEquals("ADMIN", JwtTokenValidatorFilter.normalizeDownstreamRole("ROLE_ADMIN_STYLERADAR", "ROLE_ADMIN_STYLERADAR"));
        assertEquals("ADMIN", JwtTokenValidatorFilter.normalizeDownstreamRole("STYLERADAR_ADMIN", "STYLERADAR_ADMIN"));
        assertEquals("ADMIN", JwtTokenValidatorFilter.normalizeDownstreamRole("ROLE_STYLERADAR_ADMIN", "ROLE_STYLERADAR_ADMIN"));
        assertEquals("ADMIN", JwtTokenValidatorFilter.normalizeDownstreamRole("BUYER", "BUYER,STYLERADAR_ADMIN"));
        assertEquals("ADMIN", JwtTokenValidatorFilter.normalizeDownstreamRole("COMPRADOR", "COMPRADOR,ADMIN_STYLERADAR"));
        assertEquals("COMPRADOR", JwtTokenValidatorFilter.normalizeDownstreamRole("COMPRADOR", "COMPRADOR,USER"));
        assertNull(JwtTokenValidatorFilter.normalizeDownstreamRole(null, null));

        assertNull(JwtTokenValidatorFilter.normalizeDownstreamRoles(null));
        assertEquals("", JwtTokenValidatorFilter.normalizeDownstreamRoles(""));
        assertEquals("COMPRADOR,ADMIN_STYLERADAR,ADMIN", JwtTokenValidatorFilter.normalizeDownstreamRoles("COMPRADOR,ADMIN_STYLERADAR"));
        assertEquals("BUYER,STYLERADAR_ADMIN,ADMIN", JwtTokenValidatorFilter.normalizeDownstreamRoles("BUYER,STYLERADAR_ADMIN"));
        assertEquals("COMPRADOR,ADMIN_STYLERADAR,ADMIN", JwtTokenValidatorFilter.normalizeDownstreamRoles("COMPRADOR, ,ADMIN_STYLERADAR"));
        assertEquals("COMPRADOR,USER", JwtTokenValidatorFilter.normalizeDownstreamRoles("COMPRADOR,USER"));
    }

    @Test
    @DisplayName("Filter con claim roles como String (no lista): Inyecta cabeceras correctamente")
    void testFilterWithRolesClaimAsString() {
        String token = Jwts.builder()
                .subject("simple-user-888")
                .claim("roles", "COMPRADOR")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600000))
                .signWith(secretKey)
                .compact();

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/try-on/cuotas")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, ex -> {
            assertEquals("COMPRADOR", ex.getRequest().getHeaders().getFirst(JwtTokenValidatorFilter.USER_ROLE_HEADER));
            assertEquals("COMPRADOR", ex.getRequest().getHeaders().getFirst(JwtTokenValidatorFilter.USER_ROLES_HEADER));
            return Mono.empty();
        })).verifyComplete();
    }

    @Test
    @DisplayName("extractAlmacenId: Extrae storeId/almacenId de claims válidos y rechaza malformados")
    void testExtractAlmacenIdFromClaims() {
        io.jsonwebtoken.Claims claims1 = Jwts.claims().add("almacenId", "ALM-101").build();
        assertEquals("ALM-101", JwtTokenValidatorFilter.extractAlmacenId(claims1));

        io.jsonwebtoken.Claims claims2 = Jwts.claims().add("almacen_id", "ALM-102").build();
        assertEquals("ALM-102", JwtTokenValidatorFilter.extractAlmacenId(claims2));

        io.jsonwebtoken.Claims claims3 = Jwts.claims().add("storeId", "STORE-201").build();
        assertEquals("STORE-201", JwtTokenValidatorFilter.extractAlmacenId(claims3));

        io.jsonwebtoken.Claims claims4 = Jwts.claims().add("store_id", "STORE-202").build();
        assertEquals("STORE-202", JwtTokenValidatorFilter.extractAlmacenId(claims4));

        String sampleUuid = java.util.UUID.randomUUID().toString();
        io.jsonwebtoken.Claims claimsUuid = Jwts.claims().add("almacenId", sampleUuid).build();
        assertEquals(sampleUuid, JwtTokenValidatorFilter.extractAlmacenId(claimsUuid));

        io.jsonwebtoken.Claims claimsInvalid = Jwts.claims().add("almacenId", "INVALID@@@$$").build();
        assertNull(JwtTokenValidatorFilter.extractAlmacenId(claimsInvalid));

        io.jsonwebtoken.Claims claimsEmpty = Jwts.claims().build();
        assertNull(JwtTokenValidatorFilter.extractAlmacenId(claimsEmpty));
    }

    @Test
    @DisplayName("isPublicRequest: Cobertura exhaustiva de ramas nulas, OPTIONS y rutas excluidas")
    void testIsPublicRequestBranches() {
        assertFalse(filter.isPublicRequest(null));
        assertFalse(filter.isPublicPath(null));
        assertFalse(filter.isPublicGetPath(null));

        MockServerHttpRequest optReq = MockServerHttpRequest.options("/api/v1/any/endpoint").build();
        assertTrue(filter.isPublicRequest(optReq));

        MockServerHttpRequest prendasReq = MockServerHttpRequest.get("/api/v1/prendas").build();
        assertTrue(filter.isPublicRequest(prendasReq), "GET /api/v1/prendas debe ser público");
        MockServerHttpRequest prendasSlashReq = MockServerHttpRequest.get("/api/v1/prendas/").build();
        assertTrue(filter.isPublicRequest(prendasSlashReq), "GET /api/v1/prendas/ debe ser público");
        MockServerHttpRequest prendaDetailReq = MockServerHttpRequest.get("/api/v1/prendas/123").build();
        assertTrue(filter.isPublicRequest(prendaDetailReq), "GET /api/v1/prendas/{id} debe ser público");
        MockServerHttpRequest prendasSimilaresReq = MockServerHttpRequest.get("/api/v1/prendas/123/similares").build();
        assertTrue(filter.isPublicRequest(prendasSimilaresReq), "GET /api/v1/prendas/{id}/similares debe ser público");
        MockServerHttpRequest catalogoTallasReq = MockServerHttpRequest.get("/api/v1/catalogo/items/123/tallas-medidas").build();
        assertTrue(filter.isPublicRequest(catalogoTallasReq), "GET /api/v1/catalogo/items/{id}/tallas-medidas debe ser público");
        MockServerHttpRequest almacenesReq = MockServerHttpRequest.get("/api/v1/almacenes").build();
        assertTrue(filter.isPublicRequest(almacenesReq), "GET /api/v1/almacenes debe ser público");
        MockServerHttpRequest almacenesCatalogoReq = MockServerHttpRequest.get("/api/v1/almacenes/123/catalogo").build();
        assertTrue(filter.isPublicRequest(almacenesCatalogoReq), "GET /api/v1/almacenes/{id}/catalogo debe ser público");
        MockServerHttpRequest storesReq = MockServerHttpRequest.get("/api/v1/stores").build();
        assertTrue(filter.isPublicRequest(storesReq), "GET /api/v1/stores debe ser público");
        MockServerHttpRequest storesNitReq = MockServerHttpRequest.get("/api/v1/stores/900555777-1").build();
        assertTrue(filter.isPublicRequest(storesNitReq), "GET /api/v1/stores/{nit} debe ser público");
        MockServerHttpRequest storePhotosReq = MockServerHttpRequest.get("/api/v1/stores/900555777-1/photos").build();
        assertTrue(filter.isPublicRequest(storePhotosReq), "GET /api/v1/stores/{nit}/photos debe ser público");
        MockServerHttpRequest postStorePhotosReq = MockServerHttpRequest.post("/api/v1/stores/900555777-1/photos").build();
        assertFalse(filter.isPublicRequest(postStorePhotosReq), "POST /api/v1/stores/{nit}/photos no debe ser público");
        MockServerHttpRequest foundationsReq = MockServerHttpRequest.get("/api/v1/foundations").build();
        assertTrue(filter.isPublicRequest(foundationsReq), "GET /api/v1/foundations debe ser público");

        // Rutas privadas reales (donaciones, segunda mano por usuario, pendientes de moderación, conversaciones y pagos)
        String[] privatePaths = {
                "/api/v1/second-hand/publications/users/123",
                "/api/v1/second-hand/publications/user/123",
                "/api/v1/second-hand/user/123",
                "/api/v1/donations/my-donations",
                "/api/v1/prendas/pendientes",
                "/api/v1/prendas/pendientes/almacenes/1/moderacion",
                "/api/v1/users/conversations",
                "/api/v1/stores/conversations",
                "/api/v1/stores/900555777-1/conversations",
                "/api/v1/donations/10/conversations",
                "/api/v1/second-hand/publications/20/conversations",
                "/api/v1/plans"
        };
        for (String p : privatePaths) {
            MockServerHttpRequest req = MockServerHttpRequest.get(p).build();
            assertFalse(filter.isPublicGetPath(p), "Ruta " + p + " no debe ser pública");
            assertFalse(filter.isPublicRequest(req), "Petición a " + p + " no debe ser pública");
        }
    }

    @Test
    @DisplayName("Filter con ADMIN_STYLERADAR y claim almacenId: Downstream recibe X-Almacen-Id legítimo y rol homologado")
    void testFilterInjectsLegitAlmacenIdAndHomologatedRole() {
        String token = Jwts.builder()
                .subject("admin-user-777")
                .claim("role", "ADMIN_STYLERADAR")
                .claim("roles", List.of("ADMIN_STYLERADAR", "COMPRADOR"))
                .claim("almacenId", "TIENDA-CENTRO-01")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600000))
                .signWith(secretKey)
                .compact();

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/almacenes/TIENDA-CENTRO-01/catalogo")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-Almacen-Id", "spoofed-store-id")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean chainCalled = new AtomicBoolean(false);

        Mono<Void> result = filter.filter(exchange, ex -> {
            chainCalled.set(true);
            assertEquals("TIENDA-CENTRO-01", ex.getRequest().getHeaders().getFirst("X-Almacen-Id"));
            assertEquals("ADMIN", ex.getRequest().getHeaders().getFirst(JwtTokenValidatorFilter.USER_ROLE_HEADER));
            assertTrue(ex.getRequest().getHeaders().getFirst(JwtTokenValidatorFilter.USER_ROLES_HEADER).contains("ADMIN"));
            return Mono.empty();
        });

        StepVerifier.create(result).verifyComplete();
        assertTrue(chainCalled.get());
    }

    @Test
    @DisplayName("SSE Notificaciones: exige claim purpose=sse-stream en ?ticket= y rechaza uso fuera del stream")
    void testFilterAllowsSseNotificationStreamWithValidTicketQueryParam() {
        String validTicket = Jwts.builder()
                .subject("42")
                .claim("purpose", "sse-stream")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(secretKey)
                .compact();
        String ticketWithoutPurpose = Jwts.builder()
                .subject("42")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(secretKey)
                .compact();
        String ticketWrongPurpose = Jwts.builder()
                .subject("42")
                .claim("purpose", "access-api")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(secretKey)
                .compact();

        MockServerHttpRequest validStreamReq = MockServerHttpRequest.get("/api/v1/users/42/notifications/stream?ticket=" + validTicket)
                .build();
        MockServerWebExchange validExchange = MockServerWebExchange.from(validStreamReq);
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        StepVerifier.create(filter.filter(validExchange, ex -> {
            chainCalled.set(true);
            assertEquals("42", ex.getRequest().getHeaders().getFirst(JwtTokenValidatorFilter.USER_ID_HEADER));
            return Mono.empty();
        })).verifyComplete();
        assertTrue(chainCalled.get());

        MockServerHttpRequest missingTicketReq = MockServerHttpRequest.get("/api/v1/users/42/notifications/stream").build();
        MockServerWebExchange missingExchange = MockServerWebExchange.from(missingTicketReq);
        StepVerifier.create(filter.filter(missingExchange, ex -> Mono.empty())).verifyComplete();
        assertEquals(HttpStatus.UNAUTHORIZED, missingExchange.getResponse().getStatusCode());

        MockServerHttpRequest invalidTicketReq = MockServerHttpRequest.get("/api/v1/users/42/notifications/stream?ticket=invalido").build();
        MockServerWebExchange invalidExchange = MockServerWebExchange.from(invalidTicketReq);
        StepVerifier.create(filter.filter(invalidExchange, ex -> Mono.empty())).verifyComplete();
        assertEquals(HttpStatus.UNAUTHORIZED, invalidExchange.getResponse().getStatusCode());

        MockServerHttpRequest legacyAliasSseReq = MockServerHttpRequest.get("/api/v1/usuarios/42/notificaciones/stream?ticket=" + validTicket).build();
        MockServerWebExchange legacyAliasExchange = MockServerWebExchange.from(legacyAliasSseReq);
        StepVerifier.create(filter.filter(legacyAliasExchange, ex -> Mono.empty())).verifyComplete();
        assertEquals(HttpStatus.UNAUTHORIZED, legacyAliasExchange.getResponse().getStatusCode());

        MockServerHttpRequest noPurposeReq = MockServerHttpRequest.get("/api/v1/users/42/notifications/stream?ticket=" + ticketWithoutPurpose).build();
        MockServerWebExchange noPurposeExchange = MockServerWebExchange.from(noPurposeReq);
        StepVerifier.create(filter.filter(noPurposeExchange, ex -> Mono.empty())).verifyComplete();
        assertEquals(HttpStatus.UNAUTHORIZED, noPurposeExchange.getResponse().getStatusCode());

        MockServerHttpRequest wrongPurposeReq = MockServerHttpRequest.get("/api/v1/users/42/notifications/stream?ticket=" + ticketWrongPurpose).build();
        MockServerWebExchange wrongPurposeExchange = MockServerWebExchange.from(wrongPurposeReq);
        StepVerifier.create(filter.filter(wrongPurposeExchange, ex -> Mono.empty())).verifyComplete();
        assertEquals(HttpStatus.UNAUTHORIZED, wrongPurposeExchange.getResponse().getStatusCode());

        MockServerHttpRequest sseBearerOnProtectedReq = MockServerHttpRequest.get("/api/v1/users/42/alerts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validTicket)
                .build();
        MockServerWebExchange sseBearerExchange = MockServerWebExchange.from(sseBearerOnProtectedReq);
        AtomicBoolean protectedChainCalled = new AtomicBoolean(false);
        StepVerifier.create(filter.filter(sseBearerExchange, ex -> {
            protectedChainCalled.set(true);
            return Mono.empty();
        })).verifyComplete();
        assertEquals(HttpStatus.UNAUTHORIZED, sseBearerExchange.getResponse().getStatusCode());
        assertEquals(false, protectedChainCalled.get());

        MockServerHttpRequest sseTicketQueryOnProtectedReq = MockServerHttpRequest.get("/api/v1/users/42/alerts?ticket=" + validTicket).build();
        MockServerWebExchange sseTicketQueryExchange = MockServerWebExchange.from(sseTicketQueryOnProtectedReq);
        StepVerifier.create(filter.filter(sseTicketQueryExchange, ex -> Mono.empty())).verifyComplete();
        assertEquals(HttpStatus.UNAUTHORIZED, sseTicketQueryExchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("Validación dual JWT: acepta firma con secreto Base64 decodificado y UTF-8, y rechaza otra clave con 401")
    void filter_soportaSecretoBase64YUtf8YRechazaOtraClaveCon401() {
        String base64SignedToken = Jwts.builder()
                .subject("101")
                .claim("roles", List.of("BUYER"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600000))
                .signWith(secretKey)
                .compact();

        SecretKey utf8Key = Keys.hmacShaKeyFor(SECRET_BASE64.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String utf8SignedToken = Jwts.builder()
                .subject("202")
                .claim("roles", List.of("STORE_ADMIN"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600000))
                .signWith(utf8Key)
                .compact();

        SecretKey foreignKey = Keys.hmacShaKeyFor(
                "OtraClaveCompletamenteDistintaQueNoDebeValidarse2026!".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        String foreignSignedToken = Jwts.builder()
                .subject("999")
                .claim("roles", List.of("STYLERADAR_ADMIN"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600000))
                .signWith(foreignKey)
                .compact();

        MockServerWebExchange base64Exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/users/101/store")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + base64SignedToken)
                        .build()
        );
        AtomicBoolean base64Passed = new AtomicBoolean(false);
        StepVerifier.create(filter.filter(base64Exchange, ex -> {
            base64Passed.set(true);
            assertEquals("101", ex.getRequest().getHeaders().getFirst(JwtTokenValidatorFilter.USER_ID_HEADER));
            assertEquals("BUYER", ex.getRequest().getHeaders().getFirst(JwtTokenValidatorFilter.USER_ROLES_HEADER));
            return Mono.empty();
        })).verifyComplete();
        assertTrue(base64Passed.get());

        MockServerWebExchange utf8Exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/users/202/store")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + utf8SignedToken)
                        .build()
        );
        AtomicBoolean utf8Passed = new AtomicBoolean(false);
        StepVerifier.create(filter.filter(utf8Exchange, ex -> {
            utf8Passed.set(true);
            assertEquals("202", ex.getRequest().getHeaders().getFirst(JwtTokenValidatorFilter.USER_ID_HEADER));
            assertEquals("STORE_ADMIN", ex.getRequest().getHeaders().getFirst(JwtTokenValidatorFilter.USER_ROLES_HEADER));
            return Mono.empty();
        })).verifyComplete();
        assertTrue(utf8Passed.get());

        MockServerWebExchange foreignExchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/users/999/store")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + foreignSignedToken)
                        .build()
        );
        AtomicBoolean foreignPassed = new AtomicBoolean(false);
        StepVerifier.create(filter.filter(foreignExchange, ex -> {
            foreignPassed.set(true);
            return Mono.empty();
        })).verifyComplete();
        assertFalse(foreignPassed.get(), "Un token firmado con otra clave jamás debe continuar hacia downstream");
        assertEquals(HttpStatus.UNAUTHORIZED, foreignExchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("Tiendas (ms-tiendas): GET de catalog-settings, low-stock-threshold y new-arrival-days son públicos sin JWT; PUT sin JWT retorna 401")
    void filter_rutasPublicasYProtegidasConfiguracionTiendas() {
        for (String publicGetPath : List.of(
                "/api/v1/stores/900555777-1/catalog-settings",
                "/api/v1/stores/900555777-1/low-stock-threshold",
                "/api/v1/stores/900555777-1/new-arrival-days"
        )) {
            MockServerWebExchange getExchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get(publicGetPath).build()
            );
            AtomicBoolean passed = new AtomicBoolean(false);
            StepVerifier.create(filter.filter(getExchange, ex -> {
                passed.set(true);
                return Mono.empty();
            })).verifyComplete();
            assertTrue(passed.get(), "GET " + publicGetPath + " debe permitir acceso anónimo");

            MockServerWebExchange putExchange = MockServerWebExchange.from(
                    MockServerHttpRequest.put(publicGetPath).build()
            );
            AtomicBoolean putPassed = new AtomicBoolean(false);
            StepVerifier.create(filter.filter(putExchange, ex -> {
                putPassed.set(true);
                return Mono.empty();
            })).verifyComplete();
            assertFalse(putPassed.get(), "PUT " + publicGetPath + " sin JWT no debe pasar downstream");
            assertEquals(HttpStatus.UNAUTHORIZED, putExchange.getResponse().getStatusCode());
        }
    }

    @Test
    @DisplayName("Playlists e Inventario: GET públicos permiten acceso anónimo; /playlists/me, /playlists/me/guardadas y mutaciones exigen JWT")
    void filter_rutasPublicasYProtegidasPlaylistsEInventario() {
        for (String publicGetPath : List.of(
                "/api/v1/playlists",
                "/api/v1/playlists/destacadas",
                "/api/v1/playlists/populares",
                "/api/v1/playlists/pl-123",
                "/api/v1/inventario/prenda/p-1",
                "/api/v1/inventario/prenda/p-1/disponibilidad",
                "/api/v1/inventario/sedes/s-1/disponibles",
                "/api/v1/inventario/tallas/M/prendas"
        )) {
            MockServerWebExchange getExchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get(publicGetPath).build()
            );
            AtomicBoolean passed = new AtomicBoolean(false);
            StepVerifier.create(filter.filter(getExchange, ex -> {
                passed.set(true);
                return Mono.empty();
            })).verifyComplete();
            assertTrue(passed.get(), "GET " + publicGetPath + " debe permitir acceso anónimo");
        }

        for (String privateGetPath : List.of(
                "/api/v1/playlists/me",
                "/api/v1/playlists/me/guardadas"
        )) {
            MockServerWebExchange privExchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get(privateGetPath).build()
            );
            AtomicBoolean passed = new AtomicBoolean(false);
            StepVerifier.create(filter.filter(privExchange, ex -> {
                passed.set(true);
                return Mono.empty();
            })).verifyComplete();
            assertFalse(passed.get(), "GET " + privateGetPath + " sin JWT debe retornar 401");
            assertEquals(HttpStatus.UNAUTHORIZED, privExchange.getResponse().getStatusCode());
        }

        for (String protectedPostPath : List.of(
                "/api/v1/playlists",
                "/api/v1/inventario/reserva"
        )) {
            MockServerWebExchange postExchange = MockServerWebExchange.from(
                    MockServerHttpRequest.post(protectedPostPath).build()
            );
            AtomicBoolean passed = new AtomicBoolean(false);
            StepVerifier.create(filter.filter(postExchange, ex -> {
                passed.set(true);
                return Mono.empty();
            })).verifyComplete();
            assertFalse(passed.get(), "POST " + protectedPostPath + " sin JWT debe retornar 401");
            assertEquals(HttpStatus.UNAUTHORIZED, postExchange.getResponse().getStatusCode());
        }
    }
}
