package com.iiot.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {
    @Bean
    org.springdoc.core.customizers.OpenApiCustomizer publicHealthDocumentation() {
        return api -> api.getPaths().forEach((path, item) -> {
            if (path.equals("/actuator/health") || path.startsWith("/actuator/health/")) {
                item.readOperations().forEach(operation -> operation.setSecurity(java.util.List.of()));
            }
        });
    }

    @Bean
    OpenAPI iiotOpenApi() {
        return new OpenAPI()
                .components(new io.swagger.v3.oas.models.Components().addSecuritySchemes("bearerAuth",
                        new io.swagger.v3.oas.models.security.SecurityScheme()
                                .type(io.swagger.v3.oas.models.security.SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new io.swagger.v3.oas.models.security.SecurityRequirement().addList("bearerAuth"))
                .info(new Info()
                        .title("IIoT Powered by AI API")
                        .version("0.0.1-SNAPSHOT")
                        .description("Industrial telemetry, anomaly detection, equipment retrieval, and grounded AI chat. "
                                + "REST requires a JWT bearer token; health, authentication and documentation are public. RAG endpoints require rag.enabled=true; "
                                + "chat also requires agent.enabled=true. AI REST routes remain documented and return 503 when disabled. "
                                + "MCP is a separate Streamable HTTP protocol at /mcp, enabled by mcp.enabled=true and protected "
                                + "by an ADMIN JWT. RAG, documents, chat and MCP tool calls require ROLE_ADMIN. "
                                + "POST /api/documents/ingest replaces the equipment corpus. Equipment data is synthetic.")
                        .license(new License().name("MIT").url("https://opensource.org/licenses/MIT")));
    }
}
