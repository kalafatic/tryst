# TRYST Eclipse RCP Administration Architecture

## 1. Native RCP Workbench Foundation
TRYST uses Eclipse 4 / Equinox RCP as the administrative console. The UI bundle `eu.kalafatic.tryst.ui` interacts with backend services via dynamic OSGi service tracking (`TrystUiServices`) to avoid bundle activation order pitfalls.

## 2. Views and Perspectives
- `TrystAdministrationPerspective`: 4-quadrant layout for metrics, database, processes, telemetry, and sessions.
- `EmbeddedBrowserView`: Embeds SWT `Browser`, listens to `RuntimeEndpointRegistry`, and navigates to the active runtime server endpoint.
- `SessionDetailView`: Displays deep operational context for selected domain sessions.
- `ServerOverviewView`, `UsersAndSessionsView`, `DatabaseAdminView`, `TelemetryView`, `AuditAndLogsView`, `ManagedProcessesView`.
