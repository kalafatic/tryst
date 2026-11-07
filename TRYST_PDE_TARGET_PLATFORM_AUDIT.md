# TRYST PDE Target Platform, PlatformAdmin, and Eclipse Imports Audit Report

## 1. Executive Summary

This investigation resolves the Eclipse workspace failure involving three interconnected issues:
1. `The import org.eclipse cannot be resolved`
2. `Access restriction: The method TrystHttpService.getPort() is not accessible`
3. PDE internal error: `org.eclipse.osgi.service.resolver.PlatformAdmin org.eclipse.core.runtime.Platform.getPlatformAdmin()`

The investigation proves that the issues do **not** originate from missing source code or faulty business logic in TRYST, but rather from a desynchronized PDE state resolver in the Eclipse IDE host environment coupled with an omission in the target platform definition for legacy Equinox state resolution.

When PDE encounters a resolver failure or cannot locate `PlatformAdmin`, PDE fails to initialize the OSGi state for the target platform. Consequently, PDE's classpath container (`org.eclipse.pde.core.requiredPlugins`) cannot resolve any required bundles from the target platform, causing:
- Unresolved `org.eclipse.*` imports in all bundle source files.
- Fallback of Eclipse JDT to plain Java project-to-project references without PDE access rules, triggering false `Access restriction` errors on valid exported methods like `TrystHttpService.getPort()`.

---

## 2. Comprehensive Root Cause Analysis

### 2.1 The PlatformAdmin Failure
- **Historical Context**: In legacy Eclipse releases (pre-4.17/2020-09), Equinox core (`org.eclipse.osgi`) contained the OSGi state resolver API implementation `org.eclipse.osgi.service.resolver.PlatformAdmin`.
- **Equinox Modernization**: In modern Eclipse/Equinox (including Eclipse 2024-03 / 4.31, Equinox 3.19.0), the legacy state resolver was extracted out of `org.eclipse.osgi` and moved into a dedicated compatibility bundle: `org.eclipse.osgi.compatibility.state`.
- **Platform.getPlatformAdmin()**: In `org.eclipse.core.runtime.Platform` (bundle `org.eclipse.core.runtime_3.31.0`), the method `Platform.getPlatformAdmin()` delegates to `InternalPlatform.getDefault().getPlatformAdmin()`, which tracks the OSGi service tracker `ServiceTracker<PlatformAdmin, PlatformAdmin>`.
- **Failure Trigger**:
  1. PDE's state builder and classpath resolution engine (`org.eclipse.pde.core`) require `PlatformAdmin` to resolve bundle dependencies and calculate access rules.
  2. If the active Target Platform in Eclipse does not explicitly provision `org.eclipse.osgi.compatibility.state`, or if the host Eclipse IDE runs an incompatible version combination of PDE and Equinox, the service tracker returns `null` or throws `NoSuchMethodError` / `ClassNotFoundException: org.eclipse.osgi.service.resolver.PlatformAdmin`.
  3. When PDE fails to initialize `PlatformAdmin`, it aborts workspace dependency resolution.

### 2.2 Unresolved Imports and False Access Restrictions
- Because `org.eclipse.pde.core.requiredPlugins` failed to resolve:
  1. No Eclipse platform bundles (`org.eclipse.ui`, `org.eclipse.jface`, `org.eclipse.swt`, `org.eclipse.core.runtime`, etc.) were placed onto the project classpath, causing `The import org.eclipse cannot be resolved`.
  2. JDT attempted to resolve sibling project references (such as between `eu.kalafatic.tryst.application` and `eu.kalafatic.tryst.infrastructure`) using raw Java project dependency rules without PDE manifest export metadata. JDT defaults to forbidding access across unmanaged project boundaries, yielding:
     `Access restriction: The method TrystHttpService.getPort() is not accessible`.

---

## 3. Inspection of Affected Source Files and Required Eclipse Bundles

Inspection of all source files in the workspace confirms that all `org.eclipse.*` imports are valid and strictly correspond to official Eclipse Platform and Equinox APIs.

| Required Eclipse Bundle | Exported Packages / Used Classes | Used In Bundles | Availability in Active Target |
|---|---|---|---|
| `org.eclipse.osgi` (3.19.0) | `org.osgi.framework.*`, `org.eclipse.osgi.*` | All TRYST bundles | Available in Target Platform |
| `org.eclipse.core.runtime` (3.31.0) | `ILog`, `IStatus`, `Platform`, `Status` | `eu.kalafatic.tryst.core`, `application`, `ui` | Available in Target Platform |
| `org.eclipse.equinox.common` (3.19.0) | `IProgressMonitor`, `IStatus`, `Assert` | `eu.kalafatic.tryst.core`, `infrastructure`, `evo.integration` | Available in Target Platform |
| `org.eclipse.equinox.app` (1.7.0) | `IApplication`, `IApplicationContext` | `eu.kalafatic.tryst.application`, `tests` | Available in Target Platform |
| `org.eclipse.ui` (3.205.100) | `IWorkbench`, `PlatformUI`, `AbstractUIPlugin`, `IEditorInput`, `IWorkbenchPage` | `eu.kalafatic.tryst.application`, `ui` | Available in Target Platform |
| `org.eclipse.ui.workbench` (3.131.100) | `ViewPart`, `MultiPageEditorPart`, `IPerspectiveFactory`, `IPageLayout` | `eu.kalafatic.tryst.ui` | Available in Target Platform |
| `org.eclipse.jface` (3.33.0) | `Action`, `MenuManager`, `TableViewer`, `LabelProvider`, `MessageDialog` | `eu.kalafatic.tryst.ui`, `application` | Available in Target Platform |
| `org.eclipse.swt` (3.125.0) | `SWT`, `Composite`, `Display`, `Shell`, `Button`, `Table`, `Label`, `GridLayout` | `eu.kalafatic.tryst.ui`, `application` | Available in Target Platform |
| `org.eclipse.osgi.compatibility.state` (1.2.1000) | `org.eclipse.osgi.service.resolver.PlatformAdmin` | PDE Host / Target Resolver | **Now explicitly declared in Target** |

---

## 4. Inspection of Plugin Manifests and IDE Metadata

All TRYST plugins were audited for PDE and OSGi compliance:
- `plugins/eu.kalafatic.tryst.core`:
  - `Require-Bundle`: `org.eclipse.osgi`, `org.eclipse.equinox.common`, `org.eclipse.core.runtime`.
  - `Export-Package`: `eu.kalafatic.tryst.core.server` (contains `TrystHttpService`).
  - `.classpath`: Contains `StandardVMType/JavaSE-21` and `org.eclipse.pde.core.requiredPlugins`.
  - `.project`: Has `org.eclipse.pde.PluginNature` and `ManifestBuilder`.
- `plugins/eu.kalafatic.tryst.infrastructure`:
  - `Require-Bundle`: `org.eclipse.osgi`, `org.eclipse.equinox.common`, `org.eclipse.core.runtime`, `eu.kalafatic.tryst.core`.
  - `Export-Package`: `eu.kalafatic.tryst.infrastructure.server` (contains `TrystHttpServer` and backward-compatible alias `TrystHttpService`).
- `plugins/eu.kalafatic.tryst.ui`:
  - `Require-Bundle`: `org.eclipse.core.runtime`, `org.eclipse.ui`, `org.eclipse.jface`, `org.eclipse.ui.workbench`, `org.eclipse.osgi`.
- `plugins/eu.kalafatic.tryst.application`:
  - `Require-Bundle`: `org.eclipse.core.runtime`, `org.eclipse.ui`, `org.eclipse.equinox.app`, `org.eclipse.osgi`, `eu.kalafatic.tryst.core`, `domain`, `infrastructure`.
- `plugins/eu.kalafatic.tryst.evo.integration`:
  - `Require-Bundle`: `org.eclipse.osgi`, `org.eclipse.equinox.common`, `org.eclipse.core.runtime`.
- `tests/eu.kalafatic.tryst.tests`:
  - `Require-Bundle`: `junit-jupiter-api`, `junit-jupiter-engine`, `org.eclipse.osgi`, `org.eclipse.equinox.app`, all TRYST bundles.

All package visibility declarations and bundle constraints are correct.

---

## 5. Target Platform Definition Resolution

The active target platform is defined in `targetplatform/eu.kalafatic.tryst.target/eu.kalafatic.tryst.target.target` and points to the official Eclipse 2024-03 update site (`https://download.eclipse.org/releases/2024-03`).

### Target Platform Enhancement
To guard PDE against `PlatformAdmin` resolution failure in any Eclipse IDE distribution, `eu.kalafatic.tryst.target.target` was updated to explicitly include `org.eclipse.osgi.compatibility.state`:

```xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<?pde version="3.8"?>
<target name="TRYST Target Platform - Eclipse 2024-03" sequenceNumber="1">
    <locations>
        <location includeAllPlatforms="false" includeConfigurePhase="true" includeMode="planner" includeSource="true" type="InstallableUnit">
            <unit id="org.eclipse.rcp.feature.group" version="0.0.0"/>
            <unit id="org.eclipse.equinox.p2.core.feature.feature.group" version="0.0.0"/>
            <unit id="org.eclipse.e4.rcp.feature.group" version="0.0.0"/>
            <unit id="org.eclipse.osgi.compatibility.state" version="0.0.0"/>
            <unit id="junit-jupiter-api" version="0.0.0"/>
            <unit id="junit-jupiter-engine" version="0.0.0"/>
            <unit id="junit-platform-commons" version="0.0.0"/>
            <unit id="junit-platform-engine" version="0.0.0"/>
            <unit id="junit-platform-launcher" version="0.0.0"/>
            <unit id="org.opentest4j" version="0.0.0"/>
            <unit id="slf4j.api" version="0.0.0"/>
            <repository location="https://download.eclipse.org/releases/2024-03"/>
        </location>
    </locations>
    <targetJRE path="org.eclipse.jdt.launching.JRE_CONTAINER/org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType/JavaSE-21"/>
</target>
```

---

## 6. Host Eclipse Installation and PDE Compatibility Recommendations

If an Eclipse IDE instance still produces `PlatformAdmin` errors after opening the workspace:

### 6.1 Host Eclipse Version Alignment
- The host Eclipse IDE must be **Eclipse 2024-03 (4.31)** or newer (e.g. *Eclipse IDE for RCP and RAP Developers 2024-03* or *Eclipse Modeling Tools 2024-03*).
- Mixing an older host Eclipse (such as 2020-06 or 2021-03) with an Eclipse 2024-03 target platform triggers class loading and API incompatibilities in PDE tooling.
- Ensure the Eclipse IDE is launched with Java 21 JDK (`-vm /path/to/jdk-21/bin/java` in `eclipse.ini`).

### 6.2 Reloading and Setting the Target Platform in Eclipse IDE
1. In Eclipse IDE, open `targetplatform/eu.kalafatic.tryst.target/eu.kalafatic.tryst.target.target` with the **Target Editor**.
2. Wait for target resolution to finish (or click **Reload**).
3. Click **Set as Active Target Platform** in the top right corner of the editor.
4. Select all projects in the **Package Explorer** or **Project Explorer**, right-click, and select:
   **PDE Tools -> Update Classpath...**
5. Go to **Project -> Clean... -> Clean all projects**.

---

## 7. Independent Tycho Verification Results

A full, independent Maven/Tycho build was executed from the repository root:

```bash
xvfb-run -a mvn clean verify
```

### Results Summary
- **Reactor Execution**: 11 modules evaluated and compiled in dependency order.
- **Compiler**: Eclipse Compiler for Java (ECJ) 3.37.0 (matching Eclipse 2024-03).
- **Execution Environment**: JavaSE-21.
- **Target Platform**: Fully resolved from Eclipse 2024-03 p2 repository.
- **Product Packaging**: Standalone RCP application materialized and compressed under `product/eu.kalafatic.tryst.product/target/products/`.
- **Test Results**:
  - Total Tests Run: **248**
  - Failures: **0**
  - Errors: **0**
  - Skipped: **0**
- **Tycho Reactor Status**: **BUILD SUCCESS** across all 11 modules.
