package com.iiot.simulator;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "simulator.enabled", havingValue = "true", matchIfMissing = true)
public class TelemetrySimulator {
    private static final Logger log = LoggerFactory.getLogger(TelemetrySimulator.class);
    private final JdbcTemplate jdbc;
    private final SimulatorProperties properties;
    private volatile boolean ready;
    private long tick;

    public TelemetrySimulator(JdbcTemplate jdbc, SimulatorProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        ready = true;
        log.info("Telemetry simulator enabled: {} machines, {} ms interval, anomaly every {} ticks",
                properties.machineCount(), properties.intervalMs(), properties.anomalyEveryTicks());
    }

    @Scheduled(fixedDelayString = "${simulator.interval-ms:5000}",
            initialDelayString = "${simulator.interval-ms:5000}")
    @Transactional
    public synchronized void generate() {
        if (!ready) {
            return;
        }
        generateAt(Instant.now());
    }

    // Called inside the scheduled transaction; package visibility enables deterministic tests.
    void generateAt(Instant instant) {
        long currentTick = ++tick;
        var random = new Random(properties.seed() + currentTick);
        boolean inject = currentTick % properties.anomalyEveryTicks() == 0;
        boolean dropout = (currentTick / properties.anomalyEveryTicks()) % 2 == 0;
        int affectedMachine = random.nextInt(properties.machineCount());
        OffsetDateTime timestamp = instant.atOffset(ZoneOffset.UTC);
        List<Object[]> readings = new ArrayList<>();

        for (int i = 0; i < properties.machineCount(); i++) {
            UUID id = machineId(i);
            ensureMachine(id, i, timestamp);
            boolean anomaly = inject && i == affectedMachine;
            // Slow load cycles, per-machine offsets, and bounded noise produce correlated signals.
            double phase = instant.getEpochSecond() / 60.0 + i * 0.8;
            double load = 0.55 + 0.20 * Math.sin(phase) + random.nextGaussian() * 0.02;
            load = Math.clamp(load, 0.2, 0.9);
            double temperature = 42 + i % 10 + 32 * load + random.nextGaussian() * 0.6;
            double vibration = 0.6 + 2.2 * load + random.nextGaussian() * 0.08;
            if (anomaly && !dropout) {
                temperature += 45;
                vibration += 10;
            }
            double powerKw = (4 + i % 8) * load;
            List<EnergySample> previous = jdbc.query("""
                    SELECT "value", "timestamp" FROM telemetry.sensor_readings
                    WHERE machine_id = ? AND metric_type = 'energy_kwh'
                    ORDER BY "timestamp" DESC, id DESC LIMIT 1
                    """, (rs, row) -> new EnergySample(rs.getDouble(1),
                    rs.getObject(2, OffsetDateTime.class).toInstant()), id);
            double seconds = previous.isEmpty() ? properties.intervalMs() / 1000.0
                    : Math.max(0, (instant.toEpochMilli() - previous.getFirst().at().toEpochMilli()) / 1000.0);
            // Do not invent energy consumption while the simulator was stopped.
            seconds = Math.min(seconds, properties.intervalMs() / 1000.0);
            double energy = (previous.isEmpty() ? 0 : previous.getFirst().value()) + powerKw * seconds / 3600;

            if (!(anomaly && dropout)) {
                add(readings, id, "temperature_celsius", temperature, timestamp);
            }
            add(readings, id, "vibration_mm_s", vibration, timestamp);
            add(readings, id, "energy_kwh", energy, timestamp);
            add(readings, id, "modbus_hr_40001", anomaly && dropout ? 65535 : Math.round(temperature * 10), timestamp);
            add(readings, id, "modbus_hr_40002", Math.round(vibration * 100), timestamp);
            add(readings, id, "modbus_hr_40003", Math.round(load * 1000), timestamp);
            add(readings, id, "modbus_hr_40004", anomaly ? (dropout ? 2 : 1) : 0, timestamp);
            if (anomaly) {
                String code = dropout ? "SIMULATED_DROPOUT" : "SIMULATED_SPIKE";
                jdbc.update("""
                        INSERT INTO telemetry.machine_events
                        (machine_id, event_type, "timestamp", fault_code, description)
                        VALUES (?, 'FAULT', ?, ?, ?)
                        """, id, timestamp, code, dropout
                        ? "Injected temperature sensor dropout; register 40001 reports sentinel 65535"
                        : "Injected temperature and vibration spike");
                log.info("Injected {} for simulator machine {}", code, id);
            }
        }
        jdbc.batchUpdate("""
                INSERT INTO telemetry.sensor_readings (machine_id, metric_type, "value", "timestamp")
                VALUES (?, ?, ?, ?)
                """, readings);
    }

    static UUID machineId(int index) {
        return UUID.nameUUIDFromBytes(("iiot-simulator-machine-" + index).getBytes(StandardCharsets.UTF_8));
    }

    private void ensureMachine(UUID id, int index, OffsetDateTime timestamp) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM telemetry.machines WHERE id = ?", Integer.class, id) == 0) {
            jdbc.update("INSERT INTO telemetry.machines (id, name, location, status) VALUES (?, ?, ?, 'RUNNING')",
                    id, "SIM-%03d".formatted(index + 1), "Virtual factory / line " + (index % 3 + 1));
            jdbc.update("""
                    INSERT INTO telemetry.machine_events
                    (machine_id, event_type, "timestamp", from_status, to_status, description)
                    VALUES (?, 'STATUS_CHANGE', ?, 'OFFLINE', 'RUNNING', 'Virtual machine initialized')
                    """, id, timestamp);
        }
    }

    private static void add(List<Object[]> rows, UUID id, String metric, double value, OffsetDateTime timestamp) {
        rows.add(new Object[]{id, metric, value, timestamp});
    }

    private record EnergySample(double value, Instant at) {
    }
}
