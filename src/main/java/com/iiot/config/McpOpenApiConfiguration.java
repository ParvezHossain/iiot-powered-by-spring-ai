package com.iiot.config;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The SDK owns /mcp; document the actual transport without registering an MVC handler over it. */
@Configuration(proxyBeanMethods = false)
public class McpOpenApiConfiguration {
    @Bean
    OpenApiCustomizer mcpDocumentation(@Value("${mcp.enabled:false}") boolean enabled) {
        return api -> {
            var json = new MediaType().schema(new ObjectSchema())
                    .addExamples("initialize", example(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize",
                            "params", Map.of("protocolVersion", "2025-11-25", "capabilities", Map.of(),
                                    "clientInfo", Map.of("name", "swagger", "version", "1.0")))))
                    .addExamples("initialized", example(Map.of("jsonrpc", "2.0", "method", "notifications/initialized")))
                    .addExamples("listTools", example(Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/list")))
                    .addExamples("askQuestion", call("ragQuery", Map.of("question", "What does E204 mean?")))
                    .addExamples("machineStatus", call("getMachineStatus", Map.of("machineId", "replace-with-machine-uuid")))
                    .addExamples("recentAnomalies", call("getRecentAnomalies", Map.of("limit", 10)));
            var post = operation("mcpRequest", "MCP initialization, tool discovery and tool calls")
                    .description("ADMIN JWT required on every request. MCP_ENABLED is currently " + enabled + ". "
                            + "Enable MCP_ENABLED to activate this SDK transport; when disabled it returns 404. "
                            + "Initialize first, copy the Mcp-Session-Id response header into subsequent requests, "
                            + "then send notifications/initialized. Use tools/list to obtain argument schemas and tools/call to execute. "
                            + "Use an MCP client for streaming/session calls and send "
                            + "Accept: application/json, text/event-stream. Swagger documents the protocol examples. RAG questions additionally require RAG_ENABLED.")
                    .requestBody(new RequestBody().required(true).content(new Content().addMediaType("application/json", json)))
                    .addParametersItem(new HeaderParameter().schema(new StringSchema()).name("Mcp-Session-Id").description("Session returned by initialize; omit for initialize"))
                    .addParametersItem(new HeaderParameter().schema(new StringSchema()).name("MCP-Protocol-Version").example("2025-11-25"));
            api.path("/mcp", new PathItem().post(post)
                    .get(operation("mcpEvents", "Open MCP server event stream")
                            .addParametersItem(new HeaderParameter().schema(new StringSchema()).name("Mcp-Session-Id").required(true)))
                    .delete(operation("mcpClose", "Close an MCP session")
                            .addParametersItem(new HeaderParameter().schema(new StringSchema()).name("Mcp-Session-Id").required(true))));
        };
    }

    private static Operation operation(String id, String summary) {
        return new Operation().operationId(id).summary(summary).tags(List.of("MCP tool calling"))
                .security(List.of(new SecurityRequirement().addList("bearerAuth")))
                .responses(new ApiResponses()
                        .addApiResponse("200", new ApiResponse().description("JSON-RPC response or event stream")
                                .addHeaderObject("Mcp-Session-Id", new Header().schema(new StringSchema())
                                        .description("Returned by initialize; send on subsequent MCP requests"))
                                .content(new Content().addMediaType("application/json", new MediaType().schema(new ObjectSchema()))
                                        .addMediaType("text/event-stream", new MediaType())))
                        .addApiResponse("202", new ApiResponse().description("Notification accepted"))
                        .addApiResponse("400", new ApiResponse().description("Invalid protocol request"))
                        .addApiResponse("401", new ApiResponse().description("Missing, expired or invalid JWT"))
                        .addApiResponse("403", new ApiResponse().description("ADMIN role required or untrusted origin"))
                        .addApiResponse("404", new ApiResponse().description("MCP disabled or session unknown")));
    }

    private static Example example(Map<String, Object> value) { return new Example().value(value); }
    private static Example call(String name, Map<String, Object> arguments) {
        return example(Map.of("jsonrpc", "2.0", "id", 3, "method", "tools/call",
                "params", Map.of("name", name, "arguments", arguments)));
    }
}
