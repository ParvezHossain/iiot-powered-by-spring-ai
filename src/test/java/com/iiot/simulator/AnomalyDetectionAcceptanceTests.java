package com.iiot.simulator;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import com.iiot.telemetry.TelemetryQueryService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "simulator.enabled=false")
@Transactional
class AnomalyDetectionAcceptanceTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired TelemetryQueryService queries;

    @ParameterizedTest
    @ValueSource(longs = {42, 137, 2026})
    void detectsInjectedFaultsWithAtLeastNinetyPercentPrecisionAndRecall(long seed) {
        var simulator = new TelemetrySimulator(jdbc, new SimulatorProperties(5, 5000, 12, seed));
        Instant start = Instant.parse("2026-09-07T12:00:00Z");
        for (int tick = 1; tick <= 360; tick++) {
            simulator.generateAt(start.plusSeconds(tick * 5L));
        }
        OffsetDateTime from = start.atOffset(ZoneOffset.UTC);
        OffsetDateTime to = from.plusMinutes(30);
        // Labels come from simulator fault events only, never from a value threshold or detector scores.
        Set<Long> expected = new HashSet<>(jdbc.query("""
                SELECT r.id FROM telemetry.sensor_readings r JOIN telemetry.machine_events e
                  ON e.machine_id = r.machine_id AND e."timestamp" = r."timestamp"
                WHERE (e.fault_code = 'SIMULATED_SPIKE' AND r.metric_type IN ('temperature_celsius', 'vibration_mm_s'))
                   OR (e.fault_code = 'SIMULATED_DROPOUT' AND r.metric_type = 'modbus_hr_40001')
                """, (rs, row) -> rs.getLong(1)));
        var detected = queries.anomalies(null, from, to, 1000, 0);
        long truePositives = detected.stream().filter(a -> expected.contains(a.reading().id())).count();
        long falsePositives = detected.size() - truePositives;
        long falseNegatives = expected.size() - truePositives;
        double precision = (double) truePositives / Math.max(1, detected.size());
        double recall = (double) truePositives / expected.size();
        System.out.printf("Detector acceptance seed=%d: TP=%d FP=%d FN=%d precision=%.4f recall=%.4f%n",
                seed, truePositives, falsePositives, falseNegatives, precision, recall);
        assertThat(expected).hasSize(45); // 15 spikes × two metrics, plus 15 dropouts.
        assertThat(precision).isGreaterThanOrEqualTo(0.90);
        assertThat(recall).isGreaterThanOrEqualTo(0.90);
        assertThat(detected).anyMatch(a -> a.reason().equals("SENSOR_DROPOUT"));
        assertThat(detected).filteredOn(a -> a.baseline() != null)
                .allSatisfy(a -> assertThat(Math.abs(a.baseline().zScore())).isGreaterThan(4));
    }
}
