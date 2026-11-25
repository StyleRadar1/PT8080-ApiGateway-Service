package edu.dosw.styleradar.gateway.infrastructure.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SecurityHeadersWebFilterTest {

    private final SecurityHeadersWebFilter filter = new SecurityHeadersWebFilter();

    @Test
    @DisplayName("Emite cabeceras de seguridad HTTP estándar en respuestas del Gateway")
    void filter_emiteCabecerasDeSeguridadEnRutasGenerales() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/stores").build()
        );

        Mono<Void> result = filter.filter(exchange, ex -> ex.getResponse().setComplete());
        StepVerifier.create(result).verifyComplete();

        HttpHeaders headers = exchange.getResponse().getHeaders();
        assertEquals("max-age=31536000; includeSubDomains", headers.getFirst(SecurityHeadersWebFilter.STRICT_TRANSPORT_SECURITY));
        assertEquals("nosniff", headers.getFirst(SecurityHeadersWebFilter.X_CONTENT_TYPE_OPTIONS));
        assertEquals("DENY", headers.getFirst(SecurityHeadersWebFilter.X_FRAME_OPTIONS));
        assertEquals("strict-origin-when-cross-origin", headers.getFirst(SecurityHeadersWebFilter.REFERRER_POLICY));
        assertEquals("camera=(), microphone=(), geolocation=(self)", headers.getFirst(SecurityHeadersWebFilter.PERMISSIONS_POLICY));
        assertNull(headers.getFirst(HttpHeaders.CACHE_CONTROL));
    }

    @Test
    @DisplayName("Emite Cache-Control: no-store en rutas de autenticación /api/v1/auth/**")
    void filter_emiteCacheControlNoStoreEnRutasAuth() {
        for (String authPath : new String[]{"/api/v1/auth", "/api/v1/auth/login", "/api/v1/auth/otp/verify"}) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.post(authPath).build()
            );

            Mono<Void> result = filter.filter(exchange, ex -> ex.getResponse().setComplete());
            StepVerifier.create(result).verifyComplete();

            HttpHeaders headers = exchange.getResponse().getHeaders();
            assertEquals("no-store", headers.getFirst(HttpHeaders.CACHE_CONTROL),
                    "Debe emitir Cache-Control: no-store en " + authPath);
            assertEquals("nosniff", headers.getFirst(SecurityHeadersWebFilter.X_CONTENT_TYPE_OPTIONS));
        }
    }

    @Test
    @DisplayName("No sobreescribe cabeceras de seguridad ya presentes y maneja headers null")
    void applySecurityHeaders_respetaCabecerasExistentesYNullSafe() {
        SecurityHeadersWebFilter.applySecurityHeaders(null, "/api/v1/auth/login");

        HttpHeaders headers = new HttpHeaders();
        headers.set(SecurityHeadersWebFilter.X_FRAME_OPTIONS, "SAMEORIGIN");
        SecurityHeadersWebFilter.applySecurityHeaders(headers, null);

        assertEquals("SAMEORIGIN", headers.getFirst(SecurityHeadersWebFilter.X_FRAME_OPTIONS));
        assertEquals("nosniff", headers.getFirst(SecurityHeadersWebFilter.X_CONTENT_TYPE_OPTIONS));
        assertNull(headers.getFirst(HttpHeaders.CACHE_CONTROL));
    }

    @Test
    @DisplayName("getOrder retorna Ordered.HIGHEST_PRECEDENCE + 10")
    void getOrder_retornaPrioridadEsperada() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 10, filter.getOrder());
    }
}
