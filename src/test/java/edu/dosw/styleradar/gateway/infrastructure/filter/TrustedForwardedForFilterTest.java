package edu.dosw.styleradar.gateway.infrastructure.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Pruebas Unitarias - TrustedForwardedForFilter (Protección Anti-Spoofing de IP)")
class TrustedForwardedForFilterTest {

    @Test
    @DisplayName("Cliente directo no confiable: descarta X-Forwarded-For, X-Real-IP y Forwarded e inyecta solo la IP TCP real")
    void untrustedDirectClientCannotSpoofForwardedHeaders() {
        TrustedForwardedForFilter filter = new TrustedForwardedForFilter(List.of("172.16.0.0/12", "10.0.0.0/8"));

        MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/auth/login")
                .remoteAddress(new InetSocketAddress("198.51.100.7", 45123))
                .header(TrustedForwardedForFilter.X_FORWARDED_FOR_HEADER, "1.2.3.4")
                .header(TrustedForwardedForFilter.X_REAL_IP_HEADER, "5.6.7.8")
                .header(TrustedForwardedForFilter.FORWARDED_HEADER, "for=9.9.9.9")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<List<String>> forwardedForSent = new AtomicReference<>();
        AtomicReference<String> realIpSent = new AtomicReference<>();
        AtomicReference<String> forwardedSent = new AtomicReference<>();

        Mono<Void> result = filter.filter(exchange, ex -> {
            forwardedForSent.set(ex.getRequest().getHeaders().get(TrustedForwardedForFilter.X_FORWARDED_FOR_HEADER));
            realIpSent.set(ex.getRequest().getHeaders().getFirst(TrustedForwardedForFilter.X_REAL_IP_HEADER));
            forwardedSent.set(ex.getRequest().getHeaders().getFirst(TrustedForwardedForFilter.FORWARDED_HEADER));
            return Mono.empty();
        });

        StepVerifier.create(result).verifyComplete();
        assertEquals(List.of("198.51.100.7"), forwardedForSent.get());
        assertNull(realIpSent.get());
        assertNull(forwardedSent.get());
    }

    @Test
    @DisplayName("Proxy confiable con cadena contaminada y puerto Azure ARR: toma la IP derecha no confiable")
    void trustedProxyExtractsRightmostUntrustedIpStrippingClientSpoofAndPort() {
        TrustedForwardedForFilter filter = new TrustedForwardedForFilter(List.of("172.16.0.0/12"));

        MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/auth/login")
                .remoteAddress(new InetSocketAddress("172.16.1.4", 443))
                .header(TrustedForwardedForFilter.X_FORWARDED_FOR_HEADER, "1.2.3.4, 203.0.113.45:54321")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<List<String>> forwardedForSent = new AtomicReference<>();
        StepVerifier.create(filter.filter(exchange, ex -> {
            forwardedForSent.set(ex.getRequest().getHeaders().get(TrustedForwardedForFilter.X_FORWARDED_FOR_HEADER));
            return Mono.empty();
        })).verifyComplete();

        assertEquals(List.of("203.0.113.45"), forwardedForSent.get());
    }

    @Test
    @DisplayName("Múltiples proxies confiables: salta los hops confiables desde la derecha y descarta prefijo falsificado")
    void multipleTrustedProxiesResolveRealClientIpFromRight() {
        TrustedForwardedForFilter filter = new TrustedForwardedForFilter(List.of("10.0.0.0/8", "172.16.0.0/12"));

        MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/auth/login")
                .remoteAddress(new InetSocketAddress("172.16.1.4", 8080))
                .header(TrustedForwardedForFilter.X_FORWARDED_FOR_HEADER, "1.2.3.4, 198.51.100.25, 10.0.0.5")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<List<String>> forwardedForSent = new AtomicReference<>();
        StepVerifier.create(filter.filter(exchange, ex -> {
            forwardedForSent.set(ex.getRequest().getHeaders().get(TrustedForwardedForFilter.X_FORWARDED_FOR_HEADER));
            return Mono.empty();
        })).verifyComplete();

        assertEquals(List.of("198.51.100.25"), forwardedForSent.get());
    }

    @Test
    @DisplayName("Múltiples cabeceras X-Forwarded-For desde proxy confiable: toma la derecha no confiable")
    void multipleXForwardedForHeadersTakeRightmostUntrustedIp() {
        TrustedForwardedForFilter filter = new TrustedForwardedForFilter(List.of("172.16.0.0/12"));

        MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/auth/login")
                .remoteAddress(new InetSocketAddress("172.16.1.4", 8080))
                .header(TrustedForwardedForFilter.X_FORWARDED_FOR_HEADER, "1.2.3.4")
                .header(TrustedForwardedForFilter.X_FORWARDED_FOR_HEADER, "198.51.100.88")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<List<String>> forwardedForSent = new AtomicReference<>();
        StepVerifier.create(filter.filter(exchange, ex -> {
            forwardedForSent.set(ex.getRequest().getHeaders().get(TrustedForwardedForFilter.X_FORWARDED_FOR_HEADER));
            return Mono.empty();
        })).verifyComplete();

        assertEquals(List.of("198.51.100.88"), forwardedForSent.get());
    }

    @Test
    @DisplayName("Salto malformado a la derecha desde proxy confiable: detiene recorrido y usa remoteIp de forma segura")
    void malformedRightmostHopFallsBackToRemoteIp() {
        TrustedForwardedForFilter filter = new TrustedForwardedForFilter(List.of("172.16.0.0/12"));

        MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/auth/login")
                .remoteAddress(new InetSocketAddress("172.16.1.4", 8080))
                .header(TrustedForwardedForFilter.X_FORWARDED_FOR_HEADER, "1.2.3.4, invalid-host")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<List<String>> forwardedForSent = new AtomicReference<>();
        StepVerifier.create(filter.filter(exchange, ex -> {
            forwardedForSent.set(ex.getRequest().getHeaders().get(TrustedForwardedForFilter.X_FORWARDED_FOR_HEADER));
            return Mono.empty();
        })).verifyComplete();

        assertEquals(List.of("172.16.1.4"), forwardedForSent.get());
    }

    @Test
    @DisplayName("Fail-closed con lista vacía de proxies: ni loopback puede inyectar X-Forwarded-For")
    void emptyTrustedProxiesListIsFailClosedEvenForLoopback() {
        TrustedForwardedForFilter filter = new TrustedForwardedForFilter(List.of());

        MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/auth/login")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 55000))
                .header(TrustedForwardedForFilter.X_FORWARDED_FOR_HEADER, "203.0.113.45")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<List<String>> forwardedForSent = new AtomicReference<>();
        StepVerifier.create(filter.filter(exchange, ex -> {
            forwardedForSent.set(ex.getRequest().getHeaders().get(TrustedForwardedForFilter.X_FORWARDED_FOR_HEADER));
            return Mono.empty();
        })).verifyComplete();

        assertEquals(List.of("127.0.0.1"), forwardedForSent.get());
    }

    @Test
    @DisplayName("Soporta IPv6 con corchetes y puerto en extracción de IP literal")
    void extractsIpv6WithBracketsAndPort() {
        TrustedForwardedForFilter filter = new TrustedForwardedForFilter(List.of("::1"));

        MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/auth/login")
                .remoteAddress(new InetSocketAddress("0:0:0:0:0:0:0:1", 443))
                .header(TrustedForwardedForFilter.X_FORWARDED_FOR_HEADER, "[2001:db8::1]:8443")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<List<String>> forwardedForSent = new AtomicReference<>();
        StepVerifier.create(filter.filter(exchange, ex -> {
            forwardedForSent.set(ex.getRequest().getHeaders().get(TrustedForwardedForFilter.X_FORWARDED_FOR_HEADER));
            return Mono.empty();
        })).verifyComplete();

        assertEquals(List.of("2001:db8::1"), forwardedForSent.get());
    }
}
