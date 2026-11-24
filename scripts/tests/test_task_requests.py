"""A worker can ask for a task that does not exist: the integrator checks and adds it."""
import json
import os
import subprocess
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from helpers import load

swarm = load("swarm")

TEXT = ("# TODO\n\n## Work\n\n"
        "- [-] T-01 · The task being merged\n  - scope: apps/api/src/a\n  - details:\n    1. do it\n"
        "- [ ] T-02 · Another task that exists\n  - scope: apps/api/src/b\n")


def good(**more):
    item = {"title": "Add the missing report endpoint", "details": "Needed by T-01.\nSecond line.",
            "scope": ["apps/api/src/reports"]}
    item.update(more)
    return item


def fence(items):
    return "notes\n\n```task-requests\n" + json.dumps(items) + "\n```\n"


class ParseTests(unittest.TestCase):
    def test_a_block_is_read(self):
        self.assertEqual(swarm.task_requests(fence([good()])), [good()])

    def test_no_block_gives_nothing(self):
        self.assertEqual(swarm.task_requests("just notes"), [])

    def test_broken_json_gives_nothing(self):
        self.assertEqual(swarm.task_requests("```task-requests\n{nope\n```"), [])

    def test_json_that_is_not_a_list_gives_nothing(self):
        self.assertEqual(swarm.task_requests("```task-requests\n{\"a\": 1}\n```"), [])


class EntryTests(unittest.TestCase):
    def entries(self, items, text=TEXT, known=()):
        return swarm.request_entries(items, text, 415, known)

    def test_a_valid_request_becomes_an_entry(self):
        (entry,) = self.entries([good()])
        self.assertTrue(entry.startswith("- [ ] FU-415-01 · Add the missing report endpoint\n"))
        self.assertIn("  - scope: apps/api/src/reports\n", entry)
        self.assertIn("    Needed by T-01.", entry)
        self.assertIn("pull request #415", entry)
        self.assertNotIn("depends", entry)

    def test_depends_on_existing_tasks_is_kept(self):
        (entry,) = self.entries([good(depends=["T-02"])])
        self.assertIn("  - depends: T-02\n", entry)

    def test_depends_on_an_archived_task_is_kept(self):
        (entry,) = self.entries([good(depends=["OLD-1"])], known={"OLD-1"})
        self.assertIn("  - depends: OLD-1\n", entry)

    def test_unusable_requests_are_dropped(self):
        bad = [
            "not a dict",
            good(title="short"),
            good(title="x" * 161),
            good(title="two\nlines of title"),
            good(details=""),
            good(details=5),
            good(scope=[]),
            good(scope=["scripts/swarm.py"]),
            good(scope=[".github/workflows/x.yml"]),
            good(scope=["../etc"]),
            good(scope="apps/api"),
            good(depends=["NOPE-9"]),
            good(depends="T-02"),
            good(depends=["T-02"] * 6),
        ]
        self.assertEqual(self.entries(bad), [])

    def test_a_title_that_already_exists_is_dropped(self):
        self.assertEqual(self.entries([good(title="Another task that exists")]), [])

    def test_the_same_title_twice_adds_one(self):
        self.assertEqual(len(self.entries([good(), good()])), 1)

    def test_at_most_three_per_task(self):
        items = [good(title=f"Add the missing report number {n}") for n in range(6)]
        self.assertEqual(len(self.entries(items)), 3)

    def test_no_room_when_many_requests_are_open(self):
        open_ones = "".join(f"- [ ] FU-1-{n:02d} · Open request number {n}\n  - scope: a\n" for n in range(30))
        self.assertEqual(self.entries([good()], TEXT + open_ones), [])

    def test_a_taken_id_moves_the_number_on(self):
        text = TEXT + "- [x] FU-415-01 · Earlier request done\n  - scope: a\n"
        (entry,) = self.entries([good()], text)
        self.assertIn("FU-415-02", entry)


class InsertTests(unittest.TestCase):
    def test_entries_go_right_after_the_task(self):
        out = swarm.insert_after_task(TEXT, "T-01", ["- [ ] FU-1-01 · New one here\n  - scope: a"])
        order = [line for line in out.split("\n") if line.startswith("- [")]
        self.assertEqual([l.split(" · ")[0] for l in order], ["- [-] T-01", "- [ ] FU-1-01", "- [ ] T-02"])
        self.assertIn("    1. do it\n- [ ] FU-1-01", out)

    def test_without_the_task_they_get_their_own_section(self):
        out = swarm.insert_after_task(TEXT, "GONE-1", ["- [ ] FU-1-01 · New one here\n  - scope: a"])
        self.assertIn(swarm.REQUEST_HEADER, out)
        self.assertIn("FU-1-01", out)


class MergeTests(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.dir)
        original = Path.cwd()
        os.chdir(self.dir)
        self.addCleanup(os.chdir, original)
        Path(".agents/notes").mkdir(parents=True)
        swarm.store().write(TEXT)

    def note(self, name, items):
        Path(".agents/notes", name).write_text(fence(items))

    def test_only_this_tasks_notes_are_read(self):
        self.note("T-01-20261121-100000.md", [good()])
        self.note("T-01-fix-20261121-110000.md", [good(title="Add the second missing thing")])
        self.note("T-02-20261121-100000.md", [good(title="Belongs to another task")])
        self.note("T-010-20261121-100000.md", [good(title="Belongs to T-010, not T-01")])
        titles = [item["title"] for item in swarm.merged_requests("T-01")]
        self.assertEqual(sorted(titles), ["Add the missing report endpoint", "Add the second missing thing"])

    def test_a_missing_notes_folder_gives_nothing(self):
        shutil.rmtree(".agents/notes")
        self.assertEqual(swarm.merged_requests("T-01"), [])

    def test_merging_a_task_adds_its_requested_tasks(self):
        self.note("T-01-20261121-100000.md", [good(depends=["T-02"])])
        with mock.patch.object(swarm, "gh_json", return_value={"headRefOid": "abc", "body": "done",
                                                               "title": "T-01: task", "baseRefName": "main",
                                                               "state": "OPEN"}):
            comment = swarm.record_merge(415, "T-01", "abc")
        text = swarm.store().read()
        self.assertIn("[x] T-01", text)
        self.assertIn("- [ ] FU-415-01 · Add the missing report endpoint", text)
        self.assertIn("Added the follow-up task(s) this worker asked for: FU-415-01.", comment)

    def test_record_merge_rejects_mismatched_task_identity(self):
        self.note("T-01-20261121-100000.md", [good()])
        info = {"headRefOid": "abc", "body": "done", "title": "OTHER: task",
                "baseRefName": "main", "state": "OPEN"}
        with mock.patch.object(swarm, "gh_json", return_value=info):
            with self.assertRaises(swarm.ReconciliationError):
                swarm.record_merge(415, "T-01", "abc")
        self.assertNotIn("- [x] T-01", swarm.store().read())

    def test_record_merge_rejects_missing_or_duplicate_task(self):
        info = {"headRefOid": "abc", "body": "done", "title": "T-01: task",
                "baseRefName": "main", "state": "OPEN"}
        with mock.patch.object(swarm, "gh_json", return_value=info):
            with self.assertRaises(swarm.ReconciliationError):
                swarm.record_merge(415, "GONE-1", "abc")
        self.assertFalse(Path("docs/swarm/GONE-1.md").exists())
        swarm.store().write(TEXT + "\n- [ ] T-01 · duplicate\n")
        with mock.patch.object(swarm, "gh_json", return_value=info):
            with self.assertRaises(swarm.ReconciliationError):
                swarm.record_merge(415, "T-01", "abc")
        self.assertFalse(Path("docs/swarm/T-01.md").exists())

    def test_record_merge_rejects_reused_or_malformed_prior_pr(self):
        info = {"headRefOid": "abc", "body": "done", "title": "T-01: task",
                "baseRefName": "main", "state": "OPEN"}
        for prior in ("#414", "invalid"):
            with self.subTest(prior=prior):
                swarm.store().write(TEXT.replace(
                    "  - scope: apps/api/src/a",
                    f"  - scope: apps/api/src/a\n  - pr: {prior}"))
                with mock.patch.object(swarm, "gh_json", return_value=info):
                    with self.assertRaises(swarm.ReconciliationError):
                        swarm.record_merge(415, "T-01", "abc")
                self.assertFalse(Path("docs/swarm/T-01.md").exists())

    def test_record_merge_allows_idempotent_same_pr_retry(self):
        text = TEXT.replace("- [-] T-01", "- [x] T-01").replace(
            "  - scope: apps/api/src/a",
            "  - scope: apps/api/src/a\n  - pr: #415")
        swarm.store().write(text)
        info = {"headRefOid": "abc", "body": "done", "title": "T-01: task",
                "baseRefName": "main", "state": "OPEN"}
        with mock.patch.object(swarm, "gh_json", return_value=info):
            comment = swarm.record_merge(415, "T-01", "abc")
        self.assertIn("Merged to main", comment)
        self.assertTrue(Path("docs/swarm/T-01.md").exists())

    def test_record_merge_normalizes_api_or_malformed_evidence(self):
        error = subprocess.CalledProcessError(1, ("pr", "view"))
        for answer in (error, None, {"headRefOid": "abc"}):
            with self.subTest(answer=answer):
                options = {"side_effect": answer} if isinstance(answer, Exception) else {"return_value": answer}
                with mock.patch.object(swarm, "gh_json", **options):
                    with self.assertRaises(swarm.ReconciliationError):
                        swarm.record_merge(415, "T-01", "abc")
                self.assertFalse(Path("docs/swarm/T-01.md").exists())

    def test_a_merge_without_requests_says_nothing_extra(self):
        with mock.patch.object(swarm, "gh_json", return_value={"headRefOid": "abc", "body": "done",
                                                               "title": "T-01: task", "baseRefName": "main",
                                                               "state": "OPEN"}):
            comment = swarm.record_merge(415, "T-01", "abc")
        self.assertNotIn("follow-up", comment)


if __name__ == "__main__":
    unittest.main()
