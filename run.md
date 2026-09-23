> **Angular workspace (Phases 1.1–3.1):** `./mvnw package` now builds `frontend/` and
> bundles the public UI at `http://localhost:8080/`. Docker builds include it too.
> Use `-Dskip.frontend=true` for backend-only Maven work. See
> [frontend/README.md](frontend/README.md) for setup and SPA routing details.
> The dark control-room layout includes live health status and a JWT sign-in dialog.
> Tokens remain in memory; reloading requires signing in again.
> Prompt 3.1 adds machine cards and live charts. Rebuild/restart Spring Boot for
> the new authenticated `GET /api/machines` endpoint.
> Existing API JWT and ADMIN requirements still apply.

# Run and Explore IIoT Powered by AI

> **Authentication setup:** REST APIs now require JWT bearer tokens. Before starting,
> set `AUTH_JWT_SECRET` and the three `AUTH_INITIAL_ADMIN_*` values in `.env` (Docker)
> or your shell environment (host execution). Follow [Authentication](docs/authentication.md)
> for registration/login, token refresh, admin APIs and complete configuration.
> Obtain `ACCESS_TOKEN` using that guide before running the business API examples below.
> Health and Swagger remain public. RAG, chat, document search and MCP require ADMIN JWTs.


Start with sections 1–8 to get the full stack running. Continue through the lab in
order; optional development, email, and integration-test exercises are marked.
Run commands from the repository root in **Bash on Linux, macOS, or WSL** unless
another location is stated. Keep the same terminal for variables used by later steps.

This guide follows the checked-out Compose files, configuration, Java code,
migrations, tests, and scripts. It covers the Swagger additions present in this
checkout. It does not assume that every change has already reached the remote repository.

> **Validation scope:** Compose configuration and the bundled document loader were
> checked locally: four full-stack services, 10 documents, and 34 chunks. The Java
> 21 verification run for this checkout passed with 85 tests passing and one
> optional PostgreSQL test skipped. Commands below were checked against the
> implementation; expected live results are not a claim that every exercise was
> executed while writing this guide.
>
> **Needs verification:** a fresh clone's remote accessibility, first-time Docker
> image/model downloads on your machine, live model answer quality, external MCP
> Inspector interoperability, and Gmail inbox delivery require the corresponding
> live checks below.

## 1. Project Overview

This is a local Industrial IoT demonstration. It generates fictional machine
measurements, detects unusual readings, retrieves equipment manuals, and uses a
local language model to answer questions with evidence.

After the full-stack setup you will have:

- A Spring Boot API with simulated machines, anomaly detection, alerts, and Swagger UI.
- PostgreSQL 17 with pgvector, storing measurements and searchable document embeddings.
- Ollama running local embedding and chat models on CPU.
- An AI chat endpoint and an authenticated MCP endpoint for compatible clients.

The project uses Java 21, Spring Boot 4.1.1, Spring AI 2.0.1, Flyway database
migrations, and the MCP Java SDK. There is no separate frontend application;
start exploring through curl and Swagger UI.

```text
curl / Swagger / chat client ──> Spring Boot app ──> PostgreSQL + pgvector
                                     │
                                     └──────────> Ollama models
MCP client ── ADMIN JWT ───────> /mcp ──> shared query and RAG services

Inside the app:
Simulator → measurements → rolling detector → logs + stored alerts
Equipment Markdown → chunks → embeddings → searchable vectors
Agent → telemetry tools and/or document retrieval → answer + evidence
```

Docker runs `postgres`, `ollama`, and `app`. A fourth service, `models`, installs
missing models and exits successfully. The simulator, detector, agent, MCP server,
and alert workers all live inside `app`; they are not separate containers.

AI features are optional for telemetry-only use. Gmail and MCP Inspector are
optional. No paid model API key is required. Internet access is needed initially
for cloning, Docker images, Maven dependencies, and model downloads; Inspector's
first `npx` invocation also downloads a package. Installed models perform local
inference. Gmail requires internet access whenever email is sent.

PostgreSQL and Ollama use persistent Docker volumes. Chat history is held in app
memory and disappears when the app restarts. The non-Docker H2 mode described in
section 17 also loses its database on shutdown.

Equipment manuals and readings are synthetic. Statistical deviations and generated
answers are demonstration output, not real equipment diagnoses.

## 2. Prerequisites

Install only the tools for the workflow you intend to use.

| Tool | Why / when needed | Docker full stack? | Verify |
| --- | --- | --- | --- |
| Git | Clone and inspect the repository | Yes for cloning; unnecessary for an extracted source archive | `git --version` |
| Docker Engine or Docker Desktop | Run the provided containers | Yes | `docker --version` and `docker info` |
| Modern Docker Compose plugin supporting `--wait` | Start the multi-service stack | Yes; use `docker compose`, not legacy `docker-compose` | `docker compose version` |
| Bash | Run this guide's shell examples | Yes for these examples | `bash --version` |
| OpenSSL | Generate the MCP secret | Yes for the full-stack setup shown here | `openssl version` |
| curl | Send health and API requests | Yes for this lab | `curl --version` |
| JDK 21 | Compile, run, and test Java on the host | No; Docker builds with its own JDK | `java -version` and `javac -version` |
| Maven Wrapper | Run the project's Maven version | Already included; no system Maven installation needed | After cloning: `./mvnw --version` |
| Python 3 | Deterministic demo and optional live verification scripts | No | `python3 --version` |
| Node.js and npm/npx | Optional MCP Inspector client only | No | `node --version`, `npm --version`, `npx --version` |

Use JDK 21 for the same baseline as the Dockerfile and CI. Maven Wrapper downloads
Maven 3.9.16 on first use. Python scripts use the standard library; no `pip install`
is needed. PostgreSQL, its `psql` client, and Ollama are supplied by Docker, so you
do not need host installations for the main workflow.

The optional Inspector currently requires Node 22.19.0 or newer; the project does
not pin its npm version. Check the [Inspector documentation](https://github.com/modelcontextprotocol/inspector/blob/main/clients/cli/README.md)
when installing it.

Allow several GB of free disk space for images, models, and data. The repository
does not define a minimum RAM/CPU requirement or configure GPU access. CPU model
responses can take minutes.

**Windows:** use WSL with Docker Desktop integration for this complete Bash lab.
Native Windows Maven uses `mvnw.cmd`; shell assignments, heredocs, and the Python
demo's POSIX process-group cleanup are not PowerShell commands. Native Windows
Compose uses `;` in `COMPOSE_FILE`; explicit `-f` arguments avoid this separator
issue. Keep one consistent shell and Docker context throughout the guide.

## 3. Clone the Repository

The configured `origin` in the inspected checkout is used below. Choose a parent
directory that does not already contain an `iiot-powered-by-ai` directory:

```bash
git clone https://github.com/ParvezHossain/iiot-powered-by-spring-ai.git iiot-powered-by-ai
cd iiot-powered-by-ai
ls -a
ls pom.xml mvnw Dockerfile docker-compose.yml docker-compose.ai.yml .env.example
```

You should see `src/`, `docs/`, `scripts/`, `.mvn/`, and the files listed above.
An existing checkout can skip cloning and simply enter its project directory.

> **Repository discrepancy:** `README.md` gives the clone URL ending in
> `iiot-powered-by-ai.git`; this checkout's `git remote -v` instead reports
> `iiot-powered-by-spring-ai.git`. The command above follows the actual remote and
> explicitly chooses the familiar local directory name. **Needs verification:**
> remote access or redirects if cloning fails; confirm the repository URL/access
> with its owner rather than assuming the two names are interchangeable.

## 4. Understand the Repository Before Running It

```text
iiot-powered-by-ai/
├── .env.example                 # Full-stack Compose settings to copy
├── .github/workflows/ci.yml     # Build, demo, and PostgreSQL checks
├── .mvn/wrapper/                # Maven download configuration
├── docker/postgres/init.sql     # Enable pgvector on a fresh database
├── docs/
│   ├── equipment/              # Ten synthetic Markdown source documents
│   ├── swagger-api.md          # Interactive API guide
│   └── ...                     # Telemetry, RAG, agent, MCP, and alert guides
├── scripts/
│   ├── demo.py                 # Isolated deterministic demo
│   ├── verify-rag-query.py      # Optional live RAG checks
│   └── verify-agent.py          # Optional live agent checks
├── src/main/java/com/iiot/      # Application and feature packages
├── src/main/resources/
│   ├── application.properties
│   ├── application-docker.properties
│   └── db/migration/           # Flyway V1–V3 SQL migrations
├── src/test/java/com/iiot/      # Automated tests and demo fixtures
├── docker-compose.yml         # Base services
├── docker-compose.ai.yml      # Full AI stack override
├── Dockerfile                 # Java build and runtime image
├── pom.xml                    # Dependencies, Java version, packaged documents
├── mvnw / mvnw.cmd             # Maven launchers
├── README.md
└── run.md
```

You do not need to understand Java internals yet. Start with `.env.example` and
the Compose files. `docs/equipment/` contains the content the AI can retrieve;
other `docs/` files explain the implementation. `target/`, when present, contains
build outputs and test reports and is ignored by Git.

## 5. Environment Configuration

### Required for the normal full-stack demo

Create `.env` once. This command preserves an existing file:

```bash
if [ -e .env ]; then
  printf '%s\n' '.env already exists; review it instead of overwriting it.'
else
  cp .env.example .env
fi
chmod 600 .env
```

Open `.env` in your editor. The provided values are:

```dotenv
COMPOSE_FILE=docker-compose.yml:docker-compose.ai.yml
APP_PORT=8080
POSTGRES_PORT=5432
OLLAMA_PORT=11434
POSTGRES_PASSWORD=iiot_dev
AGENT_MODEL=qwen2.5:1.5b
RAG_ANSWER_MODEL=qwen2.5:1.5b
ALERTS_GMAIL_ENABLED=false
```

Before startup, fill `AUTH_JWT_SECRET`, `AUTH_INITIAL_ADMIN_USERNAME`,
`AUTH_INITIAL_ADMIN_EMAIL`, and `AUTH_INITIAL_ADMIN_PASSWORD` in `.env`. Generate the JWT
secret with `openssl rand -base64 32` and keep it private. The initial admin
password must contain at least 12 characters and no more than 72 UTF-8 bytes.
These values have no working default; startup fails if required values are missing.
After an admin exists in PostgreSQL, bootstrap credentials can be removed; the JWT
secret remains required. See [Authentication](docs/authentication.md) for the full
configuration and login workflow.

`COMPOSE_FILE` selects both files automatically for subsequent `docker compose`
commands. MCP now uses the same ADMIN JWT obtained from `/api/auth/login` as the
other AI endpoints. A separate MCP API key is no longer required or accepted.

PostgreSQL database/user names are both `iiot`. `iiot_dev` is the repository's
local demo password, not a secret supplied by this guide. You can change
`POSTGRES_PASSWORD` **before the database volume is first initialized**. Changing
it later in `.env` does not update the password inside an existing database.

Compose sends the app to `postgres:5432` and `ollama:11434` on its private network.
Host port changes do not change these internal addresses.

### Optional configuration

Add or edit these keys only when needed:

| Setting | Checked-in default / effect |
| --- | --- |
| `APP_PORT`, `POSTGRES_PORT`, `OLLAMA_PORT` | `8080`, `5432`, `11434`; host ports bound to `127.0.0.1` |
| `AGENT_MODEL`, `RAG_ANSWER_MODEL` | `qwen2.5:1.5b`; bootstrap installs both if different |
| `RAG_INGEST_ON_STARTUP` | `true`; re-embed bundled equipment documents at startup |
| `RAG_BATCH_SIZE` | `16`; valid range 1–64 |
| `SIMULATOR_ENABLED` | `true` |
| `SIMULATOR_MACHINE_COUNT` | `5`; valid range 1–1000 |
| `SIMULATOR_INTERVAL_MS` | `5000`; minimum 100 |
| `SIMULATOR_ANOMALY_EVERY_TICKS` | `12`; minimum 2 |
| `SIMULATOR_SEED` | `42` |
| `ALERTS_ENABLED` | `true`; background detection, stored alerts, and delivery |
| `ALERTS_GMAIL_ENABLED` | `false`; enable only after section 15 |
| `GMAIL_USERNAME`, `GMAIL_APP_PASSWORD`, `ALERT_EMAIL_TO` | Required when enabling Gmail |

The AI override forces `RAG_ENABLED`, `AGENT_ENABLED`, and `MCP_ENABLED` to `true`.
Putting `false` in `.env` does not disable them while that override is selected.
The embedding model is fixed in Java as `nomic-embed-text:v1.5`, with 768 dimensions;
there is no configurable embedding-model key in the provided Compose files.

Compose's `.env` provides interpolation values; it does **not** automatically
forward every possible Spring property into the container. For example,
`OLLAMA_BASE_URL` is explicitly set to `http://ollama:11434` in Compose, and
`RAG_DOCUMENTS` is not forwarded. Customizing those requires a Compose edit or
override. Likewise, `.env` is not automatically loaded by `./mvnw spring-boot:run`.

Keep `.env`, JWT signing secrets, access tokens and Gmail app passwords out of Git. Verify the ignore rule:

```bash
git check-ignore .env
```

Expected: `.env`. Avoid posting a fully expanded `docker compose config` because
it contains interpolated credentials. Exported shell values override `.env`
interpolation; use a fresh terminal if old overrides are causing surprises.

For API exercises, set a **lab shell variable**, not an application setting:

```bash
BASE_URL=http://localhost:8080
```

Change it if you selected another `APP_PORT`. Recreate this variable when opening
a new terminal. Older docs' `8081`/`5433` examples are workspace-specific overrides;
the actual checked-in defaults are `8080`/`5432`.

## 6. Understand the Docker Compose Setup

`docker-compose.yml` defines the app, PostgreSQL/pgvector, and Ollama. AI routes
are disabled by default in this base file. `docker-compose.ai.yml` adds the
one-shot `models` helper and enables RAG, chat, and MCP in the app.

| Service | Purpose | Port: host → container | Persistent Data |
| --- | --- | --- | --- |
| `postgres` | PostgreSQL 17 and pgvector | `127.0.0.1:5432` → `5432` | `postgres_data`: database, telemetry, alerts, vectors |
| `ollama` | Local model server | `127.0.0.1:11434` → `11434` | `ollama_data`: installed models |
| `models` | Install missing embedding/chat models; then exit | None | Writes through Ollama into its model volume |
| `app` | REST, Swagger, simulator, RAG, agent, MCP, alerts | `127.0.0.1:8080` → `8080` | Business data in PostgreSQL; conversation memory is not persisted |

The `.env` port keys change only the host column. Docker normally prefixes volume
names with the Compose project name.

Startup dependencies:

```text
postgres healthy ────────────────────────┐
ollama healthy ──> models exits with 0 ────┼──> app startup and readiness
ollama healthy ──────────────────────────┘
```

PostgreSQL health checks both database readiness and the vector extension.
Ollama health runs `ollama list`; that alone does not prove required models exist.
The `models` helper supplies that separate check/download step.

**Optional lighter Docker mode:** explicitly use only the base file:

```bash
docker compose -f docker-compose.yml up --build -d --wait
```

With its default feature flags this provides telemetry and log alerts without
model downloads. **Ollama still starts**: the app depends on it and its Docker
startup check contacts it even when RAG is off. Use the same `-f` selection for
subsequent commands in this mode. Do not switch modes midway through this lab
unless intended; the remaining primary examples assume the full stack.

Volumes survive container stops, restarts, recreation, and ordinary `down`.
`down --volumes` deletes both database contents and downloaded models; see section 21.

## 7. First Full-Stack Startup

First validate the selected service names without displaying secrets:

```bash
docker compose config --services
```

Expected names, in any order: `postgres`, `ollama`, `models`, `app`. A missing-key
error means section 5 is incomplete. If `models` is missing, check `COMPOSE_FILE`.

Build and start the stack, waiting for readiness:

```bash
docker compose up --build -d --wait --wait-timeout 900
```

- `--build` builds the app image from the Dockerfile.
- `-d` leaves containers running in the background.
- `--wait` waits for services to be running/healthy; it also implies detached mode.
- `--wait-timeout 900` permits 15 minutes for the readiness wait. It is not a
  total deadline for all image builds and downloads. See the
  [Compose command reference](https://docs.docker.com/reference/cli/docker/compose/up/).

On a clean machine, startup proceeds as follows:

1. Download `pgvector/pgvector:pg17`, `ollama/ollama:latest`, and the app build/runtime
   bases `eclipse-temurin:21-jdk` and `eclipse-temurin:21-jre`.
2. Build the application with Maven Wrapper. The Docker build skips tests; section
   18 runs them separately. It packages `docs/equipment/*.md` into the JAR.
3. Initialize PostgreSQL and execute `docker/postgres/init.sql` to enable `vector`
   on a new database volume.
4. Start Ollama. The `models` helper installs `nomic-embed-text:v1.5` and
   `qwen2.5:1.5b` by default. Existing models are reused; differing configured chat
   models are installed separately.
5. Start the app. Flyway applies V1 (machines, readings, events), V2 (alerts), and V3 (authentication).
   Spring AI initializes `public.equipment_vectors` separately.
6. Load, chunk, embed, and store the equipment documents. With startup ingestion
   enabled, successful ingestion is required before application readiness.
7. Begin simulation and automatic alert monitoring after application readiness.

Model downloads and CPU embeddings can take minutes. Leave the startup terminal
running and open a second terminal in the same repository to inspect progress:

```bash
docker compose logs -f models app
```

Press Ctrl+C to stop following logs; this does not stop detached containers.
Expected final state: `postgres`, `ollama`, and `app` healthy; `models` exited with
code 0. An exited helper is normal. On timeout, inspect logs before resetting
anything; completed downloads and database contents are normally reusable.

## 8. Verify That Everything Is Running

Perform these checks in order. Stop and resolve a failed dependency before moving
to AI requests.

### A. Container state

```bash
docker compose ps -a
```

Expect three healthy long-running services and `models` showing successful exit.
For an unhealthy service, inspect `docker compose logs --tail=100 postgres`,
`docker compose logs --tail=100 ollama`, or `docker compose logs --tail=100 app`.
For a failed helper, use `docker compose logs models`.

### B. App health and readiness

```bash
curl --fail "$BASE_URL/actuator/health"
curl --fail "$BASE_URL/actuator/health/readiness"
```

Expected: JSON with `"status":"UP"`. Readiness is explicitly enabled by the
Docker profile. Connection refusal usually means the wrong port or a stopped app;
non-2xx health requires checking app/dependency logs. Readiness is not a guarantee
that later model calls or generated answers will succeed.

### C. Database and migrations

```bash
docker compose exec -T postgres psql -U iiot -d iiot -c \
  "SELECT current_database(), current_user;"
docker compose exec -T postgres psql -U iiot -d iiot -c \
  "SELECT extname, extversion FROM pg_extension WHERE extname = 'vector';"
docker compose exec -T postgres psql -U iiot -d iiot -c \
  "SELECT version, description, success FROM telemetry.flyway_schema_history ORDER BY installed_rank;"
```

Expect database/user `iiot`, a `vector` extension row, and successful V1/V2
migration rows; Flyway may also record schema creation. Missing tables usually
mean the app did not finish migrations. Check app logs; extension/connection
failures also warrant PostgreSQL logs.

### D. Models

```bash
docker compose exec ollama ollama list
```

Expect `nomic-embed-text:v1.5` and `qwen2.5:1.5b`, or your configured chat model(s).
Missing models: inspect `docker compose logs models ollama`. Ollama being healthy
with an empty model list is insufficient for this full-stack lab.

### E. Indexed equipment documents

```bash
docker compose exec -T postgres psql -U iiot -d iiot -c \
  "SELECT COUNT(*) AS chunks,
          COUNT(DISTINCT metadata->>'document_id') AS documents,
          MIN(vector_dims(embedding)) AS dimensions
   FROM public.equipment_vectors
   WHERE metadata->>'corpus' = 'equipment-v1';"
```

For the unmodified bundled corpus, expect **34 chunks, 10 documents, 768
dimensions**. A missing table suggests RAG is disabled or initialization failed.
Zero rows suggests ingestion was disabled or failed. Inspect app logs for
`Embedded equipment corpus` and errors; see section 11 for a manual refresh.

### F. Simulator

Wait a few seconds after readiness, then run:

```bash
docker compose exec -T postgres psql -U iiot -d iiot -c \
  "SELECT COUNT(*) AS machines FROM telemetry.machines;
   SELECT COUNT(*) AS readings FROM telemetry.sensor_readings;"
```

A fresh default stack should have five machines and an increasing reading count.
Repeat the command after several seconds. Existing volumes may contain more
machines/history. If counts stay zero, check `SIMULATOR_ENABLED` and app logs.

## 9. Explore the Application

This is a first tour. Later sections explain each feature in more detail.

### A. Start with a simple request

```bash
curl --fail "$BASE_URL/actuator/health"
```

This demonstrates HTTP access to Spring Boot Actuator. Expect `UP`; next compare
health with the actual data requests below. Exposure is configured in
`src/main/resources/application.properties`.

### B. Read one machine's current telemetry

There is no REST endpoint for listing machines. Obtain a real UUID from the database:

```bash
MACHINE_ID=$(docker compose exec -T postgres psql -U iiot -d iiot -Atc \
  "SELECT id FROM telemetry.machines WHERE name = 'SIM-001';")
printf '%s\n' "$MACHINE_ID"
curl -H "Authorization: Bearer $ACCESS_TOKEN" --fail "$BASE_URL/api/machines/$MACHINE_ID/status"
```

Expect one UUID, then JSON containing `id`, `name`, `location`, `status`, and
`latestReadings`. If the UUID is blank, repeat section 8F. A stored `RUNNING`
status is not a health verdict. Each metric can have a different latest timestamp.
`TelemetryController` and `TelemetryQueryService` implement this request.

Next, query recent vibration history. Derive portable time bounds from PostgreSQL
rather than using platform-specific `date` flags:

```bash
FROM=$(docker compose exec -T postgres psql -U iiot -d iiot -Atc \
  "SELECT to_char((now() - interval '10 minutes') AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"');")
TO=$(docker compose exec -T postgres psql -U iiot -d iiot -Atc \
  "SELECT to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"');")
curl -H "Authorization: Bearer $ACCESS_TOKEN" --fail --get "$BASE_URL/api/machines/$MACHINE_ID/readings" \
  --data-urlencode "from=$FROM" --data-urlencode "to=$TO" \
  --data-urlencode 'metricType=vibration_mm_s' \
  --data-urlencode 'limit=20' --data-urlencode 'offset=0'
```

Expect an array, oldest first. Bounds are inclusive; `limit` is 1–1000 and
`offset` is nonnegative. Increase `offset` to page through results. Refresh
`FROM`/`TO` for a later window. Read [telemetry API](docs/telemetry-api.md) next.

### C. Inspect anomalies

```bash
curl -H "Authorization: Bearer $ACCESS_TOKEN" --fail "$BASE_URL/api/anomalies"
```

Expect an array of anomalies, newest first, over the preceding hour. Initially
`[]` can be normal. `RollingAnomalyDetector` computes the results from readings;
wait roughly a minute for the first default injection. Continue with section 14.

### D. Find a manual passage, then ask a grounded question

```bash
curl -H "Authorization: Bearer $ACCESS_TOKEN" --fail --get "$BASE_URL/api/documents/search" \
  --data-urlencode 'query=What does E204 mean?' --data-urlencode 'topK=3'
curl -H "Authorization: Bearer $ACCESS_TOKEN" --fail "$BASE_URL/api/rag/query" -H 'Content-Type: application/json' \
  -d '{"question":"What does E204 mean?"}'
```

Search returns chunks with `text`, `score`, and `metadata`. The RAG response adds
an answer and citations, or explicitly reports insufficient evidence.
`EquipmentSearchController` and `RagAnswerService` handle these features.
Next compare the returned quotes with the source document in section 11.

### E. Ask the agent about current data and documentation

```bash
curl -H "Authorization: Bearer $ACCESS_TOKEN" --fail "$BASE_URL/api/agent/chat" -H 'Content-Type: application/json' \
  -d '{"question":"Is SIM-001 vibration normal, and what should I do if not?"}'
```

Expect `conversationId`, `answer`, `insufficientEvidence`, `evidenceIds`, and
`evidence`. CPU inference may take minutes. Inspect actual tool calls even when
`insufficientEvidence` is true; a successful HTTP response is not proof of a
supported answer. `AgentService` implements orchestration. Continue with section 12.

### F. Confirm MCP access control

```bash
curl -i "$BASE_URL/mcp"
```

Expected: HTTP **401**, because no bearer access token was supplied. This intentionally
omits `--fail` so you can inspect the response. `McpApiKeyFilter` protects the
transport. Section 13 adds an authenticated protocol client.

### G. Observe automatic alerts

```bash
docker compose logs -f app
```

Watch for `Injected SIMULATED_SPIKE` or `Injected SIMULATED_DROPOUT`, followed by
`ANOMALY_ALERT`. Alerts do not require an API request. `AnomalyAlertService`
records and logs them. Stop following with Ctrl+C; continue with sections 14–15.

## 10. Explore the Database

Open an interactive SQL session inside the existing PostgreSQL container:

```bash
docker compose exec postgres psql -U iiot -d iiot
```

The prompt should be `iiot=#`. The following blocks are **SQL/psql commands**,
not Bash. Exit with `\q` when finished. No host PostgreSQL installation is needed.

List the tables and their definitions:

```sql
\dt telemetry.*
\dt public.*
\d telemetry.sensor_readings
\d public.equipment_vectors
```

| Object | Meaning |
| --- | --- |
| `telemetry.machines` | Machine UUID, display name, location, stored status |
| `telemetry.sensor_readings` | Timestamped values identified by machine and metric |
| `telemetry.machine_events` | Initialization/status changes and injected fault events |
| `telemetry.anomaly_alerts` | Detected reading payloads and email delivery state |
| `telemetry.flyway_schema_history` | Applied migration history |
| `public.equipment_vectors` | Document chunk content, metadata, and embeddings |

Find machines and the newest measurements:

```sql
SELECT id, name, location, status FROM telemetry.machines ORDER BY name;

SELECT m.name, r.metric_type, r."value", r."timestamp"
FROM telemetry.sensor_readings r
JOIN telemetry.machines m ON m.id = r.machine_id
ORDER BY r."timestamp" DESC, r.id DESC LIMIT 20;
```

Look for `temperature_celsius`, `vibration_mm_s`, cumulative `energy_kwh`, and
simulated Modbus register metrics. These are generated values, not sensor hardware.

Inspect injected faults:

```sql
SELECT m.name, e.fault_code, e.description, e."timestamp"
FROM telemetry.machine_events e
JOIN telemetry.machines m ON m.id = e.machine_id
WHERE e.event_type = 'FAULT'
ORDER BY e."timestamp" DESC, e.id DESC LIMIT 20;
```

These are simulator labels. There is **no general `anomalies` table**: API anomaly
results are computed from measurements. Stored alerts are the subset observed by
the live worker, not a complete historical anomaly index:

```sql
SELECT reading_id, payload::jsonb->>'reason' AS reason,
       detected_at, email_status, attempts, sent_at
FROM telemetry.anomaly_alerts
ORDER BY detected_at DESC LIMIT 20;
```

Inspect document coverage without printing large vectors:

```sql
SELECT metadata->>'document_id' AS document_id,
       metadata->>'source' AS source, COUNT(*) AS chunks
FROM public.equipment_vectors
WHERE metadata->>'corpus' = 'equipment-v1'
GROUP BY 1, 2 ORDER BY 1;

SELECT id, metadata->>'section' AS section, LEFT(content, 200) AS excerpt
FROM public.equipment_vectors
WHERE metadata->>'document_id' = 'REF-FAULTS-001';
```

Finally, leave psql:

```text
\q
```

Flyway owns `telemetry`; Spring AI initializes the vector table. Do not edit
applied migration files to change a persistent schema; introduce a new migration.
Read [schema and ERD](docs/telemetry-schema.md) for constraints and relationships.

## 11. Explore RAG

RAG means retrieving relevant source material before asking a model to answer:

```text
Bundled Markdown in docs/equipment/
        ↓ validate metadata; split by sections and ~300-token fragments
Document chunks with source information
        ↓ nomic-embed-text:v1.5; 768-dimensional embeddings
public.equipment_vectors in pgvector
        ↓ embed question; search by cosine similarity
Relevant passages
        ↓ local chat model
Answer + server-validated source quotes, or insufficient evidence
```

The loader prepends document/section titles. `NomicEmbeddingModel` adds the model's
query/document prefixes during embedding. Search stays within `equipment-v1`.
The standalone search endpoint defaults to five matches and threshold 0.0;
RAG answers default to six matches and threshold 0.45.

Test retrieval independently of answer generation:

```bash
curl -H "Authorization: Bearer $ACCESS_TOKEN" --fail --get "$BASE_URL/api/documents/search" \
  --data-urlencode 'query=What repair fixed the compressor overheating caused by a blocked cooling screen?' \
  --data-urlencode 'topK=3' --data-urlencode 'threshold=0.0'
```

Expect matching passages and metadata; exact ranking/scores depend on the model.
Similarity scores are not diagnostic confidence.

Test supported and unsupported questions:

```bash
curl -H "Authorization: Bearer $ACCESS_TOKEN" --fail "$BASE_URL/api/rag/query" -H 'Content-Type: application/json' \
  -d '{"question":"What does E204 mean?"}'
curl -H "Authorization: Bearer $ACCESS_TOKEN" --fail "$BASE_URL/api/rag/query" -H 'Content-Type: application/json' \
  -d '{"question":"What does E999 mean?"}'
```

E204 is an unavailable motor temperature sensor signal on the fictional SIM-003
compressor, not overheating. Review `citations[].quote`, `documentId`, `source`,
and `section` against [the fault reference](docs/equipment/fault-code-reference.md).
A supported answer should have `insufficientEvidence=false` and checked citations;
invalid model output can still result in abstention.

E999 is absent from the bundled corpus. With working retrieval, expect HTTP 200,
`insufficientEvidence=true`, and `citations: []`. Missing/invalid citations also
produce abstention. Dependency failures return HTTP 503 instead. Quote validation
checks source fidelity, not the correctness of every interpretation.

**Optional corpus refresh — changes stored vectors:**

```bash
curl -H "Authorization: Bearer $ACCESS_TOKEN" --fail -X POST "$BASE_URL/api/documents/ingest"
```

Expected for the current corpus:

```json
{"documents":10,"chunks":34,"model":"nomic-embed-text:v1.5"}
```

This reads packaged documents; it is not an upload API. Replacement is
transactional and scoped to the equipment corpus. Editing `docs/equipment/`
requires rebuilding the image before the running container can ingest the edits:

```bash
docker compose up --build -d --wait --wait-timeout 900 app
```

With default startup ingestion, the rebuilt app refreshes the corpus automatically.

Read `EquipmentDocumentLoader.java`, `EquipmentIngestionService.java`,
`NomicEmbeddingModel.java`, `RagConfiguration.java`, and `RagAnswerService.java`
in `src/main/java/com/iiot/rag/`. Continue with [ingestion](docs/rag-ingestion.md)
and [grounded answers](docs/rag-query.md).

## 12. Explore Tool Calling / Agent

The agent asks the chat model to choose bounded Java functions. Results feed back
into the model before final answer generation; the model does not generate SQL.

| Tool | Role |
| --- | --- |
| `resolveMachine` | Resolve a stored name or simulator number into existing UUIDs |
| `getMachineStatus` | Latest measurements and stored machine metadata |
| `queryTelemetryRange` | Historical measurements over an explicit range |
| `getRecentAnomalies` | Computed statistical deviations and dropouts |
| `retrieveEquipmentKnowledge` | Retrieve equipment passages for interpretation |

Names are matched case-insensitively. Numeric `1` can resolve `SIM-001`; `12`
means `SIM-012`, which does not exist in the default five-machine fleet. Ambiguous
or missing machines need clarification. The agent must use an existing UUID.

| Approach | Source of facts | Example |
| --- | --- | --- |
| RAG | Equipment documents | “What does E204 mean?” |
| Tool calling | Live or historical database queries | “What is SIM-001's latest vibration?” |
| RAG + tool calling | Measurements plus applicable manual passages | “Is that vibration normal, and what should I do?” |

Start a conversation:

```bash
curl -H "Authorization: Bearer $ACCESS_TOKEN" --fail "$BASE_URL/api/agent/chat" -H 'Content-Type: application/json' \
  -d '{"question":"What is SIM-001 latest vibration reading?"}'
```

Copy the response's `conversationId`. Read it into the shell when prompted, then
send two follow-ups:

```bash
read -r -p 'Paste the returned conversationId: ' CONVERSATION_ID
curl -H "Authorization: Bearer $ACCESS_TOKEN" --fail "$BASE_URL/api/agent/chat" -H 'Content-Type: application/json' \
  -d "{\"conversationId\":\"$CONVERSATION_ID\",\"question\":\"Is that normal, and what should I do?\"}"
curl -H "Authorization: Bearer $ACCESS_TOKEN" --fail "$BASE_URL/api/agent/chat" -H 'Content-Type: application/json' \
  -d "{\"conversationId\":\"$CONVERSATION_ID\",\"question\":\"And what about last week?\"}"
```

On a fresh database there is no last-week history; expect missing-evidence behavior
rather than invented readings. “Last week” means the previous UTC Monday–Sunday.

Inspect `evidence[].tool`, `success`, and `result`, then match `evidenceIds` to
those entries. The result field contains serialized tool output. Evidence labels
restart each turn; remembering a machine does not replace fetching fresh data.

Memory holds up to six completed turns/24,000 context characters, expires after
30 idle minutes, and has a 128-session capacity. It disappears on app restart.
Unknown/expired IDs return 404; overlapping requests for one conversation return
409; capacity exhaustion returns 503. Omit `conversationId` to begin again.

The default agent allows four decision rounds and eight executed tool calls.
The small chat model can choose the wrong tools or fail final evidence validation.
`insufficientEvidence=true` is a documented possible outcome even on a healthy
stack; inspect evidence instead of treating this as automatic infrastructure failure.

Implementation: `src/main/java/com/iiot/agent/AgentService.java`,
`AgentKnowledgeTools.java`, `ConversationMemory.java`, and
`src/main/java/com/iiot/telemetry/TelemetryTools.java`.
See [agent behavior and limitations](docs/agent-chat.md).

## 13. Explore MCP

MCP lets an external client discover and invoke tools using a standard protocol.
Here it exposes existing telemetry and knowledge services without going through
the conversational agent.

- Endpoint: `$BASE_URL/mcp`, normally `http://localhost:8080/mcp`.
- Transport: **Streamable HTTP**, not stdio or the legacy `/sse` transport.
- Header on every request: `Authorization: Bearer <ADMIN access token>`.
- Tools: `getMachineStatus`, `getRecentAnomalies`, `ragQuery`.

An ADMIN JWT grants access to all three tools for all machines, and to RAG/chat.
USER tokens receive 403. Health and Swagger are public. MCP sessions do not
share the agent's `conversationId` memory; the external client owns its context.

Confirm missing credentials fail:

```bash
curl -i "$BASE_URL/mcp"
```

Expected: 401 and `{"status":401,"message":"Authentication required"}`.
An ADMIN request returns 404 when MCP is disabled.

**Optional Inspector exercise:** requires Node/npm from section 2. Log in as ADMIN
using the authentication guide to obtain `ACCESS_TOKEN`:

```bash
MCP_URL="$BASE_URL/mcp"
npx @modelcontextprotocol/inspector --cli "$MCP_URL" --transport http \
  --header "Authorization: Bearer $ACCESS_TOKEN" --method tools/list
```

`npx` may ask to download Inspector. Expect the three tool names listed above.
Inspector handles protocol initialization and sessions; a plain authenticated
GET is not a substitute for a tool call. The syntax follows the
[official Inspector CLI guide](https://github.com/modelcontextprotocol/inspector/blob/main/clients/cli/README.md).

Use the `MACHINE_ID` obtained in section 9:

```bash
npx @modelcontextprotocol/inspector --cli "$MCP_URL" --transport http \
  --header "Authorization: Bearer $ACCESS_TOKEN" \
  --method tools/call --tool-name getMachineStatus --tool-arg "machineId=$MACHINE_ID"
npx @modelcontextprotocol/inspector --cli "$MCP_URL" --transport http \
  --header "Authorization: Bearer $ACCESS_TOKEN" \
  --method tools/call --tool-name getRecentAnomalies --tool-arg "machineId=$MACHINE_ID"
npx @modelcontextprotocol/inspector --cli "$MCP_URL" --transport http \
  --header "Authorization: Bearer $ACCESS_TOKEN" \
  --method tools/call --tool-name ragQuery --tool-arg 'question=What does E204 mean?'
```

Successful results have `isError=false`, JSON text content, and
`structuredContent.result`. A tool error uses `isError=true`; an insufficient-
evidence RAG answer can still be a successful tool call. Allow at least three
minutes in your client's timeout settings for CPU RAG; each underlying Ollama
request has a two-minute read timeout.

All three tools remain discoverable if MCP is enabled without RAG, but `ragQuery`
then reports a tool error. Default host/origin checks accept local addresses;
this configuration is intended for local clients.

Implementation: `src/main/java/com/iiot/mcp/EquipmentMcpConfiguration.java` and
`McpApiKeyFilter.java`. See [MCP server](docs/mcp-server.md) for the full contract.

## 14. Explore Anomaly Detection

The simulator creates `SIM-001` through `SIM-005` by default and generates a batch
approximately every five seconds after readiness. Every 12 batches it selects
one machine for a fault, alternating a temperature/vibration spike and a
sensor dropout. Fault labels are `SIMULATED_SPIKE` and `SIMULATED_DROPOUT`;
E204 is a manual code, not a simulator-emitted event code.

The detector compares each temperature/vibration reading with up to 30 earlier
samples for that machine and metric, requiring at least 10 prior samples. It
flags absolute z-scores greater than 4. A minimum noise scale prevents division
by zero. Register `modbus_hr_40001=65535` is an explicit dropout and requires no
statistical warm-up. Energy is cumulative and is not scored this way.

Observe all machines, because the injected fault may affect a different machine
from the one you selected earlier:

```bash
curl -H "Authorization: Bearer $ACCESS_TOKEN" --fail "$BASE_URL/api/anomalies"
docker compose logs --since=5m app
```

Wait roughly one minute from simulation start for the first injected spike; the
second injection is a dropout. Exact scheduling varies. Repeat the commands and
inspect anomaly `reason`, `reading`, and `baseline`. Dropouts have a null baseline.
A missing temperature sample can leave the status endpoint showing an older
value: compare timestamps and the dropout register.

Raw readings and simulator events persist in PostgreSQL. API anomalies are
recomputed; the alert worker separately stores observed anomalies in
`telemetry.anomaly_alerts`. An empty API array does not prove healthy equipment,
and a statistical flag does not identify a root cause.

Implementation: `src/main/java/com/iiot/simulator/TelemetrySimulator.java`,
`SimulatorProperties.java`, and `src/main/java/com/iiot/telemetry/RollingAnomalyDetector.java`.
The [detector guide](docs/anomaly-detection.md) explains the synthetic acceptance
dataset, false positives, and limitations.

## 15. Explore Alerting

With `ALERTS_ENABLED=true`, the app checks newly committed readings about once
per second. Each detected reading creates at most one database alert record and
an `ANOMALY_ALERT` log. It does not require chat, RAG, MCP, or Gmail.

```bash
docker compose logs --since=5m app
docker compose exec -T postgres psql -U iiot -d iiot -c \
  "SELECT reading_id, detected_at, email_status, attempts, sent_at
   FROM telemetry.anomaly_alerts ORDER BY detected_at DESC LIMIT 20;"
```

With Gmail disabled, expect `NOT_REQUESTED`. The worker starts its live window at
application readiness and checks an overlapping minute; it does not replay every
historical anomaly after a restart. Very late readings can fall outside that window.

### Optional: enable Gmail

Skip this exercise unless you want real email. Use a Gmail/Workspace app password,
not your account password. App passwords require 2-Step Verification and may be
unavailable under some account policies; follow
[Google's app-password instructions](https://support.google.com/accounts/answer/185833?hl=en).

Edit the ignored `.env`, replacing the examples with your own account details:

```dotenv
ALERTS_GMAIL_ENABLED=true
GMAIL_USERNAME=your-account@gmail.com
GMAIL_APP_PASSWORD=your-google-app-password
ALERT_EMAIL_TO=your-recipient@example.com
```

Keep `ALERTS_ENABLED=true`. Recreate the app to apply the settings:

```bash
docker compose up -d --wait --wait-timeout 900 app
```

Wait for a **new** anomaly and repeat the logs/status query. Successful SMTP
acceptance produces `ANOMALY_EMAIL_SENT`, `SENT`, and a `sent_at` timestamp. Check
your inbox/spam folder separately; SMTP acceptance does not prove inbox delivery.

The sender uses `smtp.gmail.com:587`, authentication, required STARTTLS, and
certificate identity checks, with five-second connection/read/write timeouts.
Failed messages remain `PENDING`, retry after 30 seconds, and become `FAILED`
after five attempts. PostgreSQL preserves pending deliveries across app restarts.
Old `NOT_REQUESTED` rows are not mailed retroactively. A crash after SMTP acceptance
but before the database update can produce a duplicate on retry.

Use one alert-worker app instance per database. To stop email, set
`ALERTS_GMAIL_ENABLED=false` and recreate the app with the command above.

Implementation: `src/main/java/com/iiot/alert/AlertConfiguration.java` and
`AnomalyAlertService.java`. See [alerting](docs/alerting.md). Never commit or paste
Gmail credentials into shared logs/issues.

## 16. Swagger / API Exploration

Open these URLs in a browser, adjusting the application port if necessary:

- [Swagger UI](http://localhost:8080/swagger-ui/index.html)
- [OpenAPI JSON](http://localhost:8080/v3/api-docs)
- [OpenAPI YAML](http://localhost:8080/v3/api-docs.yaml)

Retrieve the specifications from your configured base URL:

```bash
curl --fail "$BASE_URL/v3/api-docs"
curl --fail "$BASE_URL/v3/api-docs.yaml"
```

In Swagger UI:

1. Expand **Telemetry** and select `GET /api/machines/{id}/status`.
2. Click **Try it out**, enter the UUID from section 9, and click **Execute**.
3. Inspect the generated request, HTTP status, and JSON response.
4. Open the readings endpoint to inspect required time bounds and pagination.
5. Explore document search, RAG, and agent request/response schemas.

AI routes and MCP protocol examples are visible even in default H2 mode.
ADMIN calls to disabled RAG/chat services return 503. Enable RAG_ENABLED and
AGENT_ENABLED with PostgreSQL/pgvector and Ollama configured to execute them;
enable MCP_ENABLED for the MCP servlet. USER callers receive 403.
The UI executes real operations: `POST /api/documents/ingest` replaces stored
corpus vectors. MCP's protocol/session workflow is documented separately, not
represented as ordinary REST operations in Swagger.

See [API documentation guide](docs/swagger-api.md), controller annotations, and
`src/main/java/com/iiot/config/OpenApiConfiguration.java`. The current agent source
also defines conversation 404/409 errors and capacity-related 503 responses;
consult section 12 and `ConversationMemory.java` if an operation's displayed
response list does not include them.

## 17. Local Development Without Docker

This is an **alternative** workflow, not a prerequisite for the Docker lab.
Use JDK 21 and Maven Wrapper. Stop the Docker app first if it occupies port 8080:

```bash
docker compose stop app
```

In a fresh Bash terminal at the repository root, verify Java and start the app:

```bash
java -version
javac -version
./mvnw --version
./mvnw spring-boot:run
```

Expected: Spring Boot starts with an in-memory H2 database, Flyway migrations,
simulated telemetry, automatic log alerts, health, and Swagger. RAG, agent, and
MCP are disabled by default. No PostgreSQL or Ollama is required in this mode.
The H2 user is `sa` with an empty password; no H2 web console is configured.

From another terminal:

```bash
curl --fail http://localhost:8080/actuator/health
curl -H "Authorization: Bearer $ACCESS_TOKEN" --fail http://localhost:8080/api/anomalies
```

Use `/actuator/health` here; the Docker profile explicitly enables the readiness
group used by container checks. H2 is a different database from your Docker
PostgreSQL instance, so do not assume UUIDs queried from Docker describe H2 rows.
Stop local Spring Boot with Ctrl+C; all H2 data disappears.

`.env` is not automatically imported by Spring Boot or Maven. Inherited
`SPRING_*`, `RAG_*`, or other environment overrides can change the defaults;
use a clean terminal before diagnosing local-mode behavior.

To enable AI while running the JVM on the host, you still need external
PostgreSQL with pgvector and Ollama with installed models. Supply
`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`,
`SPRING_DATASOURCE_PASSWORD`, `SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver`,
`OLLAMA_BASE_URL`, `RAG_ENABLED=true`, and `AGENT_ENABLED=true` through the host
environment. Add `MCP_ENABLED=true` if desired; clients use an ADMIN JWT.
H2 does not implement the PostgreSQL vector/ingestion pipeline. Keep the full-stack
Compose workflow for your first AI run; [ingestion configuration](docs/rag-ingestion.md)
describes this optional hybrid setup.

## 18. Running Tests

Use JDK 21 and an otherwise clean shell. The standard suite does not require
Docker, PostgreSQL, Ollama, or Gmail credentials. It uses H2 and controlled model
outputs; some tests start temporary local HTTP servers.

```bash
./mvnw test
```

This compiles the project and runs tests. To also package and verify the artifact:

```bash
./mvnw verify
```

Expected: `BUILD SUCCESS`; one pgvector integration test is skipped unless
explicitly enabled. Test counts can grow as the project changes. Reports live in
`target/surefire-reports/`. `verify` produces the executable JAR in `target/`.
Do not run concurrent Maven builds in the same checkout.

| Test area | What it verifies | External services for normal suite |
| --- | --- | --- |
| Schema, simulator, telemetry | Migrations, constraints, measurements, API validation | None; H2 |
| Rolling detector/acceptance | Baselines, dropouts, synthetic precision/recall | None; H2 |
| Corpus/RAG | Chunking, metadata, query validation, citation checks, failures | None; controlled vectors/model output |
| Agent/memory | Tool routing, evidence, identities, conversation limits | None; controlled model output |
| MCP | Real HTTP protocol, discovery, tool calls, bearer auth | Temporary test server; no external model |
| Alerting | Automatic detection, deduplication, retry/TLS/mail construction | Mocked mail transport; no real email |
| OpenAPI | Enabled routes, schema separation, UI/YAML, disabled AI routes | None beyond local test contexts/server |
| `InterviewDemoTests` | Combined deterministic workflow | Temporary H2 and HTTP server |

Focused examples:

```bash
./mvnw -Dtest=AnomalyDetectionAcceptanceTests,RollingAnomalyDetectorTests test
./mvnw -Dtest=EquipmentMcpTests,EquipmentMcpWiringTests test
./mvnw -Dtest=AnomalyAlertTests,AlertDeliveryTests test
./mvnw -Dtest=OpenApiDocumentationTests,IiotPoweredByAiApplicationTests test
```

### Optional PostgreSQL/pgvector checks

CI additionally tests PostgreSQL behavior. **Use a separate test database**, never
point these tests at the demo `iiot` database: ingestion tests replace/remove the
corpus, and tests manipulate database records.

The following creates an explicitly named test database in the running container.
Run `CREATE DATABASE` once; if it already exists, confirm it is a disposable test
database before reusing it:

```bash
docker compose exec -T postgres psql -U iiot -d postgres -c 'CREATE DATABASE iiot_test;'
docker compose exec -T postgres psql -U iiot -d iiot_test -c 'CREATE EXTENSION IF NOT EXISTS vector;'
```

Use a subshell so connection settings do not leak into later development commands.
Adjust host port `5432` if you changed `POSTGRES_PORT`. Enter your configured
PostgreSQL password when prompted:

```bash
(
  export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/iiot_test
  export SPRING_DATASOURCE_USERNAME=iiot
  export SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver
  read -r -s -p 'PostgreSQL password from .env: ' SPRING_DATASOURCE_PASSWORD
  printf '\n'
  export SPRING_DATASOURCE_PASSWORD
  ./mvnw -Dtest=TelemetrySchemaTests,TelemetrySimulatorTests,TelemetryApiTests,RollingAnomalyDetectorTests,AnomalyDetectionAcceptanceTests test
  RAG_PGVECTOR_TESTS=true ./mvnw -Dtest=PgVectorIngestionTests test
)
```

The vector test uses controlled embeddings to check persistence, replacement,
filtering, and rollback. It does not require Ollama or test semantic retrieval
quality. `.github/workflows/ci.yml` defines these additional workflows.

### Optional live model checks

With the full stack running and Python 3 installed:

```bash
IIOT_ACCESS_TOKEN="$ACCESS_TOKEN" python3 scripts/verify-rag-query.py "$BASE_URL"
IIOT_ACCESS_TOKEN="$ACCESS_TOKEN" python3 scripts/verify-agent.py "$BASE_URL" SIM-001
```

The RAG script checks E204 quotes and E999 abstention. The agent script checks
data-only, knowledge-only, mixed, and greeting routes. These use real Ollama and
PostgreSQL and can take minutes. The documented small-model citation/routing
limitation can make the agent script fail even when infrastructure is healthy.
Inspect the printed evidence and app logs.

For ordinary test failures, read the first failure in the Surefire report rather
than only the Maven summary. Check Java version, inherited environment settings,
local socket permission, and dependency downloads. Tests using Mockito need JVM
instrumentation; a restricted runner may block attachment. Run in a normal local
development environment or configure the runner's approved test permissions.

## 19. Interview/Demo Workflow

This workflow can run from a clean checkout without starting Docker or configuring
`.env`. Install JDK 21 and Python 3 first. Prepare dependencies once, online:

```bash
./mvnw --batch-mode --no-transfer-progress -Dtest=InterviewDemoTests test
```

After `BUILD SUCCESS`, run the repeatable demo:

```bash
python3 scripts/demo.py
```

Expect a transcript covering telemetry, mixed data/manual questions, multi-turn
memory, E204 knowledge, anomaly detection, MCP authentication/calls, and automatic
alerts, ending with `PASS` and elapsed time.

The script runs Maven **offline**, creates temporary resources, uses an isolated
H2 database and random HTTP port, and enforces a 110-second process timeout. It
removes inherited service/model/JVM override settings from its child environment.
Gmail is disabled. Do not run another Maven build alongside it.

**What it proves:** real HTTP handling, database queries, memory, tool callbacks,
source/evidence validation, detector behavior, MCP exchange/auth, and automatic
alert integration against assertions.

**What it does not prove:** live LLM reasoning, real embedding quality, pgvector
recall, Gmail inbox delivery, or hardware-independent response time. Model
responses and retrieval selections are scripted fixtures. Unlike your fresh live
database, the demo deliberately seeds last-week readings.

If offline artifacts are missing, repeat preparation. If the timeout fires, inspect
the printed build/test log and machine load. Use Linux/macOS/WSL: the script uses
POSIX process groups. See [interview demo](docs/interview-demo.md).

## 20. Troubleshooting

Use `docker compose ps -a` first. Keep `.env` and file selection consistent; avoid
posting expanded Compose configuration or credentials when asking for help.

| Symptom | Likely cause | How to diagnose | How to fix |
| --- | --- | --- | --- |
| Docker cannot connect | Engine/Desktop stopped or wrong context | `docker info`; `docker context show` | Start Docker and select the intended context; retry |
| Docker socket permission denied | User lacks access to the daemon | `docker info` | Follow your Docker installation's user/rootless access setup; do not make the socket world-writable |
| `--wait` not recognized | Old Compose plugin | `docker compose version`; `docker compose up --help` | Update to a plugin supporting the flags used here |
| Port already allocated | Existing PostgreSQL, Ollama, or Java process/container | `docker ps`; read the bind error | Change the matching port in `.env`, rerun `up`, and update `BASE_URL` or host DB URL |
| `models` is absent | Only base file selected | `docker compose config --services` | Restore both files in `COMPOSE_FILE`; verify JWT/bootstrap configuration, then rerun startup |
| Container exits | Configuration, build, or dependency failure | `docker compose ps -a`; `docker compose logs --tail=100 app` | Correct the first logged error, then rerun `up --build -d --wait --wait-timeout 900` |
| PostgreSQL unhealthy | Database startup or vector extension failure | `docker compose logs postgres`; repeat section 8C | Resolve storage/init errors; use the extension repair below for an older initialized volume |
| App reports DB password failure after editing `.env` | Stored DB role password still has its original value | `docker compose logs app` | Restore the original setting or deliberately update the DB role; do not erase data just to try a password |
| Model helper fails or models missing | Download/network/disk failure | `docker compose logs models ollama`; `docker compose exec ollama ollama list` | Restore connectivity/free space and rerun full startup; use the explicit pull commands below if needed |
| App never becomes healthy | Failed migration, missing model, ingestion error, or slow embedding | `docker compose logs -f app models`; section 8 checks | Fix the dependency error; allow CPU ingestion time and rerun the readiness wait |
| Slow first startup | Image/Maven/model downloads plus embeddings | Follow `models` and `app` logs | Let active downloads finish; preserve volumes to reuse work; verify Docker has enough resources |
| RAG/chat routes return 503 | AI services disabled or unavailable | Service selection and app startup logs; Swagger route list | Use the AI override; ensure `BASE_URL` targets that app |
| Search returns `[]` | Empty corpus or restrictive threshold | Section 8E; repeat search with `threshold=0.0` | Ingest documents after models are ready; rebuild if source documents changed |
| RAG/chat returns 503 | Unavailable model/retrieval, or agent capacity exhausted | `docker compose logs app ollama`; inspect models | Restore dependency availability; for capacity, wait for idle sessions to expire |
| Agent returns `insufficientEvidence=true` | Missing data, wrong tool choices, or rejected evidence | Inspect `evidence`, `evidenceIds`, source passages, timestamps | Verify REST/search independently; ask a specific existing-machine question; do not assume the small model will pass every live check |
| Chat follow-up returns 404/409 | Expired/unknown conversation or overlapping turn | Check `conversationId` and whether prior request finished | Omit ID to start again, or wait for the current turn |
| MCP returns 401 | Missing, expired or invalid JWT, or malformed/duplicate header | Confirm client sends one bearer header | Log in or refresh the ADMIN token and update the client header |
| AI/MCP returns 403 | Authenticated account lacks ADMIN role | Check the login response role | Use an ADMIN account |
| MCP returns 404 | MCP disabled or wrong port | `docker compose config --services`; app logs | Enable MCP_ENABLED and send an ADMIN JWT |
| MCP returns 403 | Host/origin outside local allowlist | Inspect client URL/origin | Use the documented localhost endpoint for this local setup |
| Inspector RAG call times out | Client timeout too short for CPU inference | App/Ollama logs and direct RAG request | Increase the client's request timeout; consult the installed Inspector version's configuration |
| No anomalies yet | Warm-up, simulator disabled, or selected machine unaffected | Readings count; all-machine anomaly query; injection logs | Wait about a minute; verify simulator settings and query all machines |
| No Gmail email | Gmail disabled, bad account/app password, SMTP failure, or only old alerts | Section 15 status query; `ANOMALY_EMAIL_FAILED` logs | Correct `.env`, recreate app, and observe a new anomaly; inspect spam and account policy |
| Windows commands fail | Bash syntax used in PowerShell, wrong Compose separator, or CRLF script | Check shell and file line endings | Use WSL for this guide; use `mvnw.cmd` for native Windows Java commands and explicit Compose `-f` flags |
| `./mvnw: Permission denied` | Executable bit missing | `ls -l mvnw` | Run `chmod +x mvnw`, then retry |
| Offline demo fails | Maven cache incomplete or regression | Output from `python3 scripts/demo.py` | Run its online preparation command; inspect the test failure if preparation also fails |

### Docker Hub 401 while loading Java base-image metadata

A failure at `FROM eclipse-temurin:21-jdk` or `FROM eclipse-temurin:21-jre`
occurs before Java compilation or application authentication. It concerns the
Docker Hub registry token exchange. First retry the public base-image pulls:

```bash
docker pull eclipse-temurin:21-jdk
docker pull eclipse-temurin:21-jre
docker compose build app
docker compose up -d --wait --wait-timeout 900
```

A transient registry/network failure can clear on retry. If either pull still
returns 401, use `docker login` to refresh your Docker Hub authentication, then
retry. Run login and Compose under the same user and Docker context. If login
succeeds but pulls still fail, check registry access through your VPN/proxy and
Docker daemon network configuration. Application `AUTH_*` settings do not control
image pulls; changing them cannot repair this build-stage error.

An unauthenticated request to `https://registry-1.docker.io/v2/` normally returns
401 with a bearer challenge; successful token acquisition followed by an authorized
manifest request is the meaningful check. See [Docker registry authentication](https://docs.docker.com/reference/api/registry/auth/)
and [Docker login](https://docs.docker.com/reference/cli/docker/login/).

For a pre-existing PostgreSQL volume missing the extension, the initialization
script will not rerun automatically. Repair explicitly:

```bash
docker compose exec -T postgres psql -U iiot -d iiot -c 'CREATE EXTENSION IF NOT EXISTS vector;'
docker compose up -d --wait --wait-timeout 900
```

If a required default model is missing, download it explicitly, then retry startup:

```bash
docker compose exec ollama ollama pull nomic-embed-text:v1.5
docker compose exec ollama ollama pull qwen2.5:1.5b
docker compose up -d --wait --wait-timeout 900
```

Substitute configured chat model names if changed. Ordinary API calls do not
trigger model downloads. The model bootstrap reuses installed models; an explicit
`ollama pull` is also how you request a model update, which can change outputs.

## 21. Stop, Restart, Reset

Run these from the same project directory with the same `.env`/Compose selection.
Choose the action you need; do not execute every row as a sequence.

| Command | Effect | Data retained |
| --- | --- | --- |
| `docker compose stop` | Stop containers without removing them | PostgreSQL and models retained; chat memory lost |
| `docker compose start` | Start existing stopped containers; no rebuild or configuration update | Existing volumes retained |
| `docker compose restart` | Restart existing containers; does not apply edited environment settings | Existing volumes retained; chat memory lost |
| `docker compose down` | Remove stack containers and network | Named database/model volumes retained |
| `docker compose down --volumes` | Remove containers/network and declared volumes | Database and downloaded models deleted |

The one-shot helper can run and exit again when services are started/restarted;
that is expected. Recheck health afterward. After `down`, use `up`, not `start`:

```bash
docker compose up -d --wait --wait-timeout 900
```

After editing `.env`, use `up` to apply changes. After editing Java or bundled
manuals, also rebuild:

```bash
docker compose up --build -d --wait --wait-timeout 900 app
```

> **Destructive reset:** the command below permanently removes this Compose
> project's telemetry, events, alert records, document vectors, and downloaded
> models. It does not delete source files or `.env`. Use it only when you intend
> to discard the lab data; the next startup must initialize and download again.

```bash
docker compose down --volumes
```

Ordinary app restarts preserve database rows and model files, but default startup
ingestion replaces the equipment corpus and conversation memory always resets.
A renamed project directory or changed Compose project name can select different
volumes and make existing data appear missing.

## 22. Complete Beginner Checklist

Mark optional exercises as skipped when appropriate.

- [ ] Required tools installed and Docker daemon reachable
- [ ] Repository cloned and expected files present
- [ ] Clone-URL and older port examples understood
- [ ] `.env` created without overwriting existing settings
- [ ] JWT secret configured and `.env` confirmed ignored by Git
- [ ] Four full-stack services selected
- [ ] App, PostgreSQL, and Ollama healthy; model helper exited successfully
- [ ] Health/readiness requests return `UP`
- [ ] Migrations and pgvector extension verified
- [ ] Required models available
- [ ] Equipment corpus contains 10 documents and 34 chunks
- [ ] Machine UUID obtained and current/history REST requests tested
- [ ] Database tables and sample measurements explored
- [ ] Anomaly injection and computed anomaly response observed
- [ ] Automatic alert log and stored alert record observed
- [ ] Document search and supported/unsupported RAG requests tested
- [ ] Agent evidence inspected and conversation ID reused
- [ ] MCP unauthenticated request returns 401
- [ ] Optional: authenticated MCP discovery and tool calls tested
- [ ] Optional: Gmail delivery configured and verified separately
- [ ] Swagger UI and OpenAPI specifications explored
- [ ] Optional: H2 local development workflow tried
- [ ] Automated tests run with JDK 21
- [ ] Deterministic demo prepared and executed
- [ ] Optional: dedicated PostgreSQL tests and live AI checks run
- [ ] Stop/restart/reset behavior understood
- [ ] Source-code roadmap below explored

## Where to Go Next in the Code

Paths below are relative to the repository root. Read in this order after running
the lab; use the related section when you want to repeat a behavior.

| Order | File/package path | What to look for | Lab sections |
| --- | --- | --- | --- |
| 1 | `src/main/java/com/iiot/IiotPoweredByAiApplication.java` | Spring Boot entry point and package scanning | 1, 17 |
| 2 | `pom.xml`, `src/main/resources/application.properties`, `application-docker.properties` in the same resources directory | Java/dependencies, default H2 versus Docker configuration, feature flags | 4–7, 17 |
| 3 | `docker-compose.yml`, `docker-compose.ai.yml`, `Dockerfile`, `docker/postgres/init.sql` | Dependency health conditions, model bootstrap, packaged resources, extension setup | 5–8 |
| 4 | `src/main/java/com/iiot/LocalServicesStartupCheck.java` | Docker-only PostgreSQL/vector and Ollama startup checks | 8, 20 |
| 5 | `src/main/resources/db/migration/V1__create_telemetry_schema.sql`, `V2__anomaly_alerts.sql` in the same directory | Tables, constraints, indexes, migration ownership | 10, 15 |
| 6 | `src/main/java/com/iiot/simulator/` | `TelemetrySimulator.java` and `SimulatorProperties.java`: readiness, cadence, stable IDs, spike/dropout injection | 9, 14 |
| 7 | `src/main/java/com/iiot/telemetry/TelemetryController.java`, `TelemetryQueryService.java` in the same package | HTTP inputs, range/pagination validation, latest-per-metric SQL | 9–10 |
| 8 | `src/main/java/com/iiot/telemetry/RollingAnomalyDetector.java` | Preceding-sample SQL windows, warm-up, noise floor, sentinel detection | 14 |
| 9 | `src/main/java/com/iiot/rag/EquipmentDocumentLoader.java`, `EquipmentIngestionService.java`, `NomicEmbeddingModel.java` in the same package | Metadata, section chunks, deterministic IDs, transaction rollback, embedding prefixes | 11 |
| 10 | `src/main/java/com/iiot/rag/RagConfiguration.java`, `EquipmentSearchController.java`, `RagAnswerService.java` in the same package | Vector setup, model options, retrieval, quote/code validation, abstention | 11 |
| 11 | `src/main/java/com/iiot/telemetry/TelemetryTools.java`, `src/main/java/com/iiot/agent/` | `AgentKnowledgeTools.java`, `AgentService.java`, `ConversationMemory.java`: callbacks, model choices, evidence checks, bounded memory | 12 |
| 12 | `src/main/java/com/iiot/mcp/` | `EquipmentMcpConfiguration.java` and `McpApiKeyFilter.java`: SDK transport, three tools, bearer scope | 13 |
| 13 | `src/main/java/com/iiot/alert/` | `AnomalyAlertService.java` and `AlertConfiguration.java`: live monitoring window, deduplication, SMTP/retries | 15 |
| 14 | `src/main/java/com/iiot/config/OpenApiConfiguration.java` and controller/model annotations | API metadata, schemas, feature-dependent documentation | 16 |
| 15 | `src/test/java/com/iiot/`, especially `demo/InterviewDemoTests.java` and `config/OpenApiDocumentationTests.java` | How fixtures isolate model behavior and how contracts are asserted | 18–19 |
| 16 | `scripts/demo.py`, `scripts/verify-rag-query.py`, `scripts/verify-agent.py`, `.github/workflows/ci.yml` | Offline replay versus live-model checks and additional PostgreSQL CI coverage | 18–19 |

Use [README.md](README.md) for the broader narrative and [docs/](docs/) for deeper
feature discussions. Treat dated verification results and historical workspace
ports in those documents as context; the active configuration and implementation
determine your run.
