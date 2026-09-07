package com.iiot.simulator;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "simulator.enabled=false")
@Transactional
class TelemetrySimulatorTests {
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void fiveMinutesProduceVariedDataAndBothAnomalyTypes() {
        var simulator = new TelemetrySimulator(jdbc, new SimulatorProperties(3, 5000, 12, 42));
        Instant start = Instant.parse("2026-09-07T12:00:00Z");
        for (int tick = 1; tick <= 60; tick++) {
            simulator.generateAt(start.plusSeconds(tick * 5L));
        }
        assertThat(count("SELECT COUNT(*) FROM telemetry.machines WHERE name LIKE 'SIM-%'")) .isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM telemetry.sensor_readings")).isEqualTo(3 * 60 * 7 - 2);
        assertThat(count("SELECT COUNT(DISTINCT metric_type) FROM telemetry.sensor_readings")).isEqualTo(7);
        assertThat(count("SELECT COUNT(DISTINCT \"value\") FROM telemetry.sensor_readings WHERE metric_type = 'temperature_celsius'"))
                .isGreaterThan(100);
        assertThat(count("SELECT COUNT(*) FROM telemetry.machine_events WHERE fault_code = 'SIMULATED_SPIKE'")) .isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM telemetry.machine_events WHERE fault_code = 'SIMULATED_DROPOUT'")) .isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM telemetry.sensor_readings WHERE metric_type = 'temperature_celsius' AND \"value\" > 95"))
                .isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM telemetry.sensor_readings WHERE metric_type = 'modbus_hr_40001' AND \"value\" = 65535"))
                .isEqualTo(2);
        assertThat(count("""
                SELECT COUNT(*) FROM telemetry.machine_events e
                JOIN telemetry.sensor_readings r ON e.machine_id = r.machine_id AND e."timestamp" = r."timestamp"
                WHERE e.fault_code = 'SIMULATED_DROPOUT' AND r.metric_type = 'temperature_celsius'
                """)).isZero();
        assertThat(count("""
                SELECT COUNT(*) FROM telemetry.sensor_readings
                WHERE metric_type LIKE 'modbus_hr_%'
                AND ("value" < 0 OR "value" > 65535 OR "value" <> FLOOR("value"))
                """)).isZero();
        assertThat(count("""
                SELECT COUNT(*) FROM (
                    SELECT "value", LAG("value") OVER (PARTITION BY machine_id ORDER BY "timestamp") AS previous
                    FROM telemetry.sensor_readings WHERE metric_type = 'energy_kwh'
                ) samples WHERE "value" < previous
                """)).isZero();
    }

    @Test
    void restartReusesMachinesAndContinuesEnergyCounter() {
        var properties = new SimulatorProperties(1, 5000, 12, 42);
        Instant start = Instant.parse("2026-09-07T12:00:00Z");
        new TelemetrySimulator(jdbc, properties).generateAt(start);
        double before = jdbc.queryForObject("SELECT MAX(\"value\") FROM telemetry.sensor_readings WHERE metric_type = 'energy_kwh'", Double.class);
        new TelemetrySimulator(jdbc, properties).generateAt(start.plusSeconds(3600));
        double after = jdbc.queryForObject("SELECT MAX(\"value\") FROM telemetry.sensor_readings WHERE metric_type = 'energy_kwh'", Double.class);
        assertThat(count("SELECT COUNT(*) FROM telemetry.machines WHERE name = 'SIM-001'")).isEqualTo(1);
        assertThat(after).isGreaterThan(before).isLessThan(before + 0.01);
        assertThat(count("SELECT COUNT(*) FROM telemetry.machine_events WHERE event_type = 'STATUS_CHANGE'")).isEqualTo(1);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
