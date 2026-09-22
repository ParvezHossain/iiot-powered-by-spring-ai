package com.iiot.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {
    @Bean
    OpenAPI iiotOpenApi() {
        return new OpenAPI().info(new Info()
                .title("IIoT Powered by AI API")
                .version("0.0.1-SNAPSHOT")
                .description("Industrial telemetry, anomaly detection, equipment retrieval, and grounded AI chat. "
                        + "REST and health endpoints do not require authentication. RAG endpoints require rag.enabled=true; "
                        + "chat also requires agent.enabled=true. Disabled endpoints are absent from this document. "
                        + "MCP is a separate Streamable HTTP protocol at /mcp, enabled by mcp.enabled=true and protected "
                        + "by Authorization: Bearer <MCP_API_KEY>; use an MCP client for discovery and calls. "
                        + "POST /api/documents/ingest replaces the equipment corpus. Equipment data is synthetic.")
                .license(new License().name("MIT").url("https://opensource.org/licenses/MIT")));
    }
}
