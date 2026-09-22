package com.iiot.demo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.iiot.agent.*;
import com.iiot.alert.AlertConfiguration;
import com.iiot.alert.AnomalyAlertService;
import com.iiot.mcp.EquipmentMcpConfiguration;
import com.iiot.rag.RagAnswerProperties;
import com.iiot.rag.RagAnswerService;
import com.iiot.telemetry.*;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Interview replay: real application paths with explicitly scripted model/retrieval boundaries. */
@SpringBootTest(classes = InterviewDemoTests.DemoApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:interview_demo;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.profiles.active=", "spring.main.banner-mode=off", "simulator.enabled=false",
        "agent.enabled=true", "rag.enabled=true", "mcp.enabled=true",
        "alerts.enabled=true", "alerts.gmail.enabled=false",
        "mcp.api-key=isolated-demo-only-0123456789abcdef0123456789", "server.address=127.0.0.1"})
class InterviewDemoTests {
    private static final UUID MACHINE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String AUTH = "Bearer isolated-demo-only-0123456789abcdef0123456789";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier("agentChatModel") ChatModel model;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void interviewScenario() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0).plusSeconds(1);
        var monday = now.toLocalDate().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                .atStartOfDay().atOffset(ZoneOffset.UTC);
        var from = monday.minusWeeks(1);
        var to = monday.minusNanos(1);
        jdbc.update("INSERT INTO telemetry.machines (id, name, location, status) VALUES (?, 'SIM-001', 'Demo pump bay', 'RUNNING')", MACHINE);
        for (int i = 30; i > 0; i--) {
            var sampledAt = now.minusSeconds(i * 5L);
            // Keep current-week warm-up separate from last week's fixture even just after Monday midnight.
            reading(2.0, sampledAt.isBefore(monday) ? monday : sampledAt);
        }
        reading(7.2, now);
        reading(1.8, from.plusDays(1));
        reading(2.0, from.plusDays(2));
        reading(2.2, from.plusDays(3));

        var replies = new ArrayDeque<>(List.of(
                calls("resolveMachine", Map.of("name", "SIM-001")),
                calls("getMachineStatus", Map.of("machineId", MACHINE.toString())),
                text("ready"), answer("SIM-001's latest vibration is 7.2 mm/s at " + now + " [T2].", "T2"),
                calls("getMachineStatus", Map.of("machineId", MACHINE.toString()),
                        "retrieveEquipmentKnowledge", Map.of("question", "SIM-001 normal vibration and inspection")),
                text("ready"), answer("The latest 7.2 mm/s reading [T1] is above the manual's 1.2–2.4 mm/s range at comparable load. "
                        + "Compare the load and inspect; stop and isolate before opening the strainer or coupling guard. "
                        + "Vibration alone does not establish cavitation [T2].", "T1", "T2"),
                calls("queryTelemetryRange", Map.of("machineId", MACHINE.toString(), "metricType", "vibration_mm_s",
                        "from", from.toString(), "to", to.toString())),
                text("ready"), answer("For SIM-001 vibration last week (" + from.toLocalDate() + " through "
                        + to.toLocalDate() + " UTC), the three seeded samples were 1.8, 2.0, and 2.2 mm/s. "
                        + "That is a sparse sample, not proof of continuous healthy operation [T1].", "T1"),
                calls("retrieveEquipmentKnowledge", Map.of("question", "What does E204 mean?")),
                text("ready"), answer("E204 means the SIM-003 compressor's motor temperature sensor signal is unavailable; "
                        + "it does not mean motor overheating. Stop and isolate before checking wiring and connectors, "
                        + "then verify fresh readings [T1].", "T1")));
        var prompts = new ArrayList<Prompt>();
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            prompts.add(invocation.getArgument(0));
            assertThat(replies).as("Unexpected model call in deterministic replay").isNotEmpty();
            return replies.removeFirst();
        });

        var transcript = new StringBuilder();
        String conversation = null;
        String[] questions = {"What is SIM-001's latest vibration reading?",
                "Is that normal, and what should I do?", "And what about last week?", "What does E204 mean?"};
        List<List<String>> routes = List.of(List.of("resolveMachine", "getMachineStatus"),
                List.of("getMachineStatus", "retrieveEquipmentKnowledge"), List.of("queryTelemetryRange"),
                List.of("retrieveEquipmentKnowledge"));
        for (int turn = 0; turn < questions.length; turn++) {
            var body = new java.util.LinkedHashMap<String, Object>();
            body.put("question", questions[turn]);
            if (conversation != null) body.put("conversationId", conversation);
            var response = http.send(HttpRequest.newBuilder(URI.create(base() + "/api/agent/chat"))
                    .header("Authorization", "Bearer " + demoAccessToken())
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode result = JSON.readTree(response.body());
            assertThat(result.path("insufficientEvidence").asBoolean()).isFalse();
            if (conversation != null) assertThat(result.path("conversationId").asString()).isEqualTo(conversation);
            conversation = result.path("conversationId").asString();
            var executed = new ArrayList<String>();
            for (JsonNode evidence : result.path("evidence")) {
                assertThat(evidence.path("success").asBoolean()).isTrue();
                executed.add(evidence.path("tool").asString());
            }
            assertThat(executed).containsExactlyElementsOf(routes.get(turn));
            assertThat(result.path("answer").asString()).isNotBlank();
            assertThat(result.path("evidenceIds").size()).isEqualTo(turn == 1 ? 2 : 1);
            if (turn == 0) {
                var latest = JSON.readTree(result.path("evidence").get(1).path("result").asString());
                assertThat(latest.path("latestReadings").get(0).path("value").asDouble()).isEqualTo(7.2);
            }
            transcript.append(turn + 1).append(". ").append(questions[turn]).append('\n')
                    .append("   ").append(result.path("answer").asString()).append('\n')
                    .append("   Tools: ").append(String.join(" → ", executed)).append("\n\n");
            if (turn == 2) {
                var historical = JSON.readTree(result.path("evidence").get(0).path("result").asString());
                assertThat(historical.size()).isEqualTo(3);
                assertThat(historical.get(0).path("value").asDouble()).isEqualTo(1.8);
                assertThat(historical.get(2).path("value").asDouble()).isEqualTo(2.2);
            }
        }
        assertThat(replies).isEmpty();
        var history = JSON.readTree(prompts.get(9).getUserMessage().getText()).path("history");
        assertThat(history.size()).isEqualTo(2);
        assertThat(history.toString()).contains("SIM-001", MACHINE.toString(), "vibration");

        var denied = http.send(HttpRequest.newBuilder(URI.create(base() + "/mcp"))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(denied.statusCode()).isEqualTo(401);
        var transport = HttpClientStreamableHttpTransport.builder(base()).endpoint("/mcp")
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", AUTH)).openConnectionOnStartup(false).build();
        var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build();
        try {
            client.initialize();
            assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name)
                    .containsExactlyInAnyOrder("getMachineStatus", "getRecentAnomalies", "ragQuery");
            var anomalies = client.callTool(McpSchema.CallToolRequest.builder().name("getRecentAnomalies")
                    .arguments(Map.of("machineId", MACHINE.toString(), "from", now.minusMinutes(3).toString(), "to", now.toString())).build());
            assertThat(anomalies.isError()).isFalse();
            var flagged = JSON.readTree(((McpSchema.TextContent) anomalies.content().getFirst()).text());
            assertThat(flagged.size()).isEqualTo(1);
            assertThat(flagged.get(0).path("baseline").path("zScore").asDouble()).isEqualTo(104.0);
            assertThat(flagged.get(0).path("reason").asString()).isEqualTo("HIGH_VIBRATION");
            transcript.append("5. What does the statistical detector flag?\n")
                    .append("   SIM-001 vibration: 7.2 mm/s vs prior mean 2.0; z-score 104.0, cutoff 4.0.\n")
                    .append("   One HIGH_VIBRATION result from the real SQL detector, called through MCP.\n\n");
            var knowledge = client.callTool(McpSchema.CallToolRequest.builder().name("ragQuery")
                    .arguments(Map.of("question", "What does E204 mean?")).build());
            assertThat(knowledge.isError()).isFalse();
            var grounded = JSON.readTree(((McpSchema.TextContent) knowledge.content().getFirst()).text());
            assertThat(grounded.path("insufficientEvidence").asBoolean()).isFalse();
            assertThat(grounded.path("citations").get(0).path("source").asString()).isEqualTo("fault-code-reference.md");
            assertThat(grounded.path("answer").asString()).contains("E204", "temperature sensor signal is unavailable");
            var status = client.callTool(McpSchema.CallToolRequest.builder().name("getMachineStatus")
                    .arguments(Map.of("machineId", MACHINE.toString())).build());
            assertThat(status.isError()).isFalse();
            var machine = JSON.readTree(((McpSchema.TextContent) status.content().getFirst()).text());
            assertThat(machine.path("name").asString()).isEqualTo("SIM-001");
            assertThat(machine.path("latestReadings").get(0).path("value").asDouble()).isEqualTo(7.2);
            transcript.append("MCP security: missing token → 401; authenticated discovery and all three tools → PASS.\n")
                    .append("Sources: bundled synthetic pump manual and fault-code reference; RAG quotes validated.\n");
        }
        finally {
            client.closeGracefully();
        }
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM telemetry.anomaly_alerts", Integer.class)).isEqualTo(1));
        assertThat(jdbc.queryForObject("SELECT email_status FROM telemetry.anomaly_alerts", String.class))
                .isEqualTo("NOT_REQUESTED");
        transcript.append("Automatic alert: background worker recorded and logged HIGH_VIBRATION without an API trigger.\n")
                .append("Gmail delivery is disabled in this isolated demo; no real email is sent.\n");
        String output = System.getProperty("demo.transcript");
        if (output != null) Files.writeString(Path.of(output), transcript.toString());
    }

    @Autowired com.iiot.auth.AuthService authentication;
    @Autowired com.iiot.auth.AuthProperties authenticationProperties;

    private String demoAccessToken() {
        return authentication.login(authenticationProperties.initialAdminUsername(),
                authenticationProperties.initialAdminPassword()).accessToken();
    }

    private String base() { return "http://127.0.0.1:" + port; }

    private void reading(double value, OffsetDateTime at) {
        jdbc.update("INSERT INTO telemetry.sensor_readings (machine_id, metric_type, \"value\", \"timestamp\") VALUES (?, 'vibration_mm_s', ?, ?)",
                MACHINE, value, at);
    }

    private static ChatResponse text(String value) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(value))));
    }

    private static ChatResponse answer(String value, String... ids) {
        return text(JSON.writeValueAsString(Map.of("answer", value, "insufficientEvidence", false, "evidenceIds", List.of(ids))));
    }

    private static ChatResponse calls(Object... definitions) {
        var calls = new ArrayList<AssistantMessage.ToolCall>();
        for (int i = 0; i < definitions.length; i += 2) {
            calls.add(new AssistantMessage.ToolCall(UUID.randomUUID().toString(), "function", (String) definitions[i],
                    JSON.writeValueAsString(definitions[i + 1])));
        }
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(calls).build())));
    }

    @TestComponent
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({com.iiot.auth.SecurityConfiguration.class, com.iiot.auth.AuthRepository.class,
            com.iiot.auth.TokenService.class, com.iiot.auth.AuthService.class, com.iiot.auth.InitialAdminInitializer.class,
            AgentController.class, AgentService.class, TelemetryController.class, TelemetryQueryService.class,
            TelemetryTools.class, EquipmentMcpConfiguration.class, AlertConfiguration.class, AnomalyAlertService.class})
    static class DemoApp {
        @Bean AgentProperties agentProperties() { return new AgentProperties("deterministic-demo", 4, 8); }
        @Bean ChatModel agentChatModel() { return mock(ChatModel.class); }
        @Bean VectorStore demoVectors() throws Exception {
            var vectors = mock(VectorStore.class);
            String manual = Files.readString(Path.of("docs/equipment/sim-001-pump-manual.md"));
            assertThat(manual).contains("vibration is 1.2–2.4 mm/s", "stop and isolate the equipment",
                    "Do not conclude cavitation from vibration alone");
            String fault = Files.readString(Path.of("docs/equipment/fault-code-reference.md"));
            var pump = Document.builder().id("demo-pump-manual").text(manual)
                    .metadata(Map.of("source", "sim-001-pump-manual.md")).build();
            var reference = Document.builder().id("demo-fault-reference").text(fault)
                    .metadata(Map.of("source", "fault-code-reference.md")).build();
            when(vectors.similaritySearch(any(SearchRequest.class))).thenAnswer(invocation ->
                    List.of(((SearchRequest) invocation.getArgument(0)).getQuery().contains("E204") ? reference : pump));
            return vectors;
        }
        @Bean ToolCallbackProvider telemetryToolCallbackProvider(TelemetryTools tools) {
            return MethodToolCallbackProvider.builder().toolObjects(tools).build();
        }
        @Bean ToolCallbackProvider agentToolCallbackProvider(TelemetryTools tools, VectorStore vectors, JdbcTemplate jdbc) {
            return MethodToolCallbackProvider.builder().toolObjects(tools,
                    new AgentKnowledgeTools(vectors, new RagAnswerProperties("demo", 6, 0.45), jdbc)).build();
        }
        @Bean RagAnswerService ragAnswerService(VectorStore vectors) throws Exception {
            String source = Files.readString(Path.of("docs/equipment/fault-code-reference.md"));
            String quote = source.substring(source.indexOf("E204 means"), source.indexOf(" It indicates"));
            var chat = mock(ChatModel.class);
            when(chat.call(any(Prompt.class))).thenReturn(text(JSON.writeValueAsString(Map.of("answer", quote.replace('\n', ' '),
                    "insufficientEvidence", false, "citations", List.of(Map.of("sourceId", "S1", "quote", quote))))));
            return new RagAnswerService(vectors, chat, new RagAnswerProperties("demo", 6, 0.45));
        }
    }
}
