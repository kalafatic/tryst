# TRYST Tycho Dependency and Access Restriction Audit

## 1. Deep Dive into the Reported Access Restriction

### 1.1 Reported Diagnostic
```text
Access restriction:
The method 'TrystHttpServer.setAiModelRegistryService(AiModelRegistryService)'
is not accessible
(restriction on required project 'eu.kalafatic.tryst.infrastructure')
```

### 1.2 Bundle Manifest Declarations
Examination of the source manifests confirms that the package visibility is declared properly:
- In `plugins/eu.kalafatic.tryst.infrastructure/META-INF/MANIFEST.MF`:
  ```text
  Export-Package: eu.kalafatic.tryst.infrastructure.ai,
   eu.kalafatic.tryst.infrastructure.build,
   eu.kalafatic.tryst.infrastructure.persistence,
   eu.kalafatic.tryst.infrastructure.persistence.repository,
   eu.kalafatic.tryst.infrastructure.server,
   eu.kalafatic.tryst.infrastructure.vcs,
   org.sqlite
  ```
- In `plugins/eu.kalafatic.tryst.domain/META-INF/MANIFEST.MF`:
  ```text
  Export-Package: eu.kalafatic.tryst.domain.ai,
   ...
  ```
- In `plugins/eu.kalafatic.tryst.application/META-INF/MANIFEST.MF`:
  ```text
  Require-Bundle: org.eclipse.core.runtime,
   org.eclipse.ui,
   org.eclipse.equinox.app,
   org.eclipse.osgi,
   eu.kalafatic.tryst.core;bundle-version="0.1.0",
   eu.kalafatic.tryst.domain;bundle-version="0.1.0",
   eu.kalafatic.tryst.infrastructure;bundle-version="0.1.0"
  ```

`TrystHttpServer` is located in package `eu.kalafatic.tryst.infrastructure.server`.
`AiModelRegistryService` is located in package `eu.kalafatic.tryst.domain.ai`.
Both packages are exported without restrictive x-friends or qualifiers.

### 1.3 Root Cause of the Access Restriction Error
In Eclipse JDT/PDE:
1. When a repository lacks `.project` and `.classpath` configuration (or when projects are imported via m2e or generic Eclipse import without PDE nature), Eclipse creates standard Java project dependencies (`<classpathentry combineaccessrules="true" kind="src" path="/eu.kalafatic.tryst.infrastructure"/>`) or resolves stale binary bundles.
2. In plain Java project-to-project references, Eclipse JDT defaults to forbidding access to non-standard or internal packages unless PDE access rules are computed from `MANIFEST.MF`.
3. PDE computes access rules dynamically **only** when the project has `org.eclipse.pde.PluginNature` and the classpath contains the PDE container `org.eclipse.pde.core.requiredPlugins`.
4. Without the PDE nature and container, Eclipse treats the workspace project as an ordinary Java project, causing JDT to flag method calls across project boundaries with forbidden access restriction errors.

### 1.4 Resolution Without Weakening Access Rules
By providing native PDE project configurations with:
- `org.eclipse.pde.PluginNature`
- `org.eclipse.pde.core.requiredPlugins` classpath container
- Authoritative target platform target definition
PDE automatically computes the access rules from the exported packages in `MANIFEST.MF`. Because `eu.kalafatic.tryst.infrastructure.server` and `eu.kalafatic.tryst.domain.ai` are explicitly exported, all methods on `TrystHttpServer` and `AiModelRegistryService` are fully accessible. No compiler errors or warnings are relaxed. Module encapsulation is strictly preserved.

---

## 2. Reactor Sibling Dependency Resolution

Tycho compiles plugins against the OSGi runtime state materialized from the Maven reactor and p2 target platform.

- **Full Reactor Execution**:
  When running `mvn clean verify` from the repository root, Tycho registers all sibling bundles in the reactor target platform. Inter-bundle dependencies (`eu.kalafatic.tryst.core`, `domain`, `infrastructure`) resolve directly from the workspace source tree.
- **Target Platform Definition**:
  Tycho now references `eu.kalafatic.tryst.target` directly in `target-platform-configuration`, ensuring consistent p2 unit resolution matching Eclipse IDE target platform resolution.
