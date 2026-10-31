# TRYST Project Structure

## Repository Layout

```text
tryst/
│
├── pom.xml                                      # Parent Maven/Tycho build descriptor
├── README.md                                    # Repository overview and quickstart
├── .gitignore                                   # Ignore build artifacts, eclipse files
├── TRYST_ARCHITECTURE.md                        # Platform architecture and bundle specifications
├── TRYST_MVC_ARCHITECTURE.md                    # Model-View-Controller design specifications
├── TRYST_PROJECT_STRUCTURE.md                   # This document
├── TRYST_BUILD.md                               # Build instructions and test verification
│
├── plugins/
│   │
│   ├── eu.kalafatic.tryst.core/                 # Core utilities, contracts, exceptions, events
│   │   ├── META-INF/MANIFEST.MF
│   │   ├── build.properties
│   │   ├── pom.xml
│   │   └── src/eu/kalafatic/tryst/core/
│   │       ├── ApplicationEvent.java
│   │       ├── ApplicationEventListener.java
│   │       └── TrystException.java
│   │
│   ├── eu.kalafatic.tryst.domain/               # Domain model, state rules, domain services
│   │   ├── META-INF/MANIFEST.MF
│   │   ├── build.properties
│   │   ├── pom.xml
│   │   └── src/eu/kalafatic/tryst/domain/
│   │       ├── model/
│   │       │   ├── AgentTask.java
│   │       │   ├── ConsoleMessage.java
│   │       │   ├── ExecutionStatus.java
│   │       │   ├── Project.java
│   │       │   └── Workspace.java
│   │       └── service/
│   │           ├── WorkspaceChangeListener.java
│   │           └── WorkspaceService.java
│   │
│   ├── eu.kalafatic.tryst.application/          # Application entrypoint & workbench lifecycle
│   │   ├── META-INF/MANIFEST.MF
│   │   ├── build.properties
│   │   ├── plugin.xml
│   │   ├── pom.xml
│   │   └── src/eu/kalafatic/tryst/application/
│   │       ├── ApplicationActionBarAdvisor.java
│   │       ├── ApplicationWorkbenchAdvisor.java
│   │       ├── ApplicationWorkbenchWindowAdvisor.java
│   │       ├── TrystApplication.java
│   │       └── TrystApplicationPlugin.java
│   │
│   └── eu.kalafatic.tryst.ui/                   # Workbench UI, views, controllers, perspective
│       ├── META-INF/MANIFEST.MF
│       ├── build.properties
│       ├── plugin.xml
│       ├── pom.xml
│       └── src/eu/kalafatic/tryst/ui/
│           ├── TrystUiPlugin.java
│           ├── controller/
│           │   ├── ConsoleOutputController.java
│           │   ├── ProjectExplorerController.java
│           │   └── WorkspaceController.java
│           ├── perspective/
│           │   └── TrystMainPerspective.java
│           └── view/
│               ├── ConsoleOutputView.java
│               ├── MainWorkspaceView.java
│               └── ProjectExplorerView.java
│
├── features/
│   │
│   └── eu.kalafatic.tryst.feature/              # Feature aggregator for TRYST bundles
│       ├── build.properties
│       ├── feature.xml
│       └── pom.xml
│
├── product/
│   │
│   └── eu.kalafatic.tryst.product/              # Standalone product configuration & export
│       ├── build.properties
│       ├── pom.xml
│       └── tryst.product
│
└── tests/
    │
    └── eu.kalafatic.tryst.tests/                # JUnit 5 integration & unit test suite
        ├── META-INF/MANIFEST.MF
        ├── build.properties
        ├── pom.xml
        └── src/eu/kalafatic/tryst/tests/
            ├── DomainModelTest.java
            ├── MvcSeparationTest.java
            └── WorkspaceServiceTest.java
```
