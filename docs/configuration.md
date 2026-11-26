# Configuration

Helix has no built-in servers. Every value below is optional and blank by default.

You can set them in two ways:

- **At runtime:** the sign-in screen has an **Edit** button for the Authentik, Termix, Moonlight, Prometheus
  and Grafana URLs. The Jellyfin server currently comes only from `HELIX_JELLYFIN_URL`.
- **At build time:** set environment variables (or Gradle properties, `-PNAME=value` or
  `~/.gradle/gradle.properties`) when you build. They become the app's defaults. In GitHub Actions, the CI
  release build reads them from repository **variables** (not secrets; none of them is secret).

| Variable | Example | Used for |
| --- | --- | --- |
| `HELIX_AUTHENTIK_URL` | `https://auth.example.com` | Passkey sign-in and `assetlinks.json` host. |
| `HELIX_TERMIX_URL` | `https://termix.example.com` | Termix API and terminals. Its host is also resolved to tell LAN from WAN (see below). |
| `HELIX_MOONLIGHT_URL` | `https://moonlight.example.com` | moonlight-web; the WebSocket stream fallback is derived from it. |
| `HELIX_PROMETHEUS_URL` | `https://prometheus.example.com` | Host metrics and Uptime Kuma alerts. |
| `HELIX_GRAFANA_URL` | `https://grafana.example.com` | Grafana alerts. |
| `HELIX_JELLYFIN_URL` | `https://jellyfin.example.com` | Default Jellyfin server. |
| `HELIX_STREAM_HOST` | `10.0.0.20` | Default Sunshine/Vibepollo host IP. See "Cleartext" below. |
| `HELIX_STREAM_HOST_NAME` | `desktop` | Termix host name to highlight as the recommended stream target. |
| `HELIX_WOL_HOST` | `workstation` | Termix host name for the quick Wake-on-LAN action. |
| `HELIX_WOL_MAC` | `aa:bb:cc:dd:ee:ff` | MAC address for that host, if Termix has none. |
| `HELIX_DIUN_HOST` | `server` | Termix host that runs the Diun container (its logs feed the alert inbox). Blank turns Diun alerts off. |
| `HELIX_HOST_ALIASES` | see below | Matches Prometheus instances and alert labels to Termix hosts. |
| `HELIX_SPOTIFY_CLIENT_ID` | | Your Spotify app's client ID (PKCE, no secret). Blank shows "Spotify not configured". |

## Host aliases

Prometheus instance labels, Uptime Kuma monitors and Grafana alert labels rarely use the same names as your
Termix hosts. `HELIX_HOST_ALIASES` maps them:

```text
server=docker-host,nvidia-gpu-exporter,10.0.0.10;desktop=10.0.0.20;nas=esxi,vcenter
```

- Entries are separated by `;`. Each entry is `termix-host=alias,alias,...`.
- An alias can be a full name or IP, a short name (`docker-host` also matches `docker-host.lan:9100`), or a
  word inside a longer name (`nas` matches `nas-backup`). An alias with a `-` also matches as a prefix or a
  `-suffix`.
- Uptime Kuma monitors that match nothing go to the first host in the list.

## LAN or WAN

Helix resolves the Termix host name. If DNS answers with a private address (10.0.0.0/8, 172.16.0.0/12 or
192.168.0.0/16), Helix treats the network as LAN; otherwise as WAN. This works with split-horizon DNS or a VPN
that pushes your home DNS.

## Cleartext

GameStream pairing and server info use plain HTTP on port 47989. Android blocks plain HTTP unless the app
allows it:

- With `HELIX_STREAM_HOST` set, the build allows plain HTTP to that address only.
- Without it, the stream host is chosen at runtime and can be any address, so the build allows plain HTTP
  app-wide. All of Helix's own server traffic still uses `https://` and `wss://`.
