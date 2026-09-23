package com.iiot.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"simulator.enabled=false", "alerts.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:spa-tests;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.web.resources.static-locations=classpath:/spa-fixtures/"})
@AutoConfigureMockMvc
class WebConfigTests {
    @Autowired MockMvc mvc;

    @Test
    void browserRoutesAndAssetsArePublic() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("index.html"));
        mvc.perform(get("/index.html")).andExpect(status().isOk()).andExpect(content().string(containsString("SPA routing fixture")));
        mvc.perform(get("/machines/pump-1").accept("text/html")).andExpect(status().isOk())
                .andExpect(content().string(containsString("SPA routing fixture")));
        mvc.perform(head("/machines/pump-1").accept("text/html")).andExpect(status().isOk());
        mvc.perform(get("/main-test.js")).andExpect(status().isOk())
                .andExpect(content().string(containsString("static fixture")));
    }

    @Test
    void missingAssetsAndBackendRoutesDoNotReturnSpa() throws Exception {
        mvc.perform(get("/missing.js").accept("text/html")).andExpect(status().isNotFound());
        mvc.perform(get("/api/unknown").accept("text/html")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/unknown").with(jwt()).accept("text/html")).andExpect(status().isNotFound());
        mvc.perform(get(java.net.URI.create("/%61pi/auth/me")).accept("text/html")).andExpect(status().isUnauthorized());
        mvc.perform(get("/mcp").accept("text/html")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/agent/unknown").with(jwt()).accept("text/html")).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/env").accept("text/html")).andExpect(status().isUnauthorized());
        mvc.perform(get("/unlisted-route")).andExpect(status().isUnauthorized());
        mvc.perform(post("/machines/pump-1").accept("text/html")).andExpect(status().isUnauthorized());
    }
}
