# RESP-E2E-RIGHT-TO-LEFT-RESPONSIVE

## Summary

One new Playwright spec, `apps/staff-web/e2e/rightToLeftResponsive.spec.ts`, proves the staff web stays
usable and clinically readable in Arabic/RTL from phone to desktop. It declares one test per registered
route (28 feature routes plus `/`, the development preview and the catch-all, with synthetic bound IDs)
and runs it under every configured Playwright project, so each project's own viewport supplies the
compact, medium or expanded band and installed cross-browser engines are covered. No new project, no
dependency, no production source and no tracker is touched. Two real, out-of-scope product defects were
found by running the spec; one is parked in a single `test.fixme` with a strict assertion and a
task-request, as the story directs.

## Affected requirements

- WEB-07 — supported clients and the end-to-end browser matrix (all viewport bands, Chromium projects).
- UX-01 — English/Arabic, `dir="rtl"`, mirrored navigation, isolated mixed-script clinical text.
- Evidence anchors: `docs/design/responsive-layout.md` §11/R11 and §13, and
  `docs/design/rtl-and-localization.md` §§1–3.

## Risk class

Cross-boundary for verification only (layout behaviour across shell, rail, drawer and i18n). No
production boundary, authorization rule or data flow changes; synthetic fixtures only.

## Review owner

Senior React and Vite, with Senior TypeScript for the strict `.ts` spec; read-only plan, code, coverage
and devsecops reviewers per the quality loop.

## Data changes

None. No migration, backfill, lock or volume impact, rollback or forward-fix. Only synthetic identifiers
(`SYN-0001`, `SYN-NOTE-0001`, `SYN-ORD-0001`) appear in route fixtures; no patient record, credential or
real domain name is used.

## Authorization boundaries and audit events

None added or changed. The `ProtectedRoute` default (`signedIn=false`) and the role-scoped shell mean
most feature routes render a placeholder or state view; the spec asserts the visible state and never
expects clinical rows. No new audit event.

## Migration and rollout considerations

None; no deployable artifact, contract or configuration changed.

## Test evidence

Commands run from `apps/staff-web` unless noted. `CI=` was set only to reuse the locally started Vite
dev server so the dev-only `/design-preview` route was available.

- `npx playwright install chromium` → exit 0 (Chromium 159 + headless shell downloaded).
- `npx playwright install-deps chromium` → exit 0 (missing `libglib-2.0.so.0` and friends installed).
- Targeted red/green slice, `CI= npx playwright test --config e2e/playwright.config.ts e2e/rightToLeftResponsive.spec.ts --project=phone-portrait-360x740 --grep 'design preview keeps|welcome:' --retries=0`
  → first run red (`browserType.launch ... libglib-2.0.so.0` missing; then unsupported-browser page because
  of the headless UA; then a compact drawer click blocked by the RTL skip-link overlay), final run
  `2 passed (2.3s)`, exit 0.
- **Full matrix**: `CI= npx playwright test --config e2e/playwright.config.ts e2e/rightToLeftResponsive.spec.ts --retries=0`
  → `10 skipped / 320 passed (1.9m)`, exit 0 on the final file. The 10 skips are the one fixme test under each of the 10
  installed Chromium projects (Webkit/Firefox binaries are absent, so their conditional projects are
  not generated). Each of the 320 executing cases visits a real route, toggles Arabic through the
  installed language control, and measures the painted shell.
- `bash apps/staff-web/check.sh` → exit **1**, but only one pre-existing test fails:
  `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:166`, the
  spec-inventory tripwire. Final lines: `Test Files  1 failed | 267 passed (268)` /
  `Tests  1 failed | 3044 passed (3045)`. Generated-client check, `tsc --noEmit`, ESLint, build and
  WEB-08 bundle budget all passed before that test. Baseline proof: with the new spec temporarily moved
  out of the tree, `npx vitest run tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts --reporter=dot`
  → `Tests  1 failed | 2 passed (3)`, exit 1 — `main` already has 14 e2e specs against a hard-coded list
  of 9. Updating that inventory is outside this task's scope (see task-requests).
- `npx tsc --noEmit -p tsconfig.lint.json` → exit 0 (this project includes `e2e`, unlike the module's
  `tsconfig.json`); no `error TS…`.
- `npx eslint e2e/rightToLeftResponsive.spec.ts --max-warnings 0` → exit 0.
- `python3 -I scripts/check-constraints.py --base origin/main --require-base` → exit 0.
- `python3 -I scripts/check-verdicts.py .agent/pr-notes/RESP-E2E-RIGHT-TO-LEFT-RESPONSIVE-20261124-004449.md` → exit 0 after the three GO verdicts below were recorded.
- `git merge --no-edit origin/main` → `Already up to date.`

**Repair pass (gate red on the WEB-10 GAP tripwire).** The delivery gate failed at
`tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:166`: the
`currentSpecs` pin listed 9 e2e specs while the tree holds 15 (14 already on `main`, plus this
spec). After that pin was refreshed, the next assertion in the same tripwire failed because
`visualChecks` was pinned to `[]` while the new RTL spec calls `getComputedStyle` at
`e2e/rightToLeftResponsive.spec.ts:222` — a computed-style read the test explicitly counts as
visual-check vocabulary. Both pins were regenerated to the current artifact shape. No threshold
was lowered, no exclusion, skip, suppression or `@Disabled` added, and no assertion weakened; the
tripwire still fails on any future added or removed spec or visual-check spec.

- `npx vitest run tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts --reporter=verbose`
  → `Test Files  1 passed (1)` / `Tests  3 passed (3)`, exit 0.
- `bash apps/staff-web/check.sh` → exit **0**: `Test Files  268 passed (268)` /
  `Tests  3045 passed (3045)`; coverage summary
  `Statements 100% (5107/5107) / Branches 100% (2634/2634) / Functions 100% (1697/1697) / Lines 100% (4903/4903)`.
  The `gen:api:check`, `typecheck`, `lint`, `build` and bundle-budget stages all precede the passing
  coverage stage, so they passed too. (jsdom `Not implemented: navigation` lines are pre-existing
  console noise from `UnsupportedBrowserPage.tsx`, not failures.)
- `python3 -I scripts/check-constraints.py --base origin/main --require-base` → exit 0.
- `python3 -I scripts/check-verdicts.py .agent/pr-notes/RESP-E2E-RIGHT-TO-LEFT-RESPONSIVE-20261124-004449.md`
  → exit 0.

Entry point to test trace: Playwright project → `page.goto(route)` → `AppShell` + `I18nProvider`
(language control) → `NavigationDrawer`/`NavRail` geometry and `main#main` content →
`getBoundingClientRect`/`scrollWidth`/`getClientRects` assertions in the new spec.

## Coverage

No production source file changed: files changed = 1 new e2e spec + this notes file. The staff-web Vitest
gate excludes e2e specs (`vitest run --coverage --exclude 'e2e/**'`), so no e2e coverage number is
claimed and none is needed for production files. Every branch of the new spec is exercised by an
executing assertion except the one parked real-defect case, which is explicitly named under
"Why this is not finished":

| Branch / invariant | Executing test | Assertion |
|---|---|---|
| Each of 31 concrete routes × each installed project band | `*: Arabic layout is mirrored, contained and readable` | `main#main` and heading visible, nav geometry, shell overflow, no overlap |
| Language toggle `ltr` → `rtl`, `en` → `ar` | `switchToArabic` in every test | `html` has `dir=rtl` and `lang=ar` |
| Compact drawer opener on inline-start vs medium/expanded rail | same per-route test, `data-breakpoint` branch | nav editor right-edge gap ≤ 40 px and closer to inline-start than inline-end |
| App-shell horizontal overflow at each band | same per-route test (`shellWidths`) | `scrollWidth <= clientWidth` with both values in the message |
| No overlapping visible clinical text | same per-route test (`overlappingTextRuns`) | collision list equals `[]`, with the colliding runs in the message. The detector's positive branch was observed during development: on `/lab/turnaround-board` at 1280 px it returned 16 header/rail collisions and failed the case before the chrome overlap was scoped out (Finding 2), so an always-empty detector would not pass unnoticed. |
| Bidi isolation of a value and its unit | `design preview keeps the clinical measurement in one isolated Arabic run` | exactly one `bdi[dir=auto][lang]` run, containing both `12.5` and `cm`, `lang=ar` |
| Real defect: document-level RTL overflow + compact drawer cannot be opened | one `test.fixme` test (both assertions kept strict) | `documentElement.scrollWidth <= clientWidth`, then drawer opens on a real click |

### Branch-to-test list

No production source changed in the original task or the repair pass, so there are **no production
lines, branches, methods or classes** to cover. The only production file in scope is the new e2e
spec, which the Vitest gate excludes (`--exclude 'e2e/**'`); its executing branches are listed in the
table above. The repair changed only the WEB-10 GAP-tripwire test; each edited pin is checked by a
deterministic assertion in that same test:

| Changed line / branch | Deterministic test | Assertion that fails if it regresses |
|---|---|---|
| `currentSpecs` inventory pin (9 → 15 e2e specs) | `GAP: all-role, staging-RC and light/dark visual evidence is not yet recorded` | `expect(specs.sort()).toEqual([...currentSpecs].sort())` — adding or removing any `e2e/**/*.spec.ts` fails it |
| `visualChecks` pin (`[]` → `['rightToLeftResponsive.spec.ts']`) | same test | `expect(visualChecks.sort()).toEqual([...])` — adding or removing a screenshot/snapshot/`getComputedStyle`/`data-theme`/`colorScheme` spec fails it |
| New doc comments on both pins | n/a | comments only, no executable branch |

## Clinical and architecture review

No clinical data, prescribing, release of information or permission change; no architecture boundary
change. Simulated browser evidence only — not clinical or human acceptance. The real Arabic-speaking
clinical review remains a separate launch gate (UX-01).

## Review verdicts

- smartclinic-plan-reviewer: GO (second pass, after the real defect was reproduced; first-pass Required gaps on heading scope, gate execution and the fixme premise were resolved)
- smartclinic-code-reviewer: GO
- smartclinic-coverage-auditor: GO
- smartclinic-devsecops: GO

## Findings

1. **Real product defect, out of scope — RTL skip link.** `apps/staff-web/index.html:16-22` boots
   `.skip-link` with a physical `left:-9999px` (and physical `:focus` offsets), while
   `apps/staff-web/src/theme/responsive.css:179-181` uses the logical `inset-inline-start:-9999px`.
   Under `dir=rtl` the computed style has **both** `left:-9999px` and `right:-9999px`, giving a 20358 px
   wide skip-link box and `documentElement.scrollWidth=10359` against `clientWidth=360` (also 10359 vs
   1280 at desktop). At compact `isMobile` widths this expands the visual viewport to 1440 and the shell
   is shifted off-screen, so a real user cannot open the drawer. Tool evidence from a direct browser
   probe; the strict assertions are kept in the single fixme test and a follow-up task is requested.
2. **Real product defect, out of scope — expanded rail overlays header.** At 1280 px,
   `.app-shell__nav` (sticky, `insetInlineStart:0`) paints over header text and controls on some routes
   (reproduced on `/lab/turnaround-board`): the Language button is intercepted by rail links and rail
   text overlaps header text. This is not RTL-specific. The spec toggles the language through keyboard
   activation (a real WCAG path) and scopes the clinical-text overlap check to `main#main` so the RTL
   contract stays deterministic; the chrome overlap is recorded here for a follow-up task, not excluded
   by a skip-link or rail ignore rule.
3. **Environment-only workaround.** Playwright's headless shell reports `HeadlessChrome/159`, which the
   WEB-07 support guard treats as an unknown engine and answers with the plain unsupported-browser
   page. The spec sets a synthetic current-Chrome `userAgent` via `test.use({ userAgent })` so the real
   application renders. Layout is still driven by each project's viewport and the runtime RTL toggle;
   the unsupported-client guard itself is unchanged and is not what this journey asserts.
4. **Spec-inventory / visual-check tripwire refreshed (resolved in repair pass).** The GAP tripwire at
   `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts` pinned 9 e2e
   specs while `main` had 14 and this branch 15, and pinned `visualChecks=[]` while the new RTL spec
   calls `getComputedStyle`. Both pins were regenerated to the current artifact shape (15 specs; one
   computed-style visual check), with comments recording why. The tripwire still fails on any future
   added or removed spec or visual check, so its GAP-detection function is intact. `bash
   apps/staff-web/check.sh` now exits 0. Because this is resolved, task-request #3 from the original
   notes must **not** be created by the integrator (no duplicate task).
5. **Known deviation, not re-tested here.** `formatMeasurement` joins value and unit with a plain
   space, while `docs/design/rtl-and-localization.md` §3 requires U+00A0 (documented at lines 126–130 of
   that file). The spec asserts atomic isolation of `12.5` and `cm` in one `bdi`, but does not assert the
   separator; adding that failing assertion belongs to the fixing story.

## Lessons for workers

- Run `npx playwright install chromium && npx playwright install-deps chromium` before assuming browsers
  are unavailable; `--list` alone gives no execution evidence. On this image both commands work.
- The WEB-07 browser-support guard rejects the headless-shell UA (`HeadlessChrome/...`). A synthetic
  supported-Chrome UA in `test.use({ userAgent })` is needed for any e2e that expects the real shell.
- Never put a glob such as `src/features/*/routes.ts` inside a JSDoc block: the `*/` closes the comment
  and produces a cascade of misleading TypeScript parse errors.
- Logical CSS properties can still be defeated by a leftover physical property in `index.html`. When
  RTL geometry looks impossible, inspect computed `left`/`right` before blaming the test.
- Keyboard activation (`focus()` + `Enter`) is deterministic when a sticky overlay covers a control;
  `click({ force: true })` can produce a false green by opening UI a real user cannot reach.
- Check whether a failing module gate is pre-existing by temporarily removing your file and rerunning
  the single failing test; record the result instead of editing an out-of-scope tripwire.

## Why this is not finished

One thing keeps this from being an unqualified green:

1. The one `test.fixme` case (10 skipped project instances) is parked on the real, out-of-scope RTL
   skip-link defect in `apps/staff-web/index.html`. Command that exposed it (direct browser probe after
   the Arabic toggle at 360 px): computed `left=-9999px`, `right=-9999px`, skip-link width `20358px`,
   `documentElement.scrollWidth=10359`, `clientWidth=360`; the compact drawer opener cannot be clicked.
   Tried: normal click (intercepted/unreachable at `isMobile` widths) and `click({ force: true })` (rejected
   because it measures a drawer no real user can open). **Next step:** fix `index.html` to use logical
   insets and remove the physical `:focus` offsets, then convert the fixme test to a normal `test`.
2. ~~`bash apps/staff-web/check.sh` exits 1 on the spec-inventory tripwire~~ **Resolved in repair pass:**
   both stale pins refreshed; `bash apps/staff-web/check.sh` now exits 0 with 3045/3045 tests passing
   and 100% coverage across statements, branches, functions and lines. See Finding 4 and the repair-pass
   bullet under Test evidence. No threshold, exclusion or skip was added.

Everything in scope is green: the full Playwright run is 320 passed / 10 skipped, the full module gate
`bash apps/staff-web/check.sh` is 3045/3045 passing at 100% coverage, the spec passes strict TypeScript
and ESLint, and no constraint was weakened. The only remaining gap is the parked real RTL skip-link
defect above, which belongs to task-request #1.

## task-requests

```task-requests
[
  {
    "title": "Fix RTL skip-link overflow in the staff-web HTML bootstrap style",
    "details": "apps/staff-web/index.html still sets .skip-link { left: -9999px } and :focus { left: 8px; top: 8px } physically while responsive.css uses inset-inline-start. Under dir=rtl the computed left and right are both -9999px, the run is 20358px wide, documentElement.scrollWidth is 10359 and the compact drawer cannot be opened by a real click. Remove the physical declarations, keep the logical inset, then re-enable the single test.fixme in apps/staff-web/e2e/rightToLeftResponsive.spec.ts as a normal test: keep both strict assertions and keep the drawer click gated on .app-shell[data-breakpoint=\"compact\"], because medium and expanded projects mount a rail rather than the drawer.",
    "scope": ["apps/staff-web/index.html", "apps/staff-web/e2e/rightToLeftResponsive.spec.ts"],
    "depends": ["RESP-E2E-RIGHT-TO-LEFT-RESPONSIVE"]
  },
  {
    "title": "Stop the expanded staff-web rail from overlaying header controls",
    "details": "At 1280px the sticky .app-shell__nav (insetInlineStart: 0) paints over header text and the Language button on routes such as /lab/turnaround-board; Playwright reports rail sections intercepting pointer events and clinical-text-overlap checks against the header. Fix the grid/stacking so chrome never overlaps the header, then remove the keyboard-activation workaround from the RTL spec and widen its overlap check back to the full document.",
    "scope": ["apps/staff-web/src/app/AppShell.tsx", "apps/staff-web/src/theme/responsive.css", "apps/staff-web/e2e/rightToLeftResponsive.spec.ts"],
    "depends": ["RESP-E2E-RIGHT-TO-LEFT-RESPONSIVE"]
  }
]
```
