# TRYST Server Operations and First-Run Guide

## 1. First-Run Setup Procedure
1. Launch TRYST headless or via the desktop RCP launcher:
   ```bash
   ./tryst -application eu.kalafatic.tryst.application.server
   ```
2. When launching without an existing administrator, observe the startup log for the one-time setup token.
3. Access the web interface at the advertised endpoint (e.g., `http://127.0.0.1:8080/`).
4. Complete the initial setup wizard to create the administrator username and password.

## 2. Dynamic Networking Configuration
In `~/.tryst/tryst.properties`:
```properties
server.host=127.0.0.1
server.http.port=8080
server.dynamicPortFallback=true
server.portRangeStart=8080
server.portRangeEnd=8180
server.publicUrl=https://tryst.example.com
server.reverseProxyEnabled=false
```
If port 8080 is occupied, TRYST automatically binds an alternative port in the range, or an OS-assigned ephemeral port.
