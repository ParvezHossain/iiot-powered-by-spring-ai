package com.iiot.telemetry;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "simulator.enabled=false")
@Transactional
class RollingAnomalyDetectorTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired TelemetryQueryService queries;
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-09-07T12:00:00Z");

    @Test
    void learnsIndependentMachineAndMetricBaselinesAndDetectsBothDirections() {
        UUID hot = machine();
        UUID quiet = machine();
        for (int i = 30; i > 0; i--) {
            reading(hot, "temperature_celsius", 100, AT.minusSeconds(i));
            reading(hot, "vibration_mm_s", 20, AT.minusSeconds(i));
            reading(quiet, "vibration_mm_s", 1, AT.minusSeconds(i));
        }
        reading(hot, "temperature_celsius", 100, AT);
        reading(hot, "vibration_mm_s", 20, AT);
        assertThat(queries.anomalies(hot, AT, AT, 100, 0)).isEmpty();
        reading(quiet, "vibration_mm_s", 3, AT); // Below the old fixed threshold, far above this baseline.
        var quietSpike = queries.anomalies(quiet, AT, AT, 100, 0).getFirst();
        assertThat(quietSpike.reason()).isEqualTo("HIGH_VIBRATION");
        assertThat(quietSpike.baseline().mean()).isEqualTo(1);
        assertThat(quietSpike.baseline().standardDeviation()).isZero();
        assertThat(quietSpike.baseline().scale()).isEqualTo(0.05);
        reading(hot, "temperature_celsius", 90, AT.plusSeconds(1));
        reading(hot, "vibration_mm_s", 18, AT.plusSeconds(1));
        assertThat(queries.anomalies(hot, AT, AT.plusSeconds(1), 100, 0))
                .extracting(TelemetryQueryService.Anomaly::reason).containsExactly("LOW_VIBRATION", "LOW_TEMPERATURE");
    }

    @Test
    void requiresTenPriorSamplesButRecognizesDropoutWithoutWarmup() {
        UUID id = machine();
        for (int i = 9; i > 0; i--) reading(id, "temperature_celsius", 100, AT.minusSeconds(i));
        reading(id, "modbus_hr_40001", 65535, AT);
        var cold = queries.anomalies(id, AT.minusMinutes(1), AT, 100, 0);
        assertThat(cold).hasSize(1);
        assertThat(cold.getFirst().reason()).isEqualTo("SENSOR_DROPOUT");
        assertThat(cold.getFirst().baseline()).isNull();
        reading(id, "temperature_celsius", 100, AT);
        reading(id, "temperature_celsius", 120, AT.plusSeconds(1));
        var warm = queries.anomalies(id, AT.plusSeconds(1), AT.plusSeconds(1), 100, 0).getFirst();
        assertThat(warm.baseline().sampleCount()).isEqualTo(10);
        assertThat(warm.baseline().mean()).isEqualTo(100);
    }

    @Test
    void evictsOldSamplesAndPreservesScoresAcrossRangesPagesAndFutureReadings() {
        UUID id = machine();
        reading(id, "vibration_mm_s", 1000, AT.minusSeconds(31));
        for (int i = 30; i > 0; i--) reading(id, "vibration_mm_s", 2, AT.minusSeconds(i));
        reading(id, "vibration_mm_s", 3, AT);
        var before = queries.anomalies(id, AT, AT, 100, 0).getFirst();
        assertThat(before.baseline().mean()).isEqualTo(2);
        assertThat(before.baseline().sampleCount()).isEqualTo(30);
        assertThat(before.baseline().zScore()).isEqualTo(20);
        reading(id, "vibration_mm_s", 1000000, AT.plusSeconds(1));
        var full = queries.anomalies(id, AT.minusMinutes(1), AT.plusSeconds(1), 100, 0);
        assertThat(full).hasSize(2);
        assertThat(full.get(1)).isEqualTo(before);
        assertThat(queries.anomalies(id, AT, AT, 100, 0)).containsExactly(before);
        assertThat(queries.anomalies(id, AT.minusMinutes(1), AT.plusSeconds(1), 1, 1)).containsExactly(before);
    }

    @Test
    void usesStableIdOrderForTiesAndDoesNotScoreCumulativeOrDuplicateSignals() {
        UUID id = machine();
        for (int i = 0; i < 30; i++) reading(id, "temperature_celsius", 60, AT);
        reading(id, "temperature_celsius", 80, AT);
        for (String metric : new String[]{"energy_kwh", "modbus_hr_40002", "modbus_hr_40003", "modbus_hr_40004"}) {
            reading(id, metric, 1000000, AT);
        }
        var result = queries.anomalies(id, AT, AT, 100, 0);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().baseline().mean()).isEqualTo(60);
        assertThat(result.getFirst().baseline().zScore()).isEqualTo(40);
    }

    private UUID machine() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO telemetry.machines (id, name) VALUES (?, 'Statistical test')", id);
        return id;
    }

    private void reading(UUID machine, String metric, double value, OffsetDateTime at) {
        jdbc.update("INSERT INTO telemetry.sensor_readings (machine_id, metric_type, \"value\", \"timestamp\") VALUES (?, ?, ?, ?)",
                machine, metric, value, at);
    }
}
