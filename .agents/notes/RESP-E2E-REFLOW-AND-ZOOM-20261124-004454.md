# RESP-E2E-REFLOW-AND-ZOOM — staff web reflowAndZoom across phone, tablet and desktop

## Story status (segment 2 of 4)

**STORY COMPLETE within the assigned scope.** The sole scoped file,
`apps/staff-web/e2e/reflowAndZoom.spec.ts`, is committed and was not changed by
the post-merge verification. On merged HEAD `99cd56cf` every in-scope gate is
green: strict typecheck of the e2e file, scoped ESLint, the 1152-case Playwright
listing and a focused real-browser run. The only failing gate is
`bash apps/staff-web/check.sh` at a **pre-existing, out-of-scope GAP tripwire**
(`tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts`)
whose pinned e2e spec list had already drifted on `main`; a `task-requests`
entry for it is filed below and the file was deliberately not edited (scope
rule). The documented `test.fixme` cases are the task-brief-sanctioned way to
record real defects for follow-up tasks, not incomplete work in this scope. **Update (repair 1):** the tripwire was subsequently repaired and the full `check.sh` gate is green (`exit=0`); see "Repair attempt 1" below.

## Repair attempt 1 — GAP-tripwire pin refresh (tool evidence)

The reported "coverage check" failure was not a coverage miss. `bash apps/staff-web/check.sh`
failed its Vitest stage at the WEB-10 GAP tripwire
`tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:166`:
the test pins the exact committed e2e spec set to 9 names, but the checkout now
contains 15 (five sibling-worker journey specs plus this story's
`reflowAndZoom.spec.ts`). A second pin (`visualChecks`, formerly `[]`) would have
failed next because the reflow spec reads computed styles for layout measurement.
Both pins were refreshed to the actual committed set — exactly what the test's
own comment prescribes ("a new journey must replace this pin"). No threshold,
exclusion, pragma, skip or production file was changed; the only modified file is
that one test. The self-improvement `task-requests` entry below is now satisfied
and can be retired by reconciliation.

Exit evidence, all commands executed in this worker (tool evidence):
`npx vitest run tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts`
→ `Test Files 1 passed (1)`, `Tests 3 passed (3)`;
`bash apps/staff-web/check.sh` → `exit=0`, `Test Files 271 passed (271)`,
`Tests 3051 passed (3051)`, Coverage `Statements 100% (5107/5107)`,
`Branches 100% (2634/2634)`, `Functions 100% (1697/1697)`,
`Lines 100% (4903/4903)`, `No files with missing coverage.`.

### Branch-to-test list (repair 1)

The changed lines are data pins inside a test file, so they add no production
coverage denominator; each is exercised by the existing `GAP:` case in that file.

| Changed line / pin | Branch or data exercised | Deterministic test / evidence |
|---|---|---|
| `currentSpecs` expanded 9 → 15 entries (`...test.ts:93-107`) | spec-set equality, including every newly added journey | `GAP:...` case, `expect(specs.sort()).toEqual([...currentSpecs].sort())` at line 179; the 15 names were enumerated with `find e2e -name '*.spec.ts' \| sort` (tool evidence) |
| `visualChecks` `[]` → `['reflowAndZoom.spec.ts']` (`...test.ts:186-196`) | vocabulary match over computed-style reads vs screenshot/theme tokens | same `GAP` case, `expect(visualChecks.sort()).toEqual(['reflowAndZoom.spec.ts'])` at line 196; `grep -nE 'toHaveScreenshot\|toMatchSnapshot\|getComputedStyle\|data-theme\|colorScheme' e2e/reflowAndZoom.spec.ts` (tool) shows only `getComputedStyle` layout reads — no screenshot or light/dark theme assertion, so the clause's visual-evidence half stays genuinely absent |

## Summary

Added one cross-cutting Playwright spec, `apps/staff-web/e2e/reflowAndZoom.spec.ts`, that measures WCAG 1.4.4 / 1.4.10 / 1.4.12 reflow, zoom and text-spacing behaviour for every reachable staff-web route across every configured project, with synthetic data only. No production source, configuration, dependency, tracker or workflow file was changed. Genuine defects and harness limitations are recorded as `test.fixme` with measured values and are listed under **Why this is not finished**; no assertion was weakened, skipped or removed.

## Affected requirements

- **WEB-07** — supported clients and responsive device matrix (`AUREVIA_V1_SPEC.md:155`).
- **UX-01** — accessibility, RTL and responsive evidence (`AUREVIA_V1_SPEC.md:90`).
- `docs/design/responsive-layout.md` §12 (lines 393–419) and §13 rule R12 (line 458).

Acceptance mapping:

| Acceptance | Test pass | Proof |
|---|---|---|
| 320 CSS px reflow, no page-level 2-D scroll, labelled-region exception | `reflow-320` on every renderable screen | document + body `scrollWidth <= clientWidth + 1`; overflow outside `[role=region][tabindex=0]` with an accessible name is a failure; structural assertion on the compact `DataTable` region |
| 200% zoom | `zoom-200` | CSS `documentElement.style.zoom='2'`, computed readback plus 100px→200px probe rect |
| 400% zoom | `zoom-400` (declared and asserted; fixme — see Findings) | CSS zoom `'4'`, 100px→400px probe rect guard |
| Text spacing 1.4.12 | `text-spacing`, `rtl-text-spacing` | injected line-height 1.5, word-spacing 0.16em, letter-spacing 0.12em, paragraph margin 2em; computed values asserted active; Arabic tracking exempt |
| RTL / UX-01 | `rtl-text-spacing` on `/design-preview` (declared and asserted; fixme — see Findings) | real Language toggle at 768px, `html[lang=ar][dir=rtl]`, Arabic letter-spacing 0 |
| Parameterised routes with synthetic IDs | reachability cases for `/patients/SYN-0001`, `/patients/SYN-0001/chart`, `/patients/SYN-0001/notes/SYN-NOTE-0001`, `/pharmacy/SYN-PHARM-01` | each declared per project; auth-gated ones are fixme with reason |
| A11y gate reuse | `axe` on `/design-preview` | `expectNoBlockingViolations` from `e2e/accessibility.ts` |

Entry point → test trace: `apps/staff-web/src/app/routes.tsx` `createRoutes` + `apps/staff-web/src/features/*/routes.ts` patterns → `apps/staff-web/e2e/playwright.config.ts` projects → `e2e/reflowAndZoom.spec.ts` `page.goto(screen.target)` → `runMeasurePass` four page-side snapshots → `classify` → `expect(findings).toEqual([])`. All 16 configured projects are enumerated; none is silently omitted.

## Risk class

Routine. One test-harness-only file; no production behaviour, contract, data path, permission boundary or clinical result changes.

## Review owner

`aurevia-senior-react` with `aurevia-senior-typescript` for strict TypeScript. `aurevia-plan-reviewer` reviewed the plan before coding (GO after two Required rounds). Post-code verdicts are under **Review verdicts**.

## Data changes

None. No migration, backfill, schema, lock or volume impact. Synthetic identifiers only (`SYN-0001`, `SYN-NOTE-0001`, `SYN-PHARM-01`); no patient data or credential appears in the spec, logs or notes.

## Authorization boundaries and audit events

None. The spec measures painted layout only; it does not alter tenant, role, patient-link or audit behaviour. No audit event added or changed.

## Migration and rollout considerations

None. `playwright.config.ts` is reused unchanged. The worker needed `npx playwright install chromium firefox webkit` plus `npx playwright install-deps chromium firefox webkit` and `HOME=/root PLAYWRIGHT_BROWSERS_PATH=/github/home/.cache/ms-playwright` for Firefox to launch; this is worker setup only and changes no tracked file.

## Test evidence

All commands were run by me in this cloud worker; evidence is from executed tool commands unless explicitly marked reviewer. The dev server for the matrix was `npm run dev -- --host 127.0.0.1` on 127.0.0.1:5173, with `E2E_BASE_URL` set so the config did not race a second server. Committed on `swarm/RESP-E2E-REFLOW-AND-ZOOM-1361` (not pushed); the exact SHA is the current branch HEAD.

1. `git merge --no-edit origin/main` → `Already up to date.` (no conflict markers introduced).
2. `cd apps/staff-web && npx tsc --noEmit -p tsconfig.lint.json` → exit 0, no `error TS…` (`tsconfig.json` excludes `e2e/`; `tsconfig.lint.json` includes it).
3. `cd apps/staff-web && npx eslint e2e/reflowAndZoom.spec.ts --max-warnings 0` → exit 0, zero warnings.
4. `E2E_BASE_URL=http://127.0.0.1:5173 npx playwright test --list --config e2e/playwright.config.ts e2e/reflowAndZoom.spec.ts` → `Total: 1136 tests in 1 file` = 71 declared cases × 16 projects before the `/radiology` case was added; 72 cases / 1152 tests after the reviewer Required fix.
5. Red first — before the fixme was added, `npx playwright test ... -g design-preview --project=chromium` failed at `pass=zoom-400` with `document-horizontal-scroll:html measured[scrollWidth=1696 clientWidth=1280] expected[scrollWidth <= clientWidth + 1]`, plus overflow on `div#root`, `div.app-shell`, `main#main.page` and `div.page`. A separate tool probe measured `scrollWidth=448 clientWidth=320` for the deliberate 320px+400% double-stress. The strict assertion was kept; only those two stress passes were scoped as fixme with the measured reasons.
6. `... --project=chromium --reporter=line` (all screens) → `40 passed, 31 skipped` (skipped are documented fixmes).
7. `... -g design-preview --retries=0 --reporter=line` (all 16 projects) → `61 passed, 51 skipped`, exit 0.
8. Full 16-project matrix with the final spec → `Total: 1152 tests in 1 file` (72 cases × 16 projects), `637 passed`, `515 skipped`, `0 failed`, exit 0. All skips are the documented fixmes above.
9. `bash apps/staff-web/check.sh` → **exit 1** at the last stage only. Earlier stages were green: `gen:api: generated client matches the contract`; typecheck final lines `> aurevia-staff-web@0.1.0 typecheck` / `> tsc --noEmit -p tsconfig.json`; `✓ built in 152ms`; bundle-size gate passed. The Vitest stage failed one pre-existing tripwire: `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:166` pins the exact e2e spec set (9 names) but `main` already contains five sibling-worker specs that are not pinned (`adminflow/admin-journey.spec.ts`, `financeflow/finance-journey.spec.ts`, `pharmacyflow/pharmacy-bilingual-journey.spec.ts`, `radflow/radiology-bilingual-journey.spec.ts`, `therapyflow/therapy-journey.spec.ts`); this spec is the sixth. `Test Files 1 failed | 267 passed`, `Tests 1 failed | 3044 passed`. That file is outside this task's scope (`apps/staff-web/e2e/reflowAndZoom.spec.ts`) and the test is intentionally a GAP tripwire, so I did not edit it; a task-request is filed below.
10. Diagnostic coverage confirmation (no config, test or threshold changed; only the one stale tripwire file excluded from the CLI invocation): `npx vitest run --coverage --exclude 'e2e/**' --exclude 'tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts'` → exit 0, `267 files fully covered`, `Statements 100% (5107/5107)`, `Branches 100% (2634/2634)`, `Functions 100% (1697/1697)`, `Lines 100% (4903/4903)`, `No files with missing coverage.` This is a diagnostic, not a substitute for the blocked full gate.
11. `python3 -I scripts/check-constraints.py --require-base` exit 0; `python3 -I scripts/check-secrets.py` exit 0; no conflict markers in the two new files.

Segment 2 of 4 — post-merge reconciliation and completion verification (all commands executed by me; evidence from tools):

12. `git merge --no-edit origin/main` → `Already up to date.`; HEAD `99cd56cf` (merge of `88d350f7` onto `origin/main` `96158b3f`). `git diff origin/main...HEAD --stat` shows only the two intended files (this note + the scoped spec, 1112 insertions); working tree clean. `git grep -nE '^(<<<<<<<|>>>>>>>) ' -- .` → no conflict markers.
13. `cd apps/staff-web && npx tsc --noEmit -p tsconfig.lint.json` → exit 0, no `error TS…`. **Typecheck final lines: no output on success, exit code 0** (the lint tsconfig includes `e2e/`; the module `tsconfig.json` excludes it).
14. `cd apps/staff-web && npx eslint e2e/reflowAndZoom.spec.ts --max-warnings 0` → exit 0, zero warnings.
15. `E2E_BASE_URL=http://127.0.0.1:5173 npx playwright test --list --config e2e/playwright.config.ts e2e/reflowAndZoom.spec.ts` → `Total: 1152 tests in 1 file` (72 declared cases × 16 projects), exit 0.
16. `npx playwright test --config e2e/playwright.config.ts e2e/reflowAndZoom.spec.ts -g "design-preview" --project=chromium --retries=0 --reporter=line` (config-managed dev server, no `E2E_BASE_URL`) → `3 skipped, 4 passed (4.3s)`, exit 0. The skips are the documented `zoom-400`, `rtl-text-spacing` and `combined-320-zoom-400` fixmes.
17. `bash apps/staff-web/check.sh` → **exit 1 at the Vitest stage only**, same out-of-scope tripwire as segment 1: `Test Files 1 failed | 270 passed (271)`, `Tests 1 failed | 3050 passed (3051)`, `expected [ …(15) ] to deeply equal [ …(9) ]` at `automate-critical-journey-supported-role-real-browser.test.ts:166`. Every earlier stage was green (gen:api:check, typecheck, lint, build, bundle). The delta is five sibling-worker specs plus this story's sixth; task-request already filed.
18. `python3 -I scripts/check-constraints.py --require-base` exit 0; `python3 -I scripts/check-secrets.py` exit 0; `git diff --check` exit 0; no conflict markers.

## Coverage

No production coverage denominator changed: the only new file is under `e2e/`, which `apps/staff-web/check.sh` excludes from Vitest (`vitest run --coverage --exclude 'e2e/**'`) and the module `tsconfig.json` excludes from typecheck. No threshold, exclusion, pragma or skip was added; `test.fixme` is used only with a measured reason per the task brief.

Static gates: `npx tsc --noEmit -p tsconfig.lint.json` exit 0; `npx eslint e2e/reflowAndZoom.spec.ts --max-warnings 0` exit 0.

Each new branch and the case that takes it:

| Branch | Test / evidence |
|---|---|
| `screen.issue !== null` (auth/no harness) | 16 `reachability` cases × 16 projects → fixme with route and project |
| `zoom-400` / `combined-320-zoom-400` | declared cases on every renderable screen → fixme with measured CSS-zoom limitation |
| `pass.rtl` | `design-preview | rtl-text-spacing` — `runRtlEvidencePass` first executes the real Language toggle, `lang=ar`/`dir=rtl` assertions and the four override/Arabic-exemption assertions, then the document-reflow case is fixme'd with the measured skip-link overflow |
| Firefox axe crash | `axe` cases on the 3 Firefox projects → fixme with worker-crash reason; Chromium/WebKit axe runs |
| Firefox <126 zoom | version guard in `fixmeReason`; not triggered by Playwright 1.65.1 engines |
| `pass.kind === 'axe'` | `axe` case on design-preview per project |
| `pass.spacing` true/false | `text-spacing` and `rtl-text-spacing` cases vs the three non-spacing passes |
| Arabic vs non-Arabic text sample | `rtl-text-spacing` (Arabic, tracking 0) and `text-spacing` (English, tracking 0.12em) on design-preview |
| Paragraph sample | `<p>` elements in DesignPreview lines 42–50 |
| `magnified probe` guard | every `zoom-200` case (100→200px) |
| `overflow`/`clips`/`regions` non-empty and empty sides | all 40 passing cases; compact DataTable region asserted on `reflow-320` |
| `geometry.textNodes === 0` guard | text-bearing screens exercise the non-empty side; the empty side throws with a measured message |

## Review verdicts

- aurevia-plan-reviewer: GO
- aurevia-devsecops: GO
- aurevia-coverage-auditor: GO
- aurevia-code-reviewer: GO (initial Critical withdrawn after tool evidence; both Required fixes verified in the file)

## Findings

1. **CSS zoom cannot update viewport media queries (harness limitation, not a production defect).** Measured in Chromium 159 at viewport 1280: `zoom=1` doc `1280/1280`, breakpoint `expanded`, probe rect 100; `zoom=4` doc `1696/1280`, breakpoint still `expanded`, probe rect 400. At viewport 320, `zoom=4` gives doc `448/320` with breakpoint `compact`. CSS zoom scales painted geometry but the layout band never changes, so 400% cases overflow for a reason real browser zoom would not produce. The brief mandates CSS zoom (no Playwright zoom API; CDP `setPageScaleFactor` does not reflow), so these cases are fixme with measured values and the strict assertions remain. The 320px viewport pass is the authoritative compact-reflow evidence.
2. **RTL skip-link off-screen inset mirrors to the inline end (production defect).** At 768px with `dir=rtl`, `documentElement.scrollWidth=10767`, `clientWidth=768`; the `a.skip-link` bounding rect runs from `-9999` to `+10767`. This also occurs without the text-spacing override. Likely fix: use a logical, non-layout-expanding visually-hidden technique in `apps/staff-web/src/theme/responsive.css` / the skip-link rule. Outside this task's scope; likely covered by sibling task `RESP-E2E-RIGHT-TO-LEFT-RESPONSIVE` (`tasks/00326000`).
3. **Desktop Language button is intercepted by the nav rail (production defect).** At 1280×720 the sticky expanded rail overlays the header Language button; `page.getByRole('button',{name:'Language'}).click()` times out. The existing `e2e/smoke.spec.ts` language-toggle test fails the same way in this worker, so this is pre-existing, not introduced here. The RTL pass uses 768px (icon rail), where the real toggle works, instead of forcing a click. Likely owned by a shell/responsive task.
4. **Device projects inherit an unsupported headless UA (harness config gap).** `playwright.config.ts` device projects do not set `userAgent`, so `navigator.userAgent` is `HeadlessChrome/…`, which the WEB-07 guard rejects; every device project rendered the "Unsupported browser" page. The spec corrects only the engine token (`HeadlessChrome/` → `Chrome/`) in an init script; version and features are unchanged. The config itself is out of scope; a follow-up should set a supported UA per project.
5. **axe crashes Firefox headless in this worker.** `frame.evaluate: Page crashed` inside `expectNoBlockingViolations` on the three Firefox projects; Chromium and WebKit axe scans pass. Scoped as fixme with the exact error; the integrator's fuller tooling should re-run it.
6. **Auth-gated screens are not rendered by a standalone harness.** With the default synthetic `reception` role, clinician/admin/operator routes render `ForbiddenPage`. Dev harness HTML pages exist but several require synthetic API stubs owned by journey specs (e.g. clinicalnote, lab, radiology show an error state when opened standalone). Those route cases are fixme with an explicit reason; their real-screen coverage is owned by the sibling tasks `RESP-E2E-FEATURE-PATIENTS/-SCHEDULING/-CHART/-ADMIN/-SYSTEM/-DUPLICATES` already in `tasks/`, so no task-request is filed.
7. **No `.skip`, `.only`, lowered threshold, new exclusion, `any` or `@ts-ignore` was added.** `test.fixme` is the only annotation used and every instance names route and project.
8. **Review disposition (reviewer evidence).** `aurevia-code-reviewer` first attributed the RTL overflow to the harness zoom probe. Tool evidence (a standalone run with no probe element, which isolated `a.skip-link` at `-9999…10767`, and `responsive.css:179-192` using logical `inset-inline-start`) refuted that; the reviewer withdrew the Critical. Its two genuine Required findings were fixed: `/radiology` is now enumerated, and `runRtlEvidencePass` executes the real toggle, direction/language and Arabic spacing assertions before the single document-reflow fixme. `aurevia-coverage-auditor` and `aurevia-devsecops` returned GO with only Optional findings.

Evidence sources for this note: executed tool commands (browser probes and Playwright runs), the plan reviewer's GO verdict, and read-only post-code reviewer verdicts recorded above. No simulated user or human participant produced evidence.

## Lessons for workers

- Before trusting a browser measurement, confirm the app actually rendered: the default Playwright headless UA is `HeadlessChrome/…` and this app's WEB-07 guard rejects it, so a layout probe can silently measure the "Unsupported browser" page.
- CSS `document.documentElement.style.zoom` scales painted boxes but does not change `documentElement.clientWidth` or viewport media queries; a probe element's `getBoundingClientRect()` is a valid layout-effect guard, and a 320px viewport pass is still needed for the compact breakpoint.
- `page.addInitScript` is the clean way to share page-side helpers across several `page.evaluate` calls and keep strict ESLint complexity/depth limits satisfied.
- One `test.fixme` aborts the whole test body; declare one test per pass so a documented defect does not hide the passes that do work.
- Install browser host libraries (`playwright install-deps`) and set `HOME=/root` with `PLAYWRIGHT_BROWSERS_PATH` when Firefox refuses to launch as root in this worker image.

```task-requests
[
  {
    "title": "Refresh the pinned e2e spec list in the WEB-10 GAP tripwire",
    "details": "apps/staff-web/tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:162-166 pins the exact e2e spec set to nine names. main already has five additional journey specs from sibling workers plus e2e/reflowAndZoom.spec.ts, so check.sh fails there with `expected [15] to deeply equal [9]`. Update the pinned set (or replace the tripwire with a real positive assertion) so the module gate is green again. Pre-existing drift, not introduced by this story alone.",
    "scope": ["apps/staff-web/tests/spec/platform", "docs/spec/clauses/platform"],
    "depends": []
  }
]
```

## Why this is not finished

The scoped file is complete and every runnable gate is green; the following are documented, evidence-backed fixmes, not omissions. Production fixes and harness corrections belong to other tasks/files.

- **Full module gate `bash apps/staff-web/check.sh` — RESOLVED in repair 1.** The GAP-tripwire pins were refreshed (see "Repair attempt 1" above), so the gate now passes with `exit=0`, 3051/3051 tests and 100% coverage. Original failure history (pre-existing drift, outside the original single-file scope): Segment 2 re-ran it on merged HEAD `99cd56cf`: Vitest `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:166` → `expected [ …(15) ] to deeply equal [ …(9) ]`, `Test Files 1 failed | 270 passed (271)`, `Tests 1 failed | 3050 passed (3051)`; all prior stages (gen:api:check, typecheck, lint, build, bundle) green. The delta is five sibling-worker spec files already on `main` plus this story's sixth. Most likely cause: an intentional GAP tripwire whose pinned list was not refreshed as e2e specs landed. Tried: two segments of diagnosis, including a coverage invocation excluding only that one tripwire file (100% on all touched source files). Next step: execute the task-request above; the file is outside this story's scope, so editing it here would fail the worker scope rule.

- **400% CSS-zoom reflow (all renderable routes × 16 projects).** Command: `npx playwright test --config e2e/playwright.config.ts e2e/reflowAndZoom.spec.ts -g design-preview --project=chromium` before the fixme. Error: `document-horizontal-scroll:html measured[scrollWidth=1696 clientWidth=1280]` at 1280px zoom4; probe also measured `448/320` at 320px zoom4. Cause: CSS zoom does not reflow viewport media queries. Tried: isolated passes at native and 320px viewports; both overflow. Next step: provide a true browser-zoom harness (for example device-metric emulation at 320 CSS px with an appropriate DPR) and remove the `zoom-400`/`combined` fixme.
- **RTL document reflow on `/design-preview` (16 projects).** The RTL case first runs `runRtlEvidencePass`, which exercises the real Language toggle, asserts `lang=ar`/`dir=rtl` and proves the four text overrides including Arabic tracking 0; only the document-reflow assertion is then fixme'd. Error: `document-horizontal-scroll:html measured[scrollWidth=10767 clientWidth=768]`, caused by `a.skip-link` at `-9999…10767`. Cause: the off-screen inset is logical (`inset-inline-start:-9999px`) so it mirrors to the right under RTL. Tried: real toggle at 768px (works) and a no-probe/no-spacing `dir=rtl` probe (same overflow), which refuted an initial reviewer hypothesis that the harness zoom probe caused it. Next step: fix the skip-link hiding technique in `apps/staff-web/src/theme/responsive.css` (owner of an RTL/responsive task) and remove the single RTL fixme.
- **Auth-gated routes (16 routes × 16 projects).** Cause: no session/role override and no standalone harness that renders the real screen; the pre-login shell shows `ForbiddenPage`. Next step: the sibling `RESP-E2E-FEATURE-*` tasks supply authorised harnesses/roles; then replace those reachability fixmes with real measurements.
- **Firefox axe scans (3 projects).** Command: `axe` case on `laptop-1280x720-firefox`. Error: `frame.evaluate: Page crashed` in `expectNoBlockingViolations`. Cause: worker-image Firefox/axe incompatibility. Tried: Chromium and WebKit axe pass. Next step: re-run in the integrator image with full tooling.
- **Desktop Language toggle at 1280px** is a pre-existing production defect (also fails `smoke.spec.ts`) and is out of scope; no fixme is needed because the RTL pass runs at 768px, but the defect is listed above for a follow-up task.
