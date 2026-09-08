# Equipment MCP server

Set `MCP_API_KEY` and enable `MCP_ENABLED=true` to expose a Streamable HTTP MCP endpoint at `/mcp` on
the app's existing port. It uses `io.modelcontextprotocol.sdk:mcp:2.0.0` directly:
the SDK handles initialization, capability negotiation, tool discovery, calls,
HTTP sessions, and shutdown. Existing REST and chat endpoints continue to work.
The implementation follows the [official Java SDK server API](https://java.sdk.modelcontextprotocol.io/latest/server/).

## Start

For all three tools, follow the [RAG setup](rag-query.md) to install the embedding
and chat models and ingest the documents, then enable MCP as well:

```sh
# Generate once, then use the same key for the server and your client.
export MCP_API_KEY="$(openssl rand -hex 32)"
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

## Connect an MCP client

Select **Streamable HTTP**, enter the `/mcp` URL, and set the custom header
`Authorization: Bearer <your MCP_API_KEY>` in an MCP-compatible client.
This is an HTTP service, not a stdio process or legacy `/sse` endpoint.

The [official MCP Inspector CLI](https://github.com/modelcontextprotocol/inspector/blob/main/clients/cli/README.md)
can list and call the tools without involving an LLM (requires Node.js and npm):

```sh
MCP_URL=http://localhost:8081/mcp
npx @modelcontextprotocol/inspector --cli "$MCP_URL" --transport http \
  --header "Authorization: Bearer $MCP_API_KEY" --method tools/list
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
  --header "Authorization: Bearer $MCP_API_KEY" \
  --method tools/call --tool-name getMachineStatus --tool-arg "machineId=$MACHINE_ID"
npx @modelcontextprotocol/inspector --cli "$MCP_URL" --transport http \
  --header "Authorization: Bearer $MCP_API_KEY" \
  --method tools/call --tool-name getRecentAnomalies --tool-arg "machineId=$MACHINE_ID"
npx @modelcontextprotocol/inspector --cli "$MCP_URL" --transport http \
  --header "Authorization: Bearer $MCP_API_KEY" \
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

`MCP_API_KEY` (Spring property `mcp.api-key`) is required when MCP is enabled.
It must contain 32–256 bearer-token characters with no whitespace; the generation
command above produces a suitable random key. There is no default credential.
Keep the key in the environment rather than source control. Rotate it by replacing
the environment value and restarting/recreating the app; existing clients must
then use the new key. MCP-disabled startup requires no key.

Every `/mcp` request requires exactly one `Authorization: Bearer <key>` header,
including initialization, tool discovery/calls, streaming GETs, and session DELETEs.
The filter runs before the SDK and uses a constant-time byte comparison. A session
ID is not a credential. Missing, incorrect, malformed, or duplicate authorization
headers return HTTP 401 with `WWW-Authenticate: Bearer realm="iiot-mcp"` and a
generic body. Tokens in query parameters are not accepted, and rejected requests
do not reach the tools. Configuration errors and rejection bodies omit credentials.

The deliberate demo scope is **one shared key for all three read-only MCP tools
and all machines**. It provides an explicit access gate with no user accounts,
per-machine roles, or per-tool permissions. This filter covers `/mcp` and its
subpaths; the existing REST/chat APIs and health endpoint keep their current
access behavior. It is not application-wide authentication or an OAuth server.
Use HTTPS if carrying the token beyond a trusted local connection.

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
with a valid session ID. Authenticated SDK discovery and all tool calls succeed;
missing or invalid server keys prevent startup. These tests establish MCP interoperability and adapter
behavior; they do not claim a Claude Desktop session or live model evaluation.
