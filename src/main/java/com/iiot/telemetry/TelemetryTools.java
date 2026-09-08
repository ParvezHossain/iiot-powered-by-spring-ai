package com.iiot.telemetry;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class TelemetryTools {
    private final TelemetryQueryService queries;

    public TelemetryTools(TelemetryQueryService queries) {
        this.queries = queries;
    }

    @Tool(description = "Read stored machine status and latest sample per metric. Status is not a health diagnosis. "
            + "Samples may be stale or have different timestamps; inspect timestamps before interpreting them.")
    public TelemetryQueryService.MachineStatus getMachineStatus(
            @ToolParam(description = "Existing machine UUID") UUID machineId) {
        return queries.status(machineId);
    }

    @Tool(description = "Read statistical anomalies, newest first: temperature and vibration deviations with absolute rolling z-score > 4 "
            + "against up to 30 earlier samples per machine/metric, requiring 10 prior samples. Includes baseline and score. "
            + "Also reports modbus_hr_40001 = 65535 (sensor dropout), even during warm-up. "
            + "Each matching reading is one anomaly, not a health verdict or root-cause diagnosis. "
            + "Defaults to the hour ending at to, or now UTC. Time bounds are inclusive. Results are paginated.")
    public List<TelemetryQueryService.Anomaly> getRecentAnomalies(
            @ToolParam(required = false, description = "Machine UUID; omit for all machines") UUID machineId,
            @ToolParam(required = false, description = "Inclusive ISO-8601 start with timezone; defaults to one hour before to") OffsetDateTime from,
            @ToolParam(required = false, description = "Inclusive ISO-8601 end with timezone; defaults to now UTC") OffsetDateTime to,
            @ToolParam(required = false, description = "Page size 1–1000; defaults to 100") Integer limit,
            @ToolParam(required = false, description = "Nonnegative number of rows to skip; defaults to 0") Integer offset) {
        return queries.anomalies(machineId, from, to, limit == null ? 100 : limit, offset == null ? 0 : offset);
    }

    @Tool(description = "Read raw telemetry for one machine over an inclusive time range, oldest first. "
            + "Optionally filter by metric. Returns at most limit rows; increase offset for subsequent pages. "
            + "Empty results mean no matching readings, not proof of healthy equipment.")
    public List<TelemetryQueryService.Reading> queryTelemetryRange(
            @ToolParam(description = "Existing machine UUID") UUID machineId,
            @ToolParam(description = "Inclusive ISO-8601 start with timezone, e.g. 2026-09-07T12:00:00Z") OffsetDateTime from,
            @ToolParam(description = "Inclusive ISO-8601 end with timezone; must be at or after from") OffsetDateTime to,
            @ToolParam(required = false, description = "Exact metric name, 1–64 characters; omit for all metrics") String metricType,
            @ToolParam(required = false, description = "Page size 1–1000; defaults to 100") Integer limit,
            @ToolParam(required = false, description = "Nonnegative number of rows to skip; defaults to 0") Integer offset) {
        return queries.readings(machineId, from, to, metricType, limit == null ? 100 : limit, offset == null ? 0 : offset);
    }
}
