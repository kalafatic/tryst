# TRYST Build & Execution Guide

## Prerequisites

- Java 21 JDK (OpenJDK 21 recommended)
- Maven 3.9+
- Linux (GTK x86_64), macOS, or Windows
- Virtual display (e.g. `xvfb-run`) for running headless UI tests on Linux servers

## Building the Project

Run Maven Tycho from the repository root:

```bash
mvn clean verify
```

This command will:
1. Compile all bundles (`core`, `domain`, `ui`, `application`, `tests`) against Eclipse 2024-03 target platform with Java 21 release flags.
2. Execute the JUnit 5 test suite via Tycho Surefire OSGi test booter.
3. Package OSGi plugins and features.
4. Materialize and assemble the standalone RCP application under `product/eu.kalafatic.tryst.product/target/products/eu.kalafatic.tryst.product.id/`.
5. Create compressed distribution archive: `product/eu.kalafatic.tryst.product/target/products/eu.kalafatic.tryst.product.id-linux.gtk.x86_64.tar.gz`.

## Launching the Standalone Product

### On Linux Desktop
```bash
./product/eu.kalafatic.tryst.product/target/products/eu.kalafatic.tryst.product.id/linux/gtk/x86_64/tryst
```

### On Headless Linux (CI / Docker)
```bash
xvfb-run -a ./product/eu.kalafatic.tryst.product/target/products/eu.kalafatic.tryst.product.id/linux/gtk/x86_64/tryst
```

### Passing Workspace Path
```bash
./product/eu.kalafatic.tryst.product/target/products/eu.kalafatic.tryst.product.id/linux/gtk/x86_64/tryst -data /path/to/workspace
```

## Troubleshooting & Common Build Issues

### 1. `Missing requirement: eu.kalafatic.tryst.application requires 'osgi.bundle; eu.kalafatic.tryst.core 0.1.0' but it could not be found`

#### Root Cause
Tycho resolves OSGi bundle dependencies using Eclipse p2 target platform resolution. Internal module dependencies within the multi-module project (e.g., `eu.kalafatic.tryst.core`, `eu.kalafatic.tryst.domain`, `eu.kalafatic.tryst.infrastructure`) are **not** standard Maven JAR artifacts; they are resolved either:
1. From the current Maven reactor when building from the repository root, OR
2. From locally installed p2 artifacts in your local `~/.m2/repository` (populated by `mvn install`).

If you attempt to build a single submodule directly from its subdirectory (such as running `cd plugins/eu.kalafatic.tryst.application && mvn compile`) before running a full reactor build or install, Tycho cannot locate the sibling bundle in the remote Eclipse p2 repository and fails dependency resolution.

#### Solution
Always invoke Maven from the repository root:
```bash
# Build the entire reactor from repository root:
mvn clean verify

# Or to install p2 artifacts to ~/.m2/repository:
mvn clean install -DskipTests
```

If you need to build or test a specific submodule, always execute from the root directory using the reactor project list flag (`-pl`):
```bash
mvn compile -pl plugins/eu.kalafatic.tryst.application
```
(Ensure `mvn clean install -DskipTests` has been run from root at least once beforehand if not including upstream project dependencies in the reactor).

### 2. JDK Version Warnings (`Using JavaSE-25 to fulfill requested profile of JavaSE-21`)

#### Root Cause
TRYST targets **JavaSE-21**. Running Maven with an incompatible or newer major JDK version (such as JDK 25) can trigger Tycho compilation warnings, bytecode incompatibility, or JVM warning messages (`sun.misc.Unsafe`, native access warnings).

#### Solution
Ensure your `JAVA_HOME` points to a certified Java 21 JDK:
```bash
export JAVA_HOME=/path/to/jdk-21
export PATH=$JAVA_HOME/bin:$PATH
java -version  # Should report Java 21.x
```

### 3. P2 Repository Conflicts / Cache Desynchronization

If Tycho reports unexpected p2 repository conflicts or caching anomalies:
```bash
# Clear Tycho p2 resolver cache
rm -rf ~/.m2/repository/.cache/tycho
```

## Eclipse IDE Setup & Troubleshooting

### 1. `Access restriction: The constructor/type '...' is not accessible (restriction on required project ...)`

#### Root Cause
In Eclipse IDE (JDT/PDE), access rules determine visibility of classes across required OSGi bundles / Eclipse projects. This compiler warning or error occurs in Eclipse IDE when:
1. Eclipse JDT treats non-API packages or project-to-project dependencies with strict access restrictions.
2. The project's Java Execution Environment (`JavaSE-21`) or PDE Target Platform is not set as the default active target in the Eclipse workspace.
3. The Eclipse workspace was imported as plain Java projects rather than as **Plug-in Projects** with PDE dependency management.

#### Solution in Eclipse IDE
1. **Verify Target Platform**: Go to `Window -> Preferences -> Plug-in Development -> Target Platform` and ensure an Eclipse 2024-03 / Java 21 target platform is active.
2. **Configure JDT Compiler Errors/Warnings**:
   - Go to `Window -> Preferences -> Java -> Compiler -> Errors/Warnings -> Deprecated and restricted API`.
   - Set **Forbidden reference (access rules)** from `Error` to `Warning` (or `Ignore`).
   - Set **Discouraged reference (access rules)** from `Warning` to `Ignore`.
3. **Update Manifest & Projects**:
   - Right-click the project(s) -> **PDE Tools** -> **Update Classpath...**.
   - Ensure `plugins/eu.kalafatic.tryst.infrastructure/META-INF/MANIFEST.MF` exports the package `eu.kalafatic.tryst.infrastructure.server`.

### 2. `Conflicting lifecycle mapping metadata (project packaging type="eclipse-test-plugin")`

#### Root Cause
This error occurs in Eclipse m2e when two conflicting lifecycle mapping connectors are installed simultaneously in your Eclipse installation:
- `org.eclipse.m2e.pde.connector` (Modern m2e PDE integration included with Eclipse 2024+)
- `org.sonatype.tycho.m2e` (Legacy Tycho m2e configurator)

Both plugins attempt to manage lifecycle mappings for packaging types `eclipse-test-plugin`, `eclipse-plugin`, and `eclipse-repository`.

#### Solution
1. **Uninstall Legacy Tycho Configurator**:
   - Open Eclipse IDE and navigate to `Help -> About Eclipse IDE -> Installation Details`.
   - In the **Installed Software** tab, search for `Tycho Project Configurator` (`org.sonatype.tycho.m2e`).
   - Select it and click **Uninstall...**.
   - Restart Eclipse IDE.
2. **Update Maven Project Configuration**:
   - In the Project Explorer, select all projects in the workspace.
   - Right-click and choose **Maven -> Update Project...** (or press `Alt + F5`).
   - Check **Force Update of Snapshots/Releases** and click **OK**.
