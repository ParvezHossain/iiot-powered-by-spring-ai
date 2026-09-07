package com.iiot.agent;

import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import com.iiot.rag.RagAnswerProperties;
import com.iiot.rag.RagProperties;

public class AgentKnowledgeTools {
    private final VectorStore vectors;
    private final RagAnswerProperties properties;
    private final JdbcTemplate jdbc;

    public AgentKnowledgeTools(VectorStore vectors, RagAnswerProperties properties, JdbcTemplate jdbc) {
        this.vectors = vectors;
        this.properties = properties;
        this.jdbc = jdbc;
    }

    @Tool(description = "Retrieve equipment manual and fault-reference passages for definitions, normal operating ranges, "
            + "and documented maintenance actions. This is historical documentation, never current telemetry. "
            + "Include the resolved machine name (such as SIM-001), metric, or error code in the question. "
            + "Empty results mean insufficient documentation; do not invent guidance.")
    public List<Passage> retrieveEquipmentKnowledge(
            @ToolParam(description = "Specific document search question, 1–2000 characters") String question) {
        if (question == null || question.isBlank() || question.length() > 2000) {
            throw new IllegalArgumentException("question must contain 1–2000 characters");
        }
        var documents = vectors.similaritySearch(SearchRequest.builder().query(question)
                .topK(properties.topK()).similarityThreshold(properties.threshold())
                .filterExpression(new FilterExpressionBuilder().eq("corpus", RagProperties.CORPUS).build()).build());
        // Do not substitute a semantically similar fault for an explicitly requested error code.
        var codes = Pattern.compile("\\b[A-Z]{1,4}-?\\d{2,5}\\b", Pattern.CASE_INSENSITIVE).matcher(question)
                .results().map(m -> m.group().toUpperCase(Locale.ROOT)).distinct().toList();
        var retained = documents.stream().filter(d -> d.getText() != null && !d.getText().isBlank())
                .filter(d -> codes.isEmpty() || codes.stream().anyMatch(code -> mentions(d, code))).toList();
        if (codes.stream().anyMatch(code -> retained.stream().noneMatch(d -> mentions(d, code)))) {
            return List.of();
        }
        return retained.stream().map(d -> new Passage(d.getId(), d.getText(), d.getMetadata(), d.getScore())).toList();
    }

    private static boolean mentions(Document document, String code) {
        return Pattern.compile("(?i)(?<![A-Z0-9_-])" + Pattern.quote(code) + "(?![A-Z0-9_-])")
                .matcher(document.getText()).find();
    }

    @Tool(description = "Resolve a machine name to existing database UUIDs before querying telemetry. "
            + "Accept an exact stored name, SIM-001, or a simulator number such as 12 (SIM-012). "
            + "Use the UUID only if exactly one match is returned. Zero or multiple matches require clarification.")
    public List<Map<String, Object>> resolveMachine(
            @ToolParam(description = "Exact machine name or simulator number; never a guessed UUID") String name) {
        if (name == null || name.isBlank() || name.length() > 100) {
            throw new IllegalArgumentException("name must contain 1–100 characters");
        }
        String key = name.strip();
        String alias = key.matches("\\d{1,6}") ? "SIM-%03d".formatted(Integer.parseInt(key)) : key;
        return jdbc.queryForList("SELECT id, name, location FROM telemetry.machines "
                + "WHERE LOWER(name) = LOWER(?) OR LOWER(name) = LOWER(?) ORDER BY id LIMIT 6", key, alias);
    }

    public record Passage(String chunkId, String text, Map<String, Object> metadata, Double score) {}
}
