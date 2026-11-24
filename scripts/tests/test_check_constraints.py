import contextlib
import io
import os
import runpy
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from helpers import load

guard = load("check-constraints")

DIFF = """diff --git a/src/a.ts b/src/a.ts
--- a/src/a.ts
+++ b/src/a.ts
@@ -3,0 +4,2 @@ context
+first
+second
@@ -9 +11 @@
+third
diff --git a/old.ts b/old.ts
--- a/old.ts
+++ /dev/null
@@ -1 +0,0 @@
-gone
"""


def flagged(path, text):
    return [label for _, _, label, _ in guard.violations([(path, 1, text)])]


class DiffParsingTests(unittest.TestCase):
    def test_added_lines_carry_their_new_line_numbers(self):
        self.assertEqual(list(guard.diff_added_lines(DIFF)),
                         [("src/a.ts", 4, "first"), ("src/a.ts", 5, "second"), ("src/a.ts", 11, "third")])

    def test_removed_lines_and_deleted_files_add_nothing(self):
        self.assertEqual(list(guard.diff_added_lines("--- a/x\n+++ /dev/null\n+orphan\n")), [])


class RuleTests(unittest.TestCase):
    def test_typescript_suppressions_are_flagged(self):
        for text in ("// @ts-ignore", "/* @ts-expect-error */", "// @ts-nocheck",
                     "// eslint-disable-next-line", "/* istanbul ignore next */",
                     "// c8 ignore next", "// v8 ignore start", "it.skip('x', () => {})",
                     "describe.only('x', () => {})", "xit('x', () => {})",
                     "const a: any = 1;", "x as any", "<any>y"):
            with self.subTest(text=text):
                self.assertEqual(len(flagged("apps/staff-web/src/a.tsx", text)), 1, text)

    def test_ordinary_typescript_is_not_flagged(self):
        for text in ("const anyone = 1;", "it('works', () => {})", "const fit = 2;", "// a comment"):
            with self.subTest(text=text):
                self.assertEqual(flagged("a.ts", text), [])

    def test_java_suppressions_are_flagged(self):
        for text in ('@SuppressWarnings("x")', '@SuppressFBWarnings("y")', "@Disabled", "@Ignore",
                     "foo(); // NOSONAR", "@Generated", "@ExcludeFromJacocoGeneratedReport"):
            with self.subTest(text=text):
                self.assertEqual(len(flagged("apps/api/src/A.java", text)), 1, text)

    def test_python_and_shell_suppressions_are_flagged(self):
        for path, text in (("a.py", "x = 1  # noqa"), ("a.py", "y = 2  # type: ignore"),
                           ("a.py", "if z:  # pragma: no cover"), ("a.py", "@pytest.mark.skipif(1)"),
                           ("a.py", "@unittest.skip('x')"), ("a.sh", "# shellcheck disable=SC2086")):
            with self.subTest(text=text):
                self.assertEqual(len(flagged(path, text)), 1, text)

    def test_maven_weakening_is_flagged(self):
        for text in ("<skipTests>true</skipTests>", "<skip>true</skip>", "<haltOnFailure>false</haltOnFailure>",
                     "<failOnViolation>false</failOnViolation>", "<minimum>0.90</minimum>",
                     "<exclude>com/x/**</exclude>"):
            with self.subTest(text=text):
                self.assertEqual(len(flagged("apps/api/pom.xml", text)), 1, text)
        self.assertEqual(flagged("apps/api/pom.xml", "<minimum>1.00</minimum>"), [])
        self.assertEqual(flagged("apps/api/pom.xml", "<minimum>1</minimum>"), [])
        self.assertEqual(flagged("apps/api/spotbugs-exclude.xml", "<Match><Class name=\"X\"/></Match>"),
                         ["SpotBugs exclusion"])

    def test_frontend_config_weakening_is_flagged(self):
        cases = (("apps/staff-web/vitest.config.ts", "  exclude: ['src/x.ts'],"),
                 ("apps/staff-web/vitest.config.ts", "      branches: 95,"),
                 ("apps/staff-web/eslint.config.mjs", "  'no-explicit-any': 'off',"),
                 ("apps/patient-mobile/jest.config.ts", "  coveragePathIgnorePatterns: ['x'],"),
                 ("apps/staff-web/tsconfig.json", '  "strict": false,'),
                 ("apps/staff-web/package.json", '    "lines": 80,'))
        for path, text in cases:
            with self.subTest(text=text):
                self.assertEqual(len(flagged(path, text)), 1, text)
        for path, text in (("apps/staff-web/vitest.config.ts", "      branches: 100,"),
                           ("apps/staff-web/tsconfig.json", '  "exclude": ["node_modules"],'),
                           ("apps/staff-web/package.json", '    "branches": 100,')):
            with self.subTest(clean=text):
                self.assertEqual(flagged(path, text), [])

    def test_prose_trackers_notes_tests_and_the_guard_itself_are_exempt(self):
        for path in ("docs/x.ts", "README.md", "docs/pr/notes/a.md", "scripts/tests/test_x.py",
                     "scripts/check-constraints.py", "apps/staff-web/package-lock.json",
                     "apps/staff-web/src/generated/openapi.ts", "tasks/0001-A.md"):
            with self.subTest(path=path):
                self.assertEqual(flagged(path, "// eslint-disable"), [])

    def test_a_violation_reports_path_line_label_and_text(self):
        self.assertEqual(guard.violations([("a.py", 7, "x = 1  # noqa  ")]),
                         [("a.py", 7, "noqa", "x = 1  # noqa")])


class GitRepoTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(lambda: shutil.rmtree(self.root))
        self.git("init", "-q", "-b", "main")
        (self.root / "a.py").write_text("x = 1\n")
        self.git("add", "-A")
        self.commit("base")
        self.git("checkout", "-q", "-b", "work")

    def git(self, *args):
        subprocess.run(["git", *args], cwd=self.root, check=True, capture_output=True)

    def commit(self, message):
        self.git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", message)

    def run_main(self, *args):
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            status = guard.main(["--base", "main", *args], root=self.root)
        return status, err.getvalue()

    def test_a_clean_change_passes(self):
        (self.root / "a.py").write_text("x = 2\n")
        self.assertEqual(self.run_main(), (0, ""))

    def test_a_committed_suppression_fails_with_its_location(self):
        (self.root / "a.py").write_text("x = 1\ny = 2  # noqa\n")
        self.git("add", "-A")
        self.commit("work")
        status, err = self.run_main()
        self.assertEqual(status, 1)
        self.assertIn("a.py:2: noqa: y = 2  # noqa", err)
        self.assertIn("AGENTS.md", err)

    def test_an_uncommitted_edit_and_an_untracked_file_are_checked(self):
        (self.root / "a.py").write_text("x = 1  # type: ignore\n")
        (self.root / "new.ts").write_text("ok\n// eslint-disable\n")
        status, err = self.run_main()
        self.assertEqual(status, 1)
        self.assertIn("a.py:1: type: ignore", err)
        self.assertIn("new.ts:2: eslint-disable", err)

    def test_an_existing_suppression_the_change_does_not_touch_passes(self):
        self.git("checkout", "-q", "main")
        (self.root / "old.py").write_text("x = 1  # noqa\n")
        self.git("add", "-A")
        self.commit("old debt")
        self.git("checkout", "-q", "-b", "later")
        (self.root / "a.py").write_text("x = 3\n")
        self.assertEqual(self.run_main()[0], 0)

    def test_unreadable_untracked_files_are_skipped(self):
        (self.root / "binary.bin").write_bytes(b"\xff\xfe\x00")
        os.symlink(self.root / "missing", self.root / "dangling.py")
        self.assertEqual(self.run_main()[0], 0)

    def test_an_unknown_base_warns_and_passes_unless_it_is_required(self):
        status, err = self.run_main("--base", "nowhere")
        self.assertEqual((status, "cannot resolve nowhere" in err), (0, True))
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            status = guard.main(["--base", "nowhere", "--require-base"], root=self.root)
        self.assertEqual(status, 1)

    def test_the_base_defaults_to_coverage_base_or_origin_main(self):
        os.environ["COVERAGE_BASE"] = "main"
        self.addCleanup(os.environ.pop, "COVERAGE_BASE", None)
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            status = guard.main([], root=self.root)
        self.assertEqual((status, err.getvalue()), (0, ""))


class CommandLineTests(unittest.TestCase):
    def test_the_script_exits_with_the_status_of_main(self):
        script = Path(__file__).resolve().parents[1] / "check-constraints.py"
        err = io.StringIO()
        with mock.patch.object(sys, "argv", [str(script), "--base", "no-such-ref"]), \
                contextlib.redirect_stderr(err), self.assertRaises(SystemExit) as raised:
            runpy.run_path(str(script), run_name="__main__")
        self.assertEqual(raised.exception.code, 0)
        self.assertIn("cannot resolve no-such-ref", err.getvalue())


class WrittenBarTests(unittest.TestCase):
    def test_written_coverage_bar_cannot_be_lowered(self):
        diff = ("diff --git a/CONSTRAINTS.md b/CONSTRAINTS.md\n"
                "--- a/CONSTRAINTS.md\n"
                "+++ b/CONSTRAINTS.md\n"
                "@@ -3 +3 @@\n"
                "+| 1 | Coverage | 90% | gates |")
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "CONSTRAINTS.md").write_text("old")
            with mock.patch.object(guard, "git", return_value=diff):
                found = guard.written_bar_violations(root, "base")
        self.assertTrue(any("coverage bar lowered" in f[2] for f in found))

    def test_written_100_percent_bar_is_allowed(self):
        diff = ("diff --git a/CONSTRAINTS.md b/CONSTRAINTS.md\n"
                "--- a/CONSTRAINTS.md\n"
                "+++ b/CONSTRAINTS.md\n"
                "@@ -3 +3 @@\n"
                "+| 1 | Coverage | 100% | gates |")
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "CONSTRAINTS.md").write_text("old")
            with mock.patch.object(guard, "git", return_value=diff):
                found = guard.written_bar_violations(root, "base")
        self.assertEqual(found, [])


if __name__ == "__main__":
    unittest.main()
