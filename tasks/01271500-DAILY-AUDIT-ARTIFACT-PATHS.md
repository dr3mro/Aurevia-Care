- [ ] DAILY-AUDIT-ARTIFACT-PATHS · Use `.agents/` for daily and scheduled audit artifacts
  - branch: main
  - scope: .github/workflows/daily-audit.yml, .github/workflows/daily-qa.yml, .github/workflows/daily-devsecops.yml, .github/workflows/swarm-strategist.yml, scripts/agent-run.sh, scripts/tests/test_agent_run.py, scripts/tests/test_swarm.py
  - details: Direct user directive (2026-11-24): "use for daily or scheduled audits .agents". Scheduled read-only audit/review commands (`audit`, `daily-qa`, `daily-devsecops`, `strategy`) now set their scratch root to `.agents/` instead of `.agent/`; the four workflows upload the published JSON from the new root. `implement`, `debug`, `triage`, `reconcile` and other swarm commands keep `.agent/`. Notes and reports under `.agents/notes/` and `.agents/reports/` belong to task 01271000 and were not touched here; `.gitignore` is owned by that task.

## Change
- `scripts/agent-run.sh`: subcommand-based `out` selection (`audit|daily-qa|daily-devsecops|strategy) out=.agents ;; *) out=.agent ;;`) plus refreshed subcommand header comments. All scratch (`context-files.txt`, logs, `result`, `notes-stamp`, findings JSON) follows `out`.
- Upload paths: `daily-audit.yml` `.agents/audit-findings.json`, `daily-qa.yml` `.agents/daily-qa.json`, `daily-devsecops.yml` `.agents/daily-devsecops.json`, `swarm-strategist.yml` `.agents/strategy.json`. Download paths (`findings/`, `review/`) unchanged.
- Tests: `test_swarm.py::AuditTests::test_scheduled_reviews_publish_their_findings_from_agents` asserts the four upload paths; `test_agent_run.py` asserts all four audit subcommands create and use `.agents/` and that `implement` still uses `.agent/`.

## Test evidence (tool)
- `bash -n scripts/agent-run.sh` → `syntax OK`.
- `shellcheck scripts/agent-run.sh` → `shellcheck OK`.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_agent_run.py' -q` → `Ran 97 tests in 97.200s ... OK`.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm.py' -q` → 1 failure in `PlanTests.test_waves_overlap_but_planning_stays_serial` (`github.run_id` not in the concurrency group), caused by the concurrent commit 511c1ca9 that changed every workflow's concurrency group; my new test passes.
- `python3 -I scripts/check-wiring.py` → exit 0; `python3 -I scripts/check-constraints.py` → exit 0; `python3 -I scripts/check-pack.py` → `Delivery tooling validated: 39 agents, 39 skills`.
- `python3 -I scripts/check-docs.py` → exit 1 on `.agents/notes/PRINT-04-20261121-155152.md` (a migrated note), owned by task 01271000.
- `python3 -I -m unittest discover -s scripts/tests -q` → `Ran 1582 tests ... FAILED (failures=30)`: notes-migration and workflow-concurrency assertions from concurrent in-flight commits 0601c196 / 511c1ca9, none named a file or test in this scope.

## Coverage
- The only production change is a Bash `case`: all four audit arms are exercised by `test_scheduled_reviews_write_their_artifacts_under_agents` and the default arm by `test_worker_commands_keep_the_agent_scratch_directory` (plus the pre-existing `implement` tests). No new Python production line or branch, so `scripts/python-coverage.sh` adds nothing to cover.

## Review verdicts (reviewer)
- aurevia-senior-shell: GO (bounded plan; Critical warning about a blanket `.gitignore` rule was adopted — no `.gitignore` edit in this task).

## Findings
- Concurrent sessions committed parts of this change while it was in progress (commits 6d2e0eef, 0601c196, 511c1ca9). The two `test_agent_run.py` regression tests were overwritten by 0601c196 and were re-added to the worktree afterwards; verify they are present before merging.
- `.gitignore` deliberately has no `.agents/` rule: task 01271000 tracks notes and reports under `.agents/`. Audit scratch is transient in CI and no audit job commits.
- Full-suite failures are from concurrent work, not this change; re-run gates after the other sessions settle. No push or `gh` call was made by this session.
