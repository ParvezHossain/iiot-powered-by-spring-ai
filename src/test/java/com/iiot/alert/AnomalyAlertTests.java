package com.iiot.alert;

import java.time.Duration;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:alert-scheduler;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "simulator.enabled=true", "simulator.machine-count=1", "simulator.interval-ms=100", "simulator.anomaly-every-ticks=12",
        "alerts.enabled=true", "alerts.gmail.enabled=true", "alerts.gmail.username=demo@gmail.com",
        "alerts.gmail.app-password=test-only-password", "alerts.gmail.to=operator@example.com"
})
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AnomalyAlertTests {
    @Autowired JdbcTemplate jdbc;
    @MockitoBean(name = "gmailAlertMailSender") JavaMailSender mail;

    @Test
    void simulatorPushesVisibleAlertAndEmailWithinSecondsWithoutAnyAnomalyRequest(CapturedOutput output) {
        await().atMost(Duration.ofSeconds(7)).untilAsserted(() -> {
            assertThat(output.getOut()).contains("ANOMALY_ALERT", "HIGH_VIBRATION");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM telemetry.anomaly_alerts WHERE email_status = 'SENT'", Integer.class))
                    .isPositive();
        });
        var latencies = jdbc.query("""
                SELECT a.detected_at, r."timestamp" FROM telemetry.anomaly_alerts a
                JOIN telemetry.sensor_readings r ON r.id = a.reading_id
                """, (rs, row) -> Duration.between(rs.getObject(2, OffsetDateTime.class), rs.getObject(1, OffsetDateTime.class)));
        assertThat(latencies).isNotEmpty().allSatisfy(delay -> assertThat(delay).isLessThan(Duration.ofSeconds(3)));
        var sent = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail, atLeastOnce()).send(sent.capture());
        assertThat(sent.getAllValues()).allSatisfy(message -> {
            assertThat(message.getFrom()).isEqualTo("demo@gmail.com");
            assertThat(message.getTo()).containsExactly("operator@example.com");
            assertThat(message.getSubject()).startsWith("[IIoT]");
            assertThat(message.getText()).contains("reading", "machineId", "reason");
        });
        assertThat(sent.getAllValues()).extracting(SimpleMailMessage::getText).doesNotHaveDuplicates();
    }
}
