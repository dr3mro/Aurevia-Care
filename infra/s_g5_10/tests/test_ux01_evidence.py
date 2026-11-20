"""Executable acceptance tests for the UX-01 evidence evaluator.

Every fixture is synthetic. A conforming artifact must pass; every UX-01 clause
and every measurement-scope omission must fail closed. The tests enumerate the
evaluator's branches so its behaviour is fully specified without a real device,
a real clinic or a human assistive-technology reviewer.
"""
import contextlib
import importlib.util
import io
import json
import re
import runpy
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

MODULE_PATH = Path(__file__).resolve().parents[1] / "ux01_evidence.py"
SPEC = importlib.util.spec_from_file_location("ux01_evidence", MODULE_PATH)
ux01_evidence = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ux01_evidence)

ux = ux01_evidence


def synthetic_artifact(**overrides):
    """Build a synthetic, conforming UX-01 evidence artifact."""
    artifact = {
        "schema_version": "1.0",
        "kind": "synthetic_rehearsal",
        "source": "synthetic-ux-fixture",
        "localization": {
            "languages": ["en", "ar"],
            "rtl": {"layout_mirrored": True, "navigation_mirrored": True},
            "local_names": {"display": True, "script_preserving": True},
            "mixed_script": {"clinical_text": True, "bidirectional_safe": True},
        },
        "time": {
            "instant_storage": "utc",
            "iana_timezone_context": True,
            "civil_dates_retained": True,
            "dst_transitions_tested": ["Europe/London", "America/New_York",
                                       "Australia/Sydney"],
        },
        "accessibility": {
            "staff_web": {
                "standard": "WCAG 2.2",
                "level": "AA",
                "tested": True,
                "report": "synthetic-axe-report",
            },
            "mobile": {
                "ios_screen_reader": "VoiceOver",
                "android_screen_reader": "TalkBack",
                "flows_tested": True,
            },
        },
        "exports": {"language_preserved": True, "units_preserved": True},
        "presentation": {"styling_flexible": True},
    }
    for key, value in overrides.items():
        if isinstance(value, dict) and isinstance(artifact.get(key), dict):
            artifact[key].update(value)
        else:
            artifact[key] = value
    return artifact


def failures(report):
    """Return the set of failed check names in a report."""
    return set(report["failed"])


class ConformingArtifactTest(unittest.TestCase):
    def test_synthetic_rehearsal_passes_but_is_not_final_evidence(self):
        report = ux.evaluate(synthetic_artifact())
        self.assertEqual(report["verdict"], "pass")
        self.assertEqual(report["failed"], [])
        self.assertEqual(report["classification"], "rehearsal")
        self.assertFalse(report["final_evidence"])
        self.assertTrue(all(check["passed"] for check in report["checks"]))

    def test_measured_acceptance_kind_is_accepted_as_final_evidence(self):
        report = ux.evaluate(synthetic_artifact(
            kind="measured_acceptance", source="assistive-technology-run"))
        self.assertEqual(report["verdict"], "pass")
        self.assertEqual(report["classification"], "measured-pilot")
        self.assertTrue(report["final_evidence"])

    def test_unknown_kind_is_never_final_evidence(self):
        report = ux.evaluate(synthetic_artifact(kind="unverified"))
        self.assertEqual(report["classification"], "unknown")
        self.assertFalse(report["final_evidence"])


class EnvelopeSourceTest(unittest.TestCase):
    """Every artifact must carry an attributable source label.

    A measured artifact without a source cannot be traced to an authorized
    reviewer, so it must never be promoted to final evidence. This is a
    provenance guard, not a clinical gate: it proves the declaration is
    attributable, not that the review really happened.
    """

    def test_source_must_be_a_non_blank_string(self):
        for source in (None, "", "   ", 7, [], {}):
            with self.subTest(source=source):
                report = ux.evaluate(synthetic_artifact(source=source))
                self.assertIn("source", failures(report))
                self.assertEqual(report["verdict"], "fail")

    def test_missing_source_is_reported_without_raising(self):
        artifact = synthetic_artifact()
        artifact.pop("source")
        report = ux.evaluate(artifact)
        self.assertIn("source", failures(report))
        self.assertFalse(report["final_evidence"])

    def test_measured_acceptance_without_source_is_not_final_evidence(self):
        for source in (None, "", "   ", 7):
            with self.subTest(source=source):
                artifact = synthetic_artifact(
                    kind="measured_acceptance", source=source)
                report = ux.evaluate(artifact)
                self.assertIn("source", failures(report))
                self.assertEqual(report["classification"], "measured-pilot")
                self.assertFalse(report["final_evidence"])


class StructureTest(unittest.TestCase):
    def test_artifact_must_be_an_object(self):
        report = ux.evaluate([])
        self.assertEqual(report["verdict"], "fail")
        self.assertIn("artifact", failures(report))
        self.assertFalse(report["final_evidence"])

    def test_schema_version_must_match(self):
        report = ux.evaluate(synthetic_artifact(schema_version="2.0"))
        self.assertIn("schema_version", failures(report))

    def test_kind_must_be_known(self):
        report = ux.evaluate(synthetic_artifact(kind="unverified"))
        self.assertIn("kind", failures(report))

    def test_unhashable_kind_fails_closed_without_raising(self):
        # A list or dict is valid JSON but cannot be a dict key in _verdict.
        for kind in ([], {}, None, 7):
            with self.subTest(kind=kind):
                report = ux.evaluate(synthetic_artifact(kind=kind))
                self.assertIn("kind", failures(report))
                self.assertEqual(report["verdict"], "fail")
                self.assertEqual(report["classification"], "unknown")

    def test_measured_acceptance_cannot_declare_a_synthetic_source(self):
        report = ux.evaluate(synthetic_artifact(
            kind="measured_acceptance", source="SYNTHETIC-fixture"))
        self.assertIn("evidence_conflict", failures(report))
        self.assertFalse(report["final_evidence"])

    def test_measured_acceptance_with_neutral_source_has_no_conflict(self):
        report = ux.evaluate(synthetic_artifact(
            kind="measured_acceptance", source="assistive-technology-run"))
        self.assertNotIn("evidence_conflict", failures(report))

    def test_every_section_must_be_an_object(self):
        for section in ("localization", "time", "accessibility",
                        "exports", "presentation"):
            with self.subTest(section=section):
                report = ux.evaluate(synthetic_artifact(**{section: []}))
                self.assertIn(section, failures(report))


class LocalizationTest(unittest.TestCase):
    def test_both_languages_are_required(self):
        for languages in (["en"], ["ar"], [], ["en", "fr"]):
            with self.subTest(languages=languages):
                report = ux.evaluate(synthetic_artifact(
                    localization={"languages": languages}))
                self.assertIn("localization.languages", failures(report))

    def test_languages_must_be_a_list(self):
        for languages in ("en/ar", {"en": True}, None):
            with self.subTest(languages=languages):
                report = ux.evaluate(synthetic_artifact(
                    localization={"languages": languages}))
                self.assertIn("localization.languages", failures(report))

    def test_duplicate_and_non_string_languages_fail_closed(self):
        report = ux.evaluate(synthetic_artifact(
            localization={"languages": ["en", "ar", "ar"]}))
        self.assertIn("localization.languages", failures(report))
        # An unhashable member must fail closed rather than raising TypeError.
        report = ux.evaluate(synthetic_artifact(
            localization={"languages": ["en", "ar", {"code": "fr"}]}))
        self.assertIn("localization.languages", failures(report))

    def test_extra_languages_are_permitted_when_both_required_ones_exist(self):
        report = ux.evaluate(synthetic_artifact(
            localization={"languages": ["en", "ar", "fr"]}))
        self.assertNotIn("localization.languages", failures(report))

    def test_rtl_flags_must_all_be_true(self):
        for flag in ("layout_mirrored", "navigation_mirrored"):
            with self.subTest(flag=flag):
                report = ux.evaluate(synthetic_artifact(
                    localization={"rtl": {flag: False}}))
                self.assertIn("localization.rtl", failures(report))
        report = ux.evaluate(synthetic_artifact(
            localization={"rtl": {"layout_mirrored": 1,
                                  "navigation_mirrored": "yes"}}))
        self.assertIn("localization.rtl", failures(report))

    def test_rtl_must_be_an_object(self):
        report = ux.evaluate(synthetic_artifact(localization={"rtl": True}))
        self.assertIn("localization.rtl", failures(report))

    def test_local_names_flags_must_all_be_true(self):
        for flag in ("display", "script_preserving"):
            with self.subTest(flag=flag):
                report = ux.evaluate(synthetic_artifact(
                    localization={"local_names": {flag: False}}))
                self.assertIn("localization.local_names", failures(report))

    def test_local_names_must_be_an_object(self):
        report = ux.evaluate(synthetic_artifact(localization={"local_names": None}))
        self.assertIn("localization.local_names", failures(report))

    def test_mixed_script_flags_must_all_be_true(self):
        for flag in ("clinical_text", "bidirectional_safe"):
            with self.subTest(flag=flag):
                report = ux.evaluate(synthetic_artifact(
                    localization={"mixed_script": {flag: False}}))
                self.assertIn("localization.mixed_script", failures(report))

    def test_mixed_script_must_be_an_object(self):
        report = ux.evaluate(synthetic_artifact(localization={"mixed_script": []}))
        self.assertIn("localization.mixed_script", failures(report))


class TimeTest(unittest.TestCase):
    def test_instant_storage_must_be_utc(self):
        for value in ("local", "UTC", None, True):
            with self.subTest(value=value):
                report = ux.evaluate(synthetic_artifact(
                    time={"instant_storage": value}))
                self.assertIn("time.instant_storage", failures(report))

    def test_iana_context_and_civil_dates_must_be_true(self):
        for field in ("iana_timezone_context", "civil_dates_retained"):
            with self.subTest(field=field):
                report = ux.evaluate(synthetic_artifact(time={field: False}))
                self.assertIn("time." + field, failures(report))
                report = ux.evaluate(synthetic_artifact(time={field: "yes"}))
                self.assertIn("time." + field, failures(report))

    def test_dst_zones_must_be_a_non_empty_list(self):
        for value in ("Europe/London", [], None, {"Europe/London": True}):
            with self.subTest(value=value):
                report = ux.evaluate(synthetic_artifact(
                    time={"dst_transitions_tested": value}))
                self.assertIn("time.dst_transitions_tested", failures(report))

    def test_dst_zones_reject_non_strings_and_duplicates(self):
        report = ux.evaluate(synthetic_artifact(
            time={"dst_transitions_tested": ["Europe/London", "Europe/London"]}))
        self.assertIn("time.dst_transitions_tested", failures(report))
        report = ux.evaluate(synthetic_artifact(
            time={"dst_transitions_tested": ["Europe/London", {"zone": 1}]}))
        self.assertIn("time.dst_transitions_tested", failures(report))

    def test_dst_zones_must_be_valid_iana_names(self):
        report = ux.evaluate(synthetic_artifact(
            time={"dst_transitions_tested": ["Not/AZone"]}))
        self.assertIn("time.dst_transitions_tested", failures(report))

    def test_relative_path_zone_name_fails_closed(self):
        # ZoneInfo raises ValueError (not ZoneInfoNotFoundError) for a relative
        # path; the evaluator must absorb it into the named failed check.
        report = ux.evaluate(synthetic_artifact(
            time={"dst_transitions_tested": ["../bad"]}))
        self.assertIn("time.dst_transitions_tested", failures(report))

    def test_at_least_one_dst_observing_zone_is_required(self):
        report = ux.evaluate(synthetic_artifact(
            time={"dst_transitions_tested": ["Asia/Riyadh"]}))
        self.assertIn("time.dst_transitions_tested", failures(report))

    def test_southern_hemisphere_dst_zone_is_accepted(self):
        report = ux.evaluate(synthetic_artifact(
            time={"dst_transitions_tested": ["Australia/Sydney"]}))
        self.assertNotIn("time.dst_transitions_tested", failures(report))


class AccessibilityTest(unittest.TestCase):
    def test_staff_web_standard_and_level_are_exact(self):
        for field, wrong in (("standard", "WCAG 2.1"), ("level", "A")):
            with self.subTest(field=field):
                report = ux.evaluate(synthetic_artifact(
                    accessibility={"staff_web": {field: wrong}}))
                self.assertIn("accessibility.staff_web", failures(report))

    def test_staff_web_requires_testing_and_a_report(self):
        report = ux.evaluate(synthetic_artifact(
            accessibility={"staff_web": {"tested": False}}))
        self.assertIn("accessibility.staff_web", failures(report))
        for report_value in ("", "   ", None, 7):
            with self.subTest(report=report_value):
                outcome = ux.evaluate(synthetic_artifact(
                    accessibility={"staff_web": {"report": report_value}}))
                self.assertIn("accessibility.staff_web", failures(outcome))

    def test_staff_web_must_be_an_object(self):
        report = ux.evaluate(synthetic_artifact(
            accessibility={"staff_web": "WCAG 2.2 AA"}))
        self.assertIn("accessibility.staff_web", failures(report))

    def test_both_native_screen_readers_are_required(self):
        for field, wrong in (("ios_screen_reader", "TalkBack"),
                             ("android_screen_reader", "VoiceOver")):
            with self.subTest(field=field):
                report = ux.evaluate(synthetic_artifact(
                    accessibility={"mobile": {field: wrong}}))
                self.assertIn("accessibility.mobile", failures(report))

    def test_mobile_requires_documented_flow_testing(self):
        report = ux.evaluate(synthetic_artifact(
            accessibility={"mobile": {"flows_tested": False}}))
        self.assertIn("accessibility.mobile", failures(report))

    def test_mobile_must_be_an_object(self):
        report = ux.evaluate(synthetic_artifact(accessibility={"mobile": []}))
        self.assertIn("accessibility.mobile", failures(report))


class ExportsAndPresentationTest(unittest.TestCase):
    def test_export_must_preserve_language_and_units(self):
        for flag in ("language_preserved", "units_preserved"):
            with self.subTest(flag=flag):
                report = ux.evaluate(synthetic_artifact(
                    exports={flag: False}))
                self.assertIn("exports." + flag, failures(report))

    def test_presentation_styling_must_be_flexible(self):
        report = ux.evaluate(synthetic_artifact(
            presentation={"styling_flexible": False}))
        self.assertIn("presentation.styling_flexible", failures(report))

    def test_flags_require_boolean_true_not_truthy_values(self):
        for key, field, check_name in (
            ("exports", "language_preserved", "exports.language_preserved"),
            ("presentation", "styling_flexible", "presentation.styling_flexible"),
        ):
            with self.subTest(field=field):
                report = ux.evaluate(synthetic_artifact(**{key: {field: 1}}))
                self.assertIn(check_name, failures(report))


class ReportAndCliTest(unittest.TestCase):
    def test_format_report_shows_each_check_and_verdict(self):
        passed = ux.format_report(ux.evaluate(synthetic_artifact()))
        self.assertIn("[PASS]", passed)
        self.assertIn("time.dst_transitions_tested", passed)
        self.assertIn("UX-01 accessibility evidence: PASS", passed)
        self.assertIn("evidence class: rehearsal (final: False)", passed)
        failing = ux.format_report(ux.evaluate(synthetic_artifact(kind="unverified")))
        self.assertIn("[FAIL]", failing)
        self.assertIn("evidence class: unknown (final: False)", failing)
        final = ux.format_report(ux.evaluate(synthetic_artifact(
            kind="measured_acceptance", source="assistive-technology-run")))
        self.assertIn("final: True", final)

    def test_main_evaluates_a_conforming_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "evidence.json"
            path.write_text(json.dumps(synthetic_artifact()), encoding="utf-8")
            stdout = io.StringIO()
            with contextlib.redirect_stdout(stdout):
                code = ux.main([str(path)])
            self.assertEqual(code, 0)
            self.assertIn("PASS", stdout.getvalue())

    def test_main_returns_one_for_a_failing_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "evidence.json"
            path.write_text(json.dumps(synthetic_artifact(
                exports={"units_preserved": False})), encoding="utf-8")
            with contextlib.redirect_stdout(io.StringIO()), \
                    contextlib.redirect_stderr(io.StringIO()):
                code = ux.main([str(path)])
            self.assertEqual(code, 1)

    def test_main_reads_sys_argv_when_no_arguments_are_passed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "evidence.json"
            path.write_text(json.dumps(synthetic_artifact()), encoding="utf-8")
            stdout = io.StringIO()
            with mock.patch.object(sys, "argv", ["ux01_evidence.py", str(path)]):
                with contextlib.redirect_stdout(stdout):
                    code = ux.main()
            self.assertEqual(code, 0)

    def test_main_rejects_bad_usage(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            code = ux.main([])
        self.assertEqual(code, 2)
        self.assertIn("usage", stderr.getvalue())

    def test_main_rejects_a_missing_file(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            code = ux.main(["/definitely/not/here/evidence.json"])
        self.assertEqual(code, 2)
        self.assertIn("cannot read", stderr.getvalue())

    def test_main_rejects_invalid_json(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "evidence.json"
            path.write_text("{not json", encoding="utf-8")
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                code = ux.main([str(path)])
            self.assertEqual(code, 2)
            self.assertIn("cannot read", stderr.getvalue())

    def test_main_rejects_invalid_utf8_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "evidence.json"
            path.write_bytes(b"\xff\xfe")
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                code = ux.main([str(path)])
            self.assertEqual(code, 2)
            self.assertIn("cannot read", stderr.getvalue())

    def test_main_rejects_integer_length_bomb(self):
        # A JSON integer beyond CPython's digit limit raises ValueError inside
        # json.loads; it must follow the clean exit-2 path, not a traceback.
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "evidence.json"
            path.write_text("1" * 5000, encoding="utf-8")
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                code = ux.main([str(path)])
            self.assertEqual(code, 2)
            self.assertIn("cannot read", stderr.getvalue())

    def test_main_rejects_extra_arguments(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            code = ux.main(["one.json", "two.json"])
        self.assertEqual(code, 2)
        self.assertIn("usage", stderr.getvalue())

    def test_module_entry_point_runs_main(self):
        # Execute the if __name__ == "__main__" guard so the CLI bootstrap line
        # is covered by the in-process coverage trace, not left as a gap.
        fixture = MODULE_PATH.parent / "fixtures" / "synthetic_ux01_evidence.json"
        stdout = io.StringIO()
        with mock.patch.object(sys, "argv", ["ux01_evidence.py", str(fixture)]):
            with contextlib.redirect_stdout(stdout):
                with self.assertRaises(SystemExit) as raised:
                    runpy.run_path(str(MODULE_PATH), run_name="__main__")
        self.assertEqual(raised.exception.code, 0)
        self.assertIn("PASS", stdout.getvalue())


class AllBreachesTest(unittest.TestCase):
    def test_all_breaches_are_reported_together(self):
        report = ux.evaluate(synthetic_artifact(
            schema_version="2.0",
            localization={"languages": ["en"]},
            time={"instant_storage": "local"},
            accessibility={"staff_web": {"level": "A"}},
            exports={"units_preserved": False},
        ))
        self.assertEqual(report["verdict"], "fail")
        self.assertTrue({
            "schema_version", "localization.languages", "time.instant_storage",
            "accessibility.staff_web", "exports.units_preserved",
        }.issubset(failures(report)))


class DeclaredProfileTest(unittest.TestCase):
    """The declared handoff artifact must agree with the enforced constants."""

    @classmethod
    def setUpClass(cls):
        path = MODULE_PATH.parent / "ux01-profile.json"
        cls.profile = json.loads(path.read_text(encoding="utf-8"))

    def test_profile_matches_the_enforced_constants(self):
        self.assertEqual(self.profile["requirement"], "UX-01")
        self.assertEqual(self.profile["schema_version"], ux.SCHEMA_VERSION)
        self.assertEqual(self.profile["languages"], list(ux.TARGET_LANGUAGES))
        self.assertEqual(self.profile["instant_storage"], ux.INSTANT_STORAGE)
        self.assertEqual(self.profile["wcag"]["standard"], ux.WCAG_STANDARD)
        self.assertEqual(self.profile["wcag"]["level"], ux.WCAG_LEVEL)
        self.assertEqual(self.profile["screen_readers"]["ios"], ux.IOS_SCREEN_READER)
        self.assertEqual(self.profile["screen_readers"]["android"],
                         ux.ANDROID_SCREEN_READER)
        self.assertEqual(self.profile["assessment_kinds"], list(ux.KINDS))
        self.assertTrue(self.profile["synthetic_only"])

    def test_fixture_text_contains_no_phi_shaped_values(self):
        text = json.dumps(self.profile)
        self.assertIsNone(re.search(r"[\w.+-]+@[\w-]+\.[\w.]+", text))
        self.assertIsNone(re.search(r"\b\d{3}-\d{2}-\d{4}\b", text))


if __name__ == "__main__":
    unittest.main()
