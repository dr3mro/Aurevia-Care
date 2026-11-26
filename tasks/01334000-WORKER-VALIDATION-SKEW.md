- [-] WORKER-VALIDATION-SKEW · Validate a merged candidate so main-only tests cannot reject an older bundle
  - agent: orca
  - scope: .github/workflows/swarm-worker.yml, scripts/swarm.py, scripts/tests/test_swarm.py, scripts/tests/test_swarm_cli_coverage.py, tasks/01334000-WORKER-VALIDATION-SKEW.md
  - details:
    Run 45837146301, task AUD-20261122-19, candidate 43a54efa was rejected by the tokenless validate job. The job overlays current main scripts onto the older candidate worktree, so a test added to main after the worker started failed against the candidate's older workflow file. Fix: merge fetched origin/main into the candidate tree before the overlay and re-copy trusted scripts; on merge conflict, report stale=true with a ::notice:: and do not consume an attempt. Make failures visible with a bounded grep of validate.log, PYTHONUNBUFFERED=1 and the first failing test name in the job summary. Check no other workflow has the same overlay skew. Keep candidate scripts unexecuted and never run candidate workflow files.
  - evidence:
    Skills loaded by matched clause: aurevia-delivery (delivery loop), aurevia-debugger (failing gate), aurevia-github-workflow (workflow change), aurevia-senior-python (swarm.py helper and CLI), aurevia-senior-shell (workflow shell).

    Red first (tool): four structural tests added to `scripts/tests/test_swarm.py::WorkerValidationSkewTests`; `/opt/homebrew/bin/python3.15 -I -m unittest discover -s scripts/tests -p test_swarm.py -q` -> `FAILED (failures=3, errors=1)` before the workflow change, with `PYTHONUNBUFFERED: 1` and `git -C candidate-tree merge --no-edit origin/main` absent.

    Fix (tool): the validate job fetches `origin/main`, merges it into `candidate-tree` before `cp -R scripts/.`, and handles a conflict with `::notice::`, `stale=true`, `passed=false` and a clean exit; the gate step skips on stale, sets `PYTHONUNBUFFERED: 1`, prints the bounded failure grep and writes the first failing line to `$GITHUB_STEP_SUMMARY`. The job exposes `stale`, and the publish job notices stale and calls the trusted `python3 -I scripts/swarm.py stale-retry --task <id>`; `stale_retry()` releases the claim and resets attempts to zero. `rg` over all workflows found this overlay only in `swarm-worker.yml`, so `swarm-integrator.yml` needed no change.

    Green (tool): targeted `test_swarm.py` 375 tests OK, `test_swarm_cli_coverage.py` 66 tests OK, `test_delivery_workflow_gates.py` 23 tests OK. actionlint (queue ignore), check-pack, check-docs, check-secrets, check-constraints --require-base, check-wiring, gen-opencode --check, check-names and `git diff --check` each exit 0. Existing `test_worker_validate_skips_a_no_change_result_but_fails_a_missing_bundle` was updated on purpose: the gate condition now also skips a stale candidate; its no-change and missing-bundle assertions are unchanged.

    Files changed: `.github/workflows/swarm-worker.yml`, `scripts/swarm.py`, `scripts/tests/test_swarm.py`, `scripts/tests/test_swarm_cli_coverage.py`, `tasks/01334000-WORKER-VALIDATION-SKEW.md`.

    Branch -> test: stale_retry edit action (release, reset attempts, keep details, message) -> `test_stale_retry_releases_the_claim_and_resets_the_attempt`; CLI parser and true dispatch arm -> `test_main_dispatches_stale_retry`; false dispatch arm -> the other `MainDispatchCoverageTests` dispatch tests; workflow merge-before-overlay -> `test_validate_merges_main_before_the_trusted_scripts_overlay`; stale conflict handling -> `test_merge_conflict_is_reported_as_stale_not_as_a_code_defect`; visible failure output -> `test_failure_output_is_visible_and_the_first_failing_test_is_summarised`; publish stale path -> `test_publish_keeps_a_stale_candidate_retryable_without_an_attempt`.

  - review verdicts (read-only subagents, agent file IDs):
    - coverage-auditor: GO (0 Critical, 0 Required, 1 Optional: full-suite/coverage evidence pending — every new production line and branch has a named asserting test).
    - devsecops: GO (0 Critical, 0 Required, 3 Optional: clarify that main's gate scripts are overlaid rather than claiming no candidate code runs, bound raw `tail` output, and mask the pre-existing commits annotation — no trust boundary weakened).
    - github-workflow: GO (0 Critical, 0 Required, 1 Optional: the extra unauthenticated `origin` fetch is redundant with `fetch-depth: 0` and would fail for a private repo; public repo makes it harmless today).
    - code-reviewer: NO-GO then GO (Required fixed: the merge now passes explicit `-c user.name`/`-c user.email` from top-level repository variables, so an identity failure cannot be misreported as stale; the workflow test asserts both flags).

    Unverified: the workflow cannot run locally; no live run has exercised a stale merge yet. Full Python suite and coverage gate are pending in the orchestrator.
