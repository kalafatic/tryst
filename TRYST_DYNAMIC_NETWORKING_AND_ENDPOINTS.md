# TRYST Dynamic Networking and Endpoints

## 1. Overview
TRYST eliminates hardcoded port assumptions by implementing runtime socket binding with automatic dynamic fallback and reverse proxy support.

## 2. Dynamic Port Allocation Algorithm
1. The server requests binding on the configured preferred port (`httpPort`).
2. If the preferred port is occupied:
   - If `server.dynamicPortFallback` is enabled, it attempts binding across `server.portRangeStart` to `server.portRangeEnd`.
   - If the range is exhausted or unavailable, it falls back to an OS-assigned ephemeral port (`new InetSocketAddress(host, 0)`).
3. The actual bound port is extracted directly from the active socket (`server.getAddress().getPort()`).
4. `TrystHttpServer` publishes the resulting `RuntimeEndpoint` record into `RuntimeEndpointRegistry`.

## 3. Runtime Endpoint Model & Propagation
The `RuntimeEndpoint` record captures:
- `applicationId`: Server identity name.
- `bindAddress`: Local bind host IP (e.g., `127.0.0.1`).
- `httpPort`: Actual bound HTTP port.
- `internalUrl`: Derived internal URL (e.g., `http://127.0.0.1:38080`).
- `externalUrl`: External/public URL, respecting `reverseProxyEnabled` and `publicServerUrl`.
- `listenerState`: Status (`RUNNING` / `STOPPED`).
- `startupTime`: Instant when listener bound successfully.

Components such as `EmbeddedBrowserView` register listeners with `RuntimeEndpointRegistry` to automatically navigate to the active URL without requiring manual reconfiguration.
