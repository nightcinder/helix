"""Patch Authentik's WebAuthn validation to accept extra origins (Project Helix).

Replaces the single expected_origin in authenticator_validate/challenge.py with a list:
the normal https origin plus HELIX_WEBAUTHN_EXTRA_ORIGINS (comma separated).
py_webauthn's verify_authentication_response accepts str | list[str].
Exits non-zero if the target line is not found exactly once, so the image build fails
instead of silently shipping an unpatched Authentik.
"""

import sys
from pathlib import Path

TARGET = Path("/authentik/stages/authenticator_validate/challenge.py")
OLD = "expected_origin=get_origin(request),"
NEW = (
    "expected_origin=[get_origin(request)] + [o.strip() for o in "
    "__import__('os').environ.get('HELIX_WEBAUTHN_EXTRA_ORIGINS', '').split(',') "
    "if o.strip()],"
)

src = TARGET.read_text()
count = src.count(OLD)
if count != 1:
    sys.exit(f"helix patch: expected 1 match of {OLD!r} in {TARGET}, found {count}")
TARGET.write_text(src.replace(OLD, NEW))
print(f"helix patch: applied to {TARGET}")
