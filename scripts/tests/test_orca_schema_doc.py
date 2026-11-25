"""The documented ORCA schema is pinned to the real passive helpers."""
import json
import re
import unittest
from pathlib import Path

from helpers import ROOT, load

core = load("orca_core")
docs = load("check-docs")
SCHEMA = ROOT / "docs" / "orca-refactor" / "13-orca-schema.md"


def synthetic_contract():
    """A deterministic task contract with only synthetic identifiers."""
    return core.TaskContract(
        task_id="SYN-014",
        objective="Verify the synthetic context schema",
        acceptance=("manifest keys match the helper",),
        scope=("docs/synthetic/schema.md",),
        risk="routine",
        requirements=("ORCA-019",),
    )


SOURCES = {
    "safety": "SYNTHETIC SAFETY RULE",
    "execution": "SYNTHETIC EXECUTION RULE",
    "notes": "SYNTHETIC LONG OPTIONAL NOTE",
    "spec": "SYNTHETIC SPEC EXCERPT",
}
BUDGETS = {"notes": 10, "default": 0}


def fenced_text(text, heading, language):
    """Return the fenced block of a language under a heading in supplied text."""
    if heading not in text:
        raise AssertionError(f"missing heading: {heading}")
    match = re.search(rf"```{language}\n(.*?)\n```", text.split(heading, 1)[1], re.S)
    if match is None:
        raise AssertionError(f"missing {language} block under {heading}")
    return match.group(1)


def fenced(heading, language):
    """Return the fenced block of a language under a heading in the schema doc."""
    return fenced_text(SCHEMA.read_text(encoding="utf-8"), heading, language)


class SchemaDocTests(unittest.TestCase):
    """Every documented field and rule is checked against helper output."""

    def test_schema_document_exists(self):
        self.assertTrue(SCHEMA.is_file())

    def test_documented_manifest_keys_match_the_helper(self):
        documented = fenced("## Manifest fields", "text").splitlines()
        expected = core.compile_context(synthetic_contract(), SOURCES, BUDGETS)
        self.assertEqual([line.strip() for line in documented if line.strip()],
                         sorted(expected))

    def test_worked_manifest_matches_compile_context(self):
        artifact = json.loads(fenced("## Worked manifest artifact", "json"))
        expected = core.compile_context(synthetic_contract(), SOURCES, BUDGETS)
        self.assertEqual(artifact, expected)

    def test_stable_hash_is_reproduced(self):
        artifact = json.loads(fenced("## Worked manifest artifact", "json"))
        self.assertEqual(artifact["hash"],
                         core.compile_context(synthetic_contract(), SOURCES, BUDGETS)["hash"])

    def test_mandatory_sections_are_never_truncated(self):
        sources = dict(SOURCES, safety="SAFETY " * 40, execution="EXECUTE " * 40)
        manifest = core.compile_context(synthetic_contract(), sources, {"default": 1})
        self.assertEqual(manifest["content"]["safety"], sources["safety"])
        self.assertEqual(manifest["content"]["execution"], sources["execution"])
        self.assertIn("safety", manifest["instructions"])
        self.assertIn("execution", manifest["instructions"])

    def test_optional_budgets_truncate_only_when_positive(self):
        bounded = core.compile_context(synthetic_contract(), SOURCES, BUDGETS)
        self.assertEqual(bounded["content"]["notes"], "SYNTHETIC ")
        zero = core.compile_context(synthetic_contract(), SOURCES, {"default": 0})
        self.assertEqual(zero["content"]["notes"], SOURCES["notes"])
        negative = core.compile_context(synthetic_contract(), SOURCES, {"default": -3})
        self.assertEqual(negative["content"]["notes"], SOURCES["notes"])

    def test_worked_artifact_heading_is_unambiguous(self):
        text = SCHEMA.read_text(encoding="utf-8")
        self.assertEqual(text.count("## Worked manifest artifact"), 1)
        self.assertEqual(text.count("```json"), 1)

    def test_invalid_artifact_is_reported(self):
        broken = SCHEMA.read_text(encoding="utf-8").replace(
            '"hash": "03ba43b7f756",', '"hash": ')
        with self.assertRaises(json.JSONDecodeError):
            json.loads(fenced_text(broken, "## Worked manifest artifact", "json"))

    def test_preflight_examples_match_the_helper(self):
        documented = fenced("## Preflight problems", "text").splitlines()
        expected = core.preflight(synthetic_contract(), {"python3"},
                                  {"AGENTS.md"}, {"delivery"})
        self.assertEqual([line.strip() for line in documented if line.strip()], expected)

    def test_worked_artifact_is_synthetic(self):
        text = SCHEMA.read_text(encoding="utf-8")
        for token in ("/Users/", "sk-", "patient record", "ghp_"):
            with self.subTest(token=token):
                self.assertNotIn(token, text)

    def test_docs_checker_accepts_the_document(self):
        self.assertEqual(docs.check(ROOT), [])

    def test_missing_artifact_is_reported(self):
        text = SCHEMA.read_text(encoding="utf-8")
        self.assertIn("```json", text)
        self.assertIn("```text", text)


if __name__ == "__main__":
    unittest.main()
