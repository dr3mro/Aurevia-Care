# RESP-WELCOME-PAGE — fix pass: responsive `WelcomePage` (staff web)

## Summary

Continuation fix pass for swarm branch `swarm/RESP-WELCOME-PAGE-861`. The scoped
implementation from the first worker is already complete and correct: `WelcomePage`
renders a labelled `section.page[data-breakpoint]` through the real `useBreakpoint`
hook with direction-neutral logical inline styles, and a new responsive test drives
`matchMedia` across compact/medium/expanded, the 320–1280 px width matrix and Arabic
RTL. **No production code needed changing in this pass.** A bounded senior-react
review found one low-impact test gap — RTL was never combined with a concrete width —
so this pass strengthened the width-matrix test to cover all seven widths in both
`en` and `ar`; no behaviour, text, export or production line changed. The earlier
"local checks: failed" status was caused by two worker-environment artifacts, not
by the scoped files; both were diagnosed and resolved locally without committing any
protected file or build artifact. All applicable gates for the change are green and
100% coverage is proven on every file touched. All evidence below is from executed
tool commands (primary agent); no human or clinical participant.

## Affected requirements

WEB-07, UX-01.

Acceptance mapping: the `data-breakpoint` band attribute and the logical inline
styles in `apps/staff-web/src/app/WelcomePage.tsx` deliver reflow, label wrapping,
safe-area padding and logical-property use at every supported width in English and
Arabic. `apps/staff-web/tests/responsive/app/WelcomePage.responsive.test.tsx` asserts
structure, labels, roles, DOM/keyboard order, the full width matrix in both English
and Arabic, and the RTL band case. Pixel-level measurement stays with the Playwright
tasks, as the task directs.

## Risk class

Routine (contained staff-web view plus one isolated test; no cross-boundary or
safety-critical contract). Classified per `docs/WORKFLOW.md`.

## Review owner

Primary agent (tool-verified). The first pass recorded simulated
`smartclinic-senior-react` and `smartclinic-ux-reviewer` reviews, both with no
actionable findings; those subagents had no shell and relied on primary-agent
evidence. This fix pass changes no code, so no new public contract, migration or
clinical data is involved and no new cross-boundary or clinical review is required.
A fresh bounded `smartclinic-senior-react` advisory review was run for this pass: it
found no production-code issue and one low-impact test gap (RTL not exercised at a
concrete width), which was fixed by parameterizing the width matrix over `en` and
`ar`. No human participant.

## Data changes

None. No migration, backfill, lock or volume impact, and no reserved migration version
was used.

## Authorization boundaries and audit events

None. Presentation-only change to a placeholder route; no tenant, role, patient-link
or audit behaviour changed.

## Migration and rollout considerations

None.

## Test evidence

Exact commands run and their final lines (all observed in this pass):

- `git merge --no-edit origin/main` → `Already up to date.` (merge performed at the
  start and re-checked before finishing).
- Conflict-marker grep over the two scoped files → no markers. A tree-wide grep for
  leading merge-marker lines found markers only in three pre-existing, out-of-scope
  `apps/api/.../hl7/` Java files (see Findings).
- `bash apps/staff-web/check.sh` → **exit 0** (installs pinned deps with
  `npm ci --no-audit --no-fund`, `added 486 packages in 7s`). Final lines of each
  stage:
  - `gen:api: generated client matches the contract`
  - typecheck: `> smartclinic-staff-web@0.1.0 typecheck` then
    `> tsc --noEmit -p tsconfig.json` — **no `error TS…`, exit 0**.
  - lint: `> eslint . --max-warnings 0` — no warnings, exit 0.
  - build: `✓ built in 159ms`; `dist/assets/WelcomePage-DcjT3cc2.js 0.47 kB │ gzip: 0.32 kB`;
    WEB-08 budget table `WelcomePage-DcjT3cc2.js  328  ok` (all chunks `ok`).
  - `Test Files  159 passed (159)` / `Tests  1917 passed (1917)` (final run after the
    RTL-at-width test strengthening; the pre-change run was 1910 passing).
  - `No files with missing coverage.` / `207 files fully covered.` /
    `4 empty files skipped.`
  - The log prints React error stacks from existing negative tests
    (`ErrorBoundary.test.tsx`, `I18nProvider.test.tsx`, `ThemeProvider.test.tsx`,
    `UnsupportedBrowserPage`); they are expected and do not fail the run.
- Targeted `./node_modules/.bin/vitest run tests/responsive/app/WelcomePage.responsive.test.tsx`
  → `Test Files  1 passed (1)` / `Tests  26 passed (26)` after the change (19 before;
  +7 cases combine each of the seven widths with `ar`).
- Per-file coverage: `node -e` over `apps/staff-web/coverage/coverage-summary.json`
  → `src/app/WelcomePage.tsx` `{"lines":{"total":3,"covered":3,"pct":100},"functions":{"total":1,"covered":1,"pct":100},"statements":{"total":3,"covered":3,"pct":100},"branches":{"total":0,"covered":0,"pct":100}}`.
- `python3 -I scripts/gen-opencode.py --check` initially → `opencode.json is out of date: run scripts/gen-opencode.py` (exit 1) — CI-profile artifact, see Findings. After restoring the HEAD profile → exit 0, no output.
- `bash scripts/python-coverage.sh` initially → `Ran 1118 tests ... FAILED (errors=2)`
  in `test_worker_gate.py` and `test_worker_gate_coverage.py` (`FileNotFoundError:
  'apps/api/target/surefire-reports'`). After creating that gitignored build-artifact
  directory → exit 0. See Findings.
- `bash scripts/check.sh` (run once at the end): after the two environment fixes it
  passed policy, docs/secrets/names/wiring/migrations, python tooling tests and the
  frontend standards gate, then entered the full Java module gate (`mvn ... verify`)
  and was stopped at the 5-minute worker limit (exit 137 after stray Maven/Surefire
  processes were terminated). The full Java, database and integration gates belong to
  the Integrator and Delivery; this is not a change defect.

Entry point → test trace: `apps/staff-web/src/app/routes.tsx` lazily registers
`WelcomePage` for pattern `/`; `AppShell` renders it inside the app main landmark;
`WelcomePage` renders `section.page[data-breakpoint]` plus the `EmptyState`
`role="status"` panel; both are asserted in
`apps/staff-web/tests/responsive/app/WelcomePage.responsive.test.tsx` across the three
bands, the width matrix and Arabic RTL.

## Coverage

`bash apps/staff-web/check.sh` includes `npm run test:coverage` (Vitest/v8, per-file
thresholds 100/100/100/100). Result:

```
Statements   : 100% ( 3722/3722 )
Branches     : 100% ( 1892/1892 )
Functions    : 100% ( 1258/1258 )
Lines        : 100% ( 3556/3556 )
```

The only changed production file, `src/app/WelcomePage.tsx`, is 100% on lines (3/3),
statements (3/3), functions (1/1) and branches (0/0).

New/changed branches and their tests — `WelcomePage` adds no `if`, ternary,
`&&`/`||`, `switch` or `catch`; the only variability is the value returned by the
existing `useBreakpoint` hook and the active locale:

- `useBreakpoint() === 'compact'` → `responsive WelcomePage at compact` tests and the
  320/360/390 px rows of the width matrix assert `data-breakpoint="compact"`.
- `useBreakpoint() === 'medium'` → `responsive WelcomePage at medium` tests and the
  768/820/1024 px rows assert `data-breakpoint="medium"`.
- `useBreakpoint() === 'expanded'` → `responsive WelcomePage at expanded` tests and the
  1280 px row assert `data-breakpoint="expanded"`.
- `locale === 'ar'` → the `responsive WelcomePage right-to-left` tests assert
  `dir="rtl"`, `lang="ar"` and the Arabic welcome label at all three bands; the default
  English path is asserted by the per-band and width-matrix tests.

No threshold, exclusion, `skip` or pragma was added or changed. No production code was
changed in this fix pass, so no new production branch was introduced. The test-only
change adds seven `locale === 'ar'` cases at concrete widths, exercising the existing
Arabic path alongside the band-level RTL tests; the test file itself is not measured
by the production coverage threshold.

## Clinical and architecture review

Not required: presentation-only placeholder route, no signed data, patient association,
prescribing, critical results or cross-organization sharing.

## Findings

- **No production code change was needed.** The first worker's implementation and
  responsive test are complete and correct; every scoped gate is green. This pass is
  verification, environment triage and one test-strengthening fix (below).
- **Reviewer finding, resolved:** the `smartclinic-senior-react` review observed that
  the width-matrix test rendered only with the default `en` locale while the RTL block
  covered only the three abstract bands, leaving the "RTL at a concrete width"
  acceptance criterion unverified (low impact — `useBreakpoint` is locale-independent).
  Fixed by parameterizing `describe.each(widths)` over `['en', 'ar']`, rendering with
  `I18nProvider initialLocale={locale}` and asserting `document.documentElement.dir`
  / `lang` and the Arabic heading name on the `ar` pass. Targeted test went 19 → 26
  passing; full gate 1910 → 1917 passing, 100% coverage unchanged.
- **Root cause of "local checks: failed", part 1 — CI `opencode.json`.** In this cloud
  worker, `opencode.json` is the CI profile copied under `skip-worktree`
  (`git ls-files -v opencode.json` → `S`; worktree byte-identical to
  `.github/opencode.ci.json`, different from `HEAD:opencode.json`), so `check-pack.py`
  and `gen-opencode.py --check` report it stale. No commit on this branch touches
  `opencode.json`. The harness (`scripts/agent-run.sh` `restore_config`) restores the
  HEAD profile before its own check; for my self-run gate I restored it the same way
  (`git update-index --no-skip-worktree opencode.json && git checkout -q -- opencode.json`)
  → flag `H`, worktree == HEAD, `gen-opencode.py --check` exit 0. No protected file was
  regenerated, edited or committed.
- **Root cause of "local checks: failed", part 2 — missing build-artifact directory.**
  Two pre-existing, unrelated tooling tests (`test_worker_gate.MainTest.test_integration_failure_still_fails_the_gate`
  and `test_worker_gate_coverage.MainTest.test_maven_failure_with_failing_reports_still_fails`)
  mock `Path.is_dir = True` but then glob the real path `apps/api/target/surefire-reports`.
  In a fresh worker that gitignored build-artifact directory does not exist, so
  `usable_reports()` raises `FileNotFoundError` and `python-coverage.sh` fails with
  `FAILED (errors=2)`. Creating `apps/api/target/surefire-reports` (gitignored via
  `.gitignore:4 target/`, never committed) made `python-coverage.sh` exit 0. This is an
  environment dependency of those tests, not a defect in the WelcomePage change.
- After both fixes, `scripts/check.sh` progressed through every policy/docs/tooling/
  frontend stage and into the full Java `mvn verify` module gate; per the worker time
  rule it was stopped at 5 minutes rather than run to completion. Integrator/Delivery
  own that gate and the database/integration suites.
- Pre-existing, outside scope and untouched: committed `origin/main` contains literal
  conflict markers in three `apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7/`
  Java files (`Hl7ParseException.java`, `Hl7Parser.java`, `Hl7Segment.java`). The merge
  with `origin/main` reported "Already up to date" and no marker exists in either
  scoped file.
- `EmptyState` renders a nested `section.page[role="status"]`; the outer wrapper also
  uses `page`. The nesting is intentional and asserted by the tests. Heading queries
  are scoped by accessible name because the status panel has a second `<h2>`.
- The first worker's notes (`RESP-WELCOME-PAGE-20261122-084248.md`) carried a
  `## Why this is not finished` heading whose body claimed completion; that heading is
  a machine-read early-stop signal and is why the first pass was treated as not
  finishing cleanly. This note deliberately omits it because the story is complete.

## Lessons for workers

- A locally red `check-pack`/`gen-opencode.py --check` in a cloud worker is usually the
  CI `opencode.json` under `skip-worktree`: compare the worktree file with
  `.github/opencode.ci.json` and `HEAD:opencode.json` before blaming the change. Restore
  the HEAD profile the way `agent-run.sh restore_config` does; never regenerate, edit or
  commit that protected file as a worker.
- Two `worker_gate` tooling tests require the gitignored `apps/api/target/surefire-reports`
  directory to exist even when they mock `is_dir`. In a fresh worker, `mkdir -p
  apps/api/target/surefire-reports` before `scripts/check.sh` avoids the spurious
  `FileNotFoundError` / `FAILED (errors=2)`; the directory is a build artifact and is
  never committed.
- `bash apps/staff-web/check.sh` is the authoritative frontend gate: it covers
  generated-client drift, typecheck, lint, build, WEB-08 bundle budget and 100%
  coverage in one command. Paste its typecheck lines and coverage summary into the
  notes rather than relying on a standalone Vitest run.
- `data-breakpoint` is the established convention for exposing the responsive band;
  prefer the shared `page` utility for safe-area/logical padding and add only the
  missing wrap/shrink/logical-text-start inline styles.
- `within(root)` does not exclude nested state panels; scope heading queries by
  accessible name when a page renders `EmptyState`.
- Omit `## Why this is not finished` when the story is complete — it is a handoff
  signal for genuine early stops, not a place for a "not applicable" note.
