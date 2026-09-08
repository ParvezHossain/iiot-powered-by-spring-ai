package com.iiot.telemetry;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/** Causal, sample-based detector; history is reconstructed from persisted readings on each query. */
final class RollingAnomalyDetector {
    private final JdbcTemplate jdbc;

    RollingAnomalyDetector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<TelemetryQueryService.Anomaly> detect(UUID machineId, OffsetDateTime from, OffsetDateTime to,
                                              int limit, int offset) {
        String machineFilter = machineId == null ? "" : " AND machine_id = ?";
        // Fetch warm-up rows before applying the requested range, and paginate only scored anomalies.
        // Fixed SQL fragments contain no user input. Ordering by timestamp/id excludes the candidate itself.
        String sql = """
                WITH prior AS (
                    SELECT id, machine_id, metric_type, "value", "timestamp",
                        ROW_NUMBER() OVER (PARTITION BY machine_id, metric_type ORDER BY "timestamp" DESC, id DESC) AS history_rank
                    FROM telemetry.sensor_readings WHERE "timestamp" < ?
                        AND metric_type IN ('temperature_celsius', 'vibration_mm_s') %s
                ), samples AS (
                    SELECT id, machine_id, metric_type, "value", "timestamp" FROM prior WHERE history_rank <= 30
                    UNION ALL
                    SELECT id, machine_id, metric_type, "value", "timestamp" FROM telemetry.sensor_readings
                    WHERE "timestamp" >= ? AND "timestamp" <= ?
                        AND metric_type IN ('temperature_celsius', 'vibration_mm_s', 'modbus_hr_40001') %s
                ), baselines AS (
                    SELECT samples.*, COUNT(*) OVER history AS sample_count,
                        AVG("value") OVER history AS baseline_mean,
                        STDDEV_SAMP("value") OVER history AS baseline_stddev
                    FROM samples
                    WINDOW history AS (PARTITION BY machine_id, metric_type ORDER BY "timestamp", id
                        ROWS BETWEEN 30 PRECEDING AND 1 PRECEDING)
                ), scores AS (
                    SELECT baselines.*,
                        GREATEST(COALESCE(baseline_stddev, 0),
                            CASE WHEN metric_type = 'temperature_celsius' THEN 0.5 ELSE 0.05 END) AS scale,
                        ("value" - baseline_mean) / GREATEST(COALESCE(baseline_stddev, 0),
                            CASE WHEN metric_type = 'temperature_celsius' THEN 0.5 ELSE 0.05 END) AS z_score
                    FROM baselines
                )
                SELECT * FROM scores WHERE "timestamp" >= ?
                    AND ((metric_type IN ('temperature_celsius', 'vibration_mm_s') AND sample_count >= 10 AND ABS(z_score) > 4)
                      OR (metric_type = 'modbus_hr_40001' AND "value" = 65535))
                ORDER BY "timestamp" DESC, id DESC LIMIT ? OFFSET ?
                """.formatted(machineFilter, machineFilter);
        var arguments = new ArrayList<Object>();
        arguments.add(from);
        if (machineId != null) arguments.add(machineId);
        arguments.add(from);
        arguments.add(to);
        if (machineId != null) arguments.add(machineId);
        arguments.add(from);
        arguments.add(limit);
        arguments.add(offset);
        return jdbc.query(sql, (rs, row) -> {
            var reading = new TelemetryQueryService.Reading(rs.getLong("id"), rs.getObject("machine_id", UUID.class),
                    rs.getString("metric_type"), rs.getDouble("value"), rs.getObject("timestamp", OffsetDateTime.class));
            if (reading.metricType().equals("modbus_hr_40001")) {
                return new TelemetryQueryService.Anomaly(reading, "SENSOR_DROPOUT", null);
            }
            double score = rs.getDouble("z_score");
            String reason = (score > 0 ? "HIGH_" : "LOW_")
                    + (reading.metricType().equals("temperature_celsius") ? "TEMPERATURE" : "VIBRATION");
            var baseline = new TelemetryQueryService.Baseline(rs.getInt("sample_count"), rs.getDouble("baseline_mean"),
                    rs.getDouble("baseline_stddev"), rs.getDouble("scale"), score, 4.0);
            return new TelemetryQueryService.Anomaly(reading, reason, baseline);
        }, arguments.toArray());
    }
}
