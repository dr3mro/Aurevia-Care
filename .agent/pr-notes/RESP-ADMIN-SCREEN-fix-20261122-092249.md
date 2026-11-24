## Summary

Fix attempt 1 of `RESP-ADMIN-SCREEN`. The responsive `AdminScreen` implementation from the prior run was already present and committed on this branch (`30a5d8a6`, plus two `origin/main` merges). I did not need to change code: after merging `origin/main` the implementation is still green. In this pass I independently re-ran the mandatory frontend gate in the current merged state, captured the typecheck final lines, confirmed 100% coverage on every touched file, and re-diagnosed the only remaining `scripts/check.sh` failure as a cloud-worker environment artifact outside task scope. This file records the complete hand-off at the path named in the prompt. No file outside the two-path task scope was changed.

## Affected requirements

- **WEB-07** — supported staff-web widths. `AdminScreen` now renders as one logical `.page` grid (single full-width column on phones, centred reading width on larger viewports) and the access-review notices use the `.stack` utility. `data-breakpoint` exposes the active band for jsdom assertions. No page-level horizontal scroll; every clinical section and column stays in the DOM at every band.
- **UX-01** — English/Arabic right-to-left, logical-only presentation. Direction stays with the document (no subtree override); long English and Arabic runs keep their `MixedScriptText` `<bdi dir="auto">` isolation and wrap; roles, labels, live region and keyboard order are unchanged.
- Acceptance mapping (task steps 1–5): step 1 read the component, its test, `docs/design/responsive-layout.md`, `responsive.css` and `useBreakpoint.ts`; step 2 satisfied by `.page`/`.stack` plus existing child-component `field`/`touch`/`table-region` classes (no clinical column hidden); step 3 used only existing classes and the hook — no stylesheet edit and no missing class; step 4 added `tests/responsive/features-admin/AdminScreen.responsive.test.tsx` mocking `matchMedia` for compact/medium/expanded and asserting structure, labels, roles, keyboard order and RTL; step 5 no behaviour, text, API call or exported name changed.

## Risk class

Routine — contained staff-web presentation change with a new component test; no API, data, tenant boundary or authorization change (`docs/WORKFLOW.md`).

## Review owner

`orevia-senior-react` and `orevia-senior-typescript` skills applied to the scoped files. Evidence is executed tool output only; no human, clinical or patient-participant evidence. The prior run's simulated `orevia-senior-react` review returned no actionable findings; I relied on the executable gates below rather than repeating that review.

## Data changes

None. No migration, backfill, table, lock or volume impact. All test data is synthetic (`alphaOrganizationId`, `syntheticConfigurations`, `syntheticAccessProbes`, `syntheticAuditEvents`, `syntheticStaffEvents`, and the long synthetic English/Arabic strings). No patient data, credential, secret or raw log in code, tests or notes.

## Authorization boundaries and audit events

None added or changed. The component remains tenant-scoped by its `organizationId` prop; the synchronous tenant-reset guard and `MixedScriptText`/live-region behaviour are untouched. No audit event emitted.

## Migration and rollout considerations

None. No Flyway version reserved or used; presentation-only change. Responsive pixel widths, focus rings and virtual-keyboard claims remain with the Playwright tasks (`RESP-E2E-*`); jsdom asserts classes, attributes and structure only.

## Test evidence

Entry point to test trace: `src/features/admin/AdminScreen.tsx` → `tests/responsive/features-admin/AdminScreen.responsive.test.tsx` (renders `AdminScreen` directly under `ThemeProvider` + `I18nProvider` with `matchMedia` stubbed for the three bands) and the unchanged `src/features/admin/admin-screen.test.tsx` (existing behaviour and tenant-isolation assertions).

Red-first evidence (from the prior run, before the component change — recorded, not re-run):

```
cd apps/staff-web && ./node_modules/.bin/vitest run tests/responsive/features-admin/AdminScreen.responsive.test.tsx --exclude 'e2e/**'
Test Files  1 failed (1)
Tests       7 failed | 2 passed (9)
```

Mandatory frontend gate, re-run now in the current merged state:

```
cd apps/staff-web && bash check.sh
 Test Files  159 passed (159)
      Tests  1900 passed (1900)
   Start at  09:24:25
   Duration  64.65s (environment 44%, tests 29%, setup 18%, worker 3%, transform 3%, import 2%)
No files with missing coverage.
207 files fully covered.
4 empty files skipped.
Statements   : 100% ( 3721/3721 )
Branches     : 100% ( 1892/1892 )
Functions    : 100% ( 1258/1258 )
Lines        : 100% ( 3555/3555 )
exit: 0
```

Typecheck final lines (mandatory — no `error TS…` diagnostics):

```
cd apps/staff-web && npm run typecheck
> orevia-staff-web@0.1.0 typecheck
> tsc --noEmit -p tsconfig.json
=== typecheck exit: 0 ===
```

`check.sh` also runs `gen:api:check`, `npm run lint`, `npm run build` and the WEB-08 bundle-size gate; all passed (the script exits 0). The `error`/`Uncaught` console lines in the Vitest log come from existing intentional negative tests (`useTheme` / `useI18n` outside their providers, e.g. `tests/theme/ThemeProvider.test.tsx`), not failures.

Git upkeep: `git merge --no-edit origin/main` was run at the start of this pass → "Already up to date." A `git grep` for committed conflict-marker lines (the leading and trailing angle-bracket markers) shows only pre-existing markers in `apps/api/.../exchange/hl7/` files that are present in `origin/main` itself (out of this task's scope); my merge introduced none.

Repository-wide policy script (run once at the end, as instructed):

```
bash scripts/check.sh
check-pack: opencode.json is stale: run scripts/gen-opencode.py
=== check.sh exit: 1 ===
```

Verified as a cloud-worker environment artifact, not a change defect (tool evidence):

```
git ls-files -v opencode.json                     -> S opencode.json   (skip-worktree)
cmp -s opencode.json .github/opencode.ci.json     -> identical (worktree holds the CI profile)
cmp -s opencode.json <(git show HEAD:opencode.json) -> differs (HEAD holds the local profile)
git status --short -- opencode.json .github/opencode.ci.json -> empty (nobody in this worktree edited them)
```

The prior run additionally restored the HEAD profile under a byte-for-byte trap and moved `check.sh` past `check-pack` to two `scripts/` tooling tests that fail with `FileNotFoundError: apps/api/target/surefire-reports`; I confirmed `apps/api/target/surefire-reports` does not exist in this worker. Both are pre-existing environment failures in protected `scripts/` files (they assume a built Java target tree), unrelated to this staff-web presentation change; no `scripts/` file was edited by this worker.

## Coverage

Command and result: `cd apps/staff-web && bash check.sh` → `Statements 100% (3721/3721)`, `Branches 100% (1892/1892)`, `Functions 100% (1258/1258)`, `Lines 100% (3555/3555)`, 207 files fully covered, no files with missing coverage. Per-file thresholds in `vitest.config.ts` were not changed; no exclusion, ignore comment or skipped test was added. `AdminScreen.tsx` is included in the fully-covered set.

New or changed production lines and branches in `AdminScreen.tsx` (the only production file touched), each with its covering assertion:

- `import { useBreakpoint }` / `const breakpoint = useBreakpoint();` — no branch in this component; exercised by every test in the new file at all three bands.
- Outer `<section className="page" data-breakpoint={breakpoint} …>` replaced the inline grid style — asserted at compact/medium/expanded by `uses the logical page wrapper and keeps every administration section at %s` (class, `data-breakpoint`, absence of a `style` attribute, aria-label) and by `follows a width change without losing the page wrapper or the rendered data`.
- Inner `<section className="stack" …>` replaced the inline grid style — asserted in the same test (`stack` class and no `style` attribute) at all three bands.
- `useTheme` and its `tokens` usage were deleted with the inline styles; no replacement branch was introduced.
- `useBreakpoint()` band outcomes (compact/medium/expanded) are covered by the `it.each(BREAKPOINTS)` tests; the runtime listener path is covered by `follows a width change…` firing the medium media query.

No branch in the touched production file is uncovered.

## Clinical and architecture review

Not required: presentation-only change with no signed data, cross-organization sharing, patient association, prescribing or critical result. No tenant, role or state-machine boundary changed.

## Findings

- `AdminScreen` is still a presentational component imported only by tests (`grep` of `src` shows no route/container import); page wiring is owned by separate admin-page stories. Responsive evidence here is component-level, not an end-to-end route claim (the `RESP-E2E-*` tasks cover pixel behaviour).
- The child components (`ConfigEditor`, `StaffHistoryView`, `AuditEventList`, `AccessStateNotice`) still carry their own inline layouts/tokens; they belong to their own `RESP-*` stories. None hides a clinical column with `display: none`, and this story did not change them.
- `AdminTableRegion` keeps its labelled, keyboard-focusable `overflow-x: auto` region — it is the scope of `RESP-ADMIN-TABLE-REGION` and was left untouched. Its region/table roles are asserted here at every band.
- No missing layout class was encountered: `.page` and `.stack` already exist in `responsive.css`, so no inline side-aware style was needed.
- `scripts/check.sh` is red in this cloud worker for environment-only reasons (see Test evidence). Nothing inside the task scope can make it green; the integrator/Delivery full gate runs with a built Java target tree and the committed profile.

## Why this is not finished

The story's implementation and its mandatory frontend gates are complete and green (1900/1900 tests, 100% coverage on every touched file, `tsc --noEmit` exit 0). The single failing check is the repository-wide `bash scripts/check.sh` (exit 1), for two reasons that are outside this task's two-file scope and not caused by this change:

1. `check-pack: opencode.json is stale` — in a cloud worker the worktree `opencode.json` is the CI profile copied under `skip-worktree`, so the pack gate judges the CI bytes rather than the committed local profile (verified with `git ls-files -v`, `cmp` and `git status` above).
2. With the HEAD profile temporarily restored, the prior run saw two `scripts/tests` tooling tests fail with `FileNotFoundError: apps/api/target/surefire-reports`; that directory is absent in this worker because no Java build target tree was produced.

Most likely cause: cloud-worker environment/profile setup, not a code or configuration defect in the two scoped files. Tried: re-ran `bash apps/staff-web/check.sh` (green), `npm run typecheck` (green) and `bash scripts/check.sh` (red at `check-pack`), and verified the skip-worktree/profile and missing-report facts with tools. Exact next step: no code change is needed; the trusted integrator/Delivery full gate runs Java verification (which creates `apps/api/target/surefire-reports`) and uses the committed profile, where these environment checks pass. This section is recorded because a repository-wide check still fails locally; it does not indicate unfinished story work.

## Lessons for workers

- The existing responsive tests install `matchMedia` via `vi.stubGlobal` with a shared listener map per query and `vi.unstubAllGlobals()` in `afterEach`; reuse that pattern so a runtime `media.fire(...)` proves the breakpoint hook is live.
- Query a composed screen by its `aria-label` region (`getByRole('region', { name: … })`), not `getByRole('status')`: access notices also expose `status` roles, so a bare status query is ambiguous.
- `Parameters<typeof adminText>[1]` is the typed way to iterate message keys in a test; a plain `string[]` fails `tsc` under the strict config.
- In a cloud worker, a `scripts/check.sh` `check-pack` failure is a CI-profile artifact under `skip-worktree`: state which profile was measured and never edit `opencode.json` or the protected `scripts/` files to make it green.
