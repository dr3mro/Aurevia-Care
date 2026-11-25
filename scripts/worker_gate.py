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
Only the private report directory passed by worker-check.sh decides a run; an
older failing report left in the shared Surefire directory is never consulted.
A worker also may not introduce an undocumented controller route or grow the
frozen missing-route baseline: the route-parity check runs before every early
return and fails closed when the pinned-base evidence cannot be read.
"""
import glob
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# ``python3 -I scripts/<tool>.py`` omits the repository root from sys.path.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from process_env import child_environment

# Legacy default for direct/manual invocations. worker-check.sh always passes the
# private directory for the current run as the second argument instead.
REPORTS = "apps/api/target/surefire-reports"
STRICT_PREFIXES = ("FIX-", "DLV-")

# Files the orchestrator and the integrator own. A swarm worker must never change
# them, so they are checked by name wherever they appear in the diff.
TRACKER_FILES = (".opencode/config/MEMORY.md", ".opencode/config/WORKSPACE.md",
                 ".opencode/config/BUGS.md", ".opencode/config/PROJECT.md")
REQUIRED_SECTIONS = ("Test evidence", "Coverage")
UNFINISHED_SECTION = "Why this is not finished"
SCOPE_LINE = re.compile(r"^Scope \(stay inside these paths\):\s*(.*)$", re.M)
SECTION = re.compile(r"^##\s+(.+?)\s*$", re.M)
PLACEHOLDER = re.compile(r"<[^\s<>]+>")
NOTES_PATH = re.compile(r"docs/pr/notes/[A-Za-z0-9._-]+\.md")

# Route-parity gate. The baseline holds only pre-existing undocumented routes,
# frozen at the pinned base; a worker may remove entries after documenting them
# but never add one. Evidence comes from the pinned base ref and the private
# Surefire report directory, so an unreadable source must fail closed.
PARITY_TEST = "OpenApiRouteParityTest"
DEFAULT_BASELINE = "contracts/openapi/missing-routes.txt"
UNDOCUMENTED_HEADING = "NEW undocumented controller routes:"
GHOST_HEADING = "Contract routes with no controller:"
ROUTE_LINE = re.compile(r"^([A-Z]+)\s+(/\S*)$")
PATH_VARIABLE = re.compile(r"\{[^}]*\}")


def _normalized_route(method, path):
    """Return `METHOD /path` with path variables replaced by a stable `{}`."""
    return method.upper() + " " + PATH_VARIABLE.sub("{}", path)


def parse_route_lines(text):
    """Parse a frozen baseline file into normalized `METHOD /path` entries.

    Blank lines and `#` comments carry no route. A malformed route yields None so
    the caller fails closed rather than treating corrupt evidence as zero debt.
    """
    routes = set()
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        match = ROUTE_LINE.fullmatch(line)
        if match is None:
            return None
        routes.add(_normalized_route(match.group(1), match.group(2)))
    return routes


def base_ref_exists(base_ref):
    """True when the pinned base ref resolves to a Git object."""
    out = subprocess.run(["git", "rev-parse", "--verify", "-q", base_ref],
                         capture_output=True, text=True, env=child_environment(read_only=True))
    return out.returncode == 0


def pinned_base_routes(base_ref, baseline_path):
    """Routes tolerated at the pinned base, or None when the evidence is unusable.

    An unresolved base ref, a file that cannot be read, or a blank or malformed
    file fails closed. A baseline file absent at the base is a valid clean base:
    the strict parity suite was green when that base was cut, so there was no
    debt to freeze. The separate cat-file probe ensures an unrelated git failure
    cannot be mistaken for an absent file.
    """
    if not base_ref:
        return None
    try:
        if not base_ref_exists(base_ref):
            return None
        probe = subprocess.run(["git", "cat-file", "-e", f"{base_ref}:{baseline_path}"],
                               capture_output=True, text=True,
                               env=child_environment(read_only=True))
        if probe.returncode != 0:
            return set()
        out = subprocess.run(["git", "show", f"{base_ref}:{baseline_path}"],
                             capture_output=True, text=True,
                             env=child_environment(read_only=True))
    except OSError:
        return None
    if out.returncode != 0:
        return None
    if not out.stdout.strip():
        return None
    return parse_route_lines(out.stdout)


def candidate_baseline(path):
    """Routes in the candidate's working-tree baseline, or None when unusable.

    A missing file fails closed: deleting the baseline must not look like a clean
    empty set, and the Java parity suite also needs the file to exist.
    """
    try:
        text = Path(path).read_text()
    except OSError:
        return None
    return parse_route_lines(text)


def parse_parity_message(message):
    """Normalized undocumented routes in a JUnit failure message.

    Returns None when the undocumented-route section is absent: a ghost route, a
    missing success response or an unexpected assertion cannot prove that no new
    controller route was introduced, so the caller must fail closed.
    """
    routes = set()
    undocumented = False
    heading_seen = False
    for raw in message.splitlines():
        line = raw.strip()
        if line == UNDOCUMENTED_HEADING:
            undocumented = True
            heading_seen = True
            continue
        if line == GHOST_HEADING:
            undocumented = False
            continue
        if not undocumented:
            continue
        match = ROUTE_LINE.fullmatch(line)
        if match:
            routes.add(_normalized_route(match.group(1), match.group(2)))
    if not heading_seen:
        return None
    return routes


def reported_parity_routes(reports_dir):
    """New undocumented routes named by a failing parity suite, or None if unusable.

    A green parity suite contributes no routes; a missing parity report is
    unusable, because a suite that never ran cannot prove the candidate clean.
    A parseable suite that fails without a route section is unusable too, and
    unparseable XML fails closed so a truncated report cannot clear a candidate.
    """
    routes = set()
    seen = False
    for report in sorted(Path(reports_dir).glob("TEST-*.xml")):
        try:
            root = ET.parse(report).getroot()
        except ET.ParseError:
            return None
        if not root.get("name", "").endswith(PARITY_TEST):
            continue
        seen = True
        failures = [node for node in root.iter() if node.tag in ("failure", "error")]
        if not failures:
            continue
        messages = []
        for node in failures:
            attribute = node.get("message") or ""
            text = node.text or ""
            messages.append(attribute + "\n" + text)
        parsed = parse_parity_message("\n".join(messages))
        if parsed is None:
            return None
        routes |= parsed
    if not seen:
        return None
    return routes


def check_parity(parity):
    """(ok, message) for frozen-baseline growth and new undocumented routes.

    The baseline may shrink after a documented repair. It may not grow, and a
    route reported by the parity suite must already be tolerated at the base.
    Each set difference is over exact METHOD + normalized-path entries.
    """
    base = pinned_base_routes(parity["base_ref"], parity["baseline"])
    candidate = candidate_baseline(parity["baseline"])
    reported = reported_parity_routes(parity["reports"])
    if base is None or candidate is None or reported is None:
        return False, "route parity: pinned-base evidence is missing or unusable"
    grown = candidate - base
    new = reported - base
    if grown or new:
        details = []
        if grown:
            details.append("baseline growth: " + ", ".join(sorted(grown)))
        if new:
            details.append("new undocumented routes: " + ", ".join(sorted(new)))
        return False, "route parity: " + "; ".join(details)
    return True, "route parity: no new undocumented routes or baseline growth"


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


def usable_reports(reports_dir):
    """True when the directory holds at least one parseable surefire TEST-*.xml.

    A compile or setup failure can leave the fresh directory absent, empty or
    holding a truncated report. None of those are test results, so a nonzero
    build must fail as a compile/setup error instead of being triaged as an
    integration failure. Unparseable files are skipped rather than counted so
    that stale or half-written XML cannot stand in for a real failing class.
    """
    for report in sorted(Path(reports_dir).glob("TEST-*.xml")):
        try:
            ET.parse(report)
        except ET.ParseError:
            continue
        return True
    return False


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
                         capture_output=True, text=True, env=child_environment(read_only=True))
    paths = out.stdout.split()
    dirty = subprocess.run(["git", "status", "--porcelain"], capture_output=True, text=True,
                           env=child_environment(read_only=True))
    paths += [line[3:] for line in dirty.stdout.splitlines() if len(line) > 3]
    return paths


def named_classes(text):
    """Test class names a task mentions in backticks, such as `ChartRecordIntegrationTest`."""
    return set(re.findall(r"`([A-Z][A-Za-z0-9]*(?:Test|IT))`", text or ""))


def decide(failing, changed, story_id, task_text="", parity=None):
    """(ok, message) for a pinned worker candidate.

    When a parity context is supplied it is checked before any early return, so
    growing the baseline or adding a route is still blocked when the integration
    suites are green. Without it the original related/unrelated triage stands.
    """
    if parity is not None:
        ok, message = check_parity(parity)
        if not ok:
            return False, message
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
    """True for a worker's own notes file under `docs/pr/notes/`."""
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
        if path in TRACKER_FILES:
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
    out = subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True,
                         env=child_environment(read_only=True))
    return out.stdout.strip() if out.returncode == 0 else ""


def find_notes_file(story_id, root="."):
    """Newest committed notes file for a story, as a repository-relative path."""
    if not story_id:
        return ""
    pattern = str(Path(root) / "docs" / "pr" / "notes" / (glob.escape(story_id) + "-*.md"))
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
    args = argv or sys.argv[1:] or ["0"]
    mvn_failed = args[0] != "0"
    # The second argument is the private report directory created for this run.
    # Reading the shared directory would let an earlier run's failing XML decide.
    reports = Path(args[1]) if len(args) > 1 and args[1] else Path(REPORTS)
    usable = reports.is_dir() and usable_reports(reports)
    if mvn_failed and not usable:
        print("integration run failed without fresh, usable test results (compile or setup error)")
        return 1
    failing = failing_classes(reports) if usable else set()
    if mvn_failed and not failing:
        print("integration run failed without a failing test class (compile or setup error)")
        return 1
    # Arguments 3 and 4 carry the pinned base and the frozen baseline path. They
    # are independent of the report directory, which stays private to this run.
    base_ref = args[2] if len(args) > 2 and args[2] else ""
    baseline = args[3] if len(args) > 3 and args[3] else DEFAULT_BASELINE
    parity = ({"base_ref": base_ref, "baseline": baseline, "reports": reports}
              if base_ref else None)
    changed = changed_paths()
    ok, message = decide(failing, changed, os.environ.get("STORY_ID", ""),
                         os.environ.get("STORY_BODY", ""), parity)
    print(message)
    violations = candidate_from_env(changed=changed)
    if violations is not None:
        print(json.dumps({"violations": violations}, sort_keys=True))
    if violations:
        return 1
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
