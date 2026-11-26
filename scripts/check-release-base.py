"""Decide whether a release has a green Delivery run for the exact commit.

The release job may publish only when the exact tagged SHA has a successful
Delivery run on the default branch from ``push`` or ``workflow_dispatch``.
Superseded branch pushes are cancelled at run level, so the jobs of an older run
can all be green while the run itself reports ``cancelled``; the documented
recovery is an exact-head ``workflow_dispatch``. This helper reads the runs list
from stdin and checks branch, SHA, repository identity, event and conclusion
locally, failing closed. It makes no network call and handles no credential or
patient data.

Exit codes: 0 when at least one run qualifies, 1 when none qualifies, and 2 when
the payload is not a runs object. Every failure writes an actionable line to
stderr.

Usage:
    gh api ... --jq '.' | python3 -I scripts/check-release-base.py \
        --sha "$GITHUB_SHA" --branch "$DEFAULT_BRANCH" --repo "$GITHUB_REPOSITORY"
"""
import argparse
import json
import sys

TRUSTED_EVENTS = frozenset({"push", "workflow_dispatch"})


def qualifying(run, sha, branch, repo):
    """Return whether one listed run is trusted evidence for the exact commit.

    Pull requests and merge groups are candidates, never publication evidence.
    A cancelled or incomplete run fails closed even when its jobs were green.
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
    if run.get("conclusion") != "success":
        return False
    source = run.get("head_repository")
    return isinstance(source, dict) and source.get("full_name") == repo


def main(argv=None, stream=None):
    """Read runs JSON from stdin and return 0, 1 or 2 as described above."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sha", required=True, help="exact commit SHA being tagged")
    parser.add_argument("--branch", required=True, help="default branch name")
    parser.add_argument("--repo", required=True, help="repository that owns the runs")
    args = parser.parse_args(argv)
    try:
        payload = json.load(sys.stdin if stream is None else stream)
    except ValueError as error:
        print(f"release base: invalid runs JSON: {error}", file=sys.stderr)
        return 2
    if not isinstance(payload, dict) or not isinstance(payload.get("workflow_runs"), list):
        print("release base: invalid runs JSON: expected a runs object", file=sys.stderr)
        return 2
    runs = payload["workflow_runs"]
    green = sum(1 for run in runs if qualifying(run, args.sha, args.branch, args.repo))
    if green:
        print(f"release base: {green} green run(s) for {args.sha} on {args.branch}")
        return 0
    events = sorted({str(r.get("event")) for r in runs if isinstance(r, dict)})
    conclusions = sorted({str(r.get("conclusion")) for r in runs if isinstance(r, dict)})
    print(f"release base: no green {args.branch} run for {args.sha} "
          f"(events: {', '.join(events) or 'none'}; "
          f"conclusions: {', '.join(conclusions) or 'none'})", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
