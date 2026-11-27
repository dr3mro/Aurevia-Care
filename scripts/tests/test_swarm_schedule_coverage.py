"""Coverage for the uncovered branches of the swarm schedule/issue-monitor slice.

This file exercises the failure and fallback paths in ``scripts/swarm.py``
around the monitor selection, task status updates and slot-image readiness.
All fixtures are synthetic and every ``gh`` call is replaced by a local fake,
so the tests never touch the network or a real repository.
"""
import contextlib
import datetime
import io
import json
import os
import shlex
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from helpers import load

swarm = load("swarm")

NOW = datetime.datetime(2026, 11, 20, 12, 0, tzinfo=datetime.timezone.utc)

TODO = """# TODO

## Tasks

- [ ] CW-001 · Staff
  - attempts: 5
  - scope: apps/api/staff
"""

GOOD_ANSWER = {"title": "Reject expired tokens in the staff API",
               "scope": ["apps/api/staff"],
               "plan": "Expired tokens pass.\nAdd a failing test first."}


def issue(number, body, labels=("swarm-issue", "agent-fixable")):
    """Build a synthetic, trusted swarm issue with a body marker."""
    return {"number": number, "title": f"t{number}", "body": body,
            "user": {"login": "github-actions[bot]"}, "author_association": "NONE",
            "labels": [{"name": name} for name in labels]}


def aged(number, minutes, body=""):
    """Build an issue whose timestamps are `minutes` old relative to NOW."""
    stamp = (NOW - datetime.timedelta(minutes=minutes)).strftime("%Y-%m-%dT%H:%M:%SZ")
    found = issue(number, body)
    found["created_at"] = stamp
    found["updated_at"] = stamp
    return found


class Gh:
    """Record gh calls and answer the few listing queries the monitor makes."""

    def __init__(self, issues=None, comments=None):
        self.calls = []
        self.issues = issues or []
        self.comments = comments or []

    def __call__(self, *args, check=True):
        self.calls.append(args)
        if args[0] == "api" and "issues?state=open" in args[-1]:
            return json.dumps(self.issues)
        if args[0] == "api" and "/comments" in args[-1]:
            return json.dumps(self.comments)
        if args[:2] == ("pr", "view"):
            return "OPEN" if "--jq" in args else "{}"
        return ""

    def said(self, *needle):
        return any(all(n in " ".join(map(str, call)) for n in needle) for call in self.calls)


class MonitorSelectCoverageTests(unittest.TestCase):
    """The monitor selection path for an issue that names a task with no entry."""

    def select(self, fake, text=TODO, mode="daily"):
        with mock.patch.object(swarm, "gh", fake), \
                mock.patch.object(swarm, "live_workers", lambda: []):
            return swarm.monitor_select(mode, text, tempfile.mkdtemp(), now=NOW)

    def test_an_issue_naming_an_unknown_task_is_nudged(self):
        # No TODO entry and no pull request: the task marker alone sends the
        # issue back to a wave instead of creating a task or dropping it.
        fake = Gh(issues=[aged(5, 3000, body="<!-- swarm-task:CW-999 -->")])
        chosen = self.select(fake)
        self.assertEqual(chosen["nudge"], [5])
        self.assertEqual(chosen["triage"], [])


class ResetAttemptsCoverageTests(unittest.TestCase):
    """Every scan outcome of reset_attempts, including its three early exits."""

    def test_an_unknown_task_leaves_the_text_unchanged(self):
        # The entry search exhausts without finding the id, so the original
        # text is returned byte for byte and no field is rewritten.
        self.assertEqual(swarm.reset_attempts(TODO, "CW-404"), TODO)

    def test_a_task_with_no_details_or_following_entry_reaches_the_end(self):
        text = "- [ ] A-1 · a\n  - attempts: 5\n"
        self.assertEqual(swarm.reset_attempts(text, "A-1"),
                         "- [ ] A-1 · a\n  - attempts: 0\n")

    def test_the_scan_stops_at_the_next_unindented_line(self):
        text = ("- [ ] A-1 · a\n  - attempts: 5\n"
                "- [ ] A-2 · b\n  - attempts: 7\n")
        self.assertEqual(swarm.reset_attempts(text, "A-1"),
                         "- [ ] A-1 · a\n  - attempts: 0\n"
                         "- [ ] A-2 · b\n  - attempts: 7\n")


class MonitorAssignCoverageTests(unittest.TestCase):
    """The monitor assignment paths for nudges, resets and duplicate entries."""

    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.todo = self.dir / "TODO.md"
        self.todo.write_text(TODO)
        self.fake = Gh()

    def assign(self, **kwargs):
        def update(edit, message, path="TODO.md"):
            self.todo.write_text(edit(self.todo.read_text()))

        with mock.patch.object(swarm, "gh", self.fake), \
                mock.patch.object(swarm, "update_todo", update), \
                mock.patch.object(swarm, "wave_has_room", lambda: False):
            kwargs.setdefault("triaged", [])
            return swarm.monitor_assign(self.dir / "triage", todo=str(self.todo), **kwargs)

    def test_nudged_issues_get_a_requeue_comment(self):
        result = self.assign(nudge=[5])
        self.assertEqual(result["nudged"], [5])
        self.assertTrue(self.fake.said("issue", "comment", "5", "swarm-requeue"))

    def test_a_reset_leaves_every_other_task_alone(self):
        self.todo.write_text(TODO + "\n- [ ] CW-002 · Other\n  - attempts: 7\n  - scope: apps/api/other\n")
        result = self.assign(reset=[(2, "CW-001")])
        tasks = {t["id"]: t for t in swarm.parse_tasks(self.todo.read_text())}
        self.assertEqual(result["reset"], [2])
        self.assertEqual(tasks["CW-001"]["attempts"], "0")
        self.assertEqual(tasks["CW-002"]["attempts"], "7")

    def test_a_malformed_triage_answer_is_treated_as_unusable(self):
        # Untrusted worker output: invalid JSON is caught and refused, so the
        # issue goes to a person instead of failing the whole monitor job.
        (self.dir / "triage").mkdir()
        (self.dir / "triage" / "triage-5.json").write_text("not json")
        result = self.assign(triaged=[5])
        self.assertEqual(result["failed"], [5])
        self.assertTrue(self.fake.said("needs-human"))

    def test_an_entry_already_added_by_another_merge_needs_no_second_edit(self):
        # Selection reads an older TODO; by the time the edit runs, another
        # merge has added the task. The edit must leave that text untouched.
        (self.dir / "triage").mkdir()
        (self.dir / "triage" / "triage-5.json").write_text(json.dumps(GOOD_ANSWER))
        already = TODO + "\n" + swarm.triage_entry(5, GOOD_ANSWER, TODO) + "\n"
        seen = []

        def fake_update(edit, message, path="TODO.md"):
            seen.append(edit(already))
            return False

        with mock.patch.object(swarm, "gh", self.fake), \
                mock.patch.object(swarm, "update_todo", fake_update), \
                mock.patch.object(swarm, "wave_has_room", lambda: False):
            result = swarm.monitor_assign(self.dir / "triage", triaged=[5], todo=str(self.todo))
        self.assertEqual(seen, [already])
        self.assertEqual(result["tasks"], [5])


class SlotImageCoverageTests(unittest.TestCase):
    """Fallback paths for slot config lookup and the GHCR manifest check."""

    def test_an_unknown_slot_number_has_no_config(self):
        with self.assertRaises(StopIteration):
            swarm.slot_config([{"slot": 1}], 2)

    def test_a_registry_error_means_the_image_is_missing(self):
        class FailingGh:
            def __init__(self):
                self.calls = []

            def __call__(self, *args, check=True):
                self.calls.append(args)
                raise subprocess.CalledProcessError(1, "gh")

        fake = FailingGh()
        with mock.patch.object(swarm, "gh", fake), \
                mock.patch.object(swarm, "repo_slug", lambda: "o/r"):
            self.assertFalse(swarm.slot_manifests_exist([{"slot": 1}]))
        self.assertEqual(len(fake.calls), 1)


class ReconcileTasksCoverageTests(unittest.TestCase):
    """Close out task files when a merged pull request finishes their work.

    The merged-PR stream comes from a mocked `gh api --paginate`, and every task file
    is a synthetic fixture in a temporary directory, so no repository or network is used.
    """

    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())

    def write(self, name, text):
        path = self.dir / name
        path.write_text(text)
        return path

    def reconcile(self, merged, names=(), explicit=None):
        """Run reconcile_tasks with a canned merged-PR list and a fixed file set.

        `merged` is a JSON-ready list of pull requests, or an exception for the
        failed-`gh` path. `names` controls the patched glob result; `explicit`
        exercises the single-file entry point instead. The canned answer uses
        JSON lines, the same stream the paginated gh command emits.
        """
        answer = (mock.patch.object(subprocess, "check_output", side_effect=merged)
                  if isinstance(merged, Exception)
                  else mock.patch.object(subprocess, "check_output",
                                         return_value="\n".join(json.dumps(item) for item in merged)))
        paths = [str(self.dir / name) for name in names]
        with answer, mock.patch("glob.glob", return_value=paths):
            return swarm.reconcile_tasks(directory=str(self.dir), file=explicit or "")

    def test_a_failed_pr_list_leaves_the_task_untouched_and_raises(self):
        path = self.write("one.md", "- [-] CW-001 · one\n  - attempts: 4\n")
        with self.assertRaises(swarm.ReconciliationError):
            self.reconcile(RuntimeError("gh is down"), explicit=str(path))
        self.assertEqual(path.read_text(), "- [-] CW-001 · one\n  - attempts: 4\n")

    def test_a_malformed_pr_list_raises_without_editing(self):
        path = self.write("one.md", "- [ ] CW-001 · one\n  - attempts: 4\n")
        with mock.patch.object(subprocess, "check_output", return_value="not json"):
            with self.assertRaises(swarm.ReconciliationError):
                swarm.reconcile_tasks(directory=str(self.dir), file=str(path))
        self.assertEqual(path.read_text(), "- [ ] CW-001 · one\n  - attempts: 4\n")

    def test_only_open_tasks_named_by_a_merged_pr_are_closed(self):
        # A merged title identifies a task by id; a merged PR number identifies
        # one by its `pr:` field. Unrelated, unknown and malformed files stay put.
        merged = [
            {"number": 42, "title": "CW-009: pr", "baseRefName": "main", "state": "MERGED"},
            {"number": 43, "title": "ISS-7: other thing", "baseRefName": "main", "state": "MERGED"},
            {"number": 44, "title": "CW-002: fix the thing", "baseRefName": "main", "state": "MERGED"},
            {"number": 99, "title": "Repair protected workflow", "baseRefName": "main", "state": "MERGED"},
        ]
        by_id = self.write("by_id.md", "- [-] CW-002\n  - attempts: 5\n  - details:\n    body\n")
        by_pr = self.write("by_pr.md", "- [ ] CW-009 · pr\n  - pr: #42\n  - details:\n    body\n")
        unknown = self.write("unknown.md", "- [ ] CW-003 · unknown\n  - attempts: 1\n")
        heading = self.write("heading.md", "# Just a heading\n")
        has_evidence = self.write("evidence.md", "- [ ] ISS-7 · evidence\n"
                                                 "  - evidence: already recorded\n")
        self.reconcile(merged, names=("by_id.md", "by_pr.md", "unknown.md",
                                      "heading.md", "evidence.md", "missing.md"))
        for path, task in ((by_id, "CW-002"), (by_pr, "CW-009"), (has_evidence, "ISS-7")):
            self.assertIn(f"- [x] {task}", path.read_text())
        self.assertIn("  - evidence: PR #44 merged to main (auto-reconciled)\n", by_id.read_text())
        self.assertIn("  - evidence: already recorded\n", has_evidence.read_text())
        self.assertNotIn("auto-reconciled", has_evidence.read_text())
        self.assertEqual(unknown.read_text(), "- [ ] CW-003 · unknown\n  - attempts: 1\n")
        self.assertEqual(heading.read_text(), "# Just a heading\n")

    def test_malformed_evidence_shapes_fail_before_write(self):
        path = self.write("one.md", "- [ ] CW-001 · one\n")
        answers = (
            json.dumps([{"number": 7, "title": "CW-001: one"}, "not-an-object"]),
            json.dumps([{"number": "7", "title": "CW-001: one", "baseRefName": "main", "state": "MERGED"}]),
            json.dumps([{"number": 7, "title": 42, "baseRefName": "main", "state": "MERGED"}]),
        )
        for answer in answers:
            with self.subTest(answer=answer), \
                    mock.patch.object(subprocess, "check_output", return_value=answer), \
                    mock.patch.object(swarm, "git") as git:
                with self.assertRaises(swarm.ReconciliationError):
                    swarm.reconcile_tasks(directory=str(self.dir), file=str(path), persist=True)
            git.assert_not_called()
            self.assertEqual(path.read_text(), "- [ ] CW-001 · one\n")

    def test_paginated_fetch_reconciles_a_task_beyond_the_first_window(self):
        # A full first window is normal on a busy repository, not proof of truncated
        # evidence. The authoritative fetch must still see the task on a later page.
        path = self.write("far.md", "- [ ] CW-500 · far\n")
        merged = [{"number": n + 1, "title": f"X-{n}: other", "baseRefName": "main",
                   "state": "MERGED"} for n in range(500)]
        merged.append({"number": 501, "title": "CW-500: far", "baseRefName": "main",
                       "state": "MERGED"})
        stream = "\n".join(json.dumps(item) for item in merged)
        with mock.patch.object(subprocess, "check_output", return_value=stream) as checked, \
                mock.patch("glob.glob", return_value=[str(path)]):
            count = swarm.reconcile_tasks(directory=str(self.dir))
        self.assertEqual(count, 1)
        self.assertIn("- [x] CW-500", path.read_text())
        command = checked.call_args.args[0]
        self.assertEqual(command[:3], ["gh", "api", "--paginate"])
        self.assertNotIn("--slurp", command)
        url = next(arg for arg in command if arg.startswith("repos/"))
        self.assertIn("pulls?state=closed&base=main&per_page=100", url)
        jq = command[command.index("--jq") + 1]
        self.assertIn("select(.merged_at != null)", jq)
        self.assertIn("baseRefName: .base.ref", jq)
        self.assertIn('"MERGED"', jq)

    def test_paginated_fetch_invokes_a_real_gh_argv_and_parses_two_records(self):
        # Drive the actual subprocess boundary with a fake gh executable: no
        # network, but the argv and the multi-page JSON-lines contract are real.
        root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, root)
        bin_dir = root / "bin"
        bin_dir.mkdir()
        args_log = root / "argv.log"
        records = [
            {"number": 7, "title": "X-7: one", "baseRefName": "main", "state": "MERGED"},
            {"number": 8, "title": "X-8: two", "baseRefName": "main", "state": "MERGED"},
        ]
        fake = bin_dir / "gh"
        fake.write_text(
            "#!/usr/bin/env bash\n"
            f"printf '%s\\n' \"$@\" >> {shlex.quote(str(args_log))}\n"
            "printf '%s\\n' "
            + " ".join(shlex.quote(json.dumps(r)) for r in records)
            + "\n"
        )
        fake.chmod(0o755)
        with mock.patch.dict(os.environ, {"PATH": f"{bin_dir}:{os.environ['PATH']}"}):
            merged = swarm.paginated_merged_pull_requests("main")
        self.assertEqual([r["number"] for r in merged], [7, 8])
        argv = args_log.read_text().splitlines()
        self.assertEqual(argv[:3], [
            "api", "--paginate",
            f"repos/{swarm.repo_slug()}/pulls?state=closed&base=main&per_page=100",
        ])
        self.assertIn("--jq", argv)
        self.assertNotIn("--slurp", argv)

    def test_paginated_stream_rejects_a_non_dict_record(self):
        path = self.write("one.md", "- [ ] CW-001 · one\n")
        with mock.patch.object(subprocess, "check_output", return_value=json.dumps([1, 2])), \
                mock.patch.object(swarm, "git") as git:
            with self.assertRaises(swarm.ReconciliationError):
                swarm.reconcile_tasks(directory=str(self.dir), persist=True)
        git.assert_not_called()
        self.assertEqual(path.read_text(), "- [ ] CW-001 · one\n")

    def test_paginated_stream_rejects_trailing_garbage(self):
        path = self.write("one.md", "- [ ] CW-001 · one\n")
        record = {"number": 7, "title": "CW-001: one", "baseRefName": "main",
                  "state": "MERGED"}
        stream = json.dumps(record) + "\nnonsense\n"
        with mock.patch.object(subprocess, "check_output", return_value=stream), \
                mock.patch.object(swarm, "git") as git:
            with self.assertRaises(swarm.ReconciliationError):
                swarm.reconcile_tasks(directory=str(self.dir), persist=True)
        git.assert_not_called()
        self.assertEqual(path.read_text(), "- [ ] CW-001 · one\n")

    def test_empty_paginated_stream_is_a_valid_empty_batch(self):
        path = self.write("one.md", "- [ ] CW-001 · one\n")
        with mock.patch.object(subprocess, "check_output", return_value=""), \
                mock.patch.object(swarm, "git") as git, \
                mock.patch.object(swarm, "emit") as emitted:
            count = swarm.reconcile_tasks(directory=str(self.dir), persist=True)
        self.assertEqual(count, 0)
        emitted.assert_not_called()
        git.assert_not_called()
        self.assertEqual(path.read_text(), "- [ ] CW-001 · one\n")

    def test_non_list_envelope_fails_before_write(self):
        path = self.write("one.md", "- [ ] CW-001 · one\n")
        with mock.patch.object(subprocess, "check_output",
                               return_value=json.dumps({"items": []})), \
                mock.patch.object(swarm, "git") as git:
            with self.assertRaises(swarm.ReconciliationError):
                swarm.reconcile_tasks(directory=str(self.dir), file=str(path), persist=True)
        git.assert_not_called()
        self.assertEqual(path.read_text(), "- [ ] CW-001 · one\n")

    def test_duplicate_pr_number_fails_before_write(self):
        path = self.write("one.md", "- [ ] CW-001 · one\n")
        merged = [
            {"number": 7, "title": "CW-001: one", "baseRefName": "main", "state": "MERGED"},
            {"number": 7, "title": "CW-002: other", "baseRefName": "main", "state": "MERGED"},
        ]
        with mock.patch.object(subprocess, "check_output", return_value=json.dumps(merged)), \
                mock.patch.object(swarm, "git") as git:
            with self.assertRaises(swarm.ReconciliationError):
                swarm.reconcile_tasks(directory=str(self.dir), file=str(path), persist=True)
        git.assert_not_called()
        self.assertEqual(path.read_text(), "- [ ] CW-001 · one\n")

    def test_ambiguous_task_id_without_explicit_pr_stays_open_with_a_warning(self):
        path = self.write("one.md", "- [ ] CW-001 · one\n")
        merged = [
            {"number": 7, "title": "CW-001: first merge", "baseRefName": "main", "state": "MERGED"},
            {"number": 8, "title": "CW-001: verification merge", "baseRefName": "main", "state": "MERGED"},
        ]
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            count = self.reconcile(merged, names=("one.md",))
        self.assertEqual(count, 0)
        self.assertEqual(path.read_text(), "- [ ] CW-001 · one\n")
        self.assertIn("CW-001", stderr.getvalue())
        self.assertIn("7", stderr.getvalue())
        self.assertIn("8", stderr.getvalue())

    def test_ambiguous_task_id_with_explicit_pr_closes_that_exact_merge(self):
        path = self.write("one.md", "- [ ] CW-001 · one\n  - pr: #8\n")
        merged = [
            {"number": 7, "title": "CW-001: first merge", "baseRefName": "main", "state": "MERGED"},
            {"number": 8, "title": "CW-001: verification merge", "baseRefName": "main", "state": "MERGED"},
        ]
        count = self.reconcile(merged, names=("one.md",))
        self.assertEqual(count, 1)
        self.assertIn("- [x] CW-001", path.read_text())
        self.assertIn("PR #8", path.read_text())

    def test_duplicate_task_file_or_repeated_pr_field_fails_before_write(self):
        merged = [{"number": 7, "title": "CW-001: one", "baseRefName": "main", "state": "MERGED"}]
        first = self.write("first.md", "- [ ] CW-001 · one\n")
        second = self.write("second.md", "- [ ] CW-001 · one\n")
        with mock.patch.object(subprocess, "check_output", return_value=json.dumps(merged)), \
                mock.patch("glob.glob", return_value=[str(first), str(second)]), \
                mock.patch.object(swarm, "git") as git:
            with self.assertRaises(swarm.ReconciliationError):
                swarm.reconcile_tasks(directory=str(self.dir), persist=True)
        git.assert_not_called()
        self.assertEqual(first.read_text(), "- [ ] CW-001 · one\n")
        self.assertEqual(second.read_text(), "- [ ] CW-001 · one\n")
        repeated = self.write("repeated.md", "- [ ] CW-001 · one\n  - pr: #7\n  - pr: #7\n")
        with mock.patch.object(subprocess, "check_output", return_value=json.dumps(merged)), \
                mock.patch.object(swarm, "git") as git:
            with self.assertRaises(swarm.ReconciliationError):
                swarm.reconcile_tasks(directory=str(self.dir), file=str(repeated), persist=True)
        git.assert_not_called()
        self.assertIn("  - pr: #7\n  - pr: #7\n", repeated.read_text())

    def test_malformed_or_unmatched_explicit_pr_fails_before_write(self):
        merged = [{"number": 7, "title": "CW-001: one", "baseRefName": "main", "state": "MERGED"}]
        for field in ("invalid", "#99"):
            with self.subTest(field=field):
                path = self.write("one.md", f"- [ ] CW-001 · one\n  - pr: {field}\n")
                with mock.patch.object(subprocess, "check_output", return_value=json.dumps(merged)), \
                        mock.patch.object(swarm, "git") as git:
                    with self.assertRaises(swarm.ReconciliationError):
                        swarm.reconcile_tasks(directory=str(self.dir), file=str(path), persist=True)
                git.assert_not_called()
                self.assertEqual(path.read_text(), f"- [ ] CW-001 · one\n  - pr: {field}\n")

    def test_wrong_base_or_reused_task_evidence_fails_before_write(self):
        path = self.write("one.md", "- [ ] CW-001 · one\n  - pr: #7\n")
        cases = (
            [{"number": 7, "title": "CW-001: one", "baseRefName": "develop", "state": "MERGED"}],
            [{"number": 7, "title": "OTHER: one", "baseRefName": "main", "state": "MERGED"}],
            [{"number": 7, "title": "CW-001: one", "baseRefName": "main", "state": "OPEN"}],
        )
        for merged in cases:
            with self.subTest(merged=merged):
                with mock.patch.object(subprocess, "check_output", return_value=json.dumps(merged)), \
                     mock.patch.object(swarm, "git") as git:
                    with self.assertRaises(swarm.ReconciliationError):
                        swarm.reconcile_tasks(directory=str(self.dir), file=str(path), persist=True)
                git.assert_not_called()
                self.assertEqual(path.read_text(), "- [ ] CW-001 · one\n  - pr: #7\n")

    def test_reconciliation_write_failure_returns_nonzero(self):
        path = self.write("one.md", "- [ ] CW-001 · one\n")
        merged = [{"number": 7, "title": "CW-001: one", "baseRefName": "main", "state": "MERGED"}]
        stream = "\n".join(json.dumps(item) for item in merged)
        with mock.patch.object(subprocess, "check_output", return_value=stream), \
             mock.patch.object(swarm, "git", side_effect=subprocess.CalledProcessError(1, ("git",))), \
             mock.patch.dict(os.environ, {"GH_TOKEN": "t"}):
            with self.assertRaises(subprocess.CalledProcessError):
                swarm.reconcile_tasks(directory=str(self.dir), file=str(path), persist=True)


class DraftTriageCoverageTests(unittest.TestCase):
    """Count stale draft pull requests and open one triage issue when needed."""

    def drafts(self):
        """Five PRs, one per skip path plus one genuine stale draft."""
        def stamp(minutes):
            return (datetime.datetime.now(datetime.timezone.utc)
                    - datetime.timedelta(minutes=minutes)).strftime("%Y-%m-%dT%H:%M:%SZ")
        return [
            {"number": 1, "title": "not a draft", "isDraft": False, "createdAt": stamp(500)},
            {"number": 2, "title": "no timestamp", "isDraft": True, "createdAt": None},
            {"number": 3, "title": "bad timestamp", "isDraft": True, "createdAt": "not a date"},
            {"number": 4, "title": "fresh draft", "isDraft": True, "createdAt": stamp(1)},
            {"number": 5, "title": "stale draft", "isDraft": True, "createdAt": stamp(500)},
        ]

    def test_no_run_history_does_not_open_an_issue(self):
        # An empty or absent run list means nothing to triage; the registry, not
        # a missing Actions history, is what decides image readiness elsewhere.
        with mock.patch.object(swarm, "gh_json", return_value=None), \
                mock.patch.object(swarm, "gh") as gh:
            self.assertEqual(swarm.draft_triage(), 0)
        gh.assert_not_called()

    def test_only_old_drafts_count_and_trigger_an_issue(self):
        # Non-drafts, undated, malformed and fresh drafts are all skipped; the
        # stale one is listed in the single issue the plan step opens.
        with mock.patch.object(swarm, "gh_json", return_value=self.drafts()), \
                mock.patch.object(swarm, "ensure_labels"), \
                mock.patch.object(swarm, "gh") as gh:
            self.assertEqual(swarm.draft_triage(threshold=0), 1)
        gh.assert_called_once()
        self.assertEqual(gh.call_args.args[:2], ("issue", "create"))
        self.assertIn("- #5 stale draft", " ".join(map(str, gh.call_args.args)))

    def test_every_off_value_stops_the_issue(self):
        for value in ("off", "0", "false", "no"):
            with mock.patch.object(swarm, "gh_json", return_value=self.drafts()), \
                    mock.patch.dict(os.environ, {"SWARM_ISSUES": value}), \
                    mock.patch.object(swarm, "gh") as gh:
                self.assertEqual(swarm.draft_triage(threshold=0), 1)
            gh.assert_not_called()

    def test_the_kill_switch_stops_the_issue(self):
        # A disabled issue integration still counts the drafts but must not
        # write to GitHub, so the plan step stays a pure observation.
        with mock.patch.object(swarm, "gh_json", return_value=self.drafts()), \
                mock.patch.dict(os.environ, {"SWARM_ISSUES": "off"}), \
                mock.patch.object(swarm, "gh") as gh:
            self.assertEqual(swarm.draft_triage(threshold=0), 1)
        gh.assert_not_called()


class PendingNotesTests(unittest.TestCase):
    def test_old_worker_notes_without_a_daily_note_are_pending(self):
        with tempfile.TemporaryDirectory() as tmp:
            Path(tmp, "T-1-20261122-101010.md").write_text("lesson")
            now = datetime.datetime(2026, 11, 23, 12, 0,
                                    tzinfo=datetime.timezone.utc)
            with mock.patch.object(swarm, "NOTES_DIR", tmp), \
                 mock.patch.object(swarm, "new_york_now", return_value=now), \
                 mock.patch.object(swarm, "referenced_notes", return_value=set()):
                self.assertTrue(swarm.pending_note_days())

    def test_a_reconciled_day_and_current_notes_are_not_pending(self):
        with tempfile.TemporaryDirectory() as tmp:
            Path(tmp, "T-1-20261122-101010.md").write_text("lesson")
            Path(tmp, "2026-11-22.md").write_text("already reconciled")
            Path(tmp, "T-2-20261123-101010.md").write_text("today")
            now = datetime.datetime(2026, 11, 23, 12, 0,
                                    tzinfo=datetime.timezone.utc)
            with mock.patch.object(swarm, "NOTES_DIR", tmp), \
                 mock.patch.object(swarm, "new_york_now", return_value=now), \
                 mock.patch.object(swarm, "referenced_notes", return_value=set()):
                self.assertFalse(swarm.pending_note_days())

    def test_pending_notes_admit_the_daily_workflow(self):
        with mock.patch.object(swarm, "main_is_red", return_value=False), \
                mock.patch.object(swarm, "active_runs", return_value=False), \
                mock.patch.object(swarm, "pending_note_days", return_value=True), \
                mock.patch.object(swarm, "live_workers", return_value=[]) as live:
            self.assertTrue(swarm.daily_can_start())
        live.assert_not_called()


if __name__ == "__main__":
    unittest.main()
