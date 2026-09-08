package com.iiot.telemetry;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {"simulator.enabled=false", "rag.enabled=false"})
@Transactional
class TelemetryToolsTests {
    @Autowired TelemetryTools tools;
    @Autowired @Qualifier("telemetryToolCallbackProvider") ToolCallbackProvider provider;
    @Autowired JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();
    private UUID machine;
    private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-09-07T12:00:00Z");
    private static final OffsetDateTime TO = FROM.plusMinutes(1);

    @BeforeEach
    void seed() {
        machine = UUID.randomUUID();
        jdbc.update("INSERT INTO telemetry.machines (id, name, status) VALUES (?, 'Tool test press', 'RUNNING')", machine);
        for (int sample = 30; sample > 0; sample--) {
            reading("temperature_celsius", 90, FROM.minusMinutes(sample));
            reading("vibration_mm_s", 2, FROM.minusMinutes(sample));
        }
        reading("temperature_celsius", 90, FROM);
        reading("temperature_celsius", 100, TO);
        reading("vibration_mm_s", 6, TO);
        reading("modbus_hr_40001", 65535, TO);
    }

    @Test
    void allThreeToolsAreDirectlyCallableWithoutAModel() {
        var status = tools.getMachineStatus(machine);
        assertThat(status.status()).isEqualTo("RUNNING");
        assertThat(status.latestReadings()).hasSize(3);
        assertThat(tools.queryTelemetryRange(machine, FROM, TO, "temperature_celsius", null, null))
                .extracting(TelemetryQueryService.Reading::value).containsExactly(90.0, 100.0);
        assertThat(tools.getRecentAnomalies(machine, FROM, TO, null, null))
                .extracting(TelemetryQueryService.Anomaly::reason)
                .containsExactly("SENSOR_DROPOUT", "HIGH_VIBRATION", "HIGH_TEMPERATURE");
    }

    @Test
    void registersNamedCallbacksWithRequiredAndOptionalSchemaArguments() {
        assertThat(provider.getToolCallbacks()).extracting(c -> c.getToolDefinition().name())
                .containsExactlyInAnyOrder("getMachineStatus", "getRecentAnomalies", "queryTelemetryRange");
        var schema = json.readTree(callback("queryTelemetryRange").getToolDefinition().inputSchema());
        assertThat(schema.path("required").toString()).contains("machineId", "from", "to")
                .doesNotContain("metricType", "limit", "offset");
        assertThat(schema.path("properties").path("from").path("type").asString()).isEqualTo("string");
        assertThat(json.readTree(callback("getRecentAnomalies").getToolDefinition().inputSchema())
                .path("required").isEmpty()).isTrue();
    }

    @Test
    void invokesEveryRegisteredCallbackWithJsonAndSerializesResults() {
        var status = json.readTree(callback("getMachineStatus").call(body(Map.of("machineId", machine.toString()))));
        assertThat(status.path("id").asString()).isEqualTo(machine.toString());
        assertThat(status.path("latestReadings").size()).isEqualTo(3);

        var range = json.readTree(callback("queryTelemetryRange").call(body(Map.of(
                "machineId", machine.toString(), "from", "2026-09-07T18:00:00+06:00", "to", TO.toString(),
                "metricType", "temperature_celsius", "limit", 1, "offset", 1))));
        assertThat(range.size()).isEqualTo(1);
        assertThat(range.get(0).path("value").asDouble()).isEqualTo(100);
        assertThat(OffsetDateTime.parse(range.get(0).path("timestamp").asString()).toInstant()).isEqualTo(TO.toInstant());

        var anomalies = json.readTree(callback("getRecentAnomalies").call(body(Map.of(
                "machineId", machine.toString(), "to", TO.toString(), "limit", 1, "offset", 1))));
        assertThat(anomalies.size()).isEqualTo(1);
        assertThat(anomalies.get(0).path("reason").asString()).isEqualTo("HIGH_VIBRATION");
    }

    @Test
    void optionalArgumentsDefaultAndEmptyResultsRemainEmpty() {
        var now = OffsetDateTime.now();
        reading("vibration_mm_s", 21, now.minusMinutes(1));
        reading("vibration_mm_s", 22, now.plusHours(2));
        var recent = json.readTree(callback("getRecentAnomalies").call(body(Map.of("machineId", machine.toString()))));
        assertThat(recent.size()).isEqualTo(1);
        assertThat(recent.get(0).path("reading").path("value").asDouble()).isEqualTo(21);
        var range = json.readTree(callback("queryTelemetryRange").call(body(Map.of(
                "machineId", machine.toString(), "from", FROM.toString(), "to", TO.toString()))));
        assertThat(range.size()).isEqualTo(4);
        assertThat(tools.queryTelemetryRange(machine, FROM, TO, "absent_metric", null, null)).isEmpty();
    }

    @Test
    void directCallsEnforceApiValidationAndUnknownMachineBehavior() {
        assertBadRequest(() -> tools.getMachineStatus(null));
        assertBadRequest(() -> tools.queryTelemetryRange(machine, null, TO, null, null, null));
        assertBadRequest(() -> tools.queryTelemetryRange(machine, TO, FROM, null, null, null));
        assertBadRequest(() -> tools.queryTelemetryRange(machine, FROM, TO, " ", null, null));
        for (int limit : new int[]{0, 1001}) {
            assertBadRequest(() -> tools.getRecentAnomalies(machine, FROM, TO, limit, null));
        }
        assertBadRequest(() -> tools.getRecentAnomalies(machine, FROM, TO, null, -1));
        assertThatThrownBy(() -> tools.getMachineStatus(UUID.randomUUID()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void callbacksRejectMalformedAndMissingInputsInsteadOfReturningData() {
        for (String input : new String[]{"{}", "{\"machineId\":\"not-a-uuid\"}"}) {
            assertThatThrownBy(() -> callback("getMachineStatus").call(input)).isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> callback("queryTelemetryRange").call(body(Map.of(
                "machineId", machine.toString(), "from", "not-a-time", "to", TO.toString()))))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> callback("getRecentAnomalies").call("{\"limit\":1001}"))
                .hasCauseInstanceOf(ResponseStatusException.class);
    }

    private ToolCallback callback(String name) {
        return Arrays.stream(provider.getToolCallbacks()).filter(c -> c.getToolDefinition().name().equals(name))
                .findFirst().orElseThrow();
    }

    private String body(Map<String, Object> values) {
        return json.writeValueAsString(values);
    }

    private void reading(String metric, double value, OffsetDateTime time) {
        jdbc.update("INSERT INTO telemetry.sensor_readings (machine_id, metric_type, \"value\", \"timestamp\") VALUES (?, ?, ?, ?)",
                machine, metric, value, time);
    }

    private void assertBadRequest(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
