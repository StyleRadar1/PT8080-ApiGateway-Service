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
    @DisplayName("Consulta Pública GET vs Rutas Privadas: Permite catálogo público (/second-hand) pero bloquea donaciones y consultas por usuario")
    void isPublicRequest_permiteCatalogoPublicoYBloqueaConsultasPrivadas() {
        // Endpoints públicos del contrato
        MockServerHttpRequest getSecondHand = MockServerHttpRequest.get("/api/v1/second-hand").build();
        MockServerHttpRequest getSecondHandDetail = MockServerHttpRequest.get("/api/v1/second-hand/prenda-123").build();
        MockServerHttpRequest getSegundaMano = MockServerHttpRequest.get("/api/v1/segunda-mano").build();
        MockServerHttpRequest getSegundaManoDetail = MockServerHttpRequest.get("/api/v1/segunda-mano/prenda-123").build();

        assertTrue(filter.isPublicRequest(getSecondHand), "GET /second-hand debe ser público");
        assertTrue(filter.isPublicRequest(getSecondHandDetail), "GET /second-hand/{id} debe ser público");
        assertTrue(filter.isPublicRequest(getSegundaMano), "GET /segunda-mano debe ser público");
        assertTrue(filter.isPublicRequest(getSegundaManoDetail), "GET /segunda-mano/{id} debe ser público");

        // Rutas privadas del microservicio: consultas por usuario
        MockServerHttpRequest getUserSecondHand = MockServerHttpRequest.get("/api/v1/second-hand/user/101").build();
        MockServerHttpRequest getUserSegundaMano = MockServerHttpRequest.get("/api/v1/segunda-mano/user/101").build();
        assertFalse(filter.isPublicRequest(getUserSecondHand), "GET /second-hand/user/{id} es privado");
        assertFalse(filter.isPublicRequest(getUserSegundaMano), "GET /segunda-mano/user/{id} es privado");

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

        // 4. Consulta privada de publicaciones de un usuario específico
        MockServerHttpRequest reqUserPubs = MockServerHttpRequest.get("/api/v1/second-hand/user/101").build();
        MockServerWebExchange exUserPubs = MockServerWebExchange.from(reqUserPubs);
        StepVerifier.create(filter.filter(exUserPubs, ex -> Mono.empty())).verifyComplete();
        assertEquals(HttpStatus.UNAUTHORIZED, exUserPubs.getResponse().getStatusCode(),
                "Acceso anónimo a /api/v1/second-hand/user/{userId} debe retornar 401");
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
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/second-hand").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean chainCalled = new AtomicBoolean(false);

        Mono<Void> result = filter.filter(exchange, ex -> {
            chainCalled.set(true);
            return Mono.empty();
        });

        StepVerifier.create(result).verifyComplete();
        assertTrue(chainCalled.get(), "La consulta GET pública debe fluir hacia downstream");
        assertNotEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
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
}
