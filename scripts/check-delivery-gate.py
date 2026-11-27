"""Decide whether the Delivery gate may pass for one workflow run.

Delivery's ``gate`` job is the single required status check for branch
protection. It must fail closed: a mandatory verification job that never ran,
was cancelled or failed cannot be accepted just because its result is not
``failure``. Only two jobs are conditional, and each skip is legal only for the
event that makes it unnecessary:

* ``verify`` (image build and smoke) is skipped on pull requests. Every other
  event, including pushes, release tags and merge groups, needs it.
* ``release`` runs only for a tag push; it is skipped on every other event.

The expected job set is checked as well, so adding a job to ``needs`` without
updating this contract fails the gate instead of silently ignoring the new job.

Usage:
    check-delivery-gate.py --results "$RESULTS" \
        --event-name "$GITHUB_EVENT_NAME" --ref-type "$GITHUB_REF_TYPE"
"""

import argparse
import json
import sys

# Jobs that must succeed for every trigger. A missing, failed, cancelled or
# skipped result for any of these fails the gate.
MANDATORY = frozenset({"policy", "script-tests", "frontend", "java-verify", "docs"})
# Conditional jobs and the condition that permits their skip.
OPTIONAL = {
    "verify": lambda event_name, ref_type: event_name == "pull_request",
    "release": lambda event_name, ref_type: ref_type != "tag",
}
EXPECTED = MANDATORY | set(OPTIONAL)

# Diagnostic order and causality mirror delivery.yml. Results alone say that a job
# skipped, not which prerequisite failed; this graph lets the gate name the root
# cause before the dependent skips without changing any pass/fail rule.
DEPENDENCIES = {
    "policy": (),
    "script-tests": ("policy",),
    "frontend": ("script-tests",),
    "java-verify": ("script-tests",),
    "docs": ("frontend", "java-verify"),
    "verify": ("docs",),
    "release": ("verify",),
}
JOB_ORDER = tuple(DEPENDENCIES)


def result_of(value):
    """Return the ``result`` field of one ``needs`` entry, or None if malformed."""
    return value.get("result") if isinstance(value, dict) else None


def _ancestors(name):
    """Return every direct and transitive prerequisite of one job."""
    result = set()
    for parent in DEPENDENCIES[name]:
        result.add(parent)
        result.update(_ancestors(parent))
    return result


def required_jobs(event_name, ref_type):
    """Return jobs that must succeed for this event, excluding legal skips."""
    required = set(MANDATORY)
    for name, skip_allowed in OPTIONAL.items():
        if not skip_allowed(event_name, ref_type):
            required.add(name)
    return required


def dependency_problems(results, required):
    """Explain skipped jobs by naming only their blocking root prerequisites."""
    blocking = {
        name for name in required & set(results)
        if result_of(results[name]) not in ("success", "skipped")
    }
    skipped = {
        name for name in required & set(results)
        if result_of(results[name]) == "skipped"
    }
    if not blocking or not skipped:
        return []
    upstream = set()
    for name in skipped:
        upstream.update(_ancestors(name))
    relevant = blocking & upstream
    roots = [
        name for name in JOB_ORDER
        if name in relevant and not (_ancestors(name) & relevant)
    ]
    if not roots:
        return []
    label = "cause" if len(roots) == 1 else "causes"
    statuses = ", ".join(
        f"{name} is {result_of(results[name]) or 'unknown'}" for name in roots)
    return [
        f"dependency root {label}: {statuses}; downstream jobs were skipped"
    ]


def gate_violations(results, event_name, ref_type):
    """Return why the gate must fail; an empty list means it may pass.

    ``results`` is the object produced by ``toJSON(needs)``: job ids mapped to
    objects with at least a ``result`` field. Unknown or missing job ids and
    any result other than ``success`` (or a justified ``skipped``) are problems.
    """
    if not isinstance(results, dict):
        return ["needs results are not an object"]

    problems = []
    missing = sorted(EXPECTED - set(results))
    if missing:
        problems.append("missing job results: " + ", ".join(missing))
    extra = sorted(set(results) - EXPECTED)
    if extra:
        problems.append("unexpected job results: " + ", ".join(extra))
    problems.extend(dependency_problems(results, required_jobs(event_name, ref_type)))

    for name in sorted(MANDATORY & set(results), key=JOB_ORDER.index):
        result = result_of(results[name])
        if result != "success":
            problems.append(f"mandatory job {name} is {result or 'unknown'}, expected success")

    for name in sorted(set(OPTIONAL) & set(results), key=JOB_ORDER.index):
        result = result_of(results[name])
        if result == "success":
            continue
        if result == "skipped" and OPTIONAL[name](event_name, ref_type):
            continue
        if result == "skipped":
            problems.append(
                f"job {name} was skipped but {event_name}/{ref_type} requires it")
        else:
            problems.append(f"job {name} is {result or 'unknown'}, expected success")

    return problems


def main(argv=None):
    """Read arguments, report every job result, and fail on any violation."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results", required=True, help="JSON object from toJSON(needs)")
    parser.add_argument("--event-name", required=True, help="github.event_name")
    parser.add_argument("--ref-type", required=True, help="github.ref_type")
    args = parser.parse_args(argv)

    try:
        results = json.loads(args.results)
    except ValueError as error:
        print(f"delivery gate: invalid results JSON: {error}", file=sys.stderr)
        return 2

    if isinstance(results, dict):
        for name in JOB_ORDER:
            if name in results:
                print(f"{name}: {result_of(results[name]) or 'unknown'}")
        for name in sorted(set(results) - EXPECTED):
            print(f"{name}: {result_of(results[name]) or 'unknown'}")

    problems = gate_violations(results, args.event_name, args.ref_type)
    for problem in problems:
        print(f"delivery gate: {problem}", file=sys.stderr)
    if problems:
        return 1
    print("delivery gate: all required checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
