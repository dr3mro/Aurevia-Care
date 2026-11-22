## Summary

Made the composed staff-web `AdminScreen` responsive using the shared layout utilities and breakpoint hook, and added a dedicated responsive test that mocks `matchMedia` for the compact, medium and expanded bands. The screen now renders as one logical `.page` grid (single column, logical safe-area padding, 1200 px reading cap) with the access-review notices in a `.stack`, and exposes `data-breakpoint` so structure can be asserted in jsdom. No behaviour, text, API call or exported name changed; no stylesheet, dependency or file outside the two-path scope was touched.

## Affected requirements

- **WEB-07** — supported staff-web widths: one logical page column with the shared layout classes from `responsive.css` at 320–1280 px; no page-level horizontal scroll, all columns preserved.
- **UX-01** — English/Arabic RTL with logical-only presentation, isolated `<bdi>` runs, preserved live-region roles, keyboard order and coarse-pointer `touch` targets owned by the shared components.
- Acceptance mapping (story steps): step 1 read the component, its test, `docs/design/responsive-layout.md`, `responsive.css` and `useBreakpoint.ts`; step 2 satisfied by `.page`/`.stack` plus the existing child components' `field`, `touch` and `table-region` classes; step 3 used the existing classes and hook only, no stylesheet edit and no missing class; step 4 added the new mocked-`matchMedia` test at `tests/responsive/features-admin/AdminScreen.responsive.test.tsx`; step 5 no behaviour/text/API/export change.

## Risk class

Routine — contained staff-web presentation change with a new component test; no API, data, tenant boundary or authorization change (docs/WORKFLOW.md).

## Review owner

`smartclinic-senior-react` (bounded simulated review, advisory) and `smartclinic-senior-typescript` skill applied to the strict-TypeScript test file. The simulated `smartclinic-senior-react` reviewer returned **no actionable findings** after reading the two scoped files and the rendered child components against `docs/design/responsive-layout.md`, `docs/design/screen-recipe.md` and CODING_STYLE.md; it did not re-run checks and relied on the tool evidence below. Non-blocking observation: the new test sits under `tests/responsive/features-admin/` while older responsive tests sit under `tests/components/`/`tests/app/`; Vitest discovery and coverage are unaffected. Evidence is tool output plus a simulated reviewer; no human, clinical or patient-participant evidence.

## Data changes

None. No migration, backfill, table, lock or volume impact. All test data is synthetic (`alphaOrganizationId`, `syntheticConfigurations`, `syntheticAccessProbes`, `syntheticAuditEvents`, `syntheticStaffEvents` and the long synthetic English/Arabic strings); no patient data, credentials or raw logs.

## Authorization boundaries and audit events

None added or changed. The component remains tenant-scoped by its `organizationId` prop; the existing synchronous tenant-reset and `MixedScriptText`/live-region behaviour is untouched. No audit event emitted.

## Migration and rollout considerations

None. No Flyway version reserved or used; presentation-only change. Responsive pixel, focus-ring and virtual-keyboard claims remain for the Playwright tasks (RESP-E2E-*); jsdom asserts classes, attributes and structure only.

## Test evidence

Entry point to test trace: `src/features/admin/AdminScreen.tsx` → `tests/responsive/features-admin/AdminScreen.responsive.test.tsx` (renders `AdminScreen` directly under `ThemeProvider` + `I18nProvider` with `matchMedia` stubbed for compact/medium/expanded) and the unchanged `src/features/admin/admin-screen.test.tsx` (existing behaviour and tenant-isolation assertions).

Red first, before the component change:

```
cd apps/staff-web && ./node_modules/.bin/vitest run tests/responsive/features-admin/AdminScreen.responsive.test.tsx --exclude 'e2e/**'
Test Files  1 failed (1)
Tests       7 failed | 2 passed (9)
```

Green after the change:

```
cd apps/staff-web && ./node_modules/.bin/vitest run tests/responsive/features-admin/AdminScreen.responsive.test.tsx src/features/admin/admin-screen.test.tsx --exclude 'e2e/**'
Test Files  2 passed (2)
Tests       17 passed (17)
```

Mandatory frontend gate (exact commands and final lines):

```
cd apps/staff-web && bash check.sh
 Test Files  159 passed (159)
      Tests  1900 passed (1900)
   Start at  08:49:36
   Duration  59.56s (environment 43%, tests 29%, setup 18%, worker 3%, transform 3%, import 3%, setup 3%)
 No files with missing coverage.
 207 files fully covered.
Statements   : 100% ( 3721/3721 )
Branches     : 100% ( 1892/1892 )
Functions    : 100% ( 1258/1258 )
Lines        : 100% ( 3555/3555 )
```

Typecheck final lines (no diagnostics on success):

```
cd apps/staff-web && npm run typecheck
> smartclinic-staff-web@0.1.0 typecheck
> tsc --noEmit -p tsconfig.json
typecheck exit: 0
```

Lint final lines:

```
cd apps/staff-web && npm run lint
> smartclinic-staff-web@0.1.0 lint
> eslint . --max-warnings 0
lint exit: 0
```

`gen:api:check`, build and the WEB-08 bundle-size gate also passed within `check.sh`. `git merge --no-edit origin/main` was run at the start and after the implementation stage ("Already up to date"); no conflict markers introduced by this work. The `error`/`Uncaught` console lines in the full Vitest log come from existing intentional negative tests (`useI18n`/`useTheme` outside providers), not failures.

Repository-wide policy script (run once at the end, as instructed):

```
bash scripts/check.sh
check-pack: opencode.json is stale: run scripts/gen-opencode.py
scripts/check.sh exit: 1
```

This is the known cloud-worker artifact, verified with tools and **not** a change defect: `git ls-files -v opencode.json` → `S` (skip-worktree); the worktree file is byte-for-byte `.github/opencode.ci.json` and differs from `git show HEAD:opencode.json`. No worker edited that file. Re-running with the HEAD profile temporarily restored (CI bytes restored byte-for-byte via a trap) moved the failure past `check-pack` to:

```
Ran 1118 tests in 25.018s
FAILED (errors=2)
ERROR: test_integration_failure_still_fails_the_gate (test_worker_gate.MainTest)
ERROR: test_maven_failure_with_failing_reports_still_fails (test_worker_gate_coverage.MainTest)
FileNotFoundError: [Errno 2] No such file or directory: 'apps/api/target/surefire-reports'
```

Both are pre-existing environment failures in protected `scripts/` tooling (they assume a built Java target tree), unrelated to staff-web presentation; no `scripts/` file was edited by this worker. `git status` after both runs shows only the two in-scope paths plus this notes file; `scripts/wiring-baseline.json` and `opencode.json` are unchanged.

## Coverage

Command and result: `cd apps/staff-web && bash check.sh` → `Statements 100% (3721/3721)`, `Branches 100% (1892/1892)`, `Functions 100% (1258/1258)`, `Lines 100% (3555/3555)`, 207 files fully covered, no files with missing coverage. Per-file thresholds in `vitest.config.ts` were not changed; no exclusion, ignore comment or skipped test was added.

New or changed production lines and branches in `AdminScreen.tsx` (the only production file touched):

- `import { useBreakpoint }` / `const breakpoint = useBreakpoint();` — no branch in this component; exercised by every test in the new file at all three bands.
- Outer `<section className="page" data-breakpoint={breakpoint} …>` replaced the inline grid style — asserted at compact/medium/expanded by `uses the logical page wrapper and keeps every administration section at %s` (class, `data-breakpoint`, absence of a `style` attribute, aria-label) and by `follows a width change without losing the page wrapper or the rendered data`.
- Inner `<section className="stack" …>` replaced the inline grid style — asserted in the same test (`stack` class and no `style` attribute) at all three bands.
- `useTheme` and its `tokens` usage were deleted because the inline styles were removed; no replacement branch was introduced.
- The `useBreakpoint()` returned bands (three outcomes) are covered by the `it.each(BREAKPOINTS)` tests; the runtime listener path is covered by `follows a width change…` firing the medium query.

No branch in the touched production file is uncovered. The 100% report was read for the whole module, which includes `AdminScreen.tsx`.

## Findings

- `AdminScreen` is still a presentational component imported only by tests (`grep` of `src` shows no route/container import); page wiring is owned by separate admin-page stories. The responsive evidence here is component-level, not an end-to-end route claim.
- The three wide tables still use `AdminTableRegion` with an inline `overflow-x: auto`; that component is the scope of `RESP-ADMIN-TABLE-REGION`, so it was left untouched. Its labelled, keyboard-focusable region is asserted here at every band.
- Present child components still carry their own inline grids/tokens (`ConfigEditor`, `StaffHistoryView`, `AuditEventList`, `AccessStateNotice`); they are owned by their own RESP stories. None hides a column with `display: none`, and this story did not change them.
- No missing layout class was encountered: `.page` and `.stack` already exist in `responsive.css`, so no inline side-aware style was needed and no lesson about a missing class is recorded.

## Lessons for workers

- The existing responsive tests install `matchMedia` with `vi.stubGlobal` and a shared listener map per query; reuse that pattern so a runtime `media.fire(...)` can prove the breakpoint hook is live, and always `vi.unstubAllGlobals()` in `afterEach`.
- Query the composed screen by its `aria-label` region (`getByRole('region', { name: … })`) rather than `getByRole('status')`: access notices also expose `status` roles, so a bare status query is ambiguous.
- `Parameters<typeof adminText>[1]` is the typed way to iterate message keys in a test; a plain `string[]` fails `tsc` under the strict config.
