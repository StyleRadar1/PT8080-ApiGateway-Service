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
    @DisplayName("BUG-011: Emite cabeceras de seguridad HTTP estándar y CSP estricto en rutas de negocio")
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
        assertEquals(SecurityHeadersWebFilter.API_CSP_VALUE, headers.getFirst(SecurityHeadersWebFilter.CONTENT_SECURITY_POLICY));
        assertNull(headers.getFirst(HttpHeaders.CACHE_CONTROL));
    }

    @Test
    @DisplayName("BUG-011: Emite CSP compatible con Swagger UI y X-Frame-Options SAMEORIGIN en rutas de documentación")
    void filter_emiteCspCompatibileConSwaggerUI() {
        for (String swaggerPath : new String[]{"/swagger-ui.html", "/swagger-ui/index.html", "/v3/api-docs", "/v3/api-docs/inventario"}) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get(swaggerPath).build()
            );

            Mono<Void> result = filter.filter(exchange, ex -> ex.getResponse().setComplete());
            StepVerifier.create(result).verifyComplete();

            HttpHeaders headers = exchange.getResponse().getHeaders();
            assertEquals("SAMEORIGIN", headers.getFirst(SecurityHeadersWebFilter.X_FRAME_OPTIONS),
                    "Swagger debe permitir renderizado en iframe propio (SAMEORIGIN): " + swaggerPath);
            assertEquals(SecurityHeadersWebFilter.SWAGGER_CSP_VALUE, headers.getFirst(SecurityHeadersWebFilter.CONTENT_SECURITY_POLICY),
                    "Swagger debe permitir scripts y estilos requeridos por OpenAPI: " + swaggerPath);
        }
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
            assertEquals(SecurityHeadersWebFilter.API_CSP_VALUE, headers.getFirst(SecurityHeadersWebFilter.CONTENT_SECURITY_POLICY));
        }
    }

    @Test
    @DisplayName("BUG-011: Cabeceras downstream no pueden debilitar las políticas perimetrales del Gateway")
    void applySecurityHeaders_previeneDebilitamientoPorDownstreamYEsNullSafe() {
        SecurityHeadersWebFilter.applySecurityHeaders(null, "/api/v1/auth/login");

        HttpHeaders downstreamHeaders = new HttpHeaders();
        // Simular intento de downstream de debilitar perimetro
        downstreamHeaders.set(SecurityHeadersWebFilter.X_FRAME_OPTIONS, "ALLOWALL");
        downstreamHeaders.set(SecurityHeadersWebFilter.CONTENT_SECURITY_POLICY, "default-src * 'unsafe-inline' 'unsafe-eval'");
        downstreamHeaders.set(SecurityHeadersWebFilter.STRICT_TRANSPORT_SECURITY, "max-age=0");

        SecurityHeadersWebFilter.applySecurityHeaders(downstreamHeaders, "/api/v1/stores");

        assertEquals("DENY", downstreamHeaders.getFirst(SecurityHeadersWebFilter.X_FRAME_OPTIONS));
        assertEquals("max-age=31536000; includeSubDomains", downstreamHeaders.getFirst(SecurityHeadersWebFilter.STRICT_TRANSPORT_SECURITY));
        assertEquals(SecurityHeadersWebFilter.API_CSP_VALUE, downstreamHeaders.getFirst(SecurityHeadersWebFilter.CONTENT_SECURITY_POLICY));
    }

    @Test
    @DisplayName("getOrder retorna Ordered.HIGHEST_PRECEDENCE + 10")
    void getOrder_retornaPrioridadEsperada() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 10, filter.getOrder());
    }
}
