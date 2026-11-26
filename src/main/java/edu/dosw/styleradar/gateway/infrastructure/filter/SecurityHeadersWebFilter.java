package edu.dosw.styleradar.gateway.infrastructure.filter;

import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Filtro reactivo perimetral que inyecta cabeceras de seguridad HTTP estándar en las respuestas del API Gateway
 * garantizando la política perimetral (BUG-011) sin debilitamiento por cabeceras downstream y compatibilidad con Swagger UI.
 */
@Component
public class SecurityHeadersWebFilter implements WebFilter, Ordered {

    public static final String STRICT_TRANSPORT_SECURITY = "Strict-Transport-Security";
    public static final String X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";
    public static final String X_FRAME_OPTIONS = "X-Frame-Options";
    public static final String REFERRER_POLICY = "Referrer-Policy";
    public static final String PERMISSIONS_POLICY = "Permissions-Policy";
    public static final String CONTENT_SECURITY_POLICY = "Content-Security-Policy";

    private static final String HSTS_VALUE = "max-age=31536000; includeSubDomains";
    private static final String CONTENT_TYPE_OPTIONS_VALUE = "nosniff";
    private static final String FRAME_OPTIONS_DENY = "DENY";
    private static final String FRAME_OPTIONS_SAMEORIGIN = "SAMEORIGIN";
    private static final String REFERRER_POLICY_VALUE = "strict-origin-when-cross-origin";
    private static final String PERMISSIONS_POLICY_VALUE = "camera=(), microphone=(), geolocation=(self)";
    public static final String API_CSP_VALUE = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";
    public static final String SWAGGER_CSP_VALUE = "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; font-src 'self' data:; connect-src 'self' data: https:; frame-ancestors 'self'";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        exchange.getResponse().beforeCommit(() -> {
            applySecurityHeaders(exchange.getResponse().getHeaders(), path);
            return Mono.empty();
        });
        return chain.filter(exchange);
    }

    static void applySecurityHeaders(HttpHeaders headers, String path) {
        if (headers == null) {
            return;
        }
        // Las cabeceras perimetrales se fuerzan con headers.set(...) para evitar que
        // servicios downstream debiliten las directivas de seguridad del API Gateway.
        headers.set(STRICT_TRANSPORT_SECURITY, HSTS_VALUE);
        headers.set(X_CONTENT_TYPE_OPTIONS, CONTENT_TYPE_OPTIONS_VALUE);
        headers.set(REFERRER_POLICY, REFERRER_POLICY_VALUE);
        headers.set(PERMISSIONS_POLICY, PERMISSIONS_POLICY_VALUE);

        if (isSwaggerPath(path)) {
            headers.set(X_FRAME_OPTIONS, FRAME_OPTIONS_SAMEORIGIN);
            headers.set(CONTENT_SECURITY_POLICY, SWAGGER_CSP_VALUE);
        } else {
            headers.set(X_FRAME_OPTIONS, FRAME_OPTIONS_DENY);
            headers.set(CONTENT_SECURITY_POLICY, API_CSP_VALUE);
        }

        if (isAuthEndpoint(path)) {
            headers.set(HttpHeaders.CACHE_CONTROL, "no-store");
        }
    }

    private static boolean isSwaggerPath(String path) {
        if (path == null) {
            return false;
        }
        return path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")
                || path.equals("/swagger-ui.html")
                || path.startsWith("/webjars/");
    }

    private static boolean isAuthEndpoint(String path) {
        return path != null && (path.equals("/api/v1/auth") || path.startsWith("/api/v1/auth/"));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
