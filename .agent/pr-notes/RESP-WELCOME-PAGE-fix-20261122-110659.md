# RESP-WELCOME-PAGE — fix pass: responsive `WelcomePage` (staff web)

## Summary

Verification/hand-off pass for swarm branch `swarm/RESP-WELCOME-PAGE-861` (HEAD
`62c3f014` at verification time). The scoped implementation committed by the first
worker is already complete and correct: `WelcomePage` renders a labelled
`section.page[data-breakpoint]` through the real `useBreakpoint` hook with
direction-neutral logical inline styles, and
`apps/staff-web/tests/responsive/app/WelcomePage.responsive.test.tsx` drives
`matchMedia` across the three bands, the 320–1280 px width matrix in both `en` and
`ar`, and Arabic RTL. Fix pass 2 strengthened the RTL-at-width coverage (26 cases in
that file; 1917 in the full suite) and changed no production code. **No code was
changed in this pass** — every scoped gate is green and 100% coverage is proven on
the only changed production file.

This pass re-ran the authoritative scoped gate from the current branch and
reproduced the one remaining blocker: a pre-existing, out-of-scope
`FileNotFoundError` in `scripts/worker_gate.py::usable_reports()` that makes the
repository-wide tooling gate fail in any fresh checkout. A swarm worker cannot fix
that file (it is outside this task's scope and is maintainer-owned), so the exact
reproduction and fix plan are handed off below. All evidence in this note came from
executed tool commands by the primary agent; no reviewer, simulated user or human
participant supplied evidence in this pass.

## Affected requirements

WEB-07, UX-01.

Acceptance mapping: the `data-breakpoint` band attribute and the logical inline
styles in `apps/staff-web/src/app/WelcomePage.tsx` deliver reflow, label wrapping,
safe-area padding and logical-property use at every supported width in English and
Arabic. `apps/staff-web/tests/responsive/app/WelcomePage.responsive.test.tsx`
asserts structure, labels, roles, DOM/keyboard order, the full width matrix in both
locales and the RTL band case. Pixel-level measurement remains with the Playwright
tasks, as the task directs.

## Risk class

Routine (contained staff-web view plus one isolated test; no cross-boundary or
safety-critical contract). Classified per `docs/WORKFLOW.md`. The new hand-off
blocker is delivery tooling, not a change in this story.

## Review owner

Primary agent (tool-verified). Earlier passes recorded simulated
`smartclinic-senior-react` and `smartclinic-ux-reviewer` reviews, both with no
actionable findings; those subagents had no shell and relied on primary-agent
evidence. This pass changes no code, so no new public contract, migration or
clinical data is involved and no new cross-boundary or clinical review is required.

## Data changes

None. No migration, backfill, lock or volume impact; no reserved migration version
was used.

## Authorization boundaries and audit events

None. Presentation-only change to a placeholder route; no tenant, role, patient-link
or audit behaviour changed.

## Migration and rollout considerations

None.

## Test evidence

Exact commands run in this pass and their final lines:

- `git merge --no-edit origin/main` → `Already up to date.` (run at the start). No
  conflict markers in either scoped file. A tree-wide git grep for leading Git
  conflict-marker lines found markers only in three pre-existing, out-of-scope
  `apps/api/.../hl7/` Java files (see Findings).
- `bash apps/staff-web/check.sh` → **exit 0**. Final lines of each stage:
  - `gen:api: generated client matches the contract`
  - typecheck: `> smartclinic-staff-web@0.1.0 typecheck` then
    `> tsc --noEmit -p tsconfig.json` — **no `error TS…`, exit 0**
  - lint: `> eslint . --max-warnings 0` — no warnings, exit 0
  - build: `> tsc --noEmit -p tsconfig.json && vite build`, `✓ built in 137ms`,
    `dist/assets/WelcomePage-DcjT3cc2.js  0.47 kB │ gzip:  0.32 kB`, WEB-08 budget
    row `WelcomePage-DcjT3cc2.js  328  ok`
  - `Test Files  159 passed (159)`, `Tests  1917 passed (1917)`,
    `Duration  63.45s (environment 41%, tests 31%, setup 18%, worker 4%, transform 3%, import 3%)`
  - `No files with missing coverage.` / `207 files fully covered.` /
    `4 empty files skipped.`
  - The log prints React error stacks from existing negative
    `useI18n`/`useTheme` provider tests; they are expected and do not fail the run.
- `node -e` over `apps/staff-web/coverage/coverage-summary.json` →
  `src/app/WelcomePage.tsx` `{"lines":{"total":3,"covered":3,"skipped":0,"pct":100},"functions":{"total":1,"covered":1,"skipped":0,"pct":100},"statements":{"total":3,"covered":3,"skipped":0,"pct":100},"branches":{"total":0,"covered":0,"skipped":0,"pct":100}}`.
- `python3 -I -m unittest discover -s scripts/tests -p 'test_worker_gate*.py'`
  with **no** `apps/api/target/surefire-reports` directory → **exit 1**,
  `Ran 143 tests in 0.052s`, `FAILED (errors=2)`, both with
  `FileNotFoundError: [Errno 2] No such file or directory: 'apps/api/target/surefire-reports'`
  raised at `scripts/worker_gate.py`, line 63, in `usable_reports`. This is the
  blocker reproduced, not a defect of this story (see Findings).
- `git ls-files -v opencode.json` → `S` (skip-worktree). `git diff --stat origin/main -- scripts/`
  → empty (working tree has no script changes).
- `bash scripts/check.sh` (run once at the end, capped at 150 s) → exit 1 with final
  line `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. This is the
  documented cloud-worker CI-profile artifact (`opencode.json` is installed under
  `skip-worktree`); the aggregate gate stopped before the Python tooling stage, so the
  underlying `worker_gate` failure above was reproduced directly with the targeted
  command instead. The protected file was not regenerated, edited or committed.
- `git diff origin/main...HEAD` for the two scoped files → only the `WelcomePage.tsx`
  responsive wrapper and the new responsive test file.

Entry point → test trace: `apps/staff-web/src/app/routes.tsx` lazily registers
`WelcomePage` for pattern `/`; `AppShell` renders it in the app main landmark;
`WelcomePage` renders `section.page[data-breakpoint]` plus the `EmptyState`
`role="status"` panel; both are asserted in
`apps/staff-web/tests/responsive/app/WelcomePage.responsive.test.tsx` across the
three bands, the width matrix and Arabic RTL.

## Coverage

`bash apps/staff-web/check.sh` includes `npm run test:coverage` (Vitest/v8,
per-file thresholds 100/100/100/100). Result:

```
Statements   : 100% ( 3722/3722 )
Branches     : 100% ( 1892/1892 )
Functions    : 100% ( 1258/1258 )
Lines        : 100% ( 3556/3556 )
```

The only changed production file, `src/app/WelcomePage.tsx`, is 100% on lines (3/3),
statements (3/3), functions (1/1) and branches (0/0).

New/changed branches and their tests — `WelcomePage` adds no `if`, ternary,
`&&`/`||`, `switch` or `catch`; the only variability is the value of the existing
`useBreakpoint` hook and the active locale:

- `useBreakpoint() === 'compact'` → `responsive WelcomePage at compact` tests and
  the 320/360/390 px rows of the width matrix assert `data-breakpoint="compact"`.
- `useBreakpoint() === 'medium'` → `responsive WelcomePage at medium` tests and the
  768/820/1024 px rows assert `data-breakpoint="medium"`.
- `useBreakpoint() === 'expanded'` → `responsive WelcomePage at expanded` tests and
  the 1280 px row assert `data-breakpoint="expanded"`.
- `locale === 'ar'` → the RTL tests assert `dir="rtl"`, `lang="ar"` and the Arabic
  welcome label; fix pass 2 also exercised `ar` at all seven concrete widths.

No threshold, exclusion, `skip` or pragma was added or changed. No production line
was changed in this pass, so no new production branch was introduced.

## Clinical and architecture review

Not required: presentation-only placeholder route, no signed data, patient
association, prescribing, critical results or cross-organization sharing.

## Findings

- **Scoped story is complete.** The first worker's implementation and responsive
  test, plus fix pass 2's RTL-at-width strengthening, pass every applicable gate:
  generated-client drift, typecheck, lint, build, WEB-08 bundle budget, 159 test
  files / 1917 tests and 100% coverage globally and on the changed file.
- **Blocking, pre-existing, out-of-scope defect — `scripts/worker_gate.py`.**
  `usable_reports()` (lines 54–69) iterates
  `sorted(Path(reports_dir).glob("TEST-*.xml"))` at line 63. `Path.glob` calls
  `os.scandir`, so a missing directory raises `FileNotFoundError` instead of
  yielding no reports. Two tooling tests
  (`test_worker_gate.MainTest.test_integration_failure_still_fails_the_gate` and
  `test_worker_gate_coverage.MainTest.test_maven_failure_with_failing_reports_still_fails`)
  mock `Path.is_dir = True` but glob the real default path
  `apps/api/target/surefire-reports`, which is a gitignored build artifact absent
  from a fresh checkout. They therefore error (`Ran 143 tests`, `FAILED (errors=2)`),
  which fails `bash scripts/python-coverage.sh` and the policy/tooling stage of
  `bash scripts/check.sh` in the integrator and in Delivery. Confirmed pre-existing:
  `git diff --stat origin/main -- scripts/` is empty and the same two errors are
  reported on clean `origin/main`. It is not caused by this story's diff.
- **Exact fix for the maintainer who owns `scripts/` (NOT applied by this worker).**
  Bounded scope: `scripts/worker_gate.py` and `scripts/tests/test_worker_reports.py`
  only. (1) Write the regression test first in class `UsableReportsTest`:
  `test_missing_directory_has_no_usable_results` builds a `Path` under
  `tempfile.TemporaryDirectory()` that is never created and asserts
  `worker_gate.usable_reports(missing) is False` — it fails today with
  `FileNotFoundError`. (2) Make `usable_reports()` total for an absent directory:
  wrap the `for report in sorted(Path(reports_dir).glob("TEST-*.xml")):` loop so a
  `FileNotFoundError` from the vanished/absent directory returns `False`; keep the
  existing `ET.ParseError → continue` behaviour and do not change `main()`'s
  `reports.is_dir()` guard (the docstring already says an absent directory means no
  test results). (3) Prove it on a checkout with no surefire directory:
  `python3 -I -m unittest discover -s scripts/tests -p 'test_worker_gate*.py'`
  (expect OK), the full `python3 -I -m unittest discover -s scripts/tests`,
  `bash scripts/python-coverage.sh` (exit 0, 100% branches on the touched lines),
  then `bash scripts/check.sh`. Do not re-add the rejected `mkdir -p
  apps/api/target/surefire-reports` workaround, do not regenerate/commit
  `opencode.json`, and do not change a threshold, exclusion or skip.
- **Why this worker did not apply that fix.** This task's scope is only
  `apps/staff-web/src/app/WelcomePage.tsx` and
  `apps/staff-web/tests/responsive/app/WelcomePage.responsive.test.tsx`.
  `scripts/swarm.py:1905` builds the `Scope (stay inside these paths):` line from
  the task's `scope`, and `worker_gate.validate_candidate` reports any other changed
  file as a `scope` violation, which rejects the candidate; AGENTS.md/TODO rule 8
  also forbids a worker from editing `scripts/*`. A swarm PR touching `scripts/` is
  never auto-merged, so editing it here would block this story rather than unblock
  it. A `task-requests` entry cannot be used either: TODO rule 3 forbids task
  scopes that are script files. This needs a maintainer-owned repair of `main`.
- **CI `opencode.json` artifact.** `git ls-files -v opencode.json` → `S`: the
  worktree file is the CI profile copied under `skip-worktree`, so `check-pack.py`
  and `gen-opencode.py --check` can report it stale in this worker. No commit on
  this branch touches it; it was neither regenerated, edited nor committed. Per
  AGENTS.md the integrator/Delivery own the repository-wide gate.
- Pre-existing, outside scope and untouched: `origin/main` contains literal conflict
  markers in three `apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7/`
  Java files (`Hl7ParseException.java`, `Hl7Parser.java`, `Hl7Segment.java`). The
  merge with `origin/main` was clean and no marker exists in either scoped file.
- The first worker's notes (`RESP-WELCOME-PAGE-20261122-084248.md`) carried a
  `## Why this is not finished` heading whose body claimed completion; that heading
  is a machine-read early-stop signal. This pass includes it truthfully because the
  PR cannot go green until the out-of-scope tooling defect is fixed by a maintainer.

## Lessons for workers

- `Path.glob` on a missing directory raises `FileNotFoundError` (it calls
  `os.scandir`); a `is_dir()` guard elsewhere does not protect a direct call. Make
  report readers total for an absent directory rather than relying on a gitignored
  build-artifact directory existing.
- A locally red `check-pack`/`gen-opencode.py --check` in a cloud worker is usually
  the CI `opencode.json` under `skip-worktree`; compare with `.github/opencode.ci.json`
  and `HEAD:opencode.json` before blaming the change, and never edit that protected
  file as a worker.
- `bash apps/staff-web/check.sh` is the authoritative frontend gate; paste its
  typecheck and coverage lines into the notes rather than a standalone Vitest run.
- `data-breakpoint` is the established responsive-band convention; prefer the shared
  `page` utility and add only the missing wrap/shrink/logical-text-start inline
  styles. `within(root)` does not exclude nested state panels, so scope heading
  queries by accessible name when a page renders `EmptyState`.

## Why this is not finished

The scoped RESP-WELCOME-PAGE work is finished and green, but this branch's pull
request cannot pass the repository-wide gate, so the story is not accepted.

- **Failing command:** `python3 -I -m unittest discover -s scripts/tests -p 'test_worker_gate*.py'`
  (and therefore `bash scripts/python-coverage.sh` / `bash scripts/check.sh`).
- **Error:** `Ran 143 tests in 0.052s` / `FAILED (errors=2)`, both
  `FileNotFoundError: [Errno 2] No such file or directory: 'apps/api/target/surefire-reports'`
  at `scripts/worker_gate.py:63` in `usable_reports()`.
- **Most likely cause:** `usable_reports()` globs a directory that does not exist in
  a fresh checkout; two pre-existing tooling tests mock `is_dir` but not the glob.
  This is pre-existing on `origin/main` (empty diff in `scripts/`) and unrelated to
  the WelcomePage change.
- **What was tried:** reproduced the failure from the current branch; confirmed the
  same two errors occur on clean `origin/main`; confirmed the scoped change is green
  (`bash apps/staff-web/check.sh` exit 0, 1917 tests, 100% coverage). Earlier fix
  passes tried `mkdir -p apps/api/target/surefire-reports` locally, which only masks
  the error and is absent in the integrator's checkout. I did not edit `scripts/` or
  create build artifacts because both are outside this task's scope and would fail
  candidate validation / block the PR.
- **Exact next step:** a maintainer applies the bounded fix under "Findings" above
  to `scripts/worker_gate.py::usable_reports()` plus the regression test in
  `scripts/tests/test_worker_reports.py`, runs the four named checks on a checkout
  with no surefire directory, and commits to `main`. Once that lands on `origin/main`,
  re-run `git merge --no-edit origin/main` on this branch and re-run
  `bash scripts/check.sh`; no change to the two scoped staff-web files is needed.
