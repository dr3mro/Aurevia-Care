- [-] SWARM-WAVE-LIMITS · One swarm wave per branch and seven workers per wave
  - agent: orca
  - requirements: OPS-06
  - scope: .github/workflows/swarm.yml, scripts/swarm.py, scripts/tests/test_swarm.py, scripts/tests/test_workflow_concurrency.py, docs/SWARM.md
  - details: Owner directive 2026-11-24: at most one running swarm wave per branch, and at most seven workers per wave. Keep queued runs waiting (never cancel a running wave), keep the global capacity ledger and branch protection unchanged, and update tests/documentation intentionally because the wave size changed. No live GitHub verification in this session.

## Loaded skills
- aurevia-delivery, aurevia-github-workflow, aurevia-senior-python, aurevia-test-engineer, aurevia-plan-reviewer, aurevia-code-reviewer, aurevia-coverage-auditor, aurevia-devsecops.

## Plan (revised after plan-reviewer NO-GO)
1. Red tests first, then edit the workflow and the planner. Concurrency: replace `group: swarm-${{ github.run_id }}` with `group: swarm-${{ github.ref }}`, keep `cancel-in-progress: false` and add `queue: max` so a second wave waits rather than being dropped. Add a workflow test asserting all three keys and no `github.run_id`. All dispatches use `--ref main` and push/schedule target main; document that `workflow_run` resolves the base ref, so the group is branch-scoped.
2. Wave size: introduce a named `WAVE_WORKERS = 7` cap, set `STORY_WAVE_WORKERS = 7`, change the workflow input default, description and `WORKERS` fallback to `7`, and set `max-parallel: 7`. Clamp any `inputs.workers` request above 7 to 7 in `scheduler_room`/plan path with a red test proving a requested 20 yields 7. Keep `MAX_ACTIVE_WORKERS = 20` as the global cap across waves; do not change `capacity=20`, `scripts/monitor.py:318`, or the global-cap assertions.
3. Intentional test changes (state them in evidence): `test_twenty_story_workers_per_wave` → seven; `test_all_twenty_workers_can_use_ten_accounts_with_unique_tasks` → seven unique ids/slots 1–7; `test_wave_runs_twenty_workers_and_each_publishes_on_its_own` → `'7'`/`max-parallel: 7`; rename the overlap test to the branch-queue contract. Update `scripts/tests/test_workflow_concurrency.py` expectations for the new group. No assertion is deleted to make the change pass.
4. Deadlock check: with `queue: max`, `waiting_waves()` counts the queued wave, so `top_up` and `continue` must not dispatch another; add a test/reasoned assertion that the queued wave is eventually started by its own run and no deadlock results. Reconcile the `now=true` and `ignore_red_main` input descriptions with the branch queue: they relax scheduling gates, not the one-wave-per-branch group.
5. Documentation: update `docs/SWARM.md:3,9,198` and `swarm.yml:2-3,45-47` comments plus input line 14; remove the contradictory "three tasks per wave and six waves at once" text. Record the throughput tradeoff (20→7, one wave per branch) and rollback in a worker notes file, not in the task file. Live `queue: max` behaviour cannot be proven without GitHub; flag that residual risk.
6. Gates: targeted `test_swarm.py` and `test_workflow_concurrency.py`, full Python suite, `bash scripts/python-coverage.sh`, `actionlint`, `shellcheck`, `check-docs.py`, `check-constraints.py`, `check-names.py`, `check-pack.py`, `gen-opencode.py --check`, `check-ownership.py`. Record exact final lines. This PR touches protected `.github/` and `scripts/`, so integrator publication holds `needs-human`; say so in evidence.

## Evidence log
- (tool) Red: new assertions for `WAVE_WORKERS`, `clamp_wave_workers` and the branch-scoped workflow failed before the change (`AttributeError`, missing `group: swarm-${{ github.ref }}`). Green after: `test_swarm.py` `Ran 365 tests in 12.937s`, `OK`; `test_workflow_concurrency.py` 9/9 OK.
- (tool) Full suite: `python3 -I -m unittest discover -s scripts/tests -q` → `Ran 1864 tests in 109.550s`, `OK`.
- (tool) `bash scripts/python-coverage.sh` → `python coverage: every changed script line is covered`; all policy checks exit 0.
- (tool) `actionlint --version` 1.7.12 rejects the `queue:` key in this and pre-existing workflows (`delivery.yml:24`, `swarm-integrator.yml:33`, `swarm.yml:53,66`). This is an installed-actionlint limitation, not a new workflow syntax error; live GitHub validation remains a trusted-integrator hand-off. No shell script changed, so shellcheck is not applicable.
- (human) Owner directive recorded in `MEMORY.md`; no simulated user or human pilot feedback beyond that directive.

## Notes
Worker hand-off notes: `.agents/notes/01310000-20261124.md` includes the tradeoff (20→7 workers, one wave per branch) and rollback (restore per-run group and 20-worker defaults). This PR touches protected `.github/` and `scripts/`, so integrator publication holds `needs-human`.

## Review verdicts
- aurevia-plan-reviewer: GO (first NO-GO with nine Required gaps; revised plan re-reviewed GO).
- (pending) aurevia-code-reviewer: —
- (pending) aurevia-coverage-auditor: —
- (pending) aurevia-devsecops: —
