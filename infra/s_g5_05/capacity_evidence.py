"""Evaluate an OPS-03 capacity and performance evidence artifact.

The specification fixes the targets, the pilot load profile and the measurement
scope. This module turns those clauses into a fail-closed check: an artifact that
omits scope/exclusion metadata, misses a target or misstates the profile is
reported as a failure rather than silently accepted.

Two honesty rules are enforced beyond the numbers:

* 99.9% *monthly* availability cannot be established by a 30-minute load run, so
  the availability figure must declare a ``calendar_month`` window.
* A passing synthetic artifact is a rehearsal of the check, never final pilot
  evidence; ``final_evidence`` is true only for a measured pilot artifact that
  is not itself labelled synthetic.

The evaluator judges an evidence artifact, not a running system. It can prove
that supplied numbers meet OPS-03 and that the declared envelope is complete; it
cannot prove a measurement really happened server-side. Real pilot acceptance is
a separate, human-gated step.

Usage: ``python3 -I infra/s_g5_05/capacity_evidence.py <artifact.json>``
Exit codes: 0 = artifact passes the OPS-03 checks, 1 = it fails a check,
2 = unreadable input.
"""
import json
import sys
from pathlib import Path

SCHEMA_VERSION = "1.0"
KINDS = ("synthetic_rehearsal", "measured_pilot")
AVAILABILITY_TARGET = 99.9
AVAILABILITY_WINDOW = "calendar_month"
P95_READ_MS_MAX = 500
P95_COMMAND_MS_MAX = 900
RATE_TOLERANCE = 0.90
ROUTE_CLASSES = ("read", "command")

# The declared pilot profile from OPS-03. A test that used a different profile
# did not run the specified pilot scenario, so these are exact requirements.
TARGET_PROFILE = {
    "organizations": 20,
    "patient_charts": 100000,
    "concurrent_staff": 200,
    "requests_per_second": 50,
    "duration_minutes": 30,
}
# Categories that must be excluded from the reported server-side latency.
REQUIRED_EXCLUSIONS = ("document_transfer", "document_rendering", "external_calls")


def _is_number(value):
    """Return true for a real numeric value, excluding booleans."""
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def _record(checks, name, passed, detail):
    """Append one named check result to the report."""
    checks.append({"name": name, "passed": bool(passed), "detail": detail})


def _verdict(checks, kind):
    """Build the report from the checks and the declared evidence class."""
    failed = [check["name"] for check in checks if not check["passed"]]
    classification = {
        "synthetic_rehearsal": "rehearsal",
        "measured_pilot": "measured-pilot",
    }.get(kind, "unknown")
    return {
        "verdict": "fail" if failed else "pass",
        "classification": classification,
        # A rehearsal proves the check works; it is never final pilot evidence.
        "final_evidence": classification == "measured-pilot" and not failed,
        "checks": checks,
        "failed": failed,
    }


def _check_profile(profile, checks):
    """Check the declared pilot profile against the OPS-03 scenario."""
    if not isinstance(profile, dict):
        _record(checks, "profile", False, "profile must be a JSON object")
        return
    for key, target in TARGET_PROFILE.items():
        value = profile.get(key)
        name = "profile." + key
        if (not isinstance(value, int) or isinstance(value, bool) or value <= 0):
            _record(checks, name, False, "must be a positive integer")
        elif value != target:
            _record(checks, name, False, f"expected {target}, got {value}")
        else:
            _record(checks, name, True, str(value))


def _check_measurement(measurement, checks):
    """Check the measurement envelope: server-side scope, exclusions, routes."""
    if not isinstance(measurement, dict):
        _record(checks, "measurement", False, "measurement must be a JSON object")
        return
    scope = measurement.get("scope")
    _record(checks, "measurement.scope", scope == "server_side",
            f"scope={scope!r}; expected 'server_side'")

    excluded = measurement.get("excluded")
    if isinstance(excluded, list):
        missing = [item for item in REQUIRED_EXCLUSIONS if item not in excluded]
        # Extra exclusions are rejected: excluding an additional category could
        # hide slow requests behind a broader latency filter.
        extra = [item for item in excluded if item not in REQUIRED_EXCLUSIONS]
        duplicates = any(excluded.count(item) > 1 for item in excluded)
        clean = not missing and not extra and not duplicates
        detail = ("exactly the required categories excluded" if clean
                  else "missing: " + ", ".join(str(item) for item in missing) + "; "
                  + "unexpected: " + ", ".join(str(item) for item in extra) + "; "
                  + "duplicates: " + str(duplicates))
        _record(checks, "measurement.excluded", clean, detail)
    else:
        _record(checks, "measurement.excluded", False,
                "excluded must be a list of categories")

    routes = measurement.get("route_class")
    if isinstance(routes, dict):
        values = list(routes.values())
        valid = (bool(routes) and all(isinstance(value, str) for value in values)
                 and set(values) == set(ROUTE_CLASSES))
        _record(checks, "measurement.route_class", valid,
                "both read and command routes declared" if valid
                else "route_class must declare at least one read and one command route")
    else:
        _record(checks, "measurement.route_class", False,
                "route_class must be a route-to-class object")


def _check_availability(availability, checks):
    """Check monthly availability: a short load run cannot establish this."""
    if not isinstance(availability, dict):
        _record(checks, "availability", False, "availability must be a JSON object")
        return
    window = availability.get("window")
    _record(checks, "availability.window", window == AVAILABILITY_WINDOW,
            f"window={window!r}; expected '{AVAILABILITY_WINDOW}'")
    percent = availability.get("percent")
    if _is_number(percent) and 0 < percent <= 100:
        _record(checks, "availability.percent", percent >= AVAILABILITY_TARGET,
                f"{percent}%; target >= {AVAILABILITY_TARGET}%")
    else:
        _record(checks, "availability.percent", False,
                "percent must be a number in (0, 100]")


def _check_latency_and_workload(results, checks):
    """Check the two latency thresholds and that the mixed run was sustained."""
    for key, limit in (("p95_read_ms", P95_READ_MS_MAX),
                       ("p95_command_ms", P95_COMMAND_MS_MAX)):
        value = results.get(key)
        if _is_number(value) and value > 0:
            _record(checks, "results." + key, value <= limit,
                    f"{value} ms; target <= {limit} ms")
        else:
            _record(checks, "results." + key, False,
                    "must be a positive number of milliseconds")

    counts = (results.get("total_requests"), results.get("read_requests"),
              results.get("command_requests"))
    if all(_is_number(count) and count > 0 for count in counts):
        total, read, command = counts
        _record(checks, "results.mixed", total == read + command,
                f"total={total}, read={read}, command={command}")
        minimum = (TARGET_PROFILE["requests_per_second"]
                   * TARGET_PROFILE["duration_minutes"] * 60 * RATE_TOLERANCE)
        _record(checks, "results.sustained_rate", total >= minimum,
                f"total={total}; minimum={minimum:g} at the declared profile")
    else:
        _record(checks, "results.mixed", False,
                "request counts must be positive numbers")


def _check_results(results, checks):
    """Check the measured targets and availability envelope."""
    if not isinstance(results, dict):
        _record(checks, "results", False, "results must be a JSON object")
        return
    _check_availability(results.get("availability"), checks)
    _check_latency_and_workload(results, checks)


def evaluate(artifact):
    """Return a fail-closed OPS-03 verdict for one evidence artifact.

    Content problems are recorded as failed checks rather than raised, so a
    reviewer sees every breach in one report.
    """
    checks = []
    if not isinstance(artifact, dict):
        _record(checks, "artifact", False, "artifact must be a JSON object")
        return _verdict(checks, None)
    kind = artifact.get("kind")
    _record(checks, "schema_version", artifact.get("schema_version") == SCHEMA_VERSION,
            f"expected {SCHEMA_VERSION}")
    _record(checks, "kind", kind in KINDS, "kind must be " + " or ".join(KINDS))
    if kind == "measured_pilot" and "synthetic" in str(artifact.get("source", "")).lower():
        _record(checks, "evidence_conflict", False,
                "a measured pilot artifact cannot name a synthetic source")
    _check_profile(artifact.get("profile"), checks)
    _check_measurement(artifact.get("measurement"), checks)
    _check_results(artifact.get("results"), checks)
    return _verdict(checks, kind)


def format_report(report):
    """Render a verdict, its evidence class and every named check as text."""
    lines = [f"OPS-03 capacity evidence: {report['verdict'].upper()}"]
    lines.append(f"  evidence class: {report['classification']}"
                 f" (final: {report['final_evidence']})")
    for check in report["checks"]:
        status = "PASS" if check["passed"] else "FAIL"
        lines.append(f"  [{status}] {check['name']}: {check['detail']}")
    return "\n".join(lines)


def main(argv=None):
    """Evaluate a JSON artifact path from the command line."""
    arguments = sys.argv[1:] if argv is None else list(argv)
    if len(arguments) != 1:
        print("usage: capacity_evidence.py <artifact.json>", file=sys.stderr)
        return 2
    try:
        artifact = json.loads(Path(arguments[0]).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        print(f"cannot read evidence artifact: {error}", file=sys.stderr)
        return 2
    report = evaluate(artifact)
    print(format_report(report))
    return 0 if report["verdict"] == "pass" else 1


if __name__ == "__main__":
    sys.exit(main())
