package edu.dosw.styleradar.gateway.infrastructure.adapter.in.rest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;

@WebFluxTest(controllers = FallbackController.class)
@ActiveProfiles("test")
class FallbackControllerTest {

    private static final List<HttpMethod> SUPPORTED_METHODS = List.of(
            HttpMethod.GET,
            HttpMethod.POST,
            HttpMethod.PUT,
            HttpMethod.PATCH,
            HttpMethod.DELETE
    );

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void fallbackProbador_retorna503ParaTodosLosMetodosHttp() {
        for (HttpMethod method : SUPPORTED_METHODS) {
            webTestClient.method(method)
                    .uri("/fallback/probador")
                    .exchange()
                    .expectStatus().is5xxServerError()
                    .expectHeader().valueEquals(org.springframework.http.HttpHeaders.RETRY_AFTER, "30")
                    .expectBody()
                    .jsonPath("$.status").isEqualTo(503)
                    .jsonPath("$.error").isEqualTo("Servicio No Disponible")
                    .jsonPath("$.service").isEqualTo("ms-probador-virtual");
        }
    }

    @Test
    void fallbackGeneral_retorna503ParaTodosLosMetodosHttp() {
        for (HttpMethod method : SUPPORTED_METHODS) {
            webTestClient.method(method)
                    .uri("/fallback/general")
                    .exchange()
                    .expectStatus().is5xxServerError()
                    .expectHeader().valueEquals(org.springframework.http.HttpHeaders.RETRY_AFTER, "30")
                    .expectBody()
                    .jsonPath("$.status").isEqualTo(503)
                    .jsonPath("$.error").isEqualTo("Microservicio No Disponible");
        }
    }
}

