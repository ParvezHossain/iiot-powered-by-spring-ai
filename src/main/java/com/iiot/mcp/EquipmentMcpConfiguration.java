package com.iiot.mcp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.iiot.rag.RagAnswerService;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "mcp.enabled", havingValue = "true")
public class EquipmentMcpConfiguration {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Bean
    FilterRegistrationBean<McpApiKeyFilter> equipmentMcpAuthentication(@Value("${mcp.api-key:}") String apiKey) {
        var registration = new FilterRegistrationBean<>(new McpApiKeyFilter(apiKey));
        registration.addUrlPatterns("/mcp", "/mcp/*");
        registration.setDispatcherTypes(java.util.EnumSet.allOf(jakarta.servlet.DispatcherType.class));
        registration.setAsyncSupported(true);
        registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    HttpServletStreamableServerTransportProvider equipmentMcpTransport() {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(McpJsonDefaults.getMapper()).mcpEndpoint("/mcp")
                .securityValidator(DefaultServerTransportSecurityValidator.builder()
                        .allowedHosts(List.of("localhost:*", "127.0.0.1:*", "[::1]:*"))
                        .allowedOrigins(List.of("http://localhost:*", "http://127.0.0.1:*", "http://[::1]:*"))
                        .build())
                .build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> equipmentMcpServlet(
            HttpServletStreamableServerTransportProvider equipmentMcpTransport, McpSyncServer equipmentMcpServer) {
        // Depend on the initialized server before the servlet starts accepting requests.
        var registration = new ServletRegistrationBean<>(equipmentMcpTransport, "/mcp");
        registration.setAsyncSupported(true);
        registration.setLoadOnStartup(1);
        return registration;
    }

    @Bean(destroyMethod = "close")
    McpSyncServer equipmentMcpServer(HttpServletStreamableServerTransportProvider equipmentMcpTransport,
            @Qualifier("telemetryToolCallbackProvider") ToolCallbackProvider telemetry,
            ObjectProvider<RagAnswerService> rag) {
        var specifications = new ArrayList<McpServerFeatures.SyncToolSpecification>();
        for (var callback : telemetry.getToolCallbacks()) {
            if (List.of("getMachineStatus", "getRecentAnomalies").contains(callback.getToolDefinition().name())) {
                specifications.add(specification(callback));
            }
        }
        var knowledge = MethodToolCallbackProvider.builder().toolObjects(new RagQueryTool(rag)).build();
        for (var callback : knowledge.getToolCallbacks()) {
            specifications.add(specification(callback));
        }
        return McpServer.sync(equipmentMcpTransport)
                .serverInfo("iiot-equipment", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .requestTimeout(Duration.ofMinutes(3))
                .tools(specifications).build();
    }

    private static McpServerFeatures.SyncToolSpecification specification(ToolCallback callback) {
        var definition = callback.getToolDefinition();
        var tool = McpSchema.Tool.builder(definition.name(), McpJsonDefaults.getMapper(), definition.inputSchema())
                .description(definition.description())
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(true).destructiveHint(false)
                        .idempotentHint(true).openWorldHint(false).build()).build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        String result = callback.call(JSON.writeValueAsString(request.arguments() == null ? Map.of() : request.arguments()));
                        return McpSchema.CallToolResult.builder().isError(false)
                                .content(List.of(new McpSchema.TextContent(result)))
                                .structuredContent(Map.of("result", JSON.readValue(result, Object.class))).build();
                    }
                    catch (RuntimeException exception) {
                        Throwable cause = exception;
                        while (cause.getCause() != null) {
                            cause = cause.getCause();
                        }
                        String message;
                        if (cause instanceof ResponseStatusException status && status.getStatusCode().is4xxClientError()) {
                            message = status.getReason();
                        }
                        else if (cause instanceof IllegalArgumentException || cause instanceof java.time.DateTimeException) {
                            message = "Invalid tool arguments. Use an existing machine UUID, valid ISO-8601 time bounds, "
                                    + "a limit from 1 to 1000, and a nonblank question of at most 2000 characters.";
                        }
                        else {
                            message = "Tool unavailable. For ragQuery, enable RAG_ENABLED and configure the embedding and chat models. "
                                    + "Otherwise check the service dependencies.";
                        }
                        return McpSchema.CallToolResult.builder().isError(true)
                                .content(List.of(new McpSchema.TextContent(message))).build();
                    }
                }).build();
    }

    public static final class RagQueryTool {
        private final ObjectProvider<RagAnswerService> rag;

        public RagQueryTool(ObjectProvider<RagAnswerService> rag) {
            this.rag = rag;
        }

        @Tool(description = "Answer an equipment manual or error-code question using retrieved documentation. "
                + "Returns a grounded answer, checked source quotes and citations, or insufficientEvidence. "
                + "Documentation is not live telemetry. Requires RAG_ENABLED and configured local models.")
        public RagAnswerService.Answer ragQuery(
                @ToolParam(description = "Equipment knowledge question, 1–2000 characters") String question) {
            if (question == null || question.isBlank() || question.length() > 2000) {
                throw new IllegalArgumentException("question must contain 1–2000 characters");
            }
            var service = rag.getIfAvailable();
            if (service == null) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "RAG is disabled");
            }
            return service.answer(question.strip());
        }
    }
}
