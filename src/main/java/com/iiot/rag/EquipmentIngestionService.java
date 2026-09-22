package com.iiot.rag;

import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "rag.enabled", havingValue = "true")
public class EquipmentIngestionService {
    private static final Logger log = LoggerFactory.getLogger(EquipmentIngestionService.class);
    private final VectorStore vectors;
    private final EquipmentDocumentLoader loader;
    private final RagProperties properties;
    private final JdbcTemplate jdbc;

    public EquipmentIngestionService(VectorStore vectors, EquipmentDocumentLoader loader,
                                     RagProperties properties, JdbcTemplate jdbc) {
        this.vectors = vectors;
        this.loader = loader;
        this.properties = properties;
        this.jdbc = jdbc;
    }

    @Transactional(rollbackFor = Exception.class)
    public IngestionResult ingest() throws IOException {
        // Validate the complete input before replacing anything. All vector writes use this transaction.
        var chunks = loader.load();
        // Serialize corpus replacement even when requests arrive at different app instances.
        jdbc.execute("SELECT pg_advisory_xact_lock(20260202, 1)");
        vectors.delete(new FilterExpressionBuilder().eq("corpus", RagProperties.CORPUS).build());
        for (int start = 0; start < chunks.size(); start += properties.batchSize()) {
            vectors.add(chunks.subList(start, Math.min(start + properties.batchSize(), chunks.size())));
        }
        int documents = (int) chunks.stream().map(d -> d.getMetadata().get("document_id")).distinct().count();
        log.info("Embedded equipment corpus: {} documents, {} chunks, model {}", documents, chunks.size(), RagProperties.MODEL);
        return new IngestionResult(documents, chunks.size(), RagProperties.MODEL);
    }

    @Schema(name = "EquipmentIngestionResult", description = "Completed corpus replacement counts.")
    public record IngestionResult(@Schema(description = "Number of source documents loaded") int documents,
            @Schema(description = "Number of embedded chunks stored") int chunks,
            @Schema(description = "Embedding model identifier") String model) {}
}
