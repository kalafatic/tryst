# TRYST Headless Mode

## 1. Description
Headless mode allows running the full TRYST autonomous development server as a background service or in containerized/cloud environments without requiring an X server, graphical desktop, or SWT Display handle.

## 2. Launch Commands

### Linux / Containers
```bash
# Explicit headless flag (recommended)
./tryst -headless

# Alternatively via Equinox application extension
./tryst -nosplash -application eu.kalafatic.tryst.application.server

# Background daemon execution
nohup ./tryst -headless > tryst-server.log 2>&1 &
```

### Windows
```cmd
tryst.exe -headless
```

## 3. Supported Components in Headless Mode
The headless runtime operates identical services to the desktop RCP edition:
- Equinox OSGi modular runtime
- Authoritative SQLite database (`~/.tryst/tryst.db`) and automated schema migrations (V1-V4)
- PBKDF2 authentication, session verification, and RBAC security context
- Pure Java HTTP and TCP communication servers
- AI model and provider discovery (`OllamaClient`, `AiModelRegistryService`)
- Task orchestrator, autonomous development engine, and code review center
- Process execution management and resource telemetry collection
- `ServerReadinessService` calculating component-level health

## 4. Diagnostics Output
On startup, headless mode logs system diagnostics to stdout/stderr:
- Java runtime version and vendor
- Configuration and workspace roots
- Database location and integrity check status
- HTTP and TCP bind ports
- Canonical external URL (`http://localhost/tryst`)
- Component-by-component readiness evaluation (Database, Auth, HttpServer, TcpServer, AiProvider)
