# RESP-E2E-RIGHT-TO-LEFT-RESPONSIVE

Re-verification run on branch `swarm/RESP-E2E-RIGHT-TO-LEFT-RESPONSIVE-1436` at base `261d2169`.

## Brief correction

The advisor brief and the task details say `apps/staff-web/e2e/rightToLeftResponsive.spec.ts` is absent in
this snapshot. It is present in HEAD (added by commit `34763242`, merged as PR #1556) and already carries
the full RTL matrix. This run therefore verifies the existing in-scope deliverable end to end rather than
recreating it; no source change was needed. The task file's `attempts: 5` and prior notes were consulted
first, so no already-tried step was repeated without cause.

## Summary

`apps/staff-web/e2e/rightToLeftResponsive.spec.ts` proves the staff web stays usable and clinically
readable in Arabic/RTL from phone to desktop. It declares one journey for each of the 31 registered
routes (28 feature patterns plus `/`, the development-only `/design-preview` and the catch-all, with
synthetic bound IDs) and runs every journey under all installed Playwright projects, so each project's
own viewport supplies the compact, medium or expanded band. Assertions toggle the real language control,
then measure painted geometry: navigation meets the inline-start edge, the shell has no inline overflow,
visible clinical text does not overlap, and a value with its unit stays in one isolated bidirectional
run. Failure messages name the route and project (`caseContext`) and include the measured number.

No production code, dependency, project config, tracker, workflow or script changed. Synthetic identifiers
only (`SYN-0001`, `SYN-NOTE-0001`, `SYN-ORD-0001`); no patient record, credential or real endpoint.

## Affected requirements and acceptance mapping

| Requirement / criterion | Evidence in the spec | Observed result |
|---|---|---|
| WEB-07 — browser matrix across supported widths | One route journey per project in `playwright.config.ts`; compact drawer opener, medium icon rail, expanded labelled rail | 320 executing cases across 10 installed Chromium projects |
| UX-01 — Arabic/`dir=rtl`, mirrored navigation, isolated mixed-script text | `switchToArabic` asserts `dir=rtl` and `lang=ar`; `navigationGaps` measures inline-start; `isolatedRuns` checks the `bdi` value+unit run | Green in every executing case; design-preview measurement run is exactly one and carries `lang=ar` |
| One test per route and project; messages name route, project, measurement | `ROUTES` loop plus `caseContext(route)`; assertions interpolate gap, width and collision values | 330 listed cases, 320 passed |
| Real defects are parked, not weakened | Single `test.fixme` keeps both strict assertions; the overlay workaround is documented in-file | 10 skipped project instances; both defects re-measured this run (see Findings) |
| Synthetic data only | Fixtures bound to `SYN-*` identifiers | No PHI or credential in the spec or notes |

Design anchors: `docs/design/responsive-layout.md` §11/R11 and §13, and
`docs/design/rtl-and-localization.md` §§1–3.

## Risk class

Cross-boundary for verification only (layout behaviour across shell, rail, drawer and i18n). No
production boundary, authorization rule or data flow changes. Clinical and defensive-security impact was
checked and found not to apply; no clinical sign-off is claimed.

## Data changes

None. No migration, backfill, lock or volume impact, rollback or forward-fix.

## Test evidence

Commands run from the repository root unless a `cd` is shown. Evidence source: executed tool commands;
reviewer findings are labelled separately under Review verdicts, and there was no simulated-user or human
participant in this run.

- `git merge --no-edit origin/main` → `Already up to date.`, exit 0. No conflict markers
  (`git grep -nE '^(<<<<<<<|>>>>>>>) '` → no matches).
- `cd apps/staff-web && npx playwright install chromium && npx playwright install-deps chromium` → exit 0;
  Chrome for Testing 159.0.8154.6 (Playwright chromium v1255) plus headless shell and 97 OS libraries installed.
- `npx playwright test --config e2e/playwright.config.ts --list e2e/rightToLeftResponsive.spec.ts` →
  exit 0; final line `Total: 330 tests in 1 file`.
- `CI= npx playwright test --config e2e/playwright.config.ts e2e/rightToLeftResponsive.spec.ts --retries=0` →
  exit 0; final lines `10 skipped` / `320 passed (2.3m)`. This is the entry-point-to-assertion trace:
  Playwright project → `page.goto(route)` → production `AppShell` + `I18nProvider` language control →
  `NavigationDrawer`/`NavRail` geometry and `main#main` content → `getBoundingClientRect` /
  `scrollWidth` / `getClientRects` assertions in the spec.
- `bash apps/staff-web/check.sh` → exit **1** on a pre-existing, out-of-scope CI pin, with every earlier
  stage green (`gen:api:check`: "generated client matches the contract"; `tsc --noEmit`: exit 0;
  `eslint . --max-warnings 0`: exit 0; `vite build` + `check-bundle-size.mjs`: exit 0). Final lines:
  `Test Files  1 failed | 277 passed (278)` / `Tests  1 failed | 3141 passed (3142)`. The failure is
  `tests/spec/platform/route-chunk-most-kb-gzip-enforced-ci.test.ts:106`, which expects a
  `- run: bash scripts/frontend-check.sh` step in a CI job and receives `[]`.
- Baseline on the same clean tree (no probe, no source change):
  `npx vitest run tests/spec/platform/route-chunk-most-kb-gzip-enforced-ci.test.ts --reporter=dot` →
  exit 1; final lines `Test Files  1 failed (1)` / `Tests  1 failed | 2 passed (3)`. The red gate is
  therefore pre-existing and unaffected by this story; the out-of-scope pin was not edited.
- `npx tsc --noEmit -p tsconfig.lint.json` → exit 0; this project includes `e2e`, unlike the module's
  `tsconfig.json`. No `error TS…`.
- `npx eslint e2e/rightToLeftResponsive.spec.ts --max-warnings 0` → exit 0.
- `python3 -I scripts/check-constraints.py --base origin/main --require-base` → exit 0.
- `python3 -I scripts/check-docs.py` → exit 1 with 151 pre-existing findings, all in `docs/pr/reports/`;
  zero findings reference this notes file. No Markdown outside scope was edited.
- Temporary defect probe (created after `check.sh` finished, run, then deleted; no probe entry remained
  in `git status`):
  - `CI= npx playwright test --config e2e/playwright.config.ts e2e/_rtl-defect-probe.spec.ts --project=phone-portrait-360x740 --project=laptop-1280x720 --retries=0` →
    `PROBE_A {"left":"-9999px","right":"-9999px","width":"20358px","scrollWidth":10359,"clientWidth":360}`
    and `PROBE_A {"left":"-9999px","right":"-9999px","width":"21278px","scrollWidth":11279,"clientWidth":1280}`.
    Probe B also reported the rail box covering the language-button centre; its one failure on the compact
    project was expected because the compact band mounts a drawer, not a rail.
  - `CI= npx playwright test --config e2e/playwright.config.ts e2e/_rtl-defect-probe.spec.ts --project=laptop-1280x720 --grep 'PROBE B' --retries=0` →
    exit 0; `PROBE_B {"rail":{"left":1000,"right":1256,"top":16,"bottom":892.78125},"languageButton":{"left":1116,"right":1174,"top":214.875,"bottom":239.875},"covered":true}`.
  Both probes used the same keyboard-only language activation as the story spec.

## Coverage

No production source file changed: files changed = this notes file only. The staff-web Vitest gate
excludes e2e specs (`vitest run --coverage --exclude 'e2e/**'`), so no e2e coverage number is claimed and
none is required for production files. Every branch of the existing spec is exercised by an executing
assertion except the one deliberately parked real-defect case, named under "Why this is not finished":

| Branch / invariant | Executing test | Assertion |
|---|---|---|
| Each of 31 concrete routes × each installed project band | `*: Arabic layout is mirrored, contained and readable` | `main#main` and a heading visible, nav inline-start geometry, shell overflow, no overlap |
| Language toggle `ltr` → `rtl`, `en` → `ar` | `switchToArabic` in every test | `html` has `dir=rtl` and `lang=ar` |
| Compact drawer opener vs medium/expanded rail | same per-route test, `data-breakpoint` branch | opener/rail inline-start gap ≤ 40 px and nearer inline-start than inline-end |
| App-shell inline overflow at each band | same per-route test (`shellWidths`) | `scrollWidth <= clientWidth`, both values in the message |
| No overlapping visible clinical text | same per-route test (`overlappingTextRuns`) | collision list equals `[]`; colliding runs named in the message |
| Bidi isolation of a value and its unit | `design preview keeps the clinical measurement in one isolated Arabic run` | exactly one `bdi[dir=auto][lang]` run containing `12.5` and `cm`, `lang=ar` |
| Real defect: document RTL overflow and compact drawer unreachable | one `test.fixme` (10 project instances) | strict `documentElement.scrollWidth <= clientWidth`, then a real drawer click on compact |

The helper guards at spec lines 158, 179, 212 and 370 (`shell === null`, `element === null`,
`main === null`, `measurement === undefined`) are fail-loud assertions inside the measuring helpers rather
than product branches: if any fires, the surrounding executing case fails with a named message. They are
not separately enumerated above because e2e helpers are outside the Vitest coverage gate; the
`measurement === undefined` narrow is also required by `noUncheckedIndexedAccess`.

## Review verdicts

- aurevia-plan-reviewer: GO (fourth pass; earlier Required gaps on defect-probe evidence, notes coverage of both defects, probe sequencing and keyboard-only activation were closed)
- aurevia-code-reviewer: GO
- aurevia-coverage-auditor: GO
- aurevia-devsecops: GO

## Findings

1. **Real product defect, out of scope — RTL skip link.** `apps/staff-web/index.html:16-19` boots
   `.skip-link` with a physical `left:-9999px` (and physical `:focus` offsets), while
   `apps/staff-web/src/theme/responsive.css:179-181` uses logical `inset-inline-start:-9999px`. Under
   `dir=rtl` the computed box has both `left:-9999px` and `right:-9999px`, so it is 20 358 px wide at a
   360 px viewport and `documentElement.scrollWidth` reaches 10 359 against `clientWidth=360` (11 279 vs
   1280 at desktop). At compact `isMobile` widths the stretched link is a viewport-wide overlay and a real
   user cannot open the drawer. Re-measured this run with the temporary probe above.
2. **Real product defect, out of scope — expanded rail overlays the header.** At 1280 px the sticky
   `.app-shell__nav` paints over header text and controls (reproduced on `/lab/turnaround-board`): the
   probe measured rail `left=1000,right=1256,top=16,bottom=892.8` against the language button
   `left=1116,right=1174,top=214.9,bottom=239.9`, so the rail covers the button centre. Not RTL-specific.
   The spec toggles language by keyboard (a real WCAG path) and scopes the clinical-text overlap check to
   `main#main`, so the RTL contract stays deterministic; the chrome overlap is recorded here for a
   follow-up task, not excluded by a skip-link or rail ignore rule.
3. **Pre-existing red module gate.** `route-chunk-most-kb-gzip-enforced-ci.test.ts:106` fails on an
   unchanged tree; it is a CI-workflow pin, unrelated to this spec, and was baselined rather than edited.
   The earlier spec-inventory tripwire was already repaired in commit `23898e03`; 27 `.spec.ts` files are
   on disk (14 top-level plus 13 under journey subdirectories) and the current pin matches them.
4. **Compact drawer proxy.** The active per-route case measures the drawer opener
   `button[aria-controls="staff-navigation-drawer"]`, not `#staff-navigation-drawer`, because finding 1
   prevents a real click at compact widths. The drawer's own inline-start assertion remains strict inside
   the single existing `test.fixme`; no second fixme was added.
5. **Environment workaround.** Playwright's headless-shell UA is unknown to the WEB-07 support guard, so
   the spec sets a synthetic current-Chrome UA in `test.use`; layout is still driven by each project's
   viewport and the runtime RTL toggle.
6. **Known deviation, not re-tested here (code-reviewer Optional).** The design-preview assertion proves
   `12.5` and `cm` share one `bdi[dir=auto][lang=ar]` run, but not the U+00A0 separator required by
   `docs/design/rtl-and-localization.md` §3: `formatMeasurement` in `apps/staff-web/src/i18n/format.ts`
   joins with a plain space. Asserting `12.5\u00A0cm` would fail today on an out-of-scope production
   defect, so it belongs to the fixing story rather than this verification run.

## Lessons for workers

- Install `npx playwright install chromium && npx playwright install-deps chromium` before trusting that
  browsers are unavailable: `--list` and a first failed launch both predate the install on this image.
- The WEB-07 guard rejects `HeadlessChrome/…`; a supported-Chrome `userAgent` in `test.use` is required
  for any end-to-end test that expects the real shell.
- When a temporary probe spec exists, Playwright's inventory tripwire goes red and `check.sh` gives a
  false signal. Finish `check.sh` first, delete the probe before any later gate, and confirm no probe
  entry in `git status`.
- At compact widths use the keyboard path (`focus()` + `Enter`) to reach the Language control; a pointer
  click is intercepted by the RTL skip-link overlay that is itself the defect under test.
- Baseline a red module gate on the untouched tree instead of editing an out-of-scope pin.

## Why this is not finished

Two items keep this from an unqualified green; both are real, out-of-scope product defects, and the
committed spec keeps their assertions strict rather than weakening them:

1. The single `test.fixme` case (10 skipped project instances) is parked on the RTL skip-link defect in
   `apps/staff-web/index.html`. Probe measurement at 360 px: computed `left=-9999px`, `right=-9999px`,
   skip-link width `20358px`, `documentElement.scrollWidth=10359`, `clientWidth=360`; the compact drawer
   cannot be opened by a real click. Tried in the prior attempt and confirmed here: a normal click is
   intercepted, and `click({ force: true })` was rejected because it would measure a drawer no real user
   can reach. **Next step:** replace the physical declarations in `index.html` with logical insets, remove
   the physical `:focus` offsets, then convert the fixme to a normal `test` while keeping both assertions
   and the compact-only drawer click.
2. The expanded rail overlays header controls. Probe measurement at 1280 px: rail `left=1000,right=1256`
   covers the language button at `left=1116,right=1174`. **Next step:** fix the shell grid/stacking so
   chrome never overlaps the header, then remove the keyboard-activation workaround from the RTL spec and
   widen its overlap check back to the full document.

`bash apps/staff-web/check.sh` is also red on the pre-existing CI pin described in Finding 3; the next
step there belongs to that pin's owner, and no out-of-scope file was changed in this run.

## task-requests

```task-requests
[
  {
    "title": "Fix RTL skip-link overflow in the staff-web HTML bootstrap style",
    "details": "apps/staff-web/index.html:16-19 sets .skip-link { left: -9999px } and :focus { left: 8px; top: 8px } physically while src/theme/responsive.css uses inset-inline-start. Under dir=rtl the computed left and right are both -9999px, the box is 20358px wide at 360px, documentElement.scrollWidth is 10359 and the compact drawer cannot be opened by a real click. Remove the physical declarations, keep the logical inset, then re-enable the single test.fixme in apps/staff-web/e2e/rightToLeftResponsive.spec.ts as a normal test: keep both strict assertions and keep the drawer click gated on .app-shell[data-breakpoint=\"compact\"], because medium and expanded projects mount a rail instead.",
    "scope": ["apps/staff-web/index.html", "apps/staff-web/e2e/rightToLeftResponsive.spec.ts"],
    "depends": ["RESP-E2E-RIGHT-TO-LEFT-RESPONSIVE"]
  },
  {
    "title": "Stop the expanded staff-web rail from overlaying header controls",
    "details": "At 1280px the sticky .app-shell__nav (insetInlineStart: 0) paints over header text and the Language button on routes such as /lab/turnaround-board. Measured rail left=1000,right=1256,top=16,bottom=892.8 against language button left=1116,right=1174,top=214.9,bottom=239.9. Fix the grid or stacking so chrome never overlaps the header, then remove the keyboard-activation workaround from apps/staff-web/e2e/rightToLeftResponsive.spec.ts and widen its overlap check back to the full document.",
    "scope": ["apps/staff-web/src/app/AppShell.tsx", "apps/staff-web/src/theme/responsive.css", "apps/staff-web/e2e/rightToLeftResponsive.spec.ts"],
    "depends": ["RESP-E2E-RIGHT-TO-LEFT-RESPONSIVE"]
  }
]
```
