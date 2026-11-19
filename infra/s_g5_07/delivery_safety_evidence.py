"""Evaluate an OPS-06 delivery-safety evidence artifact.

The specification fixes the required delivery controls but not a file format.
This module turns OPS-06 into a fail-closed check on a declarative artifact: an
artifact that omits a control, weakens a boolean, aliases an identifier or
names an unknown enum is reported as a failure rather than silently accepted.

Two honesty rules matter more than the checks themselves:

* A synthetic rehearsal is never final evidence; only a ``measured_deployment``
  artifact with zero failures is labelled final, and even that records what the
  artifact claims, not independent verification.
* The evaluator judges the *shape and completeness* of a declaration. It cannot
  prove that cloud accounts are isolated, that a binary was really signed, that
  a rollout was monitored or that production access actually expired. Those are
  human/cloud-gated G5 items documented in ``docs/operations/s_g5_07``.

Usage: ``python3 -I infra/s_g5_07/delivery_safety_evidence.py <artifact.json>``
Exit codes: 0 = artifact passes every OPS-06 check, 1 = it fails a check,
2 = unreadable or unparsable input.
"""
import json
import re
import sys
from pathlib import Path

SCHEMA_VERSION = "1.0"
KINDS = ("synthetic_rehearsal", "measured_deployment")
ENVIRONMENTS = ("development", "test", "staging", "production")
NON_PRODUCTION_ENVIRONMENTS = tuple(
    name for name in ENVIRONMENTS if name != "production")
CI_CHECKS = ("policy", "dependency_scan", "secret_scan")
SIGNING_METHODS = ("cosign", "sigstore", "in-toto")
MIGRATION_GATE = "check-migrations --require-baseline"
ROLLOUT_STRATEGY = "progressive"
ROLLOUT_FINAL_STAGE = "full"
DIGEST_PATTERN = re.compile(r"sha256:[0-9a-fA-F]{64}\Z")


def _reject_constant(value):
    """Reject non-finite JSON numbers so NaN cannot pass an equality check."""
    raise ValueError("non-finite number: " + value)


def _record(checks, name, passed, detail):
    """Append one named check result to the report."""
    checks.append({"name": name, "passed": bool(passed), "detail": detail})


def _require_true(checks, name, value, good, bad):
    """Require the literal boolean True, not a truthy or numeric substitute."""
    passed = value is True
    _record(checks, name, passed, good if passed else bad)


def _require_string(checks, name, value, good, bad):
    """Require a non-blank string."""
    passed = isinstance(value, str) and bool(value.strip())
    _record(checks, name, passed, good if passed else bad)


def _require_enum(checks, name, value, allowed, good, bad):
    """Require a string drawn from a closed allowlist."""
    passed = isinstance(value, str) and value in allowed
    _record(checks, name, passed, good if passed else bad)


def _verdict(checks, kind):
    """Build the report from the named checks and the declared evidence class."""
    failed = [check["name"] for check in checks if not check["passed"]]
    key = kind if isinstance(kind, str) else None
    classification = {
        "synthetic_rehearsal": "rehearsal",
        "measured_deployment": "measured-deployment",
    }.get(key, "unknown")
    return {
        "verdict": "fail" if failed else "pass",
        "classification": classification,
        "final_evidence": classification == "measured-deployment" and not failed,
        "checks": checks,
        "failed": failed,
    }


def _check_environments(envs, checks):
    """Check four distinct, separately identified environments."""
    if not isinstance(envs, dict):
        _record(checks, "environments", False, "environments must be a JSON object")
        return
    if set(envs) != set(ENVIRONMENTS):
        _record(checks, "environments.names", False,
                "expected exactly " + ", ".join(ENVIRONMENTS))
        return
    accounts = []
    resources = []
    for name in ENVIRONMENTS:
        entry = envs[name]
        if not isinstance(entry, dict):
            _record(checks, "environments." + name, False,
                    "environment entry must be a JSON object")
            continue
        account = entry.get("account")
        resource = entry.get("resource")
        if (isinstance(account, str) and bool(account.strip())
                and isinstance(resource, str) and bool(resource.strip())):
            accounts.append(account.strip())
            resources.append(resource.strip())
        else:
            _record(checks, "environments." + name, False,
                    "account and resource must be non-empty strings")
    distinct_accounts = (len(accounts) == len(ENVIRONMENTS)
                         and len(set(accounts)) == len(ENVIRONMENTS))
    distinct_resources = (len(resources) == len(ENVIRONMENTS)
                          and len(set(resources)) == len(ENVIRONMENTS))
    _record(checks, "environments.distinct_accounts", distinct_accounts,
            "four distinct non-empty account identifiers required")
    _record(checks, "environments.distinct_resources", distinct_resources,
            "four distinct non-empty resource identifiers required")


def _check_fixtures(fixtures, checks):
    """Check that non-production fixtures are declared synthetic only."""
    if not isinstance(fixtures, dict):
        _record(checks, "fixtures", False, "fixtures must be a JSON object")
        return
    _require_true(checks, "fixtures.synthetic_only", fixtures.get("synthetic_only"),
                  "synthetic_only is true", "synthetic_only must be true")
    _require_true(checks, "fixtures.no_real_patient_data",
                  fixtures.get("no_real_patient_data"),
                  "no_real_patient_data is true",
                  "no_real_patient_data must be true")
    declared = fixtures.get("environments")
    if isinstance(declared, list):
        passed = (len(declared) == len(NON_PRODUCTION_ENVIRONMENTS)
                  and all(isinstance(item, str) for item in declared)
                  and set(declared) == set(NON_PRODUCTION_ENVIRONMENTS))
        detail = ("exactly the non-production environments declared" if passed
                  else "must list exactly development, test and staging")
    else:
        passed = False
        detail = "environments must be a list"
    _record(checks, "fixtures.environments", passed, detail)


def _check_ci_checks(ci, checks):
    """Check that the mandated CI policy, dependency and secret gates exist."""
    if not isinstance(ci, dict):
        _record(checks, "ci_checks", False, "ci_checks must be a JSON object")
        return
    for name in CI_CHECKS:
        _require_true(checks, "ci_checks." + name, ci.get(name),
                      name + " is enabled", name + " must be enabled")


def _check_signed_builds(sb, checks):
    """Check the immutable digest, signature verification and promotion gate."""
    if not isinstance(sb, dict):
        _record(checks, "signed_builds", False,
                "signed_builds must be a JSON object")
        return
    _require_enum(checks, "signed_builds.method", sb.get("method"),
                  SIGNING_METHODS, "allowed signing method",
                  "unknown signing method")
    digest = sb.get("digest")
    passed = isinstance(digest, str) and DIGEST_PATTERN.fullmatch(digest) is not None
    _record(checks, "signed_builds.digest", passed,
            "full sha256 digest" if passed else "digest must be sha256:<64 hex>")
    _require_true(checks, "signed_builds.verified", sb.get("verified"),
                  "signature verified", "signature must be verified")
    _require_string(checks, "signed_builds.signer_identity",
                    sb.get("signer_identity"), "signer identity declared",
                    "signer identity must be a non-empty string")
    _require_enum(checks, "signed_builds.promotion_gate",
                  sb.get("promotion_gate"), ("signature_verified",),
                  "promotion requires verification",
                  "promotion must require signature verification")


def _check_migration_checks(mc, checks):
    """Check the change-safety gate, expand/contract and forward-fix discipline."""
    if not isinstance(mc, dict):
        _record(checks, "migration_checks", False,
                "migration_checks must be a JSON object")
        return
    gate = mc.get("gate")
    _record(checks, "migrations.gate", gate == MIGRATION_GATE,
            "baseline migration gate declared" if gate == MIGRATION_GATE
            else "gate must be " + MIGRATION_GATE)
    _require_true(checks, "migrations.expand_contract", mc.get("expand_contract"),
                  "expand/contract discipline declared",
                  "expand_contract must be true")
    _require_true(checks, "migrations.forward_fix_required",
                  mc.get("forward_fix_required"), "forward-fix declared",
                  "forward_fix_required must be true")


def _check_rollout(rollout, checks):
    """Check a monitored, reversible progressive rollout with health gates."""
    if not isinstance(rollout, dict):
        _record(checks, "rollout", False, "rollout must be a JSON object")
        return
    _require_enum(checks, "rollout.strategy", rollout.get("strategy"),
                  (ROLLOUT_STRATEGY,), "progressive strategy",
                  "strategy must be progressive")
    stages = rollout.get("stages")
    if isinstance(stages, list):
        passed = (len(stages) >= 2
                  and all(isinstance(item, str) and bool(item.strip())
                          for item in stages)
                  and len(set(stages)) == len(stages)
                  and stages[-1] == ROLLOUT_FINAL_STAGE)
        detail = ("ordered distinct stages ending in full" if passed
                  else "stages must be two or more distinct names ending in full")
    else:
        passed = False
        detail = "stages must be a list"
    _record(checks, "rollout.stages", passed, detail)
    _require_true(checks, "rollout.health_gates", rollout.get("health_gates"),
                  "health gates declared", "health_gates must be true")
    _require_true(checks, "rollout.monitoring", rollout.get("monitoring"),
                  "monitoring declared", "monitoring must be true")
    _require_true(checks, "rollout.rollback_tested", rollout.get("rollback_tested"),
                  "rollback tested", "rollback_tested must be true")


def _check_production_access(pa, checks):
    """Check approved, audited, expiring production access with split duties."""
    if not isinstance(pa, dict):
        _record(checks, "production_access", False,
                "production_access must be a JSON object")
        return
    _require_true(checks, "production_access.approval_required",
                  pa.get("approval_required"), "approval required",
                  "approval_required must be true")
    requester = pa.get("requester")
    approver = pa.get("approver")
    separation = (isinstance(requester, str) and bool(requester.strip())
                  and isinstance(approver, str) and bool(approver.strip())
                  and requester != approver)
    _record(checks, "production_access.separation_of_duties", separation,
            "distinct requester and approver" if separation
            else "requester and approver must be distinct non-empty strings")
    _require_true(checks, "production_access.audited", pa.get("audited"),
                  "audit declared", "audited must be true")
    _require_true(checks, "production_access.expiring", pa.get("expiring"),
                  "expiry declared", "expiring must be true")


def evaluate(artifact):
    """Return a fail-closed OPS-06 verdict for one evidence artifact.

    Content problems are recorded as failed checks rather than raised, so a
    reviewer sees every breach in a single report.
    """
    checks = []
    if not isinstance(artifact, dict):
        _record(checks, "artifact", False, "artifact must be a JSON object")
        return _verdict(checks, None)
    kind = artifact.get("kind")
    _record(checks, "schema_version", artifact.get("schema_version") == SCHEMA_VERSION,
            "expected " + SCHEMA_VERSION)
    _record(checks, "kind", kind in KINDS, "kind must be " + " or ".join(KINDS))
    source = str(artifact.get("source", "")).lower()
    if kind == "measured_deployment" and "synthetic" in source:
        _record(checks, "evidence_conflict", False,
                "a measured deployment artifact cannot name a synthetic source")
    _check_environments(artifact.get("environments"), checks)
    _check_fixtures(artifact.get("fixtures"), checks)
    _check_ci_checks(artifact.get("ci_checks"), checks)
    _check_signed_builds(artifact.get("signed_builds"), checks)
    _check_migration_checks(artifact.get("migration_checks"), checks)
    _check_rollout(artifact.get("rollout"), checks)
    _check_production_access(artifact.get("production_access"), checks)
    _require_true(checks, "reversible_deployments",
                  artifact.get("reversible_deployments"),
                  "reversible deployments declared",
                  "reversible_deployments must be true")
    return _verdict(checks, kind)


def format_report(report):
    """Render a verdict, its evidence class and every named check as text."""
    lines = [f"OPS-06 delivery safety: {report['verdict'].upper()}"]
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
        print("usage: delivery_safety_evidence.py <artifact.json>", file=sys.stderr)
        return 2
    try:
        text = Path(arguments[0]).read_text(encoding="utf-8")
        artifact = json.loads(text, parse_constant=_reject_constant)
    except (OSError, ValueError) as error:
        print(f"cannot read evidence artifact: {error}", file=sys.stderr)
        return 2
    report = evaluate(artifact)
    print(format_report(report))
    return 0 if report["verdict"] == "pass" else 1


if __name__ == "__main__":
    sys.exit(main())
