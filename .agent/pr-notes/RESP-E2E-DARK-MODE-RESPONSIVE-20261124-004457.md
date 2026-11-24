# RESP-E2E-DARK-MODE-RESPONSIVE — staff web dark-mode / responsive end-to-end spec

## Status

The scoped deliverable is complete: `apps/staff-web/e2e/darkModeResponsive.spec.ts`
is committed on this branch and was re-verified after merging `origin/main` at
`25d93b44`. The spec is green (207 passed, 23 evidence-based fixmes), typecheck,
lint and 100% coverage are green, and the three production dark-mode defects plus
the stale WEB-10 GAP tripwire are documented below as out-of-scope follow-ups with
task-requests. No production source, config, dependency or tracker file was changed.

## Summary

Adds one Playwright specification that exercises the staff shell and one
representative route per feature module in light and dark mode at the supported
phone, tablet and desktop widths. It asserts the `data-theme` flip in both
directions, WCAG text contrast, a visible keyboard focus ring, no horizontal
document overflow and the shared WCAG gate. Synthetic data only; no production
source, config, documentation, dependency or tracker change.

## Affected requirements

- WEB-07 — supported clients and the compact layout; the configured browser/viewport matrix.
- UX-01 — WCAG 2.2 AA on staff web and flexible presentation styling.
- WEB-10 — end-to-end verification with an accessibility scan and light/dark visual checks.

## Acceptance mapping

| Story step | Evidence in this change |
|---|---|
| 1. Read config, smoke spec and responsive design; list routes; synthetic data | `representativeRoutes` has one row for each of the 17 feature modules that own a route plus the two app pages and the catch-all (`print/routes.ts` owns none). Paths and ids are synthetic (`synthetic-1`); roles come from `routeAccessRoles`/`canAccessRoute` in `src/app/navigation.ts`. |
| 2. Light/dark at 390x844, 820x1180, 1280x720 on shell and one screen per feature; contrast and focus rings visible | Per-route tests run in all 10 configured projects (Chromium at 9 device viewports including the three named sizes). Each asserts `data-theme` in both directions, text contrast ≥ 4.5:1, visible focus outline and zero horizontal document overflow; dedicated tests assert dark focus-ring contrast ≥ 3:1 and run the shared axe gate. |
| 3. One test per route and project; actionable messages; real defects isolated, not weakened | 23 tests × 10 projects = 230; every assertion message names route, project, mode and the measured value/colours. The three measured real defects are isolated in three `test.fixme` blocks with their ratios and selectors; no threshold, rule or assertion was relaxed. |
| 4. Run the Playwright suite with real browsers | Chromium was installed; `npx playwright test ... --retries=0` → 207 passed, 23 skipped (fixme), EXIT=0. |
| 6. Run `bash apps/staff-web/check.sh` | Ran: typecheck, lint, build and bundle budget pass; the only failing test is the pre-existing out-of-scope WEB-10 GAP tripwire in `tests/`, which pins 9 e2e specs while `main` has 15. 100% coverage re-proven post-merge (see Coverage). |

## Risk class

Cross-boundary (browser-level verification across all staff-web feature routes),
with no contract, data, migration or authorization boundary change.

## Review owner

smartclinic-senior-react, smartclinic-senior-typescript, smartclinic-test-engineer;
read-only gates by smartclinic-plan-reviewer, smartclinic-code-reviewer,
smartclinic-coverage-auditor and smartclinic-devsecops.

## Data changes

None. No migration, backfill, lock, volume impact, rollback or forward fix.

## Authorization boundaries and audit events

None changed. The spec reads the existing `canAccessRoute` decision to choose a
role that grants each representative route, so a forbidden page is never
mistaken for a feature screen. No audit event added.

## Migration and rollout considerations

None. Test-only change; no deployment or rollout impact.

## Test evidence

Evidence source labels: all commands below were executed directly by the worker
in this checkout (tool evidence). Reviewer verdicts are labelled separately
under `## Review verdicts`. No simulated-user or human-participant evidence was
used for this browser-level visual check.

Entry point to test trace: `page.goto(<synthetic route>)` → real Vite dev app
`src/main.tsx` → `App`/`RouterProvider`/`AppShell` → lazy `ActivePage` → visible
`Theme` button → `ThemeProvider` writes `<html data-theme>` → `page.evaluate`
reads computed styles → `expectVisualState` assertions / `expectNoBlockingViolations`.

Commands and final lines:

- `npx tsc --noEmit -p tsconfig.json` → no output, `TYPECHECK_EXIT=0`.
- `npx tsc --noEmit -p tsconfig.lint.json` (the project that actually includes `e2e`) → no output, `LINT_TSC_EXIT=0`.
- `npx eslint . --max-warnings=0` → no output, `ESLINT_EXIT=0`.
- `npx eslint e2e/darkModeResponsive.spec.ts --max-warnings=0` → no output, `ESLINT_EXIT=0`.
- `npx playwright test --config e2e/playwright.config.ts e2e/darkModeResponsive.spec.ts --list` → `Total: 230 tests in 1 file` (23 tests × 10 configured projects: `chromium`, 9 device viewports; WebKit/Firefox projects are omitted by the config because those engines are absent).
- `E2E_BASE_URL=http://127.0.0.1:5173 npx playwright test --config e2e/playwright.config.ts e2e/darkModeResponsive.spec.ts --retries=0 --workers=4` → `23 skipped`, `207 passed (57.6s)`, `EXIT=0`. (`E2E_BASE_URL` was supplied because a dev server was already bound to 5173 in this worker.)
- Canonical CI command (no server prefix, config manages the dev server): `npx playwright test --config e2e/playwright.config.ts e2e/darkModeResponsive.spec.ts`.
- `bash apps/staff-web/check.sh` → `Test Files  1 failed | 267 passed (268)`, `Tests  1 failed | 3044 passed (3045)`, `CHECK_EXIT=1`. The single failure is the out-of-scope WEB-10 GAP tripwire described under `## Why this is not finished`; generated-client check, typecheck, lint, build and bundle budget all passed before it.
- Red evidence seen before the fixmes were added: `app /: ... dark: focus-ring contrast 2.65:1 below 3:1 (rgb(11, 92, 171) on rgb(17, 24, 39)) on a.skip-link`; `/lab/turnaround-board` axe `color-contrast: Elements must meet minimum color contrast ratio thresholds`; expanded-width axe `color-contrast` on the full nav rail links (`#0000ee` on `#111827`, 1.88:1).

Playwright browsers were not installed in the worker image. `npx playwright install chromium` and `npx playwright install-deps chromium` were run (tool evidence) to obtain a real browser and system libraries, so the run above executed actual assertions rather than `--list` alone.

### Post-merge (segment 2) re-verification

After the branch merged `origin/main` (HEAD `25d93b44`), all applicable checks were
re-run in this checkout. Evidence source: executed tool commands, labelled below.

- `git diff origin/main...HEAD --stat` → only the two story files changed (this notes file and `apps/staff-web/e2e/darkModeResponsive.spec.ts`).
- `git log 1f514e13..HEAD -- apps/staff-web/src/` → no output: `main` changed no staff-web production source, so the 100% coverage result is unchanged.
- `npm run typecheck` (canonical; `tsc --noEmit -p tsconfig.json`) → no output, `TYPECHECK_EXIT=0`.
- `npx tsc --noEmit -p tsconfig.lint.json` (the project that includes `e2e`) → no output, `LINT_TSC_EXIT=0`.
- `npx eslint . --max-warnings=0` → no output, `ESLINT_EXIT=0`.
- `npx playwright test --config e2e/playwright.config.ts e2e/darkModeResponsive.spec.ts --retries=0` → `23 skipped`, `207 passed (1.4m)`, `PLAYWRIGHT_EXIT=0`. The config started its own Vite dev server (no `E2E_BASE_URL`), so this is the canonical wiring.
- `bash apps/staff-web/check.sh` → `Test Files  1 failed | 270 passed (271)`, `Tests  1 failed | 3050 passed (3051)`, `CHECK_EXIT=1`. The single failure is `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:166` (`expected [ …(15) ] to deeply equal [ …(9) ]`): the GAP tripwire pins 9 e2e specs while the merged tree has 15 (five added on `main` before this story plus this story's spec). All earlier stages (generated-client check, typecheck, lint, build, bundle budget) passed. This is a pre-existing red on `main` and is outside the assigned scope.
- Fresh coverage read-out (see Coverage) → 100% on all four metrics, `EXIT=0`.

## Coverage

- No production source changed (only the new `apps/staff-web/e2e/darkModeResponsive.spec.ts` and this notes file). Vitest coverage is unchanged by this story.
- `npx vitest run --coverage --exclude 'e2e/**' --exclude 'tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts'` (the one exclusion is the out-of-scope stale tripwire, not a threshold or an exclusion from coverage config) → `267 files fully covered. 8 empty files skipped.`; `Statements : 100% ( 5107/5107 )`, `Branches : 100% ( 2634/2634 )`, `Functions : 100% ( 1697/1697 )`, `Lines : 100% ( 4903/4903 )`, `COVERAGE_EXIT=0`.
- No new production branch exists to map to a test. The new file is a test spec: its route rows cover the light→dark and dark→light state flips, both theme measurement modes, forbidden-screen rejection, overflow and focus-outline visibility. The three real defects are each isolated in one `test.fixme` with the measured value; no threshold, rule or assertion was weakened and no test used `.skip`/`.only`. Playwright reports the 23 fixme instances as `23 skipped` in the run summary; e2e is outside the Vitest coverage scope, so this does not change the 100% figure above. Follow-up task 1 removes them once the production colours are fixed.
- Post-merge re-proof (segment 2, tool evidence): `npx vitest run --coverage --exclude 'e2e/**' --exclude 'tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts'` → `Test Files 270 passed (270)`, `Tests 3048 passed (3048)`, `267 files fully covered.`, `Statements : 100% ( 5107/5107 )`, `Branches : 100% ( 2634/2634 )`, `Functions : 100% ( 1697/1697 )`, `Lines : 100% ( 4903/4903 )`, `COV_EXIT=0`. The second command-line `--exclude` names only the known out-of-scope, pre-existing red tripwire file and was needed solely because Vitest suppresses the coverage table when any test fails; it is not a config exclusion, threshold change or skipped test, and no production file is excluded. `git log 1f514e13..HEAD -- apps/staff-web/src/` is empty, confirming no source change could alter these numbers.

## Clinical and architecture review

None required. No signed data, cross-organization sharing, patient association,
prescribing or critical-result behavior changed. The synthetic routes use opaque
identifiers only.

## Review verdicts

- smartclinic-plan-reviewer: GO
- smartclinic-test-engineer: GO
- smartclinic-senior-react: GO (first pass NO-GO only for the out-of-scope stale `check.sh` tripwire and pending verdict lines; final pass confirms no in-scope Critical or Required defect)
- smartclinic-senior-typescript: GO
- smartclinic-code-reviewer: GO (re-confirmed after the `origin/main` merge)
- smartclinic-coverage-auditor: GO (re-confirmed after the merge; optional path correction applied)
- smartclinic-devsecops: GO (re-confirmed after the merge)

## Findings

Evidence-based, with exact paths:

1. `apps/staff-web/src/theme/responsive.css:174-177` — the `:focus-visible` outline uses `var(--sc-color-focus-ring, #0b5cab)`; no dark value is ever defined. On the dark surface `#111827` (`src/theme/tokens.ts:24`) the ring measures 2.65:1, below the WCAG 1.4.11 3:1 floor. `tokens.ts:19,28` stores an amber box-shadow string that is not applied. The dedicated focus-ring test is `test.fixme` with the measurement in all 10 projects.
2. Expanded nav rail links (`src/app/AppShell.tsx:66-73`, `FullRailLink`, rendered at ≥1280 px) use the browser default link colour `rgb(0, 0, 238)` on `#111827` = 1.88:1, reported by axe as a serious `color-contrast` violation. Compact and medium bands are unaffected (labels hidden / drawer closed). The dark-shell axe test is `test.fixme` in the 3 expanded projects.
3. `src/features/labboard` overdue status (`p[data-testid="turnaround-status"]`) uses `#b3261e` on `#111827` = 2.71:1, axe serious `color-contrast`. The dark turnaround-board axe test is `test.fixme` in all 10 projects.
4. Medium-width (820 px) layout: on tall clinician screens the icon rail overlaps the header and intercepts pointer clicks on the visible Theme button (measured rail box `24,8 48x1610.6875`, Theme button `40,213.875 59.48x44`, `document.elementFromPoint` at the button returned a rail link). `toggleTheme` activates the same visible button with keyboard focus + Enter so this unrelated layout defect cannot block the visual checks; no contrast or overflow assertion was relaxed. A follow-up task should address the rail overlap.
5. `e2e/playwright.config.ts:121-127` device projects inherit the headless-shell `HeadlessChrome/...` user agent, which `src/features/system/browserSupport.ts` correctly rejects before the app mounts. The spec supplies one synthetic supported Chrome UA through `test.use({ userAgent })` in the test file only, so the real shell renders at those viewports. A follow-up task should set a supported UA per project in the shared config.
6. The brief says 18 feature route modules; the repository has 17, because `src/features/print/routes.ts` deliberately declares no routes. The matrix therefore has one row per module that actually owns a screen (19 rows including app pages and the catch-all).
7. `bash apps/staff-web/check.sh` red is outside the assigned scope — see `## Why this is not finished`.
8. Post-merge reviewers raised only Optional findings, all acknowledged as documented tradeoffs or out-of-scope follow-ups: the turnaround fixme keys on the measured colour literal (a colour fix would make the test fail for a different reason until the fixme is removed); the shared axe gate runs on the dark shell and turnaround board only, while per-route checks sample one text element each; `e2e/accessibility.ts` failure messages list axe rules without route/project context (the per-route `expectVisualState` and fixme messages do); the 820 px rail-overlap workaround uses keyboard activation rather than a dedicated pointer regression test; and the file-level Chrome UA would apply to any installed WebKit/Firefox project. None weakens an assertion or gate, and each needs a scoped follow-up rather than a change to this spec.

## Lessons for workers

- Before writing a browser test in this repo, check `isSupportedBrowser` against the Playwright project's effective user agent: the device projects inherit `HeadlessChrome/...` and render the unsupported-client page, not the app.
- When a real dark-mode defect is systemic, isolate it in one dedicated `test.fixme` with the measured value instead of skipping every route case; keep the per-route visual assertions active.
- Read the WEB-10 GAP tripwire before adding an e2e spec: it pins the exact spec inventory and asserts that no visual-check vocabulary exists, so it is expected to flip when this kind of story lands. Its update is a separate, authorized task.
- Use keyboard activation for shell controls on medium-width screens where a tall icon rail can intercept pointer clicks; programmatic focus plus Enter still drives the real button and its handler.
- Reuse `canAccessRoute` from `src/app/navigation.ts` rather than hard-coding a role matrix; the pre-login shell's role is fixed and most feature routes would otherwise render ForbiddenPage.

## Why this is not finished

The scoped deliverable (the e2e spec) is green and complete: `207 passed`,
`23 skipped`, `EXIT=0`, with typecheck, lint and 100% coverage green after the
merge. The two items below are out-of-scope follow-ups, already requested with
`task-requests`; they are recorded here exactly as the brief requires so an
integrator can write them. They do not block acceptance of this spec.

1. **Canonical frontend gate is red on an out-of-scope tripwire.** `bash apps/staff-web/check.sh` exits 1 at `apps/staff-web/tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:166` (`expect(specs.sort()).toEqual([...currentSpecs].sort())`) and would also fail at its `visualChecks` assertion because this spec legitimately uses `getComputedStyle` and `data-theme`. Evidence that this is pre-existing: `git ls-tree -r --name-only HEAD apps/staff-web/e2e | grep '\.spec.ts$'` lists 14 specs, while `currentSpecs` at that file lines 86-96 pins only 9. The tripwire's own comment says it must be replaced by a positive assertion when the missing visual evidence lands. Updating it requires editing `tests/` (and possibly the clause note), which is outside this task's scope (`apps/staff-web/e2e/darkModeResponsive.spec.ts` only), so it was not changed. Next step: a task owning that test updates the tripwire to the real e2e matrix and clause evidence, then reruns `bash apps/staff-web/check.sh`.
2. **Three real dark-mode production defects remain unfixed** (focus ring, expanded nav-rail link colour, overdue status colour), each documented above and isolated in an evidence-based `test.fixme`. Production CSS is outside this task's scope, so no threshold or assertion was changed. Next step: fix the token/colour definitions and remove the three `test.fixme` blocks.

No other check fails. Typecheck, lint, the real Playwright run and 100% Vitest coverage were all observed green in this checkout.

```task-requests
[
  {
    "title": "Give the dark focus ring and dark rail/status colours compliant contrast",
    "details": "Define mode-aware focus and link/status colours so the focus ring clears 3:1 on the dark surface (#0b5cab on #111827 measured 2.65:1), expanded nav rail links clear 4.5:1 (default #0000ee measured 1.88:1) and overdue turnaround status clears 4.5:1 (#b3261e measured 2.71:1). Add unit/style tests. Then remove the three test.fixme blocks in apps/staff-web/e2e/darkModeResponsive.spec.ts.",
    "scope": ["apps/staff-web/src/theme/", "apps/staff-web/src/app/AppShell.tsx", "apps/staff-web/src/features/labboard/", "apps/staff-web/tests/theme/", "apps/staff-web/e2e/darkModeResponsive.spec.ts"],
    "depends": []
  },
  {
    "title": "Replace the WEB-10 GAP tripwire with positive e2e evidence assertions",
    "details": "The GAP test in tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts pins 9 e2e specs while main has 14 plus the new darkModeResponsive spec, and asserts no visual-check vocabulary exists. Update it to the real spec inventory and assert the light/dark visual spec and shared axe gate as positive evidence so bash apps/staff-web/check.sh is green.",
    "scope": ["apps/staff-web/tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts"],
    "depends": []
  }
]
```
