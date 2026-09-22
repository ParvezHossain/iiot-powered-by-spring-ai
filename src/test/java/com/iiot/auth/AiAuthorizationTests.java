package com.iiot.auth;

import java.util.UUID;

import com.iiot.agent.AgentService;
import com.iiot.rag.EquipmentIngestionService;
import com.iiot.rag.RagAnswerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real security/controller wiring with model and ingestion boundaries replaced by fixtures. */
@SpringBootTest(properties = {"simulator.enabled=false", "alerts.enabled=false", "rag.enabled=false", "agent.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:ai-authorization;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class AiAuthorizationTests {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired AuthProperties properties;
    @MockitoBean AgentService agent;
    @MockitoBean RagAnswerService rag;
    @MockitoBean VectorStore vectors;
    @MockitoBean EquipmentIngestionService ingestion;
    String admin;
    String user;

    @BeforeEach
    void login() {
        admin = auth.login(properties.initialAdminUsername(), properties.initialAdminPassword()).accessToken();
        String name = "ai-user-" + UUID.randomUUID();
        auth.create(name, name + "@example.test", "ai-test-password-2026", AuthRepository.Role.USER);
        user = auth.login(name, "ai-test-password-2026").accessToken();
    }

    @Test
    void userRequestsNeverReachAiOrToolExecution() throws Exception {
        for (String path : new String[]{"/api/agent/chat", "/api/rag/query", "/api/documents/ingest"}) {
            mvc.perform(post(path).header("Authorization", "Bearer " + user)
                    .contentType("application/json").content("{\"question\":\"What does E204 mean?\"}"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/documents/search").param("query", "E204").header("Authorization", "Bearer " + user))
                .andExpect(status().isForbidden());
        verifyNoInteractions(agent, rag, vectors, ingestion);
    }

    @Test
    void adminCanInvokeEveryAiController() throws Exception {
        when(vectors.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(java.util.List.of());
        for (String path : new String[]{"/api/agent/chat", "/api/rag/query", "/api/documents/ingest"}) {
            mvc.perform(post(path).header("Authorization", "Bearer " + admin)
                    .contentType("application/json").content("{\"question\":\"What does E204 mean?\"}"))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/documents/search").param("query", "E204").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        verify(agent).chat("What does E204 mean?", null);
        verify(rag).answer("What does E204 mean?");
        verify(ingestion).ingest();
        verify(vectors).similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class));
    }
}
