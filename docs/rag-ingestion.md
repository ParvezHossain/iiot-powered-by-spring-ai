# Equipment embedding ingestion

The pipeline uses Spring AI 2.0.1's `VectorStore` abstraction with `PgVectorStore`
and a local Ollama `nomic-embed-text:v1.5` embedding model. It reads the ten
[equipment documents](equipment-corpus.md), splits them into sections and token
chunks, embeds them, and persists them in `public.equipment_vectors`.
The ingestion and search routes perform retrieval only. The separate
[RAG query endpoint](rag-query.md) uses retrieved passages to generate answers.

## Run with Docker Compose

Start the dependencies and download the embedding model once (approximately
274 MB, persisted in the existing Ollama volume):

```sh
docker compose up -d postgres ollama
docker compose exec ollama ollama pull nomic-embed-text:v1.5
RAG_ENABLED=true docker compose up -d --build --wait app
```

Use the same port overrides as your existing stack on every Compose command.
For example, this workspace's running stack uses `APP_PORT=8081` and
`POSTGRES_PORT=5433`. Export those values before the commands above if applicable.
The first RAG startup can take longer while the model loads and embeds the corpus.
Startup fails if the documents cannot be loaded or the embedding model is missing;
the app becomes ready only after successful startup ingestion.

All source Markdown files are packaged under `equipment/` in the executable JAR.
The corpus index and its expected answers are excluded. Editing bundled documents
requires rebuilding the JAR/image. Plain `./mvnw spring-boot:run` remains an H2
application with RAG disabled. To enable RAG outside Docker, supply PostgreSQL
datasource settings, `RAG_ENABLED=true`, and optionally `OLLAMA_BASE_URL`.

## Ingest and search manually

Ingestion runs automatically at startup by default. A manual refresh is also
available; it reads the configured source location and does not accept uploads:

```sh
curl --fail -X POST http://localhost:8080/api/documents/ingest
```

For the current corpus the response is:

```json
{"documents":10,"chunks":34,"model":"nomic-embed-text:v1.5"}
```

Run a semantic search, adjusting the port as needed:

```sh
curl --fail --get http://localhost:8080/api/documents/search \
  --data-urlencode 'query=What repair fixed the compressor overheating caused by a blocked cooling screen?' \
  --data-urlencode 'topK=3'
```

Each result has `id`, `score`, `text`, and `metadata`. Metadata includes
`document_id`, `document_type`, `machine_ids`, `source` (filename), `title`,
`section`, `chunk_index` (zero-based within a source), `revision`, `updated_at`,
`synthetic`, `corpus`, `embedding_model`, and `chunker_version`.
For bundled content, resolve `source` beneath `docs/equipment/` to cite the file.

`query` is required and must contain 1–2000 characters with nonblank content.
`topK` defaults to 5 and accepts 1–20. `threshold` defaults to 0.0 and accepts
finite values from 0 to 1. Invalid parameters return HTTP 400. Results are
ordered by cosine similarity, highest first; a higher threshold can return fewer
than `topK` results or an empty array. Scores indicate vector similarity, not
diagnostic confidence. Multiple sections of the same source can appear.
With RAG disabled, the ingestion and search routes are not registered.

## Chunking and replacement behavior

The loader validates YAML front matter and unique document IDs before any data
replacement. It splits within second-level Markdown headings using Spring AI's
`TokenTextSplitter`, targeting 300 tokens per section fragment. Every fragment
gets its document title and section heading prepended for retrieval context.
Short section tails are retained. Title/heading overhead is additional to the
300-token target; the splitter's tokenizer differs from the model's tokenizer.
Ollama truncation is disabled so an oversized input fails instead of silently
discarding source text.

The embedding adapter uses Nomic's `search_document:` prefix for document batches
and `search_query:` for search text. These prefixes are not stored in the source
content. The model and dimension (768) are fixed together in `RagProperties`.
Changing the embedding model requires a deliberate schema/index rebuild and
complete re-embedding; different embedding spaces must not be mixed.

Each ingestion is a full replacement of the `equipment-v1` corpus. It re-embeds
all current sources, removes stale chunks from edited/deleted sources, and leaves
other corpus metadata untouched. Stable UUID chunk IDs make repeated identical
ingestions produce the same IDs and row count. An empty or invalid input set
fails instead of clearing the index.

Deletion and batched `VectorStore.add` calls share a database transaction. An
embedding/write failure rolls back the replacement, retaining the previous
corpus. Readers see committed data. A PostgreSQL transaction advisory lock
serializes concurrent replacements across app instances. Each instance must
use the same corpus version; the last successful refresh determines its contents.
This simple implementation holds a transaction during embedding and is intended
for the small sample corpus, not large bulk ingestion jobs.

`PgVectorStore` initializes and validates its dedicated public table and required
extensions when RAG is enabled; it does not drop the table on startup. Flyway
continues to own the separate `telemetry` schema. The configured database user
needs permission to initialize the vector table and extensions in this local
development setup. Search uses exact cosine distance without an approximate
index, which is sufficient for this small corpus.

## Configuration

| Property / environment variable | Default | Purpose |
| --- | --- | --- |
| `rag.enabled` / `RAG_ENABLED` | `false` | Enable PostgreSQL-backed RAG beans and routes |
| `rag.ingest-on-startup` / `RAG_INGEST_ON_STARTUP` | `true` | Refresh corpus before application readiness |
| `rag.batch-size` / `RAG_BATCH_SIZE` | `16` | Chunks per embedding/write call, 1–64 |
| `rag.documents` / `RAG_DOCUMENTS` | `classpath:equipment/*.md` | Source resource pattern |
| `ollama.base-url` / `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama address; Docker profile uses `http://ollama:11434` |

For development with editable external files, a pattern such as
`RAG_DOCUMENTS='file:./docs/equipment/*.md'` works when launching the app from the
repository root. For Compose, an external source directory requires an explicit
read-only volume mount and `RAG_DOCUMENTS` entry in a Compose override; the
provided Compose file uses the bundled corpus. The model is pulled explicitly,
not automatically during application startup.

## Verification

Inspect persisted vectors:

```sh
docker compose exec postgres psql -U iiot -d iiot -c \
  "SELECT COUNT(*) AS chunks, COUNT(DISTINCT metadata->>'document_id') AS documents,
   MIN(vector_dims(embedding)) AS dimensions FROM public.equipment_vectors
   WHERE metadata->>'corpus' = 'equipment-v1';"
```

Initial T2.2 verification on 2026-09-07 stored **10 documents, 33 chunks, 768 dimensions**.
T2.3 adds the E204 source section, bringing the current corpus to **34 chunks**.
Real Ollama/pgvector searches returned these top matches:

| Query topic | First document / section | Cosine similarity |
| --- | --- | --- |
| Repair for compressor overheating from a blocked cooling screen | LOG-SIM-003 / Outcome | 0.7552 |
| Register 40001 is 65535 while the latest temperature is older | REF-REGISTERS-001 / Missing temperature and latest status | 0.6825 |
| Was the conveyor gearbox replaced after left-edge belt rubbing? | LOG-SIM-002 / Inspection and correction | 0.8013 |

Exact scores can vary with model/runtime changes. These are observed smoke-test
results, not guarantees for arbitrary queries.

`./mvnw verify` runs corpus, chunking, metadata, prefix, endpoint validation, and
the existing telemetry tests without downloading a model. CI additionally runs
`PgVectorIngestionTests` on a pgvector service with controlled embedding vectors;
it verifies persistence, repeat ingestion, stale chunk removal, metadata search,
and rollback after a mid-ingestion embedding failure. This persistence test does
not evaluate semantic quality; the live searches above do.

To run that optional test locally, point datasource settings at a **dedicated test
database** with pgvector available, then run:

```sh
RAG_PGVECTOR_TESTS=true ./mvnw -Dtest=PgVectorIngestionTests test
```

The test replaces and removes its equipment corpus. Do not run it against a
database whose equipment index you need to retain.

Implementation references: [Spring AI pgvector](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html),
[Ollama embeddings](https://docs.spring.io/spring-ai/reference/api/embeddings/ollama-embeddings.html),
and [Nomic task prefixes](https://huggingface.co/nomic-ai/nomic-embed-text-v1.5#task-instruction-prefixes).
