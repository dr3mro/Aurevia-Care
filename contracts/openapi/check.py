#!/usr/bin/env python3
"""Offline drift gate for the X-04 generated command contract and client (PRD-06).

Recomputes the X-01 canonical SHA-256 of each generated artifact, compares it
with the committed manifest and checks that every catalog command appears in
both artifacts with no client-side authority field. No Java toolchain, database
or network access is required. Synthetic artifacts only; no patient data.
"""
import hashlib
import json
import re
import struct
import sys
from pathlib import Path

GENERATED = Path(__file__).resolve().parent / "generated"
DIGEST_PARTS = ("x-04-generator", "x-04-manifest-v1")
FORBIDDEN = ("claimedRole", "claimedPermissions", "permission:", "role:", "scope:", "@ts-ignore", ": any")


def canonical_checksum(artifact: str) -> str:
    """SHA-256 over four big-endian length-prefixed UTF-8 parts."""
    parts = (artifact, *DIGEST_PARTS, "")
    stream = b"".join(
        struct.pack(">I", len(part.encode("utf-8"))) + part.encode("utf-8") for part in parts
    )
    return hashlib.sha256(stream).hexdigest()


def main() -> int:
    """Verify the committed artifacts against their manifest; return a process status."""
    errors = []
    try:
        manifest = json.loads((GENERATED / "manifest.json").read_text(encoding="utf-8"))
        contract = (GENERATED / "openapi.yaml").read_text(encoding="utf-8")
        client = (GENERATED / "client.ts").read_text(encoding="utf-8")
    except (OSError, ValueError) as exc:
        print(f"FAIL x-04: cannot read generated artifacts: {exc}")
        return 1

    for field, text in (("contractSha256", contract), ("clientSha256", client)):
        expected = canonical_checksum(text)
        if manifest.get(field) != expected:
            errors.append(f"{field} drift: manifest={manifest.get(field)} actual={expected}")

    commands = manifest.get("commands", [])
    identifiers = [item.get("id", "") for item in commands]
    if not identifiers or len(identifiers) != len(set(identifiers)):
        errors.append("manifest commands must be non-empty and unique")
    for command in commands:
        identifier = command.get("id", "")
        action = command.get("action", "")
        if f"operationId: {identifier}" not in contract:
            errors.append(f"contract missing operationId {identifier}")
        if f"x-smartclinic-action: {action}" not in contract:
            errors.append(f"contract missing action {action} for {identifier}")
        if f"export function {identifier}(" not in client:
            errors.append(f"client missing function {identifier}")
        for parameter in re.findall(r"\{([A-Za-z][A-Za-z0-9]*)\}", command.get("path", "")):
            if f"encodeURIComponent({parameter})" not in client or f"  {parameter}: string," not in client:
                errors.append(f"client does not encode path parameter {parameter} for {identifier}")
    for forbidden in FORBIDDEN:
        if forbidden in client:
            errors.append(f"client exposes forbidden authority token {forbidden}")

    if errors:
        for error in errors:
            print(f"FAIL x-04: {error}")
        return 1
    print(f"x-04 drift check OK: {len(identifiers)} commands, contract and client match manifest")
    return 0


if __name__ == "__main__":
    sys.exit(main())
