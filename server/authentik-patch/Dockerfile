# Authentik with one local patch: the passkey (WebAuthn) validate stage also accepts the extra origins
# listed in HELIX_WEBAUTHN_EXTRA_ORIGINS (comma separated), e.g. android:apk-key-hash:<hash> for the Helix
# Android app. Upstream Authentik only accepts the https origin, so native Android passkey sign-in loops
# without this. See docs/server-setup.md.
#
# UPGRADING AUTHENTIK: change AUTHENTIK_VERSION (or pass --build-arg) and rebuild. The build FAILS if the
# patch no longer applies (upstream moved the code); then re-check
# authentik/stages/authenticator_validate/challenge.py before changing the patch.
ARG AUTHENTIK_VERSION=2026.8.5
FROM ghcr.io/goauthentik/server:${AUTHENTIK_VERSION}

USER root
COPY patch_webauthn_origins.py /tmp/patch_webauthn_origins.py
RUN python /tmp/patch_webauthn_origins.py && rm /tmp/patch_webauthn_origins.py
USER 1000
