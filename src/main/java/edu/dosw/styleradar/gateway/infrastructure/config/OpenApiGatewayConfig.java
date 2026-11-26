package edu.dosw.styleradar.gateway.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.properties.AbstractSwaggerUiConfigProperties.SwaggerUrl;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Configuration
public class OpenApiGatewayConfig {

    public static final Map<String, String> AGGREGATED_OPENAPI_URLS = Map.ofEntries(
            Map.entry("API Gateway (:8080)", "/v3/api-docs"),
            Map.entry("ms-probador-virtual (:8500)", "/v3/api-docs/probador"),
            Map.entry("ms-segunda-vida-donaciones (:8520)", "/v3/api-docs/donaciones"),
            Map.entry("ms-auth (:8100)", "/v3/api-docs/auth"),
            Map.entry("ms-tiendas (:8140)", "/v3/api-docs/tiendas"),
            Map.entry("ms-notificaciones (:8300)", "/v3/api-docs/notificaciones"),
            Map.entry("ms-usuarios (:8120)", "/v3/api-docs/usuarios"),
            Map.entry("ms-inventario (:8200)", "/v3/api-docs/inventario"),
            Map.entry("ms-item-catalogo (:8220)", "/v3/api-docs/catalogo"),
            Map.entry("ms-geolocalizacion (:8240)", "/v3/api-docs/geolocalizacion"),
            Map.entry("ms-playlist (:8260)", "/v3/api-docs/playlist"),
            Map.entry("ms-chat (:8320)", "/v3/api-docs/chat"),
            Map.entry("ms-pagos-suscripciones (:8400)", "/v3/api-docs/pagos"),
            Map.entry("ms-estadisticas (:8420)", "/v3/api-docs/estadisticas")
    );

    @Bean
    public OpenAPI gatewayOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("STYLE RADAR · PT8080-ApiGateway-Service")
                        .description("API Gateway perimetral unificado de StyleRadar (Puerto 8080). " +
                                "Responsable del enrutamiento reactivo perimetral hacia los microservicios del ecosistema, " +
                                "políticas CORS globales, agregación de Swagger y tolerancia a fallos.")
                        .version("v1.0.0")
                        .contact(new Contact()
                                .name("Squad Portal8080 (PT8080) - DOSW 2026")
                                .url("https://github.com/StyleRadar1/PT8080-ApiGateway-Service"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))
                .servers(List.of(
                        new Server().url("/").description("Servidor Actual (QA, Producción o Local)"),
                        new Server().url("https://styleradar-gateway-qa.azurewebsites.net").description("API Gateway QA (Azure)"),
                        new Server().url("https://styleradar-gateway-prod.azurewebsites.net").description("API Gateway Producción (Azure)"),
                        new Server().url("http://localhost:8080").description("API Gateway Local (Puerto 8080)")
                ));
    }

    @Bean
    public SmartInitializingSingleton ensureCatalogSwaggerUiUrls(
            ObjectProvider<SwaggerUiConfigProperties> swaggerUiPropertiesProvider
    ) {
        return () -> swaggerUiPropertiesProvider.ifAvailable(props -> {
            Set<SwaggerUrl> urls = props.getUrls() != null
                    ? new LinkedHashSet<>(props.getUrls())
                    : new LinkedHashSet<>();
            AGGREGATED_OPENAPI_URLS.forEach((name, url) -> {
                boolean alreadyRegistered = urls.stream()
                        .anyMatch(existing -> url.equals(existing.getUrl()));
                if (!alreadyRegistered) {
                    urls.add(new SwaggerUrl(name, url, name));
                }
            });
            props.setUrls(urls);
        });
    }
}

