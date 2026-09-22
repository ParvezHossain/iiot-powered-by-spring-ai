package com.iiot;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "simulator.enabled=false")
class IiotPoweredByAiApplicationTests {

	@LocalServerPort
	private int port;

    @Test
    void defaultDocumentationIncludesTelemetryAndHealthButNotDisabledAi() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/v3/api-docs")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            var paths = JsonMapper.builder().build().readTree(response.body()).path("paths");
            assertThat(paths.has("/api/machines/{id}/status")).isTrue();
            assertThat(paths.has("/actuator/health")).isTrue();
            assertThat(paths.has("/api/rag/query")).isFalse();
            assertThat(paths.has("/api/agent/chat")).isFalse();
            assertThat(paths.has("/api/documents/search")).isFalse();
        }
    }

	@Test
	void healthEndpointReportsUp() throws Exception {
		try (var client = HttpClient.newHttpClient()) {
			var request = HttpRequest.newBuilder(
					URI.create("http://localhost:" + port + "/actuator/health")).GET().build();
			var response = client.send(request, HttpResponse.BodyHandlers.ofString());

			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(JsonMapper.builder().build().readTree(response.body()).path("status").asString())
					.isEqualTo("UP");
		}
	}
}
