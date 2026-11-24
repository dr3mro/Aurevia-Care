# READY-32 · System pages and browser-support check

PR #276, tested branch tip 362c755f22e0c11e26a8a8f4cbc8ae6f7502215d.

Task: READY-32

Swarm worker 10. Local checks: passed. Agent result: ok.

# READY-32 — System pages and browser-support check

Task: READY-32
Branch: `swarm/READY-32-410` (HEAD `de837cd` at start); commit scoped to this story only.
Evidence source for every claim below: executed tool commands and file reads. One bounded Senior React review was dispatched; its findings are recorded under Findings when received. No human, clinical or simulated-user evidence is claimed.

## Summary

Added five fixed, data-free system pages (signed out, session expired, forbidden, not found, unsupported browser) with English and Arabic messages and one primary action each. Added a pure `isSupportedBrowser(userAgent, width)` client check with a documented baseline and fail-closed behavior for unknown clients. The Vite entry point now renders the unsupported page instead of mounting the application when the check fails; the other four pages self-register as feature routes under the READY-51 convention. Existing state views (`SessionExpiredState`, `ForbiddenState`) are reused where they apply.

## Affected requirements

- WEB-03 — session lifecycle pages (signed out, session expired) with a sign-in action and no patient data.
- WEB-07 — supported-client check, compact-width floor, plain message and no data on unsupported clients.

## Risk class

Routine — fixed presentational pages, a pure client predicate and a route registration inside the staff web. No clinical logic, permissions, tenant scope, migration or external contract changes.

## Review owner

Senior React and Vite (bounded advisory review dispatched, outcome under Findings). Senior TypeScript applies to the strict-type entry-point test. No clinical or defensive-security review required for this slice.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix. No fixture, API call, URL, browser storage or log field was added.

## Authorization boundaries and audit events

None changed. The new pages render only translated static text. No tenant, role or patient-link scope is read; no audit event added or changed. The user-agent string is read for the support decision only and is never stored, logged or placed in a URL.

## Migration and rollout considerations

None. The four registered pages are code-split: the build emitted `SessionExpiredPage` (0.19 kB / 0.17 kB gzip), `SignedOutPage` (0.24 / 0.19), `NotFoundPage` (0.24 / 0.19) and `ForbiddenPage` (0.29 / 0.23), all far below the WEB-08 250 KB chunk budget. The canonical bundle-size gate passed.

## Test evidence

Commands run from the repository root (tool evidence, EXIT values observed):

- Red stage (new tests before any production file existed):
  `npx vitest run --exclude 'e2e/**' tests/features/system/browserSupport.test.ts tests/features/system/systemPages.test.tsx tests/main.test.tsx`
  → `Test Files  3 failed (3)` / `Tests  no tests` — all three files failed to resolve the not-yet-created modules.
- Green stage (new tests only):
  `npx vitest run --exclude 'e2e/**' tests/features/system/browserSupport.test.ts tests/features/system/systemPages.test.tsx tests/main.test.tsx`
  → `Test Files  3 passed (3)` / `Tests  50 passed (50)`.
- Full unit suite: `npx vitest run --exclude 'e2e/**'` → `Test Files  51 passed (51)` / `Tests  588 passed (588)`.
- Canonical gate: `bash apps/staff-web/check.sh` → `EXIT=0` (generated-client drift, typecheck, lint, build, WEB-08 bundle gate, coverage).
- Worker gates: `bash scripts/frontend-typecheck-check.sh` → `frontend typecheck: passed` (EXIT=0); `bash scripts/coverage-check.sh` → `coverage check: passed` (EXIT=0).
- Repository check: `bash scripts/check.sh` → EXIT=1 at its `check-pack.py` stage only (`check-pack: opencode.json is stale: run scripts/gen-opencode.py`). This is the documented cloud-worker skip-worktree artifact, not a change defect: `git ls-files -v opencode.json` → `S opencode.json`, and `git show HEAD:opencode.json` differs from the worktree file that the worker setup replaced with the CI profile. I did not touch `opencode.json` or `.opencode/`. Remaining stages run individually were green: `check-names`, `check-wiring`, `check-migrations --require-baseline`, `check-docs`, `check-secrets`, `python3 -I -m unittest discover -s scripts/tests -q` (433 of 436 pass; the 3 failures are all `test_check_pack` comparisons of that same worktree CI profile). The committed `opencode.json` remains untouched for the integrator/Delivery, which see the local profile.

Typecheck final lines (from `check.sh`; the same `tsc --noEmit -p tsconfig.json` also runs inside `npm run build`):

```
> orevia-staff-web@0.1.0 typecheck
> tsc --noEmit -p tsconfig.json


> orevia-staff-web@0.1.0 lint
> eslint . --max-warnings 0
```

No `error TS…` was reported; the stages advanced to the build, which completed with every emitted chunk `ok`.

Coverage final lines (from `check.sh`):

```
No files with missing coverage.
83 files fully covered.

Statements   : 100% ( 1433/1433 )
Branches     : 100% ( 707/707 )
Functions    : 100% ( 477/477 )
Lines        : 100% ( 1368/1368 )
```

Entry point to test trace:
- `src/main.tsx` calls `isSupportedBrowser(navigator.userAgent, window.innerWidth)` before mounting; on failure it renders `ThemeProvider → I18nProvider → UnsupportedBrowserPage` instead of `App`. `tests/main.test.tsx` mocks the predicate and `createRoot`, imports the entry point and inspects the element tree passed to `render` for both outcomes and the missing-root failure.
- `src/features/system/browserSupport.ts` → `tests/features/system/browserSupport.test.ts` drives the 17-case user-agent/width matrix.
- `src/features/system/routes.ts` is discovered by the eager `import.meta.glob` in `src/app/routes.tsx`; `tests/features/system/systemPages.test.tsx` renders each lazy route component, and `tests/app/AppShell.test.tsx` plus `src/app/routes.test.tsx` assert the merged registry order.
- "No data" assertion: `tests/features/system/systemPages.test.tsx > shows no application data on the unsupported page` checks that no synthetic staff/organization/app-title text, navigation landmark or table is rendered.

## Coverage

Command: `npm run test:coverage`, invoked by `bash apps/staff-web/check.sh` (EXIT=0). Result: 100% statements, branches, functions and lines; 83 files fully covered; no files with missing coverage.

Per touched production file from `coverage/coverage-summary.json` (lines / branches / functions / statements, all 100%): `src/main.tsx`, `src/features/system/browserSupport.ts`, `src/features/system/messages.ts`, `src/features/system/SystemNotice.tsx`, `src/features/system/SignedOutPage.tsx`, `src/features/system/SessionExpiredPage.tsx`, `src/features/system/ForbiddenPage.tsx`, `src/features/system/NotFoundPage.tsx`, `src/features/system/UnsupportedBrowserPage.tsx`, `src/features/system/routes.ts`.

New branches and the test that takes each side:

- `browserSupport.ts` `if (width < MIN_SUPPORTED_WIDTH)` true → `isSupportedBrowser > narrower than compact`; false → every other matrix case, including `compact width` at exactly 768.
- `browserSupport.ts` `if (INTERNET_EXPLORER.test(userAgent))` true → `Internet Explorer 11` and `Internet Explorer 10`; false → all browser cases.
- `browserSupport.ts` `if (match === null)` in `matchedMajor` true → a Chrome user agent against the Edge, Firefox and Safari rules (and an unknown UA against every rule); false → each matching browser case.
- `browserSupport.ts` `return major >= rule.baseline - SUPPORTED_VERSION_WINDOW` true → `current Chrome/Edge/Firefox/Safari` and `… two majors behind`; false → `Chrome three majors behind`, `old Edge`, `old Firefox`, `old Safari`.
- `browserSupport.ts` final `return false` (no rule matched) → `unknown client fails closed`.
- `main.tsx` missing-container `if` true → `fails closed when the root element is missing`; false → both mount tests.
- `main.tsx` support ternary true → `mounts the app when the browser c
