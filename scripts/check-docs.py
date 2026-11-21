"""Check Markdown links and requirement IDs in the tracking and guide files.

* Relative links must point at files that exist. Links into the generated
  developer reference (docs/reference/<module>/...) are exempt.
* Every requirement ID mentioned outside the specification must be defined in
  SMARTCLINIC_V1_SPEC.md. Ranges such as MSG-01-07 and IAM-01/02 are expanded.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = "SMARTCLINIC_V1_SPEC.md"
LINK = re.compile(r"(?<!!)\[[^\]]*\]\(([^)\s]+)\)")
GENERATED = ("staff-web/", "patient-mobile/")
# "plan" is a pre-existing scratch copy of this pack and is not checked.
SKIP = {".git", "build", "node_modules", ".opencode", "plan"}


def spec_ids(text):
    """Requirement IDs defined as table rows or bold paragraph leads."""
    body = text.split("## Appendix A")[0]
    return set(re.findall(r"(?m)^\| ([A-Z]{2,4}-\d{2}) \|", body)) | \
        set(re.findall(r"\*\*([A-Z]{2,4}-\d{2}) ·", body))


def mentioned_ids(text, prefixes):
    """Expand ID mentions such as MSG-01-07 or IAM-01/02 into single IDs."""
    found = set()
    pattern = re.compile(
        r"\b(" + "|".join(sorted(prefixes)) + r")-(\d{2})(?!\d)"
        r"(?:[–-](\d{2})(?!\d)|((?:/\d{2})+))?")
    for prefix, first, last, slashes in pattern.findall(text):
        numbers = [int(first)]
        if last:
            numbers = range(int(first), int(last) + 1)
        elif slashes:
            numbers += [int(n) for n in slashes.strip("/").split("/")]
        found |= {f"{prefix}-{n:02d}" for n in numbers}
    return found


# Worker notes and archives are records, not specs: agent-written text there must not turn main red.
SKIP_PREFIXES = (".agent/", "docs/archive/")


def markdown_files(root):
    return [p for p in root.rglob("*.md")
            if not SKIP & set(p.relative_to(root).parts)
            and not p.relative_to(root).as_posix().startswith(SKIP_PREFIXES)]


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
            if rel.parts[:2] == ("docs", "reference") and file_part.startswith(GENERATED):
                continue
            if Path(file_part).name == "TODO.md":
                continue  # a local view built from tasks/, not stored in git
            if file_part and not (path.parent / file_part).exists():
                errors.append(f"{rel}: broken link {target}")
        if rel.name == SPEC:
            continue
        for ident in sorted(mentioned_ids(text, prefixes) - defined):
            errors.append(f"{rel}: unknown requirement ID {ident}")
    return errors


def main():
    errors = check()
    for error in errors:
        print(f"check-docs: {error}", file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
