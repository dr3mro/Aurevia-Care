"""Fresh per-run Surefire reports for the swarm worker gate.

Every fixture here is synthetic: temporary directories, fabricated XML and
mocked Git calls. The gate must decide from the private report directory of the
current run; failing XML left in the shared Surefire directory by an earlier run
must never change the verdict, and a nonzero build without usable fresh results
must fail as a compile or setup error.
"""
import importlib.util
import io
import os
import re
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

SCRIPTS = Path(__file__).parents[1]
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))
SPEC = importlib.util.spec_from_file_location("worker_reports_under_test", SCRIPTS / "worker_gate.py")
worker_gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(worker_gate)


def write_report(directory, filename, failures="0", errors="0"):
    """Write a synthetic surefire TEST-*.xml report into a directory."""
    suite = filename[len("TEST-"):-len(".xml")]
    (Path(directory) / filename).write_text(
        '<testsuite name="%s" tests="1" failures="%s" errors="%s"/>' % (suite, failures, errors))


class FreshReportDirectoryTest(unittest.TestCase):
    """main() reads only the private report directory passed as its second argument."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.shared = self.root / "shared"
        self.fresh = self.root / "fresh"
        self.shared.mkdir()
        self.fresh.mkdir()

    def tearDown(self):
        self.tmp.cleanup()

    def run_gate(self, argv, env=None, changed=()):
        """Drive main() with synthetic environment and capture its verdict."""
        base = {"STORY_BODY": "", "STORY_ID": "", "WORKER_FINISHED": "1", "NOTES_FILE": ""}
        base.update(env or {})
        out = io.StringIO()
        with mock.patch.object(worker_gate, "REPORTS", str(self.shared)), \
             mock.patch.object(worker_gate, "changed_paths", return_value=list(changed)), \
             mock.patch.object(worker_gate, "head_commit", return_value="abc123"), \
             mock.patch.dict(os.environ, base, clear=True), redirect_stdout(out):
            code = worker_gate.main(argv)
        return code, out.getvalue()

    def test_stale_failure_does_not_fail_a_successful_run(self):
        write_report(self.shared, "TEST-com.x.StaleTest.xml", failures="2")
        write_report(self.fresh, "TEST-com.x.FreshTest.xml")
        code, message = self.run_gate(["0", str(self.fresh)])
        self.assertEqual(code, 0)
        self.assertEqual(message, "integration tests passed\n")
        self.assertNotIn("StaleTest", message)

    def test_stale_failure_does_not_disguise_a_compile_failure(self):
        write_report(self.shared, "TEST-com.x.StaleTest.xml", failures="2")
        # A compile/setup failure leaves no fresh report directory at all.
        missing = self.root / "never-created"
        code, message = self.run_gate(["1", str(missing)], env={"STORY_ID": "FIX-01"})
        self.assertEqual(code, 1)
        self.assertIn("compile or setup error", message)
        self.assertNotIn("repair task", message)
        self.assertNotIn("StaleTest", message)

    def test_fresh_failure_reaches_the_repair_classifier(self):
        write_report(self.fresh, "TEST-com.x.FooTest.xml", failures="1")
        code, message = self.run_gate(
            ["1", str(self.fresh)],
            env={"STORY_ID": "FIX-01", "STORY_BODY": "The `FooTest` class must pass."})
        self.assertEqual(code, 1)
        self.assertIn("still failing", message)
        self.assertIn("FooTest", message)

    def test_fresh_failure_reaches_the_ordinary_classifier(self):
        write_report(self.fresh, "TEST-com.x.FooTest.xml", errors="1")
        code, message = self.run_gate(["0", str(self.fresh)], changed=["Foo.java"])
        self.assertEqual(code, 1)
        self.assertIn("related to this change", message)
        self.assertIn("FooTest", message)

    def test_fresh_unrelated_failure_keeps_the_repair_task_exception(self):
        write_report(self.fresh, "TEST-com.x.OtherTest.xml", failures="1")
        code, message = self.run_gate(
            ["1", str(self.fresh)],
            env={"STORY_ID": "FIX-01", "STORY_BODY": "The `FooTest` class must pass."})
        self.assertEqual(code, 0)
        self.assertIn("other classes still red", message)

    def test_nonzero_build_with_unusable_fresh_reports_fails(self):
        (self.fresh / "TEST-com.x.TruncatedTest.xml").write_text("<not xml")
        code, message = self.run_gate(["1", str(self.fresh)], env={"STORY_ID": "FIX-01"})
        self.assertEqual(code, 1)
        self.assertIn("compile or setup error", message)
        self.assertNotIn("repair task", message)

    def test_nonzero_build_with_all_passing_fresh_reports_fails(self):
        write_report(self.fresh, "TEST-com.x.FreshTest.xml")
        code, message = self.run_gate(["1", str(self.fresh)], env={"STORY_ID": "FIX-01"})
        self.assertEqual(code, 1)
        self.assertIn("without a failing test class", message)

    def test_empty_second_argument_uses_the_default_directory(self):
        write_report(self.shared, "TEST-com.x.StaleTest.xml", failures="1")
        code, message = self.run_gate(["0", ""], env={"STORY_ID": "FIX-01"})
        self.assertEqual(code, 1)
        self.assertIn("repair task", message)
        self.assertIn("StaleTest", message)

    def test_single_argument_uses_the_default_directory(self):
        write_report(self.shared, "TEST-com.x.StaleTest.xml", failures="1")
        code, message = self.run_gate(["0"], env={"STORY_ID": "FIX-01"})
        self.assertEqual(code, 1)
        self.assertIn("repair task", message)


class UsableReportsTest(unittest.TestCase):
    """usable_reports: only parseable fresh XML counts as test results."""

    def test_empty_directory_has_no_usable_results(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertFalse(worker_gate.usable_reports(tmp))

    def test_truncated_xml_alone_is_not_usable(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "TEST-com.x.BrokenTest.xml").write_text("<not xml")
            self.assertFalse(worker_gate.usable_reports(tmp))

    def test_truncated_xml_followed_by_a_valid_report_is_usable(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "TEST-a.BrokenTest.xml").write_text("<not xml")
            write_report(tmp, "TEST-b.GoodTest.xml")
            self.assertTrue(worker_gate.usable_reports(tmp))

    def test_valid_report_is_usable(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_report(tmp, "TEST-com.x.GoodTest.xml")
            self.assertTrue(worker_gate.usable_reports(tmp))

    def test_non_report_files_are_ignored(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "notes.txt").write_text("not a report")
            self.assertFalse(worker_gate.usable_reports(tmp))


class WorkerCheckScriptTest(unittest.TestCase):
    """worker-check.sh isolates reports and hands the private path to the reader."""

    @classmethod
    def setUpClass(cls):
        cls.script = (SCRIPTS / "worker-check.sh").read_text()

    def test_creates_a_private_report_directory(self):
        self.assertIn("mktemp -d", self.script)

    def test_passes_the_private_directory_to_the_gate(self):
        self.assertIsNotNone(re.search(r"worker_gate\.py[^\n]*\$\{?reports_dir", self.script))

    def test_clears_stale_shared_reports_before_the_run(self):
        self.assertLess(self.script.index("TEST-*.xml"), self.script.index("-Dgroups=integration"))
        self.assertIn("-delete", self.script)

    def test_copies_this_runs_reports_into_the_private_directory(self):
        maven = self.script.index("mvn -q -B -f apps/api/pom.xml -Pno-security -Dgroups=integration")
        gate = self.script.index("python3 -I scripts/worker_gate.py")
        self.assertLess(maven, gate)
        self.assertIn("$reports_dir/", self.script)


if __name__ == "__main__":
    unittest.main()
