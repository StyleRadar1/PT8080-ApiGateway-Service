package edu.dosw.styleradar.gateway.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiGatewayConfig {

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
                        new Server().url("http://localhost:8080").description("API Gateway Local (Puerto 8080)"),
                        new Server().url("https://styleradar-gateway-prod.azurewebsites.net").description("API Gateway Producción (Azure - Sprint 2/3)")
                ));
    }
}

