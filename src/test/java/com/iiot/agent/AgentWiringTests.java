package com.iiot.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import com.iiot.rag.RagAnswerProperties;
import com.iiot.rag.RagAnswerService;
import com.iiot.telemetry.TelemetryQueryService;
import com.iiot.telemetry.TelemetryTools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentWiringTests {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(AgentConfiguration.class)
            .withBean("equipmentOllamaApi", OllamaApi.class, () -> OllamaApi.builder().build())
            .withBean("equipmentChatModel", ChatModel.class, () -> mock(ChatModel.class))
            .withBean(VectorStore.class, () -> mock(VectorStore.class))
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(RagAnswerProperties.class, () -> new RagAnswerProperties("test", 6, 0.45))
            .withBean("telemetryToolCallbackProvider", ToolCallbackProvider.class,
                    () -> MethodToolCallbackProvider.builder().toolObjects(new TelemetryTools(mock(TelemetryQueryService.class))).build());

    @Test
    void bothFlagsEnableFiveToolsAndDistinctModelsWithoutAmbiguousRagInjection() {
        context.withPropertyValues("rag.enabled=true", "agent.enabled=true")
                .withBean(RagAnswerService.class).withBean(AgentService.class).run(ctx -> {
                    assertThat(ctx).hasNotFailed().hasSingleBean(AgentService.class).hasSingleBean(RagAnswerService.class);
                    assertThat(ctx.getBean("agentToolCallbackProvider", ToolCallbackProvider.class).getToolCallbacks())
                            .extracting(c -> c.getToolDefinition().name()).containsExactlyInAnyOrder(
                                    "getMachineStatus", "getRecentAnomalies", "queryTelemetryRange", "resolveMachine", "retrieveEquipmentKnowledge");
                    assertThat(ctx.getBean("agentChatModel")).isNotSameAs(ctx.getBean("equipmentChatModel"));
                });
    }

    @Test
    void agentIsOptInAndRequiresRag() {
        for (String[] flags : new String[][]{{}, {"agent.enabled=true", "rag.enabled=false"},
                {"agent.enabled=false", "rag.enabled=true"}}) {
            context.withPropertyValues(flags).run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean("agentChatModel"));
        }
    }
}
