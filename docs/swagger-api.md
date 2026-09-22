# Interactive API documentation

> REST APIs now require JWT authentication. Use `/api/auth/login`, then enter the
> returned access token in **Authorize**. All AI/document routes, MCP and `/api/admin/**`
> require ADMIN. Public auth routes, health and documentation remain accessible.
> See [Authentication](authentication.md) for setup, refresh, bootstrap and examples.
> Older curl examples below require `-H "Authorization: Bearer $ACCESS_TOKEN"`
> when calling `/api/**` business endpoints.


Run `./mvnw spring-boot:run` or start the Docker stack described in the README.

- Swagger UI: <http://localhost:8080/swagger-ui/index.html> (`/swagger-ui.html` redirects here).
- OpenAPI JSON: <http://localhost:8080/v3/api-docs>.
- OpenAPI YAML: <http://localhost:8080/v3/api-docs.yaml>.

Use the configured application port if it differs from 8080. The specification
is generated from the running controllers, including parameter constraints,
examples, request/response schemas, and supported error statuses. Swagger UI's
**Try it out** executes real requests. In particular, document ingestion replaces
the stored equipment corpus and invokes the embedding model.

## Endpoint coverage

| Method | Path | Availability / purpose |
| --- | --- | --- |
| GET | `/api/machines/{id}/status` | Latest reading per metric and machine metadata |
| GET | `/api/machines/{id}/readings` | Inclusive time range, optional metric, offset pagination |
| GET | `/api/anomalies` | Rolling anomalies; defaults to the last hour across machines |
| POST | `/api/documents/ingest` | `rag.enabled=true`; replace the equipment corpus |
| GET | `/api/documents/search` | `rag.enabled=true`; semantic chunk retrieval |
| POST | `/api/rag/query` | `rag.enabled=true`; answer with validated source quotes |
| POST | `/api/agent/chat` | Both `rag.enabled=true` and `agent.enabled=true`; conversational tool use |
| GET | `/actuator/health` | Application health; available health groups are also exposed by Actuator |

The default H2 development mode exposes telemetry and health. The full AI Compose
stack also exposes documents, RAG, and chat. AI controllers remain visible in Swagger; disabled services return 503 to ADMIN callers. Simulator and alert workers have no HTTP endpoints.

REST requires JWT bearer authentication. The specification declares a global
bearer requirement with public authentication operation overrides. Health is
public. Authentication errors use consistent status/message bodies; existing
business errors retain Spring Boot conventions. A successful AI response
can still have `insufficientEvidence=true`; inspect that field and the evidence.

## MCP

`/mcp` is an SDK servlet implementing Streamable HTTP JSON-RPC. Swagger explicitly
documents initialization, tool discovery/call examples, event streaming and session
deletion under **MCP tool calling**. Use an MCP client for protocol session and
streaming support; send `Accept: application/json, text/event-stream` on POST.
When `mcp.enabled=true`, use an MCP client with
`Authorization: Bearer <ADMIN access token>`. Missing or invalid credentials return
401; authenticated USER accounts receive 403. Shared MCP keys are no longer accepted.
Use `tools/list` to discover `getMachineStatus`, `getRecentAnomalies`, and `ragQuery`.
See [MCP server](mcp-server.md) for connection and protocol details.

## Configuration

Springdoc 3.x supports Spring Boot 4 (see the
[official compatibility documentation](https://springdoc.org/faq.html)).
The Maven version is pinned in `springdoc.version`.

To disable documentation in a deployment, set both:

```properties
springdoc.api-docs.enabled=false
springdoc.swagger-ui.enabled=false
```

The UI and spec otherwise use the application's host, port, and context path.
