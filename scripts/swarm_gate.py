"""Independent, fail-closed swarm verification for protected-branch integration.

The Swarm gate answers a different question from Delivery: Delivery verifies
that code builds and passes tests; this module verifies the *swarm contract*.
It classifies each pull request, validates task metadata and protected-file
policy against the protected base revision, checks that evidence is bound to
the exact revision GitHub will merge, and validates an integration manifest
against real commit ancestry.

Trust model: GitHub Actions runs this module from a checkout of the protected
base revision, never from the PR head. The immutable GitHub event payload
supplies the revision; worker-authored notes are supporting metadata only and
can never mark a mandatory check passed. No token is read or logged here and
no worker code is executed. ``evaluate`` is pure over plain data.
"""

import argparse
import datetime
import fnmatch
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping

PASSED, FAILED, NOT_APPLICABLE, ERROR = "passed", "failed", "not_applicable", "error"
VALID_STATES = frozenset({PASSED, FAILED, NOT_APPLICABLE, ERROR})
WORKER, INTEGRATION, RECONCILIATION, HUMAN, UNKNOWN = "worker", "integration", "reconcile", "human", "unknown"

# Mirrored from scripts/swarm.py. Kept local so a worker PR cannot edit the
# policy copy used to judge it; a test asserts the two sets stay in sync.
GATE_CONFIG = ("apps/api/spotbugs-exclude.xml", "scripts/wiring-baseline.json",
               ".opencode/setup/CODING_STYLE.md", "apps/staff-web/vite.config.ts",
               "apps/staff-web/eslint.config.ts", "apps/patient-mobile/jest.config.ts",
               "apps/patient-mobile/eslint.config.mjs")
PROTECTED = (".github/*", ".opencode/*", ".opencode/opencode.json", "scripts/*",
             ".opencode/setup/AGENTS.md", "check.sh", "*/check.sh", "compose.yaml", "Dockerfile",
             "apps/api/pom.xml", *GATE_CONFIG)
TRACKERS = ("tasks/*", ".opencode/config/MEMORY.md", ".opencode/config/WORKSPACE.md",
            ".opencode/config/BUGS.md", ".opencode/config/PROJECT.md", "docs/swarm/*")

TASK_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9-]*$")
SHA = re.compile(r"^[0-9a-f]{40}$")
SWARM_BRANCH = re.compile(r"^swarm/([A-Za-z0-9-]+)-\d+$")
INTEGRATION_BRANCH = re.compile(r"^swarm/integration/[A-Za-z0-9._-]+$")
RECONCILE_BRANCH = re.compile(r"^swarm/reconcile/[A-Za-z0-9._-]+$")
TASK_TITLE = re.compile(r"^([A-Za-z0-9][A-Za-z0-9-]*):\s+\S")
TASK_MARK = re.compile(r"^- \[([ x-])\] (\S+)\s+·", re.M)
EVIDENCE_SECTIONS = ("## Test evidence", "## Coverage", "## Review verdicts")
CHECK_COMMANDS = ("scripts/quick-check.sh", "scripts/check.sh", "mvn ",
                  "python3 -I -m unittest", "apps/patient-mobile/check.sh", "apps/staff-web/check.sh")
MANIFEST_PATH = ".agent/integration-manifest.json"


@dataclass(frozen=True)
class CheckResult:
    """One mandatory check, its terminal state and the reason for it."""

    name: str
    state: str
    reason: str

    def __post_init__(self):
        if self.state not in VALID_STATES:
            raise ValueError(f"invalid check state {self.state!r}")


@dataclass(frozen=True)
class GateResult:
    """Structured verdict for one revision, bound to the exact SHA tested."""

    pr_number: int
    task_id: str
    kind: str
    head_sha: str
    tested_sha: str
    base_sha: str
    verified_at: str
    checks: tuple
    failures: tuple

    @property
    def passed(self):
        """True only when no check failed or errored."""
        return not self.failures

    @property
    def overall(self):
        """The overall state, ``passed`` or ``failed``."""
        return PASSED if self.passed else FAILED

    def check(self, name):
        """Return one check by name, or None when it was not evaluated."""
        return next((c for c in self.checks if c.name == name), None)

    def as_dict(self):
        """Return a JSON-serialisable summary including per-check states."""
        return {"pr_number": self.pr_number, "task_id": self.task_id, "kind": self.kind,
                "head_sha": self.head_sha, "tested_sha": self.tested_sha,
                "base_sha": self.base_sha, "verified_at": self.verified_at,
                "checks": [c.__dict__ for c in self.checks],
                "failures": list(self.failures), "overall": self.overall}


@dataclass(frozen=True)
class GateContext:
    """Inputs gathered by the trusted workflow from GitHub and the base tree."""

    pr_number: int = 0
    event_name: str = "pull_request"
    head_ref: str = ""
    head_sha: str = ""
    base_sha: str = ""
    tested_sha: str = ""
    labels: tuple = ()
    title: str = ""
    commit_authors: tuple = ()
    author_association: str = ""
    files: tuple = ()
    task_text: str = ""
    evidence_text: str = ""
    required_checks: tuple = ()
    check_runs: Mapping = None
    manifest: Mapping = None
    ancestors: Mapping = None
    verified_at: str = ""


def _check(name, ok, reason, when=True, na_reason="not applicable to this PR kind"):
    """Build a passed/failed check, or a not_applicable one when disabled."""
    if not when:
        return CheckResult(name, NOT_APPLICABLE, na_reason)
    return CheckResult(name, PASSED if ok else FAILED, reason)


def _matches(path, patterns):
    """True when one fnmatch pattern covers ``path``."""
    return any(fnmatch.fnmatch(path, pattern) for pattern in patterns)


def classify_pull_request(head_ref="", labels=(), title="", commit_authors=(),
                          author_association=""):
    """Return ``(kind, task_id)`` for one pull request.

    Several independent signals identify automation, so removing one label or
    renaming a branch cannot turn a worker PR into an ordinary human PR. A PR
    with an automation signal it cannot parse is ``unknown`` and fails closed.

    ``author_association`` is GitHub's trusted association of the PR author
    with the repository. A maintainer (OWNER/MEMBER/COLLABORATOR) may use any
    branch name for a manual PR; automation can never claim that association
    by editing a label or branch name.
    """
    authors = tuple(a.lower() for a in commit_authors)
    swarm_author = any("swarm" in a or "github-actions" in a for a in authors)
    trusted_human = str(author_association).upper() in ("OWNER", "MEMBER", "COLLABORATOR")
    label_automation = bool({"swarm", "autofix"} & set(labels))
    automation = label_automation or swarm_author
    title_match = TASK_TITLE.match(title or "")
    if INTEGRATION_BRANCH.fullmatch(head_ref or ""):
        return INTEGRATION, ""
    if RECONCILE_BRANCH.fullmatch(head_ref or ""):
        return RECONCILIATION, ""
    worker = SWARM_BRANCH.fullmatch(head_ref or "")
    if worker:
        return (WORKER, worker.group(1)) if TASK_ID.fullmatch(worker.group(1)) else (UNKNOWN, "")
    if (head_ref or "").startswith("swarm/"):
        return (UNKNOWN, "") if (automation or not trusted_human) else (HUMAN, "")
    if title_match and TASK_ID.fullmatch(title_match.group(1)):
        return WORKER, title_match.group(1)
    if automation and not trusted_human:
        return UNKNOWN, ""
    return HUMAN, ""


def _field(task_text, name):
    """Return one simple ``key: value`` field of the first task entry."""
    match = re.search(rf"^  - {re.escape(name)}:[ \t]*(.*)$", task_text, re.M)
    return match.group(1).strip() if match else ""


def validate_task_metadata(task_text, task_id, pr_number):
    """Check task existence, identifier, eligibility, evidence and PR reference."""
    exists = bool(task_text)
    mark = TASK_MARK.search(task_text) if exists else None
    declared = mark.group(2) if mark else ""
    eligible = bool(mark and mark.group(1) in (" ", "-") and declared == task_id)
    evidence = _field(task_text, "evidence") if exists else ""
    declared_pr = _field(task_text, "pr").lstrip("#")
    pr_ok = not declared_pr or (declared_pr.isdigit() and int(declared_pr) == pr_number)
    return [
        _check("task_exists", exists, "task file exists on the protected base revision"),
        _check("task_identifier", bool(task_id and TASK_ID.fullmatch(task_id)),
               "task identifier is valid"),
        _check("task_eligible", eligible, "task is open or taken by this worker"),
        _check("task_evidence", bool(evidence) and evidence.lower() not in ("none", "pending"),
               "task record carries evidence"),
        _check("task_pr_reference", pr_ok, "task record names this PR or no PR"),
    ]


def validate_protected_files(files, kind):
    """Reject worker changes to gates, workflows, coverage config and trackers."""
    paths = tuple(files)
    applicable = kind != HUMAN
    gate_files = [p for p in paths if _matches(p, GATE_CONFIG) or p == "apps/api/pom.xml"]
    workflows = [p for p in paths if p.startswith(".github/workflows/")]
    tracked = [p for p in paths if _matches(p, TRACKERS)]
    other = [p for p in paths if (_matches(p, PROTECTED) or p in tracked)
             and p not in gate_files and p not in workflows]
    if kind == RECONCILIATION:
        other = [p for p in other if not _matches(p, TRACKERS)]
    return [
        _check("protected_files", not other, "no unauthorized workflow, agent or script change",
               when=applicable),
        _check("coverage_config", not gate_files, "no coverage or test-gate configuration change",
               when=applicable),
        _check("workflow_config", not workflows, "no workflow modification in an automated PR",
               when=applicable),
    ]


def validate_verification_evidence(evidence_text, kind, head_sha):
    """Check evidence schema, a canonical command and exact revision binding."""
    text = evidence_text or ""
    return [
        _check("evidence_schema", all(h in text for h in EVIDENCE_SECTIONS),
               "notes use the documented required headings", when=kind == WORKER),
        _check("mandatory_commands", any(c in text for c in CHECK_COMMANDS),
               "notes name a canonical verification command", when=kind == WORKER),
        _check("revision_binding", bool(head_sha and head_sha in text),
               "notes bind to the exact tested head revision", when=kind == WORKER),
    ]


def validate_check_results(runs, required):
    """Treat missing, failed, cancelled or unknown mandatory results as failure."""
    runs = runs or {}
    required = tuple(required)
    if not required:
        return [_check("check_results", True, "no checks configured", when=False,
                       na_reason="no required check names configured")]
    bad = [name for name in required if str(runs.get(name, "")).lower() not in ("success", "neutral")]
    return [_check("check_results", not bad,
                   "required checks are success: " + (", ".join(bad) or "all present"))]


def validate_integration_manifest(manifest, ancestors, base_sha, kind):
    """Verify a batch manifest against real commit ancestry, not its own claims."""
    data = manifest if isinstance(manifest, Mapping) else {}
    prs = data.get("worker_prs") if isinstance(data.get("worker_prs"), (list, tuple)) else []
    valid_prs = bool(prs) and all(isinstance(p, Mapping) and isinstance(p.get("number"), int)
                                  and isinstance(p.get("head_sha"), str) and SHA.fullmatch(p["head_sha"])
                                  and isinstance(p.get("task_id"), str) and p["task_id"] for p in prs)
    base_ok = (isinstance(data.get("base_sha"), str) and SHA.fullmatch(data["base_sha"])
               and (not base_sha or data["base_sha"] == base_sha))
    verification = data.get("verification") if isinstance(data.get("verification"), Mapping) else {}
    ancestry = ancestors or {}
    missing = [p["head_sha"][:12] for p in prs if isinstance(p, Mapping)
               and isinstance(p.get("head_sha"), str) and ancestry.get(p["head_sha"]) is not True]
    applicable = kind == INTEGRATION or manifest is not None
    return ([
        _check("integration_manifest", bool(data.get("batch_id")) and valid_prs and base_ok
               and verification.get("passed") is True,
               "manifest names a batch, base, verified worker heads and a passed verification"),
        _check("manifest_ancestry", not missing,
               "worker revisions not integrated: " + ", ".join(missing) if missing
               else "every worker revision is an ancestor of the tested commit"),
    ] if applicable else [
        _check("integration_manifest", True, "no manifest", when=False),
        _check("manifest_ancestry", True, "no manifest", when=False),
    ])


def evaluate(ctx):
    """Return the full :class:`GateResult` for one trusted context."""
    kind, task_id = classify_pull_request(ctx.head_ref, ctx.labels, ctx.title,
                                         ctx.commit_authors, ctx.author_association)
    checks = [_check("pr_classification", kind != UNKNOWN, f"classified as {kind}")]
    if kind == WORKER:
        checks.extend(validate_task_metadata(ctx.task_text, task_id, ctx.pr_number))
    else:
        checks.extend(_check(n, True, "not a worker PR", when=False) for n in
                      ("task_exists", "task_identifier", "task_eligible", "task_evidence",
                       "task_pr_reference"))
    checks.extend(validate_protected_files(ctx.files, kind))
    checks.extend(validate_verification_evidence(ctx.evidence_text, kind, ctx.head_sha))
    checks.extend(validate_check_results(ctx.check_runs, ctx.required_checks))
    checks.extend(validate_integration_manifest(ctx.manifest, ctx.ancestors, ctx.base_sha, kind))
    checks.append(_check("tested_revision",
                         bool(ctx.head_sha and ctx.tested_sha and ctx.head_sha == ctx.tested_sha),
                         "the verified revision is the current GitHub head"))
    failures = tuple(f"{c.name}: {c.reason}" for c in checks if c.state in (FAILED, ERROR))
    timestamp = ctx.verified_at or datetime.datetime.now(datetime.timezone.utc).isoformat()
    return GateResult(ctx.pr_number, task_id, kind, ctx.head_sha, ctx.tested_sha,
                      ctx.base_sha, timestamp, tuple(checks), failures)


def _gh_json(*args):
    """Run ``gh`` with the workflow's read-only token and parse its JSON output."""
    return json.loads(subprocess.check_output(["gh", *args], text=True,
                                              stderr=subprocess.DEVNULL) or "{}")


def collect_context(event, event_name, root, required_checks=(), head_root=None):
    """Gather a :class:`GateContext` from the immutable event and read-only APIs.

    ``root`` is the trusted base checkout (verifier, tasks, evidence);
    ``head_root`` is a separate checkout of the tested revision (manifest).
    """
    tested_root = head_root or root
    if event_name == "merge_group":
        group = event.get("merge_group") or {}
        ctx = GateContext(event_name=event_name, head_ref="swarm/integration/merge-group",
                          head_sha=group.get("head_sha", ""), base_sha=group.get("base_sha", ""),
                          tested_sha=group.get("head_sha", ""),
                          required_checks=tuple(required_checks))
        return _collect_manifest(ctx, tested_root)
    pr = event.get("pull_request") or {}
    number = pr.get("number", 0)
    ctx = GateContext(pr_number=number, event_name=event_name,
                      head_ref=pr.get("head", {}).get("ref", ""),
                      head_sha=pr.get("head", {}).get("sha", ""),
                      base_sha=pr.get("base", {}).get("sha", ""),
                      tested_sha=pr.get("head", {}).get("sha", ""),
                      labels=tuple(x.get("name", "") for x in pr.get("labels", [])),
                      title=pr.get("title", ""),
                      author_association=pr.get("author_association", ""),
                      required_checks=tuple(required_checks))
    if not number:
        return ctx
    details = _gh_json("pr", "view", str(number), "--json", "headRefOid,title,labels,commits")
    files = subprocess.check_output(["gh", "pr", "diff", str(number), "--name-only"],
                                    text=True).split()
    ctx = GateContext(**{**ctx.__dict__, "title": details.get("title") or ctx.title,
                         "labels": tuple(x.get("name", "") for x in details.get("labels", [])),
                         "commit_authors": tuple((x.get("author") or {}).get("login", "")
                                                 for x in details.get("commits", [])),
                         "files": tuple(files)})
    ctx = _collect_task_and_evidence(ctx, root)
    return _collect_manifest(ctx, tested_root)


def _collect_task_and_evidence(ctx, root):
    """Read the task and notes from the trusted base checkout."""
    _, task_id = classify_pull_request(ctx.head_ref, ctx.labels, ctx.title, ctx.commit_authors)
    if not task_id:
        return ctx
    task_path = Path(root) / "tasks" / f"{task_id}.md"
    notes = sorted((Path(root) / "docs" / "pr" / "notes").glob(f"{task_id}-*.md"))
    return GateContext(**{**ctx.__dict__,
                          "task_text": task_path.read_text() if task_path.is_file() else "",
                          "evidence_text": notes[-1].read_text() if notes else ""})


def _collect_manifest(ctx, root):
    """Read the integration manifest and verify ancestry in the tested tree."""
    path = Path(root) / MANIFEST_PATH
    if not path.is_file():
        return ctx
    manifest = json.loads(path.read_text())
    ancestors = {}
    for item in manifest.get("worker_prs", []):
        sha = item.get("head_sha", "")
        if not SHA.fullmatch(sha):
            continue
        done = subprocess.run(["git", "merge-base", "--is-ancestor", sha, ctx.tested_sha],
                              cwd=root, capture_output=True, text=True)
        ancestors[sha] = done.returncode == 0
    return GateContext(**{**ctx.__dict__, "manifest": manifest, "ancestors": ancestors})


def main(argv=None):
    """Evaluate one event; print a readable summary; exit 0 only when it passes."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", nargs="?", default="evaluate", choices=("evaluate",))
    parser.add_argument("--event-path", required=True)
    parser.add_argument("--event-name", required=True)
    parser.add_argument("--root", default=".")
    # The verifier runs from the trusted base checkout, but the manifest and
    # ancestry describe the revision under test and live in a separate checkout.
    # Reading them from --root would judge a stale revision.
    parser.add_argument("--head-root", default=None)
    parser.add_argument("--required-checks", default=os.environ.get("SWARM_GATE_REQUIRED_CHECKS", ""))
    args = parser.parse_args(argv)
    try:
        event = json.loads(Path(args.event_path).read_text())
        required = tuple(n.strip() for n in args.required_checks.split(",") if n.strip())
        ctx = collect_context(event, args.event_name, args.root, required, args.head_root)
        result = evaluate(ctx)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Swarm gate: verification error: {error}", file=sys.stderr)
        return 2
    print(f"Swarm gate: {result.overall} ({result.kind}, PR #{result.pr_number or '-'})")
    for check in result.checks:
        print(f"  {check.name}: {check.state} — {check.reason}")
    for failure in result.failures:
        print(f"Swarm gate failure: {failure}", file=sys.stderr)
    return 0 if result.passed else 1


if __name__ == "__main__":
    sys.exit(main())
