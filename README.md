# TRYST – AI Development Environment

TRYST is an AI-powered autonomous software development environment desktop application inspired by Google Jules, built as an independent Eclipse Rich Client Platform (RCP) application.

## Key Features

- **Standalone Eclipse RCP Application**: Independent desktop workbench with native menus, status line, and perspectives.
- **Strict MVC Architecture**: Decoupled Model, View, and Controller layers.
- **Pure Java 21 Foundation**: Built on Eclipse OSGi runtime and Tycho build system.
- **Extensible Architecture**: Clean boundaries ready for local LLM (Ollama) and autonomous agent extensions in subsequent prompts.

## Architecture & Documentation

- [Architecture Overview](TRYST_ARCHITECTURE.md)
- [MVC Architecture & Implementation](TRYST_MVC_ARCHITECTURE.md)
- [Project Structure](TRYST_PROJECT_STRUCTURE.md)
- [Build and Launch Guide](TRYST_BUILD.md)

## Quick Start

### Build and Test
```bash
mvn clean verify
```

### Run Standalone Desktop App
```bash
./product/eu.kalafatic.tryst.product/target/products/eu.kalafatic.tryst.product.id/linux/gtk/x86_64/tryst
```
