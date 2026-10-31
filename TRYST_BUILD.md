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
