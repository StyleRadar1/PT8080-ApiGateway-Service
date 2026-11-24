package edu.dosw.styleradar.gateway.infrastructure.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.NettyWriteResponseFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Filtro global que centraliza la política CORS en el perímetro del API Gateway (SR-RNF-03).
 * <p>
 * Tras la validación CORS perimetral ({@code spring.cloud.gateway.globalcors}):
 * <ul>
 *     <li>Remueve la cabecera {@code Origin} de la petición reenviada para evitar que los
 *     microservicios downstream rechacen orígenes legítimos con {@code 403 Invalid CORS request}.</li>
 *     <li>Conserva estrictamente los valores de {@code Access-Control-Allow-Origin},
 *     {@code Access-Control-Allow-Credentials} y {@code Access-Control-Expose-Headers}
 *     autorizados por el API Gateway para la petición, descartando cualquier cabecera CORS
 *     adicional o conflictiva emitida por el microservicio downstream.</li>
 *     <li>Preserva y deduplica los valores de {@code Vary} aportados por el microservicio
 *     downstream (p. ej. {@code Accept-Encoding}) junto con los de CORS.</li>
 * </ul>
 */
@Component
public class DownstreamCorsSanitizerFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerWebExchange targetExchange = exchange;
        if (exchange.getRequest().getHeaders().containsKey(HttpHeaders.ORIGIN)) {
            ServerHttpRequest sanitizedRequest = exchange.getRequest().mutate()
                    .headers(headers -> headers.remove(HttpHeaders.ORIGIN))
                    .build();
            targetExchange = exchange.mutate().request(sanitizedRequest).build();
        }

        final ServerWebExchange finalExchange = targetExchange;
        HttpHeaders initialResponseHeaders = finalExchange.getResponse().getHeaders();
        final List<String> gatewayAllowOrigin =
                captureHeaderValues(initialResponseHeaders, HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
        final List<String> gatewayAllowCredentials =
                captureHeaderValues(initialResponseHeaders, HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS);
        final List<String> gatewayExposeHeaders =
                captureHeaderValues(initialResponseHeaders, HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS);

        finalExchange.getResponse().beforeCommit(() -> {
            sanitizeCorsResponseHeaders(
                    finalExchange.getResponse().getHeaders(),
                    gatewayAllowOrigin,
                    gatewayAllowCredentials,
                    gatewayExposeHeaders
            );
            return Mono.empty();
        });
        return chain.filter(finalExchange);
    }

    static List<String> captureHeaderValues(HttpHeaders headers, String headerName) {
        if (headers == null) {
            return List.of();
        }
        List<String> values = headers.get(headerName);
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> captured = values.stream()
                .filter(value -> value != null && !value.isBlank())
                .toList();
        return captured.isEmpty() ? List.of() : captured;
    }

    static void sanitizeCorsResponseHeaders(
            HttpHeaders headers,
            List<String> gatewayAllowOrigin,
            List<String> gatewayAllowCredentials,
            List<String> gatewayExposeHeaders
    ) {
        if (headers == null) {
            return;
        }
        enforceGatewayHeader(headers, HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, gatewayAllowOrigin);
        enforceGatewayHeader(headers, HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, gatewayAllowCredentials);
        enforceGatewayHeader(headers, HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, gatewayExposeHeaders);
        dedupeListHeader(headers, HttpHeaders.VARY);
    }

    private static void enforceGatewayHeader(HttpHeaders headers, String headerName, List<String> gatewayValues) {
        if (gatewayValues == null || gatewayValues.isEmpty()) {
            headers.remove(headerName);
            return;
        }
        headers.put(headerName, new ArrayList<>(gatewayValues));
    }

    private static void dedupeListHeader(HttpHeaders headers, String headerName) {
        List<String> values = headers.get(headerName);
        if (values == null || values.size() <= 1) {
            return;
        }
        Map<String, String> unique = collectUniqueTokens(values);
        if (!unique.isEmpty()) {
            headers.put(headerName, new ArrayList<>(unique.values()));
        }
    }

    private static Map<String, String> collectUniqueTokens(List<String> values) {
        Map<String, String> unique = new LinkedHashMap<>();
        for (String raw : values) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            for (String part : raw.split(",")) {
                String token = part.trim();
                if (!token.isEmpty()) {
                    unique.putIfAbsent(token.toLowerCase(Locale.ROOT), token);
                }
            }
        }
        return unique;
    }

    @Override
    public int getOrder() {
        return NettyWriteResponseFilter.WRITE_RESPONSE_FILTER_ORDER + 1;
    }
}
