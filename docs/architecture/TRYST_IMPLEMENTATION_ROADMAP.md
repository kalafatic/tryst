# TRYST Architecture Implementation Roadmap

**Document Identifier:** `TRYST-ARCH-ROADMAP-001`
**Status:** APPROVED BASELINE
**Classification:** Implementation Roadmap

---

## 1. Prioritized Defect and Architectural Debt Inventory

The audit of the existing codebase identified the following architectural improvements, prioritized for subsequent feature implementations:

```
[CRITICAL / HIGH]
  P1. Structured JSON Tool Invocation Schema
      Issue: Agent loop uses prefix string format "TOOL:<name> PARAM:<key>=<value>".
      Impact: Susceptible to hallucinations or parsing drifts on smaller Ollama models.
      Target: Structured JSON payload and formal tool schema validation.

  P2. Decouple Web Client Static Assets
      Issue: HTML/CSS/JS is stored as inline multiline String constants in TrystHttpServer.java.
      Impact: Hampers independent frontend asset editing, caching, and frontend linting.
      Target: Extract assets into /resources/web/ and load via OSGi Bundle classloader.

[MEDIUM]
  P3. Workspace Scoping Refactor
      Issue: WorkspaceService maintains in-memory currentWorkspace pointer.
      Impact: Potential state collision if concurrent multi-user tasks target different projects.
      Target: Deprecate global currentWorkspace; pass explicit Project/Workspace context.

  P4. Host Tooling Readiness Checks
      Issue: Git and Maven rely on host PATH binaries via ProcessBuilder.
      Impact: Unclear failure message if Git or Maven is missing or incompatible.
      Target: Add explicit executable health checks in ServerReadinessService.
```

---

## 2. Incremental Implementation Phases

### Phase 1: Tooling and Protocol Robustness
- **Goal:** Replace string parsing with formal JSON tool schemas.
- **Components:** `AiContextManager`, `SessionTaskOrchestrator`, `ToolRegistry`.
- **Validation:** Automated JUnit tests with mock LLM outputs simulating tool calls.

### Phase 2: Web Client Asset Decoupling
- **Goal:** Move HTML, CSS, and vanilla JavaScript into clean modular bundle files.
- **Components:** `plugins/eu.kalafatic.tryst.infrastructure/resources/web/`, `StaticAssetHandler`.
- **Validation:** HTTP E2E tests validating MIME types, gzip/content encoding, and status codes.

### Phase 3: Multi-Workspace Concurrency Hardening
- **Goal:** Eliminate singleton workspace assumptions in domain services.
- **Components:** `WorkspaceService`, `SessionTaskOrchestrator`, `AutonomousDevelopmentService`.
- **Validation:** Parallel multi-tenant execution tests ensuring concurrent tasks do not collide.

### Phase 4: Pluggable AI Provider SPI
- **Goal:** Formalize AI provider SPI allowing additional local or remote inference backends while keeping Ollama default.
- **Components:** `AiInferenceService`, `AiModelRegistryService`.
- **Validation:** Multi-provider registration and role-filtered model discovery tests.

### Phase 5: Optional EVO Adapter Bundle
- **Goal:** Provide seamless EVO integration without coupling TRYST Core to EVO.
- **Components:** Separate OSGi bundle `eu.kalafatic.tryst.evo.adapter`.
- **Validation:** Standalone verification confirms TRYST runs identically with or without the adapter.
