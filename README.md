# Helix

Helix is a homelab cockpit for Android XR headsets and spatial glasses (for example Android XR devices and
URXR-style glasses that use the phone as a trackpad). It puts your homelab in one set of floating panels:

- **Terminal and files:** SSH terminals, SFTP and Docker through [Termix](https://github.com/Termix-SSH/Termix).
- **Desktop streaming:** native GameStream/Moonlight streaming from Sunshine or Vibepollo, with a
  moonlight-web WebSocket fallback over port 443.
- **Monitoring:** host metrics from Prometheus, alerts from Grafana, Uptime Kuma (through Prometheus) and Diun.
- **Media:** a small floating Jellyfin/Plex player and Spotify Connect control.
- **Wake-on-LAN** for hosts that Termix knows about.

Two layouts are available: **Panels** (the default) and **Tree**.

> Status: early and personal. Helix is built for one homelab and shared as-is. Expect rough edges.

## What you need

Helix signs in with an **Authentik passkey**, then uses Termix's OIDC login behind Authentik. It does not
work without Authentik today. You need:

| Service | Needed for | Notes |
| --- | --- | --- |
| [Authentik](https://goauthentik.io) | Sign-in | A passkey-only authentication flow, proxy outposts in front of Termix and moonlight-web, and a small patch so it accepts the Android app's passkey origin. See [docs/server-setup.md](docs/server-setup.md). |
| [Termix](https://github.com/Termix-SSH/Termix) | Hosts, terminals, files, Docker, WoL | OIDC login through Authentik. |
| [Sunshine](https://github.com/LizardByte/Sunshine) or Vibepollo | Streaming | Pair from Helix with a PIN. |
| [moonlight-web](https://github.com/MrCreativ3001/moonlight-web-stream) | Streaming away from home (optional) | WebSocket fallback on port 443. |
| Prometheus, Grafana | Metrics and alerts (optional) | Behind Authentik as well. |
| Jellyfin or Plex, Spotify | Media (optional) | Spotify needs your own client ID. |

## Build

Requirements: JDK 17 or newer, the Android SDK, and NDK `28.2.13676358` (the native Moonlight core is
built with CMake).

```sh
./gradlew assembleDebug
```

The APK is in `app/build/outputs/apk/debug/`.

Every server URL is blank by default. Either enter the URLs on the sign-in screen (**Edit** under the
endpoints), or bake your defaults into the build with `HELIX_*` environment variables or Gradle properties.
All options are listed in [docs/configuration.md](docs/configuration.md).

```sh
HELIX_AUTHENTIK_URL=https://auth.example.com \
HELIX_TERMIX_URL=https://termix.example.com \
HELIX_MOONLIGHT_URL=https://moonlight.example.com \
./gradlew assembleDebug
```

### Signing and passkeys

Android passkeys are bound to the app's signing certificate. Whatever key signs your build (the debug key
or your own release key), its SHA-256 fingerprint must be in Authentik's `assetlinks.json` and its
`android:apk-key-hash:` origin must be allowed by the Authentik patch. [docs/server-setup.md](docs/server-setup.md)
shows how to get both values.

Release signing reads `HELIX_RELEASE_KEYSTORE_PATH`, `HELIX_RELEASE_STORE_PASSWORD`, `HELIX_RELEASE_KEY_ALIAS`
and `HELIX_RELEASE_KEY_PASSWORD`. Without them the release build is unsigned.

## Contributing

Issues and pull requests are welcome. Pull requests run the unit tests, lint and a debug build on
GitHub-hosted runners, plus gitleaks and semgrep. Please don't include real hostnames, IP addresses or
credentials in code or tests: use `example.com` and the `192.0.2.0/24` documentation range.

## License

Helix is licensed under the **GNU General Public License v3.0**. See [LICENSE](LICENSE). Helix includes
[moonlight-common-c](https://github.com/moonlight-stream/moonlight-common-c) and code ported from
[moonlight-android](https://github.com/moonlight-stream/moonlight-android), both GPL-3.0. Third-party
notices are in [NOTICE](NOTICE).
