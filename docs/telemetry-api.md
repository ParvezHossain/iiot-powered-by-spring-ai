# Raw telemetry query API

All endpoints use GET and return JSON. Machine IDs are UUIDs from
`telemetry.machines`. Timestamps must be ISO-8601 with a timezone, such as
`2026-09-07T12:00:00Z`. When using positive offsets, URL-encode the `+`.

| Endpoint | Parameters | Response |
| --- | --- | --- |
| `/api/machines/{id}/status` | None | Machine `id`, `name`, `location`, `status`, and `latestReadings` array |
| `/api/machines/{id}/readings` | Required `from`, `to`; optional `metricType`, `limit`, `offset` | Array of raw readings, oldest first |
| `/api/anomalies` | Optional `machineId`, `from`, `to`, `limit`, `offset` | Array of `{ "reading": {...}, "reason": "...", "baseline": {...} }`, newest first |

A reading contains `id`, `machineId`, `metricType`, `value`, and `timestamp`.
Both time bounds are inclusive. Equal timestamps are ordered by reading ID in
the same direction as time. `limit` defaults to 100 and accepts 1–1000; `offset`
defaults to zero and must be nonnegative. Arrays contain at most `limit` items;
request another page by increasing `offset`. Pages reflect live data, so new
inserts can shift offsets, particularly for newest-first anomaly results.

Status is the stored machine status, not an inferred health diagnosis.
`latestReadings` contains one sample per metric, sorted by metric name; the
highest ID wins a timestamp tie. Samples may have different timestamps, and a
missing sensor retains its last reading. Check each sample's timestamp for
freshness. A machine with no readings returns an empty array.

Anomalies default to the hour ending at `to`, or at the current UTC time when
`to` is omitted. Explicit `from` overrides the default start. Rules are:

| Metric | Rule | Reason |
| --- | --- | --- |
| `temperature_celsius` | Absolute rolling z-score > 4 | `HIGH_TEMPERATURE` or `LOW_TEMPERATURE` |
| `vibration_mm_s` | Absolute rolling z-score > 4 | `HIGH_VIBRATION` or `LOW_VIBRATION` |
| `modbus_hr_40001` | Value = 65535 | `SENSOR_DROPOUT` |

The detector replaces the initial 90 °C / 5 mm/s cutoffs with a separate baseline
for each machine and metric. It uses the previous 30 readings, requires at least
10 prior readings, and excludes the candidate from its own baseline. The score is
`(value - mean) / max(sample standard deviation, noise floor)`, with floors of
0.5 °C and 0.05 mm/s to avoid division by zero and oversensitivity to tiny noise.
The floors are scale regularizers, not safe operating limits. Scores above 4 or
below -4 are flagged. No statistical flag is emitted during warm-up.

`baseline` contains `sampleCount`, `mean`, `standardDeviation`, `scale` (the
denominator after applying the floor), signed `zScore`, and `threshold` (4).
For a dropout, `baseline` is null: the explicit register sentinel is recognized
without warm-up. Each matching reading is one anomaly, so a simultaneous
temperature/vibration spike produces two results. Missing readings alone are not
detected as dropouts. Cumulative energy, duplicate converted registers, load and
quality registers are not statistically scored. The detector never reads fault
events or quality flags as labels; those are used only by acceptance tests.

Prior samples are fetched before the requested `from` time, and pagination is
applied after scoring. Narrowing the result range or changing pages does not reset
the baseline. Timestamp then ID defines sample order, including ties. Baselines
are reconstructed from persisted data, so app restarts preserve the detector's
history without in-memory state. Late/backfilled readings can change later scores.
See [detector evaluation and limitations](anomaly-detection.md).

Unknown machine IDs return HTTP 404, including an unknown `machineId` filter.
Malformed IDs/timestamps, missing required bounds, reversed ranges, blank or
overlong metric filters, and invalid pagination return HTTP 400. A valid query
without matching readings returns HTTP 200 and `[]`.

With Compose, use the configured app port (8080 by default):

```sh
curl "http://localhost:8080/api/machines/$MACHINE_ID/status"
curl --get "http://localhost:8080/api/machines/$MACHINE_ID/readings" \
  --data-urlencode 'from=2026-09-07T12:00:00Z' \
  --data-urlencode 'to=2026-09-07T12:05:00Z' \
  --data-urlencode 'metricType=temperature_celsius'
curl --get 'http://localhost:8080/api/anomalies' \
  --data-urlencode "machineId=$MACHINE_ID" --data-urlencode 'limit=20'
```

`TelemetryApiTests` exercises the MVC endpoints against seeded database rows,
with the simulator disabled and each test rolled back. The same tests run against
H2 locally and PostgreSQL in CI.

## Spring AI tools (T3.1)

`TelemetryTools` exposes the same read-only queries as public `@Tool` methods.
The Spring bean `telemetryToolCallbackProvider` registers all three through
`MethodToolCallbackProvider`. Registration is independent of `rag.enabled` and
does not require Ollama, embeddings, or an LLM loop.

| Tool | Required arguments | Optional arguments |
| --- | --- | --- |
| `getMachineStatus` | `machineId` | None |
| `getRecentAnomalies` | None | `machineId`, `from`, `to`, `limit`, `offset` |
| `queryTelemetryRange` | `machineId`, `from`, `to` | `metricType`, `limit`, `offset` |

JSON machine IDs are UUID strings; time bounds are ISO-8601 strings with a
timezone. Omitted or null optional arguments use the API defaults described
above. The tool descriptions and generated input schemas describe those
arguments. Validation and anomaly-window defaults are enforced in the shared
query service, including for calls made outside MVC.

Inject `TelemetryTools` to invoke a method directly:

```java
var status = tools.getMachineStatus(machineId);
var anomalies = tools.getRecentAnomalies(machineId, null, null, 20, 0);
var readings = tools.queryTelemetryRange(machineId, from, to, "temperature_celsius", 100, 0);
```

Or inject `@Qualifier("telemetryToolCallbackProvider") ToolCallbackProvider provider`
to invoke a registered callback with JSON, without a model:

```java
var callback = Arrays.stream(provider.getToolCallbacks())
        .filter(tool -> tool.getToolDefinition().name().equals("getMachineStatus"))
        .findFirst().orElseThrow();
String resultJson = callback.call("{\"machineId\":\"" + machineId + "\"}");
```

Direct calls return the same typed records as the API and throw
`ResponseStatusException` for invalid queries or unknown machines. Callback
calls serialize successful results as JSON and report conversion or invocation
errors as exceptions, not empty successful results. HTTP status codes apply only
when invoked through the HTTP API. A later agent can explicitly supply these
callbacks to its chat client; this task does not attach them to the RAG model.

`TelemetryToolsTests` verifies registration, generated schemas, direct calls,
JSON invocation of every callback, defaults, pagination, serialization, and
invalid inputs against seeded database rows with RAG and the simulator disabled.
