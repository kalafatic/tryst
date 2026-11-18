# TRYST Build and Launch Guide

## Verified toolchain

- JDK 21 (`/usr/lib/jvm/java-21-openjdk-amd64`)
- Maven 3.9.12 via the included `./mvnw`
- Linux GTK x86_64

TRYST uses Tycho 4.0.8. The machine-wide Maven 3.8.7 cannot resolve
`tycho-extras:4.0.8`, so a Maven wrapper pinned to 3.9.12 is included.

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
```

## Build

```bash
./mvnw -B -DskipTests clean verify
```

Verified result: `BUILD SUCCESS` for all modules.

`scripts/build.sh` performs the same build with JDK 21 and prefers the included wrapper.
Override the Maven executable with `MVN_CMD` if needed.

## Products

| Product | Directory | Launcher |
|---|---|---|
| TRYST RCP | `product/eu.kalafatic.tryst.product/target/products/eu.kalafatic.tryst.product.id/linux/gtk/x86_64` | `tryst` |

Product identity:

- Product definition id / registered product: `eu.kalafatic.tryst.application.product`
- Product file uid: `eu.kalafatic.tryst.product.id`
- UI application: `eu.kalafatic.tryst.application.application`
- Headless application: `eu.kalafatic.tryst.application.server`

## Launch configurations

Launch files are in `.launches/` and are visible when the repository root project
(`eu.kalafatic.tryst.parent`) is imported.
The launches explicitly select this repository's bundles, so they stay isolated when
other projects or repository variants are imported into the same workspace.

| Launch | Purpose |
|---|---|
| `TRYST_RCP_Application.launch` | Full TRYST RCP product |
| `TRYST_Headless_Server.launch` | Headless server application |
| `TRYST_Tests.launch` | PDE JUnit 5 run of `eu.kalafatic.tryst.tests` |
| `TRYST_Maven_Build.launch` | `clean verify -DskipTests` with JDK 21 |

## Headless runtime

```bash
scripts/build.sh
scripts/run-headless.sh
```

Or directly:

```bash
cd product/eu.kalafatic.tryst.product/target/products/eu.kalafatic.tryst.product.id/linux/gtk/x86_64
./tryst -nosplash -consoleLog -application eu.kalafatic.tryst.application.server
```

Verified: the headless server reaches `APPLICATION_READY`, reports `Server Readiness: READY`
for authentication, database and HTTP components, and listens on `127.0.0.1:38280`
(configured HTTP port 38080 plus the active port-group offset). It uses `~/.tryst` for
configuration and `~/.tryst/workspace` for workspaces.

## Test status

```bash
./mvnw -B verify
```

Verified result: 358 tests, 1 failure:

- `OpenCodeCodingHarnessAdapterTest.testRealOpenCodeTaskInsideWorkspace` -
  the external OpenCode smoke run reported success but did not create
  `smoke-output.txt`. This is an external-agent integration failure, not a build or launch
  configuration failure.

## Notes

- The Maven build emits a warning that the POM for `tycho-extras` is missing. Tycho extras
  are a set of plugins rather than a single artifact; the build and product export still
  complete successfully.
- OpenCode/Aider integration tests require the corresponding CLI tools to be installed and
  configured.
