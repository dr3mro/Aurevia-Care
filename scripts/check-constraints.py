"""Fail when a change adds a quality-gate suppression or weakens a quality gate.

AGENTS.md forbids lowering a coverage threshold, adding an exclusion, skipping a
test or suppressing a lint, type or static-analysis finding. Until now that rule
was only an instruction in the worker prompt. This check enforces it on the lines
a change adds, so a worker cannot make a gate green by switching the gate off.

It looks at added lines only: existing suppressions stay (wiring-baseline.json
tracks the Java debt) and cannot fail a change that does not touch them. The base
is the merge base with COVERAGE_BASE (default origin/main), so it covers commits
and uncommitted work. When the base cannot be resolved the check warns and passes,
unless --require-base is given (the integrator gate does).

Usage: check-constraints.py [--base REF] [--require-base]
"""
import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

# ``python3 -I scripts/<tool>.py`` omits the repository root from sys.path.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from process_env import child_environment

ROOT = Path(__file__).resolve().parents[1]

# Files that name the patterns without using them: this guard and its tests, prose,
# lock files, generated clients and the task and notes trackers.
EXEMPT = tuple(re.compile(pattern) for pattern in (
    r"^scripts/check-constraints\.py$", r"^scripts/tests/", r"\.md$", r"^\.agent/",
    r"^docs/", r"(^|/)package-lock\.json$", r"/generated/", r"^tasks/",
))

JS = r"\.(?:ts|tsx|js|jsx|mts|cts|mjs|cjs)$"
JAVA = r"\.java$"
PYTHON = r"\.py$"
SHELL = r"\.sh$"
POM = r"(^|/)pom\.xml$"
SPOTBUGS = r"spotbugs-exclude\.xml$"
JS_CONFIG = (r"(?:^|/)(?:vitest|vite|jest|eslint)\.config\.[a-z]+$"
             r"|(?:^|/)tsconfig[^/]*\.json$|(?:^|/)package\.json$")
# A coverage threshold is only ever allowed to be 100.
THRESHOLD = r"[\"']?(?:lines|branches|functions|statements)[\"']?\s*:\s*(?!100\b)\d"


def _rule(paths, label, pattern):
    return re.compile(paths), label, re.compile(pattern)


RULES = (
    _rule(JS, "@ts-ignore, @ts-expect-error or @ts-nocheck", r"@ts-(?:ignore|expect-error|nocheck)\b"),
    _rule(JS, "eslint-disable", r"eslint-disable"),
    _rule(JS, "coverage ignore comment", r"\b(?:istanbul|c8|v8)\s+ignore\b"),
    _rule(JS, "skipped or focused test",
          r"(?:^|[^\w.])(?:(?:it|test|describe|suite)\.(?:skip|only)|xit|xdescribe|fdescribe)\s*\("),
    _rule(JS, "explicit any", r":\s*any\b|\bas\s+any\b|<any>"),
    _rule(JAVA, "@SuppressWarnings or @SuppressFBWarnings", r"@Suppress(?:FB)?Warnings\b"),
    _rule(JAVA, "disabled test", r"@(?:Disabled|Ignore)\b"),
    _rule(JAVA, "NOSONAR", r"NOSONAR"),
    _rule(JAVA, "@Generated or a JaCoCo exclusion (hides code from coverage)",
          r"@(?:Generated|ExcludeFromJacoco\w*)\b"),
    _rule(PYTHON, "noqa", r"#\s*noqa\b"),
    _rule(PYTHON, "type: ignore", r"#\s*type:\s*ignore\b"),
    _rule(PYTHON, "pragma: no cover", r"#\s*pragma:\s*no\s+cover\b"),
    _rule(PYTHON, "skipped test", r"@(?:pytest\.mark\.skip\w*|unittest\.skip\w*)"),
    _rule(SHELL, "shellcheck disable", r"#\s*shellcheck\s+disable"),
    _rule(POM, "skipped build step", r"<(?:skip|skipTests|skipITs)>\s*true\s*</"),
    _rule(POM, "failure turned into a warning", r"<(?:haltOnFailure|failOnViolation)>\s*false\s*</"),
    _rule(POM, "coverage threshold below 100%", r"<minimum>\s*(?!1(?:\.0+)?\s*</minimum>)[^<]*</minimum>"),
    _rule(POM, "plugin exclusion", r"<exclude>"),
    _rule(SPOTBUGS, "SpotBugs exclusion", r"<Match\b"),
    _rule(JS_CONFIG, "strict mode turned off", r"[\"']?strict[\"']?\s*:\s*false\b"),
    _rule(JS_CONFIG, "lint rule turned off", r":\s*[\"']off[\"']"),
    _rule(JS_CONFIG, "coverage or lint exclusion",
          r"\b(?:coveragePathIgnorePatterns|coverageExclude|exclude|ignores)\s*:"),
    _rule(JS_CONFIG, "coverage threshold below 100%", THRESHOLD),
)

HUNK = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@")


def git(root, *args):
    """Run git in ``root`` and return its stdout; an empty string on failure."""
    done = subprocess.run(
        ["git", *args], cwd=root, capture_output=True, text=True, check=False,
        env=child_environment(read_only=True),
    )
    return done.stdout if done.returncode == 0 else ""


def resolve_base(root, ref):
    """The merge base of HEAD and ``ref``, or None when ``ref`` is unknown here."""
    base = git(root, "merge-base", "HEAD", ref).strip()
    return base or None


def diff_added_lines(diff):
    """Yield (path, line number, text) for every line a unified -U0 diff adds."""
    path, number = None, 0
    for line in diff.splitlines():
        if line.startswith("+++ "):
            path = line[6:] if line.startswith("+++ b/") else None
        elif line.startswith("@@"):
            number = int(HUNK.match(line).group(1))
        elif line.startswith("+") and path:
            yield path, number, line[1:]
            number += 1


def added_lines(root, base):
    """Every line added since ``base``: committed, staged, unstaged and untracked."""
    yield from diff_added_lines(git(root, "diff", "-U0", "--no-color", base))
    for name in git(root, "ls-files", "--others", "--exclude-standard").splitlines():
        try:
            text = (Path(root) / name).read_text()
        except (OSError, UnicodeDecodeError):
            continue
        for number, line in enumerate(text.splitlines(), 1):
            yield name, number, line


def violations(lines):
    """The (path, line number, label, text) of every added line that breaks a rule."""
    found = []
    for path, number, text in lines:
        if any(pattern.search(path) for pattern in EXEMPT):
            continue
        for paths, label, pattern in RULES:
            if paths.search(path) and pattern.search(text):
                found.append((path, number, label, text.strip()))
    return found


def written_bar_violations(root, base):
    """Flag added CONSTRAINTS.md lines that lower a written threshold.

    The table in CONSTRAINTS.md is the human-readable contract for the numbers the
    gates enforce. A change that edits a row to a looser value must be caught even
    though Markdown is otherwise exempt from the source-suppression rules.
    """
    path = "CONSTRAINTS.md"
    found = []
    for name, number, text in diff_added_lines(git(root, "diff", "-U0", "--no-color", base, "--", path)):
        if name != path:
            continue
        # A written 100% / 0 warnings / strict:true turned into anything looser.
        if re.search(r"\b(?:[1-9]?\d|\d\.\d+)\s*%", text) and not re.search(r"\b100\s*%", text):
            if re.search(r"\b(?:coverage|bar|threshold)\b", text, re.I):
                found.append((name, number, "written coverage bar lowered", text.strip()))
        if re.search(r"\b(?:warning|warnings)\b", text, re.I) and not re.search(r"\b0\b", text):
            found.append((name, number, "written warning bar lowered", text.strip()))
        if re.search(r"strict:\s*false", text):
            found.append((name, number, "written strictness bar lowered", text.strip()))
    return found


def main(argv=None, root=ROOT):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default=os.environ.get("COVERAGE_BASE", "origin/main"))
    parser.add_argument("--require-base", action="store_true", dest="require_base")
    args = parser.parse_args(argv)
    base = resolve_base(root, args.base)
    if base is None:
        message = f"constraints: cannot resolve {args.base}; the added lines were not checked"
        print(message, file=sys.stderr)
        return 1 if args.require_base else 0
    found = violations(added_lines(root, base)) + written_bar_violations(root, base)
    for path, number, label, text in found:
        print(f"{path}:{number}: {label}: {text}", file=sys.stderr)
    if found:
        print("constraints: this change adds a suppression or weakens a gate (AGENTS.md, Coverage "
              "rules). Remove it and fix the cause: test the branch, delete the dead code, or "
              "fix the type.", file=sys.stderr)
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
