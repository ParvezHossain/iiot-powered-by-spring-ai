package com.iiot.telemetry;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class TelemetryController {
    private final TelemetryQueryService queries;

    public TelemetryController(TelemetryQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/machines/{id}/status")
    public TelemetryQueryService.MachineStatus status(@PathVariable UUID id) {
        return queries.status(id);
    }

    @GetMapping("/machines/{id}/readings")
    public List<TelemetryQueryService.Reading> readings(
            @PathVariable UUID id, @RequestParam OffsetDateTime from, @RequestParam OffsetDateTime to,
            @RequestParam(required = false) String metricType,
            @RequestParam(defaultValue = "100") int limit, @RequestParam(defaultValue = "0") int offset) {
        return queries.readings(id, from, to, metricType, limit, offset);
    }

    @GetMapping("/anomalies")
    public List<TelemetryQueryService.Anomaly> anomalies(
            @RequestParam(required = false) UUID machineId,
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to,
            @RequestParam(defaultValue = "100") int limit, @RequestParam(defaultValue = "0") int offset) {
        return queries.anomalies(machineId, from, to, limit, offset);
    }
}
