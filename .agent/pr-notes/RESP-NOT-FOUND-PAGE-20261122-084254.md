# RESP-NOT-FOUND-PAGE — Staff web: responsive `NotFoundPage` (app)

## Summary

Made the application catch-all page responsive with presentation-only changes.
`NotFoundPage` now uses the existing `page` utility (safe-area-aware logical
padding and centred reading measure), exposes `data-breakpoint` from the shared
`useBreakpoint` hook, and wraps the heading plus the existing `EmptyState` in a
`stack` container. Logical inline styles keep both children shrinkable,
start-aligned and wrappable; no clinical field or column exists here and nothing
is hidden. No behaviour, text, API call, callback or exported name changed.
Added a dedicated responsive test that mocks `matchMedia` for compact, medium
and expanded, resolves all seven required widths in English and Arabic, and
asserts structure, labels/roles, keyboard order and RTL isolation.

## Affected requirements

- WEB-07 (responsive staff web from 320 px upward)
- UX-01 (no horizontal scroll, wrapping text, logical properties, RTL, touch/zoom)

## Risk class

Routine — contained presentation change to one component plus one new test file.
No API, database, contract, authorization, migration or clinical-policy surface
was touched (docs/WORKFLOW.md).

## Review owner

Senior React and Vite (`smartclinic-senior-react`) and UX reviewer
(`smartclinic-ux-reviewer`), both simulated bounded reviews. Their findings are
addressed under Findings below. No human or real-clinical evidence is claimed.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix.

## Authorization boundaries and audit events

None. Tenant, role and patient-link scope are unchanged; no audit event added
or changed. The page is data-free and fetched from no endpoint.

## Migration and rollout considerations

None. CSS-class and logical-inline-style presentation only; no data, endpoint
or authorization change. No reserved migration version was used.

## Test evidence

Evidence provenance: executed tool commands (local Vitest, tsc, eslint, Vite,
bundle-size script and policy scripts) plus two simulated specialist reviews.
No simulated-user or human-participant evidence is claimed. Synthetic data only
(locale codes `en`/`ar` and the existing static shell/state labels); no patient
identifier, credential or real domain name appears in code or notes.

Entry point to test trace: `src/app/routes.tsx` lazy-registers `NotFoundPage`
for the `*` catch-all (`routes.tsx:33-34,89`); the rendered component uses
`useI18n()` → `shellText(locale, 'notFound')` for the `<h2>` and renders
`EmptyState` → `StatePanel` (`role="status"`, `aria-live="polite"`). The new
test renders `NotFoundPage` directly under `ThemeProvider` + `I18nProvider`
with a stubbed `matchMedia` and asserts the resulting DOM at each band.

Red first (before the component change):

- `./node_modules/.bin/vitest run tests/responsive/app/NotFoundPage.responsive.test.tsx --reporter=dot`
  → `Test Files  1 failed (1)` / `Tests  23 failed (23)` — the root had no
  `data-breakpoint`, `page` class or `stack` container.

Green after implementation (all from `apps/staff-web`):

- `./node_modules/.bin/vitest run tests/responsive/app/NotFoundPage.responsive.test.tsx --reporter=dot`
  → `Test Files  1 passed (1)` / `Tests  32 passed (32)`.
- `npm run typecheck` → `> tsc --noEmit -p tsconfig.json` — exit 0, **no `error TS…`**.
- `bash apps/staff-web/check.sh` → `EXIT=0`, with:
  - `gen:api: generated client matches the contract`
  - typecheck `> tsc --noEmit -p tsconfig.json` (exit 0, no output)
  - lint `> eslint . --max-warnings 0` (exit 0, no output)
  - build `✓ built in 163ms`
  - bundle budget: every chunk `ok` (including `NotFoundPage-9qWJactF.js 370 ok` and `NotFoundPage-C8m0ifMn.js 205 ok`)
  - `Test Files  159 passed (159)` / `Tests  1923 passed (1923)`
- `python3 -I scripts/check-names.py`, `check-secrets.py`, `check-wiring.py`,
  `check-docs.py` → exit 0 each (run from repository root).
- `git merge --no-edit origin/main` → `Already up to date.` before and after the
  change; branch `swarm/RESP-NOT-FOUND-PAGE-861` stayed 1:1 with `origin/main`
  (`e590b1d8`) until the local commit.

Note on `scripts/check.sh`: it was intentionally **not** run as a whole because
it invokes `apps/api/check.sh` → `mvn -q -B verify`, which the worker prompt
explicitly forbids (over ten minutes / timeout). Its frontend and policy steps
were run individually instead and are green, as recorded above and in Coverage.

The `useTheme must be used inside ThemeProvider` stack traces in the full test
log come from the pre-existing negative-path test
`tests/theme/ThemeProvider.test.tsx`; they are not failures (159/159 files and
1923/1923 tests passed).

## Coverage

- Command: `npm run test:coverage` via `bash apps/staff-web/check.sh`
  (v8, `perFile: true`, thresholds 100% for lines/branches/functions/statements).
- Result: `No files with missing coverage.` / `207 files fully covered.` /
  Statements `100% (3722/3722)`, Branches `100% (1892/1892)`,
  Functions `100% (1258/1258)`, Lines `100% (3556/3556)`.
- Touched production file `src/app/NotFoundPage.tsx`: 100% on all four metrics
  (included in the run above; no missed line or branch).

New or changed branches in production code: **none**. The component adds no
`if`, ternary, `&&`, `||`, `switch` or `catch`; `data-breakpoint` is a value
read from the unchanged shared `useBreakpoint()` hook. Each of the three values
it can report is nevertheless asserted through the mocked media queries:

- `compact` → the three `%s width` band tests at `compact` and the Arabic
  widths 320/360/390 (both assert `data-breakpoint="compact"`).
- `medium` → the three band tests at `medium` and widths 768/820/1024.
- `expanded` → the three band tests at `expanded` and width 1280.

The hook's own medium/expanded/compact branches live in untouched
`src/theme/useBreakpoint.ts` / `breakpoints.ts` and are already covered by
`tests/theme/breakpoints.test.ts`; they are exercised again here end to end.

No threshold, exclusion, `@ts-ignore`, `any`, ignore comment or skipped test was
added or changed. No test was weakened or deleted.

## Clinical and architecture review

Not required: no clinical decision, signed data, cross-organization sharing,
patient association, prescribing or critical-result behaviour is involved. The
change is presentation only and touched no domain contract or state machine.

## Findings

- **Simulated senior-React review (addressed).** It confirmed no new production
  branches, all logical inline properties, unchanged text/exported names and
  correct structure/labels/keyboard/RTL assertions. It found that the test's
  camelCase `physicalStyleKeys` made `getPropertyValue(...)` vacuous in CSSOM
  (keys must be dash-case). Fixed: keys are now `margin-left`, `margin-right`,
  `padding-left`, `padding-right`, `border-left`, `border-right`, `left`,
  `right`. After the fix the assertion correctly failed on a pre-existing
  physical `border` shorthand on the nested `EmptyState` panel; the assertion
  is now scoped to the elements this component styles, since `StateViews.tsx`
  is owned by RESP-STATEVIEWS and outside this scope.
- **Simulated UX review (addressed).** It found RTL was exercised only at the
  compact band and that the RTL test relied on the global setup to reset
  `document.dir/lang`. Fixed: the RTL test now runs at compact, medium and
  expanded, an Arabic 7-width matrix was added alongside the English one, and
  `afterEach` explicitly removes `dir`/`lang`.
- **No dedicated page-title class in `responsive.css`.** Per task step 3 the
  stylesheet was not edited; centring/shrink/wrap use logical inline styles
  (`inline-size`, `min-inline-size`, `overflow-wrap: anywhere`,
  `text-align: start`). Recorded under Lessons for workers.
- **Pre-existing, out of scope — committed conflict markers.** After a clean
  `git merge --no-edit origin/main` (`Already up to date`), `git grep -nE
  '^(<<<<<<<|>>>>>>>) '` reported markers already committed in `HEAD` on
  `origin/main` inside `apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7/Hl7ParseException.java`,
  `Hl7Parser.java` and `Hl7Segment.java` (a BUILD-37 merge artifact). They are
  not from this change and are outside the declared scope; no file was edited.
- **Pre-existing/environmental — `check-pack.py`.** `python3 -I scripts/check-pack.py`
  reports `opencode.json is stale`. Confirmed as the documented cloud-worker
  CI-profile artifact: `git ls-files -v opencode.json` → `S opencode.json`,
  the worktree file equals `.github/opencode.ci.json`, and `HEAD:opencode.json`
  differs from it. No `.opencode/`, `opencode.json` or script file was touched.

## Lessons for workers

- CSSOM `style.getPropertyValue()` takes dash-case property names. An assertion
  with camelCase keys (e.g. `marginLeft`) always returns `''` and proves
  nothing; use `margin-left` etc. when enforcing "logical properties only".
- When a component renders nested shared components, scope a physical-property
  assertion to the elements that component itself styles. A shared component
  can carry a pre-existing physical shorthand owned by a different task; the
  nested component's own responsive test owns that contract.
- In jsdom an inline `minInlineSize: 0` reads back as `'0'`, not `'0px'`.
- `I18nProvider` writes `document.documentElement.dir/lang` in an effect. Reset
  both in the test's own `afterEach`, and call `unmount()` between two renders
  in one `it` (cleanup runs per test, not per render).
- Cover RTL at every band, not only compact: use `describe.each` over the three
  bands plus a widths matrix for both `en` and `ar`. jsdom cannot paint pixels,
  so assert classes, `data-breakpoint`, logical inline-style strings, roles,
  labels and DOM order; leave measured 44 px targets, 320 px reflow and 200
  percent zoom to the Playwright tasks.
- `scripts/check.sh` runs `apps/api/check.sh` → `mvn -q -B verify`. Under the
  worker "no full verify" rule, run the module `check.sh` and the relevant
  policy scripts individually and record each command and final line instead.
