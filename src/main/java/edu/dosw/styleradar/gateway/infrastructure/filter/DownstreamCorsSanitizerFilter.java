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
 *     <li>Deduplica y sanea las cabeceras CORS en la respuesta antes de enviarlas al navegador,
 *     preservando el valor autoritativo emitido por el API Gateway.</li>
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
        finalExchange.getResponse().beforeCommit(() -> {
            sanitizeCorsResponseHeaders(finalExchange.getResponse().getHeaders());
            return Mono.empty();
        });
        return chain.filter(finalExchange);
    }

    static void sanitizeCorsResponseHeaders(HttpHeaders headers) {
        if (headers == null) {
            return;
        }
        retainFirstValue(headers, HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
        retainFirstValue(headers, HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS);
        dedupeCommaSeparatedHeader(headers, HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS);
        dedupeListHeader(headers, HttpHeaders.VARY);
    }

    private static void retainFirstValue(HttpHeaders headers, String headerName) {
        List<String> values = headers.get(headerName);
        if (values == null || values.size() <= 1) {
            return;
        }
        values.stream()
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .ifPresent(first -> headers.set(headerName, first));
    }

    private static void dedupeCommaSeparatedHeader(HttpHeaders headers, String headerName) {
        List<String> values = headers.get(headerName);
        if (values == null || values.size() <= 1) {
            return;
        }
        Map<String, String> unique = collectUniqueTokens(values);
        if (!unique.isEmpty()) {
            headers.set(headerName, String.join(", ", unique.values()));
        }
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
