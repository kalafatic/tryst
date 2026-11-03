# TRYST Module Dependencies and Bundle Hierarchy

**Document Identifier:** `TRYST-ARCH-MODULE-001`
**Status:** APPROVED BASELINE
**Classification:** Architecture Specification

---

## 1. Modular Hierarchy and Dependency Graph

The TRYST platform consists of strictly decoupled OSGi bundles structured under Maven and Tycho. Cyclic dependencies between bundles are prohibited.

```
       +---------------------------------------------+
       |             eu.kalafatic.tryst.ui           |
       |  (SWT / JFace Views, Controllers, Locators) |
       +---------------------------------------------+
                 |              |               |
                 v              |               v
       +--------------------+   |   +------------------------------------+
       |  ...application    |   |   |   eu.kalafatic.tryst.infrastructure|
       | (Workbench/Server) |   |   | (HTTP/TCP, SQLite, Git, Ollama)    |
       +--------------------+   |   +------------------------------------+
                 |              |               |
                 +--------+     |     +---------+
                          |     v     |
       +---------------------------------------------+
       |             eu.kalafatic.tryst.domain       |
       |  (Entities, Repositories, Security, Tooling)|
       +---------------------------------------------+
                                |
                                v
       +---------------------------------------------+
       |              eu.kalafatic.tryst.core        |
       | (Event Bus, Async Executor, Config, Logging)|
       +---------------------------------------------+
                                |
                                v
       +---------------------------------------------+
       |           Eclipse Equinox / Java 21         |
       +---------------------------------------------+
```

---

## 2. Bundle Manifest and Export Specifications

### 2.1 `eu.kalafatic.tryst.core`
- **Bundle-SymbolicName:** `eu.kalafatic.tryst.core`
- **Required Bundles:** `org.eclipse.osgi`, `org.eclipse.equinox.common`, `org.eclipse.core.runtime`.
- **Exported Packages:**
  - `eu.kalafatic.tryst.core`
  - `eu.kalafatic.tryst.core.concurrent`
  - `eu.kalafatic.tryst.core.config`
  - `eu.kalafatic.tryst.core.error`
  - `eu.kalafatic.tryst.core.event`
  - `eu.kalafatic.tryst.core.lifecycle`
  - `eu.kalafatic.tryst.core.log`
- **Imported Packages:** None.

### 2.2 `eu.kalafatic.tryst.domain`
- **Bundle-SymbolicName:** `eu.kalafatic.tryst.domain`
- **Required Bundles:** `eu.kalafatic.tryst.core;bundle-version="0.1.0"`
- **Exported Packages:**
  - `eu.kalafatic.tryst.domain.ai`
  - `eu.kalafatic.tryst.domain.model`
  - `eu.kalafatic.tryst.domain.repository`
  - `eu.kalafatic.tryst.domain.review`
  - `eu.kalafatic.tryst.domain.security`
  - `eu.kalafatic.tryst.domain.service`
  - `eu.kalafatic.tryst.domain.tool`
- **Forbidden Packages:** Must NEVER import SWT, JFace, or UI workbench packages.

### 2.3 `eu.kalafatic.tryst.infrastructure`
- **Bundle-SymbolicName:** `eu.kalafatic.tryst.infrastructure`
- **Bundle-ClassPath:** `.`, `lib/sqlite-jdbc.jar`
- **Required Bundles:**
  - `org.eclipse.osgi`
  - `org.eclipse.equinox.common`
  - `org.eclipse.core.runtime`
  - `slf4j.api;bundle-version="2.0.12"`
  - `eu.kalafatic.tryst.core;bundle-version="0.1.0"`
  - `eu.kalafatic.tryst.domain;bundle-version="0.1.0"`
- **Exported Packages:**
  - `eu.kalafatic.tryst.infrastructure.ai`
  - `eu.kalafatic.tryst.infrastructure.build`
  - `eu.kalafatic.tryst.infrastructure.persistence`
  - `eu.kalafatic.tryst.infrastructure.persistence.repository`
  - `eu.kalafatic.tryst.infrastructure.server`
  - `eu.kalafatic.tryst.infrastructure.vcs`
  - `org.sqlite`

### 2.4 `eu.kalafatic.tryst.application`
- **Bundle-SymbolicName:** `eu.kalafatic.tryst.application;singleton:=true`
- **Activator:** `eu.kalafatic.tryst.application.TrystApplicationPlugin`
- **Required Bundles:**
  - `org.eclipse.core.runtime`
  - `org.eclipse.ui`
  - `org.eclipse.equinox.app`
  - `org.eclipse.osgi`
  - `eu.kalafatic.tryst.core;bundle-version="0.1.0"`
  - `eu.kalafatic.tryst.domain;bundle-version="0.1.0"`
  - `eu.kalafatic.tryst.infrastructure;bundle-version="0.1.0"`
- **Exported Packages:**
  - `eu.kalafatic.tryst.application`
  - `eu.kalafatic.tryst.application.lifecycle`

### 2.5 `eu.kalafatic.tryst.ui`
- **Bundle-SymbolicName:** `eu.kalafatic.tryst.ui;singleton:=true`
- **Activator:** `eu.kalafatic.tryst.ui.TrystUiPlugin`
- **Bundle-ActivationPolicy:** `lazy`
- **Required Bundles:**
  - `org.eclipse.core.runtime`
  - `org.eclipse.ui`
  - `org.eclipse.jface`
  - `org.eclipse.ui.workbench`
  - `org.eclipse.osgi`
  - `eu.kalafatic.tryst.core;bundle-version="0.1.0"`
  - `eu.kalafatic.tryst.domain;bundle-version="0.1.0"`
  - `eu.kalafatic.tryst.infrastructure;bundle-version="0.1.0"`
- **Exported Packages:**
  - `eu.kalafatic.tryst.ui`
  - `eu.kalafatic.tryst.ui.controller`
  - `eu.kalafatic.tryst.ui.perspective`
  - `eu.kalafatic.tryst.ui.view`

---

## 3. Layer Boundary Rules

1. **Downstream Unawareness:** Lower layers (`core`, `domain`) must never reference or depend on higher layers (`infrastructure`, `application`, `ui`).
2. **Persistence Inversion:** Domain defines repository interfaces (`eu.kalafatic.tryst.domain.repository.*`); Infrastructure implements them (`eu.kalafatic.tryst.infrastructure.persistence.repository.*`).
3. **UI Decoupling:** The UI layer acquires application services via `TrystUiServices` through OSGi `ServiceTracker`, never instantiating services directly.
4. **No Framework Leaks:** Third-party libraries (e.g. SQLite JDBC) are encapsulated within `infrastructure` and never exposed across domain interfaces.
