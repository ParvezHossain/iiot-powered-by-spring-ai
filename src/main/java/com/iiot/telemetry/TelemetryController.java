package com.iiot.telemetry;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Telemetry", description = "Machine readings and rolling statistical anomalies.")
@RestController
@RequestMapping("/api")
public class TelemetryController {
    private final TelemetryQueryService queries;

    public TelemetryController(TelemetryQueryService queries) {
        this.queries = queries;
    }

    @Operation(operationId = "getMachineStatus", summary = "Get current machine status",
            description = "Returns machine metadata and the latest reading per metric, ordered by metric type. latestReadings may be empty.")
    @ApiResponse(responseCode = "200", description = "Successful response")
    @ApiResponse(responseCode = "400", description = "Invalid UUID, timestamp, range, or pagination parameter", content = @Content)
    @ApiResponse(responseCode = "404", description = "Machine does not exist", content = @Content)
    @GetMapping("/machines/{id}/status")
    public TelemetryQueryService.MachineStatus status(@Parameter(description = "Stored machine UUID") @PathVariable UUID id) {
        return queries.status(id);
    }

    @Operation(operationId = "getMachineReadings", summary = "Get historical readings",
            description = "Inclusive time range; from must be at or before to. Results are ordered by timestamp then reading ID ascending. An empty array means no matching readings.")
    @ApiResponse(responseCode = "200", description = "Successful response")
    @ApiResponse(responseCode = "400", description = "Invalid UUID, timestamp, range, or pagination parameter", content = @Content)
    @ApiResponse(responseCode = "404", description = "Machine does not exist", content = @Content)
    @GetMapping("/machines/{id}/readings")
    public List<TelemetryQueryService.Reading> readings(
            @Parameter(description = "Stored machine UUID") @PathVariable UUID id, @Parameter(description = "Inclusive start, with UTC offset", example = "2026-09-21T00:00:00Z") @RequestParam OffsetDateTime from, @Parameter(description = "Inclusive end, with UTC offset", example = "2026-09-21T01:00:00Z") @RequestParam OffsetDateTime to,
            @Parameter(description = "Exact metric name", example = "vibration_mm_s", schema = @Schema(minLength = 1, maxLength = 64))
            @RequestParam(required = false) String metricType,
            @Parameter(description = "Maximum results", schema = @Schema(minimum = "1", maximum = "1000"))
            @RequestParam(defaultValue = "100") int limit, @Parameter(description = "Number of results to skip", schema = @Schema(minimum = "0"))
            @RequestParam(defaultValue = "0") int offset) {
        return queries.readings(id, from, to, metricType, limit, offset);
    }

    @Operation(operationId = "getAnomalies", summary = "Find rolling statistical anomalies",
            description = "Defaults to the preceding hour across all machines. Time bounds are inclusive. Ordered by timestamp then ID descending. Statistical detection requires baseline history; an empty array does not certify machine health.")
    @ApiResponse(responseCode = "200", description = "Successful response")
    @ApiResponse(responseCode = "400", description = "Invalid UUID, timestamp, range, or pagination parameter", content = @Content)
    @ApiResponse(responseCode = "404", description = "Machine does not exist", content = @Content)
    @GetMapping("/anomalies")
    public List<TelemetryQueryService.Anomaly> anomalies(
            @Parameter(description = "Restrict to a stored machine; omit for all machines") @RequestParam(required = false) UUID machineId,
            @Parameter(description = "Inclusive start; defaults to one hour before to", example = "2026-09-21T00:00:00Z")
            @RequestParam(required = false) OffsetDateTime from,
            @Parameter(description = "Inclusive end; defaults to current UTC time", example = "2026-09-21T01:00:00Z")
            @RequestParam(required = false) OffsetDateTime to,
            @Parameter(description = "Maximum results", schema = @Schema(minimum = "1", maximum = "1000"))
            @RequestParam(defaultValue = "100") int limit, @Parameter(description = "Number of results to skip", schema = @Schema(minimum = "0"))
            @RequestParam(defaultValue = "0") int offset) {
        return queries.anomalies(machineId, from, to, limit, offset);
    }
}
