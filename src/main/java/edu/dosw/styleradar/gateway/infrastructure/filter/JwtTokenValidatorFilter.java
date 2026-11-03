package edu.dosw.styleradar.gateway.infrastructure.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

@Component
public class JwtTokenValidatorFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenValidatorFilter.class);
    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_ROLE_HEADER = "X-User-Role";

    private final SecretKey secretKey;

    public JwtTokenValidatorFilter(@Value("${security.jwt.secret}") String secret) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();
            try {
                Claims claims = Jwts.parser()
                        .verifyWith(secretKey)
                        .build()
                        .parseSignedClaims(token)
                        .getPayload();

                String userId = claims.getSubject();
                String role = claims.get("role", String.class);

                ServerHttpRequest.Builder builder = request.mutate();
                if (userId != null) {
                    builder.header(USER_ID_HEADER, userId);
                }
                if (role != null) {
                    builder.header(USER_ROLE_HEADER, role);
                }

                log.debug("[Gateway] Token JWT validado para usuario: {} con rol: {}", userId, role);
                return chain.filter(exchange.mutate().request(builder.build()).build());
            } catch (Exception ex) {
                log.warn("[Gateway] Token JWT no valido o expirado: {}", ex.getMessage());
                // Se permite continuar; los microservicios downstream aplicarán sus propias reglas de seguridad
            }
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}

