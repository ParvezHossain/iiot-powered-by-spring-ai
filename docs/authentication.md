# Authentication and authorization

The existing Java 21 / Spring Boot 4.1.1 MVC backend now uses Spring Security's
OAuth2 Resource Server and Nimbus JWT support. Controllers remain under `/api`;
JDBC and Flyway remain the persistence mechanisms. There is no server session or
server-rendered login. No frontend is included.

## Configuration and first startup

Set these in the environment for `./mvnw spring-boot:run`, or in the ignored `.env`
for Docker Compose. Compose explicitly forwards the settings to the application.
Spring Boot does **not** load `.env` for host execution.

| Environment variable | Default / requirement |
| --- | --- |
| `AUTH_JWT_SECRET` | Required on every startup; Base64 of at least 32 random bytes. Generate with `openssl rand -base64 32`. MCP uses this same JWT authentication. |
| `AUTH_INITIAL_ADMIN_USERNAME` | Required when no ADMIN exists; 3–64 ASCII letters, digits, dots, underscores or hyphens. |
| `AUTH_INITIAL_ADMIN_EMAIL` | Required when no ADMIN exists; valid email, at most 254 characters. |
| `AUTH_INITIAL_ADMIN_PASSWORD` | Required when no ADMIN exists; at least 12 characters, at most 72 UTF-8 bytes. |
| `AUTH_ISSUER` | `iiot-api`; must match signed access tokens. |
| `AUTH_AUDIENCE` | `iiot-spa`; must match signed access tokens. |
| `AUTH_ACCESS_TTL` | `PT15M`; positive duration, maximum one hour. |
| `AUTH_REFRESH_TTL` | `P7D`; greater than access TTL, maximum 90 days. |
| `AUTH_BCRYPT_STRENGTH` | `12`; permitted work factors 10–16. Benchmark on deployment hardware. |
| `AUTH_MAX_LOGIN_ATTEMPTS` | `5`; failed attempts before temporary account lock. |
| `AUTH_LOCKOUT` | `PT15M`; temporary account lock duration. |
| `AUTH_REQUESTS_PER_MINUTE` | `30`; auth POST requests per socket peer per application instance. |
| `AUTH_CORS_ORIGINS` | Empty (same-origin); comma-separated exact origins, e.g. `http://localhost:4200`. Wildcards are rejected. |

Credentials and signing keys have no production defaults. Keep `.env` permissions
restricted and inject production values through your deployment's secret store.
Changing the signing key invalidates existing access tokens; refresh tokens can
still obtain tokens signed with the replacement key. All instances must share the
same configuration and database. Coordinate key changes across instances.

Flyway runs before an application runner creates the initial ADMIN. A singleton
row lock serializes initialization across instances. If any ADMIN already exists,
startup does not modify credentials, roles or status. You may then remove the
bootstrap credentials from the deployment. If no ADMIN exists, missing/invalid
bootstrap credentials or an existing conflicting username/email fail startup;
the initializer never promotes an existing ordinary account. H2 is ephemeral, so
local development recreates its admin after each process restart. PostgreSQL
persists users across restarts. Removing bootstrap environment values does not
change the stored admin password.

## API contract

All bodies are JSON. User responses contain only `id`, `username`, `email`, `role`
and `enabled`. Usernames/emails are stored lowercase; usernames cannot contain `@`,
so username-or-email login has an unambiguous namespace. Passwords are not trimmed.

| Method | Endpoint | Access / result |
| --- | --- | --- |
| POST | `/api/auth/register` | Public; creates enabled USER, returns 201 and user view. |
| POST | `/api/auth/login` | Public; username/email and password exchange. |
| POST | `/api/auth/refresh` | Public route requiring a valid refresh token in its body. |
| POST | `/api/auth/logout` | Public route accepting refresh token; revokes all user sessions, returns 204. |
| GET | `/api/auth/me` | Authenticated; current user view. |
| POST | `/api/admin/users` | ADMIN; creates USER or ADMIN, returns 201. |
| GET | `/api/admin/users?limit=100&offset=0` | ADMIN; paginated array, maximum 100 users. |
| PUT | `/api/admin/users/{id}/role` | ADMIN; body `{"role":"USER"}` or `{"role":"ADMIN"}`. |
| PUT | `/api/admin/users/{id}/status` | ADMIN; body `{"enabled":false}` or `{"enabled":true}`. |
| GET/POST | `/api/documents/**` | ADMIN; document search and ingestion. |
| POST | `/api/rag/query`, `/api/agent/chat` | ADMIN; questions, chat and agent tool execution. |
| GET/POST | Other business APIs | Authentication required by default. |
| GET | Health and Swagger/OpenAPI routes | Explicitly public. |
| Any | `/mcp` | ADMIN JWT on every protocol request, including tool discovery/calls and session deletion. Shared API keys are no longer accepted. |

AI REST routes remain visible in Swagger even when their services are disabled;
ADMIN callers then receive 503. Set `RAG_ENABLED=true` and `AGENT_ENABLED=true`
with PostgreSQL/pgvector and Ollama configured to execute them. `/mcp` is documented
with JSON-RPC examples under **MCP tool calling**; set `MCP_ENABLED=true` to register
the SDK servlet. Tool discovery/execution is available through MCP and agent chat.
Telemetry REST access remains available to USER and ADMIN. Normal registration
creates USER accounts and therefore does not grant access to any AI feature.

New public routes must be deliberately added to `SecurityConfiguration`. New roles
can be added to `AuthRepository.Role` and to standard Spring Security policies;
there is no database enum or role CHECK constraint to rewrite. Admin services also
use `@PreAuthorize` for defense in depth. Telemetry is currently shared between
users; this change does not introduce machine ownership or tenancy. Chat histories
are bound to their authenticated creator and return 404 to other users.

## Register and obtain tokens

Example registration body (choose your own password):

```http
POST /api/auth/register
Content-Type: application/json

{"username":"operator1","email":"operator1@example.com","password":"replace-with-your-own-long-password"}
```

```json
{"id":"<uuid>","username":"operator1","email":"operator1@example.com","role":"USER","enabled":true}
```

Supplying `role` or `roles` during registration returns 400. Duplicate username or
email returns 409. Login accepts either normalized username or email:

```http
POST /api/auth/login
Content-Type: application/json

{"usernameOrEmail":"operator1","password":"replace-with-your-own-long-password"}
```

```json
{
  "accessToken":"<signed-jwt>",
  "tokenType":"Bearer",
  "expiresIn":900,
  "refreshToken":"<opaque-random-token>",
  "refreshExpiresAt":"2026-10-01T12:00:00Z",
  "user":{"id":"<uuid>","username":"operator1","email":"operator1@example.com","role":"USER","enabled":true}
}
```

Avoid placing real passwords/tokens in shell history, screenshots or logs. A shell workflow that prompts on the terminal and retains tokens in memory:

```bash
BASE_URL=http://localhost:8080
```

```bash
AUTH_RESPONSE=$(python3 -c '
import getpass,json,sys,urllib.request
with open("/dev/tty") as tty:
    print("Username/email: ",end="",file=sys.stderr,flush=True)
    name=tty.readline().strip()
password=getpass.getpass("Password: ")
request=urllib.request.Request(sys.argv[1]+"/api/auth/login",
    data=json.dumps({"usernameOrEmail":name,"password":password}).encode(),
    headers={"Content-Type":"application/json"})
with urllib.request.urlopen(request) as r: print(r.read().decode())
' "$BASE_URL")
ACCESS_TOKEN=$(printf '%s' "$AUTH_RESPONSE" | python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])')
REFRESH_TOKEN=$(printf '%s' "$AUTH_RESPONSE" | python3 -c 'import json,sys; print(json.load(sys.stdin)["refreshToken"])')
unset AUTH_RESPONSE
curl --fail -H "Authorization: Bearer $ACCESS_TOKEN" "$BASE_URL/api/auth/me"
curl --fail -H "Authorization: Bearer $ACCESS_TOKEN" "$BASE_URL/api/anomalies"
```

Swagger UI's **Authorize** button accepts the access token without the `Bearer`
prefix. Login responses should never be cached. Business APIs do not accept access
tokens in query parameters or cookies.

## Refresh, logout and admin management

When an access token expires, send the current refresh token:

```http
POST /api/auth/refresh
Content-Type: application/json

{"refreshToken":"<current-refresh-token>"}
```

The response has the same shape as login. Replace **both** stored tokens atomically.
Serialize refresh requests in an Angular interceptor (including coordination across
tabs if tokens are shared). A refresh token is single use. Refresh lifetime is an
absolute session deadline inherited through rotations, not an indefinitely sliding
window. On refresh failure, discard tokens and require login.

Logout accepts the same body at `/api/auth/logout` and returns 204, including for an
unknown well-formed token. Logout invalidates **all sessions** for that account,
including issued access tokens. An expired access token is not required: omit its
Authorization header when calling refresh/logout. A supplied invalid bearer header
still triggers the security filter's 401.

For admin operations, log in with the configured bootstrap credentials (or another
ADMIN) and send that access token. Creation accepts:

```json
{"username":"supervisor","email":"supervisor@example.com","password":"replace-with-your-own-long-password","role":"ADMIN"}
```

Role/status updates invalidate the affected user's access and refresh tokens.
Re-enabled or promoted users must log in again. Admins cannot demote/disable
themselves; the last enabled admin cannot be removed. Admin updates are serialized
for this invariant. Ordinary users cannot invoke any admin operation, including
changing their own role.

## Security behavior and operation

* HS256 signatures are verified with an explicitly selected algorithm. Required
  expiry, issuer, audience and access-token type are validated. Subject and session
  version are checked against the database on each request; current database roles
  provide authorities. This intentional database read enables immediate revocation.
* Refresh tokens contain 256 random bits and no user data. Only their SHA-256
  digests, user/family IDs, absolute expiry and revocation state are stored. Rotation
  locks the user row, re-reads the token and commits revocation atomically. Reuse of
  an unexpired revoked token revokes all sessions for that user, including access
  tokens. Revocation is committed even when returning 401.
* BCrypt uses a fresh salt per password and a configurable cost. The 72-byte limit
  prevents silent truncation. Unknown users still undergo a dummy hash comparison.
  Failed attempts persist across application instances and temporarily lock the
  account. Auth requests additionally have a bounded in-process peer rate limit.
  At a production reverse proxy, apply shared per-client limits and request-body
  size limits; the application intentionally does not trust arbitrary forwarded
  IP headers. Behind a proxy its limit applies to the proxy peer, so tune it to
  complement gateway limits. HTTP 429 includes `Retry-After: 60`.
* Deploy behind HTTPS. No cookie-based credentials, HTTP Basic, form login, or
  server sessions are used. CSRF is disabled because authentication requires an
  explicit Authorization header or a token in the JSON body, not an automatically
  attached browser cookie. If switching to refresh cookies later, add CSRF defenses
  and revisit SameSite/CORS. Cross-origin requests use an explicit origin list and
  `allowCredentials=false`.
* Prefer access/refresh tokens in SPA memory. Persistent browser storage exposes
  tokens to XSS; choose persistence deliberately and deploy CSP/XSS controls.
  Never send credentials/tokens to telemetry, analytics or request-body logs.
  Audit logs contain only event names and internal user UUIDs.
* Registration is intentionally public; email verification, password reset, MFA
  and tenant ownership are outside this change. All USERs can read shared telemetry.
* Retain revoked token hashes until their original expiry so replay can be
  recognized. Periodically remove expired rows with the following maintenance SQL
  (run through your normal database maintenance process):
  `DELETE FROM telemetry.auth_refresh_tokens WHERE expires_at < CURRENT_TIMESTAMP;`

Errors use `{"status":401,"message":"Authentication required"}` for missing or
invalid bearer credentials, `{"status":403,"message":"Access denied"}` for
insufficient roles, and generic credential/token messages for login/refresh.
Invalid auth input is 400; duplicate identity and admin invariant conflicts are
409. Existing business error conventions remain unchanged. CORS rejection is
handled by Spring's CORS processor.

## Database and file changes

Flyway `V3__authentication.sql` adds `telemetry.auth_users` (UUID identity, unique
normalized username/email, password hash, role/status, session version and lockout
state), `auth_refresh_tokens` (hash primary key, user FK and user/family/expiry
indexes), and a singleton `auth_bootstrap_lock`. Existing tables and V1/V2 are
unchanged. H2 and PostgreSQL use the same migration; JPA does not create the schema.

| Files/classes | Responsibility |
| --- | --- |
| `auth/AuthProperties`, `SecurityConfiguration`, `TokenService` | Validated configuration, security policies, JWT signing and validation, CORS and security errors. |
| `auth/AuthRepository`, `AuthService` | JDBC records, registration, BCrypt validation, login lockout, refresh rotation and revocation. |
| `auth/AuthController`, `AuthExceptionHandler` | Validated JSON requests, safe DTOs and consistent auth errors. |
| `auth/AdminUserController`, `AdminUserService` | Authorized user administration and immediate session invalidation. |
| `auth/InitialAdminInitializer`, `AuthRateLimitFilter` | Serialized bootstrap and bounded peer throttling. |
| `agent/ConversationMemory` | Authenticated conversation ownership. |
| `config/OpenApiConfiguration`, `config/McpOpenApiConfiguration` | Bearer scheme, AI routes, public health and explicit MCP protocol/tool examples. |
| `pom.xml` | Boot-managed Resource Server and Spring Security test dependencies. |
| `application.properties`, `.env.example`, `docker-compose.yml` | Environment-driven authentication configuration and container forwarding. |
| `AuthenticationTests`, `AuthRateLimitFilterTests`, `AuthConfigurationTests`, `application-default.properties` | Auth integration/rate-limit tests and test-only credentials. |
| Existing telemetry/schema/OpenAPI/demo/conversation tests | Updated for protected APIs, V3, Swagger security, and conversation isolation. |
| `.github/workflows/ci.yml` | PostgreSQL authentication regression step. |
| `scripts/verify-agent.py`, `scripts/verify-rag-query.py`, `scripts/demo.py` | Bearer support and isolated demo environment. |
| `README.md`, `run.md`, `docs/swagger-api.md`, this guide | Updated setup and authenticated request instructions. |

## Verification

Use Java 21:

```bash
./mvnw --batch-mode --no-transfer-progress -Dtest=AuthenticationTests,AuthRateLimitFilterTests,AuthConfigurationTests,ConversationMemoryTests test
./mvnw --batch-mode --no-transfer-progress verify
```

Tests supply credentials from `src/test/resources/application-default.properties`;
these resources are not packaged in the application JAR. Auth integration tests
run the real security filter chain, Flyway, JDBC, BCrypt and JWT implementation.
They exercise registration, duplicate/invalid input, role injection, login,
lockout, token claims/signatures, refresh expiry/rotation/concurrency/replay,
logout, authorization, admin management, bootstrap, and CORS.

For PostgreSQL, set `AUTH_TEST_DATABASE_URL`, `SPRING_DATASOURCE_USERNAME`,
`SPRING_DATASOURCE_PASSWORD` and `SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver`,
then run `-Dtest=AuthenticationTests test`. Use a **dedicated disposable database**:
auth tests delete auth rows between cases. CI runs this against its PostgreSQL 17
service in addition to the H2 suite.

Live AI verification scripts now require `IIOT_ACCESS_TOKEN` in the environment.
The deterministic `python3 scripts/demo.py` obtains its own test tokens.

Implementation references: [Spring Security JWT resource server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
and [password storage](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html).

Verification (Java 21): the completed ADMIN-only AI/MCP follow-up and configuration
fixes passed the full suite: 122 tests passed, with one optional live pgvector
ingestion test skipped. The application JAR packaged successfully. Tests verify
ADMIN AI access, USER denial before model/tool execution, MCP SDK discovery and
calls using ADMIN JWTs, rejection of USERs with valid MCP session IDs, Swagger
visibility with disabled features, model-property binding and bootstrap behavior.
Compose also validates without a legacy MCP key or bootstrap credentials (the
application still requires bootstrap credentials when no ADMIN exists).

The original authentication implementation additionally passed 28 integration tests
against an isolated PostgreSQL 17 database, including refresh/bootstrap concurrency;
that disposable database was removed after verification.
