# RESP-WELCOME-PAGE — fix pass: responsive `WelcomePage` (staff web)

## Summary

Verification and hand-off pass for swarm branch `swarm/RESP-WELCOME-PAGE-861`.
The scoped implementation is already complete and remains green on the current
`origin/main`: `apps/staff-web/src/app/WelcomePage.tsx` renders a labelled
`section.page[data-breakpoint]` through the real `useBreakpoint` hook with
direction-neutral logical inline styles, and
`apps/staff-web/tests/responsive/app/WelcomePage.responsive.test.tsx` drives
`matchMedia` across three bands, the 320–1280 px width matrix in both `en` and
`ar`, and Arabic RTL. **No production or test code was changed in this pass.**
The authoritative frontend gate was re-run from this branch and is green with
100% coverage on every file and on the changed file.

The one remaining blocker is a pre-existing, out-of-scope defect in the
maintainer-owned `scripts/worker_gate.py::usable_reports()` that makes the
repository-wide tooling gate fail on any fresh checkout where the gitignored
Surefire directory does not exist. A swarm worker cannot fix that file: it is
outside this task's declared scope, is a protected file under TODO rule 8, and
a pull request touching it is never auto-merged. The exact reproduction and
bounded maintainer fix are handed off below.

All evidence in this pass came from executed tool commands by the primary
agent, one simulated `smartclinic-senior-react` reviewer (static read only, no
shell, no actionable findings), and previously recorded simulated
`smartclinic-ux-reviewer` review (no actionable findings). No simulated-user or
human/clinical participant supplied evidence. No patient data or credentials
appear in this note.

## Affected requirements

WEB-07, UX-01.

Acceptance mapping: the `data-breakpoint` band attribute and the logical inline
styles in `apps/staff-web/src/app/WelcomePage.tsx` deliver reflow, label
wrapping, safe-area padding and logical-property use at every supported width in
English and Arabic. `apps/staff-web/tests/responsive/app/WelcomePage.responsive.test.tsx`
asserts structure, labels, roles, DOM/keyboard order, the full width matrix in
both locales and the RTL band case. Pixel-level measurement stays with the
Playwright tasks as the task directs; jsdom cannot lay out pixels.

## Risk class

Routine (contained staff-web view plus one isolated test; no cross-boundary or
safety-critical contract), classified per `docs/WORKFLOW.md`. The remaining
hand-off blocker is delivery tooling, not a change in this story.

## Review owner

Primary agent (tool-verified). Bounded simulated `smartclinic-senior-react`
review of the two scoped files: no actionable findings; it confirmed the shared
`page`/`EmptyState`/`useBreakpoint` conventions, logical-properties-only styles,
complete band/width/locale/RTL assertions and no reason to change either file.
That reviewer had no shell and relied on static reading plus the primary
agent's evidence. An earlier simulated `smartclinic-ux-reviewer` review also
returned no actionable findings. No new public contract, migration or clinical
data is involved in this pass, so no new cross-boundary or clinical review is
required.

## Data changes

None. No migration, backfill, lock or volume impact and no reserved migration
version was used.

## Authorization boundaries and audit events

None. Presentation-only change to a placeholder route; no tenant, role,
patient-link or audit behaviour changed.

## Migration and rollout considerations

None.

## Test evidence

Exact commands run in this pass and their final lines (tool evidence unless
noted):

- `git merge --no-edit origin/main` → `Already up to date.` (run at the start;
  re-run before the commit). `git log --oneline -1 origin/main` → `ddc8b13f Take
  0 task(s) for the swarm [skip ci]`. A tree-wide
  `git grep -nE '^(<<<<<<<|>>>>>>>) '` found markers only in three pre-existing,
  out-of-scope `apps/api/.../exchange/hl7/` Java files; neither scoped file has
  a marker.
- `bash apps/staff-web/check.sh` → **exit 0**. Final lines of each stage:
  - `gen:api: generated client matches the contract`
  - typecheck: `> tsc --noEmit -p tsconfig.json` — **no `error TS…`, exit 0**
  - lint: `> eslint . --max-warnings 0` — no warnings, exit 0
  - build: `> tsc --noEmit -p tsconfig.json && vite build`, `✓ built in 182ms`,
    `dist/assets/WelcomePage-CEQyPUXZ.js  0.47 kB │ gzip:  0.32 kB`, WEB-08
    budget row `WelcomePage-CEQyPUXZ.js  329  ok`
  - tests: `Test Files  172 passed (172)`, `Tests  2055 passed (2055)`
  - coverage: `No files with missing coverage.` / `213 files fully covered.` /
    `4 empty files skipped.`
  - The log prints React error stacks from existing negative
    `useI18n`/`useTheme` provider tests; they are expected and do not fail the
    run.
- `node -e` over `apps/staff-web/coverage/coverage-summary.json` →
  `src/app/WelcomePage.tsx` lines 3/3 (100%), statements 3/3 (100%), functions
  1/1 (100%), branches 0/0 (100%).
- `python3 -I -m unittest discover -s scripts/tests -p 'test_worker_gate*.py'`
  with **no** `apps/api/target/surefire-reports` directory → **exit 1**,
  `Ran 143 tests in 0.071s`, `FAILED (errors=2)`, both
  `FileNotFoundError: [Errno 2] No such file or directory: 'apps/api/target/surefire-reports'`
  at `scripts/worker_gate.py:63` in `usable_reports()`. This is the blocker
  reproduced, not a defect of this story (see Findings).
- `git ls-files -v opencode.json` → `S` and worktree file equals
  `.github/opencode.ci.json`; `git diff origin/main...HEAD -- scripts/` → empty.
- `bash scripts/check.sh` (run once at the end, capped at 150 s) → exit 1 with
  final line `check-pack: opencode.json is stale: run scripts/gen-opencode.py`.
  This is the documented cloud-worker CI-profile artifact (OPS-CW-003-01): the
  aggregate gate stopped before the Python tooling stage, so the underlying
  `worker_gate` failure was reproduced directly with the targeted command
  above. The protected file was not regenerated, edited or committed.
- `git diff origin/main...HEAD -- <the two scoped files>` → only the
  `WelcomePage.tsx` responsive wrapper and the new responsive test file; no
  other production file changed on this branch.

Entry point → test trace: `apps/staff-web/src/app/routes.tsx` lazily registers
`WelcomePage` for pattern `/`; `AppShell` renders it in the app main landmark;
`WelcomePage` renders `section.page[data-breakpoint]` plus the `EmptyState`
`role="status"` panel; both are asserted in
`apps/staff-web/tests/responsive/app/WelcomePage.responsive.test.tsx` across the
three bands, the seven-width matrix in both locales and Arabic RTL.

## Coverage

`bash apps/staff-web/check.sh` includes `npm run test:coverage` (Vitest/v8,
per-file thresholds 100/100/100/100). Fresh result from this branch:

```
Statements   : 100% ( 3914/3914 )
Branches     : 100% ( 1990/1990 )
Functions    : 100% ( 1320/1320 )
Lines        : 100% ( 3737/3737 )
```

The only changed production file, `src/app/WelcomePage.tsx`, is 100% on lines
(3/3), statements (3/3), functions (1/1) and branches (0/0).

New/changed branches and their tests — `WelcomePage` adds no `if`, ternary,
`&&`/`||`, `switch` or `catch`; the only variability is the value returned by
the existing `useBreakpoint` hook and the active locale:

- `useBreakpoint() === 'compact'` → the `responsive WelcomePage at compact`
  tests and the 320/360/390 px matrix rows assert `data-breakpoint="compact"`.
- `useBreakpoint() === 'medium'` → the `at medium` tests and the 768/820/1024 px
  rows assert `data-breakpoint="medium"`.
- `useBreakpoint() === 'expanded'` → the `at expanded` tests and the 1280 px row
  assert `data-breakpoint="expanded"`.
- `locale === 'ar'` → the RTL tests assert `dir="rtl"`, `lang="ar"` and the
  Arabic welcome label at all three bands; the matrix also exercises `ar` at
  all seven concrete widths.

No threshold, exclusion, `skip` or pragma was added or changed. No production
line was changed in this pass, so no new production branch was introduced.

## Clinical and architecture review

Not required: presentation-only placeholder route, no signed data, patient
association, prescribing, critical results or cross-organization sharing.

## Findings

- **Scoped story is complete and green on the current base.** First-worker
  implementation plus fix-pass-2 RTL-at-width strengthening pass every
  applicable gate: generated-client drift, typecheck, lint, build, WEB-08 bundle
  budget, 172 test files / 2055 tests and 100% coverage globally and on the
  changed file. No code change was needed in this pass.
- **Blocking, pre-existing, out-of-scope defect — `scripts/worker_gate.py`.**
  `usable_reports()` (lines 54–69) iterates
  `sorted(Path(reports_dir).glob("TEST-*.xml"))` at line 63. `Path.glob` calls
  `os.scandir`, so a missing directory raises `FileNotFoundError` instead of
  yielding no reports. Two tooling tests
  (`test_worker_gate.MainTest.test_integration_failure_still_fails_the_gate` and
  `test_worker_gate_coverage.MainTest.test_maven_failure_with_failing_reports_still_fails`)
  mock `Path.is_dir = True` but glob the real default path
  `apps/api/target/surefire-reports`, a gitignored build artifact absent from a
  fresh checkout. They error, which fails `bash scripts/python-coverage.sh` and
  the policy/tooling stage of `bash scripts/check.sh` in the integrator and in
  Delivery. Confirmed pre-existing: `git diff --stat origin/main -- scripts/`
  is empty and `git log --oneline origin/main -- scripts/worker_gate.py` shows
  the last change is `8f363e02` (FIX-WORKER-REPORTS), not this branch.
- **Exact fix for the maintainer who owns `scripts/` (NOT applied by this
  worker).** Bounded scope: `scripts/worker_gate.py` and the two worker-gate
  test modules. (1) Write the regression test first: a `UsableReportsTest`
  case builds a `Path` under `tempfile.TemporaryDirectory()` that is never
  created and asserts `worker_gate.usable_reports(missing) is False`; it fails
  today with `FileNotFoundError`. In the two failing `MainTest` cases, stop
  mocking `Path.is_dir`; instead create one temp directory containing a
  parseable fixture `TEST-RxTransmissionFlowTest.xml`
  (`<testsuite name="RxTransmissionFlowTest" failures="1" errors="0"/>`) and
  pass its path as `main`'s second argument, still asserting exit code 1 and
  `repair task: still failing`. (2) Make `usable_reports()` total for an absent
  directory: wrap the glob loop so a `FileNotFoundError` returns `False`; keep
  the `ET.ParseError → continue` behaviour and do not change `main()`'s
  `reports.is_dir()` guard. (3) Prove on a checkout with no surefire directory:
  `python3 -I -m unittest discover -s scripts/tests -p 'test_worker_gate*.py'`,
  the full `python3 -I -m unittest discover -s scripts/tests`,
  `bash scripts/python-coverage.sh`, then `bash scripts/check.sh` with 100%
  branches on the touched lines. Do not re-add the rejected
  `mkdir -p apps/api/target/surefire-reports` workaround, do not regenerate or
  commit `opencode.json`, and do not change a threshold, exclusion or skip.
- **Why this worker did not apply that fix.** Task scope is only
  `apps/staff-web/src/app/WelcomePage.tsx` and
  `apps/staff-web/tests/responsive/app/WelcomePage.responsive.test.tsx`. TODO
  rule 8 and the delivery skill forbid a worker from editing `scripts/*`, and
  `worker_gate.validate_candidate` would report any other changed file as a
  scope violation; a swarm PR touching `scripts/` is never auto-merged, so
  editing it here would block this story rather than unblock it. A
  `task-requests` entry cannot be used either: TODO rule 3 requires task scope
  paths that are not workflow, script or agent files. This needs a
  maintainer-owned repair on `origin/main`, not a story-worker change.
- **CI `opencode.json` artifact (OPS-CW-003-01).** `git ls-files -v opencode.json`
  → `S`: the worktree file is the CI profile copied under `skip-worktree`, so
  `check-pack.py` and `gen-opencode.py --check` report it stale in this worker.
  No commit on this branch touches it; it was neither regenerated, edited nor
  committed. Per AGENTS.md the integrator/Delivery own the repository-wide
  gate.
- Pre-existing, outside scope and untouched: `origin/main` contains literal
  conflict markers in three
  `apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7/` Java files
  (`Hl7ParseException.java`, `Hl7Parser.java`, `Hl7Segment.java`). The merge with
  `origin/main` was clean and no marker exists in either scoped file. These
  markers sit inside Javadoc block comments; Java compiles and tests pass, but
  they are not resolved here because those files are outside this task's scope.
- Earlier fix passes are recorded in
  `.agent/pr-notes/RESP-WELCOME-PAGE-fix-20261122-090120.md`,
  `RESP-WELCOME-PAGE-fix-20261122-091056.md` and
  `RESP-WELCOME-PAGE-fix-20261122-110659.md`. The first worker's
  `RESP-WELCOME-PAGE-20261122-084248.md` carried a `## Why this is not finished`
  heading whose body claimed completion (a machine-read early-stop signal);
  this note includes that heading truthfully because the PR cannot pass the
  repository-wide gate while the out-of-scope tooling defect is unfixed.

## Lessons for workers

- `Path.glob` on a missing directory raises `FileNotFoundError` (it calls
  `os.scandir`); an `is_dir()` guard elsewhere does not protect a direct call.
  Make report readers total for an absent directory rather than relying on a
  gitignored build-artifact directory existing.
- A locally red `check-pack`/`gen-opencode.py --check` in a cloud worker is
  usually the CI `opencode.json` under `skip-worktree`. Compare with
  `.github/opencode.ci.json` and `HEAD:opencode.json` before blaming the change,
  and never edit that protected file as a worker.
- `bash apps/staff-web/check.sh` is the authoritative frontend gate; paste its
  typecheck, test and coverage final lines into the notes rather than a
  standalone Vitest run. Its totals change as `main` advances (1917 tests in an
  earlier pass, 2055 here), so record a fresh result from the current SHA.
- `data-breakpoint` is the established responsive-band convention; prefer the
  shared `page` utility and add only the missing wrap/shrink/logical-text-start
  inline styles. `within(root)` does not exclude nested state panels, so scope
  heading queries by accessible name when a page renders `EmptyState`.
- Re-verifying an already-complete story is a valid fix pass: do not churn
  scoped code when the specialist review and every scoped gate are green; spend
  the pass on accurate, reproducible hand-off evidence instead.

## Why this is not finished

The scoped RESP-WELCOME-PAGE work is finished and green, but this branch's pull
request cannot pass the repository-wide gate, so the story is not accepted.

- **Failing command:** `python3 -I -m unittest discover -s scripts/tests -p
  'test_worker_gate*.py'` (and therefore `bash scripts/python-coverage.sh` and
  `bash scripts/check.sh`; the latter stops earlier on the CI-profile artifact
  described above).
- **Error:** `Ran 143 tests in 0.071s` / `FAILED (errors=2)`, both
  `FileNotFoundError: [Errno 2] No such file or directory: 'apps/api/target/surefire-reports'`
  at `scripts/worker_gate.py:63` in `usable_reports()`.
- **Most likely cause:** `usable_reports()` globs a directory that does not
  exist in a fresh checkout; two pre-existing tooling tests mock `is_dir` but
  not the glob. This is pre-existing on `origin/main` (empty diff in
  `scripts/`, last commit `8f363e02`) and unrelated to the WelcomePage change.
- **What was tried:** reproduced the failure from the current branch; confirmed
  the scoped change is green on the current base (`bash apps/staff-web/check.sh`
  exit 0, 172 files / 2055 tests, 100% coverage, WelcomePage.tsx 3/3 lines);
  obtained a no-actionable-findings simulated senior-react review. Earlier fix
  passes tried `mkdir -p apps/api/target/surefire-reports` locally, which only
  masks the error and is absent in the integrator's checkout. I did not edit
  `scripts/` or create build artifacts because both are outside this task's
  scope and would fail candidate validation / block auto-merge.
- **Exact next step:** a maintainer applies the bounded fix under "Findings"
  above to `scripts/worker_gate.py::usable_reports()` plus the regression test
  in the two worker-gate test modules, runs the four named checks on a checkout
  with no surefire directory, and commits to `main`. Once that lands on
  `origin/main`, re-run `git merge --no-edit origin/main` on this branch and
  re-run `bash scripts/worker-check.sh` plus `bash scripts/check.sh`; no change
  to the two scoped staff-web files is needed.
