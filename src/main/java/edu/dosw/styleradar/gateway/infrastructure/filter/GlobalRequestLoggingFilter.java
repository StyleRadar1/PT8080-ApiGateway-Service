package edu.dosw.styleradar.gateway.infrastructure.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
public class GlobalRequestLoggingFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GlobalRequestLoggingFilter.class);
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String CORRELATION_ID_ATTRIBUTE = "gateway.correlationId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startTime = System.currentTimeMillis();
        ServerHttpRequest request = exchange.getRequest();

        String incomingCorrelationId = request.getHeaders().getFirst(CORRELATION_ID_HEADER);
        String incomingRequestId = request.getHeaders().getFirst(REQUEST_ID_HEADER);

        String correlationId = firstNonBlank(incomingCorrelationId, incomingRequestId);
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }
        String requestId = firstNonBlank(incomingRequestId, correlationId);

        final String finalCorrelationId = correlationId;
        final String finalRequestId = requestId;

        ServerHttpRequest mutatedRequest = request.mutate()
                .headers(headers -> {
                    headers.set(CORRELATION_ID_HEADER, finalCorrelationId);
                    headers.set(REQUEST_ID_HEADER, finalRequestId);
                })
                .build();

        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(mutatedRequest)
                .build();
        mutatedExchange.getAttributes().put(CORRELATION_ID_ATTRIBUTE, finalCorrelationId);

        log.info("[Gateway] [{}] Peticion entrante: {} {}",
                finalCorrelationId, request.getMethod(), request.getURI().getPath());

        mutatedExchange.getResponse().beforeCommit(() -> {
            var headers = mutatedExchange.getResponse().getHeaders();
            if (!headers.containsKey(CORRELATION_ID_HEADER)) {
                headers.add(CORRELATION_ID_HEADER, finalCorrelationId);
            }
            if (!headers.containsKey(REQUEST_ID_HEADER)) {
                headers.add(REQUEST_ID_HEADER, finalRequestId);
            }
            return Mono.empty();
        });

        return chain.filter(mutatedExchange)
                .doFinally(signalType -> {
                    long duration = System.currentTimeMillis() - startTime;
                    var response = mutatedExchange.getResponse();
                    log.info("[Gateway] [{}] Peticion completada en {} ms con estado HTTP {}",
                            finalCorrelationId, duration, response.getStatusCode());
                });
    }

    public static String resolveOrCreateCorrelationId(ServerWebExchange exchange) {
        if (exchange == null) {
            return UUID.randomUUID().toString();
        }
        Object attr = exchange.getAttribute(CORRELATION_ID_ATTRIBUTE);
        if (attr instanceof String attrStr && !attrStr.isBlank()) {
            return attrStr.trim();
        }
        String headerCorrelation = exchange.getRequest().getHeaders().getFirst(CORRELATION_ID_HEADER);
        String headerRequest = exchange.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER);
        String resolved = firstNonBlank(headerCorrelation, headerRequest);
        if (resolved == null) {
            resolved = UUID.randomUUID().toString();
        }
        exchange.getAttributes().put(CORRELATION_ID_ATTRIBUTE, resolved);
        return resolved;
    }

    public static String resolveOrCreateRequestId(ServerWebExchange exchange, String fallbackCorrelationId) {
        if (exchange != null) {
            String headerRequest = exchange.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER);
            if (headerRequest != null && !headerRequest.isBlank()) {
                return headerRequest.trim();
            }
        }
        return (fallbackCorrelationId != null && !fallbackCorrelationId.isBlank())
                ? fallbackCorrelationId
                : UUID.randomUUID().toString();
    }

    private static String firstNonBlank(String primary, String secondary) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        if (secondary != null && !secondary.isBlank()) {
            return secondary.trim();
        }
        return null;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}

