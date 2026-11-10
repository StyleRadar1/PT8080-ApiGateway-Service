package edu.dosw.styleradar.gateway.infrastructure.filter;

import edu.dosw.styleradar.gateway.infrastructure.filter.GlobalRequestLoggingFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.*;

class GlobalRequestLoggingFilterTest {

    private final GlobalRequestLoggingFilter filter = new GlobalRequestLoggingFilter();

    @Test
    void filter_agregaCorrelationIdCuandoNoExiste() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/probador/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        Mono<Void> result = filter.filter(exchange, ex -> {
            String correlationId = ex.getRequest().getHeaders().getFirst(GlobalRequestLoggingFilter.CORRELATION_ID_HEADER);
            assertNotNull(correlationId);
            assertFalse(correlationId.isBlank());
            return Mono.empty();
        });

        StepVerifier.create(result).verifyComplete();
    }

    @Test
    void filter_preservaCorrelationIdExistente() {
        String existingId = "req-12345-portal8080";
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/probador/test")
                .header(GlobalRequestLoggingFilter.CORRELATION_ID_HEADER, existingId)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        Mono<Void> result = filter.filter(exchange, ex -> {
            String correlationId = ex.getRequest().getHeaders().getFirst(GlobalRequestLoggingFilter.CORRELATION_ID_HEADER);
            assertEquals(existingId, correlationId);
            return Mono.empty();
        });

        StepVerifier.create(result).verifyComplete();
    }

    @Test
    void getOrder_retornaPrioridadMaxima() {
        assertEquals(Integer.MIN_VALUE, filter.getOrder());
    }
}

