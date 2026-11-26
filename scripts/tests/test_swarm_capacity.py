"""Bounded-wave admission and configured capacity limits are fail-closed."""
import json
import os
import subprocess
import unittest
from unittest import mock

from helpers import load

swarm = load("swarm")

TEXT = (
    "- [ ] A-1 · first\n  - scope: apps/a\n  - details: one\n\n"
    "- [ ] B-1 · second\n  - scope: apps/b\n  - details: two\n\n"
    "- [ ] C-1 · third\n  - scope: apps/c\n  - details: three\n"
)


def run(env=None, **kwargs):
    values = {"SWARM_MAX_TASK": "true", "SWARM_MAX_WORKERS_PER_WAVE": "2",
              "SWARM_MAX_WAVE_COUNT": "2"}
    values.update(env or {})
    with mock.patch.dict(os.environ, values):
        return swarm.plan_workers(4, [{"slot": 1}, {"slot": 2}, {"slot": 3}, {"slot": 4}],
                                  TEXT, [], **kwargs)


class WaveAdmissionTests(unittest.TestCase):
    def test_below_at_and_above_cap(self):
        runs = [{"databaseId": 1, "status": "in_progress"}]
        self.assertEqual(swarm.wave_admission(runs, max_waves=2), (True, 1, ""))
        runs.append({"databaseId": 2, "status": "queued"})
        self.assertEqual(swarm.wave_admission(runs, max_waves=2), (False, 2, "wave_cap"))

    def test_self_is_excluded_and_completed_runs_do_not_count(self):
        runs = [{"databaseId": 7, "status": "in_progress"},
                {"databaseId": 8, "status": "completed"}]
        self.assertEqual(swarm.wave_admission(runs, exclude_run_id="7", max_waves=1),
                         (True, 0, ""))

    def test_empty_and_unknown_status(self):
        self.assertEqual(swarm.wave_admission(None, max_waves=1), (True, 0, ""))
        runs = [{"databaseId": 9, "status": "unexpected"}]
        self.assertEqual(swarm.wave_admission(runs, max_waves=1), (False, 1, "wave_cap"))

    def test_malformed_snapshot_fails_closed(self):
        for runs in ([{}], ["bad"], [{"databaseId": 1}]):
            with self.subTest(runs=runs):
                with self.assertRaises(swarm.CapacityUnavailable):
                    swarm.wave_admission(runs, max_waves=2)

    def test_limit_comes_from_the_canonical_variable(self):
        runs = [{"databaseId": 1, "status": "in_progress"}]
        with mock.patch.dict(os.environ, {"SWARM_MAX_TASK": "true",
                                          "SWARM_MAX_WAVE_COUNT": "3"}):
            self.assertEqual(swarm.wave_admission(runs), (True, 1, ""))
        with mock.patch.dict(os.environ, {"SWARM_MAX_TASK": "false"}):
            self.assertEqual(swarm.wave_admission(runs), (False, 1, "wave_cap"))


class ActiveWavesTests(unittest.TestCase):
    def test_reads_one_checked_actions_snapshot(self):
        payload = json.dumps([{"databaseId": 1, "status": "in_progress"}])
        with mock.patch.object(swarm, "gh", return_value=payload) as call:
            with mock.patch.dict(os.environ, {"SWARM_MAX_TASK": "true",
                                              "SWARM_MAX_WAVE_COUNT": "2"}):
                result = swarm.active_waves("1")
        self.assertEqual(result, (True, 0, ""))
        self.assertIn("swarm.yml", call.call_args.args)
        self.assertIn("databaseId,status", call.call_args.args)

    def test_api_json_and_shape_errors_fail_closed(self):
        for effect in (subprocess.CalledProcessError(1, "gh"), OSError("offline"),
                       ValueError("bad json"), "{}", '"not-a-list"'):
            with self.subTest(effect=effect):
                if isinstance(effect, Exception):
                    patcher = mock.patch.object(swarm, "gh", side_effect=effect)
                else:
                    patcher = mock.patch.object(swarm, "gh", return_value=effect)
                with patcher:
                    with self.assertRaises(swarm.CapacityUnavailable):
                        swarm.active_waves()


class PlannerCapTests(unittest.TestCase):
    def test_bounded_default_plans_one_and_holds_while_taken(self):
        with mock.patch.dict(os.environ, {"SWARM_MAX_TASK": "false"}):
            self.assertEqual(len(swarm.plan_workers(4, [{"slot": 1}], TEXT, [])), 1)
            taken = TEXT.replace("- [ ] A-1", "- [-] A-1")
            self.assertEqual(swarm.plan_workers(1, [{"slot": 1}], taken, []), [])

    def test_configured_count_and_multiple_focus_ids(self):
        self.assertEqual([item["id"] for item in run(task_count=2)], ["A-1", "B-1"])
        self.assertEqual([item["id"] for item in run(focus="C-1,B-1", task_count=2)],
                         ["B-1", "C-1"])

    def test_scheduler_room_honours_wave_cap(self):
        with mock.patch.dict(os.environ, {"SWARM_MAX_TASK": "true",
                                          "SWARM_MAX_WAVE_COUNT": "2"}):
            self.assertEqual(swarm.scheduler_room(20, 0, waves=2), 0)
            self.assertEqual(swarm.scheduler_room(20, 0, waves=1), 20)
            self.assertEqual(swarm.scheduler_room(20, 18, force=True, waves=2), 0)

    def test_cli_skips_a_wave_when_capacity_or_wave_count_is_unavailable(self):
        for effect, reason in ((swarm.CapacityUnavailable("api"), "capacity_unavailable"),
                               (None, "wave_cap")):
            with self.subTest(reason=reason):
                with mock.patch.object(swarm, "gh_json", return_value=[]), \
                     mock.patch.object(swarm, "live_workers", return_value=[]), \
                     mock.patch.object(swarm, "todo_read", return_value=TEXT), \
                     mock.patch.object(swarm, "reclaim", return_value=TEXT), \
                     mock.patch.object(swarm, "load_slots", return_value=[{"slot": 1}]), \
                     mock.patch.object(swarm, "active_waves",
                                      side_effect=effect if effect else None,
                                      return_value=None if effect else (False, 2, reason)), \
                     mock.patch.object(swarm, "emit") as emitted, \
                     mock.patch("builtins.print"):
                    self.assertEqual(swarm.main(["plan-workers"]), 0)
                emitted.assert_any_call("reason", reason)
                emitted.assert_any_call("count", 0)

    def test_top_up_refuses_when_the_wave_cap_is_reached(self):
        env = {"SWARM_MAX_TASK": "true", "SWARM_MAX_WAVE_COUNT": "2"}
        with mock.patch.dict(os.environ, env), \
             mock.patch.object(swarm, "live_workers", return_value=[]), \
             mock.patch.object(swarm, "active_waves", return_value=(False, 2, "wave_cap")):
            self.assertFalse(swarm.top_up("A-1"))



class WiringAssertionTests(unittest.TestCase):
    def test_cli_passes_canonical_count_and_waves_to_planner(self):
        env = {"SWARM_MAX_TASK": "true", "SWARM_MAX_WORKERS_PER_WAVE": "2"}
        with mock.patch.dict(os.environ, env), \
             mock.patch.object(swarm, "gh_json", return_value=[]), \
             mock.patch.object(swarm, "live_workers", return_value=[]), \
             mock.patch.object(swarm, "active_waves", return_value=(True, 1, "")), \
             mock.patch.object(swarm, "waiting_waves", return_value=0), \
             mock.patch.object(swarm, "todo_read", return_value=TEXT), \
             mock.patch.object(swarm, "reclaim", return_value=TEXT), \
             mock.patch.object(swarm, "load_slots", return_value=[{"slot": 1}]), \
             mock.patch.object(swarm, "scheduler_room", return_value=2) as room, \
             mock.patch.object(swarm, "plan_workers", return_value=[]) as planned, \
             mock.patch.object(swarm, "update_todo"), mock.patch.object(swarm, "note_claims"), \
             mock.patch.object(swarm, "emit"), mock.patch("builtins.print"):
            swarm.main(["plan-workers", "--max", "9"])
        self.assertEqual(planned.call_args.kwargs["task_count"], 2)
        self.assertEqual(room.call_args.args[3], 1)

    def test_continue_swarm_uses_configured_count_and_slots(self):
        env = {"SWARM_MAX_TASK": "true", "SWARM_MAX_WORKERS_PER_WAVE": "2"}
        with mock.patch.dict(os.environ, env), \
             mock.patch.object(swarm, "plan_workers", return_value=[{"id": "A-1"}]) as planned:
            self.assertTrue(swarm.continue_swarm(TEXT, []))
        self.assertEqual(planned.call_args.args[0], 2)
        self.assertEqual(len(planned.call_args.args[1]), 2)

    def test_clamp_uses_configured_cap(self):
        with mock.patch.dict(os.environ, {"SWARM_MAX_TASK": "true",
                                          "SWARM_MAX_WORKERS_PER_WAVE": "2"}):
            self.assertEqual(swarm.clamp_wave_workers(20), 2)

    def test_wave_count_defaults_and_invalid_fail_closed(self):
        with mock.patch.dict(os.environ, {"SWARM_MAX_TASK": "true"}):
            os.environ.pop("SWARM_MAX_WAVE_COUNT", None)
            self.assertEqual(swarm.concurrent_wave_limit(), 3)
        with mock.patch.dict(os.environ, {"SWARM_MAX_TASK": "true",
                                          "SWARM_MAX_WAVE_COUNT": "bad"}):
            self.assertEqual(swarm.concurrent_wave_limit(), 1)

    def test_top_up_fails_closed_when_wave_snapshot_errors(self):
        env = {"SWARM_MAX_TASK": "true"}
        with mock.patch.dict(os.environ, env), \
             mock.patch.object(swarm, "live_workers", return_value=[]), \
             mock.patch.object(swarm, "active_waves",
                               side_effect=swarm.CapacityUnavailable("api")):
            self.assertFalse(swarm.top_up("A-1"))

    def test_self_exclusion_keeps_other_active_runs(self):
        runs = [{"databaseId": 7, "status": "in_progress"},
                {"databaseId": 8, "status": "in_progress"}]
        self.assertEqual(swarm.wave_admission(runs, exclude_run_id="7", max_waves=2),
                         (True, 1, ""))


class BoundedModeTests(unittest.TestCase):
    def test_bounded_cli_forces_one_worker(self):
        with mock.patch.dict(os.environ, {"SWARM_MAX_TASK": "false"}), \
             mock.patch.object(swarm, "gh_json", return_value=[]), \
             mock.patch.object(swarm, "live_workers", return_value=[]), \
             mock.patch.object(swarm, "active_waves", return_value=(True, 0, "")), \
             mock.patch.object(swarm, "waiting_waves", return_value=0), \
             mock.patch.object(swarm, "todo_read", return_value=TEXT), \
             mock.patch.object(swarm, "reclaim", return_value=TEXT), \
             mock.patch.object(swarm, "load_slots", return_value=[{"slot": 1}]), \
             mock.patch.object(swarm, "scheduler_room", return_value=1) as room, \
             mock.patch.object(swarm, "plan_workers", return_value=[]) as planned, \
             mock.patch.object(swarm, "update_todo"), mock.patch.object(swarm, "note_claims"), \
             mock.patch.object(swarm, "emit"), mock.patch("builtins.print"):
            swarm.main(["plan-workers", "--max", "20"])
            self.assertEqual(swarm.clamp_wave_workers(20), 1)
        self.assertEqual(planned.call_args.kwargs["task_count"], 1)

    def test_focus_ids_are_urgent_even_with_a_smaller_story_wave(self):
        include = run(focus="B-1,C-1", story_wave=1, task_count=3)
        self.assertEqual([item["id"] for item in include], ["B-1", "C-1", "A-1"])

    def test_bounded_mode_holds_with_an_open_pr(self):
        with mock.patch.dict(os.environ, {"SWARM_MAX_TASK": "false"}):
            prs = [{"number": 9, "title": "A-1", "headRefName": "x",
                    "labels": [], "isCrossRepository": False, "comments": []}]
            self.assertEqual(swarm.plan_workers(1, [{"slot": 1}], TEXT, prs), [])


if __name__ == "__main__":
    unittest.main()
