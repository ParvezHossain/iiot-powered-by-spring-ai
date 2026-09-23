# Angular frontend

Phases 1.1–4.2 add the standalone Angular workspace, client-side routing, Tailwind
CSS, the production Maven build, and the dark control-room layout. Prompt 2.1 adds
backend-aligned models and sign-in state. Prompt 3.1 adds the machine selector
and live telemetry charts. Prompt 3.2 adds the anomaly feed and chart focus. Phase 4 adds RAG answers, citations, conversational agent chat, and tool inspection.

## Build and run

From the repository root, with Java 21 and the backend environment configured as
explained in [the root README](../README.md):

```bash
./mvnw package
java -jar target/iiot-powered-by-ai-0.0.1-SNAPSHOT.jar
```

Open `http://localhost:8080/`. Maven installs its own Node **24.19.0** and npm
**11.17.0** in `target/frontend`, runs `npm ci` against the committed lockfile,
then builds Angular in production mode. No global Node/Angular installation is
required for Maven or Docker builds. The first build requires network access to
Maven Central, nodejs.org, and the npm registry.

Angular writes to `frontend/dist/iiot-control-center/`. Maven clears stale assets,
copies that distribution to `src/main/resources/static/`, and packages it under
`BOOT-INF/classes/static/` in the executable JAR. Both directories are generated
and ignored by Git. Do not put hand-written files in `src/main/resources/static/`;
put frontend public assets in `frontend/public/`. Maven `clean` also removes the
generated distribution and static resources.

```bash
# Existing Compose setup now builds and serves the UI too.
docker compose up --build -d --wait --wait-timeout 900

# Backend-only tests: skip Node installation and the frontend build.
./mvnw -Dskip.frontend=true test

# Routing/security regression tests only.
./mvnw -Dskip.frontend=true -Dtest=WebConfigTests,AuthenticationTests test
```

The frontend profile is active unless the `skip.frontend` property is present.
Use the skip option only for backend work: after a clean, it produces no UI;
without a clean, previously generated UI assets may remain.

For frontend development, install Node 24.19.0/npm 11.17.0 locally:

```bash
cd frontend
npm ci
npm start
# http://localhost:4200
npm run build
```

The development server proxies `/actuator/health`, `/api/**`, and `/mcp` routes to `http://127.0.0.1:8080`
through `proxy.conf.json`. Start the backend for live health status; without it,
the UI reports health unavailable. The machine selector and charts use the telemetry API; the anomaly feed supports chart focus; the right panel provides RAG search and agent chat. Production uses the same Spring Boot origin.

### Login returns 404 during development

Start Spring Boot on port 8080, then stop any existing Angular dev server and
restart it from this repository:

```bash
cd frontend
npm start
```

`npm start` explicitly loads `proxy.conf.json`. Restart `ng serve` whenever that
file changes; an already running server can retain its old proxy routes. IDE run
configurations should use this npm script (or `ng serve --proxy-config proxy.conf.json`
with `frontend/` as the working directory).

The browser should request `http://localhost:4200/api/auth/login`. Angular forwards
that request to `http://127.0.0.1:8080/api/auth/login`, preserving the path. This
keeps bearer requests same-origin and avoids requiring cross-origin configuration.
Do not hard-code port 8080 in `AuthService`: the production UI uses its own Spring
Boot origin, and the interceptor intentionally restricts credentials to that origin.

A credential-free proxy check is:

```bash
curl -i -H 'Content-Type: application/json' -d '{}' http://localhost:4200/api/auth/login
```

The expected response is **400** (backend request validation), not 404. If the
backend is stopped, the proxy reports a connection failure instead.

### Machine discovery returns 404 after a frontend update

If login works but `/api/machines` returns 404, check the backend's Swagger page
on port 8080 for `GET /api/machines`. A working proxy cannot add an endpoint to
an older running backend. Rebuild and recreate the app container using the same
Compose files as your running stack. For the AI-enabled stack:

```bash
docker compose -f docker-compose.yml -f docker-compose.ai.yml up --build -d --no-deps --wait --wait-timeout 900 app
```

For base mode, omit `-f docker-compose.ai.yml`. This updates only the app; database
and Ollama containers remain running. For a host-run backend, rebuild with Maven
and restart the Java process instead. A plain Docker restart reuses the old image
and does not pick up new Java endpoints.

## Initialization commands (reference)

These commands describe how this existing workspace was created; do not run
`ng new` over it. Angular 22 meets the requested Angular 17+ baseline.

```bash
npm exec --yes --package=@angular/cli@22.1.8 -- \
  ng new iiot-control-center --directory=frontend --standalone --routing \
  --style=css --ssr=false --skip-git --skip-install --skip-tests \
  --package-manager=npm --defaults --minimal --file-name-style-guide=2016
cd frontend
npm install --save-dev tailwindcss@4.3.3 @tailwindcss/postcss@4.3.3 postcss@^8.5.6
```

Tailwind 4 uses `.postcssrc.json` and `@import "tailwindcss"` in `src/styles.css`.
The requested `tailwind.config.js` theme is explicitly loaded with `@config`;
Tailwind 4 does not discover JavaScript configuration files automatically. Versions are resolved
in `package-lock.json`. See the official [Angular Tailwind guide](https://angular.dev/guide/tailwind)
and [Node compatibility table](https://angular.dev/reference/versions).

## SPA routing and authorization

`WebConfig` serves existing static files first, then internally resolves
`index.html` for GET/HEAD browser navigation (`Accept: text/html`) to extensionless
client routes. Angular renders its not-found view for unrecognized client paths.
Missing asset files retain HTTP 404. `/api`, `/mcp`, `/actuator`, `/v3`,
`/swagger-ui`, `/error`, and `/webjars` are reserved for the backend and never use
SPA fallback. Newly introduced backend namespaces must also be reserved there.

The shell, root JavaScript/CSS bundles, `/assets/`, and `/media/` are public.
Existing API authentication and ADMIN restrictions remain enforced by Spring
Security. Loading an HTML shell grants no API access. Requests to arbitrary paths
without an HTML Accept header still require authentication by default.

`WebConfigTests` verifies deep links, static files, missing assets, and protected
backend namespaces against the actual security filter chain using test fixtures.
The normal Maven lifecycle also compiles and production-builds the Angular app.

## Control-room theme and layout (Prompt 1.2)

`tailwind.config.js` defines `bg-deep` (#0B0F19), `bg-card` (#111827),
`border-muted` (#1F2937), and the `status-running`, `status-warning`,
`status-critical`, and `status-offline` color families. Use them with Tailwind
color utilities such as `text-status-warning` or `bg-status-running`.
`bg-ai-gradient` provides the requested indigo/violet/pink gradient;
`ai-text` applies it to text. `glass-panel` combines translucent surfaces,
borders, and `backdrop-blur-md`. `motion-safe:animate-warning-glow` provides
the amber pulse. Global styles include scrollbars, keyboard focus indicators,
dark native controls, and reduced-motion overrides.

`App` owns the sticky header, default model badges, health status, JWT status
popover, and page container. `HomeComponent` provides the 60/40 telemetry/AI
workspace at widths of 1024px and above, stacking vertically below that width.
The telemetry panel now displays live machine cards and charts. The anomaly panel displays recent alerts; the right panel provides equipment knowledge search and conversational agent chat.
The JWT status button now opens the Prompt 2.1 sign-in dialog. A signed-in
account displays its server-supplied username and role, with a sign-out action.

`SystemHealthService` polls the public `/actuator/health` endpoint immediately
and every 30 seconds, with a five-second timeout and automatic subscription
cleanup. `UP` is operational; `DOWN`/`OUT_OF_SERVICE` (including HTTP 503 health
responses) is degraded. Network errors, malformed responses, and unknown status
are unavailable. Polling continues after failures. Backend health does not imply
that Ollama or AI functionality is enabled.

Run `npm run build` to check TypeScript, Angular templates, and Tailwind output.
For a manual check, open the UI at desktop and mobile widths, open/close the JWT
login dialog with keyboard controls, and stop/restart the backend to observe health
recovery. Enable reduced motion in your OS/browser to suppress warning pulses.

## Models and authentication (Prompt 2.1)

`src/app/core/models/index.ts` mirrors the backend JSON records. In particular,
login accepts `usernameOrEmail`; readings use `metricType`/`timestamp`; anomalies
wrap a `reading` and a nullable `baseline`. Agent tool executions are exposed as
`evidence`, and citation metadata can be arbitrary JSON. Dates remain ISO-8601
strings, UUIDs remain strings, and reading IDs follow the backend numeric format.

`AuthService` exposes readonly Angular signals for `accessToken`, `currentUser`,
`isAuthenticated`, `isAdmin`, `busy`, and `loginVisible`. The login dialog submits
to `/api/auth/login` and uses the returned user, rather than trusting decoded JWT
claims. Credentials and tokens are never displayed or logged. The native modal
supports focus management, Escape/cancel, browser autofill, and inline errors.

Tokens are stored **only in memory**, using the signal option requested in the
prompt. Neither localStorage nor sessionStorage is used. Reloading or opening a
new tab requires sign-in. The refresh token is retained privately for logout
revocation and explicit renewal from the Account page. Automatic refresh and
persistent sessions are not implemented. Use **Renew session** before expiration; access expiration clears the session and opens login. Browser storage
would extend token exposure beyond the page lifetime, so it is intentionally not
used. This does not make tokens immune to malicious scripts running in the page.

`jwt.interceptor.ts` adds bearer credentials only to same-origin `/api/` and
`/mcp` routes, including absolute same-origin URLs. It excludes public auth
operations, health, assets, and other origins. A protected 401 clears the affected
session and opens login; 403 propagates without signing out. Old in-flight 401s
cannot invalidate a newer session. Requests, particularly mutations and AI tool
calls, are never automatically replayed. Auth calls bypass the interceptor to
avoid login-error loops. Backend authorization remains authoritative; `isAdmin`
is only a presentation aid.

Sign-out clears local credentials immediately and calls `/api/auth/logout` with
the refresh token. The backend currently revokes **all sessions for that user**.
A network failure is reported without restoring local credentials. AI panels require an administrator session.

```bash
cd frontend
npm ci
npm test         # Vitest + Angular HttpTestingController
npm run build
```

The tests cover successful/failed sign-in, role state, URL credential boundaries,
401 versus 403, concurrent and stale responses, expiration, and logout failure.
The default Maven `test`/`verify` lifecycle also runs these tests; `-DskipTests`
skips test execution, and `-Dskip.frontend=true` skips the entire frontend profile.
Angular's [functional interceptor guidance](https://angular.dev/guide/http/interceptors)
underlies this pipeline.

## Machine selector and real-time charts (Prompt 3.1)

Rebuild and **restart Spring Boot** for the new `GET /api/machines` endpoint,
then restart the frontend development server with `npm start`. Both USER and
ADMIN accounts can view telemetry; AI APIs retain their ADMIN restrictions.

`features/telemetry/MachineGridComponent` discovers actual database UUIDs and
shows machine name, nullable location, stored status, and the newest sample for
each of temperature, vibration, and energy. The simulator creates SIM-001 through
SIM-005 by default; other configured machines appear too. Missing readings display
an em dash, not zero. RUNNING is green, IDLE/MAINTENANCE amber, FAULTED red, and
STOPPED/OFFLINE muted. Stored machine status is not inferred from sample age;
readings expose their sample time, and the cards show when they were last fetched.

The dashboard-scoped `TelemetryStore` exposes `selectedMachineId` to both components.
It chooses the first discovered machine initially and preserves a valid selection
across polls. Machine discovery and history poll every five seconds while signed
in. In-flight requests are cancelled when selection, metric, time window, or
credentials change; polling is disposed when the dashboard is destroyed. Failed
requests display an error and recover on subsequent polls. No synthetic telemetry
is substituted for a server error.

`TelemetryChartComponent` uses ng2-charts 10 / Chart.js 4 with a linear timestamp
axis, so uneven sample spacing is represented accurately without a date adapter.
Vibration, temperature, and energy toggles use backend metric names and units;
5/10/30-minute windows are available. History requests keep fixed ISO time bounds
across pages and fetch all 1,000-row pages, up to a 10,000-sample safety limit. If
that limit is reached, the UI asks for a shorter window rather than silently
showing only old data. Dataset animations are disabled; lines use a subtle glow,
transparent grids and high-contrast tooltips. Long gaps (over 15 seconds) are not
joined by a trendline. A recent-values table provides accessible text readings.

Backend changes are limited to machine discovery in the existing telemetry
controller/service, using one query to return machines and their latest readings.
Machines with no readings are included; results are ordered by name then UUID.
There are no database migrations or security-policy changes.

Verification:

```bash
npm --prefix frontend test
./mvnw -Dskip.frontend=true -Dtest=TelemetryApiTests test
./mvnw verify
```

Frontend tests cover authentication-aware polling, empty/error states, selection
cancellation, stable pagination, recovery, and non-overlapping requests. Backend
tests cover discovery, latest-reading ties, empty machines, and USER/anonymous
access. See [ng2-charts](https://valor-software.com/ng2-charts) for chart integration.

## Anomaly stream and chart focus (Prompt 3.2)

The anomaly feed requests the latest 100 alerts from the past hour every five
seconds while signed in. Requests do not overlap, failed polls retry, and session
changes cancel pending requests and clear protected data. Alerts are sorted newest
first and deduplicated by reading ID. Warning/critical styling is a UI priority:
sensor dropout or an absolute z-score at least twice the detection threshold is
critical. It does not replace the backend machine status.

Select an alert to focus the chart on its machine and metric, centered on the
alert timestamp. Raw Modbus dropout readings are supported as sensor-register
values. The selected time window stays centered until you choose **Return to live
telemetry**, another machine, or another metric. Navigation respects reduced motion.

Development builds show explicitly labeled synthetic fixtures when disconnected;
production builds never substitute demo data. Authentication/authorization errors
do not trigger the fallback. Clicking a demo alert shows only its local sample and
never requests telemetry for an invented machine ID.

Verify with `npm test` and `npm run build`. For a manual check, sign in with the
bootstrap admin configured by `AUTH_INITIAL_ADMIN_*`, run the simulator, select an
alert, and confirm its timestamp and metric in the chart. Return to live telemetry,
then stop/restart the backend to check error recovery. A development build shows
labeled demo alerts while offline. Use the right-hand AI panels to investigate alerts and equipment guidance.

## RAG search and agent chat (Phase 4)

The right panel provides two independent forms for ADMIN accounts. Sign in using
an admin account (including the configured `AUTH_INITIAL_ADMIN_*` bootstrap user).
Start the AI-enabled stack with `RAG_ENABLED=true` and `AGENT_ENABLED=true` and make
sure the configured chat and embedding models are available. Disabled AI services
show an unavailable message; the UI never invents answers or tool results.

**Equipment knowledge** posts a question to `/api/rag/query`. While waiting it
shows a purple loading skeleton, then the answer and server-verified quotes with
document IDs and sections. `insufficientEvidence` displays an amber warning,
including when no citations are returned. Source text and model responses are
rendered as text, without interpreting HTML.

**Agent chat** posts to `/api/agent/chat`, preserving the returned conversation ID
for follow-ups. User and agent bubbles are distinct, and new messages scroll the
transcript to the bottom. Expand **Tool executions**, then an individual tool to
inspect its input parameters, output, success/failure, and citation label. The
backend now includes serialized `input` parameters alongside each evidence result.
Rebuild the backend to see these inputs; older servers display “Not provided”.
The endpoint is not streaming: execution details appear with the completed answer.

Questions must contain 1–2,000 characters. Each form allows one pending request,
with a three-minute timeout and no automatic retries. An expired conversation
returns an error; select **New conversation** to start again. Session changes and
leaving the dashboard cancel browser requests and clear session-specific state.
Cancellation does not guarantee that already-running server inference stops.

Manual check:

1. Sign in as ADMIN and ask an equipment question. Check the answer, quote,
   document, and section against a loaded manual; try an unsupported question to
   exercise insufficient evidence.
2. Ask the agent about a simulated machine, then ask a follow-up. Expand the trace
   and inspect the actual tool inputs and outputs. Start a new conversation.
3. Sign out while a request is pending and confirm the panels clear. Sign in as a
   USER and confirm both forms remain disabled.
4. Disable the AI services to verify the unavailable message and explicit retry.

`npm test` covers requests, conversation reuse/reset, session cancellation,
authorization gating, timeouts, rendering of evidence and tool traces, safe text
rendering, and transcript scrolling. `npm run build` verifies production templates.

## Additional API workflows

The header keeps Dashboard and Machine status in the primary navigation.
The Administration dropdown groups Users, Documents, and MCP tools for ADMINs.
The account menu at the top right contains Account and Sign out, with username
and role shown together. Active routes are highlighted; menus close on navigation,
outside interaction, or Escape. Model information appears in the footer. See [the complete API coverage and verification guide](../docs/frontend-api-coverage.md).
Registration never accepts role fields. Renew session rotates both in-memory tokens
without retrying a single-use refresh token; failure/cancellation requires sign-in.
Views tied to the old token are cleared on renewal. Admin user mutations revoke the
affected account's sessions. Document ingestion requires explicit corpus replacement
selection. MCP uses authenticated fetch for streaming, initializes session headers,
and supports discovery, calls, GET events and DELETE closure. No requests are
silently replayed. Leaving a page cancels browser work; the server may still finish
an operation that already started.
