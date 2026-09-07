package com.iiot;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalServicesStartupCheckTests {

	private HttpServer server;
	private JdbcTemplate jdbc;
	private LocalServicesStartupCheck check;

	@BeforeEach
	void setUp() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.start();
		jdbc = mock(JdbcTemplate.class);
		when(jdbc.queryForObject(anyString(), eq(String.class))).thenReturn("0.8.6");
		check = new LocalServicesStartupCheck(jdbc,
				URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	@Test
	void acceptsHealthyServicesWithoutDownloadedModels() {
		respond(200, "{\"models\":[]}");
		assertThatCode(() -> check.run(null)).doesNotThrowAnyException();
	}

	@Test
	void rejectsMissingVectorExtension() {
		when(jdbc.queryForObject(anyString(), eq(String.class)))
				.thenThrow(new EmptyResultDataAccessException(1));
		assertThatThrownBy(() -> check.run(null)).isInstanceOf(EmptyResultDataAccessException.class);
	}

	@Test
	void rejectsUnhealthyOllama() {
		respond(503, "{\"error\":\"unavailable\"}");
		assertThatThrownBy(() -> check.run(null)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("HTTP 503");
	}

	@Test
	void rejectsUnexpectedOllamaResponse() {
		respond(200, "{}");
		assertThatThrownBy(() -> check.run(null)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("models array");
	}

	private void respond(int status, String body) {
		server.createContext("/api/tags", exchange -> {
			try (exchange) {
				byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "application/json");
				exchange.sendResponseHeaders(status, bytes.length);
				exchange.getResponseBody().write(bytes);
			}
		});
	}
}
