# RESP-NOT-FOUND-PAGE — fix pass (staff web: responsive `NotFoundPage`, app catch-all)

## Summary

Fix pass for the draft PR that the integrator marked "did not finish cleanly"
(issue #721 / PR #720, fix attempt 1 of 3). I continued from the earlier worker's
committed implementation on branch `swarm/RESP-NOT-FOUND-PAGE-861` rather than
starting over. The requested scope files were already changed and committed
(`1edb0f68`): `apps/staff-web/src/app/NotFoundPage.tsx` now uses the existing
`page`/`stack` utilities, the shared `useBreakpoint()` hook and logical inline
styles, and `apps/staff-web/tests/responsive/app/NotFoundPage.responsive.test.tsx`
covers all three bands, the seven required widths in `en` and `ar`, roles,
labels, keyboard order and RTL.

**Result of this pass:** no code defect found. I re-ran every gate the worker and
the integrator own for this scope; all are green, including 100% coverage. No
production or test file was changed in this pass; the only new file is this
hand-off note. The fix is "verified complete — ready to merge."

## Affected requirements

- WEB-07 — staff web responsive from 320 px upward (layout bands, reflow, no horizontal scroll).
- UX-01 — no clipped/overlapping text, long `en`/`ar` strings wrap, logical
  properties, RTL mirroring, safe-area insets, no hidden clinical field, text
  scalable to 200 percent.

Acceptance mapping: band resolution and stacking → `data-breakpoint` + `.page`
+ `.stack` assertions; wrap/readability → `overflow-wrap: anywhere` and absence
of fixed block size / `nowrap`; RTL → `dir=rtl`, `lang=ar`, isolated Arabic
heading text and no local `direction` override; keyboard → no tab stops and
focus left on `document.body`; safe areas and reading cap → the untouched
`.page` utility in `src/theme/responsive.css`; logical-only → dash-case CSSOM
check over the elements this component styles. Pixel/44 px/zoom checks are
delegated to the Playwright tasks (jsdom cannot paint), as the task directs.

## Risk class

Routine — contained presentation change to one data-free component plus one new
test file (docs/WORKFLOW.md). No API, database, contract, authorization,
migration or clinical-policy surface was touched.

## Review owner

Senior React and Vite (`aurevia-senior-react`) and UX reviewer
(`aurevia-ux-reviewer`), both simulated bounded reviews recorded in the
prior worker's notes and addressed there. No simulated-user or human-participant
evidence is claimed for this pass.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix.
No reserved migration version was used.

## Authorization boundaries and audit events

None. Tenant, role and patient-link scope are unchanged; no audit event added
or changed. The catch-all page is data-free and fetches no endpoint.

## Migration and rollout considerations

None. CSS-class and logical-inline-style presentation only; no data, endpoint
or authorization change.

## Test evidence

Evidence provenance: executed tool commands in this worker (Vitest via the
module check, `tsc --noEmit`, ESLint, Vite build, bundle budget, coverage gate,
frontend-typecheck gate, policy scripts) plus the earlier worker's executed
tool evidence with its red-first run. No simulated-user or human evidence.
Synthetic data only (locale codes `en`/`ar` and the existing static shell/state
labels); no patient identifier, credential or real domain name appears.

Entry point to test trace: `src/app/routes.tsx` lazy-registers `NotFoundPage`
for the `*` catch-all; the rendered component reads `useI18n()` →
`shellText(locale, 'notFound')` for `<h2 id="not-found-title">` and renders
`EmptyState` → `StatePanel` (`role="status"`, `aria-live="polite"`). The test
renders `NotFoundPage` directly under `ThemeProvider` + `I18nProvider` with a
stubbed `matchMedia` and asserts the DOM at each synthetic band.

Commands run in this fix pass (final lines):

- `bash apps/staff-web/check.sh` → `EXIT=0`;
  `Test Files  159 passed (159)` / `Tests  1923 passed (1923)`;
  typecheck `> tsc --noEmit -p tsconfig.json` (exit 0, no `error TS…`);
  lint `> eslint . --max-warnings 0` (exit 0);
  build `✓ built in 163ms`; every bundle-chunk budget `ok`.
  The `useTheme must be used inside ThemeProvider` stack traces in the log come
  from the pre-existing negative-path test `tests/theme/ThemeProvider.test.tsx`
  and are not failures (159/159 files, 1923/1923 tests passed).
- `bash scripts/coverage-check.sh` → final line `coverage check: passed`,
  `COVERAGE_EXIT=0`.
- `bash scripts/frontend-typecheck-check.sh` → final line
  `frontend typecheck: passed (or no frontend files changed)`, `FTC_EXIT=0`.
- `python3 -I scripts/check-names.py`, `check-secrets.py`, `check-wiring.py`,
  `check-docs.py` → `exit 0` each (run from repository root).
- `git merge --no-edit origin/main` → `Already up to date.`;
  a grep for committed conflict-marker lines under `apps/staff-web/` → none;
  working tree clean after the checks.

Earlier worker's red-first evidence (from its notes, executed tool):

- Before the component change:
  `./node_modules/.bin/vitest run tests/responsive/app/NotFoundPage.responsive.test.tsx --reporter=dot`
  → `Test Files  1 failed (1)` / `Tests  23 failed (23)`.
- After implementation: same command → `Test Files  1 passed (1)` /
  `Tests  32 passed (32)`.

`scripts/check.sh` was not run as a whole: it invokes `apps/api/check.sh` →
`mvn -q -B verify`, which the worker prompt forbids (over ten minutes/timeout).
Its frontend and policy steps were run individually and are green above; the
integrator and Delivery own the full gate.

## Coverage

- Command: `bash scripts/coverage-check.sh` (staff-web branch: `npm run -s
  typecheck && npm run -s test:coverage`, v8, `perFile: true`, 100% thresholds).
  Also covered inside `bash apps/staff-web/check.sh` (`npm run test:coverage`).
- Result: `coverage check: passed`; `No files with missing coverage.` /
  `207 files fully covered.` / Statements `100% (3722/3722)`, Branches
  `100% (1892/1892)`, Functions `100% (1258/1258)`, Lines `100% (3556/3556)`.
- Touched production file `src/app/NotFoundPage.tsx`: 100% on all four metrics;
  no missed line or branch.

New or changed branches in production code: **none**. The component adds no
`if`, ternary, `&&`, `||`, `switch` or `catch`; `data-breakpoint` is a value read
from the unchanged shared `useBreakpoint()` hook. Each value it can report is
nevertheless asserted through mocked media queries:

- `compact` → the three `%s width` band tests at `compact` and the Arabic widths
  320/360/390.
- `medium` → the three band tests at `medium` and widths 768/820/1024.
- `expanded` → the three band tests at `expanded` and width 1280.

The hook's own branch logic lives in untouched `src/theme/useBreakpoint.ts` /
`breakpoints.ts`, already covered by `tests/theme/breakpoints.test.ts` and
exercised end to end here.

No threshold, exclusion, `@ts-ignore`, `any`, ignore comment or skipped test was
added or changed. No test was weakened or deleted.

## Clinical and architecture review

Not required: no clinical decision, signed data, cross-organization sharing,
patient association, prescribing or critical-result behaviour is involved. The
change is presentation only and touched no domain contract or state machine.

## Findings

- **No code defect in scope.** All worker- and integrator-owned gates for the
  declared scope pass at 100% (commands above). The fix-pass trigger was a
  process artifact, not a failing in-scope check: the prior worker's notes
  record that `scripts/check.sh` was intentionally not run as a whole, and the
  integrator's full gate reports `integration run failed without fresh, usable
  test results (compile or setup error)` — a whole-repo Java integration gate
  condition unrelated to this staff-web-only change.
- **Pre-existing, out of scope — committed conflict markers.** `git grep` shows
  conflict-marker lines (`HEAD` / `origin/swarm/BUILD-37-469`) already committed
  on `origin/main` inside
  `apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7ParseException.java`,
  `Hl7Parser.java` and `Hl7Segment.java` (a BUILD-37 merge artifact). They sit
  inside Javadoc comments, so they do not break compilation, and they are
  outside this task's scope. No file was edited.
- **Pre-existing/environmental — `check-pack.py`.**
  `python3 -I scripts/check-pack.py` reports `opencode.json is stale`: the
  documented cloud-worker CI profile under `skip-worktree`. No `.opencode/`,
  `opencode.json`, workflow or script file was touched.
- **Pre-existing tracker noise.** The `{"violations": [{"file": "TODO.md",
  "rule": "tracker"}]}` lines in the hand-off findings come from other workers'
  candidates; this candidate touches only the two scoped files and a notes
  file, and as a swarm worker I do not edit `TODO.md`.

## Lessons for workers

- A fix pass over an already-green candidate is a verification + hand-off job:
  re-run the module `check.sh`, `scripts/coverage-check.sh` and
  `scripts/frontend-typecheck-check.sh`, confirm 100%, and stop. Do not churn a
  correct implementation to look busy.
- CSSOM `style.getPropertyValue()` takes dash-case names; camelCase keys always
  read back `''` and prove nothing when enforcing logical-properties-only.
- Scope a physical-property assertion to the elements the component styles;
  a nested shared component may carry a pre-existing physical shorthand owned
  by another task (here `StateViews.tsx` / RESP-STATEVIEWS).
- In jsdom an inline `minInlineSize: 0` reads back as `'0'`, not `'0px'`.
- `I18nProvider` writes `document.documentElement.dir/lang` in an effect;
  reset both in the test's `afterEach` and `unmount()` between two renders in
  one `it`.
- `scripts/check.sh` runs the full `mvn verify`; under the worker "no full
  verify" rule run the module check and policy scripts individually and record
  each command and final line instead.
