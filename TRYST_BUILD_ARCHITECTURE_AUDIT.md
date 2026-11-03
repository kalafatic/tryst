# TRYST Build Architecture Audit

## 1. Executive Summary

This document provides a comprehensive audit of the Maven Tycho, OSGi Equinox, and Eclipse PDE build architecture for the TRYST platform. TRYST is implemented as an asynchronous autonomous software development environment built upon Java 21, Eclipse RCP / Equinox, OSGi modularity, and Maven Tycho 4.0.8.

The build architecture was audited to resolve reported workspace issues, access restriction anomalies, missing target platform definitions, and workbench trim initialization exceptions.

---

## 2. Reactor and Module Declarations

The multi-module structure is structured with a root aggregator/parent POM and distinct modules for target platform definitions, domain/runtime bundles, feature packaging, standalone product assembly, and OSGi integration testing.

### 2.1 Modules Overview

| Module Directory | Artifact ID | Packaging Type | Purpose |
|---|---|---|---|
| `/` | `eu.kalafatic.tryst.parent` | `pom` | Root aggregator, Tycho configuration, Java 21 enforcement |
| `targetplatform/eu.kalafatic.tryst.target` | `eu.kalafatic.tryst.target` | `eclipse-target-definition` | Authoritative target platform specification (`.target`) |
| `plugins/eu.kalafatic.tryst.core` | `eu.kalafatic.tryst.core` | `eclipse-plugin` | Core event bus, logging, configuration, endpoint registry |
| `plugins/eu.kalafatic.tryst.domain` | `eu.kalafatic.tryst.domain` | `eclipse-plugin` | Domain entities, business services, security, repositories |
| `plugins/eu.kalafatic.tryst.infrastructure` | `eu.kalafatic.tryst.infrastructure` | `eclipse-plugin` | SQLite JDBC persistence, HTTP/TCP servers, Ollama AI client |
| `plugins/eu.kalafatic.tryst.application` | `eu.kalafatic.tryst.application` | `eclipse-plugin` | RCP entry point, lifecycle coordinator, splash screen |
| `plugins/eu.kalafatic.tryst.ui` | `eu.kalafatic.tryst.ui` | `eclipse-plugin` | SWT/JFace views, perspectives, browser view, UI services |
| `features/eu.kalafatic.tryst.feature` | `eu.kalafatic.tryst.feature` | `eclipse-feature` | Feature grouping the 5 TRYST bundles |
| `product/eu.kalafatic.tryst.product` | `eu.kalafatic.tryst.product` | `eclipse-repository` | p2 repository and materialized RCP product binary |
| `tests/eu.kalafatic.tryst.tests` | `eu.kalafatic.tryst.tests` | `eclipse-test-plugin` | Tycho Surefire JUnit 5 integration and unit test suite |

---

## 3. Dependency Graphs Across Build Subsystems

### 3.1 Maven Reactor Order
The Maven reactor builds modules in strict topological dependency order:
```text
1. eu.kalafatic.tryst.parent (pom)
2. eu.kalafatic.tryst.target (eclipse-target-definition)
3. eu.kalafatic.tryst.core (eclipse-plugin)
4. eu.kalafatic.tryst.domain (eclipse-plugin)
5. eu.kalafatic.tryst.infrastructure (eclipse-plugin)
6. eu.kalafatic.tryst.application (eclipse-plugin)
7. eu.kalafatic.tryst.ui (eclipse-plugin)
8. eu.kalafatic.tryst.feature (eclipse-feature)
9. eu.kalafatic.tryst.product (eclipse-repository)
10. eu.kalafatic.tryst.tests (eclipse-test-plugin)
```

### 3.2 OSGi Bundle Manifest Dependency Flow
```text
[eu.kalafatic.tryst.core]
       ▲
       │ Requires: core
[eu.kalafatic.tryst.domain]
       ▲
       │ Requires: core, domain, sqlite-jdbc.jar (Bundle-ClassPath)
[eu.kalafatic.tryst.infrastructure]
       ▲
       │ Requires: core, domain, infrastructure, org.eclipse.ui, org.eclipse.equinox.app
[eu.kalafatic.tryst.application]
       ▲
       │ Requires: core, domain, infrastructure, application, org.eclipse.ui
[eu.kalafatic.tryst.ui]
```

### 3.3 Product Feature Composition
`tryst.product` includes:
- `eu.kalafatic.tryst.feature`: Includes the 5 core TRYST bundles
- `org.eclipse.rcp`: Standard Eclipse RCP runtime features
- `org.eclipse.e4.rcp`: Eclipse e4 renderer and injection engine
- `org.eclipse.equinox.p2.core.feature`: Equinox provisioning runtime

---

## 4. Classpath and Build Properties Audit

All bundles define valid `build.properties`:
- `source.. = src/`
- `output.. = bin/`
- `bin.includes = META-INF/, ., ...`
- `eu.kalafatic.tryst.infrastructure` includes `lib/sqlite-jdbc.jar` in `bin.includes` and `Bundle-ClassPath: ., lib/sqlite-jdbc.jar`.
- `eu.kalafatic.tryst.application` includes `splash.bmp` and `plugin.xml`.
- `eu.kalafatic.tryst.ui` includes `plugin.xml`.

All bundles now provide authoritative `.project`, `.classpath`, and `.settings/org.eclipse.jdt.core.prefs` committed to Git.
