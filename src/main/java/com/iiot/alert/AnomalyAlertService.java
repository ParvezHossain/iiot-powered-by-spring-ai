package com.iiot.alert;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.iiot.telemetry.TelemetryQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(name = "alerts.enabled", havingValue = "true", matchIfMissing = true)
public class AnomalyAlertService {
    private static final Logger log = LoggerFactory.getLogger(AnomalyAlertService.class);
    private final TelemetryQueryService queries;
    private final JdbcTemplate jdbc;
    private final ObjectProvider<JavaMailSender> gmail;
    private final String from;
    private final String to;
    private final JsonMapper json = JsonMapper.builder().build();
    private volatile OffsetDateTime startedAt;

    public AnomalyAlertService(TelemetryQueryService queries, JdbcTemplate jdbc,
            @Qualifier("gmailAlertMailSender") ObjectProvider<JavaMailSender> gmail,
            @Value("${alerts.gmail.username:}") String from, @Value("${alerts.gmail.to:}") String to) {
        this.queries = queries;
        this.jdbc = jdbc;
        this.gmail = gmail;
        this.from = from;
        this.to = to;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        startedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    @Scheduled(fixedDelay = 1000, scheduler = "alertScheduler")
    public void detect() {
        if (startedAt == null) return;
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var begin = now.minusMinutes(1).isAfter(startedAt) ? now.minusMinutes(1) : startedAt;
        // Overlap catches recently committed samples; the primary key deduplicates repeated polls/restarts.
        for (int offset = 0; ; offset += 1000) {
            var anomalies = queries.anomalies(null, begin, now, 1000, offset);
            for (var anomaly : anomalies) {
                long id = anomaly.reading().id();
                if (jdbc.queryForObject("SELECT COUNT(*) FROM telemetry.anomaly_alerts WHERE reading_id = ?", Integer.class, id) != 0) continue;
                try {
                    jdbc.update("""
                            INSERT INTO telemetry.anomaly_alerts
                            (reading_id, payload, detected_at, email_status, next_attempt_at) VALUES (?, ?, ?, ?, ?)
                            """, id, json.writeValueAsString(anomaly), now, gmail.getIfAvailable() == null ? "NOT_REQUESTED" : "PENDING", now);
                }
                catch (DuplicateKeyException duplicate) {
                    continue;
                }
                log.warn("ANOMALY_ALERT readingId={} machine={} metric={} value={} reason={} sampledAt={} zScore={}",
                        id, anomaly.reading().machineId(), anomaly.reading().metricType(), anomaly.reading().value(),
                        anomaly.reason(), anomaly.reading().timestamp(), anomaly.baseline() == null ? null : anomaly.baseline().zScore());
            }
            if (anomalies.size() < 1000) break;
        }
    }

    @Scheduled(fixedDelay = 1000, scheduler = "alertScheduler")
    public void deliverEmails() {
        if (startedAt == null) return;
        var sender = gmail.getIfAvailable();
        if (sender == null) return;
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var pending = jdbc.query("""
                SELECT reading_id, payload, attempts FROM telemetry.anomaly_alerts
                WHERE email_status = 'PENDING' AND next_attempt_at <= ? ORDER BY detected_at, reading_id LIMIT 10
                """, (rs, row) -> new Pending(rs.getLong(1), rs.getString(2), rs.getInt(3)), now);
        for (var alert : pending) {
            try {
                var anomaly = json.readValue(alert.payload(), TelemetryQueryService.Anomaly.class);
                var message = new SimpleMailMessage();
                message.setFrom(from);
                message.setTo(to);
                message.setSubject("[IIoT] " + anomaly.reason() + " — machine " + anomaly.reading().machineId());
                message.setText("Anomaly alert for reading " + alert.id() + "\n\n" + alert.payload()
                        + "\n\nThis is a deviation from recent history, not a diagnosis or permission to restart equipment.");
                sender.send(message);
                jdbc.update("UPDATE telemetry.anomaly_alerts SET email_status = 'SENT', sent_at = ?, attempts = attempts + 1 WHERE reading_id = ?",
                        OffsetDateTime.now(ZoneOffset.UTC), alert.id());
                log.info("ANOMALY_EMAIL_SENT readingId={}", alert.id());
            }
            catch (RuntimeException failure) {
                int attempts = alert.attempts() + 1;
                jdbc.update("UPDATE telemetry.anomaly_alerts SET email_status = ?, attempts = ?, next_attempt_at = ? WHERE reading_id = ?",
                        attempts >= 5 ? "FAILED" : "PENDING", attempts, OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(30), alert.id());
                // Avoid SMTP exception text, which may contain addresses or connection/account details.
                log.warn("ANOMALY_EMAIL_FAILED readingId={} attempt={} retry={}", alert.id(), attempts, attempts < 5);
            }
        }
    }

    private record Pending(long id, String payload, int attempts) {}
}
