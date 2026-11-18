# TRYST — Build Guide

Authoritative parent: `/home/petr/git/tryst-121126` (root `pom.xml`).

Prerequisites: JDK 21, `./mvnw` (Maven 3.9.12), network for `https://download.eclipse.org/releases/2024-03`.

| Operation | Command (from repo root) | Output | Exit |
|---|---|---|---|
| Full Build | `./mvnw -B -DskipTests clean verify` | all 10 modules; product materialized in `product/eu.kalafatic.tryst.product/target/products/` | 0 |
| Clean Build | same | — | 0 |
| Build Selected Module | `./mvnw -B -DskipTests clean verify -pl <module> -am` | module + deps | 0 |
| Test | `./mvnw -B verify` | 358 tests in `eu.kalafatic.tryst.tests` | non-0 on failures |
| Validate | `./mvnw -B validate` | fast reactor validation | 0 |
| Package | `./mvnw -B -DskipTests package` | bundles + product assembly | 0 |

Environment: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`.

## Verified results (2026-11-19)
- Full reactor compile/package: **SUCCESS**.
- UPM `validate`: SUCCESS.
- Tests: 358 run — 2 failures + 2 errors in live-agent/network E2E tests (OpenCode harness, LAN access) — environment-dependent, not dependency failures. Path defect in two E2E fixtures (stale sibling repo path `DCoder-121126` → `DCode-121126`) was fixed.
