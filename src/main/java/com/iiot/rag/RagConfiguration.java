package com.iiot.rag;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "rag.enabled", havingValue = "true")
@EnableConfigurationProperties({RagProperties.class, RagAnswerProperties.class})
class RagConfiguration {
    @Bean
    OllamaApi equipmentOllamaApi(
            @Value("${ollama.base-url:http://localhost:11434}") String baseUrl) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofMinutes(2));
        return OllamaApi.builder().baseUrl(baseUrl)
                .restClientBuilder(RestClient.builder().requestFactory(factory)).build();
    }

    @Bean
    EmbeddingModel equipmentEmbeddingModel(OllamaApi equipmentOllamaApi) {
        var model = OllamaEmbeddingModel.builder().ollamaApi(equipmentOllamaApi)
                .options(OllamaEmbeddingOptions.builder().model(RagProperties.MODEL).truncate(false).build())
                .build();
        return new NomicEmbeddingModel(model);
    }

    @Bean
    ChatModel equipmentChatModel(OllamaApi equipmentOllamaApi, RagAnswerProperties properties) {
        var evidence = Map.of("type", "object", "additionalProperties", false,
                "required", List.of("sourceId", "quote"),
                "properties", Map.of("sourceId", Map.of("type", "string"), "quote", Map.of("type", "string")));
        var format = Map.of("type", "object", "additionalProperties", false,
                "required", List.of("insufficientEvidence", "answer", "citations"),
                "properties", Map.of("insufficientEvidence", Map.of("type", "boolean"),
                        "answer", Map.of("type", "string"),
                        "citations", Map.of("type", "array", "minItems", 1, "items", evidence)));
        return OllamaChatModel.builder().ollamaApi(equipmentOllamaApi)
                .options(OllamaChatOptions.builder().model(properties.model()).temperature(0.0)
                        .seed(42).numCtx(8192).numPredict(700).format(format).build())
                .retryTemplate(new org.springframework.core.retry.RetryTemplate(
                        org.springframework.core.retry.RetryPolicy.withMaxRetries(0)))
                .build();
    }

    @Bean
    PgVectorStore equipmentVectorStore(JdbcTemplate jdbc, EmbeddingModel equipmentEmbeddingModel) {
        return PgVectorStore.builder(jdbc, equipmentEmbeddingModel)
                .schemaName("public").vectorTableName("equipment_vectors")
                .dimensions(RagProperties.DIMENSIONS)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                // Exact search is appropriate for this small corpus and avoids approximate recall loss.
                .indexType(PgVectorStore.PgIndexType.NONE)
                .initializeSchema(true).vectorTableValidationsEnabled(true).build();
    }

    @Bean
    EquipmentDocumentLoader equipmentDocumentLoader(RagProperties properties) {
        return new EquipmentDocumentLoader(properties.documents());
    }

    @Bean
    ApplicationRunner ingestEquipmentOnStartup(EquipmentIngestionService ingestion, RagProperties properties) {
        return args -> {
            if (properties.ingestOnStartup()) {
                ingestion.ingest();
            }
        };
    }
}
