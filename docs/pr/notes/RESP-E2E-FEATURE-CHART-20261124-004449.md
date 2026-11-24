## Summary

Staff-web end-to-end responsive proof for the chart feature (WEB-07, UX-01). One new Playwright spec (`apps/staff-web/e2e/responsive-chart.spec.ts`) drives the chart-feature note screen through its real providers and transport in all 10 installed device projects, in English and Arabic RTL, by keyboard and by touch. It asserts document direction, no page-wide horizontal scroll, a fixture-derived synthetic SOAP value reachable at every width, layout band, save/sign commands and signer attestation, plus a WCAG gate. The longitudinal-chart, documents and revision-history screens have no browser-mount harness; they are recorded as `test.fixme` with exact reasons and a task request. No production source, other e2e file, tracker, script, config or dependency was changed.

## Affected requirements

- WEB-07 — supported clients and device matrix: staff web at 1280x720 and above with compact layout; decision 0004 governs a supported compact layout down to 320 px and this suite exercises 360 px phones through 1920 px desktops.
- UX-01 — English and Arabic RTL (`html[dir]`/`html[lang]`), mixed-script clinical text, no page-wide horizontal scroll, all clinical values reachable on compact layouts, WCAG 2.2 AA gate.

Note: WEB-07 in `AUREVIA_V1_SPEC.md` still states the 1280/768 floors and a sub-768 px "use a larger screen" notice. `docs/decisions/0004-responsive-staff-web.md:54` says a person must amend the specification; the swarm may not. Decision 0004 and `docs/design/responsive-layout.md` govern this test; the spec text was not edited.

## Risk class

Routine (test-only addition, synthetic data, no production contract, data or permission change).

## Review owner

Senior React and Vite; Senior TypeScript; Test Engineer; plan, code, coverage and devsecops reviewers.

## Data changes

None. No migration, backfill, lock or volume change. The synthetic note stub intercepts only `/api/v1/**` and supplies the XSRF cookie from the existing helper. No PHI, credential, real identifier or real endpoint is used.

## Authorization boundaries and audit events

None changed. The harness mounts the real `NotePage` with production `ThemeProvider`, `I18nProvider`, `ActiveOrganizationProvider` and `RouterProvider` at a pinned synthetic patient/note route. No role, tenant or patient-link rule is touched; no audit event is added.

## Migration and rollout considerations

None. A new e2e spec only; no runtime, build output, container or deployment change.

## Test evidence

Evidence source: executed tools (commands and final lines below), read-only specialist reviewers, and synthetic browser journeys. No real clinician, patient or human-participant evidence.

- `git merge --no-edit origin/main` → `Already up to date.`; no conflict markers (`git grep -nE '^(<<<<<<<|>>>>>>>) '` empty).
- Environment setup (tool): `npx playwright install chromium` succeeded; `npx playwright install-deps chromium` succeeded as root on Debian 12. The first attempt to run the existing clinicalnote journey failed with `error while loading shared libraries: libglib-2.0.so.0`; installing the host dependencies removed that limit. This was an environment setup step, not a code defect.
- `cd apps/staff-web && npx eslint e2e/responsive-chart.spec.ts --max-warnings=0` → exit 0, no output (strict, type-aware ESLint 9.39.5 / typescript-eslint 8.73.0; zero findings).
- `cd apps/staff-web && npx tsc --noEmit -p tsconfig.lint.json` → exit 0, no output (TypeScript 5.7.3; `tsconfig.lint.json` is the only compiler config that includes `e2e/`).
- `cd apps/staff-web && npm run typecheck` → `> tsc --noEmit -p tsconfig.json` → exit 0, no output. (`tsconfig.json` excludes `e2e/`, so this alone is not evidence for the new spec.)
- `cd apps/staff-web && npx playwright test --config e2e/playwright.config.ts e2e/responsive-chart.spec.ts --list` → `Total: 50 tests in 1 file` (5 tests × 10 installed projects). Projects: `chromium`, `phone-portrait-360x740`, `phone-portrait-390x844`, `phone-landscape-844x390`, `tablet-portrait-768x1024`, `tablet-portrait-820x1180`, `tablet-landscape-1024x768`, `tablet-landscape-1180x820`, `laptop-1280x720`, `desktop-1920x1080`. The config additionally registers WebKit/Firefox variants of 390x844, 820x1180 and 1280x720 only when those binaries exist; they are absent here. WEB-07 engine mapping: Chromium covers Chrome/Edge, WebKit covers Safari, Firefox covers Firefox.
- `cd apps/staff-web && npx playwright test --config e2e/playwright.config.ts e2e/responsive-chart.spec.ts --retries=0` → `30 skipped / 20 passed (23.0s)`, exit 0 (the same run before the final comment-only cleanup reported `20 passed (31.0s)`). The 20 passes are the English and Arabic note journeys in every installed project; the 30 skips are the three reason-bearing `test.fixme` screens in those projects. Final re-verification after merging `origin/main`: ESLint exit 0, `tsc -p tsconfig.lint.json` exit 0, Playwright `30 skipped / 20 passed (23.0s)`.
- Entry point to test trace: `GET /e2e/clinicalnote/clinicalnote-harness.html` (Vite dev server started by the Playwright webServer) → `clinicalnote-harness.tsx` mounts `RouterProvider` at `/patients/patient-100/notes/note-syn-1` with the real providers and locale toggle → `NotePage` loads patient and note through `getPatient`/`getNote` → real transport sends `/api/v1/**` requests, fulfilled locally by `installSyntheticNoteApi` → `NoteEditor` renders the SOAP fields → assertions on values, status, recorded commands, direction, band, overflow and axe. Save is `PUT /api/v1/clinical-notes/note-syn-1`; sign is `POST /api/v1/clinical-notes/note-syn-1/sign`.
- `bash apps/staff-web/check.sh` → exit 1 at the Vitest stage only: `Test Files  1 failed | 267 passed (268)`, `Tests  1 failed | 3044 passed (3045)`. The single failure was the spec-set tripwire at `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:166`: `currentSpecs` pinned 9 e2e specs while the tree had 15. It is now resolved — see `## Coverage repair — attempt 1`; the full coverage gate re-run is green at 100%.
- A/B proof of the pre-existing failure (tool): with `responsive-chart.spec.ts` temporarily moved out of the tree, `npx vitest run tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts` still failed at the same line with `expected [ …(14) ] to deeply equal [ …(9) ]` and `Tests  1 failed | 2 passed (3)`. The file was restored immediately; no out-of-scope file was edited.
- Because `check.sh` uses `set -e` and reached line 20, every earlier stage passed. Each was also run individually (tool, all exit 0):
  - `npm run gen:api:check` → `gen:api: generated client matches the contract`
  - `npm run typecheck` → `> tsc --noEmit -p tsconfig.json`, no errors
  - `npm run lint` → `> eslint . --max-warnings 0`, no errors
  - `npm run build` → `✓ built in 213ms`
  - `node scripts/check-bundle-size.mjs` → last line `useBreakpoint-DEBbg83l.js   460  ok`
  - `npm run test:coverage` → initially 3044 of 3045 tests pass; the only failure was the spec-set tripwire, now fixed in repair attempt 1 (re-run: 3045/3045 pass, 100% coverage). Playwright specs are excluded with `--exclude 'e2e/**'`.

## Coverage

None — test-only change. No production line, branch, method or class was added or changed, so there is no production branch-to-test map and no coverage runner measures Playwright specs. `apps/staff-web/package.json:17` runs Vitest with `--exclude 'e2e/**'`; `vite.config.ts:22-42` measures only `src/**/*.{ts,tsx}` at 100% per-file thresholds. Those thresholds were not changed and no exclusion or suppression was added.

Initial result (tool, first worker): `cd apps/staff-web && npm run test:coverage` → `Test Files  1 failed | 267 passed (268)` and `Tests  1 failed | 3044 passed (3045)`; the only failure was the spec-set tripwire at `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:166`. Because that run failed, Vitest did not emit the coverage-summary row; no test was excluded and no threshold lowered to obtain one. Final result after repair attempt 1 (tool): the same command passes `268 passed (268)` files and `3045 passed (3045)` tests with `Statements 100% (5107/5107)`, `Branches 100% (2634/2634)`, `Functions 100% (1697/1697)`, `Lines 100% (4903/4903)`. The 100% source gate itself is unchanged, and `python3 -I scripts/check-constraints.py` exits 0 (no new suppression, exclusion, skip or lowered threshold).

Supporting evidence (tool):
- Scope proof: `git status --short` lists `docs/pr/notes/RESP-E2E-FEATURE-CHART-20261124-004449.md`, `apps/staff-web/e2e/responsive-chart.spec.ts` and (repair attempt 1) `apps/staff-web/tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts`; no production, tracker, script, config or workflow file changed.
- Spec branches and the runs that take them: `words.locale === 'ar'` is taken by the Arabic test and its false side by the English test; `hasTouch` is true in the six phone/tablet Chromium projects and false in `chromium`, 1280 and 1920; `band === 'compact'` is taken by 360/390 phones and its false sides by 768–1180 tablets (medium) and 1280/1920 (expanded); `reset = true` and `reset = false` are both taken by the Tab-walk helper. Every branch is exercised in a test that asserts status text, recorded API commands, SOAP values, direction, overflow or axe findings.
- Coverage commands and results: `npx eslint e2e/responsive-chart.spec.ts --max-warnings=0` → 0 findings; `npx tsc --noEmit -p tsconfig.lint.json` → 0 errors; `npx playwright test --config e2e/playwright.config.ts e2e/responsive-chart.spec.ts --list` → 50 tests in 1 file; the real run → 20 passed, 30 fixme-skipped, 0 failed.
- Dependency/static analysis (devsecops): OWASP Dependency-Check and SpotBugs are Maven gates for `apps/api`; this change adds no Java source, Maven dependency or library version, so they are not applicable rather than suppressed. Recorded static gates are ESLint 9.39.5 (0 findings), TypeScript 5.7.3 (0 errors) and the staff-web check.sh stages above.

## Clinical and architecture review

No signed data, cross-organization sharing, patient association change, prescribing or critical result is touched. The note journey is a synthetic simulation; sign is exercised against a stub and is not clinical or human acceptance. Clinical sign-off for live enablement remains a separate gate.

## Review verdicts

- aurevia-plan-reviewer: GO (after all Required findings were addressed; re-review of the revised plan)
- aurevia-test-engineer: GO (conditional on seven Required test-design changes, all applied)
- aurevia-coverage-auditor: GO
- aurevia-devsecops: GO
- aurevia-senior-typescript: GO
- aurevia-code-reviewer: GO (after notes sections and comment fixes; re-review)
- aurevia-senior-react: GO (after Tab-reachability and attestation-value fixes; re-review)

## Findings

- **Harness gap (defect of missing infrastructure, not of production code):** no browser harness in `e2e/` mounts `/patients/:id/chart`, `ChartScreen`, `DocumentList` or `RevisionHistory`; only `clinicalnote-harness.html` mounts a chart-feature screen. The production shell role is `reception` (`src/app/useRole.ts:10`, `src/fixtures/synthetic.ts`), so the clinician-only chart route renders `ForbiddenPage`. Three `test.fixme` entries record this; a task request for `e2e/chart/chart-harness.html` + `chart-harness.tsx` + a non-empty synthetic chart API is filed below. The next worker must not describe those screens as browser-verified.
- **Spec-set tripwire (resolved in repair attempt 1):** `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:86,166` pinned `currentSpecs` to 9 e2e spec paths while the checkout had 15. The pin was refreshed to the exact committed set in the repair; the coverage gate is now green at 100%. The earlier task request for this tripwire is withdrawn.
- **Fixture/stub literal duplication (informational):** `syntheticAlphaChart.revisions[0].content.subjective` and `freshRevision().content` in `e2e/clinicalnote/synthetic-note-api.ts:32` currently hold the same synthetic string. The spec derives its expectation from the fixture, so a drift in either side fails the assertion; no stub edit was in scope.
- **Coverage scope:** the existing jsdom files under `apps/staff-web/tests/responsive/features-chart/*.responsive.test.tsx` cover chart/table/document/revision structure and logical styles, but they cannot paint pixels. They are complementary evidence, not browser proof of the fixme screens.

## Lessons for workers

- Read the repo's spec-set and artifact pins before adding a new e2e spec: a `GAP` tripwire can make an otherwise green `check.sh` fail, and proving it pre-existing with an A/B run is faster than arguing about it.
- In a cloud worker, `npx playwright install chromium` installs the binary but the host may still lack shared libraries (`libglib-2.0.so.0`); `npx playwright install-deps chromium` as root fixed it here. Record the environment step, then run the real suite instead of falling back to `--list` alone.
- Playwright `locator.press('Enter')` focuses programmatically; to prove keyboard *reachability*, assert each Tab stop with `toBeFocused()` and activate from that focus.
- A confirmation that focuses its Cancel action needs `Shift+Tab`, not `Tab`, to reach a sign action that precedes Cancel in DOM order — verify the DOM order before writing the walk.
- Derive expected responsive bands from the viewport and the production breakpoint resolver, not from one hard-coded band: 844x390 is medium, not compact.

## Coverage repair — attempt 1 (tool evidence)

The coverage gate (`npm run test:coverage` in `apps/staff-web`) failed on one test: the GAP tripwire at `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:166` asserted `e2eSpecs()` equals `currentSpecs`, but the pin held 9 paths while the tree has 15. Root cause: later commits added six e2e specs (`adminflow/admin-journey.spec.ts`, `financeflow/finance-journey.spec.ts`, `pharmacyflow/pharmacy-bilingual-journey.spec.ts`, `radflow/radiology-bilingual-journey.spec.ts`, `therapyflow/therapy-journey.spec.ts`, and this story's `responsive-chart.spec.ts`) without refreshing the pin. The file's own contract (line 85: "a new journey must replace this pin") made the pin update the correct fix. No threshold, exclusion, skip or suppression was added or lowered.

### Branch-to-test list

- Production branches added or changed: **none**. The repair touches only a static expected-value array in a Vitest artifact tripwire; no production line, branch, method or class is in scope for this story (test-only change).
- `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts` — the `GAP: all-role, staging-RC and light/dark visual evidence is not yet recorded` test (line 162) drives the `e2eSpecs()` vs `currentSpecs` equality at line 166; updating the pin to the exact 15 committed spec paths makes the sorted comparison pass. Sibling assertions were re-evaluated and stay green: the workflow pin (line 171) passes because no `.github/workflows` file references playwright/e2e, and the visual-vocabulary pin (line 178) passes because no committed spec uses `toHaveScreenshot`, `toMatchSnapshot`, `getComputedStyle`, `data-theme` or `colorScheme`. The compile-time `Role` union (line 180) is unchanged.
- `e2e/responsive-chart.spec.ts` branches (unchanged, restated from `## Coverage`): `words.locale === 'ar'` → Arabic test, false side → English test; `hasTouch` → six phone/tablet projects, false side → chromium/1280/1920; `band === 'compact'` → 360/390 phones, false sides → 768–1180 medium and 1280/1920 expanded; `reset = true` and `reset = false` → Tab-walk helper. Each branch is asserted via status text, recorded API commands, SOAP values, direction, overflow or axe findings.

### Evidence (all from executed tools; no reviewer or simulated-user evidence for this repair)

- `npx vitest run tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts` → `Test Files  1 passed (1)`, `Tests  3 passed (3)`.
- First full `npm run test:coverage` after the pin fix failed once on an unrelated load flake at `tests/spec/platform/resilient-screens.test.ts:250` (`expected true to be false` on `beforeunload.defaultPrevented`); that file passes alone (`Tests  15 passed (15)`) and in the re-run.
- Re-run of full `npm run test:coverage` → `Test Files  268 passed (268)`, `Tests  3045 passed (3045)`, `No files with missing coverage. 267 files fully covered. 8 empty files skipped.`, `Statements  100% (5107/5107)`, `Branches  100% (2634/2634)`, `Functions  100% (1697/1697)`, `Lines  100% (4903/4903)`.
- `python3 -I scripts/check-constraints.py` (repo root) → exit 0; no new suppression, exclusion, skip or lowered threshold.

## Why this is not finished

1. Three chart-feature screens — longitudinal chart, document list and revision history — are not browser-verified. No harness mounts them and the shell role is `reception`, so a real-browser assertion would either hit `ForbiddenPage` or test an empty state. They are `test.fixme` with exact reasons; the chart harness is requested below. The note screen is fully covered.
2. The spec-set tripwire is resolved (see `## Coverage repair — attempt 1`): the pin now matches all 15 committed e2e specs and the full coverage gate is green at 100%. `bash apps/staff-web/check.sh` was not re-run end to end in this repair; its Vitest stage (`npm run test:coverage`) and every other stage previously passed individually (see `## Test evidence`). The earlier task request for this tripwire is withdrawn.
3. This story is therefore not accepted and should not be marked done until the chart harness task lands and the gates are rerun.

```task-requests
[
  {
    "title": "Chart browser harness for responsive chart end-to-end coverage",
    "details": "Add a dev-only e2e harness that mounts ChartPage/ChartScreen with the real providers, a synthetic clinician role, non-empty chart entries, documents and revisions, plus an EN/AR locale toggle. This unblocks the three fixme screens in e2e/responsive-chart.spec.ts and proves WEB-07/UX-01 for the whole chart feature, not just NotePage.",
    "scope": [
      "apps/staff-web/e2e/chart/chart-harness.html",
      "apps/staff-web/e2e/chart/chart-harness.tsx",
      "apps/staff-web/e2e/chart/synthetic-chart-api.ts"
    ],
    "depends": ["RESP-E2E-FEATURE-CHART"]
  }
]
```
