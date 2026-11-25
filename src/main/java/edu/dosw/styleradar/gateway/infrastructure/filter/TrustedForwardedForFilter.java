package edu.dosw.styleradar.gateway.infrastructure.filter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Resuelve y sanea la cabecera {@code X-Forwarded-For} en el perímetro del API Gateway
 * garantizando que únicamente se confíe en cabeceras enviadas por proxies autorizados
 * ({@code styleradar.gateway.trusted-proxies}).
 * <p>
 * Si la conexión directa no proviene de un proxy confiable (o la lista está vacía:
 * fail-closed), descarta cualquier {@code X-Forwarded-For}, {@code X-Real-IP} o
 * {@code Forwarded} suministrado por el cliente y reenvía exclusivamente la IP de la
 * conexión TCP real para proteger el rate limiting de {@code ms-auth}.
 */
@Component
public class TrustedForwardedForFilter implements GlobalFilter, Ordered {

    public static final String X_FORWARDED_FOR_HEADER = "X-Forwarded-For";
    public static final String X_REAL_IP_HEADER = "X-Real-IP";
    public static final String FORWARDED_HEADER = "Forwarded";

    private static final java.util.regex.Pattern IPV4_PATTERN =
            java.util.regex.Pattern.compile("(\\d{1,3})(\\.\\d{1,3}){3}");
    private static final java.util.regex.Pattern IPV6_PATTERN =
            java.util.regex.Pattern.compile("[0-9a-fA-F:]+");

    private final List<CidrBlock> trustedProxyBlocks;

    public TrustedForwardedForFilter(
            @Value("${styleradar.gateway.trusted-proxies:}") List<String> trustedProxies
    ) {
        this.trustedProxyBlocks = parseTrustedProxies(trustedProxies);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String clientIp = resolveClientIp(request);

        ServerHttpRequest.Builder mutated = request.mutate();
        mutated.headers(headers -> {
            headers.remove(X_FORWARDED_FOR_HEADER);
            headers.remove(X_REAL_IP_HEADER);
            headers.remove(FORWARDED_HEADER);
            if (clientIp != null && !clientIp.isBlank()) {
                headers.set(X_FORWARDED_FOR_HEADER, clientIp);
            }
        });

        return chain.filter(exchange.mutate().request(mutated.build()).build());
    }

    String resolveClientIp(ServerHttpRequest request) {
        String remoteIp = extractRemoteIp(request.getRemoteAddress());
        if (remoteIp == null) {
            return null;
        }
        if (!isTrustedProxy(remoteIp)) {
            return remoteIp;
        }

        HttpHeaders headers = request.getHeaders();
        List<String> forwardedForValues = headers.get(X_FORWARDED_FOR_HEADER);
        if (forwardedForValues != null && !forwardedForValues.isEmpty()) {
            List<String> hops = collectCommaSeparatedHops(forwardedForValues);
            for (int i = hops.size() - 1; i >= 0; i--) {
                String hopIp = extractIpLiteral(hops.get(i));
                if (hopIp == null) {
                    break;
                }
                if (!isTrustedProxy(hopIp)) {
                    return hopIp;
                }
            }
        }

        String realIp = extractIpLiteral(headers.getFirst(X_REAL_IP_HEADER));
        if (realIp != null && !isTrustedProxy(realIp)) {
            return realIp;
        }
        return remoteIp;
    }

    boolean isTrustedProxy(String ipAddress) {
        if (trustedProxyBlocks.isEmpty() || ipAddress == null || !isIpLiteral(ipAddress)) {
            return false;
        }
        try {
            byte[] candidate = InetAddress.getByName(ipAddress).getAddress();
            for (CidrBlock block : trustedProxyBlocks) {
                if (block.matches(candidate)) {
                    return true;
                }
            }
        } catch (UnknownHostException ignored) {
            return false;
        }
        return false;
    }

    private static List<String> collectCommaSeparatedHops(List<String> headerValues) {
        List<String> hops = new ArrayList<>();
        for (String headerValue : headerValues) {
            if (headerValue == null || headerValue.isBlank()) {
                continue;
            }
            for (String part : headerValue.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    hops.add(trimmed);
                }
            }
        }
        return hops;
    }

    private static String extractRemoteIp(InetSocketAddress remoteAddress) {
        if (remoteAddress == null) {
            return null;
        }
        InetAddress address = remoteAddress.getAddress();
        if (address != null) {
            return extractIpLiteral(address.getHostAddress());
        }
        return extractIpLiteral(remoteAddress.getHostString());
    }

    static String extractIpLiteral(String rawValue) {
        if (rawValue == null) {
            return null;
        }
        String candidate = rawValue.trim();
        if (candidate.isEmpty() || candidate.length() > 64) {
            return null;
        }

        if (candidate.startsWith("[")) {
            int closingBracket = candidate.indexOf(']');
            if (closingBracket <= 1) {
                return null;
            }
            String portSuffix = candidate.substring(closingBracket + 1);
            if (!portSuffix.isEmpty() && !portSuffix.matches(":\\d{1,5}")) {
                return null;
            }
            candidate = candidate.substring(1, closingBracket);
        } else {
            int firstColon = candidate.indexOf(':');
            int lastColon = candidate.lastIndexOf(':');
            if (firstColon > 0 && firstColon == lastColon) {
                String maybePort = candidate.substring(firstColon + 1);
                if (!maybePort.matches("\\d{1,5}")) {
                    return null;
                }
                candidate = candidate.substring(0, firstColon);
            }
        }

        int zoneIndex = candidate.indexOf('%');
        if (zoneIndex > 0) {
            candidate = candidate.substring(0, zoneIndex);
        }
        return isIpLiteral(candidate) ? candidate : null;
    }

    private static boolean isIpLiteral(String value) {
        if (value == null || value.isEmpty() || value.length() > 45) {
            return false;
        }
        if (value.indexOf(':') >= 0) {
            if (!IPV6_PATTERN.matcher(value).matches()) {
                return false;
            }
        } else if (!IPV4_PATTERN.matcher(value).matches()) {
            return false;
        }
        try {
            InetAddress.getByName(value);
            return true;
        } catch (UnknownHostException ex) {
            return false;
        }
    }

    private static List<CidrBlock> parseTrustedProxies(List<String> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<CidrBlock> blocks = new ArrayList<>();
        for (String rawEntry : entries) {
            if (rawEntry == null || rawEntry.isBlank()) {
                continue;
            }
            for (String token : rawEntry.split(",")) {
                String trimmed = token.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                CidrBlock block = parseCidrBlock(trimmed);
                if (block != null) {
                    blocks.add(block);
                }
            }
        }
        return List.copyOf(blocks);
    }

    private static CidrBlock parseCidrBlock(String entry) {
        String[] parts = entry.split("/", 2);
        String ipPart = parts[0].trim();
        if (!isIpLiteral(ipPart)) {
            return null;
        }
        try {
            byte[] network = InetAddress.getByName(ipPart).getAddress();
            int maxBits = network.length * 8;
            int prefixBits = parts.length == 2 ? Integer.parseInt(parts[1].trim()) : maxBits;
            if (prefixBits < 0 || prefixBits > maxBits) {
                return null;
            }
            return new CidrBlock(network, prefixBits);
        } catch (UnknownHostException | NumberFormatException ex) {
            return null;
        }
    }

    private static final class CidrBlock {
        private final byte[] network;
        private final int prefixBits;

        private CidrBlock(byte[] network, int prefixBits) {
            this.network = network;
            this.prefixBits = prefixBits;
        }

        boolean matches(byte[] candidate) {
            if (candidate.length != network.length) {
                return false;
            }
            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != network[i]) {
                    return false;
                }
            }
            int remainingBits = prefixBits % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainingBits);
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }

    @Override
    public int getOrder() {
        return -200;
    }
}
