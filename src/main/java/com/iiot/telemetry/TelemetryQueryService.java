package com.iiot.telemetry;

import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TelemetryQueryService {
    private final JdbcTemplate jdbc;
    private final RollingAnomalyDetector detector;

    public TelemetryQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.detector = new RollingAnomalyDetector(jdbc);
    }

    public List<MachineStatus> machines() {
        // One query, including machines without readings; use the same tie-break as status().
        return jdbc.query("""
                SELECT m.id AS machine_id, m.name, m.location, m.status,
                       r.id AS reading_id, r.metric_type, r."value", r."timestamp"
                FROM telemetry.machines m
                LEFT JOIN (
                    SELECT s.*, ROW_NUMBER() OVER (
                        PARTITION BY machine_id, metric_type ORDER BY "timestamp" DESC, id DESC
                    ) AS reading_rank FROM telemetry.sensor_readings s
                ) r ON r.machine_id = m.id AND r.reading_rank = 1
                ORDER BY m.name, m.id, r.metric_type
                """, rs -> {
            var machines = new LinkedHashMap<UUID, MachineStatus>();
            while (rs.next()) {
                UUID id = rs.getObject("machine_id", UUID.class);
                var machine = machines.get(id);
                if (machine == null) {
                    machine = new MachineStatus(id, rs.getString("name"), rs.getString("location"),
                            rs.getString("status"), new ArrayList<>());
                    machines.put(id, machine);
                }
                if (rs.getObject("reading_id") != null) {
                    machine.latestReadings().add(new Reading(rs.getLong("reading_id"), id,
                            rs.getString("metric_type"), rs.getDouble("value"),
                            rs.getObject("timestamp", OffsetDateTime.class)));
                }
            }
            return machines.values().stream().map(m -> new MachineStatus(m.id(), m.name(), m.location(),
                    m.status(), List.copyOf(m.latestReadings()))).toList();
        });
    }

    public MachineStatus status(UUID id) {
        requireId(id);
        var machines = jdbc.query("SELECT id, name, location, status FROM telemetry.machines WHERE id = ?",
                (rs, row) -> new MachineStatus(id, rs.getString("name"), rs.getString("location"),
                        rs.getString("status"), List.of()), id);
        if (machines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Machine not found");
        }
        var latest = jdbc.query("""
                SELECT id, machine_id, metric_type, "value", "timestamp" FROM (
                    SELECT r.*, ROW_NUMBER() OVER (
                        PARTITION BY metric_type ORDER BY "timestamp" DESC, id DESC
                    ) AS rank FROM telemetry.sensor_readings r WHERE machine_id = ?
                ) ranked WHERE rank = 1 ORDER BY metric_type
                """, (rs, row) -> reading(rs), id);
        var machine = machines.getFirst();
        return new MachineStatus(id, machine.name(), machine.location(), machine.status(), latest);
    }

    public List<Reading> readings(UUID id, OffsetDateTime from, OffsetDateTime to,
                                  String metric, int limit, int offset) {
        requireId(id);
        validate(from, to, limit, offset);
        if (metric != null && (metric.isBlank() || metric.length() > 64)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "metricType must contain 1–64 characters");
        }
        requireMachine(id);
        String sql = """
                SELECT id, machine_id, metric_type, "value", "timestamp"
                FROM telemetry.sensor_readings WHERE machine_id = ? AND "timestamp" >= ? AND "timestamp" <= ?
                """;
        var args = new ArrayList<Object>(List.of(id, from, to));
        if (metric != null) {
            sql += " AND metric_type = ?";
            args.add(metric);
        }
        sql += " ORDER BY \"timestamp\", id LIMIT ? OFFSET ?";
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql, (rs, row) -> reading(rs), args.toArray());
    }

    public List<Anomaly> anomalies(UUID machineId, OffsetDateTime from, OffsetDateTime to,
                                    int limit, int offset) {
        to = to == null ? OffsetDateTime.now(ZoneOffset.UTC) : to;
        from = from == null ? to.minusHours(1) : from;
        validate(from, to, limit, offset);
        if (machineId != null) {
            requireMachine(machineId);
        }
        return detector.detect(machineId, from, to, limit, offset);
    }

    private static void requireId(UUID id) {
        if (id == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "machineId is required");
        }
    }

    private static void validate(OffsetDateTime from, OffsetDateTime to, int limit, int offset) {
        if (from == null || to == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from and to are required");
        }
        if (from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be at or before to");
        }
        if (limit < 1 || limit > 1000 || offset < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be 1–1000 and offset nonnegative");
        }
    }

    private void requireMachine(UUID id) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM telemetry.machines WHERE id = ?", Integer.class, id) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Machine not found");
        }
    }

    private static Reading reading(ResultSet rs) throws SQLException {
        return new Reading(rs.getLong("id"), rs.getObject("machine_id", UUID.class),
                rs.getString("metric_type"), rs.getDouble("value"),
                rs.getObject("timestamp", OffsetDateTime.class));
    }

    @Schema(name = "TelemetryReading", description = "One persisted sensor measurement.")
    public record Reading(
            @Schema(description = "Database reading ID") long id,
            @Schema(description = "Owning machine UUID") UUID machineId,
            @Schema(description = "Metric name, e.g. temperature_celsius, vibration_mm_s, or modbus_hr_40001") String metricType,
            @Schema(description = "Measured value in the units identified by metricType") double value,
            @Schema(description = "Measurement time with UTC offset") OffsetDateTime timestamp) {}
    @Schema(name = "MachineStatus", description = "Machine metadata with the newest reading for each metric.")
    public record MachineStatus(
            @Schema(description = "Machine UUID") UUID id,
            @Schema(description = "Display name, e.g. SIM-001") String name,
            @Schema(description = "Configured machine location") String location,
            @Schema(description = "Stored machine status") String status,
            @Schema(description = "Latest reading per metric; empty when no readings exist") List<Reading> latestReadings) {}
    @Schema(name = "AnomalyBaseline", description = "Rolling baseline from preceding samples, excluding the anomalous reading.")
    public record Baseline(
            @Schema(description = "Number of preceding baseline samples (10–30)") int sampleCount,
            @Schema(description = "Baseline arithmetic mean") double mean,
            @Schema(description = "Sample standard deviation") double standardDeviation,
            @Schema(description = "Standard deviation with the metric-specific noise floor applied") double scale,
            @Schema(description = "Signed deviation divided by scale") double zScore,
            @Schema(description = "Absolute z-score threshold, currently 4.0") double threshold) {}
    @Schema(name = "TelemetryAnomaly", description = "Detected statistical deviation or sensor dropout.")
    public record Anomaly(
            @Schema(description = "Reading that triggered detection") Reading reading,
            @Schema(description = "HIGH_TEMPERATURE, LOW_TEMPERATURE, HIGH_VIBRATION, LOW_VIBRATION, or SENSOR_DROPOUT") String reason,
            @Schema(description = "Rolling statistics; null for SENSOR_DROPOUT") Baseline baseline) {}
}
