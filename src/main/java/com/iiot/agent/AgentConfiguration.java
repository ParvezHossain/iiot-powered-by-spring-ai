package com.iiot.agent;

import java.util.stream.Stream;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import com.iiot.rag.RagAnswerProperties;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"agent.enabled", "rag.enabled"}, havingValue = "true")
@EnableConfigurationProperties(AgentProperties.class)
class AgentConfiguration {
    @Bean
    ChatModel agentChatModel(OllamaApi equipmentOllamaApi, AgentProperties properties) {
        return OllamaChatModel.builder().ollamaApi(equipmentOllamaApi)
                .options(OllamaChatOptions.builder().model(properties.model()).temperature(0.0)
                        .seed(42).numCtx(16384).numPredict(1000).build())
                .retryTemplate(new org.springframework.core.retry.RetryTemplate(
                        org.springframework.core.retry.RetryPolicy.withMaxRetries(0))).build();
    }

    @Bean
    AgentKnowledgeTools agentKnowledgeTools(VectorStore vectors, RagAnswerProperties properties, JdbcTemplate jdbc) {
        return new AgentKnowledgeTools(vectors, properties, jdbc);
    }

    @Bean
    ToolCallbackProvider agentToolCallbackProvider(
            @Qualifier("telemetryToolCallbackProvider") ToolCallbackProvider telemetry, AgentKnowledgeTools knowledge) {
        var retrieval = MethodToolCallbackProvider.builder().toolObjects(knowledge).build();
        var callbacks = Stream.concat(Stream.of(telemetry.getToolCallbacks()), Stream.of(retrieval.getToolCallbacks()))
                .toArray(org.springframework.ai.tool.ToolCallback[]::new);
        return () -> callbacks.clone();
    }
}
