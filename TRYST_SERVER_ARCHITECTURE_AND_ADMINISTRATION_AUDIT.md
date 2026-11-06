# TRYST – Server Architecture and Administration Audit

**Document Version:** 1.0.0
**Date:** 2026-11-01
**Baseline Git Commit:** `539768259664f6695bf44f7efce06615bca6fbeb`

---

## 1. Eclipse RCP / Equinox as the Server Hosting Platform

### 1.1 Architectural Concept
A primary architectural tenet of TRYST is that **Eclipse RCP / Equinox / OSGi is the underlying runtime platform hosting the server application**, NOT a desktop IDE that end users must run.
- Equinox provides:
  - Strong modularity and bundle isolation (`Require-Bundle`, `Export-Package`, `Import-Package`).
  - Native OSGi service registry (`BundleContext.registerService`, `ServiceTracker`).
  - Dynamic module lifecycle (start, stop, update, configuration).
  - Clean separation between core contracts, domain logic, infrastructure drivers, and application delivery.
- In this model, the TRYST server:
  - Boots as an Equinox `IApplication`.
  - Runs in headless daemon mode (no Display, no SWT, no Workbench window).
  - Binds an embedded HTTP server (JDK `HttpServer` or Equinox HTTP service).
  - Exposes REST/JSON APIs and serves the TRYST web application frontend.

### 1.2 Evaluation of Current Codebase
- **OSGi Separation:** The bundle boundaries (`core`, `domain`, `infrastructure`, `application`, `ui`) correctly enforce service separation. `core` and `domain` do not depend on SWT or JFace.
- **The Desktop Flaw:** In `plugins/eu.kalafatic.tryst.application/src/eu/kalafatic/tryst/application/TrystApplication.java`, the entry point directly executes:
  ```java
  Display display = PlatformUI.createDisplay();
  int returnCode = PlatformUI.createAndRunWorkbench(display, new ApplicationWorkbenchAdvisor(coordinator));
  ```
  This creates an SWT display and launches an interactive Eclipse Workbench window.
- **Headless Execution Failure:** In standard server environments (headless Linux containers, cloud VMs, systemd services without X11), this call throws an immediate `SWTError: No more handles [gtk_init_check() failed]`.
- **Correction Required:** Provide a headless server `IApplication` (`TrystServerApplication`) registered under extension point `org.eclipse.core.runtime.applications` that starts `ApplicationLifecycleCoordinator`, boots the HTTP server, registers a JVM shutdown hook, and blocks on a shutdown semaphore or countdown latch without loading SWT.

---

## 2. HTTP Server and Web Application Delivery Architecture

### 2.1 Technology Selection
TRYST strictly prohibits bulky external web application frameworks (such as Spring Boot, Quarkus, Node.js, Express, Electron, or Python) to preserve native Java 21 performance, minimal footprint, and zero external runtime dependencies.

Two viable pure-Java approaches exist within the Eclipse/OSGi ecosystem:
1. **Standard Java 21 `com.sun.net.httpserver.HttpServer`:**
   - Bundled directly in standard Java 21 runtime (`java.base` / `jdk.httpserver`).
   - Lightweight, zero third-party JAR dependencies.
   - Built-in multi-threading via `setExecutor(Executors.newVirtualThreadPerTaskExecutor())` or `ManagedAsyncExecutionService`.
   - Full support for REST JSON routing, request filters, authentication handlers, and Server-Sent Events (SSE) streaming.
2. **Equinox HTTP Service (`org.eclipse.equinox.http.jetty` or `org.eclipse.equinox.http.servlet`):**
   - Standard OSGi HTTP service implementation.
   - Requires bringing in Jetty OSGi bundles from Eclipse p2 repository.

**Recommendation:** Utilize standard Java 21 `com.sun.net.httpserver.HttpServer` wrapped in an OSGi service (`HttpServerService`). It requires zero additional p2 bundle downloads, integrates seamlessly with Java 21 Virtual Threads, and guarantees complete autonomy and isolation.

### 2.2 HTTP Routing and API Design
The HTTP server will expose:
- `GET /` & `GET /static/*`: Static file handler serving SPA assets (index.html, CSS, JS).
- `POST /api/auth/login`: Authenticate credentials, return bearer token and user profile.
- `POST /api/auth/logout`: Revoke active session token.
- `GET /api/auth/me`: Validate current session and retrieve roles/permissions.
- `GET /api/projects` & `POST /api/projects`: List and create development projects.
- `GET /api/tasks` & `POST /api/tasks`: List and submit development tasks.
- `POST /api/tasks/{id}/execute`: Dispatch task to autonomous agent worker.
- `POST /api/tasks/{id}/cancel`: Abort in-flight task execution.
- `GET /api/reviews/{id}`: Retrieve generated review snapshot and file diffs.
- `POST /api/reviews/{id}/edit`: Save inline user edits to modified files.
- `POST /api/reviews/{id}/approve`: Explicit human approval trigger, executing Git commit.
- `POST /api/reviews/{id}/reject`: Reject changes, optional feedback loop.
- `GET /api/events`: Server-Sent Events (SSE) stream broadcasting typed application events.
- `GET /api/admin/metrics`: JVM, OS, SQLite, and Ollama performance metrics.
- `GET /api/admin/audit`: Searchable security and tool execution audit records.

---

## 3. Multi-User, Authentication, and Persistence Architecture

### 3.1 Entity Model Distinctions
The server architecture must clearly distinguish between distinct lifecycle entities:
- **`User`:** Identity, username, email, password hash, role (`ADMIN`, `DEVELOPER`, `VIEWER`), status (`ACTIVE`, `DISABLED`), timestamps.
- **`AuthenticationSession`:** Web login token (UUID or secure random token), user ID, creation time, expiration time (TTL), last access time, IP/client info.
- **`Project` & `Workspace`:** Repository path, owner user ID, active branches.
- **`DevelopmentSession`:** Conversation context holding chat history with AI agent.
- **`AgentTask`:** Unit of autonomous work (queued, executing, or completed).
- **`CodeReviewSnapshot`:** Frozen state of generated diffs awaiting user review and approval.

### 3.2 Password Hashing and Security
- Password hashing must use standard PBKDF2 with HMAC-SHA256 (via `javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")`), using a 128-bit random salt and 100,000 iterations.
- Passwords must never be stored in plain text.
- Passwords must never be logged in `TrystLog` or audit records.

### 3.3 Authorization Context & Filter
- An HTTP filter (`AuthenticationFilter`) intercepts all `/api/*` routes (except `/api/auth/login` and public health checks).
- Extracts `Authorization: Bearer <token>` or session cookie.
- Resolves `AuthenticationSession` from database; validates TTL.
- Injects `AuthorizationContext` into the request handler thread.
- Enforces role checks (e.g. `/api/admin/*` requires `Role.ADMIN`).

---

## 4. Server Administration and Observability Architecture

### 4.1 Required Telemetry & Metrics
The administrative subsystem must gather metrics across six distinct dimensions:
1. **JVM Metrics:** Heap memory used/max, non-heap memory, garbage collection counts/time, active threads.
2. **Operating System Metrics:** CPU load average, available processor cores, system memory, disk space on workspace volume.
3. **Database Metrics:** SQLite file size, active connection status, WAL checkpoint status, schema version.
4. **AI & Inference Metrics:** Ollama availability, active models, tokens generated, requests/sec, average inference duration.
5. **Task & Execution Metrics:** Total tasks submitted, active/running tasks, completed, failed, cancelled, average execution duration.
6. **Security & Audit Logs:** Persistent records of logins, failed authentication attempts, file read/write operations, and Git commits.

### 4.2 Durable Audit Logging
Currently, `PermissionManager` records audits to an in-memory `CopyOnWriteArrayList<AuditRecord>`.
- **Correction:** Add `audit_logs` table in SQLite schema migration V2:
  ```sql
  CREATE TABLE IF NOT EXISTS audit_logs (
      id TEXT PRIMARY KEY,
      user_id TEXT,
      action TEXT NOT NULL,
      target TEXT NOT NULL,
      granted INTEGER NOT NULL,
      reason TEXT,
      timestamp TEXT NOT NULL
  );
  CREATE INDEX IF NOT EXISTS idx_audit_logs_user_ts ON audit_logs(user_id, timestamp);
  ```
- All path checks, tool executions, and administrative modifications will be durably persisted.

---

## 5. Deployment and Operational Considerations

### 5.1 Systemd Service & Linux Daemon
TRYST server will be deployable as a persistent background daemon:
```ini
[Unit]
Description=TRYST Autonomous AI Development Server
After=network.target

[Service]
Type=simple
User=tryst
ExecStart=/opt/tryst/tryst -nosplash -application eu.kalafatic.tryst.server.application
Restart=on-failure
RestartSec=5
Environment=TRYST_CONFIG_DIR=/etc/tryst
Environment=TRYST_PORT=38080

[Install]
WantedBy=multi-user.target
```

### 5.2 Graceful Shutdown & Recovery
- Upon receiving `SIGTERM` or `SIGINT`:
  1. HTTP server stops accepting new incoming connections.
  2. In-flight tasks are marked for pause or graceful cancellation.
  3. `ManagedAsyncExecutionService` awaits termination of running tasks up to timeout.
  4. SQLite transactions are committed or rolled back; `SqliteDatabaseManager.close()` checkpoints WAL journal.
  5. OSGi services are unregistered in reverse order.
- Upon startup recovery:
  - Startup reconciler queries `tasks` where `status = 'RUNNING'`.
  - Reconciles orphaned tasks by marking them `FAILED` (with diagnostic message: "Server terminated during execution") or queuing them for resumption if safe.
