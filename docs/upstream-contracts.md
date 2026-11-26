# Upstream contracts

Helix depends on behaviour in five upstream projects that they don't document as a stable API: Termix, Authentik, Sunshine/Vibepollo, moonlight-web and the Android XR libraries. This file records what Helix relies on, how it breaks when upstream changes, and what to check after an upgrade.

Last checked against Termix 26.11.3 (the client headers still say 26.11.2), Authentik 2026.8.5 and moonlight-web v3.0.2. Paths are relative to `app/src/main/java/com/nightcinder/helix/`.

## 1. Termix private API (`/plugin-api/...`, `/plugin-ws/...`)

### What Helix relies on

| Client | Endpoints |
| --- | --- |
| `api/TermixApiClient.kt` | `GET /plugin-api/host-metrics/metrics/summary`, `GET /plugin-api/host-metrics/health/summary`, `GET /plugin-api/host-metrics/host-metrics/managers/live/{hostId}`, `POST /plugin-api/host-metrics/metrics/start/{hostId}`, `POST /plugin-api/host-metrics/metrics/heartbeat/{hostId}`, `GET /plugin-api/wake-on-lan/devices`, `POST /plugin-api/wake-on-lan/devices/{hostId}/wake` (falls back to `POST /plugin-api/wake-on-lan/host/{hostId}/wake`), `GET /plugin-api/snippets`, `POST /plugin-api/snippets/secrets/resolve`, `POST /plugin-api/snippets/execute` |
| `api/DockerApiClient.kt` | `POST /plugin-api/docker/ssh/connect`, `POST /plugin-api/docker/ssh/keepalive`, `GET /plugin-api/docker/containers/{sessionId}`, container actions under `/containers/{sessionId}/{containerId}/{action}`, `GET .../logs?tail=N`, `GET /plugin-api/docker/stacks/{sessionId}`, `GET` and `PUT .../stacks/{sessionId}/{stackName}/compose`, `POST .../stacks/{sessionId}/{stackName}/action` |
| `api/SftpApiClient.kt` | `GET /host/db/host/{hostId}`, `POST /plugin-api/file-manager/connect`, `GET .../listFiles`, `GET .../readFile`, `POST .../writeFile`, `POST .../downloadFileStream`, `POST .../uploadFileStream`, `POST .../transferToHost` |

The same contracts are summarised in `core/api/TermixContracts.kt` and `core/api/Phase2Contracts.kt`.

WebSocket terminals (`terminal/TermixWebSocketTerminalTransport.kt`): SSH on `/plugin-ws/ssh-terminal/terminal`, Docker console on `/plugin-ws/docker/console`. The `https://` base URL is rewritten to `wss://`.

Authentication:
- Every Termix call sends `Authorization: Bearer <jwt>` (`auth/TermixAuthInterceptor.kt`).
- The JWT comes from Termix's own OIDC login, which returns to `termix-mobile://oidc-callback` (`auth/AuthentikPasskeyAuthManager.kt`). Static `tmx_` API keys are refused.
- Mutating calls (`POST`, `PUT`, `PATCH`, `DELETE`) send `X-Electron-App: true`, as the Termix desktop app does.

Data key expiry:
- A `401` whose body contains `DATA_EXPIRED` maps to `TermixApiException.DataExpired`, and the interceptor starts a single-flight re-unlock (`core/model/AuthModels.kt`, `AuthentikPasskeyAuthManager.kt`).
- A WebSocket close with code `1008` or a `DATA_EXPIRED` reason takes the same recovery path.

### How it breaks

- A renamed route or a new version prefix returns `404`, which Helix shows as a generic API error.
- If Termix stops accepting `X-Electron-App`, mutating calls fail with `401` or `403`. Those are not `DATA_EXPIRED`, so no re-unlock runs.
- If the `DATA_EXPIRED` text or close code changes, Helix stops re-unlocking and the user sees errors until they sign in again.
- A change to the OIDC callback or token delivery breaks sign-in before any API call.

### Check after an upgrade

1. Sign in and confirm the OIDC callback completes.
2. Load the host tree, metrics and Wake-on-LAN devices (the `GET` endpoints above).
3. Run one mutating action per client: a snippet, a container restart, a file save.
4. Let the data key expire (or lock it in Termix), then use Helix. It should re-unlock, not show a generic error.
5. Open an SSH terminal and a Docker console.

## 2. Authentik

### What Helix relies on

Flow executor (`auth/AuthentikPasskeyAuthManager.kt`):
- Flows run through `GET`/`POST` `/api/v3/flows/executor/<slug>/`. The sign-in slug comes from config (default `passkey-only-authentication`).
- Any page under `/api/v3/flows/executor/` is treated as a flow page.

Stage components handled (the `component` field of the executor response):

| Component | Behaviour |
| --- | --- |
| `ak-stage-identification` | Passkey sign-in step |
| `ak-stage-authenticator-validate` | Passkey challenge |
| `ak-stage-consent` | Consent; handled only in authorization flows (outposts, Termix OIDC), not in the sign-in flow |
| `ak-stage-access-denied` | Sign-in fails with Authentik's message |
| `xak-flow-redirect` | Follows the redirect; sign-in succeeded when `authentik_session` is set |

Cookies and headers:
- `authentik_session`: the long-lived sign-in.
- `authentik_csrf`: set by the flow page and echoed in `X-authentik-CSRF` on every executor `POST`.
- `authentik_proxy_*`: per-host forward-auth cookies for Termix and moonlight-web, started at `/outpost.goauthentik.io/start`.

When an outpost cookie expires, `auth/OutpostReauthInterceptor.kt` sees the redirect to Authentik and renews the outpost cookies with the existing `authentik_session`, then retries the request. That is prompt-free only while the proxy providers use an implicit-consent authorization flow.

The server also needs the WebAuthn origin patch for native Android passkeys; see `docs/server-setup.md`, "Passkey origin patch".

### How it breaks

- A renamed stage component, or a new stage in the flow, stops sign-in with `Unrecognised Authentik flow page` or `Unsupported stage '<name>'`.
- A change to the CSRF cookie or header makes executor `POST`s fail with `403`.
- A change to the `authentik_session` cookie name means sign-in never reports success.
- A change to the outpost start path or cookie prefix breaks Termix and moonlight-web with `OutpostFailure`.
- Upgrading Authentik without the origin patch brings back the "Unexpected client data origin" sign-in loop.

### Check after an upgrade

1. Rebuild the patched image (`server/authentik-patch`) and confirm the build log says `helix patch: applied`.
2. Sign in with the passkey.
3. Confirm Termix, moonlight-web and (if used) Prometheus and Grafana load.
4. Delete the `authentik_proxy_*` cookies (or wait them out) and confirm Helix recovers without a passkey prompt.

## 3. Sunshine / Vibepollo (GameStream)

### What Helix relies on

Files: `stream/nv/NvHttp.kt`, `stream/nv/NvGateway.kt`, `stream/nv/PairingManager.kt`.

Ports: HTTP `47989`, HTTPS `47984`.

| Endpoint | Transport |
| --- | --- |
| `serverinfo` | HTTPS first, falls back to HTTP |
| `applist`, `launch`, `resume`, `cancel` | HTTPS with client certificate |
| `pair` (first stages, `getservercert`) | HTTP, no client certificate; `getservercert` is a long poll that answers after the PIN is typed on the host |
| `pair` (`phrase=pairchallenge`) | HTTPS with client certificate |
| `unpair` | HTTP |
| `actions/clipboard?type=text` (`GET`, `POST`) | HTTPS with client certificate |

Every request carries `uniqueid` and `uuid`. Pairing also sends `devicename` and `updateState=1`. A `serverinfo` without `PairStatus` counts as unpaired, and `appversion` must be present.

TLS rules (the reasons are in the comments in `NvHttp.kt`):
- Mutual TLS: Helix presents its client certificate and trusts only the pinned server certificate. The hostname is not checked; the pin is the identity.
- A fresh `SSLContext` for every connection, so no TLS session is ever resumed. A resumed handshake with client auth makes Vibepollo answer `internal_error` or stall.
- No connection reuse (`ConnectionPool(0, ...)`). Vibepollo stalls new handshakes while an earlier client-auth connection is open.
- HTTP/1.1 only. Offering HTTP/2 caused stream resets.
- The `OkHttpClient` itself is cached per pinned certificate; only the TLS context and the connection are fresh.

### How it breaks

- A TLS or session-handling change can bring back the stall: calls hang until the 10-second read timeout.
- A change to the `serverinfo` XML (`appversion`, `PairStatus`) breaks pairing detection.
- A change to the clipboard endpoint or its `type=text` query breaks clipboard sync.
- A change to the pairing challenge breaks pairing after the PIN.

### Check after an upgrade

1. Pair from scratch and confirm the PIN flow completes.
2. Open the app list, launch, resume and quit. None should hang.
3. Make two quick calls in a row (for example two clipboard pushes). The second must not stall.
4. Copy text both ways and confirm it arrives.
5. Restart the host and confirm Helix still sees the pairing.

## 4. moonlight-web (WebSocket fallback)

### What Helix relies on

Files: `stream/MoonlightWebSocketTransport.kt`, `stream/MoonlightWsProtocol.kt`; built against `moonlight-web-stream` v3.0.2.

Capability probe, in order:
1. `GET /api/user`
2. `GET /api/role` (or `?id=<roleId>`)
3. `GET /api/hosts`: `application/x-ndjson`, first line `{"hosts":[...]}`, then one line per host
4. Host lookup
5. `GET /api/apps?host_id=<id>`

Stream: `wss://<moonlight-web>/api/host/stream/web_socket` (`StreamConfig.WS_STREAM_PATH`). A close with code `1008` or a `DATA_EXPIRED` reason is treated as expired credentials, and `DualPathMoonlightClient` reconnects after the Authentik cookies are renewed.

### How it breaks

- A changed probe path or response shape makes the probe report `Unavailable` with the reason; the stream falls back or shows the error.
- A change to the `/api/hosts` ndjson format breaks host matching.
- A changed WebSocket path makes the connection fail at the upgrade.

### Check after an upgrade

1. Update the version in the code comments if the server version changed.
2. Off the LAN (no VPN), open a stream and confirm the probe reaches `Available`, video plays and input works.
3. Let the Authentik cookies expire and confirm the stream reconnects.

## 5. Android XR libraries

### What Helix relies on

From `gradle/libs.versions.toml`:
- `androidx.xr.compose:compose` `1.0.0-rc01` (release candidate)
- `androidx.xr.scenecore:scenecore` `1.1.0-alpha02` (alpha; APIs can change without notice)

| Where | What |
| --- | --- |
| `MainActivity.kt` | `requestFullSpace`, `requestHomeSpace`, `SpaceRequestResult` |
| `ui/spatial/AndroidXrCockpitScreen.kt` | `Subspace`, `SpatialPanel`, `SpatialActivityPanel`, `SpatialExternalSurface`, `StereoMode`, `Orbiter`, `OrbiterDefaults`, `OrbiterPosition`, layout modifiers (`movable`, `resizable`, `offset`, `rotate`, `width`, `height`) |
| `ui/tree/HelixTreeApp.kt` | `Subspace`, `SpatialPanel`, `Orbiter`, layout modifiers |
| `ui/panels/PanelsHost.kt`, `ui/dashboard/DashboardScreen.kt`, `AndroidXrCockpitScreen.kt`, `HelixTreeApp.kt` | `LocalSpatialCapabilities` (Full Space vs Home Space) |

### How it breaks

- A renamed or removed alpha symbol stops the build.
- Changed `Orbiter` defaults move the panels.
- Changed `requestFullSpace` behaviour breaks the Full/Home Space switch.

### Check after an upgrade

1. Build; fix compile errors in the files above.
2. On the headset (or the Android XR emulator), open both cockpits in Full Space and Home Space.
3. Detach a panel window and play the floating media panel in Full Space.
