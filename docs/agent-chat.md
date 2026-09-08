# Equipment agent

`POST /api/agent/chat` is a conversational endpoint that lets an Ollama model
choose telemetry tools, document retrieval, both, or neither. It requires
`agent.enabled=true` and `rag.enabled=true`. The existing telemetry and RAG
endpoints remain available independently.

## Conversation memory

Omit `conversationId` on the first request. The response includes a server-generated
UUID; send it with each follow-up to continue that conversation. Existing clients
that send only `question` continue to work, starting a new conversation each time.

```sh
curl --fail http://localhost:8080/api/agent/chat \
  -H 'Content-Type: application/json' \
  -d '{"question":"Find machine 12."}'

# Replace the value with conversationId from the first response.
CONVERSATION_ID=returned-uuid
curl --fail http://localhost:8080/api/agent/chat \
  -H 'Content-Type: application/json' \
  -d "{\"conversationId\":\"$CONVERSATION_ID\",\"question\":\"What is its latest vibration reading?\"}"
curl --fail http://localhost:8080/api/agent/chat \
  -H 'Content-Type: application/json' \
  -d "{\"conversationId\":\"$CONVERSATION_ID\",\"question\":\"And what about last week?\"}"
```

Use an existing machine (for example SIM-001 in the default simulator). Machine
12 must exist to run this example without clarification. Prior questions, answers,
and resolved identities reach both tool selection and final synthesis. The model
can reuse a resolved UUID, but must fetch new evidence for factual answers. Old
`[T1]` labels are historical context; citation IDs and evidence restart each turn.
“Last week” means the previous Monday through Sunday in UTC. The server supplies
explicit inclusive bounds; the model should state the dates and query that range.
Missing historical readings produce an insufficient-evidence answer.

Memory is local to one app process and disappears on restart. It retains up to
six completed turns and 24,000 context characters per conversation, with up to
8,000 answer characters and 32 remembered machine identities per turn. Older
turns are dropped first. Sessions expire after 30 minutes idle; at most 128 are
retained. An expired or unknown ID returns HTTP 404, a malformed UUID HTTP 400,
and an overlapping request for the same conversation HTTP 409. Capacity exhaustion
returns HTTP 503. Failed model calls do not append partial turns. Requests for
different conversations can proceed independently. Conversation IDs are opaque
handles, not authenticated user identities; this local endpoint has no user accounts.
Multi-instance deployments need shared storage or session affinity.

The three-turn HTTP regression uses a machine-12 lookup, a pronoun-based latest
vibration question, and “And what about last week?”. It checks UUID reuse, the
historical metric/range query, context in both model stages, and fresh citations.
Controlled model outputs verify memory and orchestration, not live model judgment.

## Start

Follow [RAG setup](rag-query.md) to ingest documents and install the embedding
model. The agent uses its own tool-capable chat model configuration, separate
from the RAG answer model's JSON output schema:

```sh
docker compose exec ollama ollama pull qwen2.5:1.5b
AGENT_ENABLED=true RAG_ENABLED=true docker compose up -d --build --wait app
curl --fail http://localhost:8080/api/agent/chat \
  -H 'Content-Type: application/json' \
  -d '{"question":"Is SIM-001 vibration normal, and what should I do if not?"}'
```

Preserve existing Compose port overrides. This workspace uses `APP_PORT=8081`
and `POSTGRES_PORT=5433`. Model downloads are explicit.

## Routing and machine identity

The agent's callback provider combines the three [telemetry tools](telemetry-api.md)
with `retrieveEquipmentKnowledge` and `resolveMachine`. The model selects native
function calls; there is no keyword-based router. Calls and results remain in
one request's conversation so later calls can use earlier results.
Status and history tools are exposed once the question supplies a UUID or a
lookup returns one unique machine. This prevents name-to-UUID argument mistakes;
the model still chooses lookup, retrieval, or neither. Fleet-wide anomaly
queries remain available before lookup.

- Data: “What is the latest vibration reading for SIM-001?” resolves the machine
  and reads telemetry.
- Knowledge: “What does error E204 mean?” retrieves source passages.
- Mixed: “Is SIM-001's vibration reading normal, and what should I do if not?”
  reads telemetry and retrieves applicable operating/maintenance guidance.
- Neither: greetings and capability questions need no external evidence.

Machine IDs are UUIDs, not integer primary keys. `resolveMachine` matches exact
stored names case-insensitively; numeric simulator aliases such as `12` also
look up `SIM-012`. It returns only existing database records. With the default
five-machine simulator there is no machine 12. That question needs clarification
unless SIM-012 exists; no UUID or reading should be fabricated. Duplicate names
also require clarification. A UUID can be supplied directly in the question.

## Response and grounding

The response contains `conversationId`, `answer`, `insufficientEvidence`, `evidenceIds`, and
`evidence`. Each evidence entry has a request-local ID such as `T1`, its tool
name, a success flag, and the tool's JSON result encoded as a string. Document
results include chunk IDs, text, metadata (including source filename), and
retrieval scores. Telemetry results retain reading timestamps and units in metric
names. Inline `[T1]` references point to the returned evidence.

The final synthesis step receives the original question and actual tool results.
It is instructed to cite both data and documents for mixed questions, distinguish
historic manual examples from measured telemetry, preserve uncertainty, and
avoid unproven hardware diagnoses. Normal ranges and actions must apply to the
resolved machine; statistical anomaly flags do not establish normal operation.
A stored RUNNING status does not establish health or authorize restart. Statistical
anomaly scores describe deviations from recent history, not safe operating limits.

The server checks citation IDs against successful executed tools, rejects missing
or mismatched references, and never uses the model to invent evidence records.
These are provenance checks, not a proof of semantic entailment. Model quality
still affects routing and interpretation; inspect the returned source passages
and timestamps when evaluating an answer. Empty results and dependency failures
must not be treated as evidence of healthy equipment.

Invalid requests (blank/missing question or more than 2000 characters) return
HTTP 400. Model failures return HTTP 503. Tool failures are returned to the model
as sanitized errors so it can clarify or report unavailable evidence; the
response trace marks those calls unsuccessful. Invalid final output or exhausted
limits returns HTTP 200 with `insufficientEvidence=true`.

## Limits and verification

| Property | Default | Meaning |
| --- | --- | --- |
| `agent.enabled` | `false` | Opt in; also requires `rag.enabled=true` |
| `agent.model` | `qwen2.5:1.5b` | Tool-capable Ollama model; Compose forwards `AGENT_MODEL` |
| `agent.max-rounds` | `4` | Maximum tool-decision rounds (1–8), plus one synthesis call |
| `agent.max-tool-calls` | `8` | Maximum executed calls per request (1–16) |

Agent data queries accept at most 100 rows per call. Tool results are limited to
30,000 characters each and 50,000 total per request. Retrieval uses the existing
RAG top-k, similarity threshold, corpus filter, and literal-code matching rules.
The shared Ollama client has a five-second connection timeout and two-minute
read timeout per model call, with no automatic chat retry. Conversation history
is retained only when the client reuses its returned `conversationId`.

`./mvnw verify` covers orchestration using controlled model outputs, actual tool
callbacks, identity lookup, source filtering, Spring wiring, validation, and
bounded execution. Controlled outputs test the loop, not a model's judgment.
The HTTP acceptance test also asks the exact machine-12 mixed question without
supplying a UUID. Its test fixture resolves `12` to `SIM-012`, supplies a timestamped
7.2 mm/s reading and a matching manual's 5 mm/s inspection threshold, and checks
that both results reach synthesis and appear in the cited response. These values
are test fixtures, not additional fleet records or operating guidance.
Run the optional live acceptance check with a machine that has telemetry and
bundled documentation (SIM-001 by default):

```sh
python3 scripts/verify-agent.py http://localhost:8080 SIM-001
```

The script checks all four routing cases and prints the answers and evidence for
semantic review. It requires the model and PostgreSQL/pgvector services.

T3.2 local validation (2026-09-08): `./mvnw verify` passed with 57 tests,
zero failures/errors, and one skipped integration test. With `qwen2.5:1.5b`, the
live data scenario resolved SIM-001 and obtained telemetry, but final citation
validation rejected the generated answer. The live script stopped at that
scenario; full real-model acceptance remains unverified.

T3.3 memory validation: `./mvnw verify` passed with 63 tests, zero failures/errors,
and one skipped integration test, including the three-turn HTTP regression,
conversation isolation, expiration, capacity, concurrent turns, and failed-turn recovery.
