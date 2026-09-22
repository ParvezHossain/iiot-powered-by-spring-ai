package com.iiot.config;

import com.iiot.agent.AgentController;
import com.iiot.agent.AgentService;
import com.iiot.rag.*;
import com.iiot.telemetry.TelemetryController;
import com.iiot.telemetry.TelemetryQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = OpenApiDocumentationTests.ApiApplication.class, properties = {
        "rag.enabled=true", "agent.enabled=true", "springdoc.show-actuator=false",
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"})
@AutoConfigureMockMvc(addFilters = false)
class OpenApiDocumentationTests {
    @TestComponent
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({OpenApiConfiguration.class, TelemetryController.class, EquipmentSearchController.class,
            RagQueryController.class, AgentController.class})
    static class ApiApplication {}

    @Autowired MockMvc mvc;
    @MockitoBean TelemetryQueryService telemetry;
    @MockitoBean VectorStore vectors;
    @MockitoBean EquipmentIngestionService ingestion;
    @MockitoBean RagAnswerService answers;
    @MockitoBean AgentService agent;

    @Test
    void specificationIncludesEveryRestOperationAndDistinctAiSchemas() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("IIoT Powered by AI API"))
                .andExpect(jsonPath("$.paths.length()").value(7))
                .andExpect(jsonPath("$.paths['/api/machines/{id}/status'].get.responses['404']").exists())
                .andExpect(jsonPath("$.paths['/api/machines/{id}/readings'].get.parameters[4].schema.maximum").value(1000))
                .andExpect(jsonPath("$.paths['/api/anomalies'].get.operationId").value("getAnomalies"))
                .andExpect(jsonPath("$.paths['/api/documents/ingest'].post").exists())
                .andExpect(jsonPath("$.paths['/api/documents/search'].get").exists())
                .andExpect(jsonPath("$.paths['/api/rag/query'].post.responses['503']").exists())
                .andExpect(jsonPath("$.paths['/api/agent/chat'].post.responses['503']").exists())
                .andExpect(jsonPath("$.components.schemas.RagQuestion.properties.conversationId").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.AgentQuestion.properties.conversationId.format").value("uuid"))
                .andExpect(jsonPath("$.components.schemas.RagQuestion.properties.question.maxLength").value(2000))
                .andExpect(jsonPath("$.components.schemas.RagAnswer.properties.citations").exists())
                .andExpect(jsonPath("$.components.schemas.AgentConversationAnswer.properties.evidence").exists())
                .andExpect(jsonPath("$.security[0].bearerAuth").isArray());
    }

    @Test
    void swaggerUiAndYamlAreServed() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Swagger UI")));
        mvc.perform(get("/v3/api-docs.yaml")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("openapi:")));
    }
}
