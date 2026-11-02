# TRYST Server Initialization Architecture

## 1. Overview & Architectural Principles
TRYST executes as an Eclipse RCP product running Equinox OSGi, serving as its own host and embedding both HTTP and TCP communication services directly inside the OSGi runtime. The initialization sequence is coordinated deterministically by `ApplicationLifecycleCoordinator` through explicit lifecycle stages.

## 2. Deterministic Startup Sequence
1. **Equinox Initialization**: OSGi framework bootstrap, bundle activation with `Bundle-ActivationPolicy: lazy`.
2. **Platform Initialization (`PLATFORM_INITIALIZATION`)**: Instantiates in-memory typed event bus (`EventBus`).
3. **Configuration Loading (`CONFIGURATION_INITIALIZATION`)**: Loads properties from `~/.tryst/tryst.properties` via `ConfigurationService`, initializing `RuntimeEndpointRegistry`.
4. **Core Service & Persistence Initialization (`CORE_SERVICE_INITIALIZATION`)**:
   - Opens authoritative SQLite database (`~/.tryst/tryst.db`) via `SqliteDatabaseManager`.
   - Runs `PRAGMA integrity_check;` to verify database health.
   - Executes `SchemaMigrationService.migrate()` sequentially across migrations V1, V2, V3, and V4.
   - Reconciles interrupted tasks (`RUNNING`, `CLAIMED`, `COMMITTING` -> `RECOVERY_REQUIRED`).
   - Provisions administrator securely or issues a one-time bootstrap setup token.
   - Starts `TrystTcpServer` and `TrystHttpServer`.
   - Binds dynamic ports and publishes runtime endpoint to `RuntimeEndpointRegistry`.
   - Registers all authoritative services into the native OSGi Service Registry.
5. **Application Ready (`APPLICATION_READY`)**: All mandatory services healthy and operational.
6. **Workbench Loop (`WORKBENCH_RUNNING`)**: Interactive RCP UI active.
7. **Graceful Shutdown & Disposal (`APPLICATION_SHUTDOWN` -> `SERVICE_DISPOSAL` -> `APPLICATION_TERMINATED`)**: Unregisters OSGi services, terminates communication servers, closes SQLite connections.
