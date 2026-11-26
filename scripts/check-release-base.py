"""Decide whether a release has a green Delivery gate for the exact commit.

The release job may publish only when the exact tagged SHA has a Delivery run on
the default branch from ``push`` or ``workflow_dispatch`` whose ``Delivery gate``
job succeeded. Superseded branch pushes are cancelled at run level while their
jobs stay green, and GitHub cannot dispatch a workflow on a bare commit SHA, so
the run-level conclusion alone is not valid evidence. This helper reads an
envelope from stdin with the workflow-runs response and each run's jobs, then
checks branch, SHA, repository identity, event, status and the gate job locally.

With ``--state`` the helper instead prints ``pending`` while a trusted run for
the exact commit is still running, otherwise ``ready``. The release workflow
polls this state before checking the gate so an unfinished Delivery run is not
mistaken for a red main.

Exit codes: 0 when a trusted run has a green gate (or a state was printed), 1
when none does, and 2 when the envelope is malformed. Every failure writes an
actionable line to stderr. No network call, token or patient data is handled
here.

Usage:
    python3 -I scripts/check-release-base.py \
        --sha "$GITHUB_SHA" --branch "$DEFAULT_BRANCH" --repo "$GITHUB_REPOSITORY"
    python3 -I scripts/check-release-base.py --state \
        --sha "$GITHUB_SHA" --branch "$DEFAULT_BRANCH" --repo "$GITHUB_REPOSITORY"
"""
import argparse
import json
import sys

TRUSTED_EVENTS = frozenset({"push", "workflow_dispatch"})
RUN_CONCLUSIONS = frozenset({"success", "cancelled"})
GATE_JOB = "Delivery gate"


def qualifying(run, sha, branch, repo):
    """Return whether one run is trusted evidence from the exact commit.

    A cancelled run is still a candidate: only its gate job can prove whether
    the verification completed. Failure, queued and skipped runs never are.
    """
    if not isinstance(run, dict):
        return False
    if run.get("head_sha") != sha:
        return False
    if run.get("head_branch") != branch:
        return False
    if run.get("event") not in TRUSTED_EVENTS:
        return False
    if run.get("status") != "completed":
        return False
    if run.get("conclusion") not in RUN_CONCLUSIONS:
        return False
    source = run.get("head_repository")
    return isinstance(source, dict) and source.get("full_name") == repo


def pending(runs, sha, branch, repo):
    """Return whether a trusted Delivery run for the exact commit is unfinished.

    Only runs that could later provide gate evidence count: same commit, default
    branch, a trusted event and this repository. A completed run is never
    pending, even when its gate failed -- the caller can then decide red immediately.
    """
    for run in runs:
        if not isinstance(run, dict):
            continue
        if run.get("head_sha") != sha or run.get("head_branch") != branch:
            continue
        if run.get("event") not in TRUSTED_EVENTS:
            continue
        if run.get("status") == "completed":
            continue
        source = run.get("head_repository")
        if isinstance(source, dict) and source.get("full_name") == repo:
            return True
    return False


def green_gate(run_id, jobs):
    """Return whether one run's jobs contain a successful required check."""
    entries = jobs.get(str(run_id), [])
    if not isinstance(entries, list):
        return False
    return any(isinstance(job, dict) and job.get("name") == GATE_JOB
               and job.get("conclusion") == "success" for job in entries)


def main(argv=None, stream=None):
    """Read the envelope from stdin and return 0, 1 or 2 as described above."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sha", required=True, help="exact commit SHA being tagged")
    parser.add_argument("--branch", required=True, help="default branch name")
    parser.add_argument("--repo", required=True, help="repository that owns the runs")
    parser.add_argument("--state", action="store_true",
                        help="print pending or ready instead of checking the gate")
    args = parser.parse_args(argv)
    try:
        payload = json.load(sys.stdin if stream is None else stream)
    except ValueError as error:
        print(f"release base: invalid runs JSON: {error}", file=sys.stderr)
        return 2
    if (not isinstance(payload, dict)
            or not isinstance(payload.get("runs"), dict)
            or not isinstance(payload.get("jobs"), dict)):
        print("release base: invalid runs JSON: expected runs and jobs objects", file=sys.stderr)
        return 2
    runs = payload["runs"].get("workflow_runs")
    if not isinstance(runs, list):
        print("release base: invalid runs JSON: expected a workflow_runs list", file=sys.stderr)
        return 2
    if args.state:
        print("pending" if pending(runs, args.sha, args.branch, args.repo) else "ready")
        return 0
    jobs = payload["jobs"]
    accepted = sum(1 for run in runs
                   if qualifying(run, args.sha, args.branch, args.repo)
                   and green_gate(run.get("id"), jobs))
    if accepted:
        print(f"release base: {accepted} green gate(s) for {args.sha} on {args.branch}")
        return 0
    events = sorted({str(r.get("event")) for r in runs if isinstance(r, dict)})
    conclusions = sorted({str(r.get("conclusion")) for r in runs if isinstance(r, dict)})
    print(f"release base: no green gate for {args.sha} on {args.branch} "
          f"(events: {', '.join(events) or 'none'}; "
          f"conclusions: {', '.join(conclusions) or 'none'})", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
