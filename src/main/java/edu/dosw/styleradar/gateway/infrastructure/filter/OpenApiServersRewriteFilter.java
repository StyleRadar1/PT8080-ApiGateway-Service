package edu.dosw.styleradar.gateway.infrastructure.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.NettyWriteResponseFilter;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyResponseBodyGatewayFilterFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Reescribe el arreglo {@code servers} de las especificaciones OpenAPI obtenidas vía
 * {@code /v3/api-docs/{service}} para que Swagger UI apunte por defecto al origen del API Gateway ({@code "/"})
 * en lugar de los hosts internos (p. ej. {@code http://localhost:8120}).
 */
@Component
public class OpenApiServersRewriteFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(OpenApiServersRewriteFilter.class);
    private static final String AGGREGATED_DOCS_PREFIX = "/v3/api-docs/";
    private static final String SWAGGER_CONFIG_PATH = "/v3/api-docs/swagger-config";

    private final ModifyResponseBodyGatewayFilterFactory modifyResponseBodyFilterFactory;
    private final ObjectMapper objectMapper;

    public OpenApiServersRewriteFilter(
            ModifyResponseBodyGatewayFilterFactory modifyResponseBodyFilterFactory,
            ObjectMapper objectMapper
    ) {
        this.modifyResponseBodyFilterFactory = modifyResponseBodyFilterFactory;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (!HttpMethod.GET.equals(request.getMethod())
                || !isAggregatedOpenApiDocsPath(request.getURI().getPath())) {
            return chain.filter(exchange);
        }

        // Solicitar JSON sin compresión para que ModifyResponseBodyGatewayFilterFactory reescriba texto plano UTF-8
        ServerHttpRequest uncompressedRequest = request.mutate()
                .headers(headers -> headers.remove(HttpHeaders.ACCEPT_ENCODING))
                .build();
        ServerWebExchange sanitizedExchange = exchange.mutate().request(uncompressedRequest).build();

        ModifyResponseBodyGatewayFilterFactory.Config config = new ModifyResponseBodyGatewayFilterFactory.Config()
                .setRewriteFunction(String.class, String.class, (ex, body) -> {
                    HttpStatusCode status = ex.getResponse().getStatusCode();
                    if (body == null) {
                        return Mono.empty();
                    }
                    if (status != null && !status.is2xxSuccessful()) {
                        return Mono.just(body);
                    }
                    return Mono.just(rewriteOpenApiServers(body));
                });

        GatewayFilter rewriteFilter = modifyResponseBodyFilterFactory.apply(config);
        return rewriteFilter.filter(sanitizedExchange, chain);
    }

    public static boolean isAggregatedOpenApiDocsPath(String path) {
        if (path == null || !path.startsWith(AGGREGATED_DOCS_PREFIX)) {
            return false;
        }
        if (SWAGGER_CONFIG_PATH.equals(path)) {
            return false;
        }
        String suffix = path.substring(AGGREGATED_DOCS_PREFIX.length());
        return !suffix.isBlank();
    }

    public String rewriteOpenApiServers(String body) {
        if (body == null || body.isBlank()) {
            return body;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (!(root instanceof ObjectNode obj) || (!obj.has("openapi") && !obj.has("paths"))) {
                return body;
            }

            ArrayNode newServers = objectMapper.createArrayNode();
            ObjectNode gatewayServer = objectMapper.createObjectNode();
            gatewayServer.put("url", "/");
            gatewayServer.put("description", "API Gateway Actual");
            newServers.add(gatewayServer);

            JsonNode existingServers = obj.get("servers");
            if (existingServers instanceof ArrayNode existingArray) {
                for (JsonNode serverNode : existingArray) {
                    String existingUrl = serverNode.path("url").asText("").trim();
                    if (!"/".equals(existingUrl)) {
                        newServers.add(serverNode);
                    }
                }
            }
            obj.set("servers", newServers);
            return objectMapper.writeValueAsString(obj);
        } catch (Exception ex) {
            log.debug("[Gateway] No se reescribió servers OpenAPI (cuerpo no JSON/OpenAPI): {}", ex.getMessage());
            return body;
        }
    }

    @Override
    public int getOrder() {
        return NettyWriteResponseFilter.WRITE_RESPONSE_FILTER_ORDER - 1;
    }
}
