# RESP-E2E-FEATURE-SCHEDULING — staff web responsive scheduling e2e

- Story: RESP-E2E-FEATURE-SCHEDULING
- Scope: `apps/staff-web/e2e/responsive-scheduling.spec.ts` (new file only)
- Branch: `swarm/RESP-E2E-FEATURE-SCHEDULING-1361`, base `origin/main` at `29467f47`
- Evidence sources: executed tool commands (typecheck, lint, Playwright, check.sh, constraint scanner); read-only specialist reviews; live harness DOM measurements via Playwright. No human, clinical or real-patient evidence. All fixtures synthetic.

## Summary

Adds one Playwright acceptance spec that drives the real scheduling screens mounted by the dev-only reception harness across the full configured browser matrix. For each project it walks English and Arabic RTL, performs the main task of the booking form and the appointment worklist by keyboard and (on coarse-pointer projects) by touch, asserts no horizontal scroll, and asserts that every visible desktop row value is reachable in the phone compact card layout. No production source, harness, config or dependency changed.

## Affected requirements

WEB-07, UX-01, WEB-10.

| Requirement | Acceptance | Proof in the spec |
|---|---|---|
| WEB-07 | Staff web usable at phone, tablet and desktop widths in supported browsers | Every test is generated for all 10 configured projects (base Chromium + 9 device classes; WebKit/Firefox are omitted by the config when their binaries are absent). Each test walks EN and AR. |
| UX-01 | English and Arabic RTL, no loss of clinical values | `html` `dir`/`lang` asserted per locale; compact `[data-layout="cards"]` region; every visible desktop row cell value (time, patient, MRN, age, visit, clinician/room, state, note, assignee, action) and the `time` `datetime` attribute compared against the phone row, then scrolled into view and asserted visible. |
| WEB-10 | Real-browser synthetic journey with zero serious/critical axe violations | `expectNoBlockingViolations` from `./accessibility` on each locale pass; synthetic `installSyntheticReceptionApi`; POST commands gated so the optimistic status can be asserted deterministically; durable reloaded rows asserted. |
| Story | Keyboard and touch main task on each scheduling screen | Booking form: Tab through five fields then Enter; tap variant on `hasTouch` projects. Worklist: range/filter chips and row check-in by Enter, tap variant on `hasTouch` projects. Both in EN and AR. |

## Risk class

Routine (test-only addition). One new browser-level acceptance spec; no production, contract, identity or deployment boundary is changed. The only production concern it reveals is an out-of-scope layout defect documented under Findings, routed to a follow-up task.

## Review owner

Senior React and Vite specialist for the staff web; read-only reviews by smartclinic-plan-reviewer (before coding), smartclinic-code-reviewer, smartclinic-coverage-auditor and smartclinic-devsecops (after coding). Verdicts are recorded under `## Review verdicts`.

## Data changes

None. No migration, backfill, table, policy, lock or volume impact, rollback or forward-fix. Synthetic fixtures only: `patient-seed`, `patient-book`, `MAIN`, `clinician-7`, `Synthetic intake`, and a synthetic CSRF cookie supplied by the existing `installSyntheticReceptionApi` helper.

## Authorization boundaries and audit events

None. No tenant, role, patient-link or audit behavior is added or changed. The spec only intercepts the already-stubbed `/api/v1/appointments/**` and `/api/v1/patients*` browser boundaries; the temporary POST gate holds those synthetic requests and releases them in `finally`.

## Migration and rollout considerations

None. No schema, route, OpenAPI contract, generated client, feature flag or deployment artifact changes. The new file is a dev/test-only Playwright spec excluded from the production build.

## Test evidence

Commands were run from `apps/staff-web` unless stated. `env -u CI` was needed locally only because a throwaway `npm run dev` server was already bound to 127.0.0.1:5173 and the config sets `reuseExistingServer: !isSet('CI')`; CI has no such server.

Entry point to test trace: `page.goto('/e2e/reception/reception-harness.html')` → `e2e/reception/reception-harness.tsx` mounts the real `SchedulingPage(anchorCivilDate)` → `SchedulingScreen` → `BookingForm`/`AppointmentWorklist` → `SchedulingPage.handleBook`/`handleCheckIn` → `api.book`/`api.checkIn` → stubbed `POST /api/v1/appointments` and `POST /api/v1/appointments/{id}/check-in` → reloaded `AppointmentWorklist` row. The assertions cover each arrow.

1. `npx tsc --noEmit -p tsconfig.lint.json` — final line: exit 0, no output. This is the command that actually typechecks `e2e/**` (tsconfig.json used by `npm run typecheck` excludes e2e).
2. `npx eslint e2e/responsive-scheduling.spec.ts --max-warnings 0` — final line: exit 0, no output (strictTypeChecked, zero warnings).
3. `npm run typecheck` — final lines:
   ```
   > smartclinic-staff-web@0.1.0 typecheck
   > tsc --noEmit -p tsconfig.json
   ```
   exit 0 (tsc prints nothing on success).
4. `env -u CI npx playwright test --config e2e/playwright.config.ts e2e/responsive-scheduling.spec.ts --reporter=line` — final lines:
   ```
   6 skipped
   44 passed (56.9s)
   ```
   (6 skipped = 3 known-defect fixme tests on each of the two compact phone projects; see Why this is not finished.) Per-project runs first: `laptop-1280x720` `5 passed (8.1s)`; `phone-portrait-360x740` `3 skipped, 2 passed (7.5s)`; remaining eight projects in one run `3 skipped, 37 passed (46.8s)`. All exit 0.
5. `cd /__w/Aurevia-Care/Aurevia-Care && bash apps/staff-web/check.sh` — final lines:
   ```
   Test Files  1 failed | 267 passed (268)
   Tests       1 failed | 3044 passed (3045)
   ```
   exit 1. Every stage before Vitest passed: `gen:api:check`, `npm run typecheck`, `npm run lint`, `npm run build`, bundle-size gate. The only failure is the pre-existing WEB-10 spec-set tripwire in `apps/staff-web/tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:166`: it pins 9 e2e specs while 14 were already committed on main before this change (15 with the new spec). Its own comment says "a new journey must replace this pin". The file is outside this task's scope. The same failure was reproduced at baseline before the new spec existed (`1 failed | 3044 passed`, 14 vs 9).
6. `python3 -I scripts/check-constraints.py` (repo root) — exit 0, no findings; no threshold, exclusion, skip or suppression added.
7. `npx playwright install chromium` and `npx playwright install-deps chromium` were run once to make the local image capable of launching the browser; these change only the tool cache/OS packages, not the repository.
8. OWASP Dependency-Check and SpotBugs are not applicable: this is a test-only Playwright spec with no dependency change and no Java source or compiled bytecode. The dependency set is identical to the reviewed baseline (no new CVE surface) and SpotBugs analyses `apps/api` classes, a module this change does not touch. No false claim of running them is made; `check-constraints.py` exit 0 confirms no gate was weakened.
9. No CI SHA is linked because this worker does not push or call `gh`; the trusted integrator publishes the commit and attaches the SHA-linked Delivery verdict.

## Coverage

No production source, harness, config, dependency or migration changed — the only repository change is the new Playwright spec and this notes file. The staff-web Vitest coverage scope (`--exclude 'e2e/**'`) and all its thresholds are untouched; module source coverage is therefore unchanged. The coverage run inside `check.sh` executed and the only failing test was the pre-existing spec-set tripwire (3044 passed). Playwright specs are not part of the Vitest coverage instrument and are validated by execution across the matrix instead.

Branch-to-test map for the new spec (each branch is exercised by a real browser assertion, not merely reached):

| Branch | Test / assertion |
|---|---|
| locale `en` vs `ar` toggle in `openHarness` | `if (words.locale === 'ar')`; every test walks both locales and asserts `html` `dir` and `lang`. |
| compact (`width < 768`) vs non-compact table | `runWorklistPass` asserts `data-layout` `cards`/`table`; compact project also focuses the `tabindex=0` region. Phone landscape 844 is medium/table and asserts the table branch. |
| `hasTouch === true` vs false (no `.skip`) | modes array includes `tap` only on coarse projects; every project still runs the keyboard path. Verified by per-project runs: touch tablets and phone taps passed, laptop/desktop never call `tap()`. |
| `mode === 'keyboard'`/`'tap'` in `activate` | keyboard focuses and presses Enter; tap uses `locator.tap()`. Durable POST and reloaded row asserted in both. |
| negative empty submit | `if (mode === 'keyboard')` in `runBookingPass`: empty Book submit asserts no POST and empty status. |
| optimistic status hold/release | `holdPostGate` `if (POST)` holds the command while status is asserted; `finally` releases; after release the status is empty and the reloaded row shows the new state. |
| waiting filter 0 → 1 rows and needs-check-in/all | `runWorklistPass` asserts `aria-pressed`, `tbody tr` count and the no-appointments empty state; after check-in the row becomes visible under All with the checked-in state and a disabled Hand off action. |
| parity cell loop and empty guard | `collectRowValues` skips empty cells and asserts at least one value; the phone loop asserts identical text, `scrollIntoViewIfNeeded()` and visibility for every value. |
| known-defect fixmes | `test.fixme(true, reason)` only on compact widths, with an explicit reason; no project or locale is skipped and no assertion is weakened. |

Honest limits of the evidence: the axe scan runs on each locale's initial harness state, not after the optimistic status or checked-in row transitions; on compact phone projects the keyboard assertions do execute before the tap `test.fixme` aborts the test, but Playwright reports that whole test as skipped; and the two compact phone widths have no passing no-horizontal-scroll evidence at all until the production fix lands. None of these are claimed as passing evidence.

## Clinical and architecture review

None required for this change: it is a synthetic browser-acceptance test of presentation and interaction, with no signed data, cross-organization sharing, patient association, prescribing or critical result, and no production behavior change. Real clinical, Arabic-speaking staff acceptance remains a separate G5 launch gate and is not claimed here.

## Findings

- **Production layout defect (outside scope):** at widths below 768 the reception navigation renders as a full-width top bar. `ReceptionNav` sets `inlineSize: '100%'` and `flexShrink: 0` while `SchedulingScreen` hardcodes `flexDirection: 'row'`. Measured from the live harness: at a 360 px viewport `document.documentElement.scrollWidth` is 610 with `clientWidth` 360; at 768 px and above there is no overflow. On `isMobile` phone projects the same overlay intercepts `locator.tap()` on the Book button and the row action. The three affected assertions are marked `test.fixme` with the reason; they are not weakened or skipped per project. A production fix belongs to a separate task.
- **Pre-existing `check.sh` red:** the WEB-10 spec-set tripwire pins 9 e2e specs while main already had 14. Adding this spec makes it 15. The test's own comment instructs the maintainer to replace the pin. This is unrelated to the new spec's correctness and is outside scope.
- **Pre-existing red in `e2e/reception/reception-journey.spec.ts` (not edited):** its Arabic step uses `getByRole('button', { name: 'العربية' })` which now resolves to two buttons (harness toggle and nav language chip), and its English step looks for a disabled `Check in` button after the row has legitimately become `Hand off`. Both fail on main before this change. The new spec uses the scoped selector `#reception-harness > button` and asserts the post-check-in `Hand off` state instead.
- **Environment note:** Playwright browser binaries and OS libraries were absent; installed with `npx playwright install chromium` and `npx playwright install-deps chromium`. WebKit/Firefox remain absent, so the config omits their projects; the suite still exercised all 10 Chromium projects.

## Lessons for workers

- A dev harness that mounts two production pages can hide a layout defect that a single-page mount would not: measure `documentElement.scrollWidth` against `clientWidth` at 360 px before assuming a responsive e2e will be green.
- In this harness the locale toggle must be selected with `#reception-harness > button`; the navigation rail contains an identically named language button, so `getByRole('button', { name: 'العربية' })` is ambiguous.
- An empty `role="status"` paragraph has a zero-size box: assert `toBeAttached()`, not `toBeVisible()`, for the initial empty status line.
- Scope row counts to `tbody tr`; the `<thead>` row otherwise makes an empty worklist look like two rows.
- For an optimistic status that a reload clears, hold the POST with a route handler registered after the synthetic API and call `route.fallback()`; assert while held, release in `finally`, then assert the durable reloaded row.
- `browser.newContext()` does not inherit the Playwright `baseURL` fixture; pass it conditionally to satisfy `exactOptionalPropertyTypes`, and close manually created contexts in `finally`.

## Review verdicts

- smartclinic-code-reviewer: GO
- smartclinic-coverage-auditor: GO
- smartclinic-devsecops: GO

## Why this is not finished

The one repository file is implemented and its Playwright matrix is green (44 passed, 6 documented fixmes). Two items prevent an unqualified "done":

1. `bash apps/staff-web/check.sh` exits 1 solely because of the pre-existing WEB-10 e2e spec-set tripwire (`tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:166`, expected 9 specs, received 15). It was already red at baseline (14 vs 9). The pin lives outside this task's scope. Next step: the maintainer or a following task updates `currentSpecs` to include all committed specs, then reruns check.sh.
2. Three assertions per compact phone project are legitimately `test.fixme` because of the production nav/row layout defect described above; a production fix is required before WEB-07/UX-01 can be claimed green on phones. Next step: the task below fixes `SchedulingScreen`/`ReceptionNav`, then remove the three `test.fixme` guards.

```task-requests
[
  {
    "title": "Fix compact reception navigation overflow and pointer interception",
    "details": "At widths below 768 the reception top bar keeps inlineSize 100% with flexShrink 0 while SchedulingScreen hardcodes flexDirection row. Measured scrollWidth 610 vs clientWidth 360 at 360px, and the nav intercepts pointer/tap activation of the booking submit and worklist row action on isMobile projects. Make the compact shell a single column with an off-canvas drawer per docs/design/responsive-layout.md, keep every clinical column present, and prove it in a browser. Then remove the three test.fixme guards in apps/staff-web/e2e/responsive-scheduling.spec.ts.",
    "scope": ["apps/staff-web/src/features/scheduling/ReceptionNav.tsx", "apps/staff-web/src/features/scheduling/SchedulingScreen.tsx"],
    "depends": []
  },
  {
    "title": "Refresh WEB-10 e2e spec-set tripwire after new journeys",
    "details": "tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts pins currentSpecs to 9 files but main already had 14 before RESP-E2E-FEATURE-SCHEDULING added a 15th (responsive-scheduling.spec.ts). check.sh therefore fails before and after this story. Replace the pin with the current committed spec set (and keep the tripwire active for future additions), then rerun bash apps/staff-web/check.sh.",
    "scope": ["apps/staff-web/tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts"],
    "depends": []
  }
]
```
