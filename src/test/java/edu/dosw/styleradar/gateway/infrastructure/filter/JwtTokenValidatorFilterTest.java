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

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/catalogo/items")
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
        assertTrue(filter.isPublicPath("/fallback/probador"));
        assertTrue(filter.isPublicPath("/ws"));
        assertTrue(filter.isPublicPath("/ws/chat"));
        assertTrue(filter.isPublicPath("/ws-chat"));
        assertTrue(filter.isPublicPath("/ws-chat/info"));

        assertFalse(filter.isPublicPath("/actuator/gateway"));
        assertFalse(filter.isPublicPath("/actuator/gateway/routes"));
        assertFalse(filter.isPublicPath("/actuator/env"));
        assertFalse(filter.isPublicPath("/api/v1/try-on/jobs"));
        assertFalse(filter.isPublicPath("/api/v1/probador/siluetas"));
        assertFalse(filter.isPublicPath("/api/v1/donaciones/campanas"));
        assertFalse(filter.isPublicPath("/api/v1/inventario/items"));
        assertFalse(filter.isPublicPath("/api/v1/pagos/checkout"));
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
    @DisplayName("Consulta Pública GET vs Rutas Privadas: Permite catálogo público (/second-hand, /publications) pero bloquea donaciones y consultas por usuario")
    void isPublicRequest_permiteCatalogoPublicoYBloqueaConsultasPrivadas() {
        // Endpoints públicos del contrato estándar y canónico de Munar
        MockServerHttpRequest getSecondHand = MockServerHttpRequest.get("/api/v1/second-hand").build();
        MockServerHttpRequest getSecondHandDetail = MockServerHttpRequest.get("/api/v1/second-hand/prenda-123").build();
        MockServerHttpRequest getPublications = MockServerHttpRequest.get("/api/v1/second-hand/publications").build();
        MockServerHttpRequest getPublicationDetail = MockServerHttpRequest.get("/api/v1/second-hand/publications/pub-456").build();
        MockServerHttpRequest getSegundaMano = MockServerHttpRequest.get("/api/v1/segunda-mano").build();
        MockServerHttpRequest getSegundaManoDetail = MockServerHttpRequest.get("/api/v1/segunda-mano/prenda-123").build();
        MockServerHttpRequest getPublicaciones = MockServerHttpRequest.get("/api/v1/segunda-mano/publicaciones").build();
        MockServerHttpRequest getPublicacionDetail = MockServerHttpRequest.get("/api/v1/segunda-mano/publicaciones/pub-456").build();

        assertTrue(filter.isPublicRequest(getSecondHand), "GET /second-hand debe ser público");
        assertTrue(filter.isPublicRequest(getSecondHandDetail), "GET /second-hand/{id} debe ser público");
        assertTrue(filter.isPublicRequest(getPublications), "GET /second-hand/publications debe ser público");
        assertTrue(filter.isPublicRequest(getPublicationDetail), "GET /second-hand/publications/{id} debe ser público");
        assertTrue(filter.isPublicRequest(getSegundaMano), "GET /segunda-mano debe ser público");
        assertTrue(filter.isPublicRequest(getSegundaManoDetail), "GET /segunda-mano/{id} debe ser público");
        assertTrue(filter.isPublicRequest(getPublicaciones), "GET /segunda-mano/publicaciones debe ser público");
        assertTrue(filter.isPublicRequest(getPublicacionDetail), "GET /segunda-mano/publicaciones/{id} debe ser público");

        MockServerHttpRequest getHealth = MockServerHttpRequest.get("/api/v1/health").build();
        MockServerHttpRequest getSubPublications = MockServerHttpRequest.get("/api/v1/second-hand/publications/category/shirts").build();
        MockServerHttpRequest getPhotos = MockServerHttpRequest.get("/api/v1/second-hand/photos").build();
        assertTrue(filter.isPublicRequest(getHealth), "GET /api/v1/health debe ser público");
        assertTrue(filter.isPublicRequest(getSubPublications), "GET /second-hand/publications/** debe ser público");
        assertTrue(filter.isPublicRequest(getPhotos), "GET /second-hand/photos debe ser público");

        // Rutas privadas del microservicio: consultas por usuario
        MockServerHttpRequest getUserSecondHand = MockServerHttpRequest.get("/api/v1/second-hand/user/101").build();
        MockServerHttpRequest getUserPublications = MockServerHttpRequest.get("/api/v1/second-hand/publications/users/101").build();
        MockServerHttpRequest getUserSegundaMano = MockServerHttpRequest.get("/api/v1/segunda-mano/user/101").build();
        MockServerHttpRequest getUsuariosPublicaciones = MockServerHttpRequest.get("/api/v1/segunda-mano/publicaciones/usuarios/101").build();

        assertFalse(filter.isPublicRequest(getUserSecondHand), "GET /second-hand/user/{id} es privado");
        assertFalse(filter.isPublicRequest(getUserPublications), "GET /second-hand/publications/users/{userId} es privado (requiere propietario o admin)");
        assertFalse(filter.isPublicRequest(getUserSegundaMano), "GET /segunda-mano/user/{id} es privado");
        assertFalse(filter.isPublicRequest(getUsuariosPublicaciones), "GET /segunda-mano/publicaciones/usuarios/{userId} es privado");

        // Rutas privadas del microservicio: donaciones (detalle, donante, fundación)
        MockServerHttpRequest getDonations = MockServerHttpRequest.get("/api/v1/donations").build();
        MockServerHttpRequest getDonationsDetail = MockServerHttpRequest.get("/api/v1/donations/don-456").build();
        MockServerHttpRequest getDonationsDonor = MockServerHttpRequest.get("/api/v1/donations/donor/101").build();
        MockServerHttpRequest getDonationsFoundation = MockServerHttpRequest.get("/api/v1/donations/foundation/501").build();
        MockServerHttpRequest getDonaciones = MockServerHttpRequest.get("/api/v1/donaciones").build();
        MockServerHttpRequest getDonacionesDetail = MockServerHttpRequest.get("/api/v1/donaciones/don-456").build();
        MockServerHttpRequest getDonacionesDonor = MockServerHttpRequest.get("/api/v1/donaciones/donor/101").build();
        MockServerHttpRequest getDonacionesFoundation = MockServerHttpRequest.get("/api/v1/donaciones/foundation/501").build();

        assertFalse(filter.isPublicRequest(getDonations), "GET /donations no es público");
        assertFalse(filter.isPublicRequest(getDonationsDetail), "GET /donations/{id} es privado (requiere donante, fundación o admin)");
        assertFalse(filter.isPublicRequest(getDonationsDonor), "GET /donations/donor/{id} es privado (requiere donante propietario o admin)");
        assertFalse(filter.isPublicRequest(getDonationsFoundation), "GET /donations/foundation/{id} es privado (requiere fundación receptora o admin)");
        assertFalse(filter.isPublicRequest(getDonaciones), "GET /donaciones no es público");
        assertFalse(filter.isPublicRequest(getDonacionesDetail), "GET /donaciones/{id} es privado");
        assertFalse(filter.isPublicRequest(getDonacionesDonor), "GET /donaciones/donor/{id} es privado");
        assertFalse(filter.isPublicRequest(getDonacionesFoundation), "GET /donaciones/foundation/{id} es privado");

        MockServerHttpRequest getRepresentative = MockServerHttpRequest.get("/api/v1/donations/foundations/representative").build();
        assertFalse(filter.isPublicRequest(getRepresentative), "GET /donations/foundations/representative es privado (requiere token)");
    }

    @Test
    @DisplayName("Filter Consultas Privadas Anónimas: Rechaza con 401 Unauthorized consultas GET privadas de donaciones y usuario")
    void filter_consultasPrivadasAnonimas_retorna401Unauthorized() {
        // 1. Consulta privada de donación por ID
        MockServerHttpRequest reqDonation = MockServerHttpRequest.get("/api/v1/donations/don-456").build();
        MockServerWebExchange exDonation = MockServerWebExchange.from(reqDonation);
        StepVerifier.create(filter.filter(exDonation, ex -> Mono.empty())).verifyComplete();
        assertEquals(HttpStatus.UNAUTHORIZED, exDonation.getResponse().getStatusCode(),
                "Acceso anónimo a /api/v1/donations/{id} debe retornar 401");

        // 2. Consulta privada de donaciones por donante
        MockServerHttpRequest reqDonor = MockServerHttpRequest.get("/api/v1/donations/donor/101").build();
        MockServerWebExchange exDonor = MockServerWebExchange.from(reqDonor);
        StepVerifier.create(filter.filter(exDonor, ex -> Mono.empty())).verifyComplete();
        assertEquals(HttpStatus.UNAUTHORIZED, exDonor.getResponse().getStatusCode(),
                "Acceso anónimo a /api/v1/donations/donor/{donorId} debe retornar 401");

        // 3. Consulta privada de donaciones por fundación
        MockServerHttpRequest reqFoundation = MockServerHttpRequest.get("/api/v1/donaciones/foundation/501").build();
        MockServerWebExchange exFoundation = MockServerWebExchange.from(reqFoundation);
        StepVerifier.create(filter.filter(exFoundation, ex -> Mono.empty())).verifyComplete();
        assertEquals(HttpStatus.UNAUTHORIZED, exFoundation.getResponse().getStatusCode(),
                "Acceso anónimo a /api/v1/donaciones/foundation/{foundationId} debe retornar 401");

        // 4. Consulta privada de publicaciones de un usuario específico (/user/{userId})
        MockServerHttpRequest reqUserPubs = MockServerHttpRequest.get("/api/v1/second-hand/user/101").build();
        MockServerWebExchange exUserPubs = MockServerWebExchange.from(reqUserPubs);
        StepVerifier.create(filter.filter(exUserPubs, ex -> Mono.empty())).verifyComplete();
        assertEquals(HttpStatus.UNAUTHORIZED, exUserPubs.getResponse().getStatusCode(),
                "Acceso anónimo a /api/v1/second-hand/user/{userId} debe retornar 401");

        // 5. Consulta privada definitiva de publicaciones por usuario (/publications/users/{userId})
        MockServerHttpRequest reqDefUserPubs = MockServerHttpRequest.get("/api/v1/second-hand/publications/users/101").build();
        MockServerWebExchange exDefUserPubs = MockServerWebExchange.from(reqDefUserPubs);
        StepVerifier.create(filter.filter(exDefUserPubs, ex -> Mono.empty())).verifyComplete();
        assertEquals(HttpStatus.UNAUTHORIZED, exDefUserPubs.getResponse().getStatusCode(),
                "Acceso anónimo a /api/v1/second-hand/publications/users/{userId} debe retornar 401");
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
        assertEquals("ADMIN", JwtTokenValidatorFilter.normalizeDownstreamRole("COMPRADOR", "COMPRADOR,ADMIN_STYLERADAR"));
        assertEquals("COMPRADOR", JwtTokenValidatorFilter.normalizeDownstreamRole("COMPRADOR", "COMPRADOR,USER"));
        assertNull(JwtTokenValidatorFilter.normalizeDownstreamRole(null, null));

        assertNull(JwtTokenValidatorFilter.normalizeDownstreamRoles(null));
        assertEquals("", JwtTokenValidatorFilter.normalizeDownstreamRoles(""));
        assertEquals("COMPRADOR,ADMIN_STYLERADAR,ADMIN", JwtTokenValidatorFilter.normalizeDownstreamRoles("COMPRADOR,ADMIN_STYLERADAR"));
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
        MockServerHttpRequest catalogoReq = MockServerHttpRequest.get("/api/v1/catalogo").build();
        assertTrue(filter.isPublicRequest(catalogoReq), "GET /api/v1/catalogo debe ser público");
        MockServerHttpRequest catalogoSlashReq = MockServerHttpRequest.get("/api/v1/catalogo/").build();
        assertTrue(filter.isPublicRequest(catalogoSlashReq), "GET /api/v1/catalogo/ debe ser público");

        // Rutas privadas de donaciones y segunda mano
        String[] privatePaths = {
                "/api/v1/second-hand/publications/users/123",
                "/api/v1/second-hand/publications/user/123",
                "/api/v1/second-hand/user/123",
                "/api/v1/segunda-mano/publicaciones/usuarios/123",
                "/api/v1/segunda-mano/publicaciones/usuario/123",
                "/api/v1/segunda-mano/user/123",
                "/api/v1/donations/my-donations",
                "/api/v1/donaciones/mis-donaciones"
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
}
