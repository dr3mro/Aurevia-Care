# FIX-INTEGRATOR-TWO-TIER-DISPATCH — Wire the validate/publish CLI and publish every decision

## Summary

Resumed worker run for a story whose fix is **already merged to `main`** (PR 1613, merge
`098354af`, an ancestor of this branch's HEAD `c06ca49c`). The shipped change wires
`integrate-validate` and `integrate-publish` into `swarm.py`'s `main()` dispatch, emits a
`report` containing `rejected + already + conflict_details`, and widens the trusted
publish job's `if` condition so `already`, `moved`, `rejected` and exhausted conflicts are
published too. The candidate job stays read-only.

This run adds **no production, test, workflow or tracker edit**: every scoped file is
byte-identical to `origin/main` (`git diff --stat origin/main -- <scope>` is empty). The
deliverable of this run is this hand-off note. I followed the story and AGENTS.md rather
than re-implementing already-landed work (reconciled lesson 2026-11-22: keep the landed
feature and acceptance tests, contribute only the missing slice).

## Affected requirements

- **OPS-06** — separate, policy-checked and auditable operation. The two-tier split keeps
  candidate code on a read-only runner and confines the write token (`SWARM_TOKEN`) to the
  publisher; the emitted report makes every decision observable by the trusted job.
  Evidence: `.github/workflows/swarm-integrator.yml` (validate `permissions: read`,
  `GH_TOKEN: github.token`; publish `GH_TOKEN: secrets.SWARM_TOKEN`) and
  `scripts/swarm.py` `integrate_validate` / `integrate_publish`.
- Story acceptance: both CLI subcommands dispatch to their handlers; the validator emits
  `report`; the publish condition includes `report`.

## Risk class

Cross-boundary (CI workflow + Python tooling + trusted publication). Defensive-security
review was included because the change separates a read-only candidate from a write-token
publisher. No clinical surface, patient identity or PHI is involved.

## Review owner

`aurevia-github-workflow` (workflow/condition), with `aurevia-code-reviewer`,
`aurevia-coverage-auditor`, `aurevia-devsecops` and `aurevia-plan-reviewer`.

## Data changes

None. No migration, no schema change, no fixture with real identifiers; synthetic data only.

## Authorization boundaries and audit events

- Candidate (`validate`) job: `contents/pull-requests/actions: read`, `GH_TOKEN:
  github.token`, no `SWARM_TOKEN`, checks out `main`; it cannot push, comment, label or
  open issues. `integrate_validate` only writes the local manifest/bundle artifact.
- Publish job: checks out trusted `main` (candidate code never on this runner), holds
  `SWARM_TOKEN`, runs `integrate-publish`, then reconciles tasks. Its condition now also
  fires on `report`, so rejected/already/moved/exhausted-conflict decisions are reported.
- No new audit event type; publication continues through the existing PR/issue comments
  in `integrate_publish`. No token, credential or patient data is written to any note,
  fixture or log.

## Migration and rollout considerations

None. Expand/contract is not applicable; the workflow change is reversible by reverting
the commit. No production deployment is authorized by this story.

## Test evidence

All commands run from the repository root on branch
`swarm/FIX-INTEGRATOR-TWO-TIER-DISPATCH-1527`, clean at `c06ca49c`.

1. Four guard tests (exact IDs, verbose discover):
   ```
   python3 -I -m unittest discover -s scripts/tests -v \
     -k test_integrate_validate_dispatches_to_the_validator \
     -k test_integrate_publish_dispatches_to_the_publisher \
     -k test_integrate_validate_emits_decisions_needing_publication \
     -k test_publish_job_runs_for_every_validation_decision
   ```
   Final lines:
   ```
   test_publish_job_runs_for_every_validation_decision (test_delivery_workflow_gates.IntegratorPublicationCoverageTest.test_publish_job_runs_for_every_validation_decision) ... ok
   test_integrate_publish_dispatches_to_the_publisher (test_swarm.IntegratorTwoTierDispatchTest.test_integrate_publish_dispatches_to_the_publisher) ... ok
   test_integrate_validate_dispatches_to_the_validator (test_swarm.IntegratorTwoTierDispatchTest.test_integrate_validate_dispatches_to_the_validator) ... ok
   test_integrate_validate_emits_decisions_needing_publication (test_swarm_integrator_coverage.ValidatorReportTest.test_integrate_validate_emits_decisions_needing_publication) ... ok
   Ran 4 tests in 0.016s

   OK
   ```
   Entry-point-to-test trace: CLI `main()` dispatch branches → the two
   `IntegratorTwoTierDispatchTest` cases; `integrate_validate` `emit("report", ...)`
   → `ValidatorReportTest`; workflow publish `if` →
   `IntegratorPublicationCoverageTest`.

2. Full tooling suite:
   ```
   python3 -I -m unittest discover -s scripts/tests -q >/tmp/fullsuite.out 2>&1
   ```
   Final lines: `Ran 1600 tests in 46.191s` / `FAILED (failures=6)`. All six are
   `test_check_pack.py` cases (`test_check_mode_detects_drift`, `test_cli_entry_point`,
   `test_main_reports_success`, `test_committed_opencode_json_is_generated`,
   `test_repository_pack_is_valid`, `test_simulated_user_skills_need_no_process_sections`).
   Classification: pre-existing red on current `main`, class (2) in the 2026-11-22 lesson —
   the documented cloud-worker `opencode.json` CI-profile `skip-worktree` false positive
   (OPS-CW-003-01), not a defect of this story and outside its scope.

3. `bash scripts/python-coverage.sh` → exit 1 at the **unittest stage** (the same six
   `test_check_pack.py` failures); `set -e` stops before its coverage analysis. The prior
   pass on the story's own SHA recorded `python coverage: every changed script line is
   covered`. No changed production line exists in this run, so there is no new coverage
   obligation (see `## Coverage`).

4. Policy scripts, each `exit=0`:
   `python3 -I scripts/check-docs.py`, `python3 -I scripts/check-constraints.py`,
   `python3 -I scripts/check-secrets.py`, `python3 -I scripts/check-names.py`.

5. `python3 -I scripts/gen-opencode.py --check` → exit 1 with
   `.opencode/opencode.json is out of date: run scripts/gen-opencode.py`. This is the same
   documented false positive: `git ls-files -v .opencode/opencode.json` → `S .opencode/opencode.json`
   (CI profile installed under `skip-worktree`), and `git diff --stat HEAD -- .opencode/opencode.json`
   is empty, so the committed profile is the generated one. No regenerated file was committed.

6. `git merge --no-edit origin/main` → `Already up to date.`;
   `git grep -nE '^(<<<<<<<|>>>>>>>) '` → no matches.

7. `actionlint` is not installed in this worker; the prior pass recorded actionlint clean
   across the workflows. The workflow's publish-condition behaviour is covered by guard
   test 1 in this run.

Evidence sources: executed tool commands (1–6), read-only reviewer verdicts (below) and
the task tracker's recorded red/green/CI evidence. No simulated user walkthrough and no
human participant was involved.

## Coverage

`git diff --stat origin/main -- scripts/swarm.py scripts/tests/test_swarm.py
scripts/tests/test_swarm_integrator_coverage.py scripts/tests/test_delivery_workflow_gates.py
.github/workflows/swarm-integrator.yml` → empty: this run changes no production line,
branch, method or class, so no new coverage is required. The shipped branches are named by
tests already on `main`:

| New branch of the shipped fix | Test that covers it |
|---|---|
| `main()` dispatches `integrate-validate` to the validator | `test_swarm.py::IntegratorTwoTierDispatchTest::test_integrate_validate_dispatches_to_the_validator` |
| `main()` dispatches `integrate-publish` to the publisher | `test_swarm.py::IntegratorTwoTierDispatchTest::test_integrate_publish_dispatches_to_the_publisher` |
| `integrate_validate` emits `report` with `rejected`, `already`, `conflict_details` | `test_swarm_integrator_coverage.py::ValidatorReportTest::test_integrate_validate_emits_decisions_needing_publication` |
| publish job condition includes non-empty `report` | `test_delivery_workflow_gates.py::IntegratorPublicationCoverageTest::test_publish_job_runs_for_every_validation_decision` |

No threshold was lowered, no exclusion, skip, `@Generated`, `pragma: no cover` or
suppression was added. `python coverage` analysis could not be re-reached in this worker
because the full suite stops on the six unrelated `test_check_pack.py` failures; the
prior pass's coverage command and result are recorded in the task file.

## Clinical and architecture review

No clinical workflow, patient-facing surface or real patient data is touched. Architecturally
the fix completes the two-tier boundary: validation is read-only and publication is trusted.
Clinical sign-off is not applicable to this tooling story.

## Review verdicts

- aurevia-plan-reviewer: GO
- aurevia-github-workflow: GO
- aurevia-code-reviewer: GO
- aurevia-coverage-auditor: GO
- aurevia-devsecops: GO

(The four post-change GO verdicts were first recorded in the task file by the original
merged pass; this run re-asked each read-only reviewer against the current tree and copied
their final verdicts here.)

## Findings

1. **Already merged, not redone.** The task file already carries red/green evidence, four
   GO reviews and a CI status; `098354af` (PR 1613) contains the fix and is an ancestor of
   HEAD. Re-implementing it would duplicate tests and risk conflicts, so this run only
   verifies and hands off. The `[x]` mark belongs to the integrator, not this worker.
2. **Full suite / coverage false positive (class 2, owner fixes on main).** Six
   `test_check_pack.py` failures on current `main` come from the CI `opencode.json` profile
   installed under `skip-worktree`: `git ls-files -v .opencode/opencode.json` → `S`,
   `git diff --stat HEAD -- .opencode/opencode.json` → empty. They are not caused by this
   story (scope diff empty). `gen-opencode.py --check` exit 1 has the same cause. The
   integrator/Delivery run the authoritative gate with the local profile.
3. **Brief conflict.** The worker prompt directed me to run `scripts/check.sh` once at the
   end; AGENTS.md ("a swarm worker must not run `scripts/check.sh` … leave the full gate to
   the integrator") and reconciled lesson 2026-11-22 line 59 forbid it. I followed AGENTS.md
   and ran the individual policy scripts and targeted tests instead. The brief was wrong on
   this point; the rest of it matched the story and AGENTS.md.
4. **No exact-SHA CI verdict from this environment.** The task file records the last
   Delivery run for the later main SHA as `pending` behind a long queue; no required check
   was bypassed and no authoritative verdict is claimed here.
5. **Optional reviewer hardening ideas (no in-scope edit).** All five reviewers returned GO
   with no Critical or Required finding. Their Optional ideas, recorded for a future owner:
   emit a boolean `has_report` instead of full gate-log text in the job output (github-workflow,
   code-reviewer); assert all three `if` disjuncts and both non-empty guards in
   `test_publish_job_runs_for_every_validation_decision` (github-workflow, coverage-auditor);
   move the three new test classes above their `if __name__ == "__main__"` guards so direct
   file execution also runs them (github-workflow, code-reviewer); re-verify the manifest's
   decision lists in the trusted publisher as defence in depth (devsecops); and, only if
   whole-file 100% is later targeted, add named tests for the pre-existing
   `integrate_publish` guard branches (coverage-auditor). These live in `scripts/`/workflows,
   which a worker may not edit and which `task-requests` may not name as scope, so they are
   left here rather than raised as tasks.

No in-scope check fails, so no "Why this is not finished" section is warranted: the story's
code merged and all four guard tests pass. Blockers 2 and 4 are outside this worker's
scope and authority.

## Lessons for workers

- When a re-dispatched story has an empty scope diff against `origin/main` and its task
  file already carries evidence plus GO verdicts, verify the four guard tests, write the
  prompt-named notes file and stop. Do not re-implement or duplicate tests.
- In cloud workers, `gen-opencode.py --check` and `test_check_pack.py` failures are usually
  the CI `opencode.json` profile under `skip-worktree`; confirm with `git ls-files -v`
  (`S`) and `git show HEAD:.opencode/opencode.json` before calling them defects, and never
  regenerate or commit that file.
- A worker prompt that says to run `scripts/check.sh` conflicts with AGENTS.md; the full
  gate belongs to the integrator. Say so in the notes and run the standalone policy
  scripts and targeted tests instead.
- Classify a red check before changing code: in-scope defect → fix it; pre-existing red on
  `main` → owner/integrator fixes it; PR mechanics → maintainer clears it.
