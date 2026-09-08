# One-command interview demo

From a prepared checkout, run:

```sh
python3 scripts/demo.py
```

The script prints a compact question/answer transcript and a final elapsed time.
It verifies every step before showing a successful transcript, exits nonzero on
failure, and stops the Maven/JVM process group after 110 seconds. Build logs are
hidden on success and shown on failure. It uses an ephemeral H2 database and a
random loopback HTTP port; Docker, PostgreSQL, Ollama, and credentials are not
required. Existing app services and databases are not changed.

Requirements: Linux/macOS, Python 3, and JDK 21+. On a fresh checkout, download
dependencies once before the interview:

```sh
./mvnw --batch-mode --no-transfer-progress -Dtest=InterviewDemoTests test
```

Preparation is not part of the timed demo; dependency downloads cannot be bounded
reliably. Subsequent demo runs use Maven offline. The script strips inherited
Spring, model, MCP, simulator, and Maven/JVM override environment settings to
keep the scenario isolated and predictable. It recompiles changed sources, so it
does not silently run a stale jar. Avoid running another Maven build in the same
checkout at the same time.

## What to show

1. **Data:** “What is SIM-001's latest vibration reading?” — resolves the machine
   and returns the seeded 7.2 mm/s reading with a timestamp and evidence label.
2. **Mixed:** “Is that normal, and what should I do?” — combines fresh telemetry
   with the pump manual's 1.2–2.4 mm/s range and isolation guidance, without
   inventing a root cause.
3. **Memory:** “And what about last week?” — reuses the same conversation and
   machine, queries the previous UTC calendar week, and reports 1.8, 2.0, and
   2.2 mm/s samples. Dates are derived at runtime so the follow-up remains valid.
4. **Knowledge:** “What does E204 mean?” — explains the missing temperature
   signal using the bundled fault reference.
5. **Detection and MCP:** the real rolling SQL detector flags the seeded jump
   against 30 prior samples at 2.0 mm/s. An MCP client lists and calls all three
   tools with a test credential; a request without a token receives 401.
6. **Automatic alert:** the background worker independently detects the spike
   and records/logs it. Gmail is disabled, so rehearsals cannot send real email.

## Be explicit about the fixtures

The banner identifies this as a **deterministic model and retrieval replay**.
Model decisions and answer text are scripted; vector search selects fixed bundled
documents. It does not demonstrate live LLM reasoning, embeddings, or pgvector
recall. These fixtures exist only in test sources, not the production jar or
production API. The demo key likewise belongs only to this isolated test server.

The real code handles HTTP, machine lookup, telemetry queries, conversation
memory, tool callbacks, evidence validation, RAG quote validation, statistical
scoring, MCP protocol exchange, and bearer authentication. Assertions check the
tool routes, returned data, source text, remembered context, detector score,
and authentication results. An implementation regression fails the demo rather
than displaying an unconditional canned success.

A useful interview explanation: “I use fixed model responses for this timed
replay so infrastructure and model latency don't obscure the system behavior.
Production uses Ollama on the same agent path. The replay tests the integration;
live model quality is evaluated separately.”

For a live model run, use [the agent checks](agent-chat.md) and
[MCP Inspector](mcp-server.md). Those require the local services and are outside
the two-minute guarantee. The existing small-model citation limitation still
applies; this demo does not claim to fix it.

## Verification

`InterviewDemoTests` runs in the normal `./mvnw verify` suite. CI also runs the
actual Python command with a two-minute job-step limit after dependencies have
been prepared. The script has its own shorter deadline for cleanup.

Local rehearsals completed in 28.5, 36.9, and 21.2 seconds. The last run started
from outside the repository with invalid inherited database/profile settings,
confirming that the script selected its own directory and isolated fixtures.
These are measured prepared-checkout times, not a hardware-independent latency promise.
