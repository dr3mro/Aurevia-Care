"""Decide whether the PostgreSQL integration run should fail a worker's local check.

Main can already carry red integration suites that have nothing to do with the
task at hand. Failing every worker for those would turn every pull request into a
draft, so an ordinary task only fails on test classes it is related to: a test
file it changed, or a test whose name matches a changed source file. Repair tasks
(FIX-, DLV-) are judged on the test classes their task names: every named class
must pass, and failures in other classes belong to other repair tasks, so they do
not fail this one (otherwise the first of several repair tasks could never pass
while the others are still open). A repair task that names no class stays strict.
The integrator's full gate still runs everything before anything merges.
"""
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPORTS = "apps/api/target/surefire-reports"
STRICT_PREFIXES = ("FIX-", "DLV-")


def failing_classes(reports_dir):
    """Simple names of test classes with a failure or error in surefire XML reports."""
    failing = set()
    for report in sorted(Path(reports_dir).glob("TEST-*.xml")):
        try:
            suite = ET.parse(report).getroot()
        except ET.ParseError:
            failing.add(report.stem.removeprefix("TEST-").rsplit(".", 1)[-1])
            continue
        if int(suite.get("failures", 0)) or int(suite.get("errors", 0)):
            failing.add(suite.get("name", report.stem).rsplit(".", 1)[-1])
    return failing


def _stem(name):
    base = Path(name).stem
    return re.sub(r"(Integration|Isolation|Flow)?(Test|IT)$", "", base)


def related(failing, changed_paths):
    """Failing classes that a changed file explains."""
    stems = {_stem(p) for p in changed_paths if p.endswith((".java", ".sql"))}
    stems.discard("")
    names = {Path(p).stem for p in changed_paths}
    hit = set()
    for cls in failing:
        core = _stem(cls)
        if cls in names or any(core and (core in s or s in core) for s in stems):
            hit.add(cls)
    return hit


def changed_paths(base="origin/main"):
    out = subprocess.run(["git", "diff", "--name-only", f"{base}...HEAD"],
                         capture_output=True, text=True)
    paths = out.stdout.split()
    dirty = subprocess.run(["git", "status", "--porcelain"], capture_output=True, text=True)
    paths += [line[3:] for line in dirty.stdout.splitlines() if len(line) > 3]
    return paths


def named_classes(text):
    """Test class names a task mentions in backticks, such as `ChartRecordIntegrationTest`."""
    return set(re.findall(r"`([A-Z][A-Za-z0-9]*(?:Test|IT))`", text or ""))


def decide(failing, changed, story_id, task_text=""):
    """(ok, message)."""
    if not failing:
        return True, "integration tests passed"
    if story_id.startswith(STRICT_PREFIXES):
        named = named_classes(task_text)
        if not named:
            return False, "repair task: failing classes " + ", ".join(sorted(failing))
        mine = failing & named
        if mine:
            return False, "repair task: still failing " + ", ".join(sorted(mine))
        return True, ("repair task: its classes pass; other classes still red (other repair tasks): "
                      + ", ".join(sorted(failing)))
    mine = related(failing, changed)
    if mine:
        return False, "failing classes related to this change: " + ", ".join(sorted(mine))
    return True, ("WARNING integration failures not related to this change (already red on main): "
                  + ", ".join(sorted(failing)))


def main(argv=None):
    mvn_failed = (argv or sys.argv[1:] or ["0"])[0] != "0"
    reports = Path(REPORTS)
    failing = failing_classes(reports) if reports.is_dir() else set()
    if mvn_failed and not failing:
        print("integration run failed without a failing test class (compile or setup error)")
        return 1
    ok, message = decide(failing, changed_paths(), os.environ.get("STORY_ID", ""),
                         os.environ.get("STORY_BODY", ""))
    print(message)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
