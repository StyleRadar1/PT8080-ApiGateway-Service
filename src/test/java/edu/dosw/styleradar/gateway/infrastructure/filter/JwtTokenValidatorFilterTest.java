package edu.dosw.styleradar.gateway.infrastructure.filter;

import edu.dosw.styleradar.gateway.infrastructure.filter.JwtTokenValidatorFilter;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenValidatorFilterTest {

    private static final String SECRET = "c3R5bGVyYWRhci1qd3Qtc2VjcmV0LWtleS0yMDI2LXBvcnRhbDgwODAtc3VwZXItc2VjdXJlCg==";
    private JwtTokenValidatorFilter filter;
    private SecretKey secretKey;

    @BeforeEach
    void setUp() {
        filter = new JwtTokenValidatorFilter(SECRET);
        secretKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void filter_sinAuthorizationHeader_continuaSinMutar() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/auth/login").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        Mono<Void> result = filter.filter(exchange, ex -> {
            assertNull(ex.getRequest().getHeaders().getFirst(JwtTokenValidatorFilter.USER_ID_HEADER));
            return Mono.empty();
        });

        StepVerifier.create(result).verifyComplete();
    }

    @Test
    void filter_conTokenValido_inyectaHeadersDeUsuario() {
        String token = Jwts.builder()
                .subject("user-999-portal8080")
                .claim("role", "ROLE_CLIENTE")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600000))
                .signWith(secretKey)
                .compact();

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/probador/cuotas")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        Mono<Void> result = filter.filter(exchange, ex -> {
            assertEquals("user-999-portal8080", ex.getRequest().getHeaders().getFirst(JwtTokenValidatorFilter.USER_ID_HEADER));
            assertEquals("ROLE_CLIENTE", ex.getRequest().getHeaders().getFirst(JwtTokenValidatorFilter.USER_ROLE_HEADER));
            return Mono.empty();
        });

        StepVerifier.create(result).verifyComplete();
    }

    @Test
    void filter_conTokenInvalido_continuaSinLanzarExcepcion() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/probador/cuotas")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token-invalido-o-expirado")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        Mono<Void> result = filter.filter(exchange, ex -> {
            assertNull(ex.getRequest().getHeaders().getFirst(JwtTokenValidatorFilter.USER_ID_HEADER));
            return Mono.empty();
        });

        StepVerifier.create(result).verifyComplete();
    }
}

