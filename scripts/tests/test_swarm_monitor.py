import datetime
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from helpers import load
from test_swarm_issues import Gh, TODO, issue

swarm = load("swarm")

NOW = datetime.datetime(2026, 11, 20, 12, 0, tzinfo=datetime.timezone.utc)


def aged(number, minutes_created, minutes_updated=None, body="", labels=("swarm-issue", "agent-fixable")):
    def stamp(m):
        return (NOW - datetime.timedelta(minutes=m)).strftime("%Y-%m-%dT%H:%M:%SZ")
    found = issue(number, body, labels=labels)
    found["created_at"] = stamp(minutes_created)
    found["updated_at"] = stamp(minutes_created if minutes_updated is None else minutes_updated)
    return found


def comment(body):
    return {"body": body, "user": {"login": "github-actions[bot]"}, "author_association": "NONE"}


def select(mode, fake, text=TODO, live=()):
    out = tempfile.mkdtemp()
    with mock.patch.object(swarm, "gh", fake), mock.patch.object(swarm, "live_workers", lambda: list(live)):
        return swarm.monitor_select(mode, text, out, now=NOW), out


class SelectTests(unittest.TestCase):
    def test_now_takes_an_unassigned_issue_after_five_minutes_not_an_hour(self):
        chosen, _ = select("now", Gh(issues=[aged(5, 10), aged(6, 2)]))
        self.assertEqual(chosen["triage"], [5])
        chosen, _ = select("hourly", Gh(issues=[aged(5, 10)]))
        self.assertEqual(chosen["triage"], [])

    def test_now_still_skips_issues_a_worker_has_had(self):
        fake = Gh(issues=[aged(3, 30)], comments=[comment("<!-- swarm-claim:CW-001 -->\nWorker 1 received")])
        chosen, _ = select("now", fake)
        self.assertEqual(chosen["triage"], [])

    def test_hourly_takes_an_issue_nobody_was_assigned_for_an_hour(self):
        chosen, out = select("hourly", Gh(issues=[aged(5, 90)]))
        self.assertEqual(chosen["triage"], [5])
        self.assertIn("Issue #5", (Path(out) / "issue-5.md").read_text())

    def test_hourly_skips_new_issues_unlabelled_ones_and_assigned_ones(self):
        fake = Gh(issues=[aged(1, 20), aged(2, 90, labels=("swarm-issue",)), aged(3, 90)],
                  comments=[comment("<!-- swarm-claim:CW-001 -->\nWorker 1 received")])
        chosen, _ = select("hourly", fake)
        self.assertEqual(chosen["triage"], [])

    def test_a_stranger_cannot_get_an_issue_picked(self):
        stranger = aged(4, 90)
        stranger["user"] = {"login": "stranger"}
        chosen, _ = select("hourly", Gh(issues=[stranger]))
        self.assertEqual(chosen["triage"], [])

    def test_daily_takes_issues_without_progress_for_a_day(self):
        fake = Gh(issues=[aged(1, 3000, 1500), aged(2, 3000, 100)])
        chosen, _ = select("daily", fake)
        self.assertEqual(chosen["triage"], [1])

    def test_daily_requeues_the_pull_request_of_a_stale_issue(self):
        fake = Gh(issues=[aged(1, 3000, 1500, body="<!-- swarm-pr:77 -->")],
                  pr_info={"state": "OPEN", "labels": [{"name": "needs-human"}]})
        chosen, _ = select("daily", fake)
        self.assertEqual(chosen["prs"], [(1, 77)])
        fake = Gh(issues=[aged(1, 3000, 1500, body="<!-- swarm-pr:77 -->")],
                  pr_info={"state": "OPEN", "labels": [], "isDraft": True})
        chosen, _ = select("daily", fake)
        self.assertEqual(chosen["prs"], [(1, 77)])

    def test_a_healthy_pull_request_waiting_for_the_integrator_is_left_alone(self):
        fake = Gh(issues=[aged(1, 3000, 1500, body="<!-- swarm-pr:77 -->")],
                  pr_info={"state": "OPEN", "labels": [{"name": "swarm"}], "isDraft": False})
        chosen, _ = select("daily", fake)
        self.assertEqual(chosen["prs"], [])

    def test_the_pull_request_a_worker_named_in_a_comment_counts(self):
        fake = Gh(issues=[aged(5, 3000, 1500)], comments=[comment("<!-- swarm-pr:91 -->\nfinished a pass")],
                  pr_info={"state": "MERGED", "labels": []})
        chosen, _ = select("daily", fake, TODO + "\n- [-] ISS-5 · Fix it now\n  - attempts: 1\n  - scope: a\n")
        self.assertEqual(chosen["close"], [5])

    def test_a_protected_pull_request_is_not_sent_to_fix_workers(self):
        fake = Gh(issues=[aged(1, 3000, 1500, body="<!-- swarm-pr:77 -->")],
                  pr_info={"state": "OPEN", "labels": [{"name": "needs-review"}]})
        chosen, _ = select("daily", fake)
        self.assertEqual(chosen["prs"], [])

    def test_a_pull_request_with_protected_files_is_left_for_a_person(self):
        stuck = issue(9, "<!-- swarm-pr:77 -->", labels=("swarm-issue", "needs-review"))
        fake = Gh(issues=[aged(1, 3000, 1500, body="<!-- swarm-pr:77 -->"), stuck],
                  pr_info={"state": "OPEN", "labels": [{"name": "needs-human"}]})
        chosen, _ = select("daily", fake)
        self.assertEqual(chosen["prs"], [])

    def test_a_pull_request_with_too_many_files_is_left_for_a_person(self):
        stuck = issue(9, "<!-- swarm-pr:77 -->", labels=("swarm-issue", "needs-human"))
        stuck["title"] = "PR #77 needs a person: too many files"
        fake = Gh(issues=[aged(1, 3000, 1500, body="<!-- swarm-pr:77 -->"), stuck],
                  pr_info={"state": "OPEN", "labels": [{"name": "needs-human"}]})
        chosen, _ = select("daily", fake)
        self.assertEqual(chosen["prs"], [])

    def test_a_merged_pull_request_closes_its_issue(self):
        fake = Gh(issues=[aged(1, 3000, 1500, body="<!-- swarm-pr:77 -->")], pr_info={"state": "MERGED", "labels": []})
        chosen, _ = select("daily", fake)
        self.assertEqual(chosen["close"], [1])

    def test_daily_nudges_a_task_issue_and_stops_after_the_attempts_are_used(self):
        text = TODO + "\n- [ ] ISS-6 · Fix it\n  - attempts: 0\n  - scope: apps/api/x\n"
        chosen, _ = select("daily", Gh(issues=[aged(6, 3000, 1500)]), text)
        self.assertEqual(chosen["nudge"], [6])
        used = text.replace("attempts: 0", f"attempts: {swarm.MAX_ATTEMPTS}")
        chosen, _ = select("daily", Gh(issues=[aged(6, 3000, 1500)]), used)
        self.assertEqual(chosen["reset"], [(6, "ISS-6")])
        chosen, _ = select("hourly", Gh(issues=[aged(6, 90)]), used)
        self.assertEqual(chosen["reset"], [])

    def test_a_handoff_for_a_stuck_story_task_gets_its_attempts_back(self):
        used = TODO.replace("  - scope: apps/api/staff", f"  - attempts: {swarm.MAX_ATTEMPTS}\n  - scope: apps/api/staff")
        fake = Gh(issues=[aged(2, 3000, 1500, body="<!-- swarm-task:CW-001 --><!-- swarm-pr:none -->")])
        chosen, _ = select("daily", fake, used)
        self.assertEqual(chosen["reset"], [(2, "CW-001")])

    def test_daily_gives_up_after_two_requeues(self):
        fake = Gh(issues=[aged(1, 3000, 1500, body="<!-- swarm-pr:77 -->")],
                  comments=[comment("<!-- swarm-requeue -->"), comment("<!-- swarm-requeue -->")])
        chosen, _ = select("daily", fake)
        self.assertEqual(chosen["exhausted"], [1])
        self.assertEqual(chosen["prs"], [])

    def test_a_triaged_issue_is_not_nudged_by_the_next_hourly_run(self):
        fake = Gh(issues=[aged(5, 90)], comments=[comment("<!-- swarm-assigned -->\nTriage done")])
        chosen, _ = select("hourly", fake, TODO + "\n- [ ] ISS-5 · Fix it now\n  - attempts: 0\n  - scope: a\n")
        self.assertEqual(chosen, {"triage": [], "prs": [], "nudge": [], "reset": [], "close": [], "exhausted": []})

    def test_delivery_failures_are_left_to_the_repair_tasks(self):
        chosen, _ = select("daily", Gh(issues=[aged(1, 3000, 1500, body="<!-- swarm-workflow:delivery -->")]))
        self.assertEqual(chosen["triage"], [])

    def test_an_issue_whose_task_is_done_is_closed(self):
        text = TODO + "\n- [x] ISS-6 · Fixed already\n  - attempts: 1\n  - scope: a\n"
        chosen, _ = select("daily", Gh(issues=[aged(6, 3000, 1500)]), text)
        self.assertEqual(chosen["close"], [6])
        self.assertEqual(chosen["triage"], [])

    def test_a_live_worker_on_the_task_is_left_alone(self):
        fake = Gh(issues=[aged(1, 3000, 1500, body="<!-- swarm-task:CW-001 -->")])
        chosen, _ = select("daily", fake, live=["CW-001"])
        self.assertEqual(chosen["nudge"], [])

    def test_at_most_three_issues_per_run_and_five_open_issue_tasks(self):
        fake = Gh(issues=[aged(n, 3000, 1500) for n in range(1, 8)])
        chosen, _ = select("daily", fake)
        self.assertEqual(len(chosen["triage"]), 3)
        text = TODO + "".join(f"\n- [ ] ISS-{n} · Fix {n} now\n  - attempts: 0\n  - scope: a\n" for n in range(50, 55))
        chosen, _ = select("daily", Gh(issues=[aged(1, 3000, 1500)]), text)
        self.assertEqual(chosen["triage"], [])


class TriageTests(unittest.TestCase):
    good = {"title": "Reject expired tokens in the staff API", "scope": ["apps/api/staff"],
            "plan": "Expired tokens pass.\nAdd a failing test first."}

    def test_a_good_answer_becomes_a_task_entry(self):
        entry = swarm.triage_entry(5, self.good, TODO)
        self.assertIn("- [ ] ISS-5 · Reject expired tokens", entry)
        self.assertIn("scope: apps/api/staff", entry)
        tasks = swarm.parse_tasks("## T\n\n" + entry + "\n")
        self.assertEqual(tasks[0]["id"], "ISS-5")
        self.assertEqual(tasks[0]["scope"], ["apps/api/staff"])

    def test_unsafe_or_incomplete_answers_are_refused(self):
        for bad in ({}, {**self.good, "scope": [".github/workflows"]}, {**self.good, "scope": ["scripts"]},
                    {**self.good, "scope": ["../etc"]}, {**self.good, "scope": ["/abs"]},
                    {**self.good, "scope": []}, {**self.good, "scope": ["a"] * 5},
                    {**self.good, "title": "short"}, {**self.good, "title": "x" * 200},
                    {**self.good, "title": "two\nlines here ok"}, {**self.good, "plan": "  "},
                    {**self.good, "scope": "apps/api"}, {**self.good, "scope": ["."]},
                    {**self.good, "scope": ["./"]}, {**self.good, "scope": ["-rf"]},
                    {**self.good, "scope": ["a b"]}, "text"):
            self.assertIsNone(swarm.triage_entry(5, bad, TODO), bad)

    def test_agent_text_cannot_break_the_docs_check_on_main(self):
        root = Path(__file__).resolve().parents[2]
        cwd = os.getcwd()
        os.chdir(root)
        try:
            entry = swarm.triage_entry(5, {**self.good, "plan": "See [the guide](missing/guide.md) and "
                                           "[site](https://x.org). Cites IAM-99 and ARC-01."}, TODO)
        finally:
            os.chdir(cwd)
        self.assertIn("the guide (missing/guide.md)", entry)
        self.assertIn("[site](https://x.org)", entry)
        self.assertIn("IAM 99", entry)
        self.assertIn("ARC-01", entry)

    def test_agent_text_cannot_trip_the_secret_check_on_main(self):
        jwt = "eyJ" + "a" * 12 + ".eyJ" + "b" * 12 + "." + "c" * 12
        assignment = "pass" + "word = 'hunter2hunter2'"  # built at runtime so the repo scan stays clean
        entry = swarm.triage_entry(5, {**self.good, "plan": f"Fixture token {jwt} and {assignment}"}, TODO)
        self.assertNotIn(jwt, entry)
        self.assertNotIn("hunter2hunter2", entry)
        self.assertIn("[redacted]", entry)

    def test_audit_findings_use_the_same_scope_rules(self):
        finding = {"title": "Reject expired tokens in the staff API", "details": "x", "scope": ["."]}
        self.assertEqual(swarm.audit_entries([finding], TODO, "20261120"), [])

    def test_one_task_per_issue(self):
        text = TODO + "\n- [ ] ISS-5 · Already there\n  - attempts: 0\n  - scope: a\n"
        self.assertIsNone(swarm.triage_entry(5, self.good, text))


class AssignTests(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.todo = self.dir / "TODO.md"
        self.todo.write_text(TODO)
        self.fake = Gh(runs=[])
        self.cwd = os.getcwd()

    def run_assign(self, **kw):
        def update(edit, message, path="TODO.md"):
            self.todo.write_text(edit(self.todo.read_text()))
        with mock.patch.object(swarm, "gh", self.fake), mock.patch.object(swarm, "update_todo", update), \
                mock.patch.object(swarm, "waiting_waves", lambda: 0):
            kw.setdefault("triaged", [5])
            return swarm.monitor_assign(self.dir / "triage", todo=str(self.todo), **kw)

    def test_an_answer_adds_the_task_comments_and_starts_a_wave(self):
        (self.dir / "triage").mkdir()
        (self.dir / "triage" / "triage-5.json").write_text(json.dumps(TriageTests.good))
        result = self.run_assign()
        self.assertEqual(result["tasks"], [5])
        text = self.todo.read_text()
        self.assertIn("## Issue fixes", text)
        self.assertIn("ISS-5 · Reject expired tokens", text)
        self.assertTrue(self.fake.said("issue", "comment", "5", "Triage done"))
        self.assertTrue(self.fake.said("workflow", "run", "swarm.yml"))

    def test_running_twice_does_not_duplicate_the_task(self):
        (self.dir / "triage").mkdir()
        (self.dir / "triage" / "triage-5.json").write_text(json.dumps(TriageTests.good))
        self.run_assign()
        self.run_assign()
        self.assertEqual(self.todo.read_text().count("ISS-5 ·"), 1)

    def test_an_unusable_answer_sends_the_issue_to_a_person(self):
        (self.dir / "triage").mkdir()
        (self.dir / "triage" / "triage-5.json").write_text("{}")
        result = self.run_assign()
        self.assertEqual(result["failed"], [5])
        self.assertEqual(self.todo.read_text(), TODO)
        self.assertTrue(self.fake.said("needs-human"))
        self.assertFalse(self.fake.said("workflow", "run"))

    def test_a_forged_answer_for_an_issue_that_was_not_selected_is_ignored(self):
        (self.dir / "triage").mkdir()
        (self.dir / "triage" / "triage-999.json").write_text(json.dumps(TriageTests.good))
        result = self.run_assign(triaged=[])
        self.assertEqual(result["tasks"], [])
        self.assertNotIn("ISS-999", self.todo.read_text())

    def test_no_answer_counts_as_a_requeue_and_starts_nothing(self):
        result = self.run_assign()
        self.assertEqual(result["unanswered"], [5])
        self.assertTrue(self.fake.said("issue", "comment", "5", "swarm-requeue"))
        self.assertFalse(self.fake.said("workflow", "run"))

    def test_a_pull_request_goes_back_to_the_fix_workers(self):
        result = self.run_assign(prs=[(1, 77)])
        self.assertEqual(result["prs"], [1])
        self.assertTrue(self.fake.said("needs-fix"))
        self.assertTrue(self.fake.said("issue", "comment", "1", "swarm-requeue"))

    def test_a_reset_gives_the_attempts_back_and_starts_a_wave(self):
        self.todo.write_text(TODO.replace("  - scope: apps/api/staff", f"  - attempts: {swarm.MAX_ATTEMPTS}\n  - scope: apps/api/staff"))
        result = self.run_assign(triaged=[], reset=[(2, "CW-001")])
        self.assertEqual(result["reset"], [2])
        self.assertEqual(swarm.attempts_of(swarm.parse_tasks(self.todo.read_text())[0]), 0)
        self.assertTrue(self.fake.said("issue", "comment", "2", "swarm-requeue", "fresh round"))
        self.assertTrue(self.fake.said("workflow", "run", "swarm.yml"))

    def test_close_closes_the_issue(self):
        self.run_assign(triaged=[], close=[4])
        self.assertTrue(self.fake.said("issue", "close", "4"))

    def test_new_tasks_land_in_their_section_not_under_done(self):
        self.todo.write_text(TODO + "\n## Done\n\n- [x] OLD-1 · Old work\n")
        (self.dir / "triage").mkdir()
        (self.dir / "triage" / "triage-5.json").write_text(json.dumps(TriageTests.good))
        self.run_assign()
        text = self.todo.read_text()
        self.assertLess(text.index("ISS-5"), text.index("## Done"))
        self.assertLess(text.index("## Issue fixes"), text.index("## Done"))
        self.assertEqual([t["id"] for t in swarm.parse_tasks(text)], ["CW-001", "ISS-5", "OLD-1"])

    def test_exhausted_issues_need_a_person(self):
        result = self.run_assign(exhausted=[3])
        self.assertEqual(result["exhausted"], [3])
        self.assertTrue(self.fake.said("needs-human"))
        self.assertFalse(self.fake.said("workflow", "run"))


class SectionTests(unittest.TestCase):
    def test_entries_go_to_the_end_of_an_existing_section(self):
        text = "# T\n\n## A\n\n- [ ] A-1 · a\n\n## B\n\n- [ ] B-1 · b\n"
        out = swarm.add_under(text, "## A", ["- [ ] A-2 · new"])
        self.assertEqual(out, "# T\n\n## A\n\n- [ ] A-1 · a\n- [ ] A-2 · new\n\n## B\n\n- [ ] B-1 · b\n")

    def test_a_missing_section_goes_at_the_end_without_a_done_section(self):
        out = swarm.add_under("# T\n", "## A", ["- [ ] A-1 · a"])
        self.assertEqual(out, "# T\n\n## A\n\n- [ ] A-1 · a\n")


class CliTests(unittest.TestCase):
    def test_select_writes_the_selection_even_when_nothing_needs_triage(self):
        out = Path(tempfile.mkdtemp()) / "monitor"
        todo = Path(tempfile.mkdtemp()) / "TODO.md"
        todo.write_text(TODO)
        import contextlib
        import io
        with mock.patch.object(swarm, "gh", Gh()), mock.patch.object(swarm, "live_workers", lambda: []), \
                mock.patch.dict(os.environ, {"GITHUB_OUTPUT": str(out.parent / "o")}), \
                contextlib.redirect_stdout(io.StringIO()):
            swarm.main(["monitor-select", "--mode", "hourly", "--out", str(out), "--file", str(todo)])
        self.assertEqual(json.loads((out / "selection.json").read_text())["triage"], [])


class TokenTests(unittest.TestCase):
    def test_issue_calls_use_the_jobs_own_token_when_given(self):
        seen = []

        def run(cmd, capture_output, text, env=None):
            seen.append((cmd[1], (env or os.environ).get("GH_TOKEN")))
            return swarm.subprocess.CompletedProcess(cmd, 0, "", "")
        with mock.patch.object(swarm.subprocess, "run", run), \
                mock.patch.dict(os.environ, {"GH_TOKEN": "pat", "GH_ISSUES_TOKEN": "job"}):
            swarm.gh("issue", "comment", "5", "--body", "x")
            swarm.gh("api", "--method", "POST", "repos/o/r/issues/7/labels", "-f", "labels[]=a")
            swarm.gh("pr", "create", "--title", "t")
            swarm.gh("api", "repos/o/r/pulls/7")
        self.assertEqual(seen, [("issue", "job"), ("api", "job"), ("pr", "pat"), ("api", "pat")])

    def test_without_an_issues_token_every_call_uses_gh_token(self):
        self.assertFalse(swarm.is_issue_call(("pr", "view", "1")))
        self.assertTrue(swarm.is_issue_call(("label", "create", "x")))


class BestEffortTests(unittest.TestCase):
    def test_an_api_error_in_issue_reporting_never_fails_the_caller(self):
        def broken(*args, check=True):
            raise swarm.subprocess.CalledProcessError(1, args, "", "API rate limit exceeded")
        with mock.patch.object(swarm, "gh", broken):
            self.assertIsNone(swarm.note_claims([{"id": "CW-001", "slot": 1}]) or None)
            self.assertEqual(swarm.report_pr_stuck(7, "cause", "detail"), "")

    def test_claims_fetch_the_issue_list_once(self):
        fake = Gh(issues=[issue(1, "<!-- swarm-task:CW-001 -->"), issue(2, "<!-- swarm-task:CW-002 -->")])
        with mock.patch.object(swarm, "gh", fake):
            swarm.note_claims([{"id": "CW-001", "slot": 1}, {"id": "CW-002", "slot": 2}, {"id": "CW-003", "slot": 3}])
        lists = [c for c in fake.calls if c[0] == "api" and "issues?state=open" in c[-1]]
        self.assertEqual(len(lists), 1)
        self.assertTrue(fake.said("issue", "comment", "2", "Worker 2"))

    def test_the_monitor_reads_each_issues_comments_once(self):
        fake = Gh(issues=[aged(5, 90)])
        select("hourly", fake)
        reads = [c for c in fake.calls if c[0] == "api" and c[-1].endswith("/issues/5/comments?per_page=100")]
        self.assertEqual(len(reads), 1)


class IssueTaskTests(unittest.TestCase):
    def test_status_lines_do_not_push_findings_out_of_the_context(self):
        comments = [comment("finding A: the token check is missing"), comment("<!-- swarm-claim:ISS-5 -->\nreceived"),
                    comment("<!-- swarm-requeue -->\nwave started"), comment("<!-- swarm-claim:ISS-5 -->\nreceived"),
                    comment("<!-- swarm-assigned -->\ntriage done")]
        out = Path(tempfile.mkdtemp()) / "ctx.md"
        with mock.patch.object(swarm, "gh", Gh(issues=[issue(5, "body")], comments=comments)):
            swarm.issue_context("ISS-5", str(out))
        text = out.read_text()
        self.assertIn("finding A", text)
        self.assertNotIn("wave started", text)

    def test_the_issue_closes_when_the_task_merges(self):
        fake = Gh()
        with mock.patch.object(swarm, "gh", fake):
            swarm.close_merged_issues([(88, "ISS-5"), (89, "CW-001")])
        closes = [c for c in fake.calls if c[:2] == ("issue", "close")]
        self.assertEqual([c[2] for c in closes], ["5"])

    def test_a_claim_on_an_issue_task_is_commented_on_the_issue_itself(self):
        fake = Gh(issues=[issue(5, "")])
        with mock.patch.object(swarm, "gh", fake):
            swarm.note_claims([{"id": "ISS-5", "slot": 2}])
        self.assertTrue(fake.said("issue", "comment", "5", "Worker 2", "received", "swarm-claim:ISS-5"))

    def test_context_for_an_issue_task_reads_that_issue(self):
        fake = Gh(issues=[issue(5, "evidence here")], comments=[comment("earlier finding")])
        out = Path(tempfile.mkdtemp()) / "ctx.md"
        with mock.patch.object(swarm, "gh", fake):
            self.assertEqual(swarm.issue_context("ISS-5", str(out)), 1)
        self.assertIn("earlier finding", out.read_text())

    def test_a_clean_finish_posts_the_findings_and_a_stop_comments_on_it(self):
        task = {"id": "ISS-5", "kind": "story", "slot": 1}
        work = Path(tempfile.mkdtemp())
        (work / "checks").write_text("passed")
        (work / "merge").write_text("merged")
        (work / "publish-notes.md").write_text("# Notes\nRoot cause: token expiry not checked.")
        fake = Gh(issues=[issue(5, "")])
        with mock.patch.object(swarm, "gh", fake):
            swarm.note_worker_outcome(task, "ok", str(work), "88")
        comments = [c for c in fake.calls if c[:3] == ("issue", "comment", "5")]
        self.assertEqual(len(comments), 1)
        self.assertIn("<!-- swarm-pr:88 -->", comments[0][-1])
        self.assertIn("Root cause: token expiry not checked.", comments[0][-1])
        self.assertFalse([c for c in fake.calls if c[:2] == ("issue", "close")])
        fake = Gh(issues=[issue(5, "")])
        with mock.patch.object(swarm, "gh", fake):
            swarm.note_worker_outcome(task, "failed", str(work), "")
        self.assertTrue(fake.said("issue", "comment", "5", "stopped before finishing"))
        self.assertFalse(fake.said("issue", "create"))


class NotesTests(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.notes = self.dir / "pr-notes"
        self.notes.mkdir()

    def test_the_day_is_the_new_york_day_of_the_utc_stamp(self):
        self.assertEqual(swarm.note_day("CW-1-20261120-023000.md"), "2026-11-19")
        self.assertEqual(swarm.note_day("CW-1-20261120-153000.md"), "2026-11-20")
        self.assertEqual(swarm.note_day("old.md", "2026-11-18T10:00:00+00:00"), "2026-11-18")
        self.assertEqual(swarm.note_day("old.md", ""), "")

    def test_only_finished_days_are_collected_and_daily_files_are_left_alone(self):
        for name in ("A-20261119-150000.md", "B-20261119-160000.md", "C-20261120-150000.md", "2026-11-18.md"):
            (self.notes / name).write_text(f"note {name}\n")
        days = swarm.notes_collect(self.dir / "in", str(self.notes), today="2026-11-20", keep=set())
        self.assertEqual(days, ["2026-11-19"])
        files = (self.dir / "in" / "2026-11-19.files").read_text().split()
        self.assertEqual(files, ["A-20261119-150000.md", "B-20261119-160000.md"])
        self.assertIn("note A-20261119-150000.md", (self.dir / "in" / "2026-11-19.md").read_text())

    def test_notes_other_documents_point_to_stay_in_place(self):
        for name in ("REFERENCED.md", "A-20261119-150000.md"):
            (self.notes / name).write_text("x")
        days = swarm.notes_collect(self.dir / "in", str(self.notes), today="2026-11-20",
                                   added={"REFERENCED.md": "2026-11-19T10:00:00+00:00"}, keep={"REFERENCED.md"})
        self.assertEqual((self.dir / "in" / "2026-11-19.files").read_text().split(), ["A-20261119-150000.md"])
        self.assertEqual(days, ["2026-11-19"])

    def test_references_come_from_tracked_markdown_outside_notes_and_records(self):
        files = {"TODO.md": "Read first: `.agent/pr-notes/REFACTOR-PACKAGES.md`.",
                 "docs/swarm/X.md": "notes in .agent/pr-notes/OLD-20261119-010101.md",
                 ".agent/pr-notes/2026-11-19.md": "see .agent/pr-notes/SELF.md"}
        tmp = Path(tempfile.mkdtemp())
        for name, text in files.items():
            (tmp / name).parent.mkdir(parents=True, exist_ok=True)
            (tmp / name).write_text(text)
        cwd = os.getcwd()
        os.chdir(tmp)
        try:
            with mock.patch.object(swarm, "git", lambda *a, **k: "\n".join(files)):
                self.assertEqual(swarm.referenced_notes(), {"REFACTOR-PACKAGES.md"})
        finally:
            os.chdir(cwd)

    def test_a_night_reads_at_most_the_budget_and_leaves_the_rest_for_later(self):
        for i in range(30):
            (self.notes / f"A{i:02d}-20261119-150000.md").write_text("x" * 5000)
        with mock.patch.object(swarm, "NOTES_BUDGET", 20_000):
            swarm.notes_collect(self.dir / "in", str(self.notes), today="2026-11-20", keep=set())
        taken = (self.dir / "in" / "2026-11-19.files").read_text().split()
        self.assertEqual(len(taken), 4)
        self.assertLessEqual(len((self.dir / "in" / "2026-11-19.md").read_text()), 20_100)

    def test_a_busy_day_is_sampled_across_its_notes(self):
        for i in range(10):
            (self.notes / f"N{i:02d}-20261120-150000.md").write_text(f"note {i}")
        with mock.patch.object(swarm, "MAX_NOTE_FILES", 3):
            days = swarm.notes_collect(self.dir / "in", str(self.notes), today="2026-11-21", keep=set())
        self.assertEqual(days, ["2026-11-20"])
        taken = (self.dir / "in" / "2026-11-20.files").read_text().split()
        self.assertEqual(len(taken), 3)
        self.assertEqual(taken, ["N00-20261120-150000.md", "N03-20261120-150000.md",
                                 "N06-20261120-150000.md"])

    def test_the_newest_day_is_not_starved_by_an_older_busy_day(self):
        for i in range(10):
            (self.notes / f"OLD{i:02d}-20261119-150000.md").write_text("x" * 5000)
            (self.notes / f"NEW{i:02d}-20261120-150000.md").write_text("x" * 5000)
        with mock.patch.object(swarm, "NOTES_BUDGET", 20_000):
            days = swarm.notes_collect(self.dir / "in", str(self.notes), today="2026-11-21", keep=set())
        self.assertEqual(days, ["2026-11-20"])
        taken = (self.dir / "in" / "2026-11-20.files").read_text().split()
        self.assertTrue(all(name.startswith("NEW") for name in taken))

    def test_archive_zips_the_originals_and_removes_them(self):
        import zipfile
        (self.notes / "A.md").write_text("a")
        (self.notes / "B.md").write_text("b")
        removed = []
        with mock.patch.object(swarm, "git", lambda *a, **k: removed.append(a[-1]) or (self.notes / Path(a[-1]).name).unlink()):
            self.assertTrue(swarm.archive_day("2026-11-19", ["A.md", "B.md"], str(self.notes), str(self.dir / "zips")))
        self.assertEqual(sorted(zipfile.ZipFile(self.dir / "zips" / "2026-11-19.zip").namelist()), ["A.md", "B.md"])
        self.assertEqual(sorted(p.name for p in self.notes.iterdir()), [])
        (self.notes / "C.md").write_text("c")
        with mock.patch.object(swarm, "git", lambda *a, **k: (self.notes / Path(a[-1]).name).unlink()):
            swarm.archive_day("2026-11-19", ["C.md"], str(self.notes), str(self.dir / "zips"))
        self.assertEqual(len(zipfile.ZipFile(self.dir / "zips" / "2026-11-19.zip").namelist()), 3)

    def test_late_notes_are_appended_not_overwritten(self):
        archive = self.dir / "zips"
        (self.notes / "2026-11-19.md").write_text("first pass\n")
        swarm.store_daily("2026-11-19", "late pass", str(self.notes), str(archive))
        text = (self.notes / "2026-11-19.md").read_text()
        self.assertIn("first pass", text)
        self.assertIn("## Late notes\n\nlate pass", text)
        archive.mkdir()
        (archive / "2026-11-18.md").write_text("archived day\n")
        swarm.store_daily("2026-11-18", "later still", str(self.notes), str(archive))
        self.assertIn("later still", (archive / "2026-11-18.md").read_text())
        self.assertFalse((self.notes / "2026-11-18.md").exists())

    def test_only_the_newest_daily_file_stays(self):
        for name in ("2026-11-17.md", "2026-11-18.md", "2026-11-19.md", "X-20261119-010101.md"):
            (self.notes / name).write_text("x")
        moved = []
        with mock.patch.object(swarm, "git", lambda *a, **k: moved.append(Path(a[-2]).name)):
            swarm.retire_dailies(str(self.notes), str(self.dir / "zips"))
        self.assertEqual(moved, ["2026-11-17.md", "2026-11-18.md"])

    def test_push_main_commits_only_the_notes_paths_and_skips_an_empty_change(self):
        calls = []

        def git(*args, check=True):
            calls.append(args)
            return ""
        with mock.patch.object(swarm, "git", git), mock.patch.object(swarm, "authed_git", lambda *a, **k: ""):
            self.assertFalse(swarm.push_main("m", lambda: True))
        self.assertIn(("add", "-A", "--", swarm.NOTES_DIR, swarm.NOTES_ARCHIVE), calls)
        self.assertFalse(any(c[:1] == ("-c",) or "commit" in c for c in calls))

    def test_issue_proposals_are_validated_and_capped(self):
        good = {"title": "A real problem in the notes", "body": "evidence", "scope": ["apps/api"]}
        items = [good, {**good, "scope": [".github"]}, {**good, "title": "no"}, "x", {**good, "scope": []},
                 good, good, good]
        path = self.dir / "issues.json"
        path.write_text(json.dumps(items))
        found = swarm.notes_issue_entries(path)
        self.assertEqual(len(found), 3)
        path.write_text("not json")
        self.assertEqual(swarm.notes_issue_entries(path), [])

    def test_the_rules_patch_may_only_touch_instruction_documents(self):
        def patch(*paths):
            return "".join(f"diff --git a/{p} b/{p}\n--- a/{p}\n+++ b/{p}\n" for p in paths)
        self.assertTrue(swarm.rules_patch_ok(patch(".opencode/setup/AGENTS.md", ".opencode/agents/x.md", "docs/NOTES.md")))
        for bad in (patch("scripts/swarm.py"), patch(".opencode/setup/AGENTS.md", ".github/workflows/x.yml"),
                    patch("docs/archive/pr-notes/x.md"), patch("docs/sub/x.md"), patch("check.sh"), "",
                    patch(".opencode/setup/AGENTS.md") + "new file mode 120000\n",
                    patch(".opencode/setup/AGENTS.md") + "GIT binary patch\n",
                    patch(".opencode/setup/AGENTS.md") + "rename from AGENTS.md\n"):
            self.assertFalse(swarm.rules_patch_ok(bad), bad)

    def test_publish_stores_the_day_archives_and_opens_issues(self):
        collected, reconciled = self.dir / "in", self.dir / "out"
        collected.mkdir()
        reconciled.mkdir()
        (collected / "2026-11-19.files").write_text("A.md\n")
        (reconciled / "2026-11-19.md").write_text("# Notes 2026-11-19\n" + "Summary line.\n" * 30)
        (reconciled / "issues.json").write_text(json.dumps(
            [{"title": "A real problem in the notes", "body": "evidence", "scope": ["apps/api"]}]))
        (self.notes / "A.md").write_text("a")
        stored, opened = [], []

        def push(message, edit):
            edit()
            stored.append(message)
            return True
        store = swarm.store_daily  # the repository's own archive must not decide where the test writes
        with mock.patch.object(swarm, "push_main", push), mock.patch.object(swarm, "retire_dailies", lambda d: None), \
                mock.patch.object(swarm, "archive_day", lambda day, names, d: opened.append((day, names))), \
                mock.patch.object(swarm, "store_daily", lambda day, text, d: store(day, text, d, self.dir / "archive")), \
                mock.patch.object(swarm, "record_issue", lambda *a, **k: "9"), \
                mock.patch.object(swarm, "open_rules_pr", lambda *a: ""):
            result = swarm.notes_publish(collected, reconciled, str(self.notes))
        self.assertEqual(result, {"days": ["2026-11-19"], "issues": ["9"]})
        self.assertEqual(opened, [("2026-11-19", ["A.md"])])
        self.assertTrue((self.notes / "2026-11-19.md").read_text().startswith("# Notes 2026-11-19"))

    def test_the_notes_job_fails_visibly_when_nothing_was_reconciled(self):
        collected, reconciled = self.dir / "in", self.dir / "out"
        collected.mkdir()
        reconciled.mkdir()
        (collected / "2026-11-19.files").write_text("A.md\n")
        import contextlib
        import io
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(SystemExit) as stop:
            swarm.main(["notes-publish", "--collected", str(collected), "--reconciled", str(reconciled)])
        self.assertIn("no usable daily note", str(stop.exception.code))

    def test_a_too_short_reconciliation_keeps_the_originals(self):
        collected, reconciled = self.dir / "in", self.dir / "out"
        collected.mkdir()
        reconciled.mkdir()
        (collected / "2026-11-19.files").write_text("A.md\n")
        (reconciled / "2026-11-19.md").write_text("too short")
        with mock.patch.object(swarm, "push_main", lambda *a: self.fail("must not push")):
            self.assertEqual(swarm.notes_publish(collected, reconciled, str(self.notes)), {"days": [], "issues": []})


class WorkflowFileTests(unittest.TestCase):
    root = Path(__file__).resolve().parents[2] / ".github" / "workflows"

    def test_the_monitor_reacts_to_issues_runs_every_ten_minutes_and_at_two_am_new_york(self):
        text = (self.root / "swarm-issue-monitor.yml").read_text()
        for cron in ("'*/10 * * * *'", "'0 6 * * *'", "'0 7 * * *'"):
            self.assertIn(cron, text)
        self.assertIn("types: [opened, reopened, labeled]", text)
        self.assertIn("github.event_name == 'issues' && 'now'", text)
        self.assertIn("monitor-select", text)
        self.assertIn("monitor-assign", text)

    def test_agent_steps_never_hold_a_github_token(self):
        for name, command in (("swarm-issue-monitor.yml", "agent-run.sh triage"), ("daily-notes.yml", "agent-run.sh reconcile")):
            text = (self.root / name).read_text()
            step = text.split(command)[0].rsplit("- name:", 1)[1]
            self.assertNotIn("GH_TOKEN", step)
            self.assertNotIn("SWARM_TOKEN", step)

    def test_the_notes_publish_job_keeps_agent_files_out_of_the_checkout(self):
        text = (self.root / "daily-notes.yml").read_text()
        publish = text.split("  publish:", 1)[1]
        self.assertIn("path: ${{ runner.temp }}/notes-in", publish)
        self.assertIn("path: ${{ runner.temp }}/reconciled", publish)

    def test_the_notes_workflow_covers_both_new_york_offsets(self):
        text = (self.root / "daily-notes.yml").read_text()
        self.assertIn("'10 5 * * *'", text)
        self.assertIn("notes-publish", text)

    def test_each_daily_cron_runs_only_in_its_season_even_when_delayed(self):
        winter = datetime.datetime(2026, 12, 1, 7, 40, tzinfo=datetime.timezone.utc)
        summer = datetime.datetime(2026, 7, 1, 6, 50, tzinfo=datetime.timezone.utc)
        self.assertTrue(swarm.daily_slot_ok("0 7 * * *", winter))
        self.assertFalse(swarm.daily_slot_ok("0 6 * * *", winter))
        self.assertTrue(swarm.daily_slot_ok("0 6 * * *", summer))
        self.assertFalse(swarm.daily_slot_ok("0 7 * * *", summer))
        self.assertFalse(swarm.daily_slot_ok("7 * * * *", summer))

    def test_the_watchdog_reports_failures_of_the_new_workflows(self):
        text = (self.root / "swarm-watchdog.yml").read_text()
        self.assertIn("Swarm issue monitor", text)
        self.assertIn("Daily notes", text)

    def test_the_daily_guard_only_passes_at_two_am_new_york(self):
        winter = datetime.datetime(2026, 12, 1, 7, 0, tzinfo=datetime.timezone.utc)
        summer = datetime.datetime(2026, 7, 1, 6, 0, tzinfo=datetime.timezone.utc)
        self.assertEqual(swarm.new_york_hour(winter), 2)
        self.assertEqual(swarm.new_york_hour(summer), 2)
        self.assertNotEqual(swarm.new_york_hour(winter.replace(hour=6)), 2)


class DocsCheckTests(unittest.TestCase):
    def test_notes_and_archives_are_not_checked_as_docs(self):
        import importlib.util
        root = Path(__file__).resolve().parents[2]
        spec = importlib.util.spec_from_file_location("check_docs", root / "scripts" / "check-docs.py")
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        tmp = Path(tempfile.mkdtemp())
        for rel in (".agent/pr-notes/2026-11-19.md", "docs/archive/pr-notes/2026-11-18.md", "docs/guide.md"):
            (tmp / rel).parent.mkdir(parents=True, exist_ok=True)
            (tmp / rel).write_text("[x](missing.md)")
        self.assertEqual([p.relative_to(tmp).as_posix() for p in mod.markdown_files(tmp)], ["docs/guide.md"])


if __name__ == "__main__":
    unittest.main()
