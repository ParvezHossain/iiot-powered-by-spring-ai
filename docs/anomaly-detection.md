# Rolling statistical anomaly detection

`GET /api/anomalies`, the agent's `getRecentAnomalies`, and the MCP tool of the
same name all use `RollingAnomalyDetector` through the shared telemetry query
service. No LLM is involved in scoring. See the [API contract](telemetry-api.md)
for fields, defaults, pagination, and reason codes.

The detector computes a causal rolling z-score per `(machine_id, metric_type)`
for temperature and vibration. Its fixed initial settings are 30 previous
samples, a 10-sample minimum, and a strict absolute-score cutoff of 4. The sample
standard deviation is floored at 0.5 °C or 0.05 mm/s. High and low deviations are
both reported, with the baseline statistics attached. A flat baseline therefore
remains usable. A high absolute value that is stable in that machine's history
need not be flagged, while a jump below the former global cutoff can be flagged.

The SQL window ends one row before the candidate; it does not use that candidate
or later samples to estimate the baseline. The preceding rows come from storage,
including up to 30 before the requested range. The implementation uses SQL
[window frames supported by PostgreSQL](https://www.postgresql.org/docs/current/functions-window.html)
and [H2](https://h2database.com/html/grammar.html#window_frame_clause).
Filtering to anomalies and applying pagination happens after scoring.

## Simulator acceptance dataset

`AnomalyDetectionAcceptanceTests` generates five machines at five-second
intervals for 360 ticks (30 minutes), separately with seeds 42, 137, and 2026.
This includes the simulator's normal load cycles, per-machine offsets, noise,
injected spikes, and dropouts. Every 12 ticks, one machine receives a fault;
spike and dropout events alternate. Each dataset has 15 spike events affecting
two metrics and 15 dropout events: 45 labeled anomalous readings.

Labels come from a join to `SIMULATED_SPIKE` / `SIMULATED_DROPOUT` events, never
from thresholds or detector output. Precision is TP/(TP+FP), and recall is
TP/(TP+FN), measured per reading. Each seed must achieve at least 90% precision
and 90% recall. H2 and PostgreSQL 17 both measured:

| Seed | True positives | False positives | False negatives | Precision | Recall |
| --- | ---: | ---: | ---: | ---: | ---: |
| 42 | 45 | 2 | 0 | 95.74% | 100% |
| 137 | 45 | 0 | 0 | 100% | 100% |
| 2026 | 44 | 3 | 1 | 93.62% | 97.78% |
| Pooled | 134 | 5 | 1 | 96.40% | 99.26% |

Reproduce the evaluation and edge cases:

```sh
./mvnw -Dtest=AnomalyDetectionAcceptanceTests,RollingAnomalyDetectorTests test
```

Regression tests also cover separate machine/metric baselines, low deviations,
flat windows, warm-up, sentinel detection, old-sample eviction, range and page
consistency, future-data exclusion, and deterministic timestamp ties. REST,
Spring AI callbacks, and MCP tests exercise the updated results. CI runs the
detector and acceptance tests against PostgreSQL as well as H2.
Local verification passed 79 tests (one skipped integration test), plus all 13
PostgreSQL detector, acceptance, and REST tests in an isolated database.

## Limits

This is a demo detector of deviations from history, not an assessment of safe
operation or a hardware diagnosis. Machine-specific manual limits still matter.
Warm-up and empty results do not prove health. The evaluation is synthetic and
does not establish field performance across other equipment or sampling regimes.

The window counts samples rather than elapsed time and does not reset across
long gaps. At the simulator's default cadence, a full window represents about
150 seconds. Prior outliers remain in the window and can inflate variance;
sustained changes gradually become the baseline. Periodic transitions can
produce false positives, as the measured results show. Missing signals without
an explicit sentinel are not detected. Energy would require a separate detector
on rate changes, rather than a z-score on the cumulative counter.

The query service computes detection from stored readings. The
[background alert worker](alerting.md) also runs it automatically for new data,
persists deduplicated alert records, and emits logs plus optional Gmail email.
Queries may scan earlier history to obtain each series' warm-up rows; large
deployments would need measured query tuning or incremental scoring.
