CREATE TABLE machines (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    location VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'OFFLINE',
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_machines_name CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT ck_machines_status CHECK (
        status IN ('RUNNING', 'IDLE', 'STOPPED', 'MAINTENANCE', 'FAULTED', 'OFFLINE')
    )
);

CREATE TABLE sensor_readings (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    machine_id UUID NOT NULL,
    metric_type VARCHAR(64) NOT NULL,
    "value" DOUBLE PRECISION NOT NULL,
    "timestamp" TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_sensor_readings_machine FOREIGN KEY (machine_id) REFERENCES machines(id),
    CONSTRAINT ck_sensor_readings_metric CHECK (CHAR_LENGTH(TRIM(metric_type)) > 0)
);

CREATE INDEX ix_sensor_readings_machine_metric_time
    ON sensor_readings (machine_id, metric_type, "timestamp" DESC);
CREATE INDEX ix_sensor_readings_machine_time
    ON sensor_readings (machine_id, "timestamp" DESC);

CREATE TABLE machine_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    machine_id UUID NOT NULL,
    event_type VARCHAR(20) NOT NULL,
    "timestamp" TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    from_status VARCHAR(20),
    to_status VARCHAR(20),
    fault_code VARCHAR(64),
    description VARCHAR(2000),
    CONSTRAINT fk_machine_events_machine FOREIGN KEY (machine_id) REFERENCES machines(id),
    CONSTRAINT ck_machine_events_type CHECK (event_type IN ('STATUS_CHANGE', 'FAULT')),
    CONSTRAINT ck_machine_events_from_status CHECK (
        from_status IN ('RUNNING', 'IDLE', 'STOPPED', 'MAINTENANCE', 'FAULTED', 'OFFLINE')
    ),
    CONSTRAINT ck_machine_events_to_status CHECK (
        to_status IN ('RUNNING', 'IDLE', 'STOPPED', 'MAINTENANCE', 'FAULTED', 'OFFLINE')
    ),
    CONSTRAINT ck_machine_events_payload CHECK (
        (event_type = 'STATUS_CHANGE' AND from_status IS NOT NULL AND to_status IS NOT NULL
            AND from_status <> to_status AND fault_code IS NULL)
        OR
        (event_type = 'FAULT' AND fault_code IS NOT NULL AND CHAR_LENGTH(TRIM(fault_code)) > 0
            AND from_status IS NULL AND to_status IS NULL)
    )
);

CREATE INDEX ix_machine_events_machine_time ON machine_events (machine_id, "timestamp" DESC);
