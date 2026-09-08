CREATE TABLE anomaly_alerts (
    reading_id BIGINT PRIMARY KEY,
    payload VARCHAR(16000) NOT NULL,
    detected_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    email_status VARCHAR(20) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    sent_at TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT ck_alert_email_status CHECK (email_status IN ('NOT_REQUESTED', 'PENDING', 'SENT', 'FAILED'))
);
CREATE INDEX ix_alert_email_pending ON anomaly_alerts (email_status, next_attempt_at);
