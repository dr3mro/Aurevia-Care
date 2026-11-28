- [x] PUBLICATION-STATE-OUTPUT · Make integrator publication outcome explicit so a bounded base-advance retry is not a red step
  - agent: orca
  - attempts: 0
  - pr: #1837
  - requirements: OPS-06
  - scope: scripts/swarm.py, scripts/tests/test_swarm_cli_coverage.py, scripts/tests/test_swarm_integrator_coverage.py, scripts/tests/test_delivery_workflow_gates.py, scripts/tests/test_workflow_concurrency.py, scripts/tests/test_swarm.py, .github/workflows/swarm-integrator.yml, .github/workflows/swarm.yml, tasks/01358000-PUBLICATION-STATE-OUTPUT.md
  - evidence:
    Tested revision: 664bcdbc2067642d00bb7f2729c0f49982daeffa (HEAD when checks ran; PR head 672482f66 adds the notes file only).
    Skills loaded: aurevia-delivery (every story); aurevia-github-workflow (workflows); aurevia-senior-python (scripts/swarm.py); aurevia-test-engineer (red tests); aurevia-plan-reviewer (plan gate).
    Review verdicts (reviewer evidence):
    - plan-reviewer: GO (after the three Required corrections were applied: single emitter with exactly-one assertion, generic Exception test, workflow_call output expression)
    - coverage-auditor: GO (every new branch mapped to a named test; no skip, pragma, exclusion or weakened assertion)
    - devsecops: GO (state descriptive only; retry JSON unchanged byte-for-byte; equality gates fail closed; no candidate code in write-token job; caps intact). Its Optional stale comment at scripts/swarm.py:7083 was corrected in the same change.
    - code-reviewer: GO (closed enum, single emit, parenthesised expressions, no double-dispatch). Its Optional finding on the malformed `--recovery-attempt` guard was adopted: the guard now emits `failed` before raising, asserted by `test_main_caps_the_recovery_attempt`. The other Optional (pre-existing post-push comment failure) is unchanged and out of scope.
    - qa-reviewer: GO (independent matrix over all seven acceptance scenarios; no product defect). It noted that a fully skipped publish now stops the wave loop; this is the intended fail-closed tightening, with the hourly safety-net wave as the recovery path.
    Live CI (tool, `gh pr checks 1837 --watch --interval 30`) at PR head `672482f66d692302c4327f20a098718b36b345ea`: Delivery gate pass, Delivery tooling tests pass, Policy and tooling pass, Java and database verification pass, Frontend standards pass, Developer reference pass, Rebase fix/publication-state-output onto main pass, Swarm gate pass; release and image-smoke jobs correctly skipped for a PR. Run 46131026704. PR remains open for owner merge; no force push and no direct push to main.
    Test evidence (tool): RED first — `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm_cli_coverage.py'` gave 5 failures/16 errors (missing helpers, wrong exit for deferred); workflow contract modules gave 2 failures/1 error each; `test_swarm.py` failed `test_next_wave_requires_a_published_integrator_batch`. GREEN — targeted `test_swarm_cli_coverage` `Ran 84 tests ... OK`; `test_delivery_workflow_gates` OK; `test_workflow_concurrency` OK; `test_swarm` OK. Full suite `python3 -m unittest discover -s scripts/tests -q` -> `Ran 2532 tests in 312.819s`, `OK`. `COVERAGE_BASE=d70939bb REQUIRE_PY_COVERAGE=1 bash scripts/python-coverage.sh` -> rc 0, `python coverage: every changed script line is covered`. `actionlint .github/workflows/swarm-integrator.yml .github/workflows/swarm.yml` -> only the two pre-existing `queue: max` warnings. `python3 -I scripts/{check-constraints,check-docs,check-secrets,check-names}.py` -> rc 0 each; `git diff --check` rc 0. The full suite was run without `-I` because this Mac's PyYAML lives in user site-packages and `-I` hides it; CI has it in the image.
    Coverage: `publication_state` retry/failed/noop/published and unknown-negative fail-closed -> `test_publication_state_helper_covers_every_outcome`; `publication_exit_code` failed/deferred/non-negative -> `test_publication_exit_code_defers_only_the_bounded_retry`; CLI emit once per outcome -> `test_main_integrate_publish_emits_an_explicit_state_for_every_outcome`; SystemExit refusal -> `test_main_integrate_publish_refusal_emits_failed_before_exiting`; unexpected exception -> `test_main_integrate_publish_unexpected_error_emits_failed`; retry payload preserved -> `test_main_integrate_publish_retry_keeps_the_allow_listed_signal`; rejected push state -> `test_main_integrate_publish_rejection_emits_zero_and_skips_dispatch`. Workflow state gates -> `test_publish_and_reusable_workflow_expose_publication_state`, `test_reconciliation_uses_clean_main_least_privilege_and_published_gate`, `test_follow_up_jobs_require_an_explicit_publication_state`, `test_recovery_job_is_signal_gated_and_holds_no_write_token`, `test_retry_pick_is_allow_listed_and_redispatch_carries_only_ids`, `test_next_wave_requires_a_published_integrator_batch`. No threshold, skip, suppression, exclusion or weakened guard.
  - details:
    Owner decision 2026-11-28 supersedes the "keep exit 1" clause of task
    01344000. The recoverable base-advance race is expected and handled; the
    retry JSON (not the exit code) remains the sole authorization for the next
    run. Make the outcome explicit instead of encoding it as a failed step.

    Design: emit `publication_state` as one of `published`, `noop`, `deferred`,
    `failed` from the trusted `integrate-publish` path. A deferred retry exits 0;
    a terminal failure still exits 1. Add a publish-specific exit mapping so the
    `integrate-batch` contract is unchanged. The allow-listed `integration_retry`
    payload (40-hex base, positive-int PRs, attempt 0) stays the only retry
    authority; state is descriptive and must never authorize a write.

    Re-gate `.github/workflows/swarm-integrator.yml`: `reconcile` requires
    `publication_state == 'published'`; ordinary `resolve` requires `published`
    or `noop` plus the seal conflicted output; the recovery branch,
    `resolve-publish` re-dispatch and the dispatch step additionally require
    `deferred` plus the existing validated retry signal. Missing or unknown
    state fails closed. Keep the reconcile/resolve publish-success coupling out
    of the DAG so a green deferred publish cannot mark an unpushed batch merged.

    Worktree `.workspaces/publication-state`, branch `fix/publication-state-output`,
    base d70939bb. Tests first (red), then the smallest change. Named tests for
    every new emit and exit branch. Gates: targeted Python tests, full
    `python3 -I -m unittest discover -s scripts/tests -q`,
    `REQUIRE_PY_COVERAGE=1 bash scripts/python-coverage.sh`, actionlint,
    shellcheck, plan/code/coverage/security/QA reviewers, scoped commit, PR and
    exact-SHA Delivery verdict. No threshold, suppression, skip or guard weakened;
    the `integration_retry` payload assertions stay byte-identical.
