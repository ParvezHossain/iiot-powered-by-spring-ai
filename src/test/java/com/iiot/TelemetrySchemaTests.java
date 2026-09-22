package com.iiot;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "simulator.enabled=false")
@Transactional
class TelemetrySchemaTests {

	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private Flyway flyway;

	@Test
	void migrationIsAppliedAndValid() {
		assertThat(flyway.info().current().getVersion().toString()).isEqualTo("3");
		assertThat(flyway.info().pending()).isEmpty();
		flyway.validate();
	}

	@Test
	void storesReadingsAndBothEventTypesWithTheirMachine() {
		UUID machine = machine();
		OffsetDateTime measuredAt = OffsetDateTime.parse("2026-09-07T17:00:00+06:00");
		jdbc.update("""
				INSERT INTO telemetry.sensor_readings (machine_id, metric_type, "value", "timestamp")
				VALUES (?, 'temperature_celsius', 42.5, ?)
				""", machine, measuredAt);
		jdbc.update("""
				INSERT INTO telemetry.machine_events
				(machine_id, event_type, "timestamp", from_status, to_status)
				VALUES (?, 'STATUS_CHANGE', ?, 'OFFLINE', 'RUNNING')
				""", machine, measuredAt);
		jdbc.update("""
				INSERT INTO telemetry.machine_events (machine_id, event_type, "timestamp", fault_code)
				VALUES (?, 'FAULT', ?, 'OVERHEAT')
				""", machine, measuredAt);
		assertThat(jdbc.queryForObject("""
				SELECT r."value" FROM telemetry.sensor_readings r
				JOIN telemetry.machines m ON m.id = r.machine_id WHERE m.id = ?
				""", Double.class, machine)).isEqualTo(42.5);
		assertThat(jdbc.queryForObject("""
				SELECT "timestamp" FROM telemetry.sensor_readings WHERE machine_id = ?
				""", OffsetDateTime.class, machine).toInstant()).isEqualTo(measuredAt.toInstant());
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM telemetry.machine_events WHERE machine_id = ?",
				Integer.class, machine)).isEqualTo(2);
	}

	@Test
	void rejectsOrphanReading() {
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO telemetry.sensor_readings (machine_id, metric_type, "value", "timestamp")
				VALUES (?, 'temperature_celsius', 1, CURRENT_TIMESTAMP)
				""", UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void rejectsOrphanEvent() {
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO telemetry.machine_events (machine_id, event_type, "timestamp", fault_code)
				VALUES (?, 'FAULT', CURRENT_TIMESTAMP, 'OVERHEAT')
				""", UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void rejectsUnknownMachineStatus() {
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO telemetry.machines (id, name, status) VALUES (?, 'Test machine', 'UNKNOWN')",
				UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void rejectsStatusChangeWithoutDestination() {
		UUID machine = machine();
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO telemetry.machine_events (machine_id, event_type, "timestamp", from_status)
				VALUES (?, 'STATUS_CHANGE', CURRENT_TIMESTAMP, 'OFFLINE')
				""", machine)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void rejectsFaultWithoutCode() {
		UUID machine = machine();
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO telemetry.machine_events (machine_id, event_type, "timestamp")
				VALUES (?, 'FAULT', CURRENT_TIMESTAMP)
				""", machine)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void protectsMachineWithHistoricalReadingsFromDeletion() {
		UUID machine = machine();
		jdbc.update("""
				INSERT INTO telemetry.sensor_readings (machine_id, metric_type, "value", "timestamp")
				VALUES (?, 'temperature_celsius', 1, CURRENT_TIMESTAMP)
				""", machine);
		assertThatThrownBy(() -> jdbc.update("DELETE FROM telemetry.machines WHERE id = ?", machine))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	private UUID machine() {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO telemetry.machines (id, name) VALUES (?, 'Test machine')", id);
		return id;
	}
}
