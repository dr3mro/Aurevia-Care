## Summary

Made `apps/staff-web/src/features/system/NotFoundPage.tsx` responsive without changing its data, text, actions, API calls or exported names. The page now wraps the shared `SystemNotice` in the `page` utility (safe-area-aware logical padding and centred reading measure) and adds `data-breakpoint` from `useBreakpoint()` plus logical inline styles that centre the data-free notice and let long English and Arabic strings wrap without a horizontal scroll. The only interactive control keeps the shared `Button`'s `touch` class (24 px fine / 44 px coarse floor owned by `responsive.css`) and 16 px-equivalent logical padding. Added the scoped jsdom test file with `matchMedia` mocks for compact, medium and expanded, asserting structure, labels and roles, keyboard order and the Arabic RTL case.

## Affected requirements

- WEB-07 (supported clients / compact layout; `PROJECT_V1_SPEC.md` line 155) and UX-01 (English/Arabic, RTL, accessibility; line 90).
- Layout contract: `docs/design/responsive-layout.md` (decision `docs/decisions/0004-responsive-staff-web.md`), sections 2, 3, 6, 7, 11, 12, 13 (R1, R3, R5, R6, R7, R11, R12).

## Risk class

Routine — contained presentation change to one data-free staff-web view. No authorization, clinical data, API, contract, migration or deployment boundary is touched (`docs/WORKFLOW.md`).

## Review owner

Senior React and Vite (`orevia-senior-react` skill loaded and applied). UX evidence is simulated jsdom structure against the responsive-layout contract; no real clinician or human participant review was performed. Cross-client TypeScript tooling was checked by `apps/staff-web/check.sh`.

## Data changes

None. No migrations, backfills, locks, volume impact, rollback or forward-fix.

## Authorization boundaries and audit events

None. The page fetches nothing, renders no tenant, role or patient scope, and adds no audit events.

## Migration and rollout considerations

None beyond the normal staff-web bundle rollout; the `NotFoundPage` chunk remains within the WEB-08 gzip budget (budget output: all chunks `ok`).

## Test evidence

Entry-point-to-test trace: catch-all route `*` → `src/features/system/routes.ts` (`/not-found` and the app catch-all) → `features/system/NotFoundPage` → `div.page[data-breakpoint]` → `SystemNotice` (`section[aria-label]`, `h1`, `p`) → `Button` (`button.touch`). Test reads `getByRole('heading', { level: 1 }).closest('.page')`, `getByRole('region', { name })`, and `getByRole('button', { name })`; each band is selected by mocked `matchMedia`.

Commands actually run (final lines quoted):

- `cd apps/staff-web && npm ci --no-audit --no-fund` → `added 486 packages in 4s`.
- RED before the component change — `./node_modules/.bin/vitest run tests/responsive/features-system/NotFoundPage.responsive.test.tsx` → `Test Files 1 failed (1)`, `Tests 4 failed | 4 passed (8)`; failure `Error: not-found notice is not inside a responsive page wrapper`. The four behaviour/label/keyboard tests already passed, confirming no behaviour change was needed.
- GREEN after the change — `./node_modules/.bin/vitest run tests/responsive/features-system/NotFoundPage.responsive.test.tsx tests/features/system/systemPages.test.tsx` → `Test Files 2 passed (2)`, `Tests 37 passed (37)` (existing system page tests still green).
- `bash apps/staff-web/check.sh` → `gen:api: generated client matches the contract`; typecheck and lint exited clean; `✓ built in 132ms`; bundle budget all `ok`; `Test Files 159 passed (159)`; `Tests 1899 passed (1899)`.
  - Typecheck final lines (mandatory paste): `> orevia-staff-web@0.1.0 typecheck` then `> tsc --noEmit -p tsconfig.json` — exit 0, no `error TS…` diagnostics. Build typecheck `> tsc --noEmit -p tsconfig.json && vite build` also exited clean.
- Fast repository policy stages run individually (everything in `scripts/check.sh` except its module full gates): shell `bash -n`/`shellcheck`, `project-name.py`, `check-pack.py`, `check-docs.py`, `check-secrets.py`, `check-names.py`, `check-wiring.py`, `check-migrations.py --require-baseline`, `bash scripts/python-coverage.sh`, `bash scripts/frontend-check.sh` → all passed; `frontend standards: policy and tool commands passed` (patient app Jest 79 suites / 1127 tests, root Vitest 1 test).

Deliberate omission: full `scripts/check.sh` was not run because it invokes `apps/api/check.sh` → `mvn -q -B verify`, which this worker prompt forbids (runs every Java integration test, >10 minutes). Every one of its policy/tooling stages was run individually above; the integrator and Delivery run the full gate. Evidence source: executed tool commands and file reads (no reviewer, simulated user or human participant evidence).

## Coverage

Command: `bash apps/staff-web/check.sh` (runs `npm run test:coverage`; Vitest v8 provider, `perFile: true`, 100% lines/branches/functions/statements).

Result: `No files with missing coverage.` / `207 files fully covered.` / `Statements : 100% ( 3722/3722 )`, `Branches : 100% ( 1892/1892 )`, `Functions : 100% ( 1258/1258 )`, `Lines : 100% ( 3556/3556 )`. `apps/staff-web/src/features/system/NotFoundPage.tsx` is fully covered.

New/changed branches and the test that takes each: none were added in production. The change is one `useBreakpoint()` call plus a static wrapper (no `if`, ternary, `&&`, `switch` or `catch`); `useBreakpoint`'s own branches are covered by its existing theme tests and were unchanged. The three layout bands are exercised by the new tests: `centres the notice and wraps long text at %s` and `keeps the labelled region, title, detail and one action at %s` each run for `compact`, `medium`, `expanded` via mocked `matchMedia`; `keeps the single action first and keyboard-focusable at every breakpoint` covers all three bands; `mirrors the notice in Arabic right-to-left without a direction override` covers RTL at compact. No threshold, exclusion or skip was changed.

## Clinical and architecture review

Not required: no signed data, patient association, cross-organization sharing, prescribing or critical result is involved. Accessibility evidence is simulated (jsdom structure/attributes only); pixel layout, 200%/400% zoom and real coarse-pointer measurement remain with the Playwright tasks (RESP-E2E-* stories). No real clinician/unit acceptance is claimed.

## Findings

- Pre-existing defect outside scope, observed after `git merge --no-edit origin/main` (reported `Already up to date`) via `git grep -nE '^(<<<<<<<|>>>>>>>) '`: committed conflict markers on `main` in `apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7ParseException.java` (lines 6, 10), `Hl7Parser.java` (lines 14, 18) and `Hl7Segment.java` (lines 10, 14). Not edited (out of scope, Java). The integrator should remove them before the full Java gate.
- Expected pre-existing console noise during tests: jsdom `Not implemented: navigation (except hash changes)` from the existing `UnsupportedBrowserPage` click test and `useI18n/useTheme must be used inside …Provider` from the provider negative tests. All test files pass; these are not regressions.
- `responsive.css` has no dedicated notice-panel class. Per story rule 3 the stylesheet was not edited; centring and wrapping use logical inline styles on the page wrapper, matching the existing `StateViews`/`StatePanel` precedent.
- This page is data-free: it has no columns, clinical fields or inputs, so drawer/rail/table, column-stacking and 16 px input-font assertions are not applicable; the single `Button.touch` is the only interactive target.

## Lessons for workers

- When a page delegates its DOM to a shared presentational component outside your scope, put the responsive `page` wrapper and `data-breakpoint` on the page element and locate it in tests with `heading.closest('.page')`; do not edit the shared component.
- In a fresh worker, `npx vitest` tries to install a mismatched version and cannot resolve local config. Run `npm ci --no-audit --no-fund` in the module, then use `./node_modules/.bin/vitest` or `bash apps/staff-web/check.sh`.
- `scripts/check.sh` pulls in `mvn verify`; for a frontend-only worker story run its fast policy stages individually plus the module `check.sh`, so you keep the 100% frontend evidence without triggering the forbidden full Java gate.
- jsdom cannot paint pixels: assert `page`/`touch`/`data-breakpoint` classes, logical inline styles and roles, and leave widths, zoom and 44 px measurement to the Playwright projects.
