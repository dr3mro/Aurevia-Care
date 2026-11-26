# FIX-DELIVERY-GATE-EXPECTED — worker notes (OPS-06)

## Summary

The required `Delivery gate` check for every PR head is already implemented on `main`: `.github/workflows/delivery.yml` has a bare `pull_request:` trigger, no path filter on any trigger, and a concurrency group keyed by `github.event.pull_request.head.sha || github.ref` with `cancel-in-progress: false` and `queue: max`. `scripts/swarm.py` has no docs-only bypass and the concurrency/gate tests already assert those contracts. **No production code or workflow was changed in this worker.**

What remained was stale operator documentation. `docs/reference/testing-guide.md` and `docs/swarm/EFFICIENCY.md` still described a `paths-ignore` filter on `push` that no longer exists. Both were corrected, and a regression test in `scripts/tests/test_workflow_concurrency.py` now fails if either doc stops stating the no-filter invariant or reintroduces the literal. A third file with the same stale claim is outside this task's scope and is handed off via `task-requests` below.

Skill evidence: `aurevia-delivery` matched every story (delivery loop). `aurevia-github-workflow` matched the workflow/gate scope (`.github/workflows/delivery.yml`, concurrency and the required check). No other skill matched. The advisor brief was stale on one point: it prescribed "PR-only cancel-in-progress"; that was superseded by per-SHA PR concurrency groups because GitHub rejects `queue: max` with cancellation (MEMORY.md decision 2026-11-24, task `01273000`). I followed the current workflow and AGENTS.md, and changed no workflow file.

Plan review: `aurevia-plan-reviewer` first returned NO-GO because a third out-of-scope doc repeats the claim; the Required gap was resolved by recording that file under `## Findings` and in `task-requests` without editing it, and the reviewer then returned `VERDICT: GO`.

## Affected requirements

OPS-06 (CI policy checks, dependency and secret scanning, monitored progressive rollout; the Delivery gate reports an exact-SHA verdict for every candidate). Acceptance mapping: every PR head runs the full chain and reports `Delivery gate`; every push to `main` and every `v*` tag keeps its exact-SHA verdict; no path filter suppresses a required check.

## Risk class

Cross-boundary (required-check contract and operator documentation for the Delivery gate). No workflow, Java, frontend, database or contract code changed in this worker.

## Review owner

Primary: orca. Read-only specialists: `aurevia-plan-reviewer` (plan), `aurevia-code-reviewer`, `aurevia-coverage-auditor`, `aurevia-devsecops` (post-code); skill `aurevia-github-workflow` supplied the workflow invariants.

## Data changes

None. No migration, backfill, table, lock or volume impact; no rollback or forward-fix.

## Authorization boundaries and audit events

Unchanged. PR and merge-group runs receive an empty NVD key (`.github/workflows/delivery.yml:133`); checkouts keep `persist-credentials: false`; the aggregate gate stays fail-closed. No tenant, role or patient-link scope was touched, no audit event was added, and no credential or patient data appears in the change or this note.

## Migration and rollout considerations

None. Documentation and a test-only guard; no workflow definition changed, so there is no rollout step beyond the normal merge and Delivery verification of the merge commit.

## Test evidence

Commands run from the repository root; all outputs below are tool evidence.

- Red, before the doc edits: `python3 -I -m unittest discover -s scripts/tests -p 'test_workflow_concurrency.py' -v` → `FAILED (failures=2)`; one `AssertionError: 'no path filter' not found` for `testing-guide.md` and one `AssertionError: 'paths-ignore' unexpectedly found` for `EFFICIENCY.md`, both in `test_operator_docs_describe_the_unfiltered_delivery_trigger`.
- Green after the edits and after the final case-insensitivity tweak: same command → `Ran 9 tests in 0.262s` / `OK`, exit 0.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm_delivery_state.py' -q` → `Ran 67 tests in 0.133s` / `OK`, exit 0 (baseline, unchanged area).
- Filtered full tooling suite excluding only the two known worker-image-failing modules (`test_check_pack.py`, `test_hermetic_env.py`): `Ran 1676 tests in 67.982s` / `OK (skipped=1)`, 0 failures, 0 errors.
- `bash scripts/python-coverage.sh` → exit 1 with `FAILED (failures=9, skipped=1)` from 1742 tests. The nine failures are pre-existing worker-environment artifacts, not this change: seven `test_check_pack.py` failures all end in `.opencode/opencode.json is stale: run scripts/gen-opencode.py` (the worktree file is the CI profile under `skip-worktree`, verified with `git ls-files -v .opencode/opencode.json` → `S .opencode/opencode.json`; its SHA-256 equals `.opencode/opencode.ci.json`), and two `test_hermetic_env.py` signal fixtures fail with `ps: command not found` in the sandbox. This matches the documented cloud-worker false positives in AGENTS.md; no protected file was edited in response.
- `python3 -I scripts/check-docs.py` → exit 0; `python3 -I scripts/check-secrets.py` → exit 0; `python3 -I scripts/check-constraints.py --require-base` → exit 0.
- `shellcheck scripts/*.sh infra/bootstrap/start.sh apps/api/check.sh db/check.sh` → exit 0; `bash -n` over the same files → `ok`.
- `timeout 290 bash scripts/check.sh` → exit 1 with the first failing command `check-pack: .opencode/opencode.json is stale: run scripts/gen-opencode.py` — the same known CI-profile artifact; the remaining stages were run individually above.
- `actionlint` could not be run here: `python3 -m venv` reports "you need to install the python3-venv package" and `python3 -m pip` reports `No module named pip`. The workflow file was not changed by this worker; CI runs actionlint 1.7.12 with the documented `queue` ignore.
- `git merge --no-edit origin/main` → `Already up to date.`; `git grep -nE '^(<<<<<<<|>>>>>>>) '` → no conflict markers.
- No Java, staff-web or patient-mobile source changed, so their `check.sh` gates are not applicable to this worker's diff; no full `mvn verify` was run.

Entry point to test trace: `.github/workflows/delivery.yml` triggers → `test_workflow_concurrency.py` asserts the bare PR trigger, no path filters and the per-head concurrency group → `test_operator_docs_describe_the_unfiltered_delivery_trigger` asserts the two operator docs describing that same trigger. The integrator/Delivery run on the merge SHA supplies the live GitHub verdict; this worker has no `gh` or push authority.

## Coverage

No production line or branch was added: the only script edit is in `scripts/tests/`, and the two other files are Markdown.

Changed-line script check: `COVERAGE_FILE=/tmp/opencode/cov-final.data python3 -m coverage run --branch --source=scripts --omit='scripts/tests/*' -m unittest discover -s scripts/tests -p 'test_workflow_concurrency.py' -q` → `OK`; `COVERAGE_FILE=/tmp/opencode/cov-final.data python3 -m coverage json -q --omit='scripts/tests/*' -o /tmp/opencode/cov-final.json`; `python3 -I scripts/python_coverage.py /tmp/opencode/cov-final.json --base origin/main` → `python coverage: every changed script line is covered`, exit 0. This check is honest but thin for this change: its matcher covers `scripts/*.py` only and there are no added production lines, so the real proof is the red/green targeted run and the 1676-test filtered suite.

| New branch | Test that takes it |
|---|---|
| `for path in DELIVERY_DOCS` → `testing-guide.md` | `test_operator_docs_describe_the_unfiltered_delivery_trigger`: `assertIn("no path filter", text)` and `assertNotIn("paths-ignore", text)`; failed red before the doc edit, passes after |
| `for path in DELIVERY_DOCS` → `EFFICIENCY.md` | same two assertions under `subTest(doc='EFFICIENCY.md')`; failed red before the doc edit, passes after |
| `subTest` loop exit | the 9-test targeted suite reports `OK` |

No threshold, exclusion, suppression, skip or `@Generated`/ignore annotation was added or weakened.

## Clinical and architecture review

Not applicable: no patient-facing flow, identity, result, order, prescription or release-of-information behavior changed. No simulated user or human participant evidence is claimed; all evidence above is tool output or read-only reviewer findings. No real patient data was used.

## Review verdicts

- aurevia-plan-reviewer: GO (after the out-of-scope finding was recorded as a task request; first pass NO-GO)
- aurevia-code-reviewer: GO
- aurevia-coverage-auditor: GO
- aurevia-devsecops: GO

Optional reviewer refinements applied after the verdicts: the efficiency row now names all four swarm wave triggers (`tasks/` push, hourly schedule, completed slot-image run, manual dispatch); the doc-guard assertion lowercases the text for both checks; the test docstring was narrowed to the two listed operator docs. The suggestion to add `docs/spec/clauses/.../scan-dependencies-images-secrets-change-block-merges.md` to `DELIVERY_DOCS` was deliberately not applied because that file is outside this task's scope and would leave a permanently red test; it is in the follow-up request instead.

## Findings

1. **Stale third document (out of scope).** `docs/spec/clauses/vulnerability-management/scan-dependencies-images-secrets-change-block-merges.md:18` and `:30` still state that the push trigger has `paths-ignore: ['docs/**', '**/*.md']` and that documentation-only changes skip Delivery. The current workflow has no path filter on any trigger. Correct both lines and extend the doc guard in `scripts/tests/test_workflow_concurrency.py` once a task covers that file. Evidence: file read and repository-wide `paths-ignore` grep (tool).
2. **Advisor brief is stale.** It prescribes PR-only `cancel-in-progress`, which GitHub rejects together with `queue: max`; the landed design uses per-PR-head concurrency groups (MEMORY.md decision, task `01273000`, workflow test assertions). No workflow change made in response. Evidence: file reads and reviewer confirmation.
3. **No live GitHub verdict in this worker.** Earlier hand-off (task `01263000`, `[-]`, 25 attempts) records the fix on `main` but no observed PR head reporting the check; this worker has no `gh` or push authority, so no new run ID can be produced here. Evidence: worker prompt and task file read.
4. **Worker-environment false positives** in `check.sh` / `python-coverage.sh` as detailed under Test evidence; not defects of this change and not re-diagnosed further.

## Lessons for workers

When a gate trigger changes, grep the whole repository for the old mechanism (`paths-ignore`) before finishing: workflow code can be fixed in one commit while operator docs and spec-clause evidence notes keep asserting the retired behavior for weeks. Guard the corrected docs with a failing test so later workflow edits cannot silently reintroduce the contradiction, and hand out-of-scope stale files to the integrator as task requests instead of editing them.

```task-requests
[
  {
    "title": "Correct stale Delivery path-filter claim in WEB-12 evidence note",
    "details": "docs/spec/clauses/vulnerability-management/scan-dependencies-images-secrets-change-block-merges.md lines 18 and 30 state that the push trigger has paths-ignore and that documentation-only changes skip Delivery. The current .github/workflows/delivery.yml has no path filter on any trigger, so every PR and every main/tag push reports the exact-SHA Delivery gate check. Correct both lines and add this doc to DELIVERY_DOCS in scripts/tests/test_workflow_concurrency.py so the regression guard covers it too.",
    "scope": ["docs/spec/clauses/vulnerability-management"],
    "depends": ["FIX-DELIVERY-GATE-EXPECTED"]
  }
]
```

## Why this is not finished

Source work is complete and green in every applicable local gate, but the live GitHub evidence required by story `FIX-DELIVERY-GATE-EXPECTED` is still missing. The workflow fix landed earlier (task `01263000`, commit `c959c39b`, merged through `e33a2ae7`); this worker could not run `gh`, push, or observe a freshly created/rebased PR head, so it cannot confirm that `Delivery gate` now reports on that exact SHA. Exact blocker: no GitHub execution authority in this worker (per task prompt and MEMORY.md session constraint), and `actionlint` is unavailable locally (no `pip`, no `python3-venv`). Most likely cause: trusted-integrator hand-off is required by design, not a failing check. Already tried: all local workflow-contract tests, the filtered 1742/1676-test suites, individual policy checks and a merge with `origin/main`. Exact next step: after this scoped commit is merged, have the trusted integrator dispatch or rebase one PR, read `gh pr checks` for its head SHA, confirm the `Delivery gate` context is present and successful, and then mark the task `[x]` with that run ID and SHA.
