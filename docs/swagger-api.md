# Interactive API documentation

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
stack also exposes documents, RAG, and chat. Disabled controllers do not appear
in Swagger. Simulator and alert workers have no HTTP endpoints.

REST and health require no authentication. No global bearer requirement is added
to the specification. Errors document status and meaning; their body is managed
by Spring Boot and can vary with error configuration. A successful AI response
can still have `insufficientEvidence=true`; inspect that field and the evidence.

## MCP

`/mcp` is a separate SDK servlet implementing Streamable HTTP JSON-RPC, not a REST
controller. Swagger does not model its session lifecycle or tool discovery.
When `mcp.enabled=true`, use an MCP client with
`Authorization: Bearer <MCP_API_KEY>`. Missing or invalid credentials return 401.
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
