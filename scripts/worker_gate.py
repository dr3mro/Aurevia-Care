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
import glob
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPORTS = "apps/api/target/surefire-reports"
STRICT_PREFIXES = ("FIX-", "DLV-")

# Files the orchestrator and the integrator own. A swarm worker must never change
# them, so they are checked by name wherever they appear in the diff.
TRACKER_FILES = ("TODO.md", "MEMORY.md", "WORKSPACE.md", "BUGS.md", "PROJECT.md")
REQUIRED_SECTIONS = ("Test evidence", "Coverage")
UNFINISHED_SECTION = "Why this is not finished"
SCOPE_LINE = re.compile(r"^Scope \(stay inside these paths\):\s*(.*)$", re.M)
SECTION = re.compile(r"^##\s+(.+?)\s*$", re.M)
PLACEHOLDER = re.compile(r"<[^\s<>]+>")
NOTES_PATH = re.compile(r"\.agent/pr-notes/[A-Za-z0-9._-]+\.md")


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


def parse_scope(task_text):
    """Scope prefixes from the `Scope (stay inside these paths):` line of a task."""
    match = SCOPE_LINE.search(task_text or "")
    if not match:
        return []
    return [part.strip() for part in match.group(1).split(",") if part.strip()]


def is_notes_file(path):
    """True for a worker's own notes file under `.agent/pr-notes/`."""
    return bool(NOTES_PATH.fullmatch(path or ""))


def under_scope(path, scope):
    """True when `path` is a scope entry itself or lives below a scope directory."""
    for entry in scope:
        base = entry.rstrip("/")
        if path == base or path.startswith(base + "/"):
            return True
    return False


def section_body(notes, heading):
    """Text under a `## heading` up to the next heading; None when there is none.

    Heading names are matched case-insensitively so workers may capitalise them
    differently. An existing heading with no text yields the empty string.
    """
    lines = (notes or "").splitlines()
    start = None
    for index, line in enumerate(lines):
        match = SECTION.match(line)
        if match and match.group(1).strip().lower() == heading.lower():
            start = index + 1
            break
    if start is None:
        return None
    body = []
    for line in lines[start:]:
        if SECTION.match(line):
            break
        body.append(line)
    return "\n".join(body).strip()


def has_placeholder(text):
    """True when a section still contains placeholder text such as `<task-id>`."""
    return bool(PLACEHOLDER.search(text or ""))


def validate_candidate(changed, scope, notes, notes_path, finished,
                       candidate_sha=None, head_sha=None):
    """Return violations of the pinned candidate as `[{"file": ..., "rule": ...}, ...]`.

    The rules are `scope` (a changed file is outside the declared scope), `tracker`
    (a worker changed an orchestrator-owned tracker), `notes-heading` (a required
    hand-off section is absent), `notes-empty`, `notes-placeholder`, `notes-unfinished`
    (a worker that stopped early left no reason) and `candidate-changed` (HEAD moved
    after the candidate was validated). An empty list means the hand-off is sound.
    """
    violations = []
    # An empty SHA means Git could not be read; never call that a pinned candidate.
    if candidate_sha is not None and (not candidate_sha or not head_sha or candidate_sha != head_sha):
        violations.append({"file": "", "rule": "candidate-changed"})
    for path in changed:
        if Path(path).name in TRACKER_FILES:
            violations.append({"file": path, "rule": "tracker"})
            continue
        if is_notes_file(path) or under_scope(path, scope):
            continue
        violations.append({"file": path, "rule": "scope"})
    headings = list(REQUIRED_SECTIONS)
    if not finished:
        headings.append(UNFINISHED_SECTION)
    for heading in headings:
        body = section_body(notes, heading)
        missing_rule = "notes-unfinished" if heading == UNFINISHED_SECTION else "notes-heading"
        if body is None:
            violations.append({"file": notes_path, "rule": missing_rule})
        elif not body:
            violations.append({"file": notes_path, "rule": "notes-empty"})
        elif has_placeholder(body):
            violations.append({"file": notes_path, "rule": "notes-placeholder"})
    return violations


def head_commit():
    """Current HEAD as a SHA, or an empty string when Git is unavailable."""
    out = subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True)
    return out.stdout.strip() if out.returncode == 0 else ""


def find_notes_file(story_id, root="."):
    """Newest committed notes file for a story, as a repository-relative path."""
    if not story_id:
        return ""
    pattern = str(Path(root) / ".agent" / "pr-notes" / (glob.escape(story_id) + "-*.md"))
    matches = sorted(glob.glob(pattern))
    return str(Path(matches[-1]).relative_to(root)) if matches else ""


def read_notes(notes_path, root="."):
    """Notes-file text, or None when the file is absent."""
    if not notes_path:
        return None
    path = Path(root) / notes_path
    return path.read_text() if path.is_file() else None


def candidate_from_env(environ=None, root=".", changed=None):
    """Validate the worker's hand-off from its environment, or None when not pinned.

    A candidate is pinned only when the task declares a scope. HEAD is sampled
    before and after validation so a candidate that changes while it is being
    checked is rejected rather than silently reported as clean.
    """
    environ = environ if environ is not None else os.environ
    scope = parse_scope(environ.get("STORY_BODY", ""))
    if not scope:
        return None
    candidate_sha = head_commit()
    story_id = environ.get("STORY_ID", "")
    notes_path = environ.get("NOTES_FILE", "").strip() or find_notes_file(story_id, root)
    notes = read_notes(notes_path, root)
    finished = environ.get("WORKER_FINISHED", "1").strip() != "0"
    paths = changed if changed is not None else changed_paths()
    return validate_candidate(paths, scope, notes, notes_path, finished,
                              candidate_sha, head_commit())


def main(argv=None):
    mvn_failed = (argv or sys.argv[1:] or ["0"])[0] != "0"
    reports = Path(REPORTS)
    failing = failing_classes(reports) if reports.is_dir() else set()
    if mvn_failed and not failing:
        print("integration run failed without a failing test class (compile or setup error)")
        return 1
    changed = changed_paths()
    ok, message = decide(failing, changed, os.environ.get("STORY_ID", ""),
                         os.environ.get("STORY_BODY", ""))
    print(message)
    violations = candidate_from_env(changed=changed)
    if violations is not None:
        print(json.dumps({"violations": violations}, sort_keys=True))
    if violations:
        return 1
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
