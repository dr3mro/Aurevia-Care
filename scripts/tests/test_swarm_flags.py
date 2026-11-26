"""Canonical lowercase repository-variable tokens are shared by every swarm flag."""
import unittest

from helpers import load

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

    def test_literal_arrays_match_the_python_token_sets(self):
        self.assertEqual(flags.ENABLE_JSON, '["1","true","yes","on"]')
        self.assertEqual(flags.DISABLE_JSON, '["0","false","no","off"]')
