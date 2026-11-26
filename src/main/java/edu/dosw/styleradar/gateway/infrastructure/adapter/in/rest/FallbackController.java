package edu.dosw.styleradar.gateway.infrastructure.adapter.in.rest;

import edu.dosw.styleradar.gateway.infrastructure.filter.GlobalRequestLoggingFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/fallback")
public class FallbackController {

    public static final String RETRY_AFTER_SECONDS = "30";

    @GetMapping("/probador")
    public Mono<ResponseEntity<Map<String, Object>>> fallbackProbador(ServerWebExchange exchange) {
        return buildProbadorFallback(exchange);
    }

    @RequestMapping(
            value = "/probador",
            method = {RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE}
    )
    public Mono<ResponseEntity<Map<String, Object>>> fallbackProbadorMutation(ServerWebExchange exchange) {
        return buildProbadorFallback(exchange);
    }

    @GetMapping("/general")
    public Mono<ResponseEntity<Map<String, Object>>> fallbackGeneral(ServerWebExchange exchange) {
        return buildGeneralFallback(exchange);
    }

    @RequestMapping(
            value = "/general",
            method = {RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE}
    )
    public Mono<ResponseEntity<Map<String, Object>>> fallbackGeneralMutation(ServerWebExchange exchange) {
        return buildGeneralFallback(exchange);
    }

    private Mono<ResponseEntity<Map<String, Object>>> buildProbadorFallback(ServerWebExchange exchange) {
        String correlationId = GlobalRequestLoggingFilter.resolveOrCreateCorrelationId(exchange);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", HttpStatus.SERVICE_UNAVAILABLE.value());
        body.put("error", "Servicio No Disponible");
        body.put("message", "El microservicio de Probador Virtual (ms-probador-virtual :8500) no se encuentra disponible temporalmente. Intente mas tarde.");
        body.put("timestamp", Instant.now().toString());
        body.put("service", "ms-probador-virtual");
        body.put("correlationId", correlationId);
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(body));
    }

    private Mono<ResponseEntity<Map<String, Object>>> buildGeneralFallback(ServerWebExchange exchange) {
        String correlationId = GlobalRequestLoggingFilter.resolveOrCreateCorrelationId(exchange);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", HttpStatus.SERVICE_UNAVAILABLE.value());
        body.put("error", "Microservicio No Disponible");
        body.put("message", "El microservicio destino no responde temporalmente. El API Gateway ha activado el circuito de contingencia.");
        body.put("timestamp", Instant.now().toString());
        body.put("correlationId", correlationId);
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(body));
    }
}

