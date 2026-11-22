"""Fail when a worker's notes do not carry a clean verdict from every required reviewer.

The orchestrator runs the read-only reviewers before it calls a task done and copies
each reviewer's last line into its notes file under "## Review verdicts", one line per
reviewer:

    ## Review verdicts
    - smartclinic-code-reviewer: GO
    - smartclinic-coverage-auditor: NO-GO
    Required: the false side of the update in StoreX has no test

A reviewer that is missing, answers NO-GO, or leaves a Critical or Required finding
that is not marked resolved keeps the task from finishing. A change that only touches
documentation, trackers or notes has nothing to review and passes. A malformed or
absent verdict never passes: the gate fails closed.

Usage: check-verdicts.py NOTES_FILE [--base REF] [--require NAME[,NAME...]]
"""
import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# The reviewers every code change needs: correctness, 100% coverage, and security.
REQUIRED = ("smartclinic-code-reviewer", "smartclinic-coverage-auditor", "smartclinic-devsecops")

SECTION = re.compile(r"(?mi)^##\s+Review verdicts\s*$")
NEXT_SECTION = re.compile(r"(?m)^##\s")
VERDICT = re.compile(r"^\s*[-*]?\s*`?(smartclinic-[a-z0-9-]+)`?\s*[:—-]\s*(GO|NO-GO)\b", re.I)
FINDING = re.compile(r"^\s*[-*]?\s*(Critical|Required)\s*[:—-]\s*(.+)$", re.I)
# Files with nothing to review: prose, the trackers and the agents' own notes.
DOCS_ONLY = re.compile(r"(\.md$|^docs/|^\.agent/|^tasks/)")


def review_section(text):
    """The body of the "## Review verdicts" section, or None when the notes have none."""
    start = SECTION.search(text)
    if start is None:
        return None
    rest = text[start.end():]
    following = NEXT_SECTION.search(rest)
    return rest[:following.start()] if following else rest


def verdicts_and_findings(section):
    """Return ({reviewer: GO or NO-GO}, [unresolved Critical or Required finding lines])."""
    verdicts, findings = {}, []
    for line in section.splitlines():
        verdict = VERDICT.match(line)
        if verdict:
            verdicts[verdict.group(1).lower()] = verdict.group(2).upper()
            continue
        finding = FINDING.match(line)
        if finding and "resolved" not in finding.group(2).lower():
            findings.append(line.strip())
    return verdicts, findings


def changed_files(root, base):
    """Paths changed since ``base`` (committed or not); None when git cannot say."""
    done = subprocess.run(["git", "diff", "--name-only", base], cwd=root, capture_output=True,
                          text=True, check=False)
    if done.returncode != 0:
        return None
    others = subprocess.run(["git", "ls-files", "--others", "--exclude-standard"], cwd=root,
                            capture_output=True, text=True, check=False)
    return [name for name in (done.stdout + others.stdout).splitlines() if name]


def problems(text, required):
    """Every reason the notes text does not carry a clean set of verdicts."""
    section = review_section(text)
    if section is None:
        return ['the notes have no "## Review verdicts" section']
    verdicts, findings = verdicts_and_findings(section)
    found = []
    for reviewer in required:
        answer = verdicts.get(reviewer)
        if answer is None:
            found.append(f"no verdict from {reviewer}: run it and copy its last line")
        elif answer != "GO":
            found.append(f"{reviewer} answered NO-GO: fix what it found and run it again")
    found.extend(f"unresolved finding: {line}" for line in findings)
    return found


def main(argv=None, root=ROOT):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("notes")
    parser.add_argument("--base")
    parser.add_argument("--require", default=",".join(REQUIRED))
    args = parser.parse_args(argv)
    if args.base:
        files = changed_files(root, args.base)
        if files is not None and all(DOCS_ONLY.search(name) for name in files):
            print("verdicts: only documentation changed; nothing to review")
            return 0
    try:
        text = Path(args.notes).read_text()
    except OSError:
        print(f"verdicts: cannot read the notes file {args.notes}", file=sys.stderr)
        return 1
    required = [name.strip().lower() for name in args.require.split(",") if name.strip()]
    found = problems(text, required)
    for problem in found:
        print(f"verdicts: {problem}", file=sys.stderr)
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
