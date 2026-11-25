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
 * sin interferir con la negociación CORS ni con la renderización de Swagger UI.
 */
@Component
public class SecurityHeadersWebFilter implements WebFilter, Ordered {

    public static final String STRICT_TRANSPORT_SECURITY = "Strict-Transport-Security";
    public static final String X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";
    public static final String X_FRAME_OPTIONS = "X-Frame-Options";
    public static final String REFERRER_POLICY = "Referrer-Policy";
    public static final String PERMISSIONS_POLICY = "Permissions-Policy";

    private static final String HSTS_VALUE = "max-age=31536000; includeSubDomains";
    private static final String CONTENT_TYPE_OPTIONS_VALUE = "nosniff";
    private static final String FRAME_OPTIONS_VALUE = "DENY";
    private static final String REFERRER_POLICY_VALUE = "strict-origin-when-cross-origin";
    private static final String PERMISSIONS_POLICY_VALUE = "camera=(), microphone=(), geolocation=(self)";

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
        setIfAbsent(headers, STRICT_TRANSPORT_SECURITY, HSTS_VALUE);
        setIfAbsent(headers, X_CONTENT_TYPE_OPTIONS, CONTENT_TYPE_OPTIONS_VALUE);
        setIfAbsent(headers, X_FRAME_OPTIONS, FRAME_OPTIONS_VALUE);
        setIfAbsent(headers, REFERRER_POLICY, REFERRER_POLICY_VALUE);
        setIfAbsent(headers, PERMISSIONS_POLICY, PERMISSIONS_POLICY_VALUE);
        if (isAuthEndpoint(path)) {
            headers.set(HttpHeaders.CACHE_CONTROL, "no-store");
        }
    }

    private static void setIfAbsent(HttpHeaders headers, String name, String value) {
        if (!headers.containsKey(name)) {
            headers.set(name, value);
        }
    }

    private static boolean isAuthEndpoint(String path) {
        return path != null && (path.equals("/api/v1/auth") || path.startsWith("/api/v1/auth/"));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
