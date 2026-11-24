package edu.dosw.styleradar.gateway.infrastructure.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.NettyWriteResponseFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownstreamCorsSanitizerFilterTest {

    private final DownstreamCorsSanitizerFilter filter = new DownstreamCorsSanitizerFilter();

    @Test
    @DisplayName("Remueve el header Origin antes de reenviar la petición y conserva Authorization")
    void shouldStripOriginHeaderBeforeForwardingAndKeepOtherHeaders() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/try-on/quotas")
                .header(HttpHeaders.ORIGIN, "https://styleradar-qa.vercel.app")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-jwt-token")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            forwarded.set(ex);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        HttpHeaders forwardedHeaders = forwarded.get().getRequest().getHeaders();
        assertFalse(forwardedHeaders.containsKey(HttpHeaders.ORIGIN));
        assertEquals("Bearer test-jwt-token", forwardedHeaders.getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    @DisplayName("Si la petición no trae Origin, propaga el mismo exchange sin alterarlo")
    void shouldPassExchangeUnchangedWhenOriginHeaderIsAbsent() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/try-on/quotas")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-jwt-token")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            forwarded.set(ex);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertSame(exchange, forwarded.get());
    }

    @Test
    @DisplayName("Conserva únicamente las cabeceras CORS autorizadas por el Gateway y preserva Vary del microservicio sin duplicados")
    void shouldKeepOnlyGatewayAuthorizedCorsHeadersAndPreserveDownstreamVaryValues() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/donations/publications")
                .header(HttpHeaders.ORIGIN, "https://styleradar-qa.vercel.app")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        HttpHeaders gatewayHeaders = exchange.getResponse().getHeaders();
        gatewayHeaders.set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://styleradar-qa.vercel.app");
        gatewayHeaders.set(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        gatewayHeaders.set(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                "Retry-After, Authorization, WWW-Authenticate, Location, Content-Disposition");
        gatewayHeaders.addAll(HttpHeaders.VARY, List.of(
                "Origin",
                "Access-Control-Request-Method",
                "Access-Control-Request-Headers"
        ));

        GatewayFilterChain chain = ex -> {
            HttpHeaders resHeaders = ex.getResponse().getHeaders();
            resHeaders.add(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
            resHeaders.add(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
            resHeaders.add(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "Location, X-Internal-Debug");
            resHeaders.addAll(HttpHeaders.VARY, List.of("Origin", "Access-Control-Request-Method", "Accept-Encoding"));
            return ex.getResponse().setComplete();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        HttpHeaders responseHeaders = exchange.getResponse().getHeaders();
        assertEquals(List.of("https://styleradar-qa.vercel.app"),
                responseHeaders.get(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertEquals(List.of("true"),
                responseHeaders.get(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        assertEquals(
                List.of("Retry-After, Authorization, WWW-Authenticate, Location, Content-Disposition"),
                responseHeaders.get(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS)
        );
        assertEquals(
                List.of("Origin", "Access-Control-Request-Method", "Access-Control-Request-Headers", "Accept-Encoding"),
                responseHeaders.get(HttpHeaders.VARY)
        );
    }

    @Test
    @DisplayName("Si el Gateway no autorizó cabeceras CORS previas, elimina las emitidas por downstream y conserva Vary")
    void shouldRemoveUnauthorizedDownstreamCorsHeadersWhenGatewayDidNotAuthorizeAny() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/donations/publications")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = ex -> {
            HttpHeaders resHeaders = ex.getResponse().getHeaders();
            resHeaders.add(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
            resHeaders.add(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "X-Secret-Header");
            resHeaders.add(HttpHeaders.VARY, "Accept-Encoding");
            return ex.getResponse().setComplete();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        HttpHeaders responseHeaders = exchange.getResponse().getHeaders();
        assertFalse(responseHeaders.containsKey(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertFalse(responseHeaders.containsKey(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        assertFalse(responseHeaders.containsKey(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS));
        assertEquals(List.of("Accept-Encoding"), responseHeaders.get(HttpHeaders.VARY));
    }

    @Test
    @DisplayName("Maneja de forma segura HttpHeaders nulos, vacíos, de un solo valor o con entradas en blanco")
    void shouldHandleNullAndEmptyHeadersGracefully() {
        assertTrue(DownstreamCorsSanitizerFilter.captureHeaderValues(null, HttpHeaders.ORIGIN).isEmpty());
        HttpHeaders blankHeaders = new HttpHeaders();
        blankHeaders.put(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, Arrays.asList("   ", null, ""));
        assertTrue(DownstreamCorsSanitizerFilter.captureHeaderValues(
                blankHeaders, HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN).isEmpty());

        assertDoesNotThrow(() -> DownstreamCorsSanitizerFilter.sanitizeCorsResponseHeaders(
                null, List.of(), List.of(), List.of()));

        HttpHeaders singleVary = new HttpHeaders();
        singleVary.set(HttpHeaders.VARY, "Origin");
        assertDoesNotThrow(() -> DownstreamCorsSanitizerFilter.sanitizeCorsResponseHeaders(
                singleVary, null, List.of(), List.of()));
        assertEquals(List.of("Origin"), singleVary.get(HttpHeaders.VARY));

        HttpHeaders blankVaryDuplicates = new HttpHeaders();
        blankVaryDuplicates.put(HttpHeaders.VARY, Arrays.asList(" ", null, "  ", " , "));
        assertDoesNotThrow(() -> DownstreamCorsSanitizerFilter.sanitizeCorsResponseHeaders(
                blankVaryDuplicates, List.of(), List.of(), List.of()));
        assertEquals(4, blankVaryDuplicates.get(HttpHeaders.VARY).size());
    }

    @Test
    @DisplayName("El orden del filtro se ubica inmediatamente después de NettyWriteResponseFilter")
    void shouldRunAfterNettyWriteResponseFilter() {
        assertEquals(NettyWriteResponseFilter.WRITE_RESPONSE_FILTER_ORDER + 1, filter.getOrder());
    }
}
