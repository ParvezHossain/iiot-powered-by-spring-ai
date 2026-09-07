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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IiotPoweredByAiApplicationTests {

	@LocalServerPort
	private int port;

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
