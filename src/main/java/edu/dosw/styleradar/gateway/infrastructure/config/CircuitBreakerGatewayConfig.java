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
 * Configura los CircuitBreakers reactivos y TimeLimiters del Gateway para tolerancia a fallos,
 * aislamiento entre microservicios, soporte ante arranques en frío y detección de errores 5xx.
 */
@Configuration
public class CircuitBreakerGatewayConfig {

    @Bean
    public Customizer<ReactiveResilience4JCircuitBreakerFactory> defaultCircuitBreakerCustomizer(
            @Value("${styleradar.gateway.circuit-breaker.timeout-seconds:30}") long timeoutSeconds,
            @Value("${styleradar.gateway.circuit-breaker.sliding-window-size:10}") int slidingWindowSize,
            @Value("${styleradar.gateway.circuit-breaker.minimum-number-of-calls:5}") int minimumNumberOfCalls,
            @Value("${styleradar.gateway.circuit-breaker.failure-rate-threshold:50.0}") float failureRateThreshold,
            @Value("${styleradar.gateway.circuit-breaker.wait-duration-open-seconds:10}") long waitDurationOpenSeconds
    ) {
        Duration timeout = Duration.ofSeconds(Math.max(1L, timeoutSeconds));
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(CircuitBreakerConfig.custom()
                        .slidingWindowSize(Math.max(1, slidingWindowSize))
                        .minimumNumberOfCalls(Math.max(1, minimumNumberOfCalls))
                        .failureRateThreshold(Math.max(1.0f, failureRateThreshold))
                        .waitDurationInOpenState(Duration.ofSeconds(Math.max(1L, waitDurationOpenSeconds)))
                        .permittedNumberOfCallsInHalfOpenState(3)
                        .build())
                .timeLimiterConfig(TimeLimiterConfig.custom()
                        .timeoutDuration(timeout)
                        .build())
                .build());
    }
}

