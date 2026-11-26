# FIX-DELIVERY-GATE-EXPECTED — Delivery gate for every PR head

## Loaded skills

- `aurevia-delivery`: every story uses the resumable delivery loop (matched "working any story").
- `aurevia-github-workflow`: the story concerns `.github/workflows/delivery.yml`, required checks and branch gates (matched ".github/workflows").

## Summary

Discovery (tool evidence from file reads): the required workflow behaviour from this story is
already on `main`. `delivery.yml` has a bare `pull_request` trigger, a `push` trigger with only
`branches: [main]` and `tags: ['v*']` (no path filters), and a concurrency group keyed by
`github.event.pull_request.head.sha || github.ref` with `cancel-in-progress: false` and
`queue: max`. `scripts/tests/test_workflow_concurrency.py` already asserts those contracts.
`scripts/swarm.py` already classifies only exact-head pull-request runs and grants no
documentation-only bypass. No workflow, pipeline or production code change was needed.

The remaining in-scope defect was stale documentation: `docs/reference/testing-guide.md` claimed
`paths-ignore` on `push` skips docs-only commits, contradicting the current trigger. This run
adds a regression test for the documented trigger contract and corrects that paragraph.

## Affected requirements

OPS-06 — synthetic checks, CI policy and monitored, reversible delivery.

## Risk class

Cross-boundary (CI contract and operator documentation). No patient data, production code,
migration, permission or clinical workflow involved.

## Review owner

Senior GitHub workflow reviewer (read-only), plus the mandatory plan, code, coverage and
defensive-security reviewers.

## Data changes

None. No migration, backfill, lock or volume impact.

## Authorization boundaries and audit events

None. No tenant, role or patient-link scope; no audit event added.

## Migration and rollout considerations

None. Documentation and a tooling test only; the workflow is unchanged.

## Test evidence

- Entry point to test trace: `.github/workflows/delivery.yml` bare `pull_request` / unfiltered
  `push` triggers and per-head-SHA concurrency → `scripts/tests/test_workflow_concurrency.py`
  trigger and concurrency assertions → new `test_delivery_documentation_matches_unfiltered_triggers`
  reads the operator guide that describes those triggers. No production code changed.
- Baseline before the change: `python3 -I -m unittest discover -s scripts/tests -p 'test_workflow_concurrency.py' -v`
  → `Ran 8 tests in 0.176s`, `OK`.
- Red first: same command after adding the test → `Ran 9 tests in 0.176s`,
  `FAILED (failures=1)`: `AssertionError: '`paths-ignore` on `push`' unexpectedly found` in
  `test_delivery_documentation_matches_unfiltered_triggers`.
- Green after the doc fix: same command → `Ran 9 tests in 0.174s`, `OK`.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_swarm_delivery_state.py'` →
  `Ran 67 tests in 0.111s`, `OK` (no regression in the in-scope exact-head Delivery
  classifier suite).
- `python3 -I scripts/check-docs.py` → exit 0. `python3 -I scripts/check-secrets.py` → exit 0.
  `python3 -I scripts/check-names.py` → exit 0.
- `python3 -I scripts/check-verdicts.py .agents/notes/FIX-DELIVERY-GATE-EXPECTED-20261125-003200.md`
  → `check-verdicts exit=0` after all four GO lines were recorded. `check-constraints.py` and
  `quality-gate.sh` were not run because they diff against Git refs and Git is forbidden here.
- Full tooling suite `python3 -I -m unittest discover -s scripts/tests -q` → exit 1,
  `Ran 1742 tests in 77.424s`, `FAILED (failures=9, skipped=1)`. All nine failures are known
  cloud-worker environment artifacts, none in a changed file: seven in `test_check_pack`
  (`AssertionError: 1 != 0`; `python3 -I scripts/gen-opencode.py --check` reports
  `.opencode/opencode.json is out of date: run scripts/gen-opencode.py`, the documented
  CI-profile-under-skip-worktree false positive in AGENTS.md) and two in `test_hermetic_env`
  signal handling (`ps: command not found` in the sandbox).
- `shellcheck` is installed; `actionlint` is not. No shell script or workflow file changed, so
  neither lint was needed. No Java or frontend area was touched; the full `check.sh` / `mvn
  verify` was deliberately not run (worker rule and five-minute command limit).
- Live GitHub Delivery verdict for an exact SHA is unavailable: Git, `gh` and GitHub API
  commands are forbidden in this session by the durable directive in `MEMORY.md`. No check was
  bypassed, dispatched or claimed as green.

## Coverage

- Command: `python3 -I -m unittest discover -s scripts/tests -p 'test_workflow_concurrency.py' -v`
  → `Ran 9 tests in 0.174s`, `OK`; the new test executes both of its assertions.
- No production Python, Java, TypeScript, workflow or SQL line or branch was added or changed.
  `scripts/python_coverage.py` tracks top-level `scripts/*.py` only and `scripts/python-coverage.sh`
  omits `scripts/tests/*`, so there is no changed production line to cover. `python-coverage.sh`
  was not run because it probes Git internally (`git rev-parse`, `merge-base`, `diff`) and Git
  operations are forbidden here; this is reported as not run, not as a green coverage claim.
  The trusted integrator should run `bash scripts/python-coverage.sh` where Git is available and
  record its final line; no top-level `scripts/*.py` changed, so it is a changed-line no-op here.
- New branches: none in production code. The new test's negative assertion (stale claim absent)
  was proven red before the doc fix and green afterwards; the positive assertion (every push and
  tag reports the check) is exercised by the same named test.
- No threshold, exclusion, suppression or skip was added.

## Review verdicts

- aurevia-code-reviewer: GO
- aurevia-coverage-auditor: GO
- aurevia-devsecops: GO
- aurevia-github-workflow: GO

The plan reviewer first answered NO-GO on two Required gaps; both were folded in before coding
and the re-review answered GO. The coverage auditor's initial NO-GO on missing notes evidence was
closed by rewriting `## Test evidence` and `## Coverage` and re-audited to GO. The github-workflow
reviewer's Required drift finding is the out-of-scope documentation listed under `## Findings`
and filed as a task request, so no Critical or Required finding remains open on this scoped diff.

## Findings

- The workflow, its tests and the integrator's exact-head evidence rules were already correct on
  `main`; no production change was justified. MEMORY.md records that PR-only cancellation was
  replaced on 2026-11-24 by per-SHA PR groups because GitHub rejects `queue: max` with
  cancellation, so the cancelled approach in the original story text was not reintroduced.
- Corrected in this run: `docs/reference/testing-guide.md` lines 74-80 claimed a push
  `paths-ignore` filter that no longer exists.
- Out of scope and NOT edited: `docs/swarm/EFFICIENCY.md` lines 60 and 76 still price a
  `paths-ignore` saving for docs-only pushes; the execution prompt forbids editing `docs/swarm`
  and `swarm.py:protected_files` treats it as protected. A task request is filed below.
- Out of scope and NOT edited: `docs/spec/clauses/vulnerability-management/scan-dependencies-images-secrets-change-block-merges.md`
  lines 18 and 30 still assert `paths-ignore: ['docs/**', '**/*.md']` on push and that
  documentation-only changes skip the scans (verified by file read). Task 00764000 created that
  note and is already `[x]`; no open task covers correcting it. A task request is filed below.
- Brief discrepancy: the brief's scope named `docs/swarm/` and a skill file, but the execution
  prompt and AGENTS.md forbid workers from editing `docs/swarm` and `.opencode`; the skill text
  is already accurate. Work continued from the story and AGENTS.md as instructed.
- Full tooling-suite failures above are environmental and reproducible from file reads and the
  named commands; no production defect is implied.

## task-requests

```task-requests
[
  {
    "title": "Correct stale Delivery path-filter claims in the WEB-12 evidence note",
    "details": "docs/spec/clauses/vulnerability-management/scan-dependencies-images-secrets-change-block-merges.md lines 18 and 30 still claim that the push trigger of .github/workflows/delivery.yml has paths-ignore for docs/** and Markdown and that docs-only changes skip the scans. The workflow now has no path filter on any trigger. Update the claim, the cited line numbers and the hardening note, then run python3 -I scripts/check-docs.py. docs/swarm/EFFICIENCY.md lines 60 and 76 carry the same stale claim but are outside worker scope; handle that separately.",
    "scope": ["docs/spec/clauses/vulnerability-management/scan-dependencies-images-secrets-change-block-merges.md"],
    "depends": ["00764000"]
  }
]
```

## Lessons for workers

- Check whether the story's production fix is already on `main` before touching a protected
  workflow: read the current YAML, its tests and the durable decisions first. Here the only real
  gap was documentation drift.
- A documentation-contract test is cheap and catches an operator guide promising a check that
  GitHub never creates; assert the exact stale claim plus the positive behaviour.
- In cloud workers a red full tooling suite is often two known environment artifacts (stale
  worktree `opencode.json`, missing `ps`). Confirm the failing test names, run targeted suites,
  and record the exact final lines instead of editing protected files.

## Why this is not finished

The scoped source change is complete and its tests are green, but the story cannot be marked done
from this worker: no commit was made because Git and Git-backed operations are forbidden by the
durable session directive in `.opencode/config/MEMORY.md` (the owner's 2026-11-24 authorisation
is recorded for a trusted integrator, while this session's runtime still denies it). Live
evidence that a freshly created PR head reports the required `Delivery gate` check can only come
from GitHub Actions runs, and `gh` / API access is likewise unavailable here. Next step for the
integrator: commit the two scoped files with this note, run the exact-SHA Delivery gate on a PR
head, then mark task `01263000` `[x]` with the run id and head SHA. No check was bypassed.
