package edu.dosw.styleradar.gateway.infrastructure.adapter.in.rest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/fallback")
public class FallbackController {

    @GetMapping("/probador")
    public Mono<ResponseEntity<Map<String, Object>>> fallbackProbador() {
        return buildProbadorFallback();
    }

    @RequestMapping(
            value = "/probador",
            method = {RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE}
    )
    public Mono<ResponseEntity<Map<String, Object>>> fallbackProbadorMutation() {
        return buildProbadorFallback();
    }

    @GetMapping("/general")
    public Mono<ResponseEntity<Map<String, Object>>> fallbackGeneral() {
        return buildGeneralFallback();
    }

    @RequestMapping(
            value = "/general",
            method = {RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE}
    )
    public Mono<ResponseEntity<Map<String, Object>>> fallbackGeneralMutation() {
        return buildGeneralFallback();
    }

    private Mono<ResponseEntity<Map<String, Object>>> buildProbadorFallback() {
        Map<String, Object> body = Map.of(
                "status", HttpStatus.SERVICE_UNAVAILABLE.value(),
                "error", "Servicio No Disponible",
                "message", "El microservicio de Probador Virtual (ms-probador-virtual :8500) no se encuentra disponible temporalmente. Intente mas tarde.",
                "timestamp", Instant.now().toString(),
                "service", "ms-probador-virtual"
        );
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body));
    }

    private Mono<ResponseEntity<Map<String, Object>>> buildGeneralFallback() {
        Map<String, Object> body = Map.of(
                "status", HttpStatus.SERVICE_UNAVAILABLE.value(),
                "error", "Microservicio No Disponible",
                "message", "El microservicio destino no responde temporalmente. El API Gateway ha activado el circuito de contingencia.",
                "timestamp", Instant.now().toString()
        );
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body));
    }
}

