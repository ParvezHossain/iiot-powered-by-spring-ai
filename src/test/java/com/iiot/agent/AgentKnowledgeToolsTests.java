package com.iiot.agent;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import com.iiot.rag.RagAnswerProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"simulator.enabled=false", "rag.enabled=false", "agent.enabled=false"})
@Transactional
class AgentKnowledgeToolsTests {
    @Autowired JdbcTemplate jdbc;
    private VectorStore vectors;
    private AgentKnowledgeTools tools;

    @BeforeEach
    void setup() {
        vectors = mock(VectorStore.class);
        tools = new AgentKnowledgeTools(vectors, new RagAnswerProperties("test", 6, 0.45), jdbc);
    }

    @Test
    void resolvesExistingNumericAliasesAndReturnsAllAmbiguousNameMatches() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO telemetry.machines (id, name) VALUES (?, 'SIM-012')", id);
        assertThat(tools.resolveMachine("12")).hasSize(1);
        assertThat(tools.resolveMachine("sim-012").getFirst().get("id")).isEqualTo(id);
        assertThat(tools.resolveMachine("999999")).isEmpty();
        jdbc.update("INSERT INTO telemetry.machines (id, name) VALUES (?, 'SIM-012')", UUID.randomUUID());
        assertThat(tools.resolveMachine("12")).hasSize(2);
        assertThat(tools.resolveMachine("' OR 1=1 --")).isEmpty();
    }

    @Test
    void retrievalIsCorpusScopedAndRejectsWrongErrorCodes() {
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                Document.builder().id("real").text("E204 means unavailable sensor signal.").build()));
        assertThat(tools.retrieveEquipmentKnowledge("what does E204 mean")).hasSize(1);
        assertThat(tools.retrieveEquipmentKnowledge("what does E2040 mean")).isEmpty();
        assertThat(tools.retrieveEquipmentKnowledge("compare E204 and E999")).isEmpty();
        var capture = org.mockito.ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectors, times(3)).similaritySearch(capture.capture());
        assertThat(capture.getValue().getTopK()).isEqualTo(6);
        assertThat(capture.getValue().getSimilarityThreshold()).isEqualTo(0.45);
        assertThat(capture.getValue().getFilterExpression().toString()).contains("corpus", "equipment-v1");
        assertThatThrownBy(() -> tools.retrieveEquipmentKnowledge(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}
