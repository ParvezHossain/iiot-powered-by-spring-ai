# IIoT Powered by AI

[![CI](https://github.com/ParvezHossain/iiot-powered-by-ai/actions/workflows/ci.yml/badge.svg?branch=main&event=push)](https://github.com/ParvezHossain/iiot-powered-by-ai/actions/workflows/ci.yml)

Initial Spring Boot scaffold for an AI-powered Industrial IoT platform. More documentation will follow.

## Requirements

- JDK 21 or newer (Java 21 is the compilation target).
- Internet access on the first build to download Maven and dependencies.

## Run

```sh
./mvnw spring-boot:run
```

The application listens on port 8080. It includes Spring Web MVC, Data JPA,
Validation, Actuator, and an in-memory H2 database. No external database is required;
data is discarded on shutdown.

## Health check

```sh
curl --fail http://localhost:8080/actuator/health
```

Expected: HTTP 200 with a JSON body containing `"status":"UP"`. The health check includes database connectivity.

## Local development with Docker

Requires Docker Engine and the Docker Compose plugin. Start all three services:

```sh
docker compose up --build
```

Compose builds the app with Java 21 and starts PostgreSQL 17 with pgvector and
CPU-based Ollama. The `docker` Spring profile connects to `postgres:5432` and
`ollama:11434` on the Compose network. The app waits for healthy dependencies,
then checks the pgvector extension and Ollama's `/api/tags` endpoint during
startup. A failed connection stops application startup. No model is required
or downloaded automatically.

Verify the stack (or start in the background with `docker compose up -d --build --wait`):

```sh
docker compose ps
curl --fail http://localhost:8080/actuator/health/readiness
docker compose logs app
docker compose exec postgres psql -U iiot -d iiot -c "SELECT extversion FROM pg_extension WHERE extname = 'vector';"
curl --fail http://localhost:11434/api/tags
```

The app logs `Connected to PostgreSQL with pgvector` and `Connected to Ollama`
after successful startup checks. Readiness becomes `UP` after these checks;
they check startup connectivity, not continuous Ollama availability.

Host ports default to 8080 (app), 5432 (Postgres), and 11434 (Ollama), bound to
localhost. Override them if needed, for example:

```sh
APP_PORT=8081 POSTGRES_PORT=5433 OLLAMA_PORT=11435 docker compose up --build
```

The local database/user are `iiot`; the development password is `iiot_dev`,
overridable with `POSTGRES_PASSWORD`. Postgres data and Ollama models persist in
named volumes. The extension initialization script runs only when the database
volume is first created; changing the password does not update an existing database.

Stop the stack with `docker compose down`. To deliberately erase local database
data and downloaded models, use `docker compose down --volumes`.

## Telemetry schema

The [schema documentation and ERD](docs/telemetry-schema.md) describe machines,
sensor readings, and machine events. Flyway applies versioned migrations to the
`telemetry` schema at startup on both H2 and PostgreSQL.

## Telemetry simulator

The simulator starts automatically after the app becomes ready. It creates five
virtual machines (`SIM-001` through `SIM-005`) and writes readings every five
seconds. A five-minute run produces roughly 2,000 rows, with occasional injected
spikes and sensor dropouts. Stable machine UUIDs prevent duplicate machines across
restarts, and cumulative energy resumes from the last stored reading.

| Setting / environment variable | Default | Meaning |
| --- | --- | --- |
| `simulator.enabled` / `SIMULATOR_ENABLED` | `true` | Enable scheduled generation |
| `simulator.machine-count` / `SIMULATOR_MACHINE_COUNT` | `5` | Active virtual machines (1–1000) |
| `simulator.interval-ms` / `SIMULATOR_INTERVAL_MS` | `5000` | Delay between batches, minimum 100 ms |
| `simulator.anomaly-every-ticks` / `SIMULATOR_ANOMALY_EVERY_TICKS` | `12` | One affected machine every N batches, minimum 2 |
| `simulator.seed` / `SIMULATOR_SEED` | `42` | Repeatable noise and machine selection |

For example, `SIMULATOR_MACHINE_COUNT=10 ./mvnw spring-boot:run` runs ten machines.
The same environment variables work with `docker compose up --build`. Set
`SIMULATOR_ENABLED=false` to disable generation. Use one simulator-enabled app
instance per database. Reducing N stops sampling higher-numbered machines but
retains their history. H2 data disappears on exit; Compose Postgres persists it.

| Metric | Behavior |
| --- | --- |
| `temperature_celsius` | Typically 50–80 °C; slow load cycles and small noise; spikes add 45 °C |
| `vibration_mm_s` | Typically 1–3 mm/s, correlated with load; spikes add 10 mm/s |
| `energy_kwh` | Cumulative energy derived from machine power and sample interval; never resets on restart |
| `modbus_hr_40001` | Temperature × 10, or `65535` for an unavailable temperature sensor |
| `modbus_hr_40002` | Vibration × 100 |
| `modbus_hr_40003` | Load fraction × 1000 |
| `modbus_hr_40004` | Quality flags: 0 normal, 1 injected spike, 2 temperature dropout |

Registers are integral values in the unsigned 16-bit range, stored in the numeric
reading column. These are MODBUS-style holding-register samples, not a network
MODBUS server. Anomalies alternate spike/dropout at the configured cadence, so
default five-minute runs include both types without relying on chance alone.
A dropout omits the temperature row for that batch, while other sensors continue;
the sentinel and quality register provide explicit anomalous data points.
Fault events use `SIMULATED_SPIKE` and `SIMULATED_DROPOUT` for ground truth.
The following batch returns to normal. Energy does not accumulate simulated
consumption for downtime. Batch writes and their events share one transaction.

Automated tests cover 60 batches (five simulated minutes), register ranges,
dropouts, spikes, and energy continuity after restart. A separate scheduler
integration test starts the app with a 100 ms interval and verifies that readings
and both anomaly types are committed automatically, without calling the generator
from the test.

After leaving the Compose app running for five minutes, inspect recent data:

```sh
docker compose exec postgres psql -U iiot -d iiot
```

```sql
SELECT metric_type, COUNT(*), MIN("value"), MAX("value")
FROM telemetry.sensor_readings
WHERE "timestamp" >= CURRENT_TIMESTAMP - INTERVAL '5 minutes'
GROUP BY metric_type ORDER BY metric_type;

SELECT fault_code, COUNT(*) FROM telemetry.machine_events
WHERE fault_code LIKE 'SIMULATED_%'
  AND "timestamp" >= CURRENT_TIMESTAMP - INTERVAL '5 minutes'
GROUP BY fault_code;
```

## Raw telemetry API

Query machine status with `GET /api/machines/{id}/status`, time-bounded readings
with `GET /api/machines/{id}/readings?from=...&to=...`, and recent threshold-based
anomalies with `GET /api/anomalies`. See the [API reference](docs/telemetry-api.md)
for filters, pagination, thresholds, response fields, and curl examples.

## Sample equipment documents

The [synthetic equipment corpus](docs/equipment-corpus.md) contains ten Markdown
documents for RAG: five equipment manuals, three maintenance logs, an error-code
reference, and a telemetry register guide. Sources include machine metadata,
cross-references, and distinct troubleshooting cases. The corpus index includes
retrieval evaluation prompts for the later pipeline.

## Embedding ingestion and similarity search

The [RAG ingestion guide](docs/rag-ingestion.md) explains how to enable Spring AI
with pgvector and the local `nomic-embed-text:v1.5` model. With `RAG_ENABLED=true`,
startup embeds the bundled equipment corpus. `POST /api/documents/ingest`
refreshes it, and `GET /api/documents/search?query=...` returns matching chunks
with similarity scores and source metadata. RAG is disabled by default for H2.

## Grounded equipment answers

With RAG enabled and the chat model downloaded, `POST /api/rag/query` accepts
`{"question":"what does error E204 mean"}` and returns an answer with checked
source quotes and citations. Unknown codes return an insufficient-evidence
response. See the [RAG query guide](docs/rag-query.md) for setup and examples.

With `AGENT_ENABLED=true` and RAG enabled, `POST /api/agent/chat` lets the model
select telemetry queries, document retrieval, both, or neither. Answers include
the executed tool evidence and citation labels. See the [agent guide](docs/agent-chat.md)
for machine lookup, configuration, limits, and live routing checks.

## Verify

```sh
./mvnw verify
```

On Windows, use `mvnw.cmd` instead of `./mvnw`.

## Continuous integration

[CI](.github/workflows/ci.yml) builds and runs all tests with Java 21 and the Maven
Wrapper on every push and pull request. It also supports manual runs from GitHub
Actions. Maven dependencies are cached between runs. The full suite uses embedded
H2 and a local HTTP test server. CI also runs migration, constraint, and simulator tests against
a PostgreSQL 17 service container; no Ollama model is required.

The badge tracks push builds on `main`. Its repository URL is provisionally
`ParvezHossain/iiot-powered-by-ai`; update both badge links if the destination
differs. A passing badge requires publishing the project and a successful GitHub
Actions run; no remote is configured yet.

## License

[MIT](LICENSE).
