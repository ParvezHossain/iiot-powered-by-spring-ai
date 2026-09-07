package com.iiot.simulator;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:simulator-scheduler;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "simulator.enabled=true",
        "simulator.machine-count=2",
        "simulator.interval-ms=100",
        "simulator.anomaly-every-ticks=2"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TelemetrySchedulerTests {
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void schedulerStartsAutomaticallyAndCommitsReadingsAndAnomalies() {
        // Read from a separate connection: uncommitted scheduler writes cannot satisfy this test.
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM telemetry.machines", Integer.class))
                    .isEqualTo(2);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(DISTINCT fault_code) FROM telemetry.machine_events
                    WHERE fault_code IN ('SIMULATED_SPIKE', 'SIMULATED_DROPOUT')
                    """, Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(DISTINCT metric_type) FROM telemetry.sensor_readings
                    """, Integer.class)).isEqualTo(7);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM telemetry.sensor_readings
                    WHERE metric_type = 'modbus_hr_40001' AND "value" = 65535
                    """, Integer.class)).isPositive();
        });
    }
}
