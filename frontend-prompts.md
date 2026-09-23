
## Spring Boot IIoT + Angular AI Control Center

> **Architecture Context:**
> - **Backend:** Spring Boot (Java 21, Spring AI, PostgreSQL/pgvector, Ollama).
> - **Frontend:** Angular 17+ (Standalone Components, Signals, RxJS, Tailwind CSS, Lucide Angular, Charting).
> - **Monorepo Layout:** Frontend code resides in `frontend/` alongside Spring Boot root directory.

---

### Phase 1: Angular Setup & Monorepo Integration

#### Prompt 1.1: Project Initialization & Maven Build Integration
```text
I am adding an Angular (v17+) frontend to my existing Spring Boot IIoT repository.
The frontend code will reside in a top-level directory named `frontend/`.

Please generate:
1. The terminal commands to initialize a new Angular project inside `frontend/` using Standalone Components, Routing, and Tailwind CSS.
2. An updated Maven `pom.xml` snippet using `frontend-maven-plugin` so that running `./mvnw package` automatically installs Node/npm, builds the Angular project, and copies the compiled static distribution files into `src/main/resources/static/` for Spring Boot to serve.
3. A Spring Boot `WebMvcConfigurer` class (`WebConfig.java`) to handle single-page application (SPA) client-side routing, forwarding non-API static resource requests to `index.html`.
```

### Prompt 1.2: Design Tokens, Tailwind & Global Layout 

Configure the styling framework for the Angular application in `frontend/`.

Requirements:
1. Configure `tailwind.config.js` with our "IIoT Control Room Dark" theme:
   - Surface colors: `bg-deep` (#0B0F19), `bg-card` (#111827), `border-muted` (#1F2937).
   - Status colors: `status-running` (#10B981), `status-warning` (#F59E0B), `status-critical` (#EF4444), `status-offline` (#6B7280).
   - Custom utility classes or plugins for glassmorphism (`backdrop-blur-md`), pulsing glow animations for warnings, and AI gradient accents (`linear-gradient(135deg, #6366F1 0%, #8B5CF6 50%, #EC4899 100%)`).
2. Generate global SCSS/CSS rules for custom scrollbars and dark background surfaces.
3. Create the main `AppComponent` layout featuring:
   - A sticky header bar with title ("IIoT AI Command Center"), AI Model badges (`qwen2.5:1.5b`, `nomic-embed-text:v1.5`), system health pill (`/actuator/health`), and JWT status button.
   - A 2-column responsive layout container (Left: ~60% Telemetry/Anomalies, Right: ~40% AI Assistant).

### Phase 2: Data Models, Auth & State Management
#### Prompt 2.1: TypeScript Interfaces & Bearer Interceptor

In `frontend/src/app/core/`, create the core data models and HTTP authentication pipeline for our Spring Boot backend:

1. Create TypeScript interfaces/types for:
    - `MachineStatus`, `SensorReading`, `TelemetryQueryParams`
    - `AnomalyAlert`
    - `DocumentSearchResult`, `RagQueryRequest`, `RagQueryResponse`, `Citation`
    - `AgentChatRequest`, `AgentChatResponse`, `ToolExecution`
    - `AuthResponse`, `LoginCredentials`

2. Create an Angular `HttpInterceptorFn` (`jwt.interceptor.ts`) that reads an `ACCESS_TOKEN` signal/localStorage and attaches `Authorization: Bearer <token>` to all outgoing API requests targeting `/api/` and `/mcp`.

3. Create an `AuthService` using Angular Signals to track login state, current user, token storage, and show/hide a Login Modal when HTTP 401 unauthenticated errors occur.

### Phase 3: Telemetry & Anomaly Dashboard (Left Panel)
#### Prompt 3.1: Machine Selector Grid & Real-Time Charts

Build the Telemetry Dashboard components inside `frontend/src/app/features/telemetry/`:

1. `MachineGridComponent`:
    - Fetches and displays machines (`SIM-001` through `SIM-005`).
    - Cards display status, location, latest readings (`temperature_celsius`, `vibration_mm_s`, `energy_kwh`), and an active status badge (using `@if`/`@for`).
    - Selecting a machine updates a shared `selectedMachineId` signal.

2. `TelemetryChartComponent`:
    - Uses `ng2-charts` or `ngx-charts` to display real-time sensor metrics for the selected machine.
    - Includes metric toggle buttons (Vibration vs. Temperature vs. Energy) and time-window selector (e.g., last 10 minutes).
    - Polls `/api/machines/{id}/readings` periodically using RxJS timer/Signals.
    - Styled with dark transparent gridlines, glowing trendlines, and high-contrast tooltip overlays.
#### Prompt 3.2: Anomaly Stream & Alert Feed

Build the Anomaly Alert Monitoring component in `frontend/src/app/features/anomalies/`:

1. `AnomalyFeedComponent`:
    - Polls `GET /api/anomalies` to render a scrollable list of recent anomalous readings.
    - Highlights anomalies with animated amber/red pulsing borders and warning icons (`lucide-angular`).
    - Clicking an anomaly card triggers a smooth-scroll or filter action on the Telemetry Chart to focus on the time timestamp of the anomaly.
    - Includes mock data fallback when backend connection is unavailable during development.

### Phase 4: Spring AI & RAG Assistant Panel (Right Panel)
#### Prompt 4.1: Vector RAG Search & Evidence Viewer

Build the RAG Search interface in `frontend/src/app/features/ai-assistant/rag-search/`:

1. `RagSearchComponent`:
    - Search input box targeting `POST /api/rag/query` or `GET /api/documents/search`.
    - Loading skeleton state with glowing AI purple gradients during embedding/inference execution.
    - Answer card showing the model's response.
    - **Evidence Cards**: Render verified source quotes (`quote`, `documentId`, `section`) with a distinctive left-border accent (`border-left: 3px solid #8B5CF6`).
    - Explicit Amber warning badge shown if `insufficientEvidence: true`.

#### Prompt 4.2: Spring AI Agent Chat & Tool Call Tracing

Build the Agent Chat interface in `frontend/src/app/features/ai-assistant/agent-chat/`:

1. `AgentChatComponent`:
    - Conversational chat interface targeting `POST /api/agent/chat`.
    - Message bubbles distinguishing User vs. AI Agent responses.
    - **Tool Call Execution Inspector**: An expandable accordion widget under AI messages showing when the agent invokes tools (e.g., `fetching telemetry history...`, `executing vector search...`). Render input/output parameters in a styled dark code block (`#030712`).
    - Auto-scroll to bottom on new incoming messages using Angular Signals/Effects.

### Phase 5: Verification & End-to-End Testing
#### Prompt 5.1: Dev Proxy & Production Build Test

Help me configure and verify the complete full-stack environment:

1. Create `frontend/proxy.conf.json` so that running `npm start` in the `frontend/` directory proxies `/api`, `/actuator`, and `/mcp` requests to `http://localhost:8080`.
2. Provide npm script configurations in `package.json` for standalone dev (`npm start`) and Maven production build (`npm run build`).
3. Write a simple sanity-check guide explaining how to log into the UI using the bootstrap admin credentials (`AUTH_INITIAL_ADMIN_*`) and verify live data flow across Telemetry, RAG, and Agent Chat.