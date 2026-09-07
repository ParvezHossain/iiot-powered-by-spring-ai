package com.iiot.rag;

import java.util.List;
import java.util.Map;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RagQueryTests {
    private VectorStore vectors;
    private ChatModel chat;
    private MockMvc mvc;
    private Document source;
    private static final String QUOTE = "E204 means the motor temperature sensor signal is unavailable";
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void setup() throws Exception {
        vectors = mock(VectorStore.class);
        chat = mock(ChatModel.class);
        var service = new RagAnswerService(vectors, chat, new RagAnswerProperties("test-model", 6, 0.45));
        mvc = MockMvcBuilders.standaloneSetup(new RagQueryController(service)).build();
        source = new EquipmentDocumentLoader("classpath:equipment/*.md").load().stream()
                .filter(d -> d.getMetadata().get("section").toString().startsWith("E204")).findFirst().orElseThrow();
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(source));
    }

    @Test
    void answersFromRetrievedE204DocumentWithServerResolvedCitation() throws Exception {
        respond(reply("E204 means the motor temperature sensor signal is unavailable.", "S1", QUOTE));
        mvc.perform(request("what does error E204 mean"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.insufficientEvidence").value(false))
                .andExpect(jsonPath("$.answer").value("E204 means the motor temperature sensor signal is unavailable."))
                .andExpect(jsonPath("$.citations[0].documentId").value("REF-FAULTS-001"))
                .andExpect(jsonPath("$.citations[0].source").value("fault-code-reference.md"))
                .andExpect(jsonPath("$.citations[0].quote").value(QUOTE));
        var captor = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(chat).call(captor.capture());
        assertThat(captor.getValue().getInstructions()).hasSize(2);
        assertThat(captor.getValue().getSystemMessage().getText()).contains("using only", "untrusted data");
        assertThat(captor.getValue().getUserMessage().getText()).contains("E204", source.getText().split("\n")[0]);
    }

    @Test
    void unknownCodesAndEmptyRetrievalNeverCallLanguageModel() throws Exception {
        mvc.perform(request("what does error E999 mean"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.insufficientEvidence").value(true))
                .andExpect(jsonPath("$.citations").isEmpty());
        mvc.perform(request("what does E2040 mean"))
                .andExpect(jsonPath("$.insufficientEvidence").value(true));
        mvc.perform(request("compare E204 and E999"))
                .andExpect(jsonPath("$.insufficientEvidence").value(true));
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        mvc.perform(request("How do I maintain the pump?"))
                .andExpect(jsonPath("$.insufficientEvidence").value(true));
        verifyNoInteractions(chat);
    }

    @Test
    void rejectsFabricatedCitationsQuotesAndMalformedModelOutput() throws Exception {
        for (String output : List.of(reply("A made-up answer", "S99", QUOTE),
                reply("A made-up answer", "S1", "E204 means a seized bearing"),
                "not json", "{}", "{\"insufficientEvidence\":true}",
                "{\"insufficientEvidence\":false,\"answer\":\"Uncited\",\"citations\":[]}")) {
            respond(output);
            mvc.perform(request("what does E204 mean"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.insufficientEvidence").value(true))
                    .andExpect(jsonPath("$.citations").isEmpty());
        }
    }

    @Test
    void rejectsRealQuotesThatDoNotCoverRequestedCodes() throws Exception {
        respond(reply("E204 means motor overheating.", "S1",
                "No sensor replacement interval or automatic reset timeout is specified."));
        mvc.perform(request("what does error E204 mean"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.insufficientEvidence").value(true))
                .andExpect(jsonPath("$.citations").isEmpty());

        respond(reply("E204 and A-301 both mean the sensor is unavailable.", "S1", QUOTE));
        mvc.perform(request("compare E204 and A-301"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.insufficientEvidence").value(true));
    }

    @Test
    void acceptsWhitespaceOnlyDifferencesInCopiedQuotes() throws Exception {
        respond(reply("The signal is unavailable.", "S1", QUOTE.replace("motor temperature", "motor\n temperature")));
        mvc.perform(request("What does e204 mean?"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.insufficientEvidence").value(false));
    }

    @Test
    void rejectsInvalidRequestsBeforeRetrieval() throws Exception {
        for (String body : List.of("{}", "{\"question\":null}", "{\"question\":\" \"}",
                json.writeValueAsString(Map.of("question", "x".repeat(2001))), "not-json")) {
            mvc.perform(post("/api/rag/query").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(vectors, chat);
    }

    @Test
    void dependencyFailuresReturn503InsteadOfPretendingNoEvidence() throws Exception {
        when(chat.call(any(Prompt.class))).thenThrow(new IllegalStateException("Model missing"));
        mvc.perform(request("What does E204 mean?" )).andExpect(status().isServiceUnavailable());
        when(vectors.similaritySearch(any(SearchRequest.class))).thenThrow(new IllegalStateException("Database unavailable"));
        mvc.perform(request("What does E204 mean?" )).andExpect(status().isServiceUnavailable());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String question) {
        return post("/api/rag/query").contentType("application/json")
                .content(json.writeValueAsString(Map.of("question", question)));
    }

    private String reply(String answer, String sourceId, String quote) {
        return json.writeValueAsString(Map.of("insufficientEvidence", false, "answer", answer,
                "citations", List.of(Map.of("sourceId", sourceId, "quote", quote))));
    }

    private void respond(String text) {
        when(chat.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
    }
}
