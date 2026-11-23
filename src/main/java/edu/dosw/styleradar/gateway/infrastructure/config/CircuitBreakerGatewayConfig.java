package edu.dosw.styleradar.gateway.infrastructure.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Configura el TimeLimiter global de los CircuitBreakers reactivos del Gateway
 * para evitar cortes prematuros (1s por defecto) en operaciones de Azure Blob Storage
 * o arranques en frío de microservicios como Try-On y Donaciones.
 */
@Configuration
public class CircuitBreakerGatewayConfig {

    @Bean
    public Customizer<ReactiveResilience4JCircuitBreakerFactory> defaultCircuitBreakerCustomizer(
            @Value("${styleradar.gateway.circuit-breaker.timeout-seconds:30}") long timeoutSeconds
    ) {
        Duration timeout = Duration.ofSeconds(Math.max(1L, timeoutSeconds));
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(CircuitBreakerConfig.ofDefaults())
                .timeLimiterConfig(TimeLimiterConfig.custom()
                        .timeoutDuration(timeout)
                        .build())
                .build());
    }
}
