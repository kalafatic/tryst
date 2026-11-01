# TRYST Server Monitoring and Telemetry

## 1. Overview
TRYST monitors host and JVM performance continuously without introducing external agents or heavyweight monitoring stacks. Metrics are gathered via standard Java 21 Management Beans and stored downsampled in SQLite for historical analysis.

## 2. Server Resource Collection (`ServerMonitoringService`)
- **CPU Utilization**: Process and System CPU load metrics collected via `com.sun.management.OperatingSystemMXBean`. Available processor count.
- **Memory Consumption**: JVM Heap Used, Committed, and Max; Non-Heap Used; Total and Free Physical Host Memory via `MemoryMXBean`.
- **Storage Metrics**: Total workspace capacity, usable space, and used bytes via `java.nio.file.FileStore`. Database size.
- **JVM Health**: Active thread count, daemon thread count, JVM uptime, cumulative Garbage Collection counts and GC time.

## 3. Historical Telemetry Service (`TelemetryService`)
- Collects periodic snapshots (default 30-second intervals) into the `telemetry_snapshots` table.
- Stores CPU, memory, storage, session counts, task queue depth, and running task counts.
- Provides queries for recent telemetry trends (`findRecent`, `findSince`).
- Supports bounded retention via scheduled purging (`deleteOlderThan`).
- Background thread lifecycle is tightly bound to `ApplicationLifecycleCoordinator` and shuts down gracefully without leaking threads.
