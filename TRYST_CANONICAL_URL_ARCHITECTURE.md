# TRYST Canonical URL Architecture (`/tryst`)

## 1. Executive Summary & Objective
TRYST establishes `http://localhost/tryst` as its canonical browser-facing application URL, decoupling the public, external address from the internal HTTP server socket listener port.

## 2. Distinction of Endpoints
The platform explicitly differentiates:
- **Internal Bind Address & Port**: The IP address and local port to which the underlying HTTP server socket binds (e.g. `127.0.0.1:8080` or dynamically chosen fallback port `8081`).
- **Actual Bound Server Endpoint**: The active socket endpoint queried directly from `server.getAddress().getPort()`.
- **Application Context Path**: The URL prefix under which the application resources reside (default: `/tryst`).
- **Browser-Facing Canonical URL**: The canonical external URL accessed by clients and browsers (`http://localhost/tryst`).
- **Reverse Proxy URL**: When reverse proxying is enabled (`server.reverseProxyEnabled=true`), the configured public URL (e.g. `https://dev.kalafatic.eu/tryst`) supersedes internal listener endpoints.

## 3. Context Path & Routing Implementation
- **HTTP Routing in `TrystHttpServer`**:
  - Context path routes are registered for all REST APIs under `/{contextPath}/api/...` as well as backward-compatible `/api/...`.
  - The Web Application and Code Review Center is registered at `/{contextPath}`.
  - A root handler at `/` issues an explicit HTTP 302 redirect to `/{contextPath}`.
- **Session & Cookie Security**:
  - `AuthenticationService.login()` issues a `Set-Cookie: tryst_session=<token>; Path=/tryst; HttpOnly; SameSite=Lax`.
  - HTTP request authentication resolves tokens from either `Authorization: Bearer <token>` or the `tryst_session` cookie.
  - Logout clears the cookie with `Max-Age=0` and invalidates the session server-side.
- **Dynamic Port Allocation & Port 80 Diagnostics**:
  - If preferred port 80 is configured and unavailable due to non-root privileges (e.g. `Permission denied`) or port collision:
    - Diagnostic log warning is emitted: *"Canonical port 80 is unavailable or requires privileged access... TRYST will allocate an internal listener port while preserving canonical external endpoint mapping."*
    - The server dynamically falls back to an available port in `server.portRangeStart` - `server.portRangeEnd` (8080-8180) or an ephemeral port.
    - The canonical URL (`http://localhost/tryst`) and external URL remain consistent and authoritative in `RuntimeEndpointRegistry`.
- **RCP Embedded Browser Integration**:
  - `EmbeddedBrowserView` resolves the authoritative URL from `RuntimeEndpointRegistry`.
  - It prioritizes `canonicalUrl()` and `externalUrl()` (with `/tryst`) over raw internal listener sockets, ensuring seamless local navigation.
