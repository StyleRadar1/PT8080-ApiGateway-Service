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
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Filtro perimetral reactivo de validación JWT y saneamiento de cabeceras de identidad (SR-RNF-08).
 * - Elimina cabeceras X-User-Id y X-User-Role entrantes del cliente para prevenir Spoofing / Header Injection.
 * - Valida la firma y vigencia de tokens Bearer JWT; rechaza con 401 Unauthorized si son inválidos o expirados.
 * - Inyecta X-User-Id y X-User-Role downstream ÚNICAMENTE a partir de claims autenticados en el token.
 * - Soporta claves secretas en Base64 mediante JWT_SECRET_BASE64 compatible con los microservicios existentes.
 */
@Component
public class JwtTokenValidatorFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenValidatorFilter.class);
    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_ROLE_HEADER = "X-User-Role";

    private final SecretKey secretKey;

    public JwtTokenValidatorFilter(
            @Value("${security.jwt.secret-base64:${security.jwt.secret:${JWT_SECRET_BASE64:${JWT_SECRET:}}}}") String rawSecret
    ) {
        if (rawSecret == null || rawSecret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET_BASE64 o security.jwt.secret es requerido para inicializar el API Gateway");
        }
        byte[] keyBytes;
        try {
            byte[] decoded = Base64.getDecoder().decode(rawSecret.trim());
            keyBytes = (decoded.length >= 32) ? decoded : rawSecret.getBytes(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            keyBytes = rawSecret.getBytes(StandardCharsets.UTF_8);
        }
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // 1. Sanear cabeceras: eliminar cualquier X-User-Id o X-User-Role recibido del cliente (Prevenir Spoofing)
        ServerHttpRequest.Builder requestBuilder = request.mutate();
        requestBuilder.headers(httpHeaders -> {
            httpHeaders.remove(USER_ID_HEADER);
            httpHeaders.remove(USER_ROLE_HEADER);
        });

        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        // 2. Si el cliente incluye credencial Bearer, validar estrictamente
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();
            if (token.isEmpty()) {
                log.warn("[Gateway] Petición rechazada (401): Cabecera Authorization Bearer vacía hacia {}", request.getURI().getPath());
                return onError(exchange, "Token Bearer no proporcionado o vacío", HttpStatus.UNAUTHORIZED);
            }

            try {
                Claims claims = Jwts.parser()
                        .verifyWith(secretKey)
                        .build()
                        .parseSignedClaims(token)
                        .getPayload();

                String userId = claims.getSubject();
                String role = claims.get("role", String.class);
                if (role == null && claims.get("roles") != null) {
                    role = claims.get("roles").toString();
                }

                if (userId != null && !userId.isBlank()) {
                    requestBuilder.header(USER_ID_HEADER, userId);
                }
                if (role != null && !role.isBlank()) {
                    requestBuilder.header(USER_ROLE_HEADER, role);
                }

                log.debug("[Gateway] Token JWT validado exitosamente. Usuario: {}, Rol: {}", userId, role);
            } catch (Exception ex) {
                log.warn("[Gateway] Petición rechazada (401): Token JWT inválido o expirado ({}) hacia {}",
                        ex.getMessage(), request.getURI().getPath());
                return onError(exchange, "Token JWT inválido o expirado: " + ex.getMessage(), HttpStatus.UNAUTHORIZED);
            }
        }

        // 3. Continuar la cadena con la petición saneada/autenticada
        return chain.filter(exchange.mutate().request(requestBuilder.build()).build());
    }

    private Mono<Void> onError(ServerWebExchange exchange, String message, HttpStatus status) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = String.format("{\"error\":\"%s\",\"message\":\"%s\",\"status\":%d}",
                status.getReasonPhrase(), message, status.value());
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
