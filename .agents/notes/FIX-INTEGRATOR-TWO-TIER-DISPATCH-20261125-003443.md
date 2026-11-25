# FIX-INTEGRATOR-TWO-TIER-DISPATCH — verification-only hand-off

## Summary

Story scope and requirement OPS-06 were already fully implemented and merged before this
worker ran. Branch `swarm/FIX-INTEGRATOR-TWO-TIER-DISPATCH-1561` at `6416876a` is byte-identical
to `origin/main` (`git rev-list --left-right --count origin/main...HEAD` → `0 0`), so there was
**no code to write, no test to add and no change to commit**. This pass independently verified
the acceptance state with fresh tool commands and read-only reviews. The previous worker's
evidence is on the task file (`evidence:`/`reviews:`/`ci-status:` fields) and in
`.opencode/config/MEMORY.md` (owner decision, 2026-11-24, PR 1613 merged to main as `098354af`).

No production code, test, workflow, script or tracker was edited by this worker.

## Affected requirements

OPS-06 — separate CI policy checks, dependency and secret scanning, audited approvals and
reversible deployments. Slice: the trusted integrator must validate every candidate decision
and publish every decision, never just accepted/conflicted ones.

## Risk class

Cross-boundary (CI workflow plus Python CLI dispatch and write-token boundary). Not
safety-critical: no patient data, identity, prescribing or critical results are involved;
synthetic fixtures only.

## Review owner

Senior Python tooling and GitHub workflow specialists; read-only reviewers listed below.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix needed.

## Authorization boundaries and audit events

The candidate/validate job stays read-only and emits outputs only. Only the trusted publish
job calls `report_validation()`, which comments, labels, closes PRs and updates trackers.
No new audit event, tenant, role or patient-link scope was introduced by this worker.

## Migration and rollout considerations

None. The already-merged change is workflow/CLI-only. No database or deployment change.

## Test evidence

Entry-point-to-test trace (current HEAD):

1. `.github/workflows/swarm-integrator.yml:137` validate step runs
   `python3 -I scripts/swarm.py integrate-validate --queue "$QUEUE" --gate "$GATE" --out validated`
   → `scripts/swarm.py:6934` `main()` dispatch → `integrate_validate()` (`:6158`) →
   `emit("report", json.dumps(rejected + already + conflict_details))` (`:6182-6183`).
2. `swarm-integrator.yml:80` declares `report: ${{ steps.candidate.outputs.report }}`
   → `:152` publish condition `|| (needs.validate.outputs.report != '' && needs.validate.outputs.report != '[]')`
   → `:179` trusted publish step runs `integrate-publish` → `scripts/swarm.py:6936` dispatch
   → `integrate_publish()` (`:6371`) → `report_validation()` (`:6108`) replays rejected,
   already, conflict_details and accepted decisions.
3. Follow-up jobs still require `needs.publish.result == 'success'`; the resolver remains
   keyed on `conflicted`.

Commands run by this worker and their final lines (tool evidence):

- `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm.py' -k IntegratorTwoTierDispatchTest -v`
  → `Ran 2 tests in 0.006s / OK` (`test_integrate_validate_dispatches_to_the_validator`,
  `test_integrate_publish_dispatches_to_the_publisher`).
- `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm_integrator_coverage.py' -k ValidatorReportTest -v`
  → `Ran 1 test in 0.001s / OK` (`test_integrate_validate_emits_decisions_needing_publication`).
- `python3 -I -m unittest discover -s scripts/tests -p 'test_delivery_workflow_gates.py' -k IntegratorPublicationCoverageTest -v`
  → `Ran 3 tests in 0.033s / OK` (includes `test_publish_job_runs_for_every_validation_decision`,
  `test_follow_up_jobs_require_successful_publication`).
- `python3 -I -m unittest discover -s scripts/tests -p 'test_delivery_workflow_gates.py' -q`
  → `Ran 15 tests in 0.258s / OK`.
- `python3 -I scripts/python_coverage.py /tmp/opencode/cov.json --base origin/main`
  → `python coverage: every changed script line is covered`.
- `python3 -I scripts/check-docs.py`, `check-constraints.py`, `check-secrets.py`, `check-names.py`
  → exit 0 each, empty final line.

Known cloud-worker environment artifacts, confirmed once (not defects of this change;
AGENTS.md "Known worker-environment false positives"):

- `bash scripts/python-coverage.sh` → exit 1 at the unittest stage:
  `FAILED (failures=9, skipped=1)`. Seven are `check-pack: .opencode/opencode.json is stale:
  run scripts/gen-opencode.py`; `git ls-files -v .opencode/opencode.json` → `S .opencode/opencode.json`
  (CI profile installed under `skip-worktree` by `agent-run.sh`), so the committed HEAD profile
  is the correct one and the coverage comparison stage was run directly instead. Two are
  `test_agent_runner_restores_config_and_auth_on_signals` failing because the sandbox lacks
  `ps`/`kill` (`/tmp/.../bin/git: line 20: ps: command not found`). No protected file was edited.
- `python3 -I scripts/gen-opencode.py --check` → `.opencode/opencode.json is out of date:
  run scripts/gen-opencode.py` — the same skip-worktree CI profile (OPS-CW-003-01).
- `actionlint` is not installed in this worker image; workflow assertions were exercised by the
  Python workflow-gate suite above and by the read-only GitHub-workflow reviewer.

## Coverage

Command: `python3 -I scripts/python_coverage.py /tmp/opencode/cov.json --base origin/main`
Result: `python coverage: every changed script line is covered`.

No production line or branch was added or changed by this worker, so there is no new branch to
name. The already-merged branches and their existing tests (verified present and green at HEAD):

| Branch in the implemented fix | Covering test |
|---|---|
| `main()` → `integrate-validate` dispatch | `IntegratorTwoTierDispatchTest.test_integrate_validate_dispatches_to_the_validator` |
| `main()` → `integrate-publish` dispatch | `IntegratorTwoTierDispatchTest.test_integrate_publish_dispatches_to_the_publisher` |
| `integrate_validate()` emits `report` with rejected + already + conflict_details | `ValidatorReportTest.test_integrate_validate_emits_decisions_needing_publication` |
| publish job `if` includes the report output | `IntegratorPublicationCoverageTest.test_publish_job_runs_for_every_validation_decision` |
| follow-up jobs gated on successful publication | `IntegratorPublicationCoverageTest.test_follow_up_jobs_require_successful_publication` |

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association, prescribing or
critical results. No patient data or real identifier appears in this note, any fixture or any
command output recorded here.

## Review verdicts

- aurevia-github-workflow: GO — validate job read-only (`contents/pull-requests/actions: read`,
  `GH_TOKEN: github.token`, no `SWARM_TOKEN` or NVD key); publish condition includes
  `needs.validate.outputs.report`; follow-up jobs gated on publish success; no Critical, Required
  or Optional finding. Reviewer limitation: static inspection plus supplied test result, no shell
  in that read-only role.
- aurevia-code-reviewer: GO — `main()` dispatch (`:6934-6937`), `report` emission
  (`:6182-6183`), `report_validation()` replay (`:6108-6155`, called at `:6398`) and the
  read-only validate / write-token publish split are all correct; no Critical, Required or
  Optional finding. Reviewer limitation: static inspection plus supplied test results, no shell
  in that read-only role.
- aurevia-coverage-auditor: GO — every changed branch traced to a named test with a real
  assertion (both CLI dispatch arms, `report` concatenation, publish condition); no skipped test,
  exclusion or lowered threshold. Optional, non-blocking and pre-existing (not added by this fix):
  `scripts/swarm.py:6398` `report_validation(manifest)` is only reached through tests that stub it
  without asserting the call, so a direct `replay.assert_called_once_with(manifest)` in
  `test_integrate_publish_rejection_restores_tasks_and_returns_nonzero` would harden the replay
  invariant. Reviewer limitation: static trace plus supplied tool evidence, no shell in that role.
- aurevia-devsecops: GO — validate job read-only with no `SWARM_TOKEN`/`OPENCODE_API_KEY`/NVD key and
  `persist-credentials: false`; `integrate_validate` is emit-only (`guard(..., report=False)`,
  `delivery_gate(..., report=False)`), all writes live in `report_validation()` reached only from
  the trusted `integrate_publish` after SHA re-verification; `gate_environment()` strips
  credential-shaped variables before candidate code runs; fixtures synthetic, no PHI. No Critical,
  Required or Optional finding. Reviewer limitation: static inspection, no shell in that role;
  OWASP/SpotBugs not re-run because no dependency or production Java changed.

## Findings

1. The story was already complete on the base branch. Evidence: all four acceptance tests exist
   and pass at HEAD; `main()` dispatch, the `report` output and the workflow publish condition are
   present; the task file and MEMORY.md record PR 1613 merged to main as `098354af`. This pass
   added no code, so `attempts: 22` on the task file looks like repeated verification-only
   dispatch of an already-satisfied story, not an open defect. The integrator should decide
   whether to close the task from the recorded evidence rather than redispatch it.
2. Brief gap: the brief named only the `delivery` skill, but the routing contract in AGENTS.md
   matches `aurevia-senior-python` (scope includes `scripts/*.py` and their tests) and
   `aurevia-github-workflow` (scope includes `.github/workflows/swarm-integrator.yml`). Both
   were loaded and applied; no finding resulted.
3. Brief conflict: the brief asks for a commit, but MEMORY.md "Session constraint (2026-11-24)"
   and the durable runtime directive forbid Git/Git-backed operations in this session. No
   commit, push, tag or `gh` call was made; this note is a workspace edit only.
4. No reproducible in-scope production defect was found. The full-suite failures listed above
   are documented environment artifacts and were not re-diagnosed on a later pass.
5. Optional hardening suggestion from the coverage auditor (pre-existing line, not part of this
   story, no action taken to stay in scope): add `replay.assert_called_once_with(manifest)` to
   `test_integrate_publish_rejection_restores_tasks_and_returns_nonzero` so deleting the
   `report_validation(manifest)` call at `scripts/swarm.py:6398` would fail a test. This is a
   candidate for a new task in `tasks/`, not an edit by this worker.

## Lessons for workers

- Before rewriting a swarm story, diff the worktree against `origin/main` and run the story's
  named acceptance tests. If the fix and all four tests are already present, record a
  verification-only hand-off instead of recreating the change; AGENTS.md asks you to state
  plainly when no production code changed.
- In a cloud worker, `S .opencode/opencode.json` plus a stale-config check means the CI profile
  is installed under `skip-worktree`, not a real defect. Run the coverage comparison stage
  directly rather than editing the protected generated file.
- `actionlint`, `ps` and `kill` may be absent in the worker image; record the exact failing
  command and final line, then exercise the remaining gates individually.

## Why this is not finished

Not applicable to the story: the story is already implemented, merged and green at HEAD; this
worker only verified it. No check relevant to the unchanged scope failed. The only unproven item
is an exact-SHA Delivery verdict, which the earlier run recorded as unavailable from a cloud
worker because main runs were queued (ci-status on the task file); no check was bypassed.
