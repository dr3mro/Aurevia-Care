"""Plan the order and base of stacked pull requests for one integration wave.

The integration agent reads every worker's work trail, then plans one pull
request per worker. Each PR is based on the previous PR's branch so dependent
changes stay green, and the first is based on the protected branch. This module
is pure: it never pushes, opens a PR or runs a check.
"""

import json
from pathlib import Path

STAGE_ORDER = ("feature", "allocation", "brief", "implementation", "review",
               "conflict-resolution")


def trail_stages(path):
    """Return the distinct work-trail stages in their recorded order.

    Args: path is a JSONL work-trail file. Returns: a tuple of stage names.
    Errors: malformed lines are skipped so untrusted text cannot break planning.
    """
    stages = []
    source = Path(path)
    if not source.is_file():
        return tuple(stages)
    for line in source.read_text(encoding="utf-8").splitlines():
        try:
            record = json.loads(line)
        except ValueError:
            continue
        stage = record.get("step") if isinstance(record, dict) else None
        if isinstance(stage, str) and stage not in stages:
            stages.append(stage)
    return tuple(stages)


def stack_plan(branches, base, stages=()):
    """Return PR entries with the base each stacked branch must be based on.

    Args: branches are worker branch names in delivery order, base is the
    protected branch name, stages is the recorded work-trail order used only
    to document why the order was chosen. Returns: a tuple of dicts with
    branch, base and position. No Git or GitHub side effect occurs.
    """
    plan = []
    previous = base
    for position, branch in enumerate(branches, start=1):
        plan.append({"branch": branch, "base": previous, "position": position,
                     "stages": list(stages)})
        previous = branch
    return tuple(plan)


def accepted_branches(accepted):
    """Turn the integrator's accepted PR numbers into stacked branch names.

    Args: accepted is a JSON array of pull request numbers (or a JSON-encoded
    string). Returns: a tuple of ``pr-<number>`` branch names in queue order.
    Malformed entries are ignored so untrusted event text cannot inject a branch.
    """
    if isinstance(accepted, str):
        try:
            accepted = json.loads(accepted or "[]")
        except ValueError:
            return ()
    if not isinstance(accepted, (list, tuple)):
        return ()
    return tuple(f"pr-{int(number)}" for number in accepted
                 if isinstance(number, int) and not isinstance(number, bool))


def main():
    """Write the stacked plan as JSON to stdout for the trusted integrator job.

    ``plan --accepted`` is used by the workflow with a script invocation, so
    the module never depends on the caller's ``sys.path``. ``--branches`` stays
    available for agent-run.sh, which already derives branch names itself.
    """
    import argparse
    import sys

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", nargs="?", choices=("plan",), default="plan")
    parser.add_argument("--branches", nargs="*", default=[])
    parser.add_argument("--accepted", default="")
    parser.add_argument("--base", default="main")
    parser.add_argument("--trail", default=".agent/work-trail.jsonl")
    args = parser.parse_args()
    branches = args.branches or accepted_branches(args.accepted)
    json.dump(stack_plan(branches, args.base, trail_stages(args.trail)), sys.stdout)


if __name__ == "__main__":
    main()
