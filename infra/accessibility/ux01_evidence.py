"""Evaluate a UX-01 localization and accessibility evidence artifact.

The specification fixes the clauses — English and Arabic, RTL direction, local
name display, mixed-script clinical text, UTC instants with IANA time-zone
context, retained civil dates, tested daylight-saving transitions, WCAG 2.2 AA
on staff web, native screen-reader flows on both mobile platforms, language and
units preserved in exports, and flexible presentation styling. This module turns
those clauses into a fail-closed check: an artifact that omits a clause or
misstates a literal is reported as a failure rather than silently accepted.

Two honesty rules are enforced:

* A synthetic rehearsal proves the check works, never that a real assistive
  technology review happened. ``final_evidence`` is true only for a
  ``measured_acceptance`` artifact that carries an attributable, non-blank
  ``source`` and is not itself labelled synthetic.
* Even a measured artifact only proves the declared envelope; it cannot prove a
  measurement really occurred or substitute for the human G5 sign-off.

The evaluator judges an evidence artifact, not a running application. It can
prove that supplied declarations meet UX-01 and that the envelope is complete;
native screen-reader usability and WCAG conformance remain human-gated.

Usage: ``python3 -I infra/accessibility/ux01_evidence.py <artifact.json>``
Exit codes: 0 = artifact passes the UX-01 checks, 1 = it fails a check,
2 = unreadable input.
"""
import json
import sys
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

SCHEMA_VERSION = "1.0"
KINDS = ("synthetic_rehearsal", "measured_acceptance")
TARGET_LANGUAGES = ("en", "ar")
INSTANT_STORAGE = "utc"
WCAG_STANDARD = "WCAG 2.2"
WCAG_LEVEL = "AA"
IOS_SCREEN_READER = "VoiceOver"
ANDROID_SCREEN_READER = "TalkBack"


def _is_true(value):
    """Return true only for the boolean True (reject truthy substitutes)."""
    return value is True


def _is_nonempty_string(value):
    """Return true for a non-blank string."""
    return isinstance(value, str) and bool(value.strip())


def _observes_dst(zone):
    """Return True/False for a valid IANA zone, or None when it is invalid.

    Comparing a fixed winter and summer noon detects a transition in either
    hemisphere: any zone that changes its UTC offset across those dates observes
    daylight saving, while a fixed-offset zone does not.
    """
    try:
        tz = ZoneInfo(zone)
    except (ZoneInfoNotFoundError, ValueError):
        return None
    winter = datetime(2025, 1, 15, 12, tzinfo=tz).utcoffset()
    summer = datetime(2025, 7, 15, 12, tzinfo=tz).utcoffset()
    return winter != summer


def _record(checks, name, passed, detail):
    """Append one named check result to the report."""
    checks.append({"name": name, "passed": bool(passed), "detail": detail})


def _verdict(checks, kind):
    """Build the report from the checks and the declared evidence class."""
    failed = [check["name"] for check in checks if not check["passed"]]
    # Compare explicitly instead of hashing untrusted JSON: a list or dict kind
    # must fail closed as "unknown", not raise TypeError here.
    if kind == "synthetic_rehearsal":
        classification = "rehearsal"
    elif kind == "measured_acceptance":
        classification = "measured-pilot"
    else:
        classification = "unknown"
    return {
        "verdict": "fail" if failed else "pass",
        "classification": classification,
        # A rehearsal proves the check works; it is never final acceptance.
        "final_evidence": classification == "measured-pilot" and not failed,
        "checks": checks,
        "failed": failed,
    }


def _section(artifact, key, checks, checker):
    """Validate a named object section, failing closed when it is absent."""
    value = artifact.get(key)
    if not isinstance(value, dict):
        _record(checks, key, False, f"{key} must be a JSON object")
        return
    checker(value, checks)


def _check_languages(value, checks):
    """Check that both required interface languages are declared exactly once."""
    name = "localization.languages"
    if not isinstance(value, list):
        _record(checks, name, False, "languages must be a list")
        return
    if not all(isinstance(item, str) for item in value):
        _record(checks, name, False, "languages must contain only strings")
        return
    duplicates = len(value) != len(set(value))
    missing = [lang for lang in TARGET_LANGUAGES if lang not in value]
    passed = not duplicates and not missing
    detail = ("both required languages present without duplicates" if passed
              else "missing: " + ", ".join(missing) + "; duplicates: " + str(duplicates))
    _record(checks, name, passed, detail)


def _check_source(value, checks):
    """Check that the artifact names an attributable, non-blank source label.

    A measured artifact without a source cannot be traced to an authorized
    reviewer, so it must fail the envelope and never become final evidence.
    The check is about provenance of the declaration, not proof that the review
    happened; that remains a human G5 gate.
    """
    _record(checks, "source", _is_nonempty_string(value),
            "source must be a non-blank string label")


def _check_flag(value, name, checks):
    """Check one boolean clause that must be literally true."""
    _record(checks, name, value is True, f"{name} must be true")


def _check_flags(value, name, fields, checks):
    """Check a group of boolean clauses under one named section."""
    if not isinstance(value, dict):
        _record(checks, name, False, f"{name} must be a JSON object")
        return
    unset = [field for field in fields if value.get(field) is not True]
    _record(checks, name, not unset,
            "all required flags true" if not unset
            else "flags not true: " + ", ".join(unset))


def _check_localization(localization, checks):
    """Check language, RTL, local-name and mixed-script declarations."""
    _check_languages(localization.get("languages"), checks)
    _check_flags(localization.get("rtl"), "localization.rtl",
                 ("layout_mirrored", "navigation_mirrored"), checks)
    _check_flags(localization.get("local_names"), "localization.local_names",
                 ("display", "script_preserving"), checks)
    _check_flags(localization.get("mixed_script"), "localization.mixed_script",
                 ("clinical_text", "bidirectional_safe"), checks)


def _check_dst_zones(value, checks):
    """Check that real, distinct IANA zones with a DST transition were tested."""
    name = "time.dst_transitions_tested"
    if not isinstance(value, list):
        _record(checks, name, False, "must be a list of IANA zone names")
        return
    if not value:
        _record(checks, name, False, "must name at least one IANA zone")
        return
    if not all(isinstance(item, str) for item in value):
        _record(checks, name, False, "zone names must be strings")
        return
    duplicates = len(value) != len(set(value))
    results = [_observes_dst(zone) for zone in value]
    invalid = [zone for zone, result in zip(value, results) if result is None]
    observes = any(result is True for result in results)
    passed = not duplicates and not invalid and observes
    detail = ("valid IANA zones with at least one daylight-saving observer"
              if passed else
              f"duplicates={duplicates}; invalid={invalid}; dst_observed={observes}")
    _record(checks, name, passed, detail)


def _check_time(time, checks):
    """Check UTC instant storage, IANA context, civil dates and DST testing."""
    _record(checks, "time.instant_storage",
            time.get("instant_storage") == INSTANT_STORAGE,
            f"expected '{INSTANT_STORAGE}'")
    _record(checks, "time.iana_timezone_context",
            _is_true(time.get("iana_timezone_context")), "must be true")
    _record(checks, "time.civil_dates_retained",
            _is_true(time.get("civil_dates_retained")), "must be true")
    _check_dst_zones(time.get("dst_transitions_tested"), checks)


def _check_staff_web(value, checks):
    """Check the exact WCAG standard and level plus evidence of testing."""
    name = "accessibility.staff_web"
    if not isinstance(value, dict):
        _record(checks, name, False, "staff_web must be a JSON object")
        return
    problems = []
    if value.get("standard") != WCAG_STANDARD:
        problems.append("standard")
    if value.get("level") != WCAG_LEVEL:
        problems.append("level")
    if not _is_true(value.get("tested")):
        problems.append("tested")
    if not _is_nonempty_string(value.get("report")):
        problems.append("report")
    _record(checks, name, not problems,
            "WCAG 2.2 AA tested with a report" if not problems
            else "invalid: " + ", ".join(problems))


def _check_mobile(value, checks):
    """Check that both native screen readers and their flows are declared."""
    name = "accessibility.mobile"
    if not isinstance(value, dict):
        _record(checks, name, False, "mobile must be a JSON object")
        return
    problems = []
    if value.get("ios_screen_reader") != IOS_SCREEN_READER:
        problems.append("ios_screen_reader")
    if value.get("android_screen_reader") != ANDROID_SCREEN_READER:
        problems.append("android_screen_reader")
    if not _is_true(value.get("flows_tested")):
        problems.append("flows_tested")
    _record(checks, name, not problems,
            "VoiceOver and TalkBack flows documented" if not problems
            else "invalid: " + ", ".join(problems))


def _check_accessibility(accessibility, checks):
    """Check staff-web conformance and mobile screen-reader declarations."""
    _check_staff_web(accessibility.get("staff_web"), checks)
    _check_mobile(accessibility.get("mobile"), checks)


def _check_exports(exports, checks):
    """Check that exports preserve both language and measurement units."""
    _check_flag(exports.get("language_preserved"), "exports.language_preserved",
                checks)
    _check_flag(exports.get("units_preserved"), "exports.units_preserved", checks)


def _check_presentation(presentation, checks):
    """Check that presentation styling remains flexible."""
    _check_flag(presentation.get("styling_flexible"),
                "presentation.styling_flexible", checks)


def evaluate(artifact):
    """Return a fail-closed UX-01 verdict for one evidence artifact.

    Content problems are recorded as failed checks rather than raised, so a
    reviewer sees every breach in one report.
    """
    checks = []
    if not isinstance(artifact, dict):
        _record(checks, "artifact", False, "artifact must be a JSON object")
        return _verdict(checks, None)
    kind = artifact.get("kind")
    source = artifact.get("source")
    _record(checks, "schema_version", artifact.get("schema_version") == SCHEMA_VERSION,
            f"expected {SCHEMA_VERSION}")
    _record(checks, "kind", kind in KINDS, "kind must be " + " or ".join(KINDS))
    _check_source(source, checks)
    if kind == "measured_acceptance" and "synthetic" in str(source or "").lower():
        _record(checks, "evidence_conflict", False,
                "a measured acceptance artifact cannot name a synthetic source")
    _section(artifact, "localization", checks, _check_localization)
    _section(artifact, "time", checks, _check_time)
    _section(artifact, "accessibility", checks, _check_accessibility)
    _section(artifact, "exports", checks, _check_exports)
    _section(artifact, "presentation", checks, _check_presentation)
    return _verdict(checks, kind)


def format_report(report):
    """Render a verdict, its evidence class and every named check as text."""
    lines = [f"UX-01 accessibility evidence: {report['verdict'].upper()}"]
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
        print("usage: ux01_evidence.py <artifact.json>", file=sys.stderr)
        return 2
    try:
        artifact = json.loads(Path(arguments[0]).read_text(encoding="utf-8"))
    # ValueError subsumes JSONDecodeError, UnicodeDecodeError and the JSON
    # integer-length limit, so every unreadable/hostile input exits 2 cleanly.
    except (OSError, ValueError) as error:
        print(f"cannot read evidence artifact: {error}", file=sys.stderr)
        return 2
    report = evaluate(artifact)
    print(format_report(report))
    return 0 if report["verdict"] == "pass" else 1


if __name__ == "__main__":
    sys.exit(main())
