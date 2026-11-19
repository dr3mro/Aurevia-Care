"""OPS-06 migration change-safety gate.

Applies expand/contract discipline to the versioned SQL in db/migrations. The
runtime policy gate (``db/check.sh``) applies and asserts migrations; this
offline checker adds what that gate cannot express: version sequencing, declared
change intent and a valid prior expand for every contract.

Destructive detection is deliberately narrow. It matches statement-start tokens
after comments, strings, identifiers and dollar-quoted bodies have been blanked,
so the TRUNCATE/DELETE tokens in existing append-only triggers, REVOKE grants and
literals do not fail the build. The header is a developer declaration: the gate
proves its structure and the expand reference, not that SQL matches the label.
Human review of every contract migration remains required.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VERSION_NAME = re.compile(r"^V(\d+)__[A-Za-z0-9_]+\.sql$")
EXPAND_REF = re.compile(r"^V(\d+)$")
DOLLAR_TAG = re.compile(r"\$[A-Za-z0-9_]*\$")
CHANGE_VALUES = {"expand", "contract"}
DESTRUCTIVE = (
    re.compile(r"DROP\s+(?:TABLE|VIEW|MATERIALIZED\s+VIEW|INDEX|TYPE|SEQUENCE|"
               r"SCHEMA|FUNCTION|PROCEDURE|POLICY|TRIGGER|DATABASE|ROLE|"
               r"COLUMN|CONSTRAINT)\b", re.I),
    re.compile(r"DELETE\s+FROM\b", re.I),
    re.compile(r"TRUNCATE\s+(?:TABLE\s+)?\S+", re.I),
    re.compile(r"ALTER\s+TABLE\b.*\bDROP\s+(?:COLUMN|CONSTRAINT)\b", re.I),
)


def parse_version(name):
    """Return the integer version of a Flyway filename, or None if malformed."""
    match = VERSION_NAME.match(name)
    return int(match.group(1)) if match else None


def header(text):
    """Parse leading ``-- Key: value`` comments, skipping blank lines.

    Contiguous comment lines form the header. The first other non-empty line
    ends it. An unparseable comment line yields an empty value, never an error.
    """
    result = {}
    for line in text.splitlines():
        if not line.lstrip().startswith("--"):
            if line.strip():
                break
            continue
        key, _, value = line.lstrip()[2:].strip().partition(":")
        result[key.strip()] = value.strip()
    return result


def strip_noise(sql):
    """Blank comments, strings, identifiers and dollar bodies, keeping newlines.

    A single left-to-right pass is required: removing line comments before
    strings would treat a '--' inside a literal as a comment, eat the closing
    quote and let a later destructive statement disappear in string matching.
    Each removed character becomes a space (newlines are preserved) so reported
    line numbers still match the source file.
    """
    out = []
    index = 0
    size = len(sql)
    while index < size:
        char = sql[index]
        if sql.startswith("--", index):
            while index < size and sql[index] != "\n":
                out.append(" ")
                index += 1
        elif sql.startswith("/*", index):
            out.extend("  ")
            index += 2
            depth = 1
            while index < size and depth:
                if sql.startswith("/*", index):
                    depth += 1
                    out.extend("  ")
                    index += 2
                elif sql.startswith("*/", index):
                    depth -= 1
                    out.extend("  ")
                    index += 2
                else:
                    out.append("\n" if sql[index] == "\n" else " ")
                    index += 1
        elif char == "'":
            out.append(" ")
            index += 1
            while index < size:
                if sql[index] == "'":
                    if index + 1 < size and sql[index + 1] == "'":
                        out.extend("  ")
                        index += 2
                        continue
                    out.append(" ")
                    index += 1
                    break
                if sql[index] == "\\" and index + 1 < size:
                    out.extend("  ")
                    index += 2
                    continue
                out.append("\n" if sql[index] == "\n" else " ")
                index += 1
        elif char == '"':
            out.append(" ")
            index += 1
            while index < size:
                if sql[index] == '"':
                    if index + 1 < size and sql[index + 1] == '"':
                        out.extend("  ")
                        index += 2
                        continue
                    out.append(" ")
                    index += 1
                    break
                out.append("\n" if sql[index] == "\n" else " ")
                index += 1
        elif char == "$":
            tag = DOLLAR_TAG.match(sql, index)
            if tag is None:
                out.append(char)
                index += 1
                continue
            marker = tag.group(0)
            out.extend(" " * len(marker))
            index = tag.end()
            end = sql.find(marker, index)
            if end < 0:
                end = size
            while index < end:
                out.append("\n" if sql[index] == "\n" else " ")
                index += 1
            if end < size:
                out.extend(" " * len(marker))
                index = end + len(marker)
        else:
            out.append(char)
            index += 1
    return "".join(out)


def destructive_statements(sql):
    """Return (line, statement) for each statement-start destructive token.

    Statements are split on semicolons after noise removal and whitespace is
    collapsed, so a token split across lines or sharing a line with another
    statement is still matched. Only statement starts are inspected; a token
    inside a trigger clause or GRANT/REVOKE list is not a destructive statement.
    """
    cleaned = strip_noise(sql)
    findings = []
    offset = 0
    for statement in cleaned.split(";"):
        text = re.sub(r"\s+", " ", statement).strip()
        line = cleaned.count("\n", 0, offset + 1) + 1
        for pattern in DESTRUCTIVE:
            if pattern.match(text):
                findings.append((line, text))
                break
        offset += len(statement) + 1
    return findings


def migration_files(root):
    """Return (path, version or None) for each SQL file in the migration dir."""
    directory = root / "db" / "migrations"
    if not directory.is_dir():
        return []
    return [(path, parse_version(path.name)) for path in sorted(directory.glob("*.sql"))]


def _validate_expand_ref(name, version, change_header, headers, errors):
    """Verify a contract's prior expand reference exists and is earlier."""
    reference = change_header.get("Requires-expand", "")
    if not reference:
        errors.append(f"{name}: contract migration requires 'Requires-expand:'")
        return
    match = EXPAND_REF.match(reference)
    if not match:
        errors.append(f"{name}: invalid 'Requires-expand' value {reference!r}")
        return
    target = int(match.group(1))
    if target not in headers:
        errors.append(f"{name}: 'Requires-expand: V{target}' references a missing migration")
        return
    if target >= version:
        errors.append(f"{name}: 'Requires-expand' must reference an earlier migration")
        return
    if headers[target].get("Change") != "expand":
        errors.append(f"{name}: 'Requires-expand: V{target}' must reference an expand migration")


def check(root=ROOT, require_baseline=False):
    """Return every OPS-06 migration-safety error found under root.

    ``require_baseline`` makes an empty or missing migration directory an error;
    the canonical gates pass it so deleting the migrations cannot silently
    disable both this gate and db/check.sh. It defaults off for fresh scaffolding.
    """
    errors = []
    files = migration_files(root)
    if require_baseline and not files:
        errors.append("db/migrations: no versioned migrations found (baseline required)")

    records = []
    versions = []
    for path, version in files:
        if version is None:
            errors.append(f"{path.name}: invalid migration filename (expected V<n>__name.sql)")
            continue
        text = path.read_text(encoding="utf-8")
        records.append((path, version, text, header(text)))
        versions.append(version)

    # Duplicate versions are unsafe: Flyway cannot tell two migrations apart.
    # Gaps are expected: each parallel worker owns a reserved version (V9..V57)
    # and merges independently, so a branch or main can legitimately skip
    # numbers. Flyway applies by version order, not by contiguous sequence.
    seen = set()
    for version in versions:
        if version in seen:
            errors.append(f"duplicate migration version V{version}")
        seen.add(version)

    # Keyed by path so duplicate versions still validate each file's own SQL.
    headers = {}
    for path, version, _, change_header in records:
        headers.setdefault(version, change_header)
    for path, version, text, change_header in records:
        name = path.name
        change = change_header.get("Change", "")
        forward_fix = change_header.get("Forward-fix", "")
        if change and change not in CHANGE_VALUES:
            errors.append(f"{name}: invalid 'Change' value {change!r}")
        if destructive_statements(text) and change != "contract":
            errors.append(f"{name}: destructive SQL requires a 'Change: contract' header")
        if forward_fix and not VERSION_NAME.match(forward_fix):
            errors.append(f"{name}: invalid 'Forward-fix' reference {forward_fix!r}")
        if change == "contract":
            if not forward_fix:
                errors.append(f"{name}: contract migration requires 'Forward-fix:'")
            _validate_expand_ref(name, version, change_header, headers, errors)
        elif forward_fix:
            errors.append(f"{name}: 'Forward-fix:' requires a 'Change: contract' header")
    return errors


def main(root=ROOT, argv=None):
    """Report findings on stderr and return a process exit code."""
    argv = sys.argv[1:] if argv is None else argv
    require_baseline = "--require-baseline" in argv
    errors = check(root, require_baseline=require_baseline)
    for error in errors:
        print(f"check-migrations: {error}", file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
