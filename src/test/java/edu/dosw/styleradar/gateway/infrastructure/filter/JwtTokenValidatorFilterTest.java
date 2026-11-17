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
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean chainCalled = new AtomicBoolean(false);

        Mono<Void> result = filter.filter(exchange, ex -> {
            chainCalled.set(true);
            assertNull(ex.getRequest().getHeaders().getFirst(JwtTokenValidatorFilter.USER_ID_HEADER),
                    "La cabecera X-User-Id del cliente debió ser eliminada para prevenir spoofing");
            assertNull(ex.getRequest().getHeaders().getFirst(JwtTokenValidatorFilter.USER_ROLE_HEADER),
                    "La cabecera X-User-Role del cliente debió ser eliminada para prevenir spoofing");
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
    @DisplayName("Con Token JWT Válido: Inyecta X-User-Id y X-User-Role auténticos y sobreescribe cualquier intento de spoofing")
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
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean chainCalled = new AtomicBoolean(false);

        Mono<Void> result = filter.filter(exchange, ex -> {
            chainCalled.set(true);
            assertEquals("user-999-portal8080", ex.getRequest().getHeaders().getFirst(JwtTokenValidatorFilter.USER_ID_HEADER));
            assertEquals("ROLE_CLIENTE", ex.getRequest().getHeaders().getFirst(JwtTokenValidatorFilter.USER_ROLE_HEADER));
            return Mono.empty();
        });

        StepVerifier.create(result).verifyComplete();
        assertTrue(chainCalled.get(), "La cadena de filtros debió invocarse con petición autenticada");
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
    @DisplayName("Consulta Pública GET: Permite acceso anónimo a consultas de publicaciones (/second-hand y /donations)")
    void isPublicRequest_permiteConsultasGetSegundaManoYDonaciones() {
        MockServerHttpRequest getSecondHand = MockServerHttpRequest.get("/api/v1/second-hand").build();
        MockServerHttpRequest getSecondHandDetail = MockServerHttpRequest.get("/api/v1/second-hand/prenda-123").build();
        MockServerHttpRequest getSegundaMano = MockServerHttpRequest.get("/api/v1/segunda-mano").build();
        MockServerHttpRequest getDonations = MockServerHttpRequest.get("/api/v1/donations").build();
        MockServerHttpRequest getDonationsDetail = MockServerHttpRequest.get("/api/v1/donations/don-456").build();
        MockServerHttpRequest getDonaciones = MockServerHttpRequest.get("/api/v1/donaciones").build();

        assertTrue(filter.isPublicRequest(getSecondHand));
        assertTrue(filter.isPublicRequest(getSecondHandDetail));
        assertTrue(filter.isPublicRequest(getSegundaMano));
        assertTrue(filter.isPublicRequest(getDonations));
        assertTrue(filter.isPublicRequest(getDonationsDetail));
        assertTrue(filter.isPublicRequest(getDonaciones));
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
