package com.iiot.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real pgvector persistence/transaction test; no model download is required in CI. */
@EnabledIfEnvironmentVariable(named = "RAG_PGVECTOR_TESTS", matches = "true")
@SpringBootTest(properties = {"rag.enabled=true", "rag.ingest-on-startup=false",
        "rag.batch-size=2", "simulator.enabled=false"})
class PgVectorIngestionTests {
    @Autowired EquipmentIngestionService ingestion;
    @Autowired VectorStore vectors;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean(name = "equipmentEmbeddingModel") EmbeddingModel model;
    @MockitoSpyBean EquipmentDocumentLoader loader;

    @Test
    void replacesCorpusWithoutDuplicatesRemovesStaleChunksAndRollsBackFailedEmbedding() throws Exception {
        when(model.embed(anyList(), any(), any())).thenAnswer(invocation -> {
            List<Document> documents = invocation.getArgument(0);
            return embeddings(documents.size());
        });
        when(model.embed(anyString())).thenReturn(embeddings(1).getFirst());
        String unrelated = UUID.randomUUID().toString();
        try {
            var first = ingestion.ingest();
            assertThat(first.documents()).isEqualTo(10);
            assertThat(first.chunks()).isGreaterThan(10);
            var originalIds = corpusIds();
            assertThat(ingestion.ingest()).isEqualTo(first);
            assertThat(corpusIds()).containsExactlyElementsOf(originalIds);
            assertThat(jdbc.queryForObject("SELECT MIN(vector_dims(embedding)) FROM public.equipment_vectors",
                    Integer.class)).isEqualTo(768);

            vectors.add(List.of(new Document(unrelated, "Other corpus", Map.of("corpus", "unrelated"))));
            var hits = vectors.similaritySearch(SearchRequest.builder().query("test persistence")
                    .topK(3).filterExpression(new FilterExpressionBuilder().eq("corpus", RagProperties.CORPUS).build()).build());
            assertThat(hits).hasSize(3).allSatisfy(hit -> {
                assertThat(hit.getMetadata()).containsKeys("source", "document_id", "section", "machine_ids");
                assertThat(hit.getScore()).isCloseTo(1.0, within(0.0001));
                assertThat(hit.getId()).isNotEqualTo(unrelated);
            });

            var original = loader.load();
            doReturn(original.subList(0, 2)).when(loader).load();
            assertThat(ingestion.ingest().chunks()).isEqualTo(2);
            assertThat(corpusIds()).hasSize(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.equipment_vectors WHERE id = ?::uuid",
                    Integer.class, unrelated)).isEqualTo(1);

            // The first batch writes successfully, then the model fails: the previous corpus must survive.
            var beforeFailure = corpusIds();
            doReturn(original).when(loader).load();
            var calls = new AtomicInteger();
            when(model.embed(anyList(), any(), any())).thenAnswer(invocation -> {
                if (calls.incrementAndGet() == 2) {
                    throw new IllegalStateException("Simulated embedding outage");
                }
                return embeddings(((List<?>) invocation.getArgument(0)).size());
            });
            assertThatThrownBy(ingestion::ingest).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("embedding outage");
            assertThat(corpusIds()).containsExactlyElementsOf(beforeFailure);
        }
        finally {
            vectors.delete(new FilterExpressionBuilder().eq("corpus", RagProperties.CORPUS).build());
            vectors.delete(List.of(unrelated));
        }
    }

    private List<String> corpusIds() {
        return jdbc.queryForList("""
                SELECT id::text FROM public.equipment_vectors
                WHERE metadata->>'corpus' = ? ORDER BY id
                """, String.class, RagProperties.CORPUS);
    }

    private List<float[]> embeddings(int count) {
        var result = new ArrayList<float[]>();
        for (int i = 0; i < count; i++) {
            float[] vector = new float[768];
            vector[0] = 1;
            result.add(vector);
        }
        return result;
    }
}
