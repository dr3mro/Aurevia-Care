# FIX-DELIVERY-GATE-EXPECTED — fix pass 2026-11-25

## Summary

Story requirement: branch protection requires the exact context `Delivery gate`, so every
pull-request head must report it; an open head previously showed "Expected — Waiting for
status to be reported" when Delivery was path-filtered or queued behind a stale head.

Tool discovery on this branch confirmed the production fix is already present:
`.github/workflows/delivery.yml` has a bare `pull_request` trigger, an unfiltered `push`
(`branches: [main]`, `tags: ['v*']`), and a concurrency group keyed by
`${{ github.event.pull_request.head.sha || github.ref }}` with `cancel-in-progress: false`
and `queue: max`. `scripts/swarm.py` accepts only exact-head pull-request runs. No workflow,
pipeline, Java, frontend or SQL change was justified in this pass, and the PR-only
cancellation from the original story text was NOT reintroduced (MEMORY.md records that
GitHub rejects `queue: max` with cancellation; per-SHA groups superseded it on 2026-11-24).

The residual gap was that no test guarded the exact required-check contract itself. This
pass adds a test-only guard for the `gate` job: its context name, `always()` disposition,
exact job set, fail-closed checker step and three GitHub context bindings, plus synthetic
mutations proving the guard can fail. The prior pass's documentation fix and doc-contract
test remain in the branch commit and were re-verified here.

## Loaded skills

- `aurevia-delivery`: matched "working any story / swarm task"; supplied the delivery loop and swarm hand-off contract.
- `aurevia-github-workflow`: matched `.github/workflows/delivery.yml`, required checks and branch gates.
- `aurevia-senior-python`: matched the changed `scripts/tests/*.py` tooling test and its coverage gate.
- `aurevia-devsecops`: matched the CI gate / token / fail-closed boundary risk review.

## Affected requirements

OPS-06 — synthetic checks, CI policy and monitored, reversible delivery.

Acceptance mapping:

| Story scenario | Evidence |
|---|---|
| Every PR head reports `Delivery gate` | bare trigger + per-SHA group in `delivery.yml`; `test_delivery_reports_a_check_for_every_pull_request`; new `DeliveryGateContractTest` guards the exact context name and `always()` |
| Every push/tag reports the check | unfiltered push; `test_delivery_reports_a_check_for_every_branch_and_tag_push`; `test_delivery_documentation_matches_unfiltered_triggers` |
| A new head is not dropped behind a stale head | `test_delivery_queues_each_pr_head_and_ref_without_cancelling`, `test_serialized_groups_reject_legacy_keys_and_invalid_queue` |
| Integrator accepts only exact-head evidence | `test_swarm_delivery_state.py` (67 tests) |
| A missing/failed/cancelled job fails closed | `scripts/check-delivery-gate.py`; new gate-contract tests assert the step and env wiring |

## Risk class

Cross-boundary (CI contract and tooling). No patient data, production code, migration,
permission or clinical workflow involved.

## Review owner

Senior GitHub workflow reviewer, plus the mandatory plan, code, coverage and security reviewers.

## Data changes

None. No migration, backfill, lock or volume impact.

## Authorization boundaries and audit events

None. No tenant, role or patient-link scope; no audit event added.

## Migration and rollout considerations

None. Test-only change; no workflow file or production gate changed.

## Test evidence

Entry point to test trace: `.github/workflows/delivery.yml` `jobs.gate` (name `Delivery gate`,
`if: always()`, exact `needs`, last step) → `scripts/check-delivery-gate.py` (`MANDATORY`,
`OPTIONAL`, fail-closed `gate_violations`) → `scripts/tests/test_workflow_concurrency.py`
`DeliveryGateContractTest` (real-contract assertions and 17 synthetic mutations). The
agent integration path is covered separately by `scripts/swarm.py` `delivery_gate` →
`scripts/tests/test_swarm_delivery_state.py`.

Commands run and final lines (tool evidence):

- `git merge --no-edit origin/main` → `Already up to date.` (run at pass start, after code, and before finish); `git grep -nE '^(<<<<<<<|>>>>>>>) '` → `none`.
- Red first, before the helper existed: `python3 -I -m unittest discover -s scripts/tests -p 'test_workflow_concurrency.py' -v` → `Ran 11 tests in 0.290s`, `FAILED (errors=10)`, first error `NameError: name 'gate_contract_violations' is not defined`.
- Green after implementing the helper: same command → `Ran 11 tests in 0.329s`, `OK`.
- After the mid-pass `origin/main` merge: `python3 -I -m unittest discover -s scripts/tests -p 'test_workflow_concurrency.py'` → `Ran 11 tests in 0.282s`, `OK`.
- Final version with all reviewer-driven mutations: `python3 -I -m unittest discover -s scripts/tests -p 'test_workflow_concurrency.py' -v` → `Ran 11 tests in 0.281s`, `OK` (11 test methods; the 17 mutations are subtests inside `test_gate_contract_detects_a_mutated_required_check`, so unittest still reports 11 tests).
- Final comment-only cleanup re-run (no behavior change): `python3 -I -m unittest discover -s scripts/tests -p 'test_workflow_concurrency.py'` → `Ran 11 tests in 0.290s`, `OK`.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm_delivery_state.py'` → `Ran 67 tests`, `OK`.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_check_delivery_gate.py'` → `Ran 20 tests in 0.011s`, `OK`.
- `python3 -I -m unittest discover -s scripts/tests -q` → `Ran 1825 tests in 81.770s`, `FAILED (failures=9, errors=16, skipped=1)`. All are pre-existing/worker-image artifacts, none in a changed file: 16 errors in `test_orca_benchmark.py` because `scripts/orca_benchmark.py` is not tracked in this checkout; 7 `test_check_pack` failures from the documented CI-profile-under-`skip-worktree` artifact (`git ls-files -v .opencode/opencode.json` → `S`); 2 `test_hermetic_env` signal failures from missing `ps`. Verified with the tracebacks in the run log.
- `bash scripts/python-coverage.sh` → exit 1 because its internal full unittest discovery hits those same unrelated failures and `set -e` stops before report generation; this is not a changed-line coverage failure.
- Isolated equivalent: `COVERAGE_FILE=/tmp/opencode/cov2.data python3 -m coverage run --branch --source=scripts --omit='scripts/tests/*' -m unittest discover -s scripts/tests -q`, then `coverage json` and `python3 -I scripts/python_coverage.py /tmp/opencode/cov2.json --base origin/main` → `python coverage: every changed script line is covered`, exit 0.
- `python3 -I scripts/check-docs.py`, `check-secrets.py`, `check-names.py`, `check-wiring.py`, `check-constraints.py`, `check-migrations.py --require-baseline` → each exit 0.
- `shellcheck scripts/*.sh` → exit 0. `actionlint` is not installed in this image; no workflow file changed, so it was not run (not bypassed).
- `python3 -I scripts/gen-opencode.py --check` and `python3 -I scripts/check-pack.py` → both report `.opencode/opencode.json` stale; confirmed as the known skip-worktree CI-profile false positive, not regenerated or committed.
- No Java/frontend file touched: no `mvn verify`, no `apps/api/check.sh`, no `scripts/check.sh` (worker rule and five-minute command limit). No typecheck was needed; no TS error exists in this change.
- No live GitHub verdict was produced: this worker cannot push, tag or call `gh` by execution-prompt rule, so no exact-SHA Delivery run was claimed green.

## Coverage

- Touched area: `scripts/tests/test_workflow_concurrency.py` only (test module). `scripts/python-coverage.sh` omits `scripts/tests/*` (lines 26-28), so no production line or branch was added or changed and the changed-line production gate has nothing new to cover.
- Command and result: isolated changed-line gate above → `python coverage: every changed script line is covered` (exit 0). Full `python-coverage.sh` was blocked by unrelated pre-existing suite failures, reported above rather than hidden.
- Targeted command and result: `Ran 11 tests in 0.281s`, `OK`.

New branches in the test helpers and the named mutation that takes each one (all inside
`test_gate_contract_detects_a_mutated_required_check`, using `copy.deepcopy` so the workflow
on disk is never changed; each asserts the expected violation occurs exactly once):

| Helper branch | Named mutation |
|---|---|
| `name != "Delivery gate"` | `renamed context` |
| `if != "always()"` | `dropped condition`, `non-always condition` |
| `needs` not a list / set mismatch | `dropped mandatory job`, `malformed needs` |
| `steps` not a list (empty-value fallback) | `malformed steps` |
| step-selection `isinstance(item, dict)` false arm | `non-dict step` |
| checker script absent / wrong path with step present | `wrong checker`, `wrong checker path` |
| step-level `continue-on-error` truthy | `continue on error` |
| job-level `continue-on-error` truthy | `job continue on error` |
| `\|\| true` in command | `swallowed with or true` |
| `exit 0` in command | `swallowed exit` |
| `env` not a dict (empty-value fallback) | `malformed env` |
| `RESULTS` binding missing | `missing results binding` |
| `EVENT_NAME` binding wrong | `wrong event binding` |
| `REF_TYPE` binding wrong | `wrong ref binding` |
| clean contract path and independent job-set pin | `test_delivery_gate_reports_the_required_context_for_every_head` asserts `gate_contract_violations(gate) == []` and `REQUIRED_JOBS == LITERAL_JOBS` |

No threshold, exclusion, suppression, `# pragma: no cover`, skipped test or lowered limit was added.

## Review verdicts

- aurevia-code-reviewer: GO
- aurevia-coverage-auditor: GO
- aurevia-devsecops: GO
- aurevia-github-workflow: GO

(The plan reviewer first answered NO-GO on red-first sensitivity and refresh discipline; both
were fixed before coding and the re-review answered GO. Final verdicts above are copied from
the reviewers' last lines after the finished change.)

## Findings

- The workflow, its triggers, per-SHA concurrency and the integrator's exact-head evidence rules were already correct on `main`; no production change was justified. The cancelled-run approach in the original story text is superseded and was not reintroduced.
- The real residual gap was an unguarded exact required-check context. A rename of `jobs.gate.name`, a dropped `always()`, a changed `needs` set or a swapped checker script would recreate "Expected — Waiting"; the new test now rejects all four.
- Expected `needs` is derived from `check-delivery-gate.py` (`MANDATORY | OPTIONAL`) instead of a third hard-coded copy, and independently pinned by `LITERAL_JOBS`, so neither a single edit nor a coordinated checker/workflow edit can silently drop a verification job.
- Reviewer-driven hardening: the guard now also rejects `continue-on-error: true` and a command that swallows the checker's exit status (`|| true` / `exit 0`), which would otherwise report a green required check on a failing run. The coverage auditor's three malformed-input branch gaps were closed with named mutations.
- Full tooling-suite and `python-coverage.sh` failures are pre-existing/worker-image artifacts confirmed by traceback and file reads; no in-scope production defect is implied.
- Out of scope and NOT edited: `docs/swarm/EFFICIENCY.md` lines 60 and 76 still price a `paths-ignore` saving for docs-only pushes and claim Delivery skips them. The execution prompt forbids editing `docs/swarm`; a task request is filed below.
- Out of scope and already requested: the stale claim in `docs/spec/clauses/vulnerability-management/scan-dependencies-images-secrets-change-block-merges.md` is in the committed prior note (`FIX-DELIVERY-GATE-EXPECTED-20261125-003200.md`), so it is not duplicated here.
- Live GitHub Delivery evidence for an exact PR head cannot be produced in this worker (no push/gh); it remains a trusted-integrator action.

## task-requests

```task-requests
[
  {
    "title": "Correct stale Delivery path-filter claim in the efficiency notes",
    "details": "docs/swarm/EFFICIENCY.md line 60 still lists 'Delivery per push to main (docs-only)' as 7 before / 1 after / 6 saved, and line 76 change #6 still claims paths-ignore for docs/** and Markdown skips Delivery for docs-only pushes to main. The workflow has no path filter on any trigger, so every push reports the required Delivery gate check. Update the table row and change #6 to describe unfiltered triggers and the estimated cost of running the full chain on docs-only pushes. Run python3 -I scripts/check-docs.py after the edit. Worker scope forbids editing docs/swarm, so this is handed to the integrator.",
    "scope": ["docs/swarm/EFFICIENCY.md"],
    "depends": ["01263000"]
  }
]
```

## Lessons for workers

- Guard the exact status-check string that branch protection requires, not just the triggers: a renamed job recreates "Expected — Waiting" even when the workflow runs.
- A contract test on already-correct production is vacuous unless you prove sensitivity; synthetic in-memory mutations give real red/green evidence without editing a protected workflow.
- Derive expected values from the checker the workflow actually invokes so the contract lives in one place.
- Before attributing a red full suite to the environment, read the failing test names and tracebacks: here an untracked module, a skip-worktree config and a missing `ps` accounted for every failure.

## Why this is not finished

The scoped source change is complete, locally green and committed to the worker branch, but this worker cannot finish the story: no push, tag or `gh` call is allowed, so no live exact-SHA Delivery verdict can be observed, and the task file is owned by the orchestrator/integrator. The full tooling suite remains red for the three pre-existing environmental causes listed above, not for this change. Next step for the trusted integrator: merge this branch, run Delivery on the exact head SHA, confirm `Delivery gate` is reported on a fresh PR head, then mark task `01263000` `[x]` with the run id and SHA. No check was bypassed or weakened.
