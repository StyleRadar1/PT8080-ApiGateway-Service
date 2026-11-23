package edu.dosw.styleradar.gateway.infrastructure.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.NettyWriteResponseFilter;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyResponseBodyGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OpenApiServersRewriteFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("isAggregatedOpenApiDocsPath identifica solo subrutas OpenAPI de microservicios")
    void isAggregatedOpenApiDocsPath_discriminaCorrectamente() {
        assertTrue(OpenApiServersRewriteFilter.isAggregatedOpenApiDocsPath("/v3/api-docs/usuarios"));
        assertTrue(OpenApiServersRewriteFilter.isAggregatedOpenApiDocsPath("/v3/api-docs/probador"));
        assertTrue(OpenApiServersRewriteFilter.isAggregatedOpenApiDocsPath("/v3/api-docs/auth"));

        assertFalse(OpenApiServersRewriteFilter.isAggregatedOpenApiDocsPath(null));
        assertFalse(OpenApiServersRewriteFilter.isAggregatedOpenApiDocsPath("/v3/api-docs"));
        assertFalse(OpenApiServersRewriteFilter.isAggregatedOpenApiDocsPath("/v3/api-docs/"));
        assertFalse(OpenApiServersRewriteFilter.isAggregatedOpenApiDocsPath("/v3/api-docs/   "));
        assertFalse(OpenApiServersRewriteFilter.isAggregatedOpenApiDocsPath("/v3/api-docs/swagger-config"));
        assertFalse(OpenApiServersRewriteFilter.isAggregatedOpenApiDocsPath("/api/v1/usuarios"));
    }

    @Test
    @DisplayName("rewriteOpenApiServers antepone '/' en servers manteniendo servidores previos y paths")
    void rewriteOpenApiServers_anteponeSlashYPreservaEspecificacion() throws Exception {
        ModifyResponseBodyGatewayFilterFactory factory = mock(ModifyResponseBodyGatewayFilterFactory.class);
        OpenApiServersRewriteFilter filter = new OpenApiServersRewriteFilter(factory, objectMapper);

        String originalJson = """
                {
                  "openapi": "3.0.1",
                  "servers": [
                    { "url": "http://localhost:8120", "description": "Local ms-usuarios" }
                  ],
                  "paths": {
                    "/api/v1/usuarios": {
                      "post": { "summary": "Registrar usuario" }
                    }
                  }
                }
                """;

        String rewritten = filter.rewriteOpenApiServers(originalJson);
        JsonNode root = objectMapper.readTree(rewritten);

        assertEquals("/", root.path("servers").get(0).path("url").asText());
        assertEquals("API Gateway Actual", root.path("servers").get(0).path("description").asText());
        assertEquals("http://localhost:8120", root.path("servers").get(1).path("url").asText());
        assertEquals(2, root.path("servers").size());
        assertEquals("Registrar usuario", root.path("paths").path("/api/v1/usuarios").path("post").path("summary").asText());
    }

    @Test
    @DisplayName("rewriteOpenApiServers evita duplicar '/' y agrega servers cuando no existe")
    void rewriteOpenApiServers_sinDuplicarSlashYSinServersPrevios() throws Exception {
        ModifyResponseBodyGatewayFilterFactory factory = mock(ModifyResponseBodyGatewayFilterFactory.class);
        OpenApiServersRewriteFilter filter = new OpenApiServersRewriteFilter(factory, objectMapper);

        String withSlashAndPaths = """
                {
                  "paths": { "/api/v1/try-on/quotas": {} },
                  "servers": [
                    { "url": "/" },
                    { "url": "https://example.azurewebsites.net" }
                  ]
                }
                """;
        JsonNode root1 = objectMapper.readTree(filter.rewriteOpenApiServers(withSlashAndPaths));
        assertEquals(2, root1.path("servers").size());
        assertEquals("/", root1.path("servers").get(0).path("url").asText());
        assertEquals("https://example.azurewebsites.net", root1.path("servers").get(1).path("url").asText());

        String withoutServers = """
                { "openapi": "3.0.1", "paths": {} }
                """;
        JsonNode root2 = objectMapper.readTree(filter.rewriteOpenApiServers(withoutServers));
        assertEquals(1, root2.path("servers").size());
        assertEquals("/", root2.path("servers").get(0).path("url").asText());
    }

    @Test
    @DisplayName("rewriteOpenApiServers conserva sin cambios cuerpos vacíos, no-OpenAPI o texto inválido")
    void rewriteOpenApiServers_conservaCuerposNoOpenApi() {
        ModifyResponseBodyGatewayFilterFactory factory = mock(ModifyResponseBodyGatewayFilterFactory.class);
        OpenApiServersRewriteFilter filter = new OpenApiServersRewriteFilter(factory, objectMapper);

        assertNull(filter.rewriteOpenApiServers(null));
        assertEquals("", filter.rewriteOpenApiServers(""));
        assertEquals("   ", filter.rewriteOpenApiServers("   "));
        String nonOpenApiJson = "{\"status\":\"UP\"}";
        assertEquals(nonOpenApiJson, filter.rewriteOpenApiServers(nonOpenApiJson));
        assertEquals("[1,2,3]", filter.rewriteOpenApiServers("[1,2,3]"));
        assertEquals("not-json", filter.rewriteOpenApiServers("not-json"));
    }

    @Test
    @DisplayName("filter delega directo en rutas no-OpenAPI y remueve Accept-Encoding en GET /v3/api-docs/usuarios")
    void filter_rutasNormalesYDocsAgregados() {
        ModifyResponseBodyGatewayFilterFactory factory = mock(ModifyResponseBodyGatewayFilterFactory.class);
        AtomicReference<ModifyResponseBodyGatewayFilterFactory.Config> capturedConfig = new AtomicReference<>();
        GatewayFilter delegate = mock(GatewayFilter.class);
        when(delegate.filter(any(), any())).thenReturn(Mono.empty());
        when(factory.apply(any(ModifyResponseBodyGatewayFilterFactory.Config.class))).thenAnswer(inv -> {
            capturedConfig.set(inv.getArgument(0));
            return delegate;
        });

        OpenApiServersRewriteFilter filter = new OpenApiServersRewriteFilter(factory, objectMapper);
        assertEquals(NettyWriteResponseFilter.WRITE_RESPONSE_FILTER_ORDER - 1, filter.getOrder());

        // 1. POST o ruta normal -> pasa directo sin invocar ModifyResponseBody
        MockServerWebExchange normalExchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v3/api-docs/usuarios")
                        .header(HttpHeaders.ACCEPT_ENCODING, "gzip")
                        .build()
        );
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(normalExchange, chain)).verifyComplete();
        verify(chain).filter(normalExchange);
        verifyNoInteractions(factory);

        // 2. GET /v3/api-docs/usuarios -> remueve Accept-Encoding y configura RewriteFunction
        MockServerWebExchange docsExchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v3/api-docs/usuarios")
                        .header(HttpHeaders.ACCEPT_ENCODING, "gzip, br")
                        .build()
        );
        StepVerifier.create(filter.filter(docsExchange, chain)).verifyComplete();

        verify(delegate).filter(argThat(ex -> !ex.getRequest().getHeaders().containsKey(HttpHeaders.ACCEPT_ENCODING)), eq(chain));
        assertNotNull(capturedConfig.get());

        // Verificar ramas de RewriteFunction (null body, error status 500, 200 OK JSON)
        @SuppressWarnings("unchecked")
        org.springframework.cloud.gateway.filter.factory.rewrite.RewriteFunction<String, String> rewriteFn =
                (org.springframework.cloud.gateway.filter.factory.rewrite.RewriteFunction<String, String>)
                        capturedConfig.get().getRewriteFunction();
        MockServerWebExchange okExchange = MockServerWebExchange.from(MockServerHttpRequest.get("/v3/api-docs/usuarios").build());
        okExchange.getResponse().setStatusCode(HttpStatus.OK);
        StepVerifier.create(rewriteFn.apply(okExchange, null)).verifyComplete();
        StepVerifier.create(rewriteFn.apply(okExchange, "{\"openapi\":\"3.0.1\",\"servers\":[{\"url\":\"http://localhost:8120\"}]}"))
                .assertNext(res -> assertTrue(res.contains("\"url\":\"/\"")))
                .verifyComplete();

        MockServerWebExchange errExchange = MockServerWebExchange.from(MockServerHttpRequest.get("/v3/api-docs/usuarios").build());
        errExchange.getResponse().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
        StepVerifier.create(rewriteFn.apply(errExchange, "{\"error\":\"boom\"}"))
                .expectNext("{\"error\":\"boom\"}")
                .verifyComplete();
    }
}
