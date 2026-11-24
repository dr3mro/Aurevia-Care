"""Fail when a changed line of a tooling script is not covered by the tests.

`scripts/python-coverage.sh` runs the tooling tests under coverage with branch
measurement and writes a JSON report. This module compares that report with the
lines a change adds to `scripts/*.py`: every added line must run, and every
branch that starts on an added line must be taken both ways. With `--full` the
whole of `scripts/` must be at 100%. Old uncovered code does not fail a change
that does not touch it, so the gate can be on while the gaps are being closed.
"""
import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

# ``python3 -I scripts/<tool>.py`` omits the repository root from sys.path.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from process_env import child_environment

HUNK = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")
SCRIPT = re.compile(r"^scripts/[^/]+\.py$")


def added_lines(diff_text):
    """Map each changed `scripts/*.py` file to the set of line numbers the diff adds."""
    result, current = {}, None
    for line in diff_text.splitlines():
        if line.startswith("+++ "):
            name = line[6:] if line.startswith("+++ b/") else ""
            current = name if SCRIPT.match(name) else None
        elif current:
            match = HUNK.match(line)
            if match:
                start, count = int(match.group(1)), int(match.group(2) or "1")
                result.setdefault(current, set()).update(range(start, start + count))
    return result


def uncovered(report, added):
    """The added lines that did not run, and the added lines with an untaken branch."""
    problems = []
    for name, lines in sorted(added.items()):
        data = report.get("files", {}).get(name)
        if data is None:
            problems.append((name, 0, "file not measured"))
            continue
        for line in sorted(lines & set(data.get("missing_lines", []))):
            problems.append((name, line, "line not run"))
        for source, _target in data.get("missing_branches", []):
            if source in lines:
                problems.append((name, source, "branch not taken"))
    return problems


def full_gaps(report):
    """Every uncovered line or branch in the whole report."""
    problems = []
    for name, data in sorted(report.get("files", {}).items()):
        problems += [(name, line, "line not run") for line in data.get("missing_lines", [])]
        problems += [(name, source, "branch not taken") for source, _ in data.get("missing_branches", [])]
    return problems


def diff_against(base):
    """Unified diff of the scripts against `base`, or None when the base cannot be resolved."""
    probe = subprocess.run(["git", "rev-parse", "--verify", "-q", base], capture_output=True,
                           text=True, env=child_environment(read_only=True))
    if probe.returncode != 0:
        return None
    merge_base = subprocess.run(["git", "merge-base", base, "HEAD"], capture_output=True,
                                text=True, env=child_environment(read_only=True))
    point = merge_base.stdout.strip() or base
    work = subprocess.run(["git", "diff", "-U0", point, "--", "scripts"], capture_output=True,
                          text=True, env=child_environment(read_only=True))
    return work.stdout


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", help="coverage JSON report")
    parser.add_argument("--base", default="origin/main")
    parser.add_argument("--full", action="store_true", help="require 100%% for every script")
    args = parser.parse_args(argv)
    with open(args.report, encoding="utf-8") as handle:
        report = json.load(handle)
    if args.full:
        problems = full_gaps(report)
    else:
        diff = diff_against(args.base)
        if diff is None:
            print(f"python coverage: base {args.base} not found; the changed-line check was skipped",
                  file=sys.stderr)
            return 0
        problems = uncovered(report, added_lines(diff))
    for name, line, why in problems[:60]:
        print(f"python coverage: {name}:{line}: {why}", file=sys.stderr)
    if problems:
        print(f"python coverage: {len(problems)} uncovered; add tests for them in scripts/tests",
              file=sys.stderr)
        return 1
    print("python coverage: every " + ("script line" if args.full else "changed script line") + " is covered")
    return 0


if __name__ == "__main__":
    sys.exit(main())
