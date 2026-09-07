# Equipment agent

`POST /api/agent/chat` is a stateless chat endpoint that lets an Ollama model
choose telemetry tools, document retrieval, both, or neither. It requires
`agent.enabled=true` and `rag.enabled=true`. The existing telemetry and RAG
endpoints remain available independently.

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

The response contains `answer`, `insufficientEvidence`, `evidenceIds`, and
`evidence`. Each evidence entry has a request-local ID such as `T1`, its tool
name, a success flag, and the tool's JSON result encoded as a string. Document
results include chunk IDs, text, metadata (including source filename), and
retrieval scores. Telemetry results retain reading timestamps and units in metric
names. Inline `[T1]` references point to the returned evidence.

The final synthesis step receives the original question and actual tool results.
It is instructed to cite both data and documents for mixed questions, distinguish
historic manual examples from measured telemetry, preserve uncertainty, and
avoid unproven hardware diagnoses. Normal ranges and actions must apply to the
resolved machine; generic anomaly thresholds do not establish normal operation.
A stored RUNNING status does not establish health or authorize restart.

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
read timeout per model call, with no automatic chat retry. No conversation
history is retained across requests.

`./mvnw verify` covers orchestration using controlled model outputs, actual tool
callbacks, identity lookup, source filtering, Spring wiring, validation, and
bounded execution. Controlled outputs test the loop, not a model's judgment.
Run the optional live acceptance check with a machine that has telemetry and
bundled documentation (SIM-001 by default):

```sh
python3 scripts/verify-agent.py http://localhost:8080 SIM-001
```

The script checks all four routing cases and prints the answers and evidence for
semantic review. It requires the model and PostgreSQL/pgvector services.
