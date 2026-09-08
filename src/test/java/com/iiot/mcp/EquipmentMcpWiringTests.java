package com.iiot.mcp;

import com.iiot.telemetry.TelemetryQueryService;
import com.iiot.telemetry.TelemetryTools;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.server.ResponseStatusException;
import com.iiot.rag.RagAnswerService;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class EquipmentMcpWiringTests {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(EquipmentMcpConfiguration.class)
            .withBean("telemetryToolCallbackProvider", ToolCallbackProvider.class,
                    () -> MethodToolCallbackProvider.builder()
                            .toolObjects(new TelemetryTools(mock(TelemetryQueryService.class))).build());

    @Test
    void serverIsOptInAndDoesNotRequireTheAgentOrRagToStart() {
        context.run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(McpSyncServer.class));
        context.withPropertyValues("mcp.enabled=true", "rag.enabled=false", "agent.enabled=false",
                        "mcp.api-key=mcp-test-only-key-0123456789abcdef0123456789")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(McpSyncServer.class)
                        .hasBean("equipmentMcpServlet"));
    }

    @Test
    void enabledServerFailsClosedWithoutAUsableKey() {
        for (String key : new String[]{"", " ", "short", "contains spaces even if sufficiently long"}) {
            context.withPropertyValues("mcp.enabled=true", "mcp.api-key=" + key).run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                        .hasStackTraceContaining("MCP_API_KEY must contain");
            });
        }
    }

    @Test
    void disabledRagReportsUnavailableInsteadOfInventingAnAnswer() {
        var tool = new EquipmentMcpConfiguration.RagQueryTool(new DefaultListableBeanFactory().getBeanProvider(RagAnswerService.class));
        assertThatThrownBy(() -> tool.ragQuery("What does E204 mean?"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
    }
}
