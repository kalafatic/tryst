# TRYST PDE Configuration Audit

## 1. Eclipse PDE Import and Workspace Structure

Historically, the TRYST repository omitted `.project` and `.classpath` files (and ignored them in `.gitignore`), leading developers and CI environments to import submodules as unconfigured or plain Java projects. This caused severe workspace issues, including:
1. Missing OSGi bundle recognition.
2. Unresolved `org.eclipse.pde.core.requiredPlugins` classpath containers.
3. Access restriction compiler errors on validly exported packages.
4. Missing Java 21 compliance settings defaulting to older or incompatible compiler profiles.

---

## 2. Project Descriptors and Classpath Configuration

Authoritative Eclipse metadata has been committed to the repository for all modules.

### 2.1 Project Natures and Build Commands

Each OSGi plug-in project now specifies:
- **Natures**:
  - `org.eclipse.jdt.core.javanature`
  - `org.eclipse.pde.PluginNature`
  - `org.eclipse.m2e.core.maven2Nature`
- **Builders**:
  - `org.eclipse.jdt.core.javabuilder`
  - `org.eclipse.pde.ManifestBuilder`
  - `org.eclipse.pde.SchemaBuilder`
  - `org.eclipse.m2e.core.maven2Builder`

The Feature project specifies `org.eclipse.pde.FeatureNature` and `org.eclipse.pde.FeatureBuilder`.
The Target Platform project specifies `eclipse-target-definition` packaging with m2e nature.

### 2.2 Plug-in Classpath Entries

All bundle `.classpath` files conform to standard PDE structure:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<classpath>
	<classpathentry kind="src" path="src"/>
	<classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER/org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType/JavaSE-21"/>
	<classpathentry kind="con" path="org.eclipse.pde.core.requiredPlugins"/>
	<classpathentry kind="output" path="bin"/>
</classpath>
```
For `eu.kalafatic.tryst.infrastructure`, the embedded JDBC driver is declared as an exported library:
```xml
<classpathentry exported="true" kind="lib" path="lib/sqlite-jdbc.jar"/>
```

---

## 3. Java 21 Compliance Settings

In each project's `.settings/org.eclipse.jdt.core.prefs`, Java 21 compliance is explicitly established:
- `org.eclipse.jdt.core.compiler.compliance=21`
- `org.eclipse.jdt.core.compiler.source=21`
- `org.eclipse.jdt.core.compiler.codegen.targetPlatform=21`
- `org.eclipse.jdt.core.compiler.problem.assertIdentifier=error`
- `org.eclipse.jdt.core.compiler.problem.enumIdentifier=error`

This ensures that Eclipse JDT compiles all code against the authoritative Java 21 language specification matching Tycho's `<executionEnvironment>JavaSE-21</executionEnvironment>`.
