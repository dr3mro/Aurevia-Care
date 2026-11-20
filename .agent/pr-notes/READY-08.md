# READY-08 — Staff web route code-splitting and a bundle-size budget

- Branch: `swarm/READY-08-260` (base `2b184f6`)
- Requirement: **WEB-08** — "each route chunk at most 250 KB gzip" (implemented as a hard
  256000-byte gzipped ceiling per emitted `dist/assets/*.js`).
- Status: all gates pass locally. No database or API changes; no new dependency; no third-party
  or external script added; synthetic fixtures only.

## Acceptance mapping

| Story step | Change | Test evidence |
|---|---|---|
| 1. Lazy-load scheduling, chart and admin screens in one `Suspense` | `src/App.tsx`: three `React.lazy(() => import('./features/...'))` components, each with a thin adapter that supplies the screen's existing synthetic props; all three are children of a single `<Suspense>`. `AppShell` now accepts `children` and renders them inside `<main>`. | `src/app/screenLoading.test.tsx` asserts the fallback is a `<p role="status">Loading</p>` and that it disappears once all three lazy screens resolve. Existing `tests/App.test.tsx` still passes. |
| 1b. Fallback selection | READY-07 is **not** merged in this checkout (no `src/components/StateViews.tsx` exists), so the story's fallback rule selects a plain accessible "Loading" text with `role="status"`. A comment in `App.tsx` records that READY-07 should swap in `LoadingState`. | `src/app/screenLoading.test.tsx` (fallback tag and role). |
| 2. `scripts/check-bundle-size.mjs` | Reads every `dist/assets/*.js` via `readdirSync`, gzips each with `zlib.gzipSync`, prints an aligned table, and sets `process.exitCode = 1` when any file exceeds the module-level constant `MAX_CHUNK_GZIP_BYTES = 256000`. | `scripts/check-bundle-size.test.mjs` (limit constant, 300 KB rejection, 10 KB acceptance, gzip-vs-raw behaviour, `measureAssets` failure path). |
| 3. Call the script after the build in `check.sh` | `check.sh` had no build step, so `npm run build` was added and `node scripts/check-bundle-size.mjs` placed immediately after it (before `test:coverage`). | Wiring assertion in `scripts/check-bundle-size.test.mjs` checks both strings and their order. |
| 4. Failing test first | `scripts/check-bundle-size.test.mjs` was written before the script existed and shown red. | Red run below. |
| 5. Measured sizes | Recorded below from the final build/gate run. | Table below. |

## Commands and results (exact)

Run in `apps/staff-web` unless stated otherwise.

1. `npm ci --no-audit --no-fund` → `added 312 packages in 5s`.
2. **Red:** `npx vitest run scripts/check-bundle-size.test.mjs` →
   `FAIL scripts/check-bundle-size.test.mjs [ scripts/check-bundle-size.test.mjs ]` —
   `Error: Failed to resolve import "./check-bundle-size.mjs" ... Does the file exist?`
   (0 tests collected).
3. `npx vitest run scripts/check-bundle-size.test.mjs src/app/screenLoading.test.tsx tests/App.test.tsx tests/app/AppShell.test.tsx` →
   `Test Files 4 passed (4)`, `Tests 13 passed (13)`.
4. First full `bash check.sh` → `src/App.tsx(54,9): error TS2322: Type 'string | undefined' is not
   assignable to type 'string'` — `StaffMember.subject` is optional in the generated contract type
   while the synthetic fixture always supplies it. Fixed with a non-null assertion at the adapter
   boundary (the same assertion style already used in the feature fixtures).
5. Second full `bash check.sh` → `eslint`: `'Buffer' is not defined  no-undef` in
   `scripts/check-bundle-size.test.mjs`. Fixed by importing `Buffer` from `node:buffer` instead of
   relying on a global (the module's ESLint config gives no Node globals to `.mjs` files; the CLI
   script declares the few Node globals it uses with an inline `/* global ... */` directive).
6. **Final** `bash check.sh` → exit `0`:
   - `npm run gen:api:check` — "generated client matches the contract";
   - `npm run typecheck` — clean;
   - `npm run lint` — clean;
   - `npm run build` — `58 modules transformed`, built in ~118 ms;
   - `node scripts/check-bundle-size.mjs` — all four chunks `ok` (table below);
   - `npm run test:coverage` — `Test Files 32 passed (32)`, `Tests 206 passed (206)`;
     `Statements 100% (563/563)`, `Branches 100% (286/286)`,
     `Functions 100% (224/224)`, `Lines 100% (529/529)`, `No files with missing coverage.`,
     `45 files fully covered.`

   The stack-trace lines in the captured log come from pre-existing `I18nProvider` / `ThemeProvider`
   tests that intentionally render consumers outside their providers; they are expected console
   noise, not failures, and the run is green.

7. `npm run build && node scripts/check-bundle-size.mjs` → exit `0`, chunk table below.
8. **Gate-failure probe** (temporary 300 KB random `.js` written into the git-ignored `dist/assets`,
   then removed): the script printed `OVER` for that chunk and
   `One or more chunks exceed 256000 bytes gzipped (WEB-08).`, exit `1`. This proves the gate is not
   vacuous.

## Measured gzip sizes (final build, via Node `zlib.gzipSync`)

| Chunk | Gzip bytes | Budget | Result |
|---|---:|---:|---|
| `AdminScreen-COa0bEyg.js` | 4198 | 256000 | ok |
| `ChartScreen-CwF-G8HL.js` | 5712 | 256000 | ok |
| `SchedulingScreen-C7SytwZH.js` | 3235 | 256000 | ok |
| `index-CPdlsAy1.js` | 52977 | 256000 | ok |

Largest chunk is 52977 bytes (≈21% of the budget). The three feature screens are emitted as three
distinct dynamic-import chunks, which is the direct evidence that code-splitting is in effect.
No budget, dependency or third-party script was added or removed.

## Decisions and notes

- The screens need many props and there is no router yet (READY-19/21/22/23 are still open on
  `main`). Rather than change screen APIs, the lazy factories resolve the named screen export and
  return a default component that supplies the existing synthetic fixtures. This keeps each screen
  as its own chunk without duplicating fixture logic into the screen modules.
- Vite's own reported gzip figures differ slightly from the gate because the gate deliberately
  uses Node's `zlib.gzipSync` as the story specifies; the gate's numbers are the authoritative ones
  here.
- `AppShell` gained an optional `children` prop so route content lives inside the `main` landmark;
  existing `AppShell` tests that render without children are unchanged.
- Root Prettier ignores `apps/**` (`.prettierignore`), so the module's own `check.sh` is the
  formatter/tooling authority; no separate Prettier run applies to these files.
- New files are named after what they hold (`check-bundle-size`, `screenLoading`), not after the
  story ID.

## Evidence source

- Tool evidence: every command above was executed locally in this checkout; results are pasted
  verbatim. No reviewer, simulated user or human participant evidence was used for this story.
- No clinical or patient data: all rendered content comes from the existing synthetic fixtures.

## Defects found

None in production behaviour. Two type/lint issues in the new code were found and fixed during the
check runs (optional `StaffMember.subject`, ESLint `no-undef` for `Buffer`); both are recorded above
with their exact diagnostics. No open question or missing evidence remains.
