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

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startTime = System.currentTimeMillis();
        ServerHttpRequest request = exchange.getRequest();

        String correlationId = request.getHeaders().getFirst(CORRELATION_ID_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        ServerHttpRequest mutatedRequest = request.mutate()
                .header(CORRELATION_ID_HEADER, correlationId)
                .build();

        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(mutatedRequest)
                .build();

        final String finalCorrelationId = correlationId;
        log.info("[Gateway] [{}] Peticion entrante: {} {}",
                finalCorrelationId, request.getMethod(), request.getURI().getPath());

        mutatedExchange.getResponse().beforeCommit(() -> {
            var headers = mutatedExchange.getResponse().getHeaders();
            if (!headers.containsKey(CORRELATION_ID_HEADER)) {
                headers.add(CORRELATION_ID_HEADER, finalCorrelationId);
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

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}

