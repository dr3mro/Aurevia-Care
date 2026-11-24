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
# Authority declarations are matched anywhere in an identifier and case-insensitively:
# `userRole`, `grantedScopes` and `Role :` are as forbidden as `role:`.
AUTHORITY_FIELD = re.compile(r"(?i)(?:actor|role|permission|scope)s?\s*:")
AUTHORITY_HEADER = re.compile(r"(?i)['\"]X-(?:actor|role|permission|scope)s?['\"]")
FUNCTION = re.compile(r"^export function ([A-Za-z_$][A-Za-z0-9_$]*)\(", re.MULTILINE)
REQUIRED_ARGUMENTS = (
    "  idempotencyKey: string,",
    "  ifMatch: string,",
    "  body: unknown,",
    "  transport: CommandTransport,",
)
FORBIDDEN = ("claimedrole", "claimedpermissions", "@ts-ignore", ": any")


def canonical_checksum(artifact: str) -> str:
    """SHA-256 over four big-endian length-prefixed UTF-8 parts."""
    parts = (artifact, *DIGEST_PARTS, "")
    stream = b"".join(
        struct.pack(">I", len(part.encode("utf-8"))) + part.encode("utf-8") for part in parts
    )
    return hashlib.sha256(stream).hexdigest()


def function_blocks(client: str) -> dict[str, str]:
    """Split a generated client into one source block per exported command function."""
    matches = list(FUNCTION.finditer(client))
    blocks: dict[str, str] = {}
    for index, match in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(client)
        blocks[match.group(1)] = client[match.start():end]
    return blocks


def verify(directory: Path = GENERATED) -> tuple[list[str], int]:
    """Return ``(errors, command_count)`` for the generated artifacts in a directory."""
    errors: list[str] = []
    try:
        manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
        contract = (directory / "openapi.yaml").read_text(encoding="utf-8")
        client = (directory / "client.ts").read_text(encoding="utf-8")
    except (OSError, ValueError) as exc:
        return [f"cannot read generated artifacts: {exc}"], 0

    for field, text in (("contractSha256", contract), ("clientSha256", client)):
        expected = canonical_checksum(text)
        if manifest.get(field) != expected:
            errors.append(f"{field} drift: manifest={manifest.get(field)} actual={expected}")

    commands = manifest.get("commands", [])
    identifiers = [item.get("id", "") for item in commands]
    if not identifiers or len(identifiers) != len(set(identifiers)):
        errors.append("manifest commands must be non-empty and unique")
    blocks = function_blocks(client)
    for command in commands:
        identifier = command.get("id", "")
        action = command.get("action", "")
        if f"operationId: {identifier}" not in contract:
            errors.append(f"contract missing operationId {identifier}")
        if f"x-orevia-action: {action}" not in contract:
            errors.append(f"contract missing action {action} for {identifier}")
        block = blocks.get(identifier)
        if block is None:
            errors.append(f"client missing function {identifier}")
            continue
        for required in REQUIRED_ARGUMENTS:
            if required not in block:
                errors.append(
                    f"client function {identifier} is missing required precondition argument "
                    f"{required.strip()}"
                )
        for parameter in re.findall(r"\{([A-Za-z][A-Za-z0-9]*)\}", command.get("path", "")):
            if f"encodeURIComponent({parameter})" not in block or f"  {parameter}: string," not in block:
                errors.append(f"client function {identifier} does not encode path parameter {parameter}")
    field = AUTHORITY_FIELD.search(client)
    if field is not None:
        errors.append(f"client exposes forbidden authority field token {field.group(0)!r}")
    header = AUTHORITY_HEADER.search(client)
    if header is not None:
        errors.append(f"client exposes forbidden authority header token {header.group(0)!r}")
    lowered = client.lower()
    for forbidden in FORBIDDEN:
        if forbidden in lowered:
            errors.append(f"client exposes forbidden authority token {forbidden}")

    return errors, len(identifiers)


def check(directory: Path = GENERATED) -> int:
    """Print the result of verifying one artifact directory and return a process status."""
    errors, count = verify(directory)
    if errors:
        for error in errors:
            print(f"FAIL x-04: {error}")
        return 1
    print(f"x-04 drift check OK: {count} commands, contract and client match manifest")
    return 0


def main() -> int:
    """Verify the committed artifacts against their manifest; return a process status."""
    return check()


if __name__ == "__main__":
    sys.exit(main())
