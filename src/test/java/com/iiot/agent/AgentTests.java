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
    private AgentService service;
    private MockMvc mvc;
    private final JsonMapper json = JsonMapper.builder().build();
    private final UUID machine = UUID.fromString("00000000-0000-0000-0000-000000000012");

    @BeforeEach
    void setup() {
        model = mock(ChatModel.class);
        queries = mock(TelemetryQueryService.class);
        vectors = mock(VectorStore.class);
        var knowledge = new AgentKnowledgeTools(vectors, new RagAnswerProperties("test", 6, 0.45), mock(JdbcTemplate.class));
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
