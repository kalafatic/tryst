# PHASE 3 — WP-3.10 SECURITY REPORT
## TRYST Connected Applications API Authorization Enforcement

**Date:** 2026-11-16
**Scope:** `eu.kalafatic.tryst.application` + TRYST tests bundle. EVO, TEIP protocol
behavior and all other TRYST subsystems were not modified.
**Verification:** real Maven/Tycho builds and the real HTTP authorization boundary
(embedded `TrystHttpServer` + `java.net.http.HttpClient`) were executed; results below
are actual, not predicted.

---

## 1. Original Vulnerability and Its Impact

`plugins/eu.kalafatic.tryst.application/src/eu/kalafatic/tryst/application/http/ConnectedApplicationsHttpHandler.java`
exposed the connected-application registry management API
(`/api/integration/applications`) with **no authorization enforcement**. Every operation
(list, select, connection test, configuration update, enable/disable) was reachable by
any caller without credentials — including over LAN bindings.

**Impact:**
- An unauthenticated caller could list, select, reconfigure, test or disable/enable the
  coding harnesses (`opencode`, `evo`) that TRYST delegates task implementation to.
- Configuration changes (binary path, agent, model, `autoApprove`) could redirect
  execution to an attacker-controlled binary or enable dangerous auto-approval.
- The behavior contradicted the documented contract
  (`docs/integration/TEIP-OPERATIONS.md` examples use `Authorization: Bearer
  <TRYST_ADMIN_TOKEN>`) and the platform's least-privilege security model.

Evidence of the gap before the fix: the handler was registered without the security
dependencies while the adjacent `EvoIntegrationHandler` and `McpHttpHandler` receive
them (`ApplicationLifecycleCoordinator.java:597-598` vs the old `:602-604`); the handler
contained no `Authorization` parsing and no `PermissionManager` usage.

## 2. Existing Authorization Architecture Discovered

Inspection of the platform (before changes) established the canonical flow:

1. **Authentication** — `eu.kalafatic.tryst.domain.security.AuthenticationService`:
   Bearer session tokens (SHA-256 hashed `UserSession` rows) are validated by
   `authenticateToken(token)` and resolved into `SecurityContext` principals. Logins,
   brute-force lockout, session revocation and admin bootstrap live here.
2. **Authorization** — `PermissionManager` (audit + deny logging) and
   `User.hasPermission(Permission)`; role `ADMIN` holds every permission,
   `MANAGE_AGENTS` (the existing permission for managing coding agents) is held by
   `ADMIN`, `PROJECT_OWNER` and `DEVELOPER`.
3. **HTTP enforcement pattern** — the embedded `TrystHttpServer` resolves the principal
   from the `Authorization: Bearer` header (cookie fallback, explicit emergency
   development-mode `SecurityContext.DEV` fallback) and enforces:
   - `AdminHandler` (`TrystHttpServer.java:2686-2692`): empty/invalid context or
     non-admin → 403 + `ADMIN_ACCESS_DENIED` audit.
   - Permission-gated handlers (e.g. ProjectsHandler `:869-882`): missing auth → 401
     `{"error": "Authentication required"}`; authenticated without the required
     permission → 403 + audit.
4. **Dev mode** — `ApplicationConfiguration.isDevelopmentMode()` (explicit
   `tryst.dev.mode` property/env) provides the platform-wide emergency DEV admin
   bypass; production enforcement requires `tryst.dev.mode=false`.

`EvoHttpHandlers.EvoIntegrationHandler` receives a `PermissionManager` but performs no
check itself; the working references for enforcement were therefore the server's
`AdminHandler`/permission patterns plus `AuthenticationService`. The MCP handler
(`McpHttpHandler`) demonstrates the strict Bearer enforcement pattern for integration
APIs (TEIP 1.1 §8.2).

## 3. Authorization Mechanism Reused

No new authentication mechanism was introduced. WP-3.10 reuses, unchanged:

| Component | Reused as |
|---|---|
| `AuthenticationService.authenticateToken` | the single token validation call (no re-implementation of hashing/session logic) |
| `SecurityContext` / `SecurityContext.DEV` | principal model and dev-mode fallback |
| `User.hasPermission(Permission.MANAGE_AGENTS)` | the permission decision (admin always passes; PROJECT_OWNER/DEVELOPER pass; REVIEWER/USER denied) |
| `PermissionManager.recordAudit` | denied-access audit trail (same call shape as `AdminHandler`) |
| `ApplicationConfiguration.isDevelopmentMode()` | explicit emergency dev-mode bypass, identical to server-wide convention |
| `TrystHttpServer.registerHandler` + OSGi bundle wiring | unchanged registration path |

Status codes follow the server's own conventions: **401** unauthenticated
(`{"error": "Authentication required"}`), **403** authenticated without permission
(`{"error": "Access denied: MANAGE_AGENTS permission required"}`), **204** CORS
preflight (no credentials required, matching every other handler).

## 4. Files Modified

| File | Change |
|---|---|
| `plugins/eu.kalafatic.tryst.application/src/eu/kalafatic/tryst/application/http/ConnectedApplicationsHttpHandler.java` | Constructor now takes `AuthenticationService` + `PermissionManager`; `authorize()` gate (401/403 + audit) applied to every operation after CORS preflight; `resolveSecurityContext()` reusing `authenticateToken` with the explicit dev-mode DEV fallback; fail-closed when the authentication service is absent |
| `plugins/eu.kalafatic.tryst.application/src/eu/kalafatic/tryst/application/lifecycle/ApplicationLifecycleCoordinator.java` | Registration at `:600-607` now passes `this.authenticationService` and `this.permissionManager` into the handler |
| `plugins/eu.kalafatic.tryst.application/META-INF/MANIFEST.MF` | Exported `eu.kalafatic.tryst.application.http` so the tests bundle can exercise the real boundary (required for boundary testing; no other export changes) |
| `tests/eu.kalafatic.tryst.tests/src/eu/kalafatic/tryst/tests/ConnectedApplicationsHttpSecurityTest.java` | NEW: 15 boundary tests (see §6) |
| `docs/integration/TEIP-OPERATIONS.md` | §2.4 documents the required `Authorization: Bearer` headers and the 401/403 semantics (contract documentation only) |

## 5. Authorization Behavior for Every HTTP Operation

All decisions verified by the boundary tests in §6 (production semantics,
`tryst.dev.mode=false`):

| Operation | Missing credentials | Invalid/revoked token | REVIEWER/USER (no MANAGE_AGENTS) | ADMIN / DEVELOPER / PROJECT_OWNER |
|---|---|---|---|---|
| `GET /api/integration/applications` (list) | 401 | 401 | 403 | 200, prior JSON shape unchanged |
| `POST .../select` | 401 | 401 | 403 | 200, selection persisted |
| `POST .../{app}/test` | 401 | 401 | 403 | 200, connection-test result |
| `POST .../{app}/config` | 401 | 401 | 403 | 200, config applied |
| `POST .../{app}/enable` | 401 | 401 | 403 | 200, enable/disable persisted |
| `OPTIONS` (CORS preflight) | 204 | 204 | 204 | 204 (unchanged convention) |

Additional properties:
- **Fail closed:** if `AuthenticationService` cannot be established (not wired), every
  request is rejected with 401 (tested).
- **Explicit dev mode:** with `tryst.dev.mode=true` the platform-wide emergency DEV
  admin context applies and unauthenticated local access is preserved exactly like all
  other TRYST endpoints (tested).
- **Audit:** every denial is recorded through `PermissionManager.recordAudit`
  (`CONNECTED_APPLICATIONS_DENIED`), matching the `AdminHandler` audit convention.
- **Authorized behavior preserved:** response bodies and semantics of list/select/
  test/config/enable for authorized callers are byte-compatible with the pre-fix
  implementation (regression-tested).

## 6. Tests Added and Their Results

New class `ConnectedApplicationsHttpSecurityTest` drives the real HTTP boundary: a real
`TrystHttpServer` on an ephemeral port with the real `AuthenticationService` (SQLite
users/sessions), real login-issued bearer tokens, the real `PermissionManager`, and
`java.net.http.HttpClient` requests. No mocked permission methods.

15 tests, **15/15 passed** (surefire report
`tests/eu.kalafatic.tryst.tests/target/surefire-reports/`, run 2026-11-16 15:33):

- `testGetListWithoutCredentialsRejected` → 401
- `testGetListWithInvalidTokenRejected` → 401
- `testGetListWithRevokedTokenRejected` → 401 (post-logout token)
- `testAuthenticatedReviewerWithoutPermissionRejected` → 403
- `testAuthenticatedPlainUserWithoutPermissionRejected` → 403
- `testAuthorizedAdministratorCanList` → 200
- `testAuthorizedDeveloperCanList` → 200
- `testSelectOperationProtectedAndAuthorized` → 403/401/200 + selection visible
- `testConnectionTestOperationProtectedAndAuthorized` → 403/401/200
- `testConfigurationOperationProtectedAndAuthorized` → 403/200 + config visible
- `testEnableDisableOperationProtectedAndAuthorized` → 403/200/200 + persisted state
- `testAllOperationsDeniedWithoutTokenWhenDevModeDisabled` → all five operations 401
- `testExplicitDevelopmentModeBypassPreservesLocalBehavior` → dev-mode 200
- `testFailsClosedWhenAuthenticationServiceMissing` → 401
- `testCorsPreflightRemainsOpen` → 204

Mandated regression suite (same command selection) also re-run and green:

| Suite | Result |
|---|---|
| `ConnectedApplicationRegistryTest` | 6/6 |
| `OpenCodeCodingHarnessAdapterTest` | 7/7 |
| `TeipClientTest` | 6/6 |
| `TeipProtocolContractTest` | 5/5 |
| `McpServerSecurityTest` | 6/6 |
| **Total** | **45 run, 0 failures, 0 errors, 0 skipped** |

## 7. Build Results

Toolchain: Maven 3.9.12 (wrapper dist) + JDK 21
(`/usr/lib/jvm/java-21-openjdk-amd64`); system Maven 3.8.7/JDK 8 are incompatible with
Tycho 4.0.8 and were not used. `xvfb-run` is not installed on this host; the runs used
the available display (`DISPLAY=:1`) with `useUIHarness=false` — this is the reason the
documented `xvfb-run` prefix was omitted; the test selection itself is identical to the
mandated command plus the new WP-3.10 class.

1. `mvn -o install -pl plugins/eu.kalafatic.tryst.application -am` → **BUILD SUCCESS**
   (application bundle `0.1.0-SNAPSHOT` jar built 15:28, installed with p2 metadata).
2. `mvn -o integration-test -pl tests/eu.kalafatic.tryst.tests -Dtest='ConnectedApplicationRegistryTest,OpenCodeCodingHarnessAdapterTest,TeipClientTest,TeipProtocolContractTest,McpServerSecurityTest,ConnectedApplicationsHttpSecurityTest'`
   → **BUILD SUCCESS**, 45/45 tests, 05:20 min.
3. OSGi wiring: the tycho-surefire run boots a real OSGi runtime; the tests bundle
   compiled against and resolved the newly exported
   `eu.kalafatic.tryst.application.http` package at compile time and runtime —
   bundle wiring is valid. (The full standalone application startup was not re-run;
   the runtime lifecycle is unchanged except for constructor arguments at one
   registration site, which compiles and is exercised by the boundary tests.)

## 8. Remaining Security Concerns

1. **`EvoIntegrationHandler` still performs no authorization** of its own
   (`/api/integration/evo/*`). It was explicitly out of WP-3.10 scope ("Do not modify
   EVO or TEIP protocol behavior") and predates it; it receives `PermissionManager`
   but never enforces. Recommend a dedicated follow-up work package.
2. **`restartHttpService()` does not re-register external handlers**
   (`initializeHttpServerInstance`, coordinator `:1214-1259`): after an HTTP-server
   restart the EVO/MCP/applications handlers are lost until a full application restart.
   Pre-existing, affects all three handlers equally; not changed in WP-3.10.
3. **Emergency development mode is default-on** (`ApplicationConfiguration` returns
   `true` when `tryst.dev.mode` is unset). The connected-applications endpoint is
   therefore open by default in unconfigured deployments — identical to every other
   TRYST endpoint and a Phase 1/2 platform decision, not changed here. Production
   deployments must set `tryst.dev.mode=false`.
4. **Cookie-based sessions are not accepted** by this API surface (Bearer only + dev
   fallback). Stricter than the server's default handlers; chosen to avoid partial
   duplication of the private CSRF-validation infrastructure. No current consumer uses
   cookie auth for this endpoint.
5. The `/enable` handler's boolean parsing (`body.contains("\"enabled\":true")`) is
   legacy manual JSON parsing; unchanged from the pre-fix implementation and now
   protected by the authorization gate.

## 9. Confirmation of Scope

- No new authentication mechanism, filter chain, registry, or permission system was
  introduced; the existing `AuthenticationService`, `PermissionManager`,
  `SecurityContext`, `Permission.MANAGE_AGENTS` and dev-mode conventions were reused
  verbatim.
- No EVO code, TEIP protocol behavior, MCP tool plane, task lifecycle, persistence
  schema, or frontend code was modified.
- Authorized behavior is byte-compatible with the pre-fix implementation (verified by
  the regression tests above).
- Only the five files listed in §4 were changed; WP-3.10 is complete.

**Status: WP-3.10 COMPLETE — awaiting Product Owner review and approval. No further
Phase 3 work was started.**
