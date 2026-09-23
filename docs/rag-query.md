# Grounded equipment questions

`POST /api/rag/query` retrieves equipment chunks from the existing Spring AI
`VectorStore` and sends them to an Ollama chat model. The response includes the
answer and citations resolved against the retrieved sources.

## Start and query

Follow the [ingestion setup](rag-ingestion.md) for PostgreSQL and the embedding
model, and also download the default chat model:

```sh
docker compose exec ollama ollama pull qwen2.5:1.5b
RAG_ENABLED=true docker compose up -d --build --wait app
curl --fail http://localhost:8080/api/rag/query \
  -H 'Content-Type: application/json' \
  -d '{"question":"what does error E204 mean"}'
```

Retain your stack's existing port overrides when rebuilding. For this workspace's
stack, export `APP_PORT=8081` and `POSTGRES_PORT=5433` before the Compose commands,
and use port 8081 in curl. The endpoint requires ADMIN access and returns 503 when RAG is disabled.
Chat model downloads are explicit; startup ingestion uses only the embedding
model. A missing chat model is reported when answering a supported question.

The updated bundled fault reference defines **E204 as an unavailable motor
temperature sensor signal on the fictional SIM-003 AC-7 compressor**, explicitly
distinct from overheating. This definition was added as synthetic source content
for T2.3; it is not an industry-wide code definition or an emitted simulator code.
Rebuild and re-ingest the updated documents before asking about it. By default,
startup performs that ingestion; with startup ingestion disabled, call
`POST /api/documents/ingest` after rebuilding.

## Request and response

The JSON `question` field is required, must be nonblank, and accepts at most 2000
characters. Invalid input returns HTTP 400. Retrieval or model failures return
HTTP 503; an unavailable service is not presented as missing evidence.

Successful supported answers have this shape (answer wording varies):

```json
{
  "answer": "E204 means the motor temperature sensor signal is unavailable on the fictional SIM-003 AC-7 compressor. It does not mean motor overheating.",
  "insufficientEvidence": false,
  "citations": [
    {
      "sourceId": "S1",
      "chunkId": "<stored chunk UUID>",
      "documentId": "REF-FAULTS-001",
      "source": "fault-code-reference.md",
      "section": "E204 — Compressor temperature sensor signal unavailable",
      "quote": "<verbatim supporting passage from the source>",
      "score": 0.7
    }
  ]
}
```

The example score is illustrative. `sourceId` labels are local to one request;
`chunkId` identifies a stored chunk. Source filenames resolve under
`docs/equipment/` for the bundled corpus. Scores measure retrieval similarity,
not answer correctness. Citations contain the model's selected supporting quotes,
checked against retrieved text, with source metadata supplied by the service.

If no relevant evidence is found, the code is absent, the model abstains, or its
output fails citation validation, HTTP 200 returns:

```json
{
  "answer": "I don't have enough evidence in the equipment documents to answer that question.",
  "insufficientEvidence": true,
  "citations": []
}
```

Try `what does error E999 mean` to exercise the unknown-code path. The model is
not called when the retrieved sources do not contain the requested code.

## Grounding behavior and limits

Retrieval is scoped to the equipment corpus. Default selection uses the six
nearest chunks above cosine similarity 0.45. A question containing a code-like
identifier (one to four letters, optional hyphen, two to five digits) additionally
requires an exact case-insensitive token match in retrieved text. For multiple
identifiers, each must occur in the retained passages. E2040 does not match E204.
This conservative check can abstain when retrieval misses a valid source; it
does not search outside the index or guess a definition.

The system prompt requires source-only answers and treats both question and
passages as data, not instructions that override the grounding rules. The chat
instructions also prohibit inferring a hardware failure from an unavailable
signal or invalid measurement without explicit source evidence. The chat
model runs at temperature zero with seed 42, an 8192-token context and a 700-token
output limit. Ollama receives a JSON schema requiring an answer, an abstention
flag, and at least one citation; prompt instructions alone do not ensure those fields.
When the model abstains, the service discards its reviewed passage citations and
returns an empty citations array to the caller.
The service discards malformed output, empty citations, unknown source labels,
and quotes that cannot be found in the cited chunk (whitespace is normalized).
Each requested code must also appear as a complete token in at least one validated
quote; a real but unrelated quote is insufficient evidence for that code.

These checks verify citation existence and quote fidelity, not semantic entailment
of every generated claim. An LLM can still misinterpret a passage while citing
it; the returned quotes make review possible. This endpoint does not diagnose
live machines, invoke tools, or maintain conversation history.

## Configuration and tests

| Property | Default | Meaning |
| --- | --- | --- |
| `rag.answer.model` | `qwen2.5:1.5b` | Local chat model; Compose forwards `RAG_ANSWER_MODEL` |
| `rag.answer.top-k` | `6` | Retrieved chunks, 1–8 |
| `rag.answer.threshold` | `0.45` | Minimum cosine similarity, 0–1 |

The existing `OLLAMA_BASE_URL` is shared by embedding and chat. Requests use a
five-second connection timeout and a two-minute read timeout; chat calls do not
retry automatically. Changing the chat model does not require re-embedding.

`./mvnw verify` includes deterministic tests using the actual bundled E204 passage
and controlled model outputs. They check citation provenance, unknown identifiers,
empty retrieval, malformed output, invented evidence, validation, and HTTP 503
failures. Semantic behavior must also be checked with a running model using the
curl request above; controlled-output tests alone do not establish answer quality.

An optional live acceptance script checks the E204 answer, verifies its quotes
against the source file, and checks that E999 returns insufficient evidence:

```sh
python3 scripts/verify-rag-query.py http://localhost:8080
```

The model configuration uses the [Spring AI Ollama chat API](https://docs.spring.io/spring-ai/reference/api/chat/ollama-chat.html).
