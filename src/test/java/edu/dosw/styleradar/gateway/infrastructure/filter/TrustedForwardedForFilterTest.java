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
    @DisplayName("Proxy confiable Azure App Service Linux (169.254.0.0/16) y ARR con puerto: toma la IP derecha no confiable")
    void trustedAzureLinuxProxyExtractsRightmostUntrustedIpStrippingClientSpoofAndPort() {
        TrustedForwardedForFilter filter = new TrustedForwardedForFilter(
                List.of("169.254.0.0/16", "172.16.0.0/12")
        );

        MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/auth/login")
                .remoteAddress(new InetSocketAddress("169.254.129.1", 443))
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
    @DisplayName("Múltiples cabeceras X-Forwarded-For desde proxy confiable: ignora vacíos y toma la derecha no confiable")
    void multipleXForwardedForHeadersTakeRightmostUntrustedIp() {
        TrustedForwardedForFilter filter = new TrustedForwardedForFilter(List.of("172.16.0.0/12"));

        MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/auth/login")
                .remoteAddress(new InetSocketAddress("172.16.1.4", 8080))
                .header(TrustedForwardedForFilter.X_FORWARDED_FOR_HEADER, "1.2.3.4")
                .header(TrustedForwardedForFilter.X_FORWARDED_FOR_HEADER, "   ", " , 198.51.100.88 ")
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
    @DisplayName("Fallback X-Real-IP: usa X-Real-IP válido si no hubo X-Forwarded-For y cae a remoteIp si X-Real-IP es proxy confiable")
    void fallbackToXRealIpAndRemoteIpWhenNeeded() {
        TrustedForwardedForFilter filter = new TrustedForwardedForFilter(List.of("172.16.0.0/12"));

        MockServerHttpRequest reqWithRealIp = MockServerHttpRequest.post("/api/v1/auth/login")
                .remoteAddress(new InetSocketAddress("172.16.1.4", 8080))
                .header(TrustedForwardedForFilter.X_REAL_IP_HEADER, "203.0.113.77")
                .build();
        AtomicReference<String> resolvedRealIp = new AtomicReference<>();
        StepVerifier.create(filter.filter(MockServerWebExchange.from(reqWithRealIp), ex -> {
            resolvedRealIp.set(ex.getRequest().getHeaders().getFirst(TrustedForwardedForFilter.X_FORWARDED_FOR_HEADER));
            return Mono.empty();
        })).verifyComplete();
        assertEquals("203.0.113.77", resolvedRealIp.get());

        // Todos los saltos son proxies confiables -> cae a X-Real-IP no confiable o a remoteIp si X-Real-IP también es confiable
        MockServerHttpRequest reqAllTrusted = MockServerHttpRequest.post("/api/v1/auth/login")
                .remoteAddress(new InetSocketAddress("172.16.1.4", 8080))
                .header(TrustedForwardedForFilter.X_FORWARDED_FOR_HEADER, "172.16.2.1")
                .header(TrustedForwardedForFilter.X_REAL_IP_HEADER, "172.16.3.1")
                .build();
        AtomicReference<String> fallbackRemote = new AtomicReference<>();
        StepVerifier.create(filter.filter(MockServerWebExchange.from(reqAllTrusted), ex -> {
            fallbackRemote.set(ex.getRequest().getHeaders().getFirst(TrustedForwardedForFilter.X_FORWARDED_FOR_HEADER));
            return Mono.empty();
        })).verifyComplete();
        assertEquals("172.16.1.4", fallbackRemote.get());
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
    @DisplayName("Fail-closed con lista vacía o null: ni loopback puede inyectar X-Forwarded-For y sin remoteAddress no inyecta cabecera")
    void emptyOrNullTrustedProxiesIsFailClosedAndNullRemoteAddressStripsHeaders() {
        TrustedForwardedForFilter emptyFilter = new TrustedForwardedForFilter(List.of());
        TrustedForwardedForFilter nullListFilter = new TrustedForwardedForFilter(null);
        assertFalse(nullListFilter.isTrustedProxy("127.0.0.1"));

        MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/auth/login")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 55000))
                .header(TrustedForwardedForFilter.X_FORWARDED_FOR_HEADER, "203.0.113.45")
                .build();

        AtomicReference<List<String>> forwardedForSent = new AtomicReference<>();
        StepVerifier.create(emptyFilter.filter(MockServerWebExchange.from(request), ex -> {
            forwardedForSent.set(ex.getRequest().getHeaders().get(TrustedForwardedForFilter.X_FORWARDED_FOR_HEADER));
            return Mono.empty();
        })).verifyComplete();
        assertEquals(List.of("127.0.0.1"), forwardedForSent.get());

        // remoteAddress nulo: limpia las cabeceras heredadas y no agrega X-Forwarded-For
        MockServerHttpRequest noRemoteReq = MockServerHttpRequest.post("/api/v1/auth/login")
                .header(TrustedForwardedForFilter.X_FORWARDED_FOR_HEADER, "203.0.113.45")
                .build();
        AtomicReference<String> noRemoteHeader = new AtomicReference<>();
        StepVerifier.create(emptyFilter.filter(MockServerWebExchange.from(noRemoteReq), ex -> {
            noRemoteHeader.set(ex.getRequest().getHeaders().getFirst(TrustedForwardedForFilter.X_FORWARDED_FOR_HEADER));
            return Mono.empty();
        })).verifyComplete();
        assertNull(noRemoteHeader.get());
    }

    @Test
    @DisplayName("Soporta dirección no resuelta (createUnresolved), IPv6 con corchetes/zone ID y descarta formatos malformados")
    void extractsUnresolvedAddressIpv6VariantsAndRejectsMalformedLiterals() {
        TrustedForwardedForFilter filter = new TrustedForwardedForFilter(
                List.of("", " , ", "invalid-cidr/24", "10.0.0.0/99", "10.0.0.0/-1", "10.0.0.0/abc", "172.16.0.0/12", "::1")
        );

        // Unresolved InetSocketAddress
        MockServerHttpRequest unresolvedReq = MockServerHttpRequest.post("/api/v1/auth/login")
                .remoteAddress(InetSocketAddress.createUnresolved("198.51.100.10", 80))
                .build();
        assertEquals("198.51.100.10", filter.resolveClientIp(unresolvedReq));

        // IPv6 con corchetes y puerto
        MockServerHttpRequest ipv6Req = MockServerHttpRequest.post("/api/v1/auth/login")
                .remoteAddress(new InetSocketAddress("0:0:0:0:0:0:0:1", 443))
                .header(TrustedForwardedForFilter.X_FORWARDED_FOR_HEADER, "[2001:db8::1]:8443")
                .build();
        assertEquals("2001:db8::1", filter.resolveClientIp(ipv6Req));

        // Coincidencia y no coincidencia de CIDR no alineado a byte + mismatch IPv4/IPv6
        assertTrue(filter.isTrustedProxy("172.31.255.254"));
        assertFalse(filter.isTrustedProxy("172.32.0.1"));
        assertFalse(filter.isTrustedProxy("2001:db8::2"));
        assertFalse(filter.isTrustedProxy(null));
        assertFalse(filter.isTrustedProxy("not-an-ip"));

        // Extracción directa de literales IP
        assertEquals("2001:db8::1", TrustedForwardedForFilter.extractIpLiteral("[2001:db8::1]"));
        assertEquals("fe80::1", TrustedForwardedForFilter.extractIpLiteral("fe80::1%eth0"));
        assertNull(TrustedForwardedForFilter.extractIpLiteral(null));
        assertNull(TrustedForwardedForFilter.extractIpLiteral(""));
        assertNull(TrustedForwardedForFilter.extractIpLiteral("a".repeat(65)));
        assertNull(TrustedForwardedForFilter.extractIpLiteral("[2001:db8::1"));
        assertNull(TrustedForwardedForFilter.extractIpLiteral("[2001:db8::1]:abc"));
        assertNull(TrustedForwardedForFilter.extractIpLiteral("203.0.113.45:abc"));
        assertNull(TrustedForwardedForFilter.extractIpLiteral("999.999.999.999"));
        assertNull(TrustedForwardedForFilter.extractIpLiteral("2001:db8:::1"));
    }
}
