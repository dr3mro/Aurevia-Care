"""Canonical lowercase repository-variable tokens are shared by every swarm flag."""
import io
import os
import runpy
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from helpers import ROOT, load

flags = load("swarm_flags")

ENABLE_TOKENS = ("1", "true", "yes", "on")
DISABLE_TOKENS = ("0", "false", "no", "off")


class SwarmFlagsTests(unittest.TestCase):
    def test_enable_tokens_return_true(self):
        for value in ENABLE_TOKENS:
            with self.subTest(value=value):
                self.assertIs(flags.enabled(value, False), True)

    def test_case_and_padding_keep_enable_tokens_in_that_set(self):
        for value in ("TRUE", " On ", "YES", "1 "):
            with self.subTest(value=value):
                self.assertIs(flags.enabled(value, False), True)

    def test_disable_tokens_return_false(self):
        for value in DISABLE_TOKENS:
            with self.subTest(value=value):
                self.assertIs(flags.enabled(value, True), False)

    def test_case_and_padding_keep_disable_tokens_in_that_set(self):
        for value in ("FALSE", "  Off  ", "NO", "0 "):
            with self.subTest(value=value):
                self.assertIs(flags.enabled(value, True), False)

    def test_unknown_token_returns_default_true(self):
        self.assertIs(flags.enabled("maybe", True), True)

    def test_unknown_token_returns_default_false(self):
        self.assertIs(flags.enabled("maybe", False), False)

    def test_empty_token_returns_default(self):
        self.assertIs(flags.enabled("", True), True)
        self.assertIs(flags.enabled("", False), False)

    def test_disable_tokens_return_true(self):
        for value in DISABLE_TOKENS:
            with self.subTest(value=value):
                self.assertIs(flags.disabled(value, False), True)

    def test_case_and_padding_keep_disable_tokens_true(self):
        for value in ("FALSE", "  Off  ", "NO", "0 "):
            with self.subTest(value=value):
                self.assertIs(flags.disabled(value, False), True)

    def test_enable_tokens_return_false_for_disabled(self):
        for value in ENABLE_TOKENS:
            with self.subTest(value=value):
                self.assertIs(flags.disabled(value, True), False)

    def test_case_and_padding_keep_enable_tokens_false_for_disabled(self):
        for value in ("TRUE", " On ", "YES", "1 "):
            with self.subTest(value=value):
                self.assertIs(flags.disabled(value, True), False)

    def test_unknown_token_returns_default_for_disabled(self):
        self.assertIs(flags.disabled("maybe", False), False)
        self.assertIs(flags.disabled("maybe", True), True)

    def test_github_outputs_normalises_every_variable(self):
        stream = io.StringIO()
        flags.github_outputs({
            "SWARM_ENABLED": " Off ",
            "SWARM_SINGLE_TASK": " YES ",
            "SWARM_LLM_SELECTION": "1",
            "SWARM_ISSUES": "FALSE",
            "SWARM_NOTES": " On ",
        }, stream)
        self.assertEqual(
            stream.getvalue(),
            "enabled=false\nsingle_task=true\nllm_selection=true\nissues=false\nnotes=true\n",
        )

    def test_github_outputs_uses_documented_defaults(self):
        stream = io.StringIO()
        flags.github_outputs({}, stream)
        self.assertEqual(
            stream.getvalue(),
            "enabled=true\nsingle_task=false\nllm_selection=false\nissues=true\nnotes=true\n",
        )

    def test_main_requires_github_output(self):
        with mock.patch.dict(os.environ, {"GITHUB_OUTPUT": ""}, clear=False):
            os.environ.pop("GITHUB_OUTPUT", None)
            with self.assertRaises(SystemExit):
                flags.main()

    def test_main_writes_canonical_flags_to_github_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "github-output"
            path.write_text("prior=line\n", encoding="utf-8")
            environ = {"GITHUB_OUTPUT": str(path), "SWARM_ENABLED": " no "}
            with mock.patch.dict(os.environ, environ, clear=True):
                flags.main()
            self.assertEqual(
                path.read_text(encoding="utf-8"),
                "prior=line\nenabled=false\nsingle_task=false\nllm_selection=false\n"
                "issues=true\nnotes=true\n",
            )

    def test_running_the_script_writes_canonical_flags(self):
        # The workflow invokes this file directly, so the __main__ guard is a
        # production entry point and must be exercised by a test.
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "github-output"
            environ = {"GITHUB_OUTPUT": str(path), "SWARM_SINGLE_TASK": " On "}
            with mock.patch.dict(os.environ, environ, clear=True):
                runpy.run_path(str(ROOT / "scripts" / "swarm_flags.py"), run_name="__main__")
            self.assertEqual(
                path.read_text(encoding="utf-8"),
                "enabled=true\nsingle_task=true\nllm_selection=false\nissues=true\nnotes=true\n",
            )
