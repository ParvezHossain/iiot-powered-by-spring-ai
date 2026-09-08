package com.iiot.agent;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.iiot.rag.RagAnswerProperties;
import com.iiot.telemetry.TelemetryQueryService;
import com.iiot.telemetry.TelemetryTools;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AgentTests {
    private ChatModel model;
    private TelemetryQueryService queries;
    private VectorStore vectors;
    private JdbcTemplate jdbc;
    private AgentService service;
    private MockMvc mvc;
    private final JsonMapper json = JsonMapper.builder().build();
    private final UUID machine = UUID.fromString("00000000-0000-0000-0000-000000000012");

    @BeforeEach
    void setup() {
        model = mock(ChatModel.class);
        queries = mock(TelemetryQueryService.class);
        vectors = mock(VectorStore.class);
        jdbc = mock(JdbcTemplate.class);
        var knowledge = new AgentKnowledgeTools(vectors, new RagAnswerProperties("test", 6, 0.45), jdbc);
        var provider = MethodToolCallbackProvider.builder().toolObjects(new TelemetryTools(queries), knowledge).build();
        service = new AgentService(model, provider, new AgentProperties("test", 4, 8));
        mvc = MockMvcBuilders.standaloneSetup(new AgentController(service)).build();
        when(queries.status(machine)).thenReturn(new TelemetryQueryService.MachineStatus(
                machine, "SIM-012", "Line 1", "RUNNING", List.of()));
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(Document.builder()
                .id("manual").text("SIM-012 vibration above 5 mm/s requires inspection after stopping and isolating.")
                .metadata(Map.of("source", "manual.md")).build()));
    }

    @Test
    void dataQuestionCallsOnlyDataToolAndReturnsItsEvidence() throws Exception {
        when(model.call(any(Prompt.class))).thenReturn(
                calls(tool("getMachineStatus", Map.of("machineId", machine.toString()))),
                text("ready"), text(finalAnswer("Stored status is RUNNING [T1].", false, "T1")));
        mvc.perform(request("What is the status of " + machine + "?"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.insufficientEvidence").value(false))
                .andExpect(jsonPath("$.evidence[0].tool").value("getMachineStatus"))
                .andExpect(jsonPath("$.answer").value("Stored status is RUNNING [T1]."));
        verify(queries).status(machine);
        verifyNoInteractions(vectors);
        var prompts = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(3)).call(prompts.capture());
        assertThat(prompts.getAllValues()).allSatisfy(p -> assertThat(p.getOptions().getModel()).isEqualTo("test"));
        assertThat(prompts.getAllValues().get(1).getInstructions()).anyMatch(m -> m instanceof ToolResponseMessage);
        assertThat(prompts.getAllValues().get(2).getUserMessage().getText()).contains("RUNNING", "T1");
    }

    @Test
    void knowledgeQuestionCallsOnlyRetriever() {
        when(model.call(any(Prompt.class))).thenReturn(
                calls(tool("retrieveEquipmentKnowledge", Map.of("question", "SIM-012 vibration guidance"))),
                text("ready"), text(finalAnswer("Stop and isolate before inspection [T1].", false, "T1")));
        var result = service.answer("What does the manual say about SIM-012 vibration?");
        assertThat(result.insufficientEvidence()).isFalse();
        assertThat(result.evidence()).extracting(AgentService.Evidence::tool).containsExactly("retrieveEquipmentKnowledge");
        verifyNoInteractions(queries);
    }

    @Test
    void mixedQuestionCanCallBothToolsInOneRound() {
        when(model.call(any(Prompt.class))).thenReturn(
                calls(tool("getMachineStatus", Map.of("machineId", machine.toString())),
                        tool("retrieveEquipmentKnowledge", Map.of("question", "SIM-012 vibration normal range and actions"))),
                text("ready"), text(finalAnswer("No readings are available [T1]. The manual requires isolation before inspection [T2].",
                        true, "T1", "T2")));
        var result = service.answer("Is machine 12's vibration reading normal, and what should I do if not? UUID: " + machine);
        assertThat(result.evidence()).extracting(AgentService.Evidence::tool)
                .containsExactly("getMachineStatus", "retrieveEquipmentKnowledge");
        assertThat(result.answer()).contains("[T1]", "[T2]");
        verify(queries).status(machine);
        verify(vectors).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void mixedQuestionResolvesNumericAliasThenGroundsReadingAndAction() throws Exception {
        when(jdbc.queryForList(anyString(), eq("12"), eq("SIM-012")))
                .thenReturn(List.of(Map.of("id", machine, "name", "SIM-012", "location", "Line 1")));
        var sampledAt = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).withNano(0);
        when(queries.status(machine)).thenReturn(new TelemetryQueryService.MachineStatus(
                machine, "SIM-012", "Line 1", "RUNNING", List.of(new TelemetryQueryService.Reading(
                        1, machine, "vibration_mm_s", 7.2, sampledAt))));
        String answer = "Machine 12 measured 7.2 mm/s at " + sampledAt + " [T2]. "
                + "This exceeds its manual's 5 mm/s inspection threshold; stop and isolate before inspection [T3].";
        when(model.call(any(Prompt.class))).thenReturn(
                calls(tool("resolveMachine", Map.of("name", "12"))),
                calls(tool("getMachineStatus", Map.of("machineId", machine.toString())),
                        tool("retrieveEquipmentKnowledge", Map.of("question", "SIM-012 vibration normal range and actions"))),
                text("ready"), text(finalAnswer(answer, false, "T2", "T3")));

        mvc.perform(request("Is machine 12's vibration reading normal, and what should I do if not?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insufficientEvidence").value(false))
                .andExpect(jsonPath("$.answer").value(answer))
                .andExpect(jsonPath("$.evidence[0].tool").value("resolveMachine"))
                .andExpect(jsonPath("$.evidence[1].tool").value("getMachineStatus"))
                .andExpect(jsonPath("$.evidence[2].tool").value("retrieveEquipmentKnowledge"));
        verify(jdbc).queryForList(anyString(), eq("12"), eq("SIM-012"));
        verify(queries).status(machine);
        var prompts = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(4)).call(prompts.capture());
        assertThat(((org.springframework.ai.ollama.api.OllamaChatOptions) prompts.getAllValues().get(0).getOptions())
                .getToolCallbacks()).extracting(c -> c.getToolDefinition().name())
                .contains("resolveMachine", "retrieveEquipmentKnowledge", "getRecentAnomalies")
                .doesNotContain("getMachineStatus", "queryTelemetryRange");
        assertThat(((org.springframework.ai.ollama.api.OllamaChatOptions) prompts.getAllValues().get(1).getOptions())
                .getToolCallbacks()).extracting(c -> c.getToolDefinition().name())
                .contains("getMachineStatus", "queryTelemetryRange");
        assertThat(prompts.getAllValues().get(1).getInstructions())
                .filteredOn(m -> m instanceof ToolResponseMessage)
                .anySatisfy(m -> assertThat(((ToolResponseMessage) m).getResponses().getFirst().responseData())
                        .contains(machine.toString(), "SIM-012"));
        assertThat(prompts.getAllValues().get(3).getUserMessage().getText())
                .contains("7.2", sampledAt.toString(), "5 mm/s", "manual.md", "T2", "T3");
    }

    @Test
    void greetingNeedsNeitherToolAndEachRequestHasIsolatedEvidence() {
        when(model.call(any(Prompt.class))).thenReturn(text("Hello"), text(finalAnswer("Hello! How can I help?", false)));
        var result = service.answer("Hello");
        assertThat(result.evidence()).isEmpty();
        assertThat(result.insufficientEvidence()).isFalse();
        verifyNoInteractions(queries, vectors);
    }

    @Test
    void unknownToolsAndToolFailuresCannotBecomeSuccessfulEvidence() {
        when(model.call(any(Prompt.class))).thenReturn(calls(tool("deleteMachine", Map.of())),
                text("ready"), text(finalAnswer("Machine deleted [T1].", false, "T1")));
        var result = service.answer("Delete it");
        assertThat(result.insufficientEvidence()).isTrue();
        assertThat(result.evidence().getFirst().success()).isFalse();
        assertThat(result.evidenceIds()).isEmpty();
        verifyNoInteractions(queries, vectors);
    }

    @Test
    void rejectsFabricatedOrMissingEvidenceReferences() {
        for (String answer : List.of(finalAnswer("Invented [T99].", false, "T99"),
                finalAnswer("Unsupported", false), finalAnswer("Missing inline label", false, "T1"), "not json")) {
            when(model.call(any(Prompt.class))).thenReturn(calls(tool("getMachineStatus", Map.of("machineId", machine.toString()))),
                    text("ready"), text(answer));
            assertThat(service.answer("status of " + machine).insufficientEvidence()).isTrue();
        }
    }

    @Test
    void repeatedToolCallsStopAtRoundLimit() {
        when(model.call(any(Prompt.class))).thenReturn(calls(tool("getMachineStatus", Map.of("machineId", machine.toString()))));
        var result = service.answer("status of " + machine);
        assertThat(result.insufficientEvidence()).isTrue();
        assertThat(result.evidence()).hasSize(4);
        verify(model, times(4)).call(any(Prompt.class));
    }

    @Test
    void preventsInventedMachineIdsAndRequiresBothMixedEvidenceCategories() {
        when(model.call(any(Prompt.class))).thenReturn(calls(tool("getMachineStatus", Map.of("machineId", machine.toString()))),
                text("ready"), text(finalAnswer("Please provide a machine UUID.", true)));
        assertThat(service.answer("status of machine 12").evidence().getFirst().success()).isFalse();
        verifyNoInteractions(queries);

        when(model.call(any(Prompt.class))).thenReturn(
                calls(tool("getMachineStatus", Map.of("machineId", machine.toString())),
                        tool("retrieveEquipmentKnowledge", Map.of("question", "SIM-012 vibration"))),
                text("ready"), text(finalAnswer("RUNNING [T1].", false, "T1")));
        assertThat(service.answer("vibration advice for " + machine).insufficientEvidence()).isTrue();
    }

    @Test
    void modelFailuresReturn503AndInvalidRequestsNeverReachModel() throws Exception {
        for (String body : List.of("{}", "{\"question\":\" \"}", "{\"question\":null}",
                json.writeValueAsString(Map.of("question", "x".repeat(2001))), "invalid")) {
            mvc.perform(post("/api/agent/chat").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(model, queries, vectors);
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("offline"));
        mvc.perform(request("Hello")).andExpect(status().isServiceUnavailable());
    }

    private AssistantMessage.ToolCall tool(String name, Map<String, Object> args) {
        return new AssistantMessage.ToolCall(UUID.randomUUID().toString(), "function", name, json.writeValueAsString(args));
    }

    @Test
    void threeTurnConversationCarriesMachineAndMetricIntoLastWeekQuery() throws Exception {
        when(jdbc.queryForList(anyString(), eq("12"), eq("SIM-012")))
                .thenReturn(List.of(Map.of("id", machine, "name", "SIM-012")));
        var monday = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
                .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                .atStartOfDay().atOffset(java.time.ZoneOffset.UTC);
        var from = monday.minusWeeks(1);
        var to = monday.minusNanos(1);
        when(queries.readings(machine, from, to, "vibration_mm_s", 100, 0))
                .thenReturn(List.of(new TelemetryQueryService.Reading(1, machine, "vibration_mm_s", 2.1, from.plusDays(1))));
        when(model.call(any(Prompt.class))).thenReturn(
                calls(tool("resolveMachine", Map.of("name", "12"))),
                text("ready"), text(finalAnswer("Machine 12 is SIM-012 [T1].", false, "T1")),
                calls(tool("getMachineStatus", Map.of("machineId", machine.toString()))),
                text("ready"), text(finalAnswer("Its stored status is RUNNING but no vibration samples are available [T1].", true, "T1")),
                calls(tool("queryTelemetryRange", Map.of("machineId", machine.toString(), "metricType", "vibration_mm_s",
                        "from", from.toString(), "to", to.toString()))),
                text("ready"), text(finalAnswer("Machine 12 had one vibration sample of 2.1 mm/s during "
                        + from + " through " + to + " [T1].", false, "T1")));
        var first = mvc.perform(request("Find machine 12."))
                .andExpect(status().isOk()).andReturn();
        String id = json.readTree(first.getResponse().getContentAsString()).path("conversationId").asString();
        for (String question : List.of("What is its latest vibration reading?", "And what about last week?")) {
            mvc.perform(post("/api/agent/chat").contentType("application/json")
                            .content(json.writeValueAsString(Map.of("question", question, "conversationId", id))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.conversationId").value(id))
                    .andExpect(jsonPath("$.insufficientEvidence").value(question.contains("last week") ? false : true));
        }
        verify(jdbc, times(1)).queryForList(anyString(), eq("12"), eq("SIM-012"));
        verify(queries).readings(machine, from, to, "vibration_mm_s", 100, 0);
        var prompts = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(9)).call(prompts.capture());
        for (int index : List.of(6, 8)) {
            String context = prompts.getAllValues().get(index).getInstructions().stream()
                    .map(org.springframework.ai.chat.messages.Message::getText).collect(java.util.stream.Collectors.joining("\n"));
            assertThat(context).contains("latest vibration reading", "Find machine 12.", "SIM-012", machine.toString(),
                    "And what about last week?");
        }
        assertThat(prompts.getAllValues().get(6).getSystemMessage().getText()).contains(from.toString(), to.toString());
        var synthesis = json.readTree(prompts.getAllValues().get(8).getUserMessage().getText());
        assertThat(synthesis.path("history").size()).isEqualTo(2);
        assertThat(synthesis.path("evidence").size()).isEqualTo(1);
        assertThat(synthesis.path("evidence").get(0).path("tool").asString()).isEqualTo("queryTelemetryRange");
    }

    @Test
    void conversationsDoNotShareIdentityOrHistoryAndUnknownIdsNeverCallModel() throws Exception {
        when(model.call(any(Prompt.class))).thenReturn(text("ready"), text(finalAnswer("Hello", false)));
        var first = service.chat("Remember machine " + machine, null);
        when(model.call(any(Prompt.class))).thenReturn(
                calls(tool("getMachineStatus", Map.of("machineId", machine.toString()))),
                text("ready"), text(finalAnswer("Which machine?", true)));
        var other = service.chat("What about its vibration?", null);
        assertThat(other.conversationId()).isNotEqualTo(first.conversationId());
        assertThat(other.evidence().getFirst().success()).isFalse();
        verifyNoInteractions(queries);
        clearInvocations(model);
        for (String id : List.of(UUID.randomUUID().toString(), "not-a-uuid")) {
            mvc.perform(post("/api/agent/chat").contentType("application/json")
                            .content(json.writeValueAsString(Map.of("question", "Hello", "conversationId", id))))
                    .andExpect(status().is(id.equals("not-a-uuid") ? 400 : 404));
        }
        verifyNoInteractions(model);
    }

    @Test
    void failedModelTurnDoesNotEnterHistory() {
        when(model.call(any(Prompt.class))).thenReturn(text("ready"), text(finalAnswer("Hello", false)));
        var first = service.chat("Hello", null);
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("offline"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.chat("failed question", first.conversationId()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        clearInvocations(model);
        doReturn(text("ready"), text(finalAnswer("Hello again", false))).when(model).call(any(Prompt.class));
        service.chat("Try again", first.conversationId());
        var prompts = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(prompts.capture());
        assertThat(prompts.getAllValues().get(1).getUserMessage().getText()).contains("Hello").doesNotContain("failed question");
    }

    private ChatResponse calls(AssistantMessage.ToolCall... calls) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(calls)).build())));
    }

    private ChatResponse text(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private String finalAnswer(String answer, boolean insufficient, String... ids) {
        return json.writeValueAsString(Map.of("answer", answer, "insufficientEvidence", insufficient, "evidenceIds", List.of(ids)));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String question) {
        return post("/api/agent/chat").contentType("application/json").content(json.writeValueAsString(Map.of("question", question)));
    }
}
