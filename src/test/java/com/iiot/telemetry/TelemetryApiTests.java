package com.iiot.telemetry;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "simulator.enabled=false")
@AutoConfigureMockMvc
@Transactional
class TelemetryApiTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.web.context.WebApplicationContext context;
    @Autowired com.iiot.auth.AuthService authentication;
    @Autowired com.iiot.auth.AuthProperties authProperties;
    private UUID machine;
    private UUID other;
    private static final String FROM = "2026-09-07T12:00:00Z";
    private static final String TO = "2026-09-07T12:01:00Z";

    @BeforeEach
    void seed() {
        String access = authentication.login(authProperties.initialAdminUsername(), authProperties.initialAdminPassword()).accessToken();
        mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .defaultRequest(get("/").header("Authorization", "Bearer " + access)).build();
        machine = UUID.randomUUID();
        other = UUID.randomUUID();
        for (UUID id : new UUID[]{machine, other}) {
            jdbc.update("INSERT INTO telemetry.machines (id, name, location, status) VALUES (?, 'Press', 'Line 1', 'RUNNING')", id);
            for (int sample = 30; sample > 0; sample--) {
                reading(id, "temperature_celsius", 90, OffsetDateTime.parse(FROM).minusMinutes(sample).toString());
                reading(id, "vibration_mm_s", 5, OffsetDateTime.parse(FROM).minusMinutes(sample).toString());
            }
        }
        reading(machine, "temperature_celsius", 90, "2026-09-07T11:59:59Z");
        reading(machine, "temperature_celsius", 90, FROM);
        reading(machine, "vibration_mm_s", 5, FROM);
        reading(machine, "temperature_celsius", 100, TO);
        reading(machine, "vibration_mm_s", 6, TO);
        reading(machine, "modbus_hr_40001", 65535, TO);
        reading(machine, "energy_kwh", 10000, TO);
        reading(other, "temperature_celsius", 110, TO);
    }

    @Test
    void statusReturnsStoredStateAndLatestPerMetricWithDeterministicTies() throws Exception {
        reading(machine, "temperature_celsius", 101, TO);
        mvc.perform(get("/api/machines/{id}/status", machine))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(machine.toString()))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.latestReadings.length()").value(4))
                .andExpect(jsonPath("$.latestReadings[2].metricType").value("temperature_celsius"))
                .andExpect(jsonPath("$.latestReadings[2].value").value(101));
    }

    @Test
    void readingsRespectInclusiveBoundsMetricMachineAndPagination() throws Exception {
        mvc.perform(get("/api/machines/{id}/readings", machine).param("from", FROM).param("to", TO)
                        .param("metricType", "temperature_celsius"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].value").value(90)).andExpect(jsonPath("$[1].value").value(100));
        mvc.perform(get("/api/machines/{id}/readings", machine)
                        .param("from", "2026-09-07T18:00:00+06:00").param("to", TO)
                        .param("metricType", "temperature_celsius").param("limit", "1").param("offset", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].value").value(100));
    }

    @Test
    void anomaliesUseRollingBaselinesWithoutRequiringFaultEvents() throws Exception {
        mvc.perform(get("/api/anomalies").param("from", FROM).param("to", TO))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].reading.machineId").value(other.toString()));
        mvc.perform(get("/api/anomalies").param("from", FROM).param("to", TO)
                        .param("machineId", machine.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].reason").value("SENSOR_DROPOUT"))
                .andExpect(jsonPath("$[1].reason").value("HIGH_VIBRATION"))
                .andExpect(jsonPath("$[1].baseline.sampleCount").value(30))
                .andExpect(jsonPath("$[1].baseline.mean").value(5.0))
                .andExpect(jsonPath("$[2].reason").value("HIGH_TEMPERATURE"));
        mvc.perform(get("/api/anomalies").param("from", FROM).param("to", TO)
                        .param("machineId", machine.toString()).param("limit", "1").param("offset", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].reason").value("HIGH_VIBRATION"));
    }

    @Test
    void defaultAnomalyWindowExcludesOldAndFutureReadings() throws Exception {
        jdbc.update("DELETE FROM telemetry.sensor_readings WHERE machine_id = ?", other);
        var now = OffsetDateTime.now();
        for (int sample = 30; sample > 0; sample--) {
            reading(other, "vibration_mm_s", 2, now.minusHours(3).minusMinutes(sample).toString());
        }
        reading(other, "vibration_mm_s", 20, now.minusHours(2).toString());
        reading(other, "vibration_mm_s", 21, now.minusMinutes(1).toString());
        reading(other, "vibration_mm_s", 22, now.plusHours(2).toString());
        mvc.perform(get("/api/anomalies").param("machineId", other.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].reading.value").value(21));
    }

    @Test
    void emptyMachineAndEmptyRangesReturnEmptyArrays() throws Exception {
        UUID empty = UUID.randomUUID();
        jdbc.update("INSERT INTO telemetry.machines (id, name) VALUES (?, 'Empty')", empty);
        mvc.perform(get("/api/machines/{id}/status", empty)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OFFLINE"))
                .andExpect(jsonPath("$.latestReadings.length()").value(0));
        mvc.perform(get("/api/machines/{id}/readings", empty).param("from", FROM).param("to", TO))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/anomalies").param("machineId", empty.toString()).param("from", FROM).param("to", TO))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    void rejectsInvalidInputsAndMissingMachines() throws Exception {
        UUID missing = UUID.randomUUID();
        mvc.perform(get("/api/machines/{id}/status", missing)).andExpect(status().isNotFound());
        mvc.perform(get("/api/machines/{id}/readings", missing).param("from", FROM).param("to", TO))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/anomalies").param("machineId", missing.toString())).andExpect(status().isNotFound());
        mvc.perform(get("/api/machines/not-a-uuid/status")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/machines/{id}/readings", machine)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/anomalies").param("from", "invalid")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/anomalies").param("from", TO).param("to", FROM)).andExpect(status().isBadRequest());
        for (String limit : new String[]{"0", "1001", "invalid"}) {
            mvc.perform(get("/api/anomalies").param("limit", limit)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/anomalies").param("offset", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/machines/{id}/readings", machine).param("from", FROM).param("to", TO)
                .param("metricType", " ")).andExpect(status().isBadRequest());
    }

    private void reading(UUID id, String metric, double value, String at) {
        jdbc.update("INSERT INTO telemetry.sensor_readings (machine_id, metric_type, \"value\", \"timestamp\") VALUES (?, ?, ?, ?)",
                id, metric, value, OffsetDateTime.parse(at));
    }
}
