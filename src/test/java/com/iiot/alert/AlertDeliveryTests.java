package com.iiot.alert;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.iiot.telemetry.TelemetryQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"simulator.enabled=false", "alerts.enabled=false"})
@Transactional
class AlertDeliveryTests {
    @Autowired JdbcTemplate jdbc;

    @Test
    void deduplicatesPollsAndRetriesFailedGmailDeliveryWithoutReloggingOrResendingSuccess() {
        var queries = mock(TelemetryQueryService.class);
        var mail = mock(JavaMailSender.class);
        var factory = new DefaultListableBeanFactory();
        factory.registerSingleton("gmail", mail);
        var service = new AnomalyAlertService(queries, jdbc, factory.getBeanProvider(JavaMailSender.class),
                "demo@gmail.com", "operator@example.com");
        service.ready();
        var anomaly = new TelemetryQueryService.Anomaly(new TelemetryQueryService.Reading(987654, UUID.randomUUID(),
                "vibration_mm_s", 7.2, OffsetDateTime.now()), "HIGH_VIBRATION",
                new TelemetryQueryService.Baseline(30, 2, 0, 0.05, 104, 4));
        when(queries.anomalies(any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of(anomaly));
        service.detect();
        service.detect();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM telemetry.anomaly_alerts WHERE reading_id=987654", Integer.class)).isEqualTo(1);
        doThrow(new MailSendException("private smtp detail")).doNothing().when(mail).send(any(SimpleMailMessage.class));
        service.deliverEmails();
        assertThat(jdbc.queryForObject("SELECT email_status FROM telemetry.anomaly_alerts WHERE reading_id=987654", String.class)).isEqualTo("PENDING");
        service.deliverEmails(); // Backoff prevents an immediate second send.
        verify(mail, times(1)).send(any(SimpleMailMessage.class));
        jdbc.update("UPDATE telemetry.anomaly_alerts SET next_attempt_at = ? WHERE reading_id=987654", OffsetDateTime.now().minusSeconds(1));
        service.deliverEmails();
        service.deliverEmails();
        verify(mail, times(2)).send(any(SimpleMailMessage.class));
        assertThat(jdbc.queryForObject("SELECT email_status FROM telemetry.anomaly_alerts WHERE reading_id=987654", String.class)).isEqualTo("SENT");
    }

    @Test
    void gmailRequiresExplicitCredentialsAndVerifiedStartTlsWithTimeouts() {
        var config = new AlertConfiguration();
        assertThatThrownBy(() -> config.gmailAlertMailSender("", "", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.gmailAlertMailSender("demo@gmail.com", "", "operator@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        var sender = (JavaMailSenderImpl) config.gmailAlertMailSender("demo@gmail.com", "test-only", "operator@example.com");
        assertThat(sender.getHost()).isEqualTo("smtp.gmail.com");
        assertThat(sender.getPort()).isEqualTo(587);
        assertThat(sender.getJavaMailProperties()).containsEntry("mail.smtp.starttls.required", "true")
                .containsEntry("mail.smtp.ssl.checkserveridentity", "true").containsEntry("mail.smtp.timeout", "5000");
    }
}
