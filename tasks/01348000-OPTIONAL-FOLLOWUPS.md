- [x] OPTIONAL-FOLLOWUPS · Close the remaining non-gating review findings
  - agent: orca
  - attempts: 0
  - pr: #1800
  - requirements: OPS-06
  - scope: scripts/swarm.py, scripts/tests/test_swarm_schedule_coverage.py, scripts/tests/test_swarm_integrator_coverage.py, scripts/tests/test_notes_files.py, scripts/agent-run.sh, .agents/notes/01343000-20261127.md, tasks/01348000-OPTIONAL-FOLLOWUPS.md
  - evidence:
    Test evidence (tool): targeted modules `test_swarm_schedule_coverage` 32 OK, `test_swarm_integrator_coverage` 71 OK, `test_notes_files` 6 OK, `test_agent_run` 162 OK, `test_workflow_concurrency` 28 OK; full `python3 -I -m unittest discover -s scripts/tests -q` -> `Ran 2457 tests ... OK`; `COVERAGE_BASE=1e6e33be4a7b5b230a12d4be6a650b0277574fc0 REQUIRE_PY_COVERAGE=1 bash scripts/python-coverage.sh` -> `python coverage: every changed script line is covered`; `shellcheck scripts/agent-run.sh` exit 0; actionlint reports only the known pre-existing `queue: max` warning.
    Tested revision: 79ff1cdf0be035ed40c41b79166bcfde904fc93a (HEAD when checks ran).
    Coverage: empty result, real argv/two-page stream, single push assertion, dated archive shape each have a named test.
    Review verdicts: code-reviewer GO, coverage-auditor GO, devsecops GO.
    Final re-run after two Optional wording fixes: 2457 tests OK, coverage gate exit 0.
  - details:
    Worktree `.workspaces/unfixed-optional`, branch `fix/unfixed-optional`. Base is a local
    merge of PR heads #1796, #1795, #1797, #1798 and #1799; merged tree passed
    `Ran 2456 tests ... OK` before these edits.
    Remaining review findings addressed:
    - D: test name now says dated shape, not zip only.
    - Integrator: stale `test_third_push_failure_restores_records_and_stops` renamed to
      `test_base_advance_restores_records_and_emits_the_retry_signal`, and the assertion
      now counts only actual push calls (exactly one), not push+fetch.
    - E: header comment now documents `TRIAGE_IN`.
    - C: empty `gh api` output is a valid empty batch; the fail-closed tests now cover
      malformed records instead. A fake `gh` executable test exercises the real argv and
      two-page JSON-lines parsing without network access.
    No threshold, exclusion, skip or suppression was added.
