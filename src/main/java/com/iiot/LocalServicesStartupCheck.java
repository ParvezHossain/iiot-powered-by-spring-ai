package com.iiot;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@Profile("docker")
class LocalServicesStartupCheck implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(LocalServicesStartupCheck.class);
	private final JdbcTemplate jdbc;
	private final URI ollamaBaseUrl;

	LocalServicesStartupCheck(JdbcTemplate jdbc, @Value("${ollama.base-url}") URI ollamaBaseUrl) {
		this.jdbc = jdbc;
		this.ollamaBaseUrl = ollamaBaseUrl;
	}

	@Override
	public void run(ApplicationArguments args) throws Exception {
		String version = jdbc.queryForObject(
				"SELECT extversion FROM pg_extension WHERE extname = 'vector'", String.class);
		log.info("Connected to PostgreSQL with pgvector {}", version);

		try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
			var request = HttpRequest.newBuilder(ollamaBaseUrl.resolve("/api/tags"))
					.timeout(Duration.ofSeconds(10)).GET().build();
			var response = client.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200
					|| !JsonMapper.builder().build().readTree(response.body()).path("models").isArray()) {
				throw new IllegalStateException("Ollama startup check failed at " + ollamaBaseUrl
						+ ": expected HTTP 200 and a models array, received HTTP " + response.statusCode());
			}
			log.info("Connected to Ollama at {}", ollamaBaseUrl);
		}
	}
}
