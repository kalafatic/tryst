# TRYST — Project Structure

Authoritative parent/aggregator: **`pom.xml` (repository root)** — `eu.kalafatic.tryst.parent` 2.6.11-SNAPSHOT

| Layer | Location | Notes |
|---|---|---|
| Repository root | `/home/petr/git/tryst-121126` | Git root; parent POM at root |
| Build system | Maven + Tycho 4.0.8, `./mvnw` | Java 21 |
| Target platform | Eclipse 2024-03 SimRel | `targetplatform/eu.kalafatic.tryst.target/eu.kalafatic.tryst.target.target` |
| Feature | `features/eu.kalafatic.tryst.feature/feature.xml` | |
| Product | `product/eu.kalafatic.tryst.product/tryst.product` | materialized by the product module |
| Launch configs | `.launches/` (4 files) | TRYST_RCP_Application, TRYST_Headless_Server, TRYST_Tests, TRYST_Maven_Build |

## Reactor modules (10)

| Module | Role |
|---|---|
| targetplatform/eu.kalafatic.tryst.target | Target definition project |
| plugins/eu.kalafatic.tryst.core | Core services (OSGi) |
| plugins/eu.kalafatic.tryst.domain | Domain model |
| plugins/eu.kalafatic.tryst.infrastructure | Git/HTTP/SQLite infrastructure (slf4j.api) |
| plugins/eu.kalafatic.tryst.evo.integration | EVO integration |
| plugins/eu.kalafatic.tryst.application | RCP application |
| plugins/eu.kalafatic.tryst.ui | SWT UI |
| features/eu.kalafatic.tryst.feature | Feature |
| product/eu.kalafatic.tryst.product | Product assembly |
| tests/eu.kalafatic.tryst.tests | Test bundle (JUnit 5) |

Scripts: `scripts/build.sh`, `scripts/run-headless.sh`.
