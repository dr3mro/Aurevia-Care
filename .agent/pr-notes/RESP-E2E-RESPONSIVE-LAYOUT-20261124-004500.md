## Summary

Added `apps/staff-web/e2e/responsiveLayout.spec.ts`: a Playwright spec that opens every registered staff-web route in every project of `e2e/playwright.config.ts` and asserts the WEB-07/UX-01 responsive shell contract, including a right-to-left language-toggle journey. One test per route runs on every project (31 routes + 1 RTL journey = 32 tests × 10 projects = 320 enumerated tests). No production source, configuration, dependency, OpenAPI, migration or tracker file changed. The spec is the deliverable; all existing deterministic assertions in `e2e/smoke.spec.ts` (scroll-width measurement, `main#main`, `a.skip-link`, axe gate) were read and reused where they apply.

## Affected requirements

WEB-07 (supported clients and responsive browser matrix), UX-01 (English/Arabic, RTL, accessibility-compatible presentation). No other requirement changed.

## Risk class

Routine — one new end-to-end test file, no production or contract change. The required plan, code, coverage and security reviews were still run because the change asserts a cross-device contract.

## Review owner

Primary agent. Read-only reviewers: aurevia-plan-reviewer, aurevia-senior-react, aurevia-senior-typescript, aurevia-code-reviewer, aurevia-coverage-auditor, aurevia-devsecops.

## Data changes

None. No migration, backfill, lock, volume impact, rollback or forward-fix. All identifiers are synthetic (`SYN-0001`, `SYN-0002`, `/no-such-route`); no patient data, credential or real domain appears in the spec, logs or notes.

## Authorization boundaries and audit events

None. No role, permission, tenant or patient-link rule changed. The test observes the fixed synthetic `reception` role from `src/fixtures/synthetic.ts`; it does not grant or override access. No audit event added or changed.

## Migration and rollout considerations

None. No schema or deployment change.

## Test evidence

Evidence source: executed tool commands in the worker checkout at commits `b5812de4` and `91918c59` on branch `swarm/RESP-E2E-RESPONSIVE-LAYOUT-1361`; reviewer verdicts are simulated read-only review, not human or clinical evidence. No browsers are installed in this sandbox, so the live Playwright run is unavailable and the enumeration command was used instead.

- `npx eslint e2e/responsiveLayout.spec.ts --max-warnings=0` → exit 0, no output (run in `apps/staff-web`).
- `npx playwright test --config e2e/playwright.config.ts e2e/responsiveLayout.spec.ts --list` → exit 0; final line `Total: 320 tests in 1 file`. Ten projects enumerated: `chromium` plus `phone-portrait-360x740`, `phone-portrait-390x844`, `phone-landscape-844x390`, `tablet-portrait-768x1024`, `tablet-portrait-820x1180`, `tablet-landscape-1024x768`, `tablet-landscape-1180x820`, `laptop-1280x720`, `desktop-1920x1080`. WebKit and Firefox projects were omitted by `playwright.config.ts` because their binaries are absent.
- Live command `npx playwright test --config e2e/playwright.config.ts e2e/responsiveLayout.spec.ts` was not runnable here: `chromium`, `firefox` and `webkit` executable paths were checked and all three files are absent. No test was skipped or weakened to compensate.
- `bash apps/staff-web/check.sh`: `gen:api:check`, `typecheck`, `lint`, `build` and `check-bundle-size.mjs` all passed; `test:coverage` finished with `Test Files  1 failed | 267 passed (268)` and `Tests  1 failed | 3044 passed (3045)`; overall `CHECK_EXIT=1`. The single failure is the pre-existing tripwire described under Findings.
- `npm run gen:api:check` → final line `gen:api: generated client matches the contract`.
- `npm run typecheck` → exit 0 with no `error TS…` lines; output ended at `> tsc --noEmit -p tsconfig.json`. Note that `tsconfig.json` includes only `src`, `tests` and `vite.config.ts`, so the spec is validated by ESLint (`e2e/**/*.ts`) and Playwright's transpilation, not by this command.
- `npm run lint` → exit 0, no findings (output ended at `> eslint . --max-warnings 0`).
- `npm run build` → final line `✓ built in 203ms`.
- `node scripts/check-bundle-size.mjs` → exit 0; final line `useBreakpoint-DEBbg83l.js                                   460  ok`.
- `python3 -I scripts/check-constraints.py --base origin/main` → exit 0 (no added suppression, skip or lowered threshold).
- `git merge --no-edit origin/main` → `Already up to date.` at story start, after implementation and before finish.

Entry-point-to-test trace: `playwright.config.ts` starts the Vite dev server → `page.goto('/route')` → `src/main.tsx` browser-support guard → `src/app/routes.tsx` route table and feature `routes.ts` glob → `AppShell.tsx` renders `data-breakpoint`, `NavigationDrawer.tsx` drawer button, `main#main` and `header[role="banner"] > h1` → spec assertions at `responsiveLayout.spec.ts:80-161`.

Code-review fixes in `91918c59`: (1) the shell heading selector is scoped to `header[role="banner"] > h1` so the reception page header h1 on `/schedule` and `/schedule/book` cannot create a strict-locator collision; (2) `expectPageReady` calls `page.waitForLoadState('networkidle')` after the heading check so an API loading skeleton is not measured as the settled screen. ESLint and `--list` were rerun after both fixes with the results recorded above.

## Coverage

No production file changed, so Vitest/JaCoCo coverage is unchanged and no new production branch exists. `bash apps/staff-web/check.sh` initially reached `test:coverage` with 3044 passing tests and one failure on a stale tripwire pin; that pin was refreshed under coverage repair attempt 1 (below) and the gate is now 3045/3045 green. The deliverable's branches are enumerated by the Playwright list command (`Total: 320 tests in 1 file`) and each is exercised by a named test in the same file:

| Branch | Test |
|---|---|
| compact band: drawer button present, rail absent | per-route tests via `expectNavigation` (`responsiveLayout.spec.ts:110-113`) on `phone-portrait-360x740`, `phone-portrait-390x844` |
| medium band: `[data-rail="icon"]` | per-route tests via `expectNavigation` (`:115-119`) on `phone-landscape-844x390`, both tablet projects |
| expanded band: `[data-rail="full"]` | per-route tests via `expectNavigation` (`:115-119`) on `laptop-1280x720`, `desktop-1920x1080`, `chromium` |
| non-compact drawer button absent | per-route tests via `expectNavigation` (`:120-123`) |
| overflow true/false | `expectNoHorizontalScroll` (`:80-89`) in every per-route test and the RTL test |
| RTL compact user-menu branch | RTL test (`:199-201`) on compact projects |
| RTL medium/expanded direct Language button | RTL test (`:202`) on the other projects |
| lazy page mounted vs Suspense fallback; API skeleton settled | `expectPageReady` (`:154-161`) in every per-route test and the RTL test |
| shell banner heading vs page heading | `expectLandmarks` (`:127-137`) in every per-route test and the RTL test |

No `.skip`, `.only`, `test.fixme`, exclusion, lowered threshold or coverage-ignore comment was added; no assertion was weakened. A live browser run would be required to convert this enumeration into executed green results; it is blocked by the absent browser binaries recorded above.

## Coverage repair — attempt 1 (tripwire pin refresh)

The coverage gate failed because the GAP tripwire's `currentSpecs` pin
(`tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:86`)
listed 9 e2e spec paths while the committed `e2e/` tree contains 15. The pin was
refreshed to all 15 paths. The tripwire stays fully active: it still asserts the
complete filesystem set and fails on any future addition. No threshold was
lowered and no skip, exclusion, ignore comment or weakened assertion was added.
No production source changed, so line/branch coverage of production code is
unchanged.

Every changed branch / assertion and the deterministic test that exercises it:

| Branch / assertion | Test (deterministic evidence) |
|---|---|
| `specs.sort()` vs refreshed 15-path `currentSpecs.sort()` pin (`:166`) | `GAP: all-role, staging-RC and light/dark visual evidence is not yet recorded` reads the real committed `e2e/` tree via `filesBelow()`; `npx vitest run tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts` → `Test Files 1 passed (1)`, `Tests 3 passed (3)` |
| `workflows.sort()` equals `[]` (`:171`) | same `GAP` test scans `.github/workflows` and filters playwright/e2e; empty, corroborated by an independent shell scan (no matching workflow) |
| `visualChecks.sort()` equals `[]` (`:178`) | same `GAP` test scans all 15 specs for `toHaveScreenshot`/`toMatchSnapshot`/`getComputedStyle`/`data-theme`/`colorScheme`; empty, corroborated by an independent grep (no matches) |
| `expectTypeOf<Role>()` union (`:180`, compile-time branch) | `npm run typecheck` → exit 0, no `error TS…` |

Full gate after the fix (`apps/staff-web`):
- `npx vitest run tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts` → `Test Files 1 passed (1)` / `Tests 3 passed (3)`.
- `npm run test:coverage` → `Test Files 268 passed (268)` / `Tests 3045 passed (3045)`; `Statements 100% (5107/5107)`, `Branches 100% (2634/2634)`, `Functions 100% (1697/1697)`, `Lines 100% (4903/4903)`, `No files with missing coverage.`
- `npm run typecheck` → exit 0; `npm run lint` → exit 0; `python3 -I scripts/check-constraints.py --base origin/main` → exit 0.

Evidence source: executed tool commands in the worker checkout at HEAD `83bca3d1` on branch `swarm/RESP-E2E-RESPONSIVE-LAYOUT-1361`. The jsdom "Not implemented: navigation" lines in the coverage run are pre-existing non-fatal noise; the run reported 3045/3045 passing.

## Clinical and architecture review

Not required: the change makes no clinical decision, signs or releases no data, changes no patient identity or sharing boundary and adds no architectural contract. Evidence is simulated and synthetic; it is not clinical or human acceptance.

## Review verdicts

- aurevia-plan-reviewer: GO (after both Required gaps were addressed; first pass was NO-GO)
- aurevia-code-reviewer: GO (after both Required findings were fixed; first pass was NO-GO)
- aurevia-coverage-auditor: GO
- aurevia-devsecops: GO

## Findings

- **Pre-existing red gate — RESOLVED in coverage repair attempt 1 (tool evidence):** `apps/staff-web/tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:166` pinned the e2e spec set to 9 names while the tree holds 15. With the new spec temporarily moved out of the tree the test still failed with 14 received vs 9 expected; with it present, 15 vs 9. The stale list predates this task and belongs to the already-complete `SPEC-WEB-10-C02` task. The pin was refreshed to all 15 committed paths under coverage repair attempt 1; the tripwire remains active and the full gate is green (see "Coverage repair — attempt 1"). No assertion or threshold was weakened. The first task request below is now satisfied and can be closed.
- **Role limitation (plan reviewer, simulated):** production role is fixed to `reception`, so routes granted only to other roles render `ForbiddenPage` inside the same real `AppShell`. The matrix still measures the production shell's width, navigation, landmarks and overflow on every registered route path, but it does not prove the layout of the intended content screens on those denied routes. A dev-only role-override harness needs a production-scope change; requested below rather than attempted out of scope.
- **320 px floor (design doc, simulated):** `docs/design/responsive-layout.md` §13 and decision 0004 set 320 px as the compact floor, but `playwright.config.ts` has no 320 px project (its narrowest is 360 px). Out of scope here; cross-reference task `RESP-E2E-REFLOW-AND-ZOOM` for the 320 px and zoom evidence. This spec does not claim full supported-width coverage.
- **`/design-preview` (route table, tool evidence):** registered only when `import.meta.env.DEV` is true. The default `playwright.config.ts` dev server makes the case apply; if a caller supplies `E2E_BASE_URL` pointing at a production build it degrades to the catch-all, which still exercises the shell but not the preview screen.
- **Live browser evidence unavailable (tool evidence):** all three Playwright browser executable paths are absent in this sandbox. Enumeration and lint were run; a live red/green run and any genuine layout defect detection remain for CI.
- **Brief inaccuracy:** the brief stated that no `.agent/pr-notes/*.md` files existed; the directory contains reconciled daily notes and many task notes. No earlier notes existed for this task. Its step-3 heading assertion named only `header h1`; the plan reviewer correctly required a page heading inside `main` as well, which was added without removing the shell heading check. The brief's "reuse `expectNoBlockingViolations`" list was read as an optional pool: the axe scan is owned by the separate `RESP-E2E-ACCESSIBILITY-SCAN-RESPONSIVE` story, so it was not duplicated across 320 journeys.

## Lessons for workers

- Survey the module's tripwire tests before adding a new e2e spec: `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts` pins the complete spec list and goes red on any addition, so its owner must update it in the same change.
- `tsconfig.json` excludes `e2e`, so `npm run typecheck` cannot catch a spec error; validate with `npx eslint e2e/<file>.spec.ts --max-warnings=0` and `npx playwright test --config e2e/playwright.config.ts <file> --list`.
- A `Suspense` fallback with no heading can make a width measurement falsely green; wait for `main#main h1, main#main h2` before measuring a lazily imported route.
- In this cloud image all Playwright browser binaries are absent; report the `--list` result instead of installing a browser or weakening a test.

## Why this is not finished

The coverage-check failure that triggered this repair is resolved: the stale
`currentSpecs` pin was refreshed to the 15 committed e2e spec paths and the full
`npm run test:coverage` gate is now green (268/268 files, 3045/3045 tests, 100%
statements/branches/functions/lines). The only remaining unverified item is the
live Playwright run, which cannot execute in this sandbox because no browser
binaries are installed. Original failing command and final lines: `bash apps/staff-web/check.sh` → `Test Files  1 failed | 267 passed (268)` / `Tests  1 failed | 3044 passed (3045)`, test `automate-critical-journey-supported-role-real-browser.test.ts > GAP: all-role, staging-RC and light/dark visual evidence is not yet recorded` at line 166 (`expected 15 specs to deeply equal 9`). Cause: e2e specs added by completed tasks were never added to the pinned `currentSpecs` list; verified by removing this spec and rerunning the single test, which still failed with 14 vs 9. Already tried: ran the full gate, isolated the single test, reran it with the new spec temporarily moved out of tree, and checked `SPEC-WEB-10-C02` owns that file. Exact next step: a runner with Playwright browsers installed executes `npx playwright test --config e2e/playwright.config.ts e2e/responsiveLayout.spec.ts`; any genuine defect is handled with a single `test.fixme` plus a follow-up task, never a weakened assertion.

```task-requests
[
  {
    "title": "Refresh the staff-web e2e spec tripwire list",
    "details": "The GAP tripwire in apps/staff-web/tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts pins 9 e2e spec names but the tree has 15 since earlier completed tasks added journeys; bash apps/staff-web/check.sh is red on main because of it. Pin the current 15 paths (including e2e/responsiveLayout.spec.ts) so the gate is green, keeping the tripwire active.",
    "scope": ["apps/staff-web/tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts"],
    "depends": ["RESP-E2E-RESPONSIVE-LAYOUT"]
  },
  {
    "title": "Dev-only role override harness for responsive route matrix",
    "details": "Production role is fixed to reception, so denied registered routes render ForbiddenPage and the responsive matrix cannot measure their intended screens. Add a dev-only harness that mounts the real AppShell with an explicit role and route, and extend the responsive spec to exercise chart, admin, results, lab, pharmacy, operations, messages and radiology screens for their authorised roles at each breakpoint.",
    "scope": ["apps/staff-web/src/app/AppShell.tsx", "apps/staff-web/e2e/role-harness/"],
    "depends": ["RESP-E2E-RESPONSIVE-LAYOUT"]
  }
]
```
