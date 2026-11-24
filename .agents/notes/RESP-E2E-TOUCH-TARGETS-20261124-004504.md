# RESP-E2E-TOUCH-TARGETS — staff-web coarse-pointer touch-target evidence

## Summary

Added one new Playwright spec, `apps/staff-web/e2e/touchTargets.spec.ts`, that measures every
eligible `button`, `a[href]`, `input` and `select` on 30 concrete staff-web routes at 44 × 44 CSS
px on the seven coarse-pointer (phone/tablet) projects of the existing device matrix (WEB-07,
UX-01, R5 of `docs/design/responsive-layout.md`). Fine-pointer projects are explicitly not
applicable to this rule. The spec generated a deterministic RED run on real product defects; each
measured defect is recorded as a scoped `test.fixme` with its exact (route, project) pair, and the
suite is GREEN without lowering 44, widening the exception list or skipping a flaky test. No
production source, configuration, dependency, script, workflow or tracker file changed.

## Affected requirements

- WEB-07 — supported clients / coarse-pointer target matrix (clause 5 and the 44 px practice in
  `docs/design/responsive-layout.md` §2 and §13).
- UX-01 — WCAG 2.2 AA target sizing (`size.target.comfortable`, §6 of the same guide).

## Risk class

Routine — a new end-to-end verification file only; no production behavior, clinical data,
authorization or contract changed.

## Review owner

aurevia-senior-react and aurevia-senior-typescript (staff web), aurevia-test-engineer,
aurevia-plan-reviewer before coding; aurevia-code-reviewer, aurevia-coverage-auditor
and aurevia-devsecops after the change.

## Data changes

None. No migration, table, RLS policy, backfill, lock, volume impact, rollback or forward fix.
Only synthetic identifiers `SYN-0001` and `SYN-NOTE-0001` and a synthetic `Chrome/130` user agent
appear in the spec.

## Authorization boundaries and audit events

None. No tenant, role, permission, patient-link, session or audit code was touched. The spec
changes only how a test browser is described to the existing support-policy function; it does not
change that function or any production behavior.

## Migration and rollout considerations

None. No deployable artifact changed.

## Test evidence

Evidence source: the executing tool for every command below. Reviewer statements are labelled
separately under `## Review verdicts`. No maintainer or human participant was available; no real
patient data, endpoint or credential was used.

Entry point to test trace:
`apps/staff-web/e2e/playwright.config.ts` (`deviceViewports`, `projectFor`, exported `projects`,
`webServer` that starts `npm run dev`) →
`apps/staff-web/e2e/touchTargets.spec.ts` (30 concrete routes; one test fanned out over the 10
installed projects; `main#main` readiness, settled-content check, pointer-parity guard, compact
user-menu open, measurement and separate axis assertions) →
`src/app/AppShell.tsx` / `src/app/NavigationDrawer.tsx` / `src/theme/responsive.css` (the rendered
controls and the `.touch` 44 px coarse rule that the spec measures).

Commands and final lines (cwd `apps/staff-web` unless stated):

- `npx playwright install chromium` → Chrome Headless Shell 159.0.8154.6 downloaded.
- `npx playwright install-deps chromium` → Debian 12 browser libraries installed (root/apt);
  the first smoke attempt failed with `libglib-2.0.so.0: cannot open shared object file`, which
  this resolved.
- Probe `node --input-type=module -e "import {chromium} from '@playwright/test'; ..."` →
  `hasTouch=false pointer:coarse=false`, `hasTouch=true pointer:coarse=true`; this is why the spec
  asserts the project's `hasTouch` agrees with `matchMedia('(pointer: coarse)').matches`.
- RED, single coarse project:
  `npx playwright test --config e2e/playwright.config.ts e2e/touchTargets.spec.ts --project=phone-portrait-390x844 --retries=0`
  → `3 failed`, `28 passed`; failures were `/lab/turnaround-board`, `/lab/results/entry` and
  `/operations/partner-board`.
- RED, full coarse matrix (same command with the seven phone-/tablet- `--project` flags,
  `--retries=0`) → `156 failed`, `61 passed (3.6m)`. Saved failure strings include:
  - `/ / phone-landscape-844x390: width below 44px` with
    `/admin/audit / phone-landscape-844x390 / #5 a[Today]: 8x17 < 44x44`;
  - `/lab/turnaround-board / phone-portrait-360x740: height below 44px` with
    `/lab/turnaround-board / phone-portrait-390x844 / #7 a: 292x32 < 44x44`;
  - `/lab/results/entry / phone-portrait-390x844 / #7 input#result-value-hemoglobin[Result value for Hemoglobin]: 96x32 < 44x44`.
  Both assertion arrays were observed failing: 150 `width below 44px` observations and
  6 `height below 44px` observations.
- GREEN, full matrix:
  `npx playwright test --config e2e/playwright.config.ts e2e/touchTargets.spec.ts --retries=0`
  → `Running 310 tests using 2 workers`, `246 skipped`, `64 passed (1.5m)`, exit 0. The 246
  skipped are 156 scoped real-defect fixmes plus 90 not-applicable fine-pointer executions
  (3 fine projects × 30 routes); 64 passed are 54 passing coarse route executions plus 10 route
  matrix assertions (30 routes × 7 coarse projects = 210 coarse route targets: 156 fixmed,
  54 passed; plus 10 matrix assertions).
- Discovery: `npx playwright test --config e2e/playwright.config.ts e2e/touchTargets.spec.ts --list`
  → `Total: 310 tests in 1 file` (31 declared tests × 10 installed projects; WebKit and Firefox
  projects are omitted by `crossBrowserProjects()` because those binaries are not installed).
- Typecheck (includes the spec, unlike `check.sh`): `npx tsc --noEmit -p tsconfig.lint.json` →
  exit 0, no output.
- Lint: `npx eslint e2e/touchTargets.spec.ts --max-warnings=0` → exit 0, no output.
- Full module gate, from the repo root: `bash apps/staff-web/check.sh` → exit **0** after the
  repair below. All stages green: `gen:api: generated client matches the contract`, `tsc --noEmit`,
  `eslint .`, `vite build`, `node scripts/check-bundle-size.mjs`, then Vitest reported
  `Test Files  268 passed (268)` and `Tests  3045 passed (3045)` with 100% coverage.
- Baseline diagnosis (repair attempt 1): with `e2e/touchTargets.spec.ts` moved aside,
  `npx vitest run tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts`
  → exit 1, expecting 9 e2e specs but finding 14 already on `origin/main`; with the new spec the
  tripwire saw 15. The tripwire's own contract ("a new journey must replace this pin") assigns the
  pin update to whoever adds a spec, so this task updated it rather than leaving the gate red.

## Repair attempt 1 (coverage gate) — resolved

Evidence source: the executing tool for every command below; no reviewer or human input in this repair.

- **Failure:** `bash scripts/coverage-check.sh origin/main` → exit 1. Tail showed
  `FAIL tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts >
  WEB-10 clause 2 … > GAP: …` at line 166, `AssertionError: expected [ …(15) ] to deeply equal
  [ …(9) ]`. The failure is a stale expected-value pin, not an uncovered production line or
  branch: `currentSpecs` listed 9 e2e specs while the real committed set is 15.
- **Root cause:** the GAP tripwire pins the exact e2e spec inventory so a new spec cannot slip
  past unnoticed. Its own comment says "a new journey must replace this pin." This task added
  `e2e/touchTargets.spec.ts` (and `origin/main` already carried 5 other unpinned specs), so the
  pin was stale. The previous pass recorded this as out of scope, but the failing assertion is
  triggered by this task's new file and the test's contract assigns the pin update to the spec's
  author.
- **Fix:** added the 6 missing real paths to `currentSpecs` in
  `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts` so the pin
  equals the 15 committed specs. No threshold was changed, no exclusion or ignore comment added,
  no test skipped or disabled, and no production file touched. The GAP itself stays active — its
  workflow and visual-check assertions still expect `[]`, so the missing all-role / staging-RC /
  light-dark evidence is still honestly pinned as a gap.
- **Verification (final lines):**
  - `cd apps/staff-web && npx vitest run tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts`
    → `Test Files  1 passed (1)`, `Tests  3 passed (3)`, exit 0.
  - `bash scripts/coverage-check.sh origin/main` → `Test Files  268 passed (268)`,
    `Tests  3045 passed (3045)`, `No files with missing coverage`, `coverage check: passed`, exit 0.
  - `bash apps/staff-web/check.sh` → exit 0, `Test Files  268 passed (268)`,
    `Tests  3045 passed (3045)`, `Statements 100% (5107/5107)`, `Branches 100% (2634/2634)`,
    `Functions 100% (1697/1697)`, `Lines 100% (4903/4903)`.

### Branch-to-test list

The repair edits only a test file (a pinned expected-value set), so there is **no new production
line, branch, method or class** to cover; the 100% source-coverage delta is zero (see `## Coverage`).
For completeness, every branch of the edited GAP test is exercised by its single `it`, which now
passes:

| Branch / assertion in the edited test | Test that takes it | Result |
|---|---|---|
| `e2eSpecs()` sorted equals `currentSpecs` sorted (the repaired assertion, 15 vs 15) | `GAP: all-role, staging-RC and light/dark visual evidence is not yet recorded` | passed |
| workflow set filtered for `playwright\|e2e` equals `[]` | same `it` (all 268-file suite) | passed |
| `visualChecks` set equals `[]` (no screenshot/snapshot/theme vocabulary) | same `it` | passed |
| compile-time `expectTypeOf<Role>()` union | `npx tsc --noEmit` via `check.sh` | passed |
| `browserJourneysCoverSupportedRolesBilinguallyWithBlockingAxeGate` | its own `it` | passed |
| `wrongRoleIsRefusedAnotherRolesJourney` | its own `it` | passed |

No threshold lowered, no exclusion added, no test skipped. The existing `## Coverage` branch table
for `e2e/touchTargets.spec.ts` is unchanged and still accurate.

## Coverage

No source-coverage delta: the change is test-only, `apps/staff-web/package.json:16-17` runs
Vitest with `--exclude 'e2e/**'` and `apps/staff-web/vite.config.ts:24` includes only `src/**` in
coverage (thresholds unchanged at 100). No production file was changed, so there is no new
production line, branch, method or class to cover. The closest supported measure for this
e2e-only change is the executed route × project matrix and the observed RED/GREEN pair recorded
above. The commands that would express it are `cd apps/staff-web && npm run test:coverage`
(100% unchanged on `src/**`, no delta) and `cd apps/staff-web && npm run e2e` (310 tests,
64 passed, 246 skipped, exit 0); both were run here in their equivalent direct forms above.

Each new branch of the spec and the execution that takes it:

| Branch | Test execution that takes it |
|---|---|
| `namedCoarse` prefix agrees with `project.use.hasTouch` | All 310 executions (both outcomes; fine projects skip after the assertion) |
| `if (!coarse)` → `testInfo.skip` | 3 fine projects × 30 routes = 90 not-applicable skips |
| `if (defect !== undefined)` → `test.fixme` | 156 exact (route, project) pairs; false for the 54 passing compact coarse executions |
| `viewport !== null/undefined && width < 768` → open user menu | true on the 2 compact phone projects (360, 390 px); false on the 5 wide coarse projects (844–1180 px) |
| `isMeasurable`: not visible / disabled / `aria-hidden` / skip-link class | every measured route; `a.skip-link` is excluded on all 30 routes; closed-drawer controls are not rendered at measurement time |
| `boundingBox() === null` → element not measured | non-empty measured-set assertion would fail; no null boxes were observed in the green matrix |
| `control.width < 44` width failure array | 150 RED observations, e.g. `#5 a[Today]: 8x17 < 44x44` |
| `control.height < 44` height failure array | 6 RED observations, e.g. `#7 a: 292x32 < 44x44` |
| `measured.length > 0` (blank-page guard) | all 64 passing executions |
| route-list count and uniqueness assertions | first declared test in all 10 projects |

No threshold was lowered, no exclusion or coverage-ignore comment was added, and no test was
disabled; `test.fixme` marks only measured product defects and `testInfo.skip` marks only the
rule's not-applicable fine-pointer projects.

## Clinical and architecture review

No clinical data, prescribing, critical-result, release or tenant-boundary behavior changed.
Evidence is simulated and synthetic; it is not clinical, human or unit acceptance. The 44 px
rule is a documented layout contract, not a sign-off.

## Review verdicts

- aurevia-plan-reviewer: GO
- aurevia-test-engineer: GO
- aurevia-coverage-auditor: GO
- aurevia-devsecops: GO
- aurevia-code-reviewer: GO

Verdict context: the plan reviewer issued a pre-code NO-GO with nine Required gaps and a final
GO once all were closed; the test engineer issued a design-stage NO-GO with seven Required gaps
that were fixed before coding (synthetic UA, readiness, pointer parity, documented exceptions,
real RED, measured-set guard and per-control descriptors). The three post-code reviewers each
ended GO with no Critical or Required findings; the code reviewer's Optional notes are recorded
under `## Lessons for workers`.

## Findings

1. **Real defects (156 exact route × project pairs).** The RED run found: (a) `NavRail` links
   measure 8–17 px tall and often under 17 px wide at medium/expanded widths on every coarse
   project of width ≥ 768 (`phone-landscape-844x390`, the four `tablet-*` projects) on all 30
   routes — 150 pairs; (b) board detail links on `/lab/turnaround-board` and
   `/operations/partner-board` measure 292 × 32 — 6 pairs across the two compact phone projects;
   (c) result-entry inputs (96 × 32) and the action button (292 × 32) on `/lab/results/entry` —
   2 pairs. All are scoped `test.fixme`s with the measured reason; none was excused by lowering
   44 or adding a control to the exception list. Source of (a): `src/app/AppShell.tsx` `NavRail`
   links; of (b): `src/features/labboard/LabBoardScreen.tsx:89` and
   `src/features/partnerboard/PartnerBoardScreen.tsx:108`; of (c): `src/features/labresults/`.
   All need the shared `.touch` target class or equivalent coarse sizing. See `task-requests`.
2. **Stale e2e spec-set tripwire (fixed in repair 1).** The WEB-10 clause-2 GAP tripwire pinned
   the e2e spec set at 9 names while `origin/main` had 14 and this spec makes 15. The baseline run
   with the new file aside confirmed the pin was stale. The tripwire's own comment assigns the pin
   update to the spec author, so repair 1 extended `currentSpecs` to all 15 real paths; the GAP
   assertions themselves remain active. Both gates are now green (see Repair attempt 1).
3. **WEB-07 specification discrepancy.** WEB-07 text says "compact layout down to 768 px",
   while `docs/decisions/0004-responsive-staff-web.md` and `docs/design/responsive-layout.md` §2/§13
   require usable layouts from 320 px. Phone projects were kept, following the decision and guide;
   the specification text still needs a person to amend it.
4. **Headless-shell user agent.** The installed `HeadlessChrome/159` user agent does not match
   any rule in `src/features/system/browserSupport.ts` (`\bChrome` cannot match inside
   `HeadlessChrome`), so the app renders the unsupported-client page and no shell controls. The
   spec sets a synthetic, policy-accepted `Chrome/130` user agent via `test.use`. This is test
   emulation only; no production file changed.
5. **Measurement scope.** The checked state is the route as rendered: the navigation drawer is
   closed by default and its links are an allowed closed-overlay exception; a separate probe
   showed those drawer links are also 17 px tall when opened, which is a further defect for a
   follow-up task. The compact user menu is opened so its theme/language/sign-out controls are
   measured. Network-backed feature pages are not stubbed in this spec, so their API bodies may
   render an error/empty state; only controls actually present on the rendered route are measured.
   A full journey-level proof of every feature control with stubbed API responses is separate work.
6. **Environment.** Chromium and its Debian 12 OS libraries were installed in this worker to run
   the matrix; no repository file recorded that environment change. WebKit/Firefox projects are
   omitted by the config because those binaries are absent; the phone/tablet Chromium matrix is
   fully exercised.

## Completion status

This task is complete: the new e2e spec is committed and both gates are green
(`scripts/coverage-check.sh origin/main` and `bash apps/staff-web/check.sh` exit 0; 3045 tests
passed, 100% coverage). The stale spec-set tripwire that blocked the first coverage run was
repaired in place (see Repair attempt 1).

Deferred, not blocking this task: 156 coarse-pointer route × project executions remain
`test.fixme` because of real product defects in `NavRail`, the two board screens and result entry
(Findings 1). The 44 px rule was not weakened. The exact next step is a separate production task
that adds the shared `.touch` target to those controls, removes the matching `knownDefects`
entries and reruns the full matrix; those two follow-ups remain as task requests below.

## Lessons for workers

- Before authoring an e2e spec, prove the harness can render the app in this image: the default
  headless-shell user agent is rejected by the support policy, and a fresh browser download still
  needs `npx playwright install-deps chromium` on Debian 12.
- Derive a pointer threshold from the actual capability (`hasTouch` plus a `matchMedia` parity
  check), not from a project-name guess; a name can drift away from the CSS media feature.
- A "measure every control" test is only honest if it fails on the first run. Keep a recorded
  RED with the exact failure strings, then scope fixmes to measured (route, project) pairs.
- Check for tripwire tests that pin a file inventory before adding a file to that directory; here
  the tripwire was already red on `main`, so a baseline run with the new file moved aside is the
  evidence that distinguishes a pre-existing red from a regression. When the pin's own comment says
  "a new journey must replace this pin," updating it is this task's job, not a separate task —
  leaving a known-stale pin makes the coverage gate red and returns the work for repair.
- When a `test.fixme` list is used for measured defects, prefer measuring first and fixme-ing only
  on a reproduced failure so a later production fix turns the test green by itself. This runs the
  fixme before measurement by design (the RED was captured separately); a follow-up task removing
  the defect entries should rerun the full matrix.
- A drift guard that asserts only a list's length and uniqueness misses swap-in/swap-out changes;
  deriving expected paths from the route source modules is a stronger future guard.
- Reviewer Optional: `aria-hidden` is read only on the control, not its ancestors (deliberately
  err-on-measure); if an ancestor-hidden control is ever measured, extend the check.

```task-requests
[
  {
    "title": "Give NavRail links a 44px coarse-pointer target",
    "details": "Coarse projects at width >=768 measure NavRail links at 8-17px (e.g. /admin/audit / phone-landscape-844x390 / #5 a[Today]: 8x17 < 44x44). Apply the shared .touch target class or equivalent coarse sizing in src/app/AppShell.tsx (and the drawer links in src/app/NavigationDrawer.tsx), add view coverage, then remove the railDefectProjects entries from e2e/touchTargets.spec.ts and rerun the matrix.",
    "scope": ["apps/staff-web/src/app/", "apps/staff-web/e2e/touchTargets.spec.ts"],
    "depends": []
  },
  {
    "title": "Fix board and result-entry coarse target sizes",
    "details": "Board detail links (292x32) in src/features/labboard/LabBoardScreen.tsx and src/features/partnerboard/PartnerBoardScreen.tsx and the result-entry inputs/action (96x32, 292x32) in src/features/labresults/ miss the 44x44 coarse target. Apply the shared .touch class, add view coverage, then remove the compactDefectRoutes entries from e2e/touchTargets.spec.ts and rerun the matrix.",
    "scope": ["apps/staff-web/src/features/labboard/", "apps/staff-web/src/features/partnerboard/", "apps/staff-web/src/features/labresults/", "apps/staff-web/e2e/touchTargets.spec.ts"],
    "depends": []
  }
]
```
