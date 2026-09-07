package com.iiot.telemetry;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TelemetryQueryService {
    private final JdbcTemplate jdbc;

    public TelemetryQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
        String sql = """
                SELECT id, machine_id, metric_type, "value", "timestamp"
                FROM telemetry.sensor_readings WHERE "timestamp" >= ? AND "timestamp" <= ?
                AND ((metric_type = 'temperature_celsius' AND "value" > 90)
                  OR (metric_type = 'vibration_mm_s' AND "value" > 5)
                  OR (metric_type = 'modbus_hr_40001' AND "value" = 65535))
                """;
        var args = new ArrayList<Object>(List.of(from, to));
        if (machineId != null) {
            sql += " AND machine_id = ?";
            args.add(machineId);
        }
        sql += " ORDER BY \"timestamp\" DESC, id DESC LIMIT ? OFFSET ?";
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql, (rs, row) -> {
            var reading = reading(rs);
            String reason = switch (reading.metricType()) {
                case "temperature_celsius" -> "HIGH_TEMPERATURE";
                case "vibration_mm_s" -> "HIGH_VIBRATION";
                default -> "SENSOR_DROPOUT";
            };
            return new Anomaly(reading, reason);
        }, args.toArray());
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

    public record Reading(long id, UUID machineId, String metricType, double value, OffsetDateTime timestamp) {}
    public record MachineStatus(UUID id, String name, String location, String status, List<Reading> latestReadings) {}
    public record Anomaly(Reading reading, String reason) {}
}
