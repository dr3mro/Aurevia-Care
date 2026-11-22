## Summary

Staff web `PatientsScreen` (features-patients) is now responsive at 320/360/390/768/820/1024/1280 px in both LTR and RTL. On compact widths the result table is placed in a labelled, keyboard-scrollable `.table-region`; from medium upward the full table is rendered without a scroll region. The table keeps the same caption, headers, every clinical column, text, link target and exported name at every width. Long English and Arabic values wrap with logical properties and no fixed block size. Synthetic data only; no new dependency, no stylesheet edit.

## Affected requirements

WEB-07, UX-01. Layout contract: `docs/design/responsive-layout.md` (sections 2, 5, 6, 11, 12, 13), decision `docs/decisions/0004-responsive-staff-web.md`.

## Risk class

Routine (contained presentational view change). One implementation specialist (Senior React) with Senior TypeScript for the strict-mode test; no data, auth or clinical-policy change.

## Review owner

Senior React and Vite; Senior TypeScript for the test file. A bounded review was dispatched to `smartclinic-senior-react`; findings, if any, are recorded under Findings.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback concern.

## Authorization boundaries and audit events

None. The existing row link keeps the same opaque patient id and route; no tenant, role or patient-link scope change; no audit event added or changed.

## Migration and rollout considerations

None. Presentation-only change; no contract or deployment change.

## Test evidence

Entry point to test trace: `PatientsScreen` receives `patients` from `PatientsPage` → `ApiPage` → `searchPatients`; the new jsdom test renders the component directly with synthetic patient rows and stubbed `matchMedia`, then asserts the rendered region/table structure, roles, labels, keyboard order and RTL logical styles.

Commands executed from `apps/staff-web` (synthetic fixtures only):

- `npm ci --no-audit --no-fund` → `added 486 packages in 5s`.
- RED: `./node_modules/.bin/vitest run tests/responsive/features-patients/PatientsScreen.responsive.test.tsx` → `Test Files  1 failed (1)` / `Tests  9 failed (9)` — missing labelled region on compact, missing logical wrapping on cells/headers, physical `width: 100%`, no touch wrapper.
- GREEN: `./node_modules/.bin/vitest run tests/responsive/features-patients/PatientsScreen.responsive.test.tsx` → `Test Files  1 passed (1)` / `Tests  9 passed (9)`.
- Regression: `./node_modules/.bin/vitest run src/features/patients tests/responsive/features-patients` → `Test Files  6 passed (6)` / `Tests  85 passed (85)`.
- Typecheck (final lines): `npm run typecheck` → `> smartclinic-staff-web@0.1.0 typecheck` / `> tsc --noEmit -p tsconfig.json` / `TYPECHECK_EXIT=0`. No `error TS…`, no `any`, no `@ts-ignore`.
- Mandatory frontend gate: `bash check.sh` → exit 0. Final lines: `Test Files  159 passed (159)` / `Tests  1900 passed (1900)`; `No files with missing coverage.` / `207 files fully covered.`; `Statements   : 100% ( 3727/3727 )`, `Branches     : 100% ( 1894/1894 )`, `Functions    : 100% ( 1258/1258 )`, `Lines        : 100% ( 3561/3561 )`. The gate also completed `gen:api:check`, lint, production build and the WEB-08 bundle-size budget.
- Branch sync: `git merge --no-edit origin/main` → `Already up to date.` (no conflicts introduced by this task).
- Repository gate: `bash scripts/check.sh` → exit 1 at its first failing stage, `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. Confirmed once as the documented worker-image artifact OPS-CW-003-01, not a defect of this change: `git ls-files -v opencode.json` → `S opencode.json` (skip-worktree), `git diff -- opencode.json` empty, and `git show HEAD:opencode.json` parses as valid JSON. The committed local profile is generated correctly; the worktree file is the CI profile copied by `agent-run.sh`. Remaining stages were run individually and all passed (exit 0): `python3 -I scripts/check-docs.py`, `python3 -I scripts/check-secrets.py`, `python3 -I scripts/check-names.py`, `python3 -I scripts/check-wiring.py`, `python3 -I scripts/check-migrations.py --require-baseline`, `bash scripts/frontend-check.sh` (`frontend standards: policy and tool commands passed`). No protected file was edited.
- `bash scripts/python-coverage.sh` → exit 1 from known worker-image tooling limits, not from this change (no Python file touched). Recorded final lines: `check_pack` cases fail on the same stale-profile artifact; `test_worker_gate` / `test_worker_gate_coverage` raise `FileNotFoundError: [Errno 2] No such file or directory: 'apps/api/target/surefire-reports'` and gh fixture failures (`gh failed (1); assuming main is not red`, `pr view 7 ... returned non-zero exit status 1`). The integrator tooling has the CI profile and Java test results.

## Coverage

Command: `bash check.sh` in `apps/staff-web` (runs `vitest run --coverage --exclude 'e2e/**'` with `perFile: true` and 100% thresholds for lines, branches, functions and statements).

Result: 100% for every metric — 3727/3727 statements, 1894/1894 branches, 1258/1258 functions, 3561/3561 lines; 207 files fully covered, none missing.

New/changed branches in `PatientsScreen.tsx` and the test that takes each side:

| Branch | Covering test |
|---|---|
| `useBreakpoint() === 'compact'` is true → `.table-region` labelled scroll region | `keeps every clinical column in a labelled, keyboard-scrollable region at %ipx` (320, 360, 390) and the RTL case at 360 |
| `useBreakpoint() === 'compact'` is false → bare full table | `keeps the full table without a scroll region at %ipx` (768, 820, 1024), `keeps the full table on expanded desktops` (1280), `drops the scroll region after a resize without losing the row` |
| `patients.map(...)` row callback (both the empty-list zero-iteration path and a populated row) | New tests render one synthetic row; existing `PatientsPage.test.tsx` covers empty and populated result sets through `ApiPage` |

No unreachable guard, dead branch, lowered threshold, exclusion, skip or pending-as-covered result was introduced.

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association change, prescribing or critical-result behavior. The clinician-facing Arabic clinician pass and pixel-level Playwright checks remain separate launch gates (responsive-layout.md section 13).

## Findings

- The shared `Link` primitive (`apps/staff-web/src/app/router.tsx`) does not accept or forward `className`. The coarse-pointer 44 × 44 target is therefore applied with the existing `.touch` class on a `<div style={{ display: 'grid' }}>` wrapper around the link — the same `StateAction` pattern used in `apps/staff-web/src/components/StateViews.tsx` — so the grid stretches the anchor to the target floor without changing the Link primitive or its behavior. No stylesheet edit was needed; every required class (`.table-region`, `.touch`) already exists in `responsive.css`.
- Safe-area insets, the compact drawer / medium icon rail / expanded labelled rail and 16 px field text are owned by the shell and shared form controls outside this task's scope; this view uses the shell's logical padding and consumes the shared classes. Pixel measurement (44 × 44, reflow at 320 px, 200 percent zoom) is a Playwright concern; jsdom asserts structure, roles, labels, classes and logical-only inline styles as the task directs.
- The component now calls `useBreakpoint()`. `matchMedia` is optional in that hook; where it is absent (e.g. the existing `PatientsPage` tests) the hook falls back to `expanded`, so those tests keep finding the bare table with its caption and roles. This was confirmed by the 85/85 regression run.
- Out-of-scope observation for the integrator: `git grep -nE '^(<<<<<<<|>>>>>>>) '` reports committed conflict markers in `apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7/Hl7ParseException.java`, `Hl7Parser.java` and `Hl7Segment.java`. Verified with `git show HEAD:...` and `git show origin/main:...`: they are already in `origin/main` from commit `b6b349f0` (BUILD-37, PR #490), not introduced by this task and not touched by it. My own `git merge --no-edit origin/main` reported `Already up to date.` with no conflicts.
- Independent bounded review by `smartclinic-senior-react` (review-only, no edits): **no blocking findings**. It confirmed the compact region / medium-expanded table structure matches `DataTable` and responsive-layout.md §5, the grid `.touch` wrapper gives the anchor a 44 × 44 coarse target via the `StateAction` pattern, the single `breakpoint === 'compact'` branch is covered on both sides, and there are no `any`, `@ts-ignore`, physical CSS properties, hidden columns or hardcoded pixel values in the changed files. Its only note was non-blocking: on compact the region `aria-label` and the table `<caption>` carry the same text, so a screen reader announces the name twice; that is the sanctioned shared pattern and was deliberately left unchanged. Review evidence: static reads and targeted grep; it did not re-run `check.sh` (already observed by the primary agent).

## Lessons for workers

- Vitest 5 has no `--reporter=basic`; run `vitest run <path>` with the default reporter.
- `Link` does not forward `className`; to give an in-app link the `.touch` coarse-pointer target, wrap it in `<div className="touch" style={{ display: 'grid' }}>` (see `StateViews.tsx`) instead of passing a class to `Link`.
