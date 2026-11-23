## Summary

Stabilize the Facilities responsive/RTL assertion and keep the patient-app ESLint 9 flat config starting. The task was already claimed with earlier fixes on `main` (HEAD `deea5ca0`, attempts 1). This run confirms those fixes end to end, re-runs the focused and canonical gates, and repairs one additional, distinct in-scope lint-startup failure found during verification: Expo's flat preset registers `import/parsers` with a bare `@typescript-eslint/parser` key that npm nests rather than hoists, so `import/namespace` failed on every imported TypeScript file. The config now resolves that parser through the already-present `createRequire` anchor at Expo's package and remaps only that one key to an absolute path, preserving every lint rule.

No production source, test behavior, threshold, exclusion or suppression changed beyond that config edit. The staff-web test file is unchanged; its earlier fix is confirmed, not redone. The work is **not finished**: the canonical staff-web gate is blocked by two pre-existing, out-of-scope problems on `main` (generated-client drift and 19 unchanged source files below the 100% coverage threshold). See `## Why this is not finished`.

## Affected requirements

- WEB-07, UX-01 — Facilities responsive/RTL table semantics; no behavior added, removed or relaxed.
- OPS-06 — patient-app ESLint flat-config startup and the 100% frontend gate.

The brief named no requirement IDs in the specification body; the IDs above come from the task entry and the test's own `describe` title. No contract, route, migration or clinical rule is touched.

## Risk class

Routine (one test-timing confirmation plus one ESLint config resolution fix). No contract, schema, authorization or clinical behavior changes. The change only makes an existing rule set loadable.

## Review owner

Integrator / orchestrator. Read-only reviewers consulted this run: `smartclinic-plan-reviewer` (pre-code and again after the config edit), `smartclinic-test-engineer`, `smartclinic-senior-react`, `smartclinic-senior-mobile`, then `smartclinic-code-reviewer`, `smartclinic-coverage-auditor` and `smartclinic-devsecops`.

## Data changes

None. No migration, backfill, lock or volume impact. All fixtures are synthetic (`alphaOrganizationId`, `MAIN`, `Synthetic Main Facility`, `UTC`, long English/Arabic strings). No PHI or credential appears in code, tests, logs or this note.

## Authorization boundaries and audit events

None. No authorization logic, tenant scope or audit event added or changed.

## Migration and rollout considerations

None. The ESLint config change affects local/CI tooling only.

## Test evidence

Evidence source: **executed tool commands** plus read-only reviewer verdicts. No simulated-user or human-participant evidence was available or claimed.

Entry-point-to-test trace (verified by reading source): `FacilitiesPage` (`FacilitiesPage.tsx:259`) → `FacilitiesContent` (`:160`) → `FacilitiesList` (`:77`) → `ApiPage`/`useApi` (`ApiPage.tsx:38-39,81-84`), which commits the `<table>` with its `<thead>` headers when status becomes ready, with `accumulated = []` and an initially empty `<tbody>` → `PageSink` (`FacilitiesPage.tsx:108`, `PageSink.tsx:16-19`), whose `useEffect` appends the rows one commit later. The responsive test awaits `within(table).findByRole('rowheader', { name: 'MAIN' })` before reading cells (`FacilitiesPage.responsive.test.tsx:208-210`) and again in the deferred-transport case (`:334-336`), so it cannot observe the transient empty tbody. RTL `bdi` (`:286-293`) and logical-`[style]` (`:295-302`) loops, the width matrix and the width-change case are intact. No `vi.useFakeTimers`, retry plugin, raised timeout, `jest` import, `.skip` or assertion-free test was added.

Honest red-first note: the race failure is CI-load-dependent (earlier full-suite evidence: roughly one in four runs failed; CI annotation `TestingLibraryElementError: Unable to find an accessible element with the role "rowheader" and name "MAIN"` with an empty `<tbody/>`). **No local red was reproduced** by me. I therefore did not claim one. The fix is race-free by construction: a row-scoped `findByRole` waits for the end state, and the deferred-transport case widens the two-commit gap at the mocked boundary.

Commands and final lines (all run this pass):

- Focused, repeated 6× in `apps/staff-web`: `npx vitest run tests/responsive/features-admin/FacilitiesPage.responsive.test.tsx --reporter=dot` → `Test Files  1 passed (1)` / `Tests  17 passed (17)` on every run (durations 1.02–1.22 s).
- `bash apps/staff-web/check.sh` → exit **1**, failing at stage 1: `> smartclinic-staff-web@0.1.0 gen:api:check`, `✨ openapi-typescript 7.13.0`, `✘  Generated types are not up-to-date!`.
- Remaining staff-web stages run individually: `npm run typecheck` → exit 0 (`> tsc --noEmit -p tsconfig.json`, no diagnostics); `npm run lint` → exit 0 (`> eslint . --max-warnings 0`, no output); `npm run build` → exit 0 (`✓ built in 510ms`); `node scripts/check-bundle-size.mjs` → exit 0 (all chunks `ok`).
- `npm run test:coverage` in `apps/staff-web` → exit **1**: `Test Files  213 passed (213)`, `Tests  2613 passed (2613)`, but 19 unchanged source files below the 100% threshold (46 `ERROR: Coverage for … does not meet global threshold (100%)` lines). The Facilities responsive test is not among them.
- `bash apps/patient-mobile/check.sh` BEFORE the config edit → exit 1, `> patient-mobile@0.1.0 lint`, `> eslint .`, `✖ 520 problems (516 errors, 4 warnings)`, representative line `Parse errors in imported module '../../api/reauthentication': Cannot find module '@typescript-eslint/parser'` under `import/namespace`. Typecheck itself was clean.
- `bash apps/patient-mobile/check.sh` AFTER the config edit (two full runs) → exit **0** both times. Typecheck final lines verbatim: `> patient-mobile@0.1.0 typecheck`, `> tsc --noEmit`, then no diagnostics before `> patient-mobile@0.1.0 lint` / `> eslint .` (also zero output). Jest: `Test Suites: 99 passed, 99 total`, `Tests: 1284 passed, 1284 total`, coverage table `All files | 100 | 100 | 100 | 100`.
- `npx prettier --check eslint.config.mjs` → `All matched files use Prettier code style!` (exit 0).
- `python3 -I scripts/check-constraints.py` → exit 0 (no suppression, exclusion, skip or lowered threshold).
- `python3 -I scripts/check-docs.py` → exit 0.
- `cd apps/patient-mobile && npx eslint --print-config App.tsx` → `settings['import/parsers']` contains the single absolute key `…/node_modules/eslint-config-expo/node_modules/@typescript-eslint/parser/dist/index.js` mapped to `[".ts", ".tsx", ".d.ts"]`, proving the remap branch actually ran rather than the config merely parsing.
- `python3 -I scripts/check-constraints.py --require-base` → exit 0.
- `git merge --no-edit origin/main` → `Already up to date.` at start and before finish; no conflict markers remain (`git grep -nE '^(<<<<<<<|>>>>>>>) '` → none outside pre-existing Java artifacts).

## Coverage

Commands and results:

- `bash apps/patient-mobile/check.sh` after the config edit → exit 0, Jest 100% statements/branches/functions/lines (`All files | 100 | 100 | 100 | 100`), 1284 tests. This is the canonical measure for the only file I changed in production terms.
- `npm run test:coverage` in `apps/staff-web` → the in-scope test file passes all 17 cases and all 2613 tests pass, but the run exits 1 on 19 unchanged source files (see `## Why this is not finished`). This is pre-existing and outside the two-file scope; no threshold was lowered and no exclusion added.

New production branches: none. `apps/patient-mobile/eslint.config.mjs` is tooling configuration outside `jest.config.ts` `collectCoverageFrom` (which collects `App.tsx`, `index.ts`, `app.config.ts` and `src/**/*.{ts,tsx}`), so no Jest test instruments it and none is owed. Closest supported measure plus compensating proof: the before/after full `check.sh` pair above — 516 `import/namespace` parse errors before, zero lint diagnostics after — plus typecheck, 1284 Jest tests at 100% coverage for the actual production sources, a clean Prettier check, and the `eslint --print-config App.tsx` result above. The `if (extensions === undefined) return config;` early-return side is exercised by the Expo preset objects that declare no bare `import/parsers` key; the remap side is exercised by the objects that do, proven by the absolute key in `--print-config`. A failed parser resolve would throw at config load, so the green `npm run lint` is also the failure-path proof. No test was skipped, disabled or made assertion-free; no pragma, exclusion or suppression was added, and `check-constraints.py` (plain and `--require-base`) confirms the pre-existing `ignores`/`off` lines are unchanged.

## Clinical and architecture review

Not required. No signed data, cross-organization sharing, patient association, prescribing or critical result is touched. No clinician or patient participant was involved; all evidence above is from executed commands and read-only reviewers.

## Review verdicts

- smartclinic-plan-reviewer: GO
- smartclinic-code-reviewer: GO (pure remap; no rule, threshold, suppression or dependency changed; Optional comment nit resolved)
- smartclinic-coverage-auditor: GO (no production source or test changed; config is outside Jest collectCoverageFrom; no excluded/lowered threshold)
- smartclinic-devsecops: GO (no findings; absolute path resolves pinned sub-dependency, no new package, no weakened gate, no PHI/secret)
- smartclinic-test-engineer: GO (honest red-first; deferred-transport case covers the two-commit race)
- smartclinic-senior-react: GO (staff-web TSX test correctness)
- smartclinic-senior-mobile: GO (updated config, second fix)

## Findings

- **Brief incomplete on mobile lint.** The brief said to only confirm `eslint.config.mjs` and "do not redo". Verification exposed a second, distinct startup failure: `import/namespace` could not resolve the bare `@typescript-eslint/parser` key from Expo's preset because npm nests that package (`package-lock.json:8859` under `eslint-config-expo`, `:20174` under `typescript-eslint`, no top-level entry). Since the story is "restore mobile lint startup" and this file is in scope, I fixed it with the existing `createRequire` anchor. All earlier fixes (single plugin registration, copied plugin map, no unused `_shared` binding, Expo resolver via `createRequire`) are intact.
- **Tracker contradiction for memory reconciliation.** The task entry records earlier evidence that "generated API check … passed", but this run's `bash apps/staff-web/check.sh` fails at `gen:api:check` with `✘  Generated types are not up-to-date!` on the same commit range. Both claims are recorded verbatim here; the integrator should reconcile them rather than assume a regression from this change. The generated diff (160 lines of instant-events routes/types) and the commit dates support the "main drifted after the earlier claim" reading.
- **Staff-web contract drift is real and pre-existing.** `contracts/openapi.yaml` was last changed by `2844218b` (SMSG-04, adds `/api/v1/staff/instant/events` and `RealtimeEvent*` types) while `apps/staff-web/src/api/generated/openapi.d.ts` was last regenerated by `b0bc46b8`, before it. Generating to a temporary file and diffing shows 160 diff lines of those new routes/types. I did **not** regenerate the client: that artifact is outside the two-file scope and belongs to the story that changed the contract.
- **Staff-web coverage shortfall is pre-existing.** Exactly 19 unchanged source files fall below the 100% per-file threshold; the list includes `src/theme/useBreakpoint.ts`, several `*fixtures.ts`, scheduling, rad and print components. None is a file I touched. I did not lower a threshold, add an exclusion or write compensating tests for unrelated production code.
- **Local red for the race remains unreproduced**, as recorded in `## Test evidence`; the CI annotation and the race-free construction are the honest evidence.
- `git merge --no-edit origin/main` was clean both times; no worker-introduced conflict markers.
- Known worker-image limits (npm registry DNS for `npm audit`, read-only Git fetch, `opencode.json` skip-worktree profile) were not re-diagnosed; they are unrelated to this change.

## Lessons for workers

- A gate failure at a stage outside the task scope (generated-client drift, another story's coverage shortfall) is an "unfinished" outcome: record the exact command and final line and stop. Do not regenerate artifacts, lower a threshold or add an exclusion to make a green gate.
- Verify contract/gen drift before assuming it is your change: generate to a temporary file and diff, then check which commit last touched the contract versus the generated artifact.
- npm may nest `@typescript-eslint/parser` under `eslint-config-expo` and `typescript-eslint` with no top-level copy. An ESLint 9 flat config that inherits Expo's `import/parsers` bare key then fails every `import/namespace` check; remap that single key to an absolute path resolved from Expo's own package via `createRequire`.
- A CI-load-dependent race may not reproduce locally. Record the CI annotation verbatim, state "no local red reproduced", and make the fix race-free by construction (wait for a row-scoped role) rather than asserting a transient empty state or raising timeouts.

## Why this is not finished

The patient-app half is green, but the canonical staff-web gate cannot pass from inside this two-file scope.

1. `bash apps/staff-web/check.sh` → exit 1 at its first stage: `✘  Generated types are not up-to-date!` (`gen:api:check`, openapi-typescript 7.13.0).
   - Most likely cause: `contracts/openapi.yaml` gained instant-events routes in merged commit `2844218b` while `apps/staff-web/src/api/generated/openapi.d.ts` was last regenerated in `b0bc46b8`; a 160-line generated diff confirms real drift on `main`, not a tooling mismatch (the pinned generator version is 7.13.0 in both the lock step and the output).
   - What I tried: ran the generator to `/tmp/opencode/openapi.generated.d.ts` and diffed it against the checked-in file; checked `git log` for both paths; made no edit because the generated client and contract are outside scope.
   - Exact next step: the owner of the contract change (or the integrator) regenerates the staff-web client in the same change that added the routes, then re-runs `bash apps/staff-web/check.sh`.
2. `npm run test:coverage` in `apps/staff-web` → exit 1: `Test Files  213 passed (213)`, `Tests  2613 passed (2613)`, but 19 unchanged source files below the 100% threshold, e.g. `ERROR: Coverage for functions (90%) does not meet global threshold (100%) for src/theme/useBreakpoint.ts`.
   - Most likely cause: other merged stories added production files without enough tests; unrelated to this test-timing fix.
   - What I tried: ran all other stages individually (typecheck, lint, build, bundle size all exit 0) and the focused Facilities suite six times (17/17 each). I did not touch thresholds or exclusions.
   - Exact next step: route the 19 uncovered files to their owning stories (add the missing tests) as a separate task, then re-run the staff-web coverage gate.

## Task requests

Both blockers need files outside this task's two-file scope, and no task in `tasks/` covers either. The integrator should create these on merge (the first one belongs with the story that changed the contract):

```task-requests
[
  {
    "title": "Regenerate staff-web OpenAPI client after SMSG-04 contract change",
    "details": "bash apps/staff-web/check.sh exits 1 at gen:api:check with 'Generated types are not up-to-date!'. contracts/openapi.yaml gained /api/v1/staff/instant/events and RealtimeEvent* in merged commit 2844218b, while apps/staff-web/src/api/generated/openapi.d.ts was last regenerated in b0bc46b8. Run npm run gen:api in apps/staff-web on the current contract and re-run the gate. Do not weaken the drift check.",
    "scope": ["apps/staff-web/src/api/generated/openapi.d.ts"],
    "depends": []
  },
  {
    "title": "Restore 100% staff-web coverage on 19 uncovered source files",
    "details": "npm run test:coverage in apps/staff-web passes all 2613 tests but exits 1 on 19 unchanged source files below the 100% per-file threshold, for example src/theme/useBreakpoint.ts at 90% functions. Add the missing behavior tests in the owning stories. Do not lower a threshold, add an exclusion or skip a test.",
    "scope": ["apps/staff-web/src/theme/useBreakpoint.ts", "apps/staff-web/src/components/invariant.ts", "apps/staff-web/src/features"],
    "depends": []
  }
]
```
