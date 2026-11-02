# TRYST Canonical URL Test Report

## 1. Scope of Verification
Verification of canonical URL `/tryst` routing, dynamic port fallback, reverse proxy publication, cookie-based session handling, and embedded RCP browser navigation.

## 2. Test Execution Summary

| Test Case | Method / Target | Result | Key Observation |
|---|---|---|---|
| **Root URL Redirect** | `GET http://127.0.0.1:<port>/` | **PASSED** | Returns HTTP 302 with `Location: /tryst` |
| **Context Path Web UI** | `GET http://127.0.0.1:<port>/tryst` | **PASSED** | Returns 200 OK with complete Web Review and Admin HTML |
| **API under Context Path** | `GET http://127.0.0.1:<port>/tryst/api/status` | **PASSED** | Returns 200 OK with `{"status": "UP", ...}` |
| **Backward Compatible API** | `GET http://127.0.0.1:<port>/api/status` | **PASSED** | Returns 200 OK for backward compatibility |
| **Cookie Session Login** | `POST http://127.0.0.1:<port>/tryst/api/auth/login` | **PASSED** | Returns 200 OK and `Set-Cookie: tryst_session=...; Path=/tryst; HttpOnly` |
| **Cookie Session Request** | `GET http://127.0.0.1:<port>/tryst/api/auth/me` with cookie | **PASSED** | Authenticates without Authorization Bearer header |
| **Cookie Session Logout** | `POST http://127.0.0.1:<port>/tryst/api/auth/logout` | **PASSED** | Clears session cookie (`Max-Age=0`) and revokes session |
| **Port 80 Fallback Handling** | Bind preferred port 80 as non-root | **PASSED** | Logs diagnostic warning and dynamically binds 8080-8180 while publishing `canonicalUrl = http://localhost/tryst` |
| **Reverse Proxy Resolution** | `withDynamicNetworking(..., "https://dev.kalafatic.eu/tryst", true)` | **PASSED** | `resolveEffectiveExternalUrl()` returns configured public URL |
| **RCP Embedded Browser** | `EmbeddedBrowserView.resolveBestUrl()` | **PASSED** | Navigates directly to canonical or effective external URL |

## 3. Conclusion
The canonical external URL `http://localhost/tryst` is fully functional and completely decoupled from internal socket listener ports.
