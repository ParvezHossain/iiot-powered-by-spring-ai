# Frontend API coverage

The audit covered `README.md`, `run.md` (the repository uses lowercase), the guides
and equipment corpus under `docs/`, all six REST controllers, and the SDK MCP
servlet. Every application controller operation now has a frontend workflow.
The equipment files contain knowledge content; alerting and simulator configuration
are server-side facilities with no additional management controllers.

| Method and endpoint | Frontend workflow | Access |
| --- | --- | --- |
| POST `/api/auth/register` | Account → Create account | Public; always USER |
| POST `/api/auth/login` | Header → Sign in | Public |
| POST `/api/auth/refresh` | Account → Renew session | Current in-memory refresh token |
| POST `/api/auth/logout` | Header → Sign out | Current refresh token |
| GET `/api/auth/me` | Account → Load account details | Signed in |
| GET `/api/admin/users` | Users → paginated list, Previous/Next/Reload | ADMIN |
| POST `/api/admin/users` | Users → Create a user | ADMIN |
| PUT `/api/admin/users/{id}/role` | Users → Make USER / Make ADMIN | ADMIN |
| PUT `/api/admin/users/{id}/status` | Users → Enable / Disable | ADMIN |
| GET `/api/machines` | Dashboard → Fleet telemetry | Signed in |
| GET `/api/machines/{id}/status` | Machine status; selected-machine link on dashboard | Signed in |
| GET `/api/machines/{id}/readings` | Dashboard → Sensor trends | Signed in |
| GET `/api/anomalies` | Dashboard → Anomaly stream | Signed in |
| GET `/api/documents/search` | Documents → Search passages | ADMIN |
| POST `/api/documents/ingest` | Documents → Replace and embed corpus | ADMIN |
| POST `/api/rag/query` | Dashboard → Equipment knowledge | ADMIN |
| POST `/api/agent/chat` | Dashboard → Agent chat | ADMIN |
| POST `/mcp` | MCP tools → Connect, Discover tools, Run tool | ADMIN |
| GET `/mcp` | MCP tools → Open event stream | ADMIN |
| DELETE `/mcp` | MCP tools → Close session | ADMIN |
| GET `/actuator/health` | Header → system health polling | Public |

Swagger/OpenAPI and readiness remain available directly through their documented
URLs. Frontend route names avoid backend namespaces, so refreshing `/documents`,
`/users`, `/account`, `/machine-status`, and `/mcp-tools` uses SPA routing.

## Behavior and limits

- Registration sends only username, email and password. Role fields shown in some
  generated examples are rejected by the backend and are never submitted.
- Password validation includes the 72-byte UTF-8 limit; passwords are not trimmed.
- Session renewal is explicit, serialized, and rotates both tokens in memory. No
  automatic refresh or mutation replay occurs. Failed or cancelled renewal clears
  the session because the single-use token may have been consumed. Renew before
  expiry; reloading the tab requires login.
- Account lists use 100-row pages. Self-demotion/disable controls are disabled;
  the backend also protects the last enabled administrator and reports conflicts.
  Successful role/status changes revoke the target's existing sessions.
- Search exposes `query`, `topK` and `threshold`, with source text, similarity and
  metadata. Ingestion replaces configured Markdown vectors, accepts no upload,
  and reports documents/chunks/model. A timeout does not undo server execution.
- MCP negotiates a protocol version and uses the returned session header. POST
  accepts JSON or SSE responses, GET receives server events, and DELETE closes the
  session. Tool schemas and results are rendered as text. Tool failures and
  JSON-RPC errors are distinct. The last 50 events are retained. Close the session
  before leaving; page destruction stops local streams but does not send DELETE.
- Account/session changes clear protected UI data and cancel pending browser work.
  ADMIN restrictions apply in the UI and remain enforced by the backend.

## Verification

Run `npm --prefix frontend test` and `npm --prefix frontend run build` using the
pinned Node version. Automated coverage includes auth rotation/failure/concurrency,
registration field filtering, user pagination/mutations/conflicts, document query
validation and ingestion, account/status reads, and MCP session headers,
JSON/SSE decoding, discovery/calls, GET cancellation and DELETE.

For a live check with configured PostgreSQL/pgvector/Ollama:

1. Register a disposable operator on Account, sign in, and load account details.
   Renew the session; confirm telemetry works. Sign out.
2. Sign in as ADMIN. On Users create a disposable account, promote/demote it, and
   disable/re-enable it. Confirm the target must sign in again after each change.
3. Select a machine on the dashboard and use **Inspect selected machine status**.
   Load its status and compare the latest reading timestamps.
4. On Documents search for E204. Check source metadata. Explicitly select corpus
   replacement, run ingestion, and check counts before searching again.
5. With `MCP_ENABLED=true`, connect and discover tools. Run `getRecentAnomalies`
   with `{"limit":10}`. Open/stop the event stream and close the session.
6. Sign in as USER and verify that ADMIN navigation is hidden and directly opening
   those frontend routes does not issue protected requests.
