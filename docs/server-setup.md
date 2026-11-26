# Server setup

Helix signs in like this:

1. **Passkey** against an Authentik flow (native Android Credential Manager, no browser).
2. **Proxy outposts:** with the Authentik session, Helix collects the `authentik_proxy_*` cookies for the
   Termix and moonlight-web hosts (and, best effort, Prometheus and Grafana).
3. **Termix OIDC:** Helix starts Termix's OIDC login (`/users/auth/oidc/start`), Authentik completes it,
   and Termix returns its session token to `termix-mobile://oidc-callback`.
4. **Termix data unlock** is checked before the terminal and file views open.

This page lists what each service needs. It assumes you already run Authentik and Termix behind a
reverse proxy with HTTPS.

## Authentik

### Passkey flow

Create an authentication flow whose only stage is an **Authenticator Validation** stage that accepts
WebAuthn (passkeys). Helix uses the flow slug `passkey-only-authentication` by default. Enroll a passkey
for your user first (for example from the Authentik web UI on the same phone or headset, saved in your
password manager).

### Passkey origin patch

A native Android passkey sends the origin `android:apk-key-hash:<hash>` instead of an `https://` origin.
Upstream Authentik only accepts the `https://` origin, so the sign-in loops with
"Unexpected client data origin". The files in [`server/authentik-patch`](../server/authentik-patch) build an
Authentik image with a one-line change: the WebAuthn validation also accepts the origins listed in the
`HELIX_WEBAUTHN_EXTRA_ORIGINS` environment variable (comma separated).

```sh
# Pick the Authentik release you run; the tag name is up to you.
docker build --build-arg AUTHENTIK_VERSION=2026.8.5 -t local/authentik-helix server/authentik-patch
```

Use that image for both `server` and `worker`, and set on both:

```yaml
environment:
  HELIX_WEBAUTHN_EXTRA_ORIGINS: "android:apk-key-hash:<hash-of-your-signing-key>"
```

The build fails on purpose if a future Authentik version moves the patched line, so you never run an
unpatched image by accident. When you upgrade Authentik, change `AUTHENTIK_VERSION` and rebuild.

### Digital Asset Links

Android only offers a passkey to an app that the website lists in
`https://<authentik-host>/.well-known/assetlinks.json`:

```json
[{
  "relation": ["delegate_permission/common.get_login_creds"],
  "target": {
    "namespace": "android_app",
    "package_name": "com.onyxlens.helix",
    "sha256_cert_fingerprints": ["AA:BB:...:FF"]
  }
}]
```

Serve that file from the Authentik host. One way is a tiny static container routed by your reverse proxy
for `Host(<authentik-host>) && Path(/.well-known/assetlinks.json)` only.

### Getting the two values for your signing key

```sh
# SHA-256 fingerprint (assetlinks.json), for the debug key or your release keystore:
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android | grep SHA256

# android:apk-key-hash origin: the same SHA-256 digest, base64url-encoded without padding.
python3 -c "import base64,sys; print('android:apk-key-hash:' + base64.urlsafe_b64encode(bytes.fromhex(sys.argv[1].replace(':',''))).decode().rstrip('='))" AA:BB:...:FF
```

Each key you install with (debug, release) needs its own entry in both places.

### Proxy providers and sessions

- Put **Termix** and **moonlight-web** behind Authentik **proxy providers** (forward auth with an outpost).
  Helix starts each outpost login at `/outpost.goauthentik.io/start`.
- Prometheus and Grafana are optional. If used, put Prometheus behind a proxy provider and give Grafana a
  generic OAuth (OIDC) login through Authentik.
- Set the authorization flows of these providers to **implicit consent**, so Helix never stops on a consent
  page.
- For a sign-in that lasts like other apps, give the passkey flow's User Login stage a long session
  duration (for example `days=365`).

## Termix

Enable OIDC login in Termix with an Authentik **OAuth2/OpenID provider**. Helix opens
`<termix>/users/auth/oidc/start` and expects Termix to finish at `termix-mobile://oidc-callback` with its
session token, the same way the Termix mobile app signs in.

Hosts, Wake-on-LAN devices and Docker access all come from Termix, so add your hosts there.

## Streaming

- **Sunshine or Vibepollo** on the PC you want to stream. Pair from Helix: it shows a PIN to enter in the
  host's web UI (`https://localhost:47990`).
- On the LAN or a VPN, Helix streams directly (GameStream on 47984-48010). Pairing and server info use
  plain HTTP on 47989; see `HELIX_STREAM_HOST` in [configuration.md](configuration.md).
- Away from home without a VPN, Helix can fall back to **moonlight-web** over a WebSocket on port 443.

## Monitoring (optional)

- **Prometheus:** Helix reads node_exporter (Linux), windows_exporter, a vSphere exporter (ESXi), an NVIDIA
  GPU exporter, and Uptime Kuma metrics if Prometheus scrapes them.
- **Grafana:** active alerts.
- **Diun:** image-update notices are read from the Diun container's logs over Termix; set `HELIX_DIUN_HOST`.

Use `HELIX_HOST_ALIASES` to tell Helix which Prometheus instances and alert labels belong to which Termix
host.
