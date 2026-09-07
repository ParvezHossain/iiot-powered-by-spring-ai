package com.iiot.rag;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EquipmentSearchControllerTests {
    private VectorStore vectors;
    private EquipmentIngestionService ingestion;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        vectors = mock(VectorStore.class);
        ingestion = mock(EquipmentIngestionService.class);
        mvc = MockMvcBuilders.standaloneSetup(new EquipmentSearchController(vectors, ingestion)).build();
    }

    @Test
    void exposesSourceContentAndIngestionCounts() throws Exception {
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                new Document("Cooling screen cleaned", Map.of("document_id", "LOG-SIM-003"))));
        mvc.perform(get("/api/documents/search").param("query", "compressor cooling"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].text").value("Cooling screen cleaned"))
                .andExpect(jsonPath("$[0].metadata.document_id").value("LOG-SIM-003"));
        when(ingestion.ingest()).thenReturn(new EquipmentIngestionService.IngestionResult(10, 40, RagProperties.MODEL));
        mvc.perform(post("/api/documents/ingest")).andExpect(status().isOk())
                .andExpect(jsonPath("$.documents").value(10)).andExpect(jsonPath("$.chunks").value(40));
    }

    @Test
    void rejectsInvalidSearchParametersBeforeCallingModel() throws Exception {
        mvc.perform(get("/api/documents/search")).andExpect(status().isBadRequest());
        for (String query : List.of(" ", "x".repeat(2001))) {
            mvc.perform(get("/api/documents/search").param("query", query)).andExpect(status().isBadRequest());
        }
        for (String topK : List.of("0", "21", "invalid")) {
            mvc.perform(get("/api/documents/search").param("query", "pump").param("topK", topK))
                    .andExpect(status().isBadRequest());
        }
        for (String threshold : List.of("-0.1", "1.1", "NaN", "Infinity")) {
            mvc.perform(get("/api/documents/search").param("query", "pump").param("threshold", threshold))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(vectors);
    }
}
