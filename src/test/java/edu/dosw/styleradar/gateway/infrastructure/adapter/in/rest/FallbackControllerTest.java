package edu.dosw.styleradar.gateway.infrastructure.adapter.in.rest;

import edu.dosw.styleradar.gateway.infrastructure.adapter.in.rest.FallbackController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

@WebFluxTest(controllers = FallbackController.class)
@ActiveProfiles("test")
class FallbackControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void fallbackProbador_retorna503YMensajeEsperado() {
        webTestClient.get()
                .uri("/fallback/probador")
                .exchange()
                .expectStatus().is5xxServerError()
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.error").isEqualTo("Servicio No Disponible")
                .jsonPath("$.service").isEqualTo("ms-probador-virtual");
    }

    @Test
    void fallbackGeneral_retorna503() {
        webTestClient.get()
                .uri("/fallback/general")
                .exchange()
                .expectStatus().is5xxServerError()
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.error").isEqualTo("Microservicio No Disponible");
    }
}

