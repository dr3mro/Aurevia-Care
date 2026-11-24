"""Check Markdown links, requirement IDs and generated-document structure.

* Relative links must point at files that exist. Links into the generated
  developer reference (docs/reference/<module>/...) are exempt.
* Every requirement ID mentioned outside the specification must be defined in
  .opencode/config/PROJECT_V1_SPEC.md. Ranges such as MSG-01-07 and IAM-01/02 are expanded.
* Generated Markdown is also checked for structural faults that make a committed
  document unreadable: unbalanced code fences, heading jumps, ragged tables, raw
  control characters, unmasked tokens and unclosed HTML comments or markers.
  Report the file and line so the generator or the worker can fix it.
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = ".opencode/config/PROJECT_V1_SPEC.md"
LINK = re.compile(r"(?<!!)\[[^\]]*\]\(([^)\s]+)\)")
GENERATED = ("staff-web/", "patient-mobile/")
# "plan" is a pre-existing scratch copy of this pack and is not checked.
SKIP = {".git", "build", "node_modules", ".opencode", "plan"}

# Structural rules for generated Markdown. The token pattern carries a
# word-boundary guard so ordinary words such as "risk-transitions" are not
# mistaken for a leaked sk- key.
FENCE = re.compile(r"^\s*(`{3,}|~{3,})")
HEADING = re.compile(r"^(#{1,6})\s")
TABLE_ROW = re.compile(r"^\s*\|.*\|\s*$")
TABLE_SEP = re.compile(r"^\s*\|[\s:|-]+\|\s*$")
TABLE_PIPE = re.compile(r"(?<!\\)\|")
CONTROL = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f]")
TOKEN = re.compile(
    r"(?<![A-Za-z0-9])(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|"
    r"sk-[A-Za-z0-9_-]{20,})"
)
# docs/swarm holds immutable merge records built from clipped worker logs (one
# ends inside a code fence). Structural rules never rewrite that history; links
# and requirement IDs are still checked there exactly as before.
RECORD_PREFIXES = ("docs/swarm/",)


def spec_ids(text):
    """Requirement IDs defined as table rows or bold paragraph leads."""
    body = text.split("## Appendix A")[0]
    return set(re.findall(r"(?m)^\| ([A-Z]{2,4}-\d{2}) \|", body)) | set(
        re.findall(r"\*\*([A-Z]{2,4}-\d{2}) ·", body)
    )


def mentioned_ids(text, prefixes):
    """Expand ID mentions such as MSG-01-07 or IAM-01/02 into single IDs."""
    found = set()
    pattern = re.compile(
        r"\b(" + "|".join(sorted(prefixes)) + r")-(\d{2})(?!\d)"
        r"(?:[–-](\d{2})(?!\d)|((?:/\d{2})+))?"
    )
    for prefix, first, last, slashes in pattern.findall(text):
        numbers = [int(first)]
        if last:
            numbers = range(int(first), int(last) + 1)
        elif slashes:
            numbers += [int(n) for n in slashes.strip("/").split("/")]
        found |= {f"{prefix}-{n:02d}" for n in numbers}
    return found


# Worker notes and reconciled reports are records, not specs: agent-written text
# there must not turn main red. They live under .agents/; docs/pr/ remains
# skipped for older checkouts that still carry notes at the former location.
SKIP_PREFIXES = (".agent/", ".agents/", "docs/archive/", "docs/pr/")


def markdown_files(root):
    # The specification moved under .opencode/config; it must still be
    # enumerated so its links, IDs and structure are checked, even though the
    # rest of .opencode is skipped (instruction and tracker files).
    return [
        p
        for p in root.rglob("*.md")
        if (
            p.relative_to(root).as_posix() == SPEC
            or (
                not SKIP & set(p.relative_to(root).parts)
                and not p.relative_to(root).as_posix().startswith(SKIP_PREFIXES)
            )
        )
    ]


def generated_markdown_files(root):
    """Markdown files subject to the structural rules.

    Immutable merge records are excluded so an old clipped log cannot fail a
    commit; links and requirement IDs are still checked in them by ``check``.
    """
    return [
        p
        for p in markdown_files(root)
        if not p.relative_to(root).as_posix().startswith(RECORD_PREFIXES)
    ]


def _cells(line):
    """Unescaped pipe delimiters on a table row; every row shares this count.

    Pipes escaped as ``\\|`` inside a cell are ignored so a literal pipe does not
    make an otherwise matching row look ragged.
    """
    return len(TABLE_PIPE.findall(line))


def markdown_errors(text, rel):
    """Structural faults in one Markdown document as ``rel:line: message`` strings.

    Every rule reports the first offending line so a generator or a worker can
    fix the exact place. Code fences are tracked once: their contents are
    skipped by the prose rules because a shell comment or a fenced example is
    not a document heading or table.
    """
    errors = []
    lines = text.splitlines()
    count = len(lines)

    # A fence marker toggles the in-code state; its own line counts as code.
    code = set()
    fences = []
    inside = False
    for number, line in enumerate(lines, 1):
        if FENCE.match(line):
            code.add(number)
            fences.append(number)
            inside = not inside
        elif inside:
            code.add(number)
    if len(fences) % 2:
        errors.append(f"{rel}:{fences[-1]}: unbalanced code fence")

    # A heading may stay level or go down one, never jump down several levels.
    previous = 0
    for number, line in enumerate(lines, 1):
        if number in code:
            continue
        match = HEADING.match(line)
        if match:
            level = len(match.group(1))
            if previous and level > previous + 1:
                errors.append(
                    f"{rel}:{number}: heading jumps from {previous} to {level}"
                )
            previous = level

    # In a GFM table the header, the separator and every data row have the same
    # number of unescaped pipes.
    for index in range(count - 1):
        if (index + 1) in code or not TABLE_ROW.match(lines[index]):
            continue
        if not TABLE_SEP.match(lines[index + 1]):
            continue
        expected = _cells(lines[index])
        row = index + 2
        while row < count and (row + 1) not in code and TABLE_ROW.match(lines[row]):
            actual = _cells(lines[row])
            if actual != expected:
                errors.append(
                    f"{rel}:{row + 1}: table row has {actual} cells, expected {expected}"
                )
            row += 1

    for number, line in enumerate(lines, 1):
        if CONTROL.search(line):
            errors.append(f"{rel}:{number}: raw control character")
        if TOKEN.search(line):
            errors.append(f"{rel}:{number}: unmasked token pattern")

    comment_line = 0
    for number, line in enumerate(lines, 1):
        if "<!--" in line and not comment_line:
            comment_line = number
        if "-->" in line:
            comment_line = 0
    if comment_line:
        errors.append(f"{rel}:{comment_line}: unclosed HTML comment or marker")
    return errors


def check(root=ROOT):
    errors = []
    defined = spec_ids((root / SPEC).read_text(encoding="utf-8"))
    prefixes = {i.split("-")[0] for i in defined}
    for path in markdown_files(root):
        rel = path.relative_to(root)
        text = path.read_text(encoding="utf-8")
        for target in LINK.findall(text):
            if re.match(r"[a-z]+:|#", target):
                continue
            file_part = target.split("#")[0]
            if rel.parts[:2] == ("docs", "reference") and file_part.startswith(
                GENERATED
            ):
                continue
            if file_part and not (path.parent / file_part).exists():
                errors.append(f"{rel}: broken link {target}")
        if rel.name == SPEC:
            continue
        for ident in sorted(mentioned_ids(text, prefixes) - defined):
            errors.append(f"{rel}: unknown requirement ID {ident}")
    for path in generated_markdown_files(root):
        rel = path.relative_to(root).as_posix()
        text = path.read_text(encoding="utf-8")
        errors.extend(markdown_errors(text, rel))
    return errors


def main():
    errors = check()
    for error in errors:
        print(f"check-docs: {error}", file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
