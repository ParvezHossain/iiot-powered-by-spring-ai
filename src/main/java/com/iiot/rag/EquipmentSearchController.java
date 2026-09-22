package com.iiot.rag;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

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

@Tag(name = "Equipment documents", description = "Equipment corpus ingestion and semantic search. Requires rag.enabled=true.")
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

    @Operation(operationId = "ingestEquipmentDocuments", summary = "Replace the equipment corpus",
            description = "Loads configured Markdown documents, chunks and embeds them, and transactionally replaces the equipment corpus. This operation changes stored vectors and can take time.")
    @ApiResponse(responseCode = "200", description = "Successful response")
    @ApiResponse(responseCode = "500", description = "Document loading, embedding, or database operation failed", content = @Content)
    @PostMapping("/ingest")
    public EquipmentIngestionService.IngestionResult ingest() throws IOException {
        return ingestion.ingest();
    }

    @Operation(operationId = "searchEquipmentDocuments", summary = "Search equipment documents",
            description = "Returns up to topK similar equipment chunks with source metadata. An empty array means no chunks met the threshold.")
    @ApiResponse(responseCode = "200", description = "Successful response")
    @ApiResponse(responseCode = "400", description = "Invalid query, topK, or threshold", content = @Content)
    @ApiResponse(responseCode = "500", description = "Embedding or vector store operation failed", content = @Content)
    @GetMapping("/search")
    public List<Match> search(@Parameter(description = "Nonblank search text", example = "What does E204 mean?", schema = @Schema(minLength = 1, maxLength = 2000)) @RequestParam String query, @Parameter(description = "Maximum matches", schema = @Schema(minimum = "1", maximum = "20")) @RequestParam(defaultValue = "5") int topK,
                              @Parameter(description = "Minimum similarity score", schema = @Schema(minimum = "0", maximum = "1")) @RequestParam(defaultValue = "0.0") double threshold) {
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

    @Schema(name = "EquipmentMatch", description = "Retrieved equipment document chunk.")
    public record Match(@Schema(description = "Vector document chunk ID") String id,
            @Schema(description = "Similarity score when available") Double score,
            @Schema(description = "Retrieved passage text") String text,
            @Schema(description = "Source metadata including document_id, source, section, and corpus") Map<String, Object> metadata) {}
}
