# TRYST LLM Provider Discovery and Model Publication

## 1. AI Infrastructure Overview
TRYST communicates with AI inference backends (e.g. Ollama, llama.cpp, remote APIs) via standard Java 21 `java.net.http.HttpClient`.

## 2. Model Registry & Publication
Managed by `AiModelRegistryService`:
- Discovers available models via `/api/tags` on provider endpoints.
- Tracks provider connectivity and health (`ONLINE`, `DEGRADED`, `OFFLINE`).
- Exposes published models with metadata: identifier, display name, context limit, availability, and minimum required `UserRole`.
- Filters published models by role (`ADMIN`, `PROJECT_OWNER`, `DEVELOPER`, `REVIEWER`, `USER`).
- User endpoint `/api/models` exposes only models authorized for the caller's role, never exposing provider credentials or internal tokens.
