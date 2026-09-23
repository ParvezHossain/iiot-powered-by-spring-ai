# Equipment MCP server

Log in as an ADMIN and enable `MCP_ENABLED=true` to expose a Streamable HTTP MCP endpoint at `/mcp` on
the app's existing port. It uses `io.modelcontextprotocol.sdk:mcp:2.0.0` directly:
the SDK handles initialization, capability negotiation, tool discovery, calls,
HTTP sessions, and shutdown. Existing REST and chat endpoints continue to work.
The implementation follows the [official Java SDK server API](https://java.sdk.modelcontextprotocol.io/latest/server/).

## Start

For all three tools, follow the [RAG setup](rag-query.md) to install the embedding
and chat models and ingest the documents, then enable MCP as well:

```sh
# Configure JWT signing/bootstrap as described in authentication.md first.
MCP_ENABLED=true RAG_ENABLED=true docker compose up -d --build --wait app
```

Preserve your Compose port overrides. For this workspace's existing stack:

```sh
APP_PORT=8081 POSTGRES_PORT=5433 MCP_ENABLED=true RAG_ENABLED=true \
  docker compose up -d --build --wait app
```

The URL is `http://localhost:8081/mcp` for that command, or
`http://localhost:8080/mcp` with default ports. MCP does not require
`AGENT_ENABLED`. For telemetry-only local development with H2:

```sh
MCP_ENABLED=true SERVER_ADDRESS=127.0.0.1 ./mvnw spring-boot:run
```

All three tools remain discoverable when RAG is disabled; `ragQuery` then returns
an MCP tool error explaining the required setup. With MCP disabled (the default),
the servlet and MCP server are not registered.

## Use the frontend

Sign in as ADMIN and open **MCP tools** (`/mcp-tools`). Choose **Connect**, then
**Discover tools**. Select a tool, inspect its input schema, enter JSON parameters,
and choose **Run tool**. Results distinguish protocol errors from tool failures.
**Open event stream** uses an authenticated GET; the stream can remain idle.
**Close session** sends DELETE. Leaving the page or changing credentials stops
browser streams and forgets local state; close first to terminate the server
session explicitly. Session IDs come from initialization, not user-entered values.

## Connect an MCP client

Select **Streamable HTTP**, enter the `/mcp` URL, and set the custom header
`Authorization: Bearer <your ADMIN access token>` in an MCP-compatible client.
This is an HTTP service, not a stdio process or legacy `/sse` endpoint.

The [official MCP Inspector CLI](https://github.com/modelcontextprotocol/inspector/blob/main/clients/cli/README.md)
can list and call the tools without involving an LLM (requires Node.js and npm):

```sh
MCP_URL=http://localhost:8081/mcp
npx @modelcontextprotocol/inspector --cli "$MCP_URL" --transport http \
  --header "Authorization: Bearer $ACCESS_TOKEN" --method tools/list
```

Expected names: `getMachineStatus`, `getRecentAnomalies`, `ragQuery`. To obtain an
existing machine UUID from the local Compose database:

```sh
docker compose exec postgres psql -U iiot -d iiot \
  -c 'SELECT id, name FROM telemetry.machines ORDER BY name;'
```

Then call each tool:

```sh
MACHINE_ID=replace-with-existing-uuid
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

Set the client's tool-call timeout to at least three minutes for RAG on CPU
Ollama. Underlying Ollama calls retain their existing two-minute read timeout.
A model download is never triggered by an MCP call.

## Tool contract

| Tool | Arguments | Result |
| --- | --- | --- |
| `getMachineStatus` | Required `machineId` UUID | Stored status, machine metadata, latest sample per metric with timestamps |
| `getRecentAnomalies` | Optional `machineId`, inclusive ISO-8601 `from`/`to`, `limit` (1–1000, default 100), `offset` (nonnegative, default 0) | Statistical deviations with baseline statistics, plus explicit dropouts; defaults to the last hour, newest first |
| `ragQuery` | Required nonblank `question`, at most 2000 characters | Grounded answer, `insufficientEvidence`, checked quotes and source citations |

All tools are read-only and expose JSON input schemas. Telemetry uses the same
[query services and rolling detector](telemetry-api.md) as REST. MCP does not route
through the agent or share its conversation memory; the client owns its context.

Successful calls return `isError=false`, the result serialized as JSON in a text
content block, and the same data under `structuredContent.result`. An anomaly
array can be empty without indicating healthy equipment. A RAG response with
`insufficientEvidence=true` is a successful query that found insufficient support,
not a transport failure. Invalid arguments, nonexistent machines, disabled RAG,
and dependency failures return `isError=true`; dependency details are sanitized.
Unknown tools are handled by the SDK's protocol error path.

## Authentication and scope

Every `/mcp` request requires exactly one `Authorization: Bearer <ADMIN access token>`
header, including initialization, tool discovery/calls, streaming GETs and session
DELETEs. Obtain the token from `/api/auth/login` using an ADMIN account; see
[Authentication](authentication.md). The same Spring Security JWT filter chain
protects REST and MCP. Signature, issuer, audience, expiry, account status and
session version are validated on every request. Shared `MCP_API_KEY` credentials
are no longer accepted or required.

Missing, invalid, expired or revoked tokens return 401. Valid USER tokens return
403, including when presented with an existing admin session ID. A session ID is
not a credential. Error bodies contain `status` and `message`. Tokens in query
parameters and duplicate Authorization headers are rejected. Refresh the user
JWT through `/api/auth/refresh` and update the MCP client's header before it expires.
All ADMINs have access to the three read-only tools and shared machine data.
Use HTTPS outside local development.

Swagger documents `/mcp` under **MCP tool calling**, with initialize, initialized,
tools/list and tools/call examples. Use an MCP client for the session lifecycle
and streaming; POST requires `Accept: application/json, text/event-stream`.
The protocol is documented even when disabled; enable `MCP_ENABLED` to execute it.

Host and Origin validation also accepts
localhost, 127.0.0.1, and ::1 (any port); absent Origin is allowed for native clients.
Compose publishes the app only on loopback. A remote deployment requires an
intentional access-control and host/origin configuration change.

## Automated acceptance

```sh
./mvnw -Dtest=EquipmentMcpTests,EquipmentMcpWiringTests test
./mvnw verify
```

The official MCP Java SDK client sends the bearer header to a real embedded HTTP server,
initializes, lists exactly the three tools, and successfully calls each one.
Telemetry is read from seeded H2 records. RAG runs through the real answer and
citation-validation service with controlled vector and chat outputs, so no
Ollama download is needed in CI. Checks also cover invalid arguments, missing
machines, dependency failure, insufficient evidence, rejected Origin, opt-in
wiring, and disabled RAG. Authentication checks reject missing/wrong/malformed
credentials across MCP methods, duplicate headers, and query-string tokens—even
with a valid session ID. USERs cannot discover or execute tools. ADMIN SDK
discovery and all tool calls succeed; no separate server key is needed. These tests establish MCP interoperability and adapter
behavior; they do not claim a Claude Desktop session or live model evaluation.
