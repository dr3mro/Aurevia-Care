# RESP-E2E-FEATURE-PATIENTS — responsive patients browser journey

## Summary

Added one new Playwright spec, `apps/staff-web/e2e/responsive-patients.spec.ts`, that proves in a real browser that patient registration and search work at phone, tablet and desktop widths in English and Arabic RTL, with the keyboard and with the device's primary pointer. It reuses the existing reception harness and synthetic reception API; no production source, harness, config, dependency or tracker was changed. Patient overview is explicitly **not** proven: it is unreachable in this single-file scope and is recorded as `test.fixme` plus a task request.

## Affected requirements

- WEB-07 — supported clients: real-browser device matrix at 390 px phone, 820 px tablet and 1280 px laptop (plus the whole installed matrix), asserting the `data-breakpoint` compact/medium/expanded band and no horizontal scroll of the patients screen.
- UX-01 — English and Arabic RTL: asserts `html[dir]`/`html[lang]` after the harness locale toggle, verbatim en/ar strings, localized result values, mixed-script isolation, and the shared serious/critical axe gate on every journey.

## Risk class

Cross-boundary verification only (browser → real patients components → stubbed versioned API). No production change, migration or contract change; risk is contained to a new test artifact.

## Review owner

Senior React and Vite; senior TypeScript; test engineer; code reviewer; coverage auditor; devsecops.

## Data changes

None. No migration, backfill, lock or volume impact. Synthetic fixtures only: `Synthetic New Patient`, `MRN-9001`, `SYNTHETIC`, `1992-08-03`, `en`. The synthetic helper mints opaque `patient-syn-*` ids and provides the XSRF cookie; no real identifier or credential appears in the spec.

## Authorization boundaries and audit events

Unchanged. The harness keeps its fixed `reception` role and the helper stubs only `/api/v1/patients*` and `/api/v1/appointments*`. The spec asserts `POST /api/v1/patients` reached that stub; no role, tenant or audit behavior was altered.

## Migration and rollout considerations

None. No Flyway migration, OpenAPI entry, generated client, wiring or deployment file changed.

## Test evidence

All commands were run from the repository checkout. Evidence is from executed tool commands and read-only specialist reviews; no human or clinical participant was involved.

- `npm run e2e -- --config e2e/playwright.config.ts --project=phone-portrait-390x844 --project=tablet-portrait-820x1180 --project=laptop-1280x720 responsive-patients` → `12 passed (15.2s)` on the final re-run, `3 skipped` (the three overview `test.fixme` instances). Entry-point trace: harness `/e2e/reception/reception-harness.html` → `ReceptionWorkstation` → real `PatientsPage` → `PatientsScreen`/`RegisterPatientForm` → stubbed `POST /api/v1/patients` → asserted registration status and search result cells.
- Full installed Chromium matrix, `npm run e2e -- --config e2e/playwright.config.ts responsive-patients` → `40 passed (43.9s)`, `10 skipped` (overview fixme only). Covers 360/390 phone, 844x390 landscape phone, 768/820/1024/1180 tablets, 1280 laptop, 1920 desktop and the base `chromium` project. WebKit/Firefox binaries are not installed in this worker image, so those cross-browser projects are omitted by the config rather than failed.
- `bash apps/staff-web/check.sh` → **fails at an existing tripwire**, final lines: `Test Files  1 failed | 267 passed (268)`, `Tests  1 failed | 3044 passed (3045)`. The earlier stages (generated-client check, typecheck, lint, build, bundle-size gate) all passed; `set -e` stops at `npm run test:coverage`. Failing test: `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:166`, which pins the exact e2e spec set to 9 files.
- Pre-existing proof: the same tripwire was run with the new spec temporarily removed → `Test Files  1 failed (1)`, `Tests  1 failed | 2 passed (3)`, received 14 specs vs pinned 9. The pin was already red on the base tree because five other specs had been added by other work; this story adds a 15th. The pin file is outside the task scope and was not edited.
- `npx vitest run --coverage --exclude 'e2e/**' --exclude 'tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts'` → `Test Files  267 passed (267)`, `Tests  3042 passed (3042)`, `No files with missing coverage. 267 files fully covered.`
- `npm run typecheck` (`tsc --noEmit -p tsconfig.json`) → exit 0. Note: `tsconfig.json` excludes `e2e/`, so this does not typecheck the new spec.
- `npx tsc --noEmit -p tsconfig.lint.json` (includes `e2e/`) → exit 0.
- `npm run lint` → exit 0 (from `check.sh`); `npm run build` and `node scripts/check-bundle-size.mjs` passed (from `check.sh`).
- `python3 -I scripts/check-constraints.py --base 29467f4782ded5181bffac69b879b0c1b9fe8daf --require-base` → exit 0 (no added suppression, skip, exclusion or weakened threshold).

## Coverage

No production file changed, so production coverage is "none touched"; the whole hand-written staff-web source remains at 100% (statements 5107/5107, branches 2634/2634, functions 1697/1697, lines 4903/4903). E2E specs are excluded from Vitest coverage by `package.json` (`--exclude 'e2e/**'`), so the replacement evidence is the executed browser matrix plus the assertion map below. Coverage command and result are recorded under Test evidence.

Each assertion is exercised by all four journey tests (`keyboard`/`pointer` × `en`/`ar`) on every project:

| Assertion | Covering test(s) |
|---|---|
| `html[dir]` and `html[lang]` after the harness `العربية` toggle | all four journey tests, via `openHarness` |
| `[data-part=patients-page][data-breakpoint]` equals the viewport band | all four, via `register` |
| Toolbar `data-layout` stacked/side-by-side | all four, via `register` |
| Registration dialog visible; `data-coarse-pointer` equals the browser's `(pointer: coarse)` match; three sections with `data-layout` single-column/two-column | all four, via `register` |
| Five registration fields entered and values asserted; submit by Enter (keyboard) or by tap/click (pointer); `POST /api/v1/patients` recorded once; status text and dialog close | two keyboard tests / two pointer tests, via `register` |
| Search field entered by focus+type or tap+type; submit by Enter or tap/click | two keyboard / two pointer tests, via `search` |
| `[data-part=results]` `data-layout` scroll-region/table; table caption and the four localized column headers | all four, via `search` |
| Registered name, MRN, issuer and ISO year in the result row | all four, via `search` |
| Every clinical cell box lies inside the viewport | all four, via `search` (desktop-to-phone value reachability) |
| Compact region has `role=region`, aria-label from the caption, `tabindex=0`, is reached by Tab from the submit button, hides no offset and stays focused after `End` | all four on compact projects, via `search` |
| No horizontal scroll of the patients page (compact) / document (medium and expanded) | all four, via `expectNoHorizontalScroll` |
| Zero serious/critical axe violations in the finished locale | all four, via `expectNoBlockingViolations` |
| Overview unreachable — one `test.fixme` with reason | one per project (10 in the full matrix) |

## Clinical and architecture review

Simulated, synthetic browser evidence only (WEB-10). No clinician, Arabic-speaking unit reviewer or human participant reviewed these flows. The overview screen is not covered. Real-patient acceptance remains a separate gate.

## Review verdicts

- smartclinic-plan-reviewer: GO
- smartclinic-senior-react: GO
- smartclinic-senior-typescript: GO
- smartclinic-code-reviewer: GO
- smartclinic-coverage-auditor: GO
- smartclinic-devsecops: GO

All six reviewers reported no Critical or Required finding after the plan gaps and Optional wording/consistency notes were addressed in the spec. The reviewers are read-only specialists; their evidence is their file reads, not independently re-run commands.

## Findings

1. **Pre-existing gate failure (tool evidence).** `bash apps/staff-web/check.sh` is red at `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:166`: the `currentSpecs` pin holds 9 spec paths while the tree contains 14 before this story and 15 after. The test comment says "a new journey must replace this pin". The file is outside the task scope, so it was not edited. A task request asks the owner to refresh the pin.
2. **Pre-existing reception journey failure (tool evidence).** While verifying the browser environment, `npm run e2e -- --project=chromium e2e/reception/reception-journey.spec.ts --retries=0` produced `2 failed`: the seeded-row check-in assertion expects a disabled button that no longer exists after reload, and the Arabic toggle `getByRole('button', { name: 'العربية' })` resolves to two elements (the harness toggle and a scheduling language toggle). This story's spec scopes the toggle to the harness's direct child button and does not depend on check-in. Not fixed: outside scope.
3. **Compact scheduling overflow (tool evidence).** At 390 px the reception harness co-mounts `SchedulingPage`; the document measures `scrollWidth 640` vs `clientWidth 390`, and in RTL the fixed scheduling rail shifts patients controls to x≈574 and intercepts `tap()`. The horizontal-scroll assertion is scoped to `[data-part=patients-page]` on compact (document-level is kept on medium/expanded), and a local style tag hides `[data-scheduling-page]` so touch is measured on the patients screen. No patients assertion was weakened; the scheduling defect is recorded for a separate task.
4. **Compact table does not need inline scrolling (tool evidence).** The labelled region's clinical cells wrap (`overflow-wrap: anywhere`), so `scrollWidth <= clientWidth` inside the region; forcing `scrollWidth > clientWidth` was impossible without a production change. Reachability is instead proven by role/label/tabIndex, Tab focus and every column box being inside the viewport.
5. **Environment (tool evidence).** Playwright Chromium and its OS libraries were installed in the worker for this run (`npx playwright install chromium`, `npx playwright install-deps chromium`); neither changed tracked files. WebKit and Firefox are not installed, so the config omits those variants locally.

## Lessons for workers

- A top-level `test.fixme('title')` with no body marks the **whole file** as fixme and silently skips every test. Give it a body function so only that one case is skipped.
- `apps/staff-web/tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts` pins the exact e2e spec set: every new `.spec.ts` needs a coordinated pin change, which is outside a single-file worker scope — ask for it in `task-requests`.
- `tsconfig.json` excludes `e2e/`; typecheck a new spec with `npx tsc --noEmit -p tsconfig.lint.json`, which includes it.
- The reception harness mounts `SchedulingPage` too: at phone widths its rail widens the document and, under RTL, covers patients controls. Scope layout assertions to the screen under test and isolate co-mounted chrome explicitly rather than forcing taps.

## Why this is not finished

- **Patient overview is not proven.** No dev harness mounts `PatientOverviewPage`, and `src/app/navigation.ts` denies `/patients/:id` to the harness's fixed `reception` role. It is one documented `test.fixme` per project and a task request asks for a clinician-role harness. No overview assertion was weakened or claimed as passing.
- **`bash apps/staff-web/check.sh` is red** solely at the pre-existing spec-set tripwire described in Findings 1; the new spec cannot be added without tripping it, and the pin file is outside scope. All other check stages pass, and a coverage run excluding that one test is 100%.
- **Cross-browser engines not observed locally.** WebKit/Firefox binaries are absent in this worker image; the config omits them. Only Chromium projects were executed; CI must provide the WebKit/Firefox evidence.

## task-requests

```json
[
  {
    "title": "Add a clinician-role harness for the patient overview responsive journey",
    "details": "RESP-E2E-FEATURE-PATIENTS left the overview screen as a documented test.fixme: no dev harness mounts PatientOverviewPage and navigation.ts denies /patients/:id to the fixed reception role. Provide a dev-only harness/role capability so overview can be exercised at phone, tablet and desktop in en and ar, then replace the fixme with real assertions.",
    "scope": ["apps/staff-web/e2e/**", "apps/staff-web/src/app/**"],
    "depends": ["RESP-E2E-FEATURE-PATIENTS"]
  },
  {
    "title": "Refresh the WEB-10 e2e spec-set tripwire pin",
    "details": "tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:166 pins currentSpecs to 9 spec files while the tree had 14 before this story and 15 after. Update the pin to the actual committed spec set and reassess the clause-note GAP assertions so check.sh is green again.",
    "scope": ["apps/staff-web/tests/spec/platform/**"],
    "depends": ["RESP-E2E-FEATURE-PATIENTS"]
  },
  {
    "title": "Fix compact viewport overflow of the scheduling worklist rail",
    "details": "At 390 px the co-mounted SchedulingPage widens the document to 640 px; under RTL its fixed rail shifts patients controls off-screen and intercepts taps. Fix the scheduling compact layout so the full harness has no horizontal scroll and no RTL interception, then remove the test-side isolation and page-scoped scroll assertion in responsive-patients.spec.ts.",
    "scope": ["apps/staff-web/src/features/scheduling/**", "apps/staff-web/e2e/responsive-patients.spec.ts"],
    "depends": ["RESP-E2E-FEATURE-PATIENTS"]
  }
]
```
