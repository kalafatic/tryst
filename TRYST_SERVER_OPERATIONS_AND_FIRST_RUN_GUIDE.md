# TRYST Server Operations and First-Run Guide

## 1. First-Run Setup Procedure
1. Launch TRYST headless or via the desktop RCP launcher:
   ```bash
   ./tryst -application eu.kalafatic.tryst.application.server
   ```
2. When launching without an existing administrator, observe the startup log for the one-time setup token.
3. Access the web interface at the advertised endpoint (e.g., `http://127.0.0.1:38080/tryst`).
4. Complete the initial setup wizard to create the administrator username and password.

## 2. Dynamic Networking Configuration
In `~/.tryst/tryst.properties`:
```properties
server.host=127.0.0.1
server.http.port=38080
server.dynamicPortFallback=true
server.portRangeStart=38080
server.portRangeEnd=38580
server.publicUrl=https://tryst.example.com
server.reverseProxyEnabled=false
```
If port 38080 is occupied, TRYST automatically binds an alternative coherent port group using standard offsets, or an OS-assigned ephemeral port. TRYST never binds to port 8080.
