#!/usr/bin/env python3
"""Verify release APK packaging, native libraries, R8 DEX retention, and forbidden tokens."""

from __future__ import annotations

import argparse
import sys
import zipfile
from pathlib import Path

REQUIRED_LIBS = (
    "lib/arm64-v8a/libhelix_moonlight.so",
    "lib/x86_64/libhelix_moonlight.so",
)

REQUIRED_DEX_DESCRIPTORS = (
    b"Lcom/nightcinder/helix/stream/MoonlightNativeBridge;",
    b"Lcom/nightcinder/helix/core/model/StreamTelemetry;",
)

FORBIDDEN_BYTES = (
    b"viture",
    b"UrxrOrViture",
)


def check_apk(apk_path: Path) -> list[str]:
    errors: list[str] = []
    if not apk_path.is_file():
        return [f"APK not found: {apk_path}"]

    with zipfile.ZipFile(apk_path, "r") as zf:
        names = set(zf.namelist())
        for lib in REQUIRED_LIBS:
            if lib not in names:
                errors.append(f"Missing required native library: {lib}")

        dex_entries = [n for n in names if n.startswith("classes") and n.endswith(".dex")]
        if not dex_entries:
            errors.append("No classes*.dex entries found inside APK")
            return errors

        dex_blob = b"".join(zf.read(entry) for entry in dex_entries)
        for desc in REQUIRED_DEX_DESCRIPTORS:
            if desc not in dex_blob:
                errors.append(f"Missing R8-retained DEX descriptor: {desc.decode('utf-8')}")

        dex_lower = dex_blob.lower()
        for forbidden in FORBIDDEN_BYTES:
            if forbidden.lower() in dex_lower:
                errors.append(f"Forbidden token found in DEX: {forbidden.decode('utf-8')}")

        if "AndroidManifest.xml" in names:
            manifest_bytes = zf.read("AndroidManifest.xml").lower()
            for forbidden in FORBIDDEN_BYTES:
                if forbidden.lower() in manifest_bytes:
                    errors.append(f"Forbidden token found in AndroidManifest.xml: {forbidden.decode('utf-8')}")

    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, help="Path to built APK")
    args = parser.parse_args()

    errors = check_apk(args.apk)
    if errors:
        for err in errors:
            print(f"ERROR: {err}", file=sys.stderr)
        return 1

    size_mb = args.apk.stat().st_size / (1024 * 1024)
    print(f"OK: {args.apk} ({size_mb:.2f} MB) passed native lib, R8 DEX retention, and audit checks.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
