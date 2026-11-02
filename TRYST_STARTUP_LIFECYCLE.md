# TRYST Startup Lifecycle

## 1. Lifecycle Stages
The application advances deterministically through explicit lifecycle stages managed by `ApplicationLifecycleCoordinator`:

1. `APPLICATION_START`: Initial trigger from launcher.
2. `PLATFORM_INITIALIZATION`: Instantiates in-memory `EventBus` and `ServerReadinessService`.
3. `CONFIGURATION_INITIALIZATION`: Loads or establishes configuration in `~/.tryst`.
4. `CORE_SERVICE_INITIALIZATION`:
   - Connects to SQLite database (`tryst.db`) and verifies `PRAGMA integrity_check`.
   - Executes pending schema migrations (V1 through V4).
   - Reconciles interrupted tasks (marking uncompleted tasks `RECOVERY_REQUIRED`).
   - Provisions initial administrator account or generates one-time setup token.
   - Starts TCP server and HTTP REST/Web server with dynamic port fallback.
   - Registers all authoritative domain and infrastructure services into the native OSGi Service Registry.
5. `APPLICATION_READY`: All core services active and ready to accept requests.
6. `WORKBENCH_RUNNING`: (Standard RCP mode only) Workbench window visible and views restored.
7. `APPLICATION_SHUTDOWN`: Triggered by Workbench close, JVM shutdown hook, or SIGTERM signal.
8. `SERVICE_DISPOSAL`: Unregisters OSGi services, terminates communication servers, flushes thread pools, and closes SQLite database connection.
9. `APPLICATION_TERMINATED`: Clean exit.

## 2. Server Readiness Evaluation
`ServerReadinessService` tracks mandatory components (`Database`, `Authentication`, `HttpServer`) and optional components (`TcpServer`, `AiProvider`). The overall system is considered `READY` only when all mandatory components report `HEALTHY` and the lifecycle stage is `APPLICATION_READY` or `WORKBENCH_RUNNING`.
