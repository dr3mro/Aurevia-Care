# READY-33 — Patient app: Expo entry point and app configuration

## Requirement IDs and acceptance mapping

- **PAT-01, UX-01** (TODO.md READY-33). Minimum runnable Expo shell with a
  bilingual application title; English and Arabic are both supported and the
  placeholder is direction-neutral (no physical left/right styling).

| Story step | Delivered in | Acceptance evidence |
|---|---|---|
| 1. `index.ts` registers the root component | `apps/patient-mobile/index.ts` | Test `index registers App as the root component` asserts `registerRootComponent` was called with `App`; 100% line/branch coverage of `index.ts`. |
| 2. `App.tsx` renders `<RootNavigator />`; placeholder shows `appTitle` in en/ar | `apps/patient-mobile/App.tsx`, `src/app/RootNavigator.tsx`, `src/app/messages.ts` | Tests render English and Arabic titles and assert the header role and active accessibility language; `App renders the placeholder root navigator`. |
| 3. `app.json` from `project.json` with no secrets | `apps/patient-mobile/app.json` | `appConfig.test.ts` asserts name `Aurevia Care`, slug `aurevia-care`, version `0.1.0`, placeholder ids, and recursively fails on `apiKey`/`token`/`secret`/`password` in any key or value. Orientation `portrait`, `userInterfaceStyle: automatic`, `ios.supportsTablet: false`. |
| 4. Tests | `src/app/RootNavigator.test.tsx`, `src/app/appConfig.test.ts` | Jest + `@testing-library/react-native`; 7 new tests, all passing. |

No dependency was added and `expo prebuild` was not run. `package.json` is
unchanged (`main` was already `index.ts`).

## Commands run and results (evidence source: executed tool commands)

From `apps/patient-mobile` unless noted.

1. `npm ci --no-audit --no-fund` → `added 1272 packages in 13s` (only npm
   deprecation warnings for transitive packages).
2. **Red (TDD)** `npx jest src/app --no-coverage` → `Test Suites: 2 failed, 2
   total; Tests: 0 total`. Failures: `Cannot find module '../../app.json' from
   'src/app/appConfig.test.ts'` and `Cannot find module '../../App' from
   'src/app/RootNavigator.test.tsx'`. Confirmed the tests fail for the missing
   production behavior before implementation.
3. **Green** `npx jest src/app --no-coverage` → `Test Suites: 2 passed, 2 total;
   Tests: 7 passed, 7 total`.
4. `npm run typecheck` → exit 0, no diagnostics.
5. `bash check.sh` (canonical module gate: shellcheck, `npm ci`, typecheck,
   ESLint, `jest --coverage --ci`) → exit 0. `Test Suites: 29 passed, 29 total;
   Tests: 290 passed, 290 total; Snapshots: 0 total`. Global coverage
   `All files 100% / 100% / 100% / 100%` (lines/branches/functions/statements).
6. `npx jest --coverage --ci` filtered for new files → `App.tsx 100%`,
   `index.ts 100%`, `messages.ts 100%` (shell and src/app), `All files 100%`.
7. From repo root: `python3 -I scripts/check-docs.py` → exit 0;
   `python3 -I scripts/check-secrets.py` → exit 0.

`bash apps/patient-mobile/check.sh` is the check named in TODO.md step 6 for
patient-app work; it passed. The integrator runs database and integration tests.

## Defects found

- No production defect found. One test-authoring issue was observed and fixed in
  the same change: this pinned `@testing-library/react-native` returns a Promise
  from `render`, so the new tests use `await render(...)` (matching the existing
  screen tests in the module). No rule was disabled and no threshold lowered.

## Decisions and notes

- `RootNavigator` takes a `locale: 'en' | 'ar'` prop and reads the title from
  `src/app/messages.ts`. The bundle follows the existing feature pattern
  (parity-checked en/ar dictionary, one key per string), so READY-34 can extend
  the same bundle for tab labels. `App.tsx` supplies the default English locale
  until READY-34 introduces locale state; no network or auth code is added.
- Tests live under `src/app` to respect the declared scope, while importing
  `../../App` and `../../index` so both root files are executed and meet the
  100% coverage gate without adding out-of-scope test files. `expo` is mocked in
  that test so the entry point is exercised without registering a native root.
- `expo.name` and `expo.slug` are copied from `project.json` (`displayName`,
  `repositorySlug`) as the story requires. `app.json` is static JSON, so the
  README-SETUP idea of reading the display name at build time would need a
  generator script outside this story's scope; it is not introduced here.
- **Owner action required:** `app.json` uses placeholder identifiers
  `com.example.aureviacare` for `ios.bundleIdentifier` and `android.package`.
  The owner must replace both with the approved application identifiers before
  any real build or store submission. No key, token, URL or secret is present.
- The `apps/patient-mobile/docs.sh` hook still emits the documented
  "reference pending" page: it keys off `src/index.ts`, which this story does
  not create (the Expo entry is the root `index.ts`). No docs hook change made.

## Evidence classification

- Tool evidence: all commands above were executed locally; results are from
  their actual output, not summarized from a reviewer.
- Simulated reviewer: `smartclinic-senior-mobile` bounded read-only review — see
  the review disposition appended below when received.
- Human/clinical/patient participant evidence: none. This is a synthetic,
  non-clinical entry-point slice; no patient data was used.
