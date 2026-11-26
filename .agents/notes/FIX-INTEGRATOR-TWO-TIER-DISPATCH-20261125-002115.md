# FIX-INTEGRATOR-TWO-TIER-DISPATCH — wire validate/publish and publish every decision

Worker checkpoint and pull-request hand-off. Evidence sources are labelled as tool
(executed command), reviewer (read-only report) or prior-run evidence recorded in
the task file. No simulated walkthrough or human participant was involved.

## Summary

The story's production change is already merged and present in this worktree, so
this worker made **no production code change**. Static verification confirmed all
three requested behaviours are in place:

- `scripts/swarm.py` `main()` dispatches `integrate-validate` and
  `integrate-publish` (lines 6914-6917) instead of silently exiting 0.
- `integrate_validate()` emits a `report` output assembled from `rejected`,
  `already` and `conflict_details` (lines 6146-6172); `emit()` writes name/value
  pairs to `GITHUB_OUTPUT` (lines 1857-1859).
- `.github/workflows/swarm-integrator.yml` declares the `report` job output
  (line 80) and the `publish` job condition includes
  `needs.validate.outputs.report` (lines 149-152), while the `validate` job keeps
  read-only permissions and no secrets (lines 81-99, 132-137).

The original red/green tests remain in the tree:
`scripts/tests/test_swarm.py:4236-4248` (both CLI dispatch paths),
`scripts/tests/test_swarm_integrator_coverage.py:899-916` (validator report) and
`scripts/tests/test_delivery_workflow_gates.py:272-278` (workflow publish
condition).

## Affected requirements

OPS-06 — separate development/test/staging/production resources, synthetic
fixtures, CI policy checks, dependency and secret scanning, monitored rollout.
This story fixes the trusted two-tier integrator publication path so decisions
from the read-only validation job are not dropped.

## Risk class

Cross-boundary (docs/WORKFLOW.md): Python CLI plus GitHub Actions trust boundary.
The candidate job stays read-only; only the trusted publication job holds a write
token and runs scripts from `main`.

## Review owner

Senior developer (advisor brief) and the read-only reviewers listed under
`## Review verdicts`.

## Data changes

None. No migration, schema change, backfill, lock or volume impact; rollback is
the revert of the already-merged commits recorded in the task file.

## Authorization boundaries and audit events

The `validate` job holds `contents: read`, `pull-requests: read`, `actions: read`
and only the read-only job token; it runs `integrate-validate` and never
`integrate-publish`. The `publish` job checks out trusted `main` on a fresh runner
and runs `integrate-publish` with the write-capable token. No new audit event or
tenant/role/patient scope was introduced.

## Migration and rollout considerations

Not applicable: no database or deployment change. Workflow behaviour changes take
effect on the next integrator run from `main`.

## Test evidence

**Executed in this worker session (tool, read-only static verification):**

- File reads of `scripts/swarm.py` (lines 6146-6172, 6480-6505, 6905-6922,
  1855-1859), `.github/workflows/swarm-integrator.yml` (lines 67-179) and the
  three test files listed above confirmed the dispatch, report emission, publish
  condition and read-only candidate permissions. Result: all requested behaviour
  present; no edit required.
- `command -v actionlint || echo "actionlint MISSING"` → `actionlint MISSING`;
  `command -v shellcheck` → `/usr/bin/shellcheck`. actionlint could not be run in
  this environment.
- `python3 -I scripts/check-docs.py` → exit 0. The script was inspected first and
  confirmed to contain no Git/subprocess invocation (only `.git` in a skip set),
  so it is safe under the no-Git directive.
- Static assertion one-liners (no Git, no tests) parsed the workflow YAML and the
  Python AST: `workflow static assertions: OK` and
  `swarm.py static dispatch/report assertions: OK`. They confirm the `report`
  output, the publish condition, read-only validate permissions, both dispatch
  branches and the report expression. The notes file itself was checked with
  `check-docs.markdown_errors` → `notes structural errors: none`.

Static confirmation above proves the files contain the merged change; it is not a
fresh test or coverage run. Fresh execution evidence is identified separately
below and in `## Why this is not finished`.

**Prior-run evidence recorded in `tasks/01275000-FIX-INTEGRATOR-TWO-TIER-DISPATCH.md`
(not re-executed in this session; tool evidence from the earlier worker):**

- Red: four new tests failed before the fix — both CLI dispatch tests (`main()`
  exited without calling the handler), the validator report test (no `report`
  output) and the workflow publish test (no `report` output or publish condition).
- Green: the same four tests → `Ran 4 tests ... OK` after wiring dispatch,
  emitting `report` with rejected + already + conflict_details, and adding the
  publish condition.
- Full suite: `python3 -I -m unittest discover -s scripts/tests -q` →
  `Ran 1579 tests in 245.816s / OK`.
- `bash scripts/python-coverage.sh` → `python coverage: every changed script line
  is covered`; actionlint on all workflows and check-docs / check-constraints /
  check-secrets / check-names / gen-opencode --check each exited 0.
- CI: PR 1613 merged to main as `098354af`. Delivery run 45337601399 on head
  `190cf7dc` had Policy, tooling, frontend and canonical frontend gates green;
  Java verification failed only on an upstream test compile error then fixed on
  main. Delivery run 45339545141 for later main SHA `5a86e60f` stayed `pending`
  with zero jobs for over 40 minutes behind 30+ queued main runs; the exact-SHA
  verdict is unavailable from this environment, not a failure of these changes.
  No check was bypassed.

Entry point to test trace: `swarm.py main() integrate-validate` →
`integrate_validate()` → `emit("report", ...)` → workflow `validate.outputs.report`
→ `publish.if` → `swarm.py main() integrate-publish`. The dispatch tests call
`main()` directly; the validator report test patches `integrate_prepare` and
`emit`; the workflow test parses the YAML job graph.

## Coverage

Prior tool result: `bash scripts/python-coverage.sh` → every changed script line
and branch covered. Each branch introduced by the merged change and its test:

- `main()` branch `a.cmd == "integrate-validate"` →
  `IntegratorTwoTierDispatchTest.test_integrate_validate_dispatches_to_the_validator`.
- `main()` branch `a.cmd == "integrate-publish"` →
  `IntegratorTwoTierDispatchTest.test_integrate_publish_dispatches_to_the_publisher`.
- `integrate_validate()` report emission from rejected, already and
  conflict_details (report present, single `report` output, three decisions) →
  `ValidatorReportTest.test_integrate_validate_emits_decisions_needing_publication`.
- Publish job condition with a non-empty report and no accepted/conflicted PR →
  `IntegratorPublicationCoverageTest.test_publish_job_runs_for_every_validation_decision`,
  plus `test_integrator_validation_has_no_write_token_and_publication_binds_shas`
  for the read-only candidate boundary.

No threshold, exclusion, suppression or skipped test was added by this worker.
This worker did not re-run the coverage command because of the durable no-Git
directive described under `## Findings`; the coverage evidence above is from the
prior worker run and was not modified.

## Clinical and architecture review

Not required: no patient data, prescribing, critical results, cross-organization
sharing or signed data. Clinical and architecture review were not performed for
this CLI/workflow fix.

## Review verdicts

Prior run (read-only reviewers, recorded in the task file):

- aurevia-github-workflow: GO
- aurevia-code-reviewer: GO
- aurevia-coverage-auditor: GO
- aurevia-devsecops: GO

Current run (read-only reviewers; no Git, gh, tests or coverage executed, all
verdicts based on static verification plus prior recorded evidence):

- aurevia-plan-reviewer: GO
- aurevia-code-reviewer: GO
- aurevia-coverage-auditor: GO
- aurevia-devsecops: GO

The current devsecops pass raised one Required finding on pre-existing
`integrate_finalize` isolation code (recorded in full under `## Findings`). On
scope clarification it confirmed the finding is an owned, separately tracked
defect (`BUGS.md:15` AUDIT-INTEGRATOR-TOKEN, task 01271100, WFAUDIT-01) and not a
blocker for this story's already-merged dispatch/report diff; it retained the
finding and returned GO. No finding was suppressed or downgraded.

## Findings

- The advisor brief was incomplete: its specialist list was empty and it named
  only the `delivery` skill, while AGENTS.md requires the matching skills and the
  mandatory plan/code/coverage/devsecops loop. This worker loaded `delivery`,
  `senior-python`, `github-workflow`, `plan-reviewer` and `test-engineer`, and
  consulted `plan-reviewer`, `code-reviewer`, `coverage-auditor` and `devsecops`.
- The brief also directed `git merge --no-edit origin/main`, a scoped commit and
  test/coverage commands that invoke Git. That conflicts with the durable owner
  directive in `.opencode/config/MEMORY.md` ("do not run any script that use git"),
  including tests and checks that call `git diff`, and the workspace rule that
  never runs Git or Git-backed operations. This worker did not run those commands;
  only read-only Git inspection commands were run at discovery before the
  directive was loaded. No commit, push, tag, `gh` call, tracker or protected-file
  edit was made in this session.
- `tasks/_head.md` rule 8 says workers do not edit `scripts/` or workflows, while
  this task's scope lists them. No edit to those paths was needed because the fix
  is already merged; the discrepancy is left for the integrator.
- Re-dispatch finding: the task file already carried full green evidence and four
  GO verdicts, and the code was already present on both the branch and
  `origin/main` (only the task file's `agent`/`attempts` fields differ). No test
  could be made red again without reverting merged work, so no new red run was
  manufactured.
- Security review finding (Required from the current read-only devsecops pass;
  pre-existing code, not part of this story's diff): `integrate_publish` calls
  `integrate_finalize`, which re-runs the gate on candidate code
  (`scripts/swarm.py:6315-6318`) inside the token-bearing publish job and checks
  out the candidate tip (`:6382`). That conflicts with the documented publisher
  contract in `docs/operations/workflow-changes/github-verification-checklist.md:195`
  and is the subject of the blocked WFAUDIT-01 patch
  (`docs/operations/workflow-changes/candidate-validation-isolation.md`). It is
  already owned at `BUGS.md:15` (AUDIT-INTEGRATOR-TOKEN, OPS-06, task 01271100);
  route any follow-up there and to the WFAUDIT-01 owner. The earlier devsecops
  pass for this story returned GO. This worker did not change
  that path: a safe fix needs a new red/green run and 100% coverage proof, which
  the no-Git directive makes impossible here, and `tasks/_head.md` rule 3 does not
  allow a task request naming `scripts/` or workflow paths. The finding is handed
  to the integrator as a follow-up security defect, not suppressed. Optional
  security findings on the same pass: mask raw candidate gate logs before
  emitting `report`, and an "override" rejection is replayed through `guard()`
  by `report_validation` after a merge.
- Optional reviewer findings on the merged code, none blocking and none changed
  in this worker run: `base` in `integrate_validate` duplicates `tip` and is not
  read by the publisher; a moved head can raise `SystemExit` before
  `report_validation(manifest)`; the emitted report includes gate log text
  (credentials are stripped by `gate_environment`); and the new test classes sit
  after an in-file `unittest.main()` guard, so they run under canonical
  `unittest discover` but not direct file execution. The workflow publish test
  uses `assertIn` for the new condition rather than pinning the whole boolean
  expression; the other disjuncts predate this story.

## Lessons for workers

1. Read `.opencode/config/MEMORY.md` and the task file's `evidence:` before running
   any command: a re-dispatched task may already be merged and proven, and a durable
   owner directive may prohibit the very commands the brief suggests.
2. Confirm the current worktree state first when a task looks complete; do not
   duplicate a fixed change or invent a synthetic red run. Static verification plus
   clearly attributed prior evidence is the honest record when gates cannot be
   re-run.

## Why this is not finished

The story itself is finished and merged; this section records only the limit of
this worker's re-verification, not a story blocker. This worker could not re-run
`python3 -I -m unittest discover -s scripts/tests`, `bash scripts/python-coverage.sh`,
`scripts/check-constraints.py`, `check-secrets.py`, `check-names.py`, the Git merge
or the commit because the durable owner directive forbids Git commands and scripts
that invoke Git; actionlint is not installed here. The exact-SHA Delivery verdict
from the prior run was also unavailable because the run stayed queued. The next
step, if fresh execution evidence is required, is for a trusted job that is
authorized to run Git and the full gate to re-run the four named tests,
`bash scripts/python-coverage.sh` and the policy scripts on the current `main` SHA
and attach the final lines here. No check was bypassed or weakened.
