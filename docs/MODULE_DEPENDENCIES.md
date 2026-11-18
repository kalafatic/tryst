# TRYST — Module Dependencies

Reactor order (dependency-driven):

```
targetplatform/eu.kalafatic.tryst.target
plugins/eu.kalafatic.tryst.core            (org.eclipse.osgi, equinox.common, core.runtime)
plugins/eu.kalafatic.tryst.domain          → core
plugins/eu.kalafatic.tryst.infrastructure  → core, domain; slf4j.api
plugins/eu.kalafatic.tryst.evo.integration → core, domain, infrastructure
plugins/eu.kalafatic.tryst.application     → core, domain, infrastructure, evo.integration
plugins/eu.kalafatic.tryst.ui              → core, domain, infrastructure, application
features/eu.kalafatic.tryst.feature        → all application plugins
product/eu.kalafatic.tryst.product         → feature
tests/eu.kalafatic.tryst.tests             → all application plugins + junit-jupiter
```

External dependencies: Eclipse 2024-03 SimRel platform bundles; JUnit Jupiter 5.10 (from SimRel); slf4j.api.
