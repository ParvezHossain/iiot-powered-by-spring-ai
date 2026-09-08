package com.iiot.mcp;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.iiot.rag.RagAnswerProperties;
import com.iiot.rag.RagAnswerService;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mcp.enabled=true", "rag.enabled=false", "agent.enabled=false", "simulator.enabled=false",
        "mcp.api-key=mcp-test-only-key-0123456789abcdef0123456789",
        "spring.datasource.url=jdbc:h2:mem:mcp;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@Import(EquipmentMcpTests.RagFixture.class)
class EquipmentMcpTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired VectorStore vectors;
    @Autowired ChatModel chat;
    private McpSyncClient client;
    private UUID machine;
    private final JsonMapper json = JsonMapper.builder().build();
    private static final String SAMPLE_TIME = "2026-09-07T12:00:00Z";
    private static final String PASSAGE = "E204 means the temperature sensor signal is unavailable.";
    private static final String AUTHORIZATION = "Bearer mcp-test-only-key-0123456789abcdef0123456789";

    @BeforeEach
    void connect() {
        reset(vectors, chat);
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(Document.builder()
                .id("fault-reference-chunk").text(PASSAGE).metadata(Map.of("source", "fault-code-reference.md")).build()));
        when(chat.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(
                json.writeValueAsString(Map.of("answer", PASSAGE, "insufficientEvidence", false,
                        "citations", List.of(Map.of("sourceId", "S1", "quote", PASSAGE)))))))));
        machine = UUID.randomUUID();
        jdbc.update("INSERT INTO telemetry.machines (id, name, location, status) VALUES (?, 'MCP test press', 'Line 1', 'RUNNING')", machine);
        for (int sample = 30; sample > 0; sample--) {
            jdbc.update("INSERT INTO telemetry.sensor_readings (machine_id, metric_type, \"value\", \"timestamp\") VALUES (?, 'vibration_mm_s', 2, ?)",
                    machine, OffsetDateTime.parse(SAMPLE_TIME).minusSeconds(sample * 5L));
        }
        jdbc.update("INSERT INTO telemetry.sensor_readings (machine_id, metric_type, \"value\", \"timestamp\") VALUES (?, 'vibration_mm_s', 7.2, ?)",
                machine, OffsetDateTime.parse(SAMPLE_TIME));
        var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                .requestBuilder(java.net.http.HttpRequest.newBuilder().header("Authorization", AUTHORIZATION))
                .endpoint("/mcp").openConnectionOnStartup(false).build();
        client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(15)).build();
        assertThat(client.initialize().serverInfo().name()).isEqualTo("iiot-equipment");
    }

    @AfterEach
    void disconnect() {
        if (client != null) {
            client.closeGracefully();
        }
        jdbc.update("DELETE FROM telemetry.sensor_readings WHERE machine_id = ?", machine);
        jdbc.update("DELETE FROM telemetry.machines WHERE id = ?", machine);
    }

    @Test
    void sdkClientListsAndSuccessfullyCallsEveryExposedTool() {
        var tools = client.listTools().tools();
        assertThat(tools).extracting(McpSchema.Tool::name)
                .containsExactlyInAnyOrder("getMachineStatus", "getRecentAnomalies", "ragQuery");
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.annotations().readOnlyHint()).isTrue();
            assertThat(tool.inputSchema()).containsKeys("type", "properties");
        });
        var status = result(call("getMachineStatus", Map.of("machineId", machine.toString())));
        assertThat(status.path("id").asString()).isEqualTo(machine.toString());
        assertThat(status.path("latestReadings").get(0).path("value").asDouble()).isEqualTo(7.2);
        assertThat(status.path("latestReadings").get(0).path("timestamp").asString()).isEqualTo(SAMPLE_TIME);

        var anomalies = result(call("getRecentAnomalies", Map.of("machineId", machine.toString(),
                "from", SAMPLE_TIME, "to", "2026-09-07T12:01:00Z", "limit", 10)));
        assertThat(anomalies.size()).isEqualTo(1);
        assertThat(anomalies.get(0).path("reason").asString()).isEqualTo("HIGH_VIBRATION");

        var knowledge = result(call("ragQuery", Map.of("question", "What does E204 mean?")));
        assertThat(knowledge.path("insufficientEvidence").asBoolean()).isFalse();
        assertThat(knowledge.path("answer").asString()).contains("E204", "temperature sensor");
        assertThat(knowledge.path("citations").get(0).path("quote").asString()).isEqualTo(PASSAGE);
        assertThat(knowledge.path("citations").get(0).path("source").asString()).isEqualTo("fault-code-reference.md");
        verify(vectors).similaritySearch(any(SearchRequest.class));
        verify(chat).call(any(Prompt.class));
    }

    @Test
    void invalidArgumentsAndMissingMachinesAreToolErrorsAndSessionRemainsUsable() {
        for (var arguments : List.<Map<String, Object>>of(Map.of(), Map.of("machineId", "12"),
                Map.of("machineId", UUID.randomUUID().toString()), Map.of("machineId", 12))) {
            assertThat(call("getMachineStatus", arguments).isError()).isTrue();
        }
        assertThat(call("getRecentAnomalies", Map.of("limit", 0)).isError()).isTrue();
        assertThat(call("getRecentAnomalies", Map.of("from", "2026-09-08T00:00:00Z", "to", SAMPLE_TIME)).isError()).isTrue();
        for (String question : List.of(" ", "x".repeat(2001))) {
            assertThat(call("ragQuery", Map.of("question", question)).isError()).isTrue();
        }
        verifyNoInteractions(vectors, chat);
        assertThat(call("getMachineStatus", Map.of("machineId", machine.toString())).isError()).isFalse();
    }

    @Test
    void missingEvidenceIsDistinctFromSanitizedDependencyFailure() {
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        assertThat(result(call("ragQuery", Map.of("question", "Unknown code?")))
                .path("insufficientEvidence").asBoolean()).isTrue();
        when(vectors.similaritySearch(any(SearchRequest.class))).thenThrow(new IllegalStateException("private connection details"));
        var failed = call("ragQuery", Map.of("question", "E204"));
        assertThat(failed.isError()).isTrue();
        assertThat(((McpSchema.TextContent) failed.content().getFirst()).text()).doesNotContain("private connection details");
        verifyNoInteractions(chat);
    }

    @Test
    void rejectsUntrustedBrowserOrigin() throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/mcp"))
                .header("Authorization", AUTHORIZATION).header("Origin", "https://untrusted.example").GET().build();
        assertThat(java.net.http.HttpClient.newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
                .statusCode()).isEqualTo(403);
    }

    @Test
    void everyMcpRequestRequiresAuthorizationEvenWithAValidSessionId() throws Exception {
        var http = java.net.http.HttpClient.newHttpClient();
        var uri = java.net.URI.create("http://localhost:" + port + "/mcp");
        String initialize = json.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize",
                "params", Map.of("protocolVersion", "2025-11-25", "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "auth-test", "version", "1.0"))));
        var initialized = http.send(java.net.http.HttpRequest.newBuilder(uri)
                .header("Authorization", AUTHORIZATION).header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(initialize)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(initialized.statusCode()).isEqualTo(200);
        String session = initialized.headers().firstValue("mcp-session-id").orElseThrow();
        for (String method : List.of("POST", "GET", "DELETE", "OPTIONS")) {
            for (String credential : List.of("", "Bearer wrong-key", "Basic abc", "Bearer", AUTHORIZATION + ", extra")) {
                var request = java.net.http.HttpRequest.newBuilder(uri)
                        .header("Accept", "application/json, text/event-stream")
                        .header("Content-Type", "application/json").header("mcp-session-id", session)
                        .method(method, method.equals("POST")
                                ? java.net.http.HttpRequest.BodyPublishers.ofString(initialize)
                                : java.net.http.HttpRequest.BodyPublishers.noBody());
                if (!credential.isEmpty()) {
                    request.header("Authorization", credential);
                }
                var response = http.send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).as(method + " rejects invalid credentials").isEqualTo(401);
                assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer realm=\"iiot-mcp\"");
                assertThat(response.body()).isEqualTo("{\"error\":\"Unauthorized\"}");
            }
        }
        // A rejected DELETE did not close the session; only a credentialed DELETE can do so.
        var deleted = http.send(java.net.http.HttpRequest.newBuilder(uri)
                .header("Authorization", AUTHORIZATION).header("mcp-session-id", session).DELETE().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(deleted.statusCode()).isEqualTo(200);
        verifyNoInteractions(vectors, chat);
    }

    @Test
    void rejectsDuplicateHeadersAndQueryTokensWhileHealthRemainsPublic() throws Exception {
        var http = java.net.http.HttpClient.newHttpClient();
        var duplicate = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/mcp"))
                .header("Authorization", AUTHORIZATION).header("Authorization", AUTHORIZATION).GET().build();
        assertThat(http.send(duplicate, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        var query = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port
                + "/mcp?access_token=" + AUTHORIZATION.substring(7))).GET().build();
        assertThat(http.send(query, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        var health = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/actuator/health"))
                .GET().build();
        assertThat(http.send(health, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
    }

    private McpSchema.CallToolResult call(String name, Map<String, Object> arguments) {
        return client.callTool(McpSchema.CallToolRequest.builder().name(name).arguments(arguments).build());
    }

    private JsonNode result(McpSchema.CallToolResult result) {
        assertThat(result.isError()).isFalse();
        var text = json.readTree(((McpSchema.TextContent) result.content().getFirst()).text());
        assertThat(json.valueToTree(result.structuredContent()).path("result")).isEqualTo(text);
        return text;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RagFixture {
        @Bean VectorStore vectors() { return mock(VectorStore.class); }
        @Bean ChatModel equipmentChatModel() { return mock(ChatModel.class); }
        @Bean RagAnswerService ragAnswerService(VectorStore vectors, ChatModel equipmentChatModel) {
            return new RagAnswerService(vectors, equipmentChatModel, new RagAnswerProperties("test", 6, 0.45));
        }
    }
}
