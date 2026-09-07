package com.iiot.rag;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/documents")
@ConditionalOnProperty(name = "rag.enabled", havingValue = "true")
public class EquipmentSearchController {
    private final VectorStore vectors;
    private final EquipmentIngestionService ingestion;

    public EquipmentSearchController(VectorStore vectors, EquipmentIngestionService ingestion) {
        this.vectors = vectors;
        this.ingestion = ingestion;
    }

    @PostMapping("/ingest")
    public EquipmentIngestionService.IngestionResult ingest() throws IOException {
        return ingestion.ingest();
    }

    @GetMapping("/search")
    public List<Match> search(@RequestParam String query, @RequestParam(defaultValue = "5") int topK,
                              @RequestParam(defaultValue = "0.0") double threshold) {
        if (query.isBlank() || query.length() > 2000 || topK < 1 || topK > 20
                || !Double.isFinite(threshold) || threshold < 0 || threshold > 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "query must contain 1–2000 characters, topK must be 1–20, threshold must be 0–1");
        }
        var filter = new FilterExpressionBuilder().eq("corpus", RagProperties.CORPUS).build();
        return vectors.similaritySearch(SearchRequest.builder().query(query).topK(topK)
                        .similarityThreshold(threshold).filterExpression(filter).build()).stream()
                .map(d -> new Match(d.getId(), d.getScore(), d.getText(), d.getMetadata())).toList();
    }

    public record Match(String id, Double score, String text, Map<String, Object> metadata) {}
}
