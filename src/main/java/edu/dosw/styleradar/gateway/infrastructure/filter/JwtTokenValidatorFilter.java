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
    public static final String ALMACEN_ID_HEADER = "X-Almacen-Id";
    public static final String SSE_STREAM_PURPOSE = "sse-stream";

    public static final List<String> DEFAULT_PUBLIC_PATHS = List.of(
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            "/api/v1/health",
            "/api/v1/health/**",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/api/v1/auth/**",
            "/fallback/**",
            "/favicon.ico",
            "/webjars/**",
            "/ws",
            "/ws/**",
            "/ws-chat",
            "/ws-chat/**",
            "/api/v1/webhooks/**"
    );

    public static final List<String> DEFAULT_PUBLIC_GET_PATHS = List.of(
            "/api/v1/second-hand",
            "/api/v1/second-hand/",
            "/api/v1/second-hand/publications",
            "/api/v1/second-hand/publications/",
            "/api/v1/second-hand/publications/*",
            "/api/v1/second-hand/photos",
            "/api/v1/second-hand/photos/**",
            "/api/v1/stores",
            "/api/v1/stores/",
            "/api/v1/stores/*",
            "/api/v1/stores/*/catalog-settings",
            "/api/v1/stores/*/low-stock-threshold",
            "/api/v1/stores/*/new-arrival-days",
            "/api/v1/stores/*/photos",
            "/api/v1/stores/*/photos/*",
            "/api/v1/foundations",
            "/api/v1/foundations/",
            "/api/v1/prendas",
            "/api/v1/prendas/",
            "/api/v1/prendas/*",
            "/api/v1/prendas/*/similares",
            "/api/v1/catalogo/items/**",
            "/api/v1/almacenes/*/catalogo",
            "/api/v1/almacenes/*/catalogo/resumen",
            "/api/v1/almacenes/*/novedades",
            "/api/v1/almacenes",
            "/api/v1/almacenes/*/distancia",
            "/api/v1/fundaciones/cercanas",
            "/api/v1/playlists",
            "/api/v1/playlists/",
            "/api/v1/playlists/*",
            "/api/v1/playlists/destacadas",
            "/api/v1/playlists/populares",
            "/api/v1/inventario/prenda/*",
            "/api/v1/inventario/prenda/*/disponibilidad",
            "/api/v1/inventario/sedes/*/disponibles",
            "/api/v1/inventario/tallas/*/prendas"
    );

    private final SecretKey secretKey;
    private final SecretKey utf8FallbackKey;
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
        String trimmedSecret = rawSecret.trim();
        byte[] utf8Bytes = trimmedSecret.getBytes(StandardCharsets.UTF_8);
        byte[] keyBytes;
        try {
            byte[] decoded = Base64.getDecoder().decode(trimmedSecret);
            keyBytes = (decoded.length >= 32) ? decoded : utf8Bytes;
        } catch (IllegalArgumentException e) {
            keyBytes = utf8Bytes;
        }
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        this.utf8FallbackKey = (utf8Bytes.length >= 32 && !java.util.Arrays.equals(keyBytes, utf8Bytes))
                ? Keys.hmacShaKeyFor(utf8Bytes)
                : null;

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
            httpHeaders.remove(ALMACEN_ID_HEADER);
        });

        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        boolean isSseStreamRequest = isSseNotificationStreamRequest(request);
        boolean fromTicketQueryParam = (authHeader == null || !authHeader.startsWith("Bearer ")) && isSseStreamRequest;
        String effectiveAuthHeader = resolveEffectiveAuthHeader(request, authHeader);
        boolean isPublic = isPublicRequest(request);

        // 2. Si la ruta es protegida y no incluye Authorization Bearer (ni ticket SSE válido) -> Rechazar con 401 Unauthorized
        if (!isPublic) {
            if (effectiveAuthHeader == null || !effectiveAuthHeader.startsWith("Bearer ")) {
                log.warn("[Gateway] Petición rechazada (401): Acceso anónimo a ruta protegida {} {}",
                        request.getMethod(), path);
                return onError(exchange, "Acceso no autorizado: Se requiere token Bearer para acceder a esta ruta protegida", HttpStatus.UNAUTHORIZED);
            }
        }

        // 3. Si se incluye credencial Bearer o ticket SSE (en ruta pública o protegida), validar estrictamente
        if (effectiveAuthHeader != null && effectiveAuthHeader.startsWith("Bearer ")) {
            String token = effectiveAuthHeader.substring(7).trim();
            if (token.isEmpty()) {
                log.warn("[Gateway] Petición rechazada (401): Cabecera Authorization Bearer vacía hacia {}", path);
                return onError(exchange, "Token Bearer no proporcionado o vacío", HttpStatus.UNAUTHORIZED);
            }

            try {
                Claims claims = parseSignedClaims(token);
                String purpose = claims.get("purpose", String.class);
                boolean isSsePurpose = purpose != null && SSE_STREAM_PURPOSE.equalsIgnoreCase(purpose.trim());

                if (fromTicketQueryParam && !isSsePurpose) {
                    log.warn("[Gateway] Petición rechazada (401): Ticket SSE sin claim purpose={} hacia {}",
                            SSE_STREAM_PURPOSE, path);
                    return onError(exchange, "Ticket SSE inválido: se requiere claim purpose=sse-stream", HttpStatus.UNAUTHORIZED);
                }
                if (isSsePurpose && !isSseStreamRequest) {
                    log.warn("[Gateway] Petición rechazada (401): Token SSE usado fuera del stream permitido hacia {}", path);
                    return onError(exchange, "Token con propósito sse-stream no está autorizado para acceder a esta ruta", HttpStatus.UNAUTHORIZED);
                }

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

                String mergedRoles = mergeRoles(role, rolesStr);
                if (role == null && rolesStr != null) {
                    role = rolesStr;
                }
                if (mergedRoles != null && !mergedRoles.isBlank()) {
                    rolesStr = mergedRoles;
                }

                if (isAdminPaymentsPath(path) && !hasAdminAccess(role, rolesStr)) {
                    log.warn("[Gateway] Petición rechazada (403): Se requiere rol ADMIN_STYLERADAR hacia {}", path);
                    return onError(
                            exchange,
                            "Acceso denegado: Se requiere rol ADMIN_STYLERADAR para consultar pagos administrativos",
                            HttpStatus.FORBIDDEN
                    );
                }

                if (userId != null && !userId.isBlank()) {
                    requestBuilder.header(USER_ID_HEADER, userId);
                }
                // Homologacion de roles: Pagos recibe todos los roles + ADMIN_STYLERADAR en X-User-Role;
                // otros servicios conservan normalizeDownstreamRole (Catalogo espera ADMIN).
                String downstreamRole = isPaymentsServicePath(path)
                        ? buildPaymentsRoleHeader(role, rolesStr)
                        : normalizeDownstreamRole(role, rolesStr);
                String downstreamRoles = normalizeDownstreamRoles(rolesStr);
                if (downstreamRole != null && !downstreamRole.isBlank()) {
                    requestBuilder.header(USER_ROLE_HEADER, downstreamRole);
                }
                if (downstreamRoles != null && !downstreamRoles.isBlank()) {
                    requestBuilder.header(USER_ROLES_HEADER, downstreamRoles);
                }
                // X-Almacen-Id solo se deriva de claims firmados del JWT, nunca del cliente
                String almacenId = extractAlmacenId(claims);
                if (almacenId != null) {
                    requestBuilder.header(ALMACEN_ID_HEADER, almacenId);
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

    private String resolveEffectiveAuthHeader(ServerHttpRequest request, String authHeader) {
        if (authHeader != null && !authHeader.isBlank()) {
            return authHeader;
        }
        if (isSseNotificationStreamRequest(request)) {
            String ticket = request.getQueryParams().getFirst("ticket");
            if (ticket != null && !ticket.isBlank()) {
                return "Bearer " + ticket.trim();
            }
        }
        return authHeader;
    }

    private boolean isSseNotificationStreamRequest(ServerHttpRequest request) {
        if (request == null || !HttpMethod.GET.equals(request.getMethod())) {
            return false;
        }
        String path = request.getURI().getPath();
        if (path == null) {
            return false;
        }
        return pathMatcher.match("/api/v1/users/*/notifications/stream", path);
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

    private Claims parseSignedClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (io.jsonwebtoken.security.SecurityException ex) {
            if (utf8FallbackKey != null) {
                return Jwts.parser()
                        .verifyWith(utf8FallbackKey)
                        .build()
                        .parseSignedClaims(token)
                        .getPayload();
            }
            throw ex;
        }
    }

    public boolean isPublicPath(String path) {
        if (path == null) {
            return false;
        }
        if (pathMatcher.match("/api/v1/auth/logout", path)
                || pathMatcher.match("/api/v1/auth/logout/**", path)) {
            return false;
        }
        return publicPaths.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    public boolean isPublicGetPath(String path) {
        if (path == null) {
            return false;
        }
        // Exclusión de seguridad estricta: consultas privadas, moderación pendiente y conversaciones nunca son anónimas
        if (pathMatcher.match("/api/v1/second-hand/publications/users/**", path)
                || pathMatcher.match("/api/v1/second-hand/publications/user/**", path)
                || pathMatcher.match("/api/v1/second-hand/user/**", path)
                || pathMatcher.match("/api/v1/donations/**", path)
                || pathMatcher.match("/api/v1/prendas/pendientes", path)
                || pathMatcher.match("/api/v1/prendas/pendientes/**", path)
                || pathMatcher.match("/api/v1/playlists/me", path)
                || pathMatcher.match("/api/v1/playlists/me/**", path)
                || pathMatcher.match("/**/conversations", path)
                || pathMatcher.match("/**/conversations/**", path)) {
            return false;
        }
        return publicGetPaths.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    private static boolean isStyleRadarAdmin(String roleToken) {
        if (roleToken == null) {
            return false;
        }
        String r = roleToken.trim().toUpperCase();
        return r.equals("ADMIN_STYLERADAR")
                || r.equals("ROLE_ADMIN_STYLERADAR")
                || r.equals("STYLERADAR_ADMIN")
                || r.equals("ROLE_STYLERADAR_ADMIN");
    }

    /** Auth emite ADMIN_STYLERADAR; Catalogo espera ADMIN en X-User-Role. Los demas roles se conservan intactos. */
    static String normalizeDownstreamRole(String role, String rolesStr) {
        if (isStyleRadarAdmin(role)) {
            return "ADMIN";
        }
        if (rolesStr != null) {
            for (String token : rolesStr.split("[,\\s]+")) {
                if (isStyleRadarAdmin(token)) {
                    return "ADMIN";
                }
            }
        }
        return role;
    }

    /** Conserva los roles originales y agrega el alias ADMIN cuando existe ADMIN_STYLERADAR. */
    static String normalizeDownstreamRoles(String rolesStr) {
        if (rolesStr == null || rolesStr.isBlank()) {
            return rolesStr;
        }
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        boolean admin = false;
        for (String token : rolesStr.split("[,\\s]+")) {
            if (token.isBlank()) {
                continue;
            }
            out.add(token.trim());
            admin |= isStyleRadarAdmin(token);
        }
        if (admin) {
            out.add("ADMIN");
        }
        return String.join(",", out);
    }

    private boolean isAdminPaymentsPath(String path) {
        if (path == null) {
            return false;
        }
        return pathMatcher.match("/api/v1/admin/payments", path)
                || pathMatcher.match("/api/v1/admin/payments/**", path);
    }

    private boolean isPaymentsServicePath(String path) {
        if (path == null) {
            return false;
        }
        return isAdminPaymentsPath(path)
                || pathMatcher.match("/api/v1/subscriptions", path)
                || pathMatcher.match("/api/v1/subscriptions/**", path)
                || pathMatcher.match("/api/v1/payments", path)
                || pathMatcher.match("/api/v1/payments/**", path)
                || pathMatcher.match("/api/v1/plans", path)
                || pathMatcher.match("/api/v1/plans/**", path);
    }

    private static java.util.LinkedHashSet<String> collectRoleTokens(String role, String rolesStr) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        addRoleTokens(out, role);
        addRoleTokens(out, rolesStr);
        return out;
    }

    private static void addRoleTokens(java.util.Set<String> target, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (String token : raw.split("[,\\s]+")) {
            if (!token.isBlank()) {
                target.add(token.trim());
            }
        }
    }

    private static String mergeRoles(String role, String rolesStr) {
        java.util.LinkedHashSet<String> out = collectRoleTokens(role, rolesStr);
        return out.isEmpty() ? null : String.join(",", out);
    }

    static boolean hasAdminAccess(String role, String rolesStr) {
        for (String token : collectRoleTokens(role, rolesStr)) {
            if (isStyleRadarAdmin(token)
                    || "ADMIN".equalsIgnoreCase(token)
                    || "ROLE_ADMIN".equalsIgnoreCase(token)) {
                return true;
            }
        }
        return false;
    }

    static String buildPaymentsRoleHeader(String role, String rolesStr) {
        java.util.LinkedHashSet<String> out = collectRoleTokens(role, rolesStr);
        if (out.stream().anyMatch(JwtTokenValidatorFilter::isStyleRadarAdmin)) {
            out.add("ADMIN_STYLERADAR");
        }
        return out.isEmpty() ? null : String.join(",", out);
    }
    static String extractAlmacenId(Claims claims) {
        for (String key : new String[]{"almacenId", "almacen_id", "storeId", "store_id"}) {
            Object raw = claims.get(key);
            if (raw != null && raw.toString().matches("[A-Za-z0-9_-]{1,64}")) {
                return raw.toString();
            }
        }
        return null;
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
