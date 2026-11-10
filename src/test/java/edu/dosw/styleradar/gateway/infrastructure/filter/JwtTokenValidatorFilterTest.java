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
    @DisplayName("Sin Authorization Header: Continúa hacia downstream pero eliminando cabeceras X-User-* inyectadas por el cliente")
    void filter_sinAuthorizationHeader_eliminaHeadersSpoofed() {
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
        assertTrue(chainCalled.get(), "La cadena de filtros debió continuar para petición sin token");
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
}
