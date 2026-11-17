package edu.dosw.styleradar.gateway.infrastructure.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * Filtro perimetral reactivo de validación JWT y control de acceso (SR-RNF-08).
 * - Discrimina rutas públicas incondicionales (Swagger, Actuator, Auth) de rutas protegidas.
 * - Permite acceso anónimo a consultas públicas de publicaciones (/api/v1/second-hand, /api/v1/segunda-mano) bajo método GET.
 * - Mantiene protegidas con 401 Unauthorized las consultas privadas de donaciones (/api/v1/donations/**, /api/v1/donaciones/**),
 *   las consultas privadas de publicaciones por usuario (/api/v1/second-hand/user/**, /api/v1/segunda-mano/user/**)
 *   y las operaciones de creación, edición y donación (POST, PUT, PATCH, DELETE).
 * - Elimina cabeceras X-User-Id, X-User-Role y X-User-Roles entrantes del cliente para prevenir Spoofing.
 * - Valida la firma criptográfica HMAC-SHA256 de tokens Bearer JWT; rechaza con 401 si son inválidos o expirados.
 * - Inyecta X-User-Id, X-User-Role y X-User-Roles downstream ÚNICAMENTE a partir de claims autenticados en el token.
 */
@Component
public class JwtTokenValidatorFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenValidatorFilter.class);
    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_ROLE_HEADER = "X-User-Role";
    public static final String USER_ROLES_HEADER = "X-User-Roles";

    public static final List<String> DEFAULT_PUBLIC_PATHS = List.of(
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/api/v1/auth/**",
            "/fallback/**",
            "/favicon.ico",
            "/webjars/**"
    );

    public static final List<String> DEFAULT_PUBLIC_GET_PATHS = List.of(
            "/api/v1/second-hand",
            "/api/v1/second-hand/",
            "/api/v1/second-hand/*",
            "/api/v1/second-hand/publications",
            "/api/v1/second-hand/publications/",
            "/api/v1/second-hand/publications/*",
            "/api/v1/segunda-mano",
            "/api/v1/segunda-mano/",
            "/api/v1/segunda-mano/*",
            "/api/v1/segunda-mano/publicaciones",
            "/api/v1/segunda-mano/publicaciones/",
            "/api/v1/segunda-mano/publicaciones/*",
            "/api/v1/stores",
            "/api/v1/stores/",
            "/api/v1/stores/*",
            "/api/v1/tiendas",
            "/api/v1/tiendas/",
            "/api/v1/tiendas/*",
            "/api/v1/catalogo/**",
            "/api/v1/prendas/**",
            "/api/v1/catalog/**",
            "/api/v1/items/**"
    );

    private final SecretKey secretKey;
    private final List<String> publicPaths;
    private final List<String> publicGetPaths;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Autowired
    public JwtTokenValidatorFilter(
            @Value("${security.jwt.secret-base64:${security.jwt.secret:${JWT_SECRET_BASE64:${JWT_SECRET:}}}}") String rawSecret,
            @Value("${security.jwt.public-paths:}") List<String> customPublicPaths,
            @Value("${security.jwt.public-get-paths:}") List<String> customPublicGetPaths
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

        List<String> cleanedPublic = (customPublicPaths != null)
                ? customPublicPaths.stream().filter(p -> p != null && !p.isBlank()).toList()
                : List.of();
        this.publicPaths = !cleanedPublic.isEmpty() ? cleanedPublic : DEFAULT_PUBLIC_PATHS;

        List<String> cleanedGet = (customPublicGetPaths != null)
                ? customPublicGetPaths.stream().filter(p -> p != null && !p.isBlank()).toList()
                : List.of();
        this.publicGetPaths = !cleanedGet.isEmpty() ? cleanedGet : DEFAULT_PUBLIC_GET_PATHS;
    }

    public JwtTokenValidatorFilter(String rawSecret) {
        this(rawSecret, DEFAULT_PUBLIC_PATHS, DEFAULT_PUBLIC_GET_PATHS);
    }

    public JwtTokenValidatorFilter(String rawSecret, List<String> publicPaths) {
        this(rawSecret, publicPaths, DEFAULT_PUBLIC_GET_PATHS);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 1. Sanear cabeceras: eliminar cualquier X-User-Id, X-User-Role o X-User-Roles recibido del cliente (Prevenir Spoofing)
        ServerHttpRequest.Builder requestBuilder = request.mutate();
        requestBuilder.headers(httpHeaders -> {
            httpHeaders.remove(USER_ID_HEADER);
            httpHeaders.remove(USER_ROLE_HEADER);
            httpHeaders.remove(USER_ROLES_HEADER);
        });

        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        boolean isPublic = isPublicRequest(request);

        // 2. Si la ruta es protegida y no incluye Authorization Bearer -> Rechazar con 401 Unauthorized
        if (!isPublic) {
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                log.warn("[Gateway] Petición rechazada (401): Acceso anónimo a ruta protegida {} {}",
                        request.getMethod(), path);
                return onError(exchange, "Acceso no autorizado: Se requiere token Bearer para acceder a esta ruta protegida", HttpStatus.UNAUTHORIZED);
            }
        }

        // 3. Si se incluye credencial Bearer (en ruta pública o protegida), validar estrictamente
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();
            if (token.isEmpty()) {
                log.warn("[Gateway] Petición rechazada (401): Cabecera Authorization Bearer vacía hacia {}", path);
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
                Object rolesObj = claims.get("roles");
                String rolesStr = null;
                if (rolesObj instanceof Iterable<?> iterable) {
                    StringBuilder sb = new StringBuilder();
                    for (Object r : iterable) {
                        if (!sb.isEmpty()) sb.append(",");
                        sb.append(r);
                    }
                    rolesStr = sb.toString();
                } else if (rolesObj != null) {
                    rolesStr = rolesObj.toString();
                }

                if (role == null && rolesStr != null) {
                    role = rolesStr;
                }
                if (rolesStr == null && role != null) {
                    rolesStr = role;
                }

                if (userId != null && !userId.isBlank()) {
                    requestBuilder.header(USER_ID_HEADER, userId);
                }
                if (role != null && !role.isBlank()) {
                    requestBuilder.header(USER_ROLE_HEADER, role);
                }
                if (rolesStr != null && !rolesStr.isBlank()) {
                    requestBuilder.header(USER_ROLES_HEADER, rolesStr);
                }

                log.debug("[Gateway] Token JWT validado exitosamente. Usuario: {}, Rol: {}, Roles: {}", userId, role, rolesStr);
            } catch (Exception ex) {
                log.warn("[Gateway] Petición rechazada (401): Token JWT inválido o expirado ({}) hacia {}",
                        ex.getMessage(), path);
                return onError(exchange, "Token JWT inválido o expirado: " + ex.getMessage(), HttpStatus.UNAUTHORIZED);
            }
        }

        // 4. Continuar la cadena con la petición saneada/autenticada
        return chain.filter(exchange.mutate().request(requestBuilder.build()).build());
    }

    public boolean isPublicRequest(ServerHttpRequest request) {
        if (request == null) {
            return false;
        }
        String path = request.getURI().getPath();
        if (isPublicPath(path)) {
            return true;
        }
        HttpMethod method = request.getMethod();
        if (HttpMethod.OPTIONS.equals(method)) {
            return true;
        }
        if (HttpMethod.GET.equals(method) && isPublicGetPath(path)) {
            return true;
        }
        return false;
    }

    public boolean isPublicPath(String path) {
        if (path == null) {
            return false;
        }
        return publicPaths.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    public boolean isPublicGetPath(String path) {
        if (path == null) {
            return false;
        }
        // Exclusión de seguridad estricta: consultas privadas de donaciones y publicaciones por usuario nunca son anónimas
        if (pathMatcher.match("/api/v1/second-hand/publications/users/**", path)
                || pathMatcher.match("/api/v1/second-hand/publications/user/**", path)
                || pathMatcher.match("/api/v1/second-hand/user/**", path)
                || pathMatcher.match("/api/v1/segunda-mano/publicaciones/usuarios/**", path)
                || pathMatcher.match("/api/v1/segunda-mano/publicaciones/usuario/**", path)
                || pathMatcher.match("/api/v1/segunda-mano/user/**", path)
                || pathMatcher.match("/api/v1/donations/**", path)
                || pathMatcher.match("/api/v1/donaciones/**", path)) {
            return false;
        }
        return publicGetPaths.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
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
        return -100;
    }
}
