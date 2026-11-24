# FIX-ROUTES-01 · Staff web: route tests must not hard-code the list of registered routes (HIGH PRIORITY: pull requests are failing on it)

PR #333, tested branch tip c4a56e591076319c9264a1737ca95cce06ee1bd6.

**Automatic repair of a failed run on main.** The integrator merges it once the fast gate passes, including changes to workflow, script or agent files.

Task: FIX-ROUTES-01

Swarm worker 1. Local checks: passed. Agent result: ok.

## Summary

Removed the hard-coded 20-pattern route lists from the two staff-web route registry tests. Both tests now derive the expected feature patterns from the same source the router uses — `import.meta.glob('../features/*/routes.ts', { eager: true })` fed through `collectFeatureRoutes` — and assert only the app-owned structure: leading welcome page, ordered fixed pages, trailing `*` catch-all, optional `/design-preview` before `*`, no duplicate patterns. No production file, feature route file, dependency or coverage threshold was changed.

Scope (both files are the only changes):

- `apps/staff-web/src/app/routes.test.tsx` — `createRoutes` → "resolves every existing path".
- `apps/staff-web/tests/app/AppShell.test.tsx` — "route registry" → "registers only welcome, the development preview and a catch-all".

## Affected requirements

| Requirement | Requirement text (PROJECT_V1_SPEC.md) | Acceptance mapping |
|---|---|---|
| UX-01 | Bilingual, RTL, mixed-script, accessible presentation; flexible presentation styling | Fixed system pages keep a stable order; no feature route may shadow another. Tested by the ordered fixed-page subsequence and `new Set(...).size` assertions in both files. |
| WEB-09 | Resilient screens with defined loading/empty/error/forbidden/stale/session-expired states, each verified with a component test | The registry test must survive new feature screens being added, so the fixed pages, catch-all and preview-flag assertions stay valid without editing the test. Verified by the extra-route experiment below. |
| WEB-09 (route registration, READY-51) | Feature routes self-register; app collects every `features/*/routes.ts` | Both tests derive the middle of the expected list from `collectFeatureRoutes` over the real glob rather than listing feature patterns by hand. |

Entry point → test trace: `src/app/routes.tsx:createRoutes` (module-level `import.meta.glob` → `collectFeatureRoutes`) → `src/app/routes.test.tsx` (direct unit assertions) and `src/app/routes.ts:routes` → `tests/app/AppShell.test.tsx` (shell render + registry assertions).

## Risk class

Routine (test-only change, no runtime behavior). Route registration is the app's navigation contract, so both affected files are covered by the full module gate including typecheck, lint, build, bundle budget and 100% per-file coverage.

## Review owner

Senior React and Vite (staff-web tests, Vite `import.meta.glob` semantics); Senior TypeScript (strict typing of the glob and `FeatureRouteModule`). Both are advisory specialists; all checks were executed directly by this worker. No independent reviewer, simulated user or human participant was used for this change (evidence source: executed tool commands only).

## Data changes

None. No migration, backfill, lock, volume impact, rollback or forward-fix. Reserved migration version: none.

## Authorization boundaries and audit events

None changed. No tenant, role or patient-link scope is touched. No audit event added or changed. No patient data, credential, token or real domain name appears in the tests or in this note; only synthetic placeholder route patterns are used.

## Migration and rollout considerations

None. No schema, API contract, OpenAPI client, build script or documentation outside the two scoped test files changed. `routes.tsx` and every `features/*/routes.ts` are untouched.

## Test evidence

Baseline red, from `apps/staff-web`:

```
npx vitest run src/app/routes.test.tsx tests/app/AppShell.test.tsx
 Test Files  2 failed (2)
      Tests  2 failed | 23 passed (25)
```
Both failures were the fixed 20-pattern lists (`expected [ '/', '/signed-out', …(27) ] to deeply equal [ '/', '/signed-out', …(20) ]`); CI reported 27 routes against 20 expected. The seven extra routes are contributed by the `orders`, `operations`, `lab` and `finance` feature modules.

After the change, same command:

```
npx vitest run src/app/routes.test.tsx tests/app/AppShell.test.tsx
 Test Files  2 passed (2)
      Tests  25 passed (25)
```

Robustness experiment (criterion 4): a temporary synthetic module `src/features/zzz-synthetic/routes.ts` registering `/synthetic-check` was added, the same command re-run, and both tests still passed (25 passed). This module was deleted immediately afterwards; `git status --short` shows only the two scoped files modified. The first attempt used a literal `patterns.slice(0, 5)` assertion and **failed** with this extra route — because `collectFeatureRoutes` sorts module paths descending, a module whose name sorts after `system` is inserted between `/` and the fixed system pages. The fixed-page assertion was therefore changed to an ordered-subsequence check, which is robust to any added feature route.

Mandatory frontend gate, from the repository root:

```
bash apps/staff-web/check.sh
 Test Files  131 passed (131)
      Tests  1742 passed (1742)
   Duration  45.55s
 No files with missing coverage.
211 files fully covered.
3 empty files skipped.
```

The stack traces printed during that run come from existing passing tests that intentionally render consumers outside their providers (`tests/i18n/I18nProvider.test.tsx`, `tests/theme/ThemeProvider.test.tsx`); they are not failures. All gates in `check.sh` passed: `gen:api:check`, `typecheck`, `lint`, `build`, `scripts/check-bundle-size.mjs`, `test:coverage`.

Typecheck final lines, from `apps/staff-web` (`npm run typecheck`), exit code 0 — no `error TS…`:

```
> aurevia-staff-web@0.1.0 typecheck
> tsc --noEmit -p tsconfig.json
TYPECHECK_EXIT=0
```

Repository policy checks on this note, from the root: `python3 -I scripts/check-docs.py` → `CHECK_DOCS_OK` (exit 0); `python3 -I scripts/check-secrets.py` → `CHECK_SECRETS_OK` (exit 0).

`scripts/check.sh` was deliberately **not** run: it invokes `apps/api/check.sh` (full `mvn verify`, JaCoCo, OWASP, SpotBugs) and `db/check.sh` integration tests, which this worker is explicitly forbidden from running ("Never run the full mvn verify or any command that runs every integration test"). The scoped module gate above is the canonical check for this staff-web change; the integrator and Delivery run the full gate.

## Coverage

Command and result (from `bash apps/staff-web/check.sh`, which runs `npm run test:coverage`):

```
=============================== Coverage summary ===============================
Statements   : 100% ( 4298/4298 )
Branches     : 100% ( 2211/2211 )
Functions    : 100% ( 1459/1459 )
Lines        : 100% ( 4127/4127 )
================================================================================
```

No production file was changed, so no production line, branch, method or class was added or removed and there are no new production branches to cover. Both changed files are `*.test.*` and are in the documented Vitest coverage exclusion (`**/*.test.{ts,tsx}`); production coverage is therefore identical to the baseline and is 100% on all 211 hand-written source files. No threshold was lowered, no exclusion added, no test skipped.

New assertions and the test that exercises each (all in `src/app/routes.test.tsx` → `createRoutes > resolves every existing path`, mirrored in `tests/app/AppShell.test.tsx` → `route registry > registers only welcome, the development preview and a catch-all`):

| New assertion | Test | Behavior proved |
|---|---|---|
| `expect(patterns).toEqual(['/', ...featurePatterns(), '*'])` | both tests | Every registered pattern is either the welcome page, a pattern covered by `collectFeatureRoutes` of the feature modules, or the catch-all; feature routes are not listed by hand. |
| `expect(withPreview).toEqual(['/', ...featurePatterns, '/design-preview', '*'])` | AppShell test | `/design-preview` is inserted before `*` and absent without the flag. |
| Ordered-subsequ
