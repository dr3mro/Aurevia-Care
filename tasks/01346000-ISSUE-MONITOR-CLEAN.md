- [-] ISSUE-MONITOR-CLEAN · Keep triage artifacts out of the checked-out worktree
  - agent: orca
  - attempts: 0
  - requirements: OPS-06
  - scope: .github/workflows/swarm-issue-monitor.yml, scripts/agent-run.sh, scripts/tests/test_agent_run.py, scripts/tests/test_workflow_concurrency.py, tasks/01346000-ISSUE-MONITOR-CLEAN.md
  - evidence:
    Test evidence (tool, Python 3.14 venv): targeted `python3 -I -m unittest discover -s scripts/tests -p test_agent_run.py` -> `Ran 160 tests ... OK`; `-p test_workflow_concurrency.py` -> `Ran 25 tests ... OK`; `actionlint .github/workflows/swarm-issue-monitor.yml` and `shellcheck scripts/agent-run.sh` exit 0. After cherry-picking D's test fix, full `python3 -I -m unittest discover -s scripts/tests -q` -> `Ran 2433 tests ... OK`; `COVERAGE_BASE=eaf8b5e REQUIRE_PY_COVERAGE=1 bash scripts/python-coverage.sh` -> `python coverage: every changed script line is covered`.
    Tested revision: 1c707db07cd780a08209a99e5290ee7f4a740a3a (HEAD when final checks ran).
    Coverage: `cmd_triage` reads `${TRIAGE_IN}` from an external path, asserted by `test_triage_reads_issue_files_from_an_external_directory`; CR-delimited `##[`/`::` markers are split and quoted in `neutralise_log_lines`, asserted by `test_carriage_return_markers_are_neutralised_in_agent_log`; workflow paths asserted by `IssueMonitorWorkflowTest`.
    Review verdicts (after evidence sections were filled):
    - code-reviewer: GO
    - coverage-auditor: GO (after three Required branch-test gaps were fixed)
    - devsecops: GO
  - details:
    Worktree `.workspaces/issue-monitor-clean`, branch `fix/issue-monitor-clean`, base `eaf8b5e`.
    Eight Swarm issue monitor runs (45961293757, 45961295008, 45961295921, 45961295644,
    45961460703, 45961461054, 45961459677, 45961460090) failed the tokenless
    "Triage worker / Triage the issues" step. Tool evidence: step ends with
    `refusing to run in a dirty workspace; commit or stash unrelated work first`, exit 1;
    .github/workflows/swarm-issue-monitor.yml downloads `monitor-selection` into `monitor/`
    and copies `triage-in/` inside the checkout; scripts/agent-run.sh `cmd_triage` calls
    `require_clean_worktree` before any agent runs, and both paths are unignored.
    Fix: place those artifacts under `${{ runner.temp }}` and pass explicit paths into
    the triage profile, keeping the clean-worktree guard intact. Also route agent output
    through `neutralise_log_lines` on the stream path so CR-delimited workflow markers
    cannot bypass quoting. Add red tests for dirty-worktree acceptance of external paths
    and for CR markers in agent output. No gate is skipped or weakened.
