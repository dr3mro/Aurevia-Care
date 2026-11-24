# RESP-E2E-ORIENTATION-CHANGE — staff web orientationChange E2E

## Summary

Added one Playwright spec, `apps/staff-web/e2e/orientationChange.spec.ts`, that proves a phone or tablet rotating between portrait and landscape keeps the current route, the focused control and unsaved form values, and never swaps the application for the unsupported-browser notice (WEB-07, UX-01, WEB-09). One test is generated per data-free route (7) and Playwright runs it in every configured project (10), giving 70 route × project cases. Rotation is driven from each project's own starting viewport, so phone, tablet and desktop classes are all covered with no config change. No production source, dependency, config or tracker file was changed.

## Affected requirements

- WEB-07 — supported clients and the plain unsupported-browser notice (negative assertion and 320 px floor).
- UX-01 — responsive, flexible presentation; RTL rotation is explicitly out of scope here (see Findings).
- WEB-09 — preserved work: pathname, focus and unsaved text/selection survive the resize re-render.

## Risk class

Routine. Test-only addition: no production behavior, contract, migration, permission, tenant or patient-data change. The unsupported-notice negative is preserved and strengthened with a rendered-shell baseline.

## Review owner

smartclinic-senior-react (staff web), smartclinic-senior-typescript (strict TS), smartclinic-test-engineer (assertions), smartclinic-plan-reviewer, smartclinic-code-reviewer, smartclinic-coverage-auditor, smartclinic-devsecops. All are read-only reviewers; the primary agent owns the edit and the commit.

## Data changes

None. No migration, backfill, lock, volume impact, rollback or forward-fix.

## Authorization boundaries and audit events

None changed. Routes exercised are data-free shell/system pages and the development preview; no tenant, role, patient link or audit event is added. The synthetic organization selection (`SYN-FAC-01` fixtures) and note text (`SYN-0001`) are browser-local, unsaved inputs only.

## Migration and rollout considerations

None. The spec runs against the existing Vite dev harness; no deployment or feature flag is involved.

## Test evidence

Environment: no Playwright browser binary is installed in this sandbox (`~/.cache/ms-playwright` is absent; no `chrome`, `chromium` or `headless_shell` found). The runtime execution command could not be run; Playwright collection (`--list`) was run instead and this limitation is disclosed rather than hidden.

Commands run by the primary agent (tool evidence), with final lines:

- `git merge --no-edit origin/main` → `Already up to date.` (run at start, after the code stage and before finishing).
- `npx tsc --noEmit -p tsconfig.lint.json` → exit 0, no diagnostics. This project includes `e2e`; the module `check.sh` typecheck uses `tsconfig.json`, which does not, so both were run.
- `npx eslint e2e/orientationChange.spec.ts --max-warnings 0` → exit 0, no diagnostics.
- `npx playwright test --config e2e/playwright.config.ts e2e/orientationChange.spec.ts --list` → `Total: 70 tests in 1 file` (7 routes × 10 Chromium projects; no WebKit/Firefox binaries installed, so those conditional projects are absent).
- `bash apps/staff-web/check.sh` → exit **1**. Final lines: `Test Files  1 failed | 267 passed (268)` and `Tests  1 failed | 3044 passed (3045)`. The single failure is the pre-existing, out-of-scope tripwire below; every other stage of `check.sh` passed, as shown next.
- `npm run gen:api:check` → `gen:api: generated client matches the contract` (exit 0).
- `npm run typecheck` (`tsc --noEmit -p tsconfig.json`) → exit 0. Typecheck final lines: the command line `> tsc --noEmit -p tsconfig.json` followed by no diagnostics and exit 0.
- `npm run lint` (`eslint . --max-warnings 0`) → exit 0, zero warnings.
- `npm run build` → `✓ built in 171ms` (exit 0).
- `node scripts/check-bundle-size.mjs` → all chunks `ok` (exit 0).
- `npm run test:coverage` → exit 1 from the same single pre-existing tripwire; no other test failed.
- Diagnostic only (not a gate change): `npx vitest run --coverage --exclude 'e2e/**' --exclude 'tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts'` → `Test Files  267 passed (267)`, `Tests  3042 passed (3042)`; see Coverage.
- Proof of pre-existing red: with `e2e/orientationChange.spec.ts` temporarily moved aside, `npx vitest run tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts --reporter=dot` → `Test Files  1 failed (1)`, `Tests  1 failed | 2 passed (3)`, exit 1 (expected 9 spec paths, received 14). The file was restored immediately afterwards.
- `python3 -I scripts/check-constraints.py --base origin/main --require-base` → exit 0 (no suppression, exclusion, skip or lowered threshold).

Entry point → test trace: `page.goto(route)` → Vite dev server started by `webServer` in `e2e/playwright.config.ts` → `src/main.tsx` (client-support check and `resize`/`orientationchange` re-render) → `App` → `AppShell`/`RouterProvider` → the registered route component. The spec then asserts shell rendering (`main#main`, `#active-organization`), the exact unsupported heading is absent, the focused element id (`document.activeElement`), the pathname from `page.url()`, and both unsaved form values, before and after `page.setViewportSize(target)`.

Reviewer evidence is read-only: `smartclinic-plan-reviewer` reviewed the plan twice (first NO-GO with two Required gaps, both fixed, then GO), and `smartclinic-code-reviewer`, `smartclinic-coverage-auditor` and `smartclinic-devsecops` reviewed the finished file. No human, clinical or patient evidence was used.

## Coverage

No production source file was added or changed, so "100% coverage of changed production source" is **N/A** and is stated rather than fabricated. No threshold, exclusion or generated-file rule was changed.

Diagnostic coverage command and result (the canonical `npm run test:coverage` is red only on the pre-existing tripwire described above):

```
npx vitest run --coverage --exclude 'e2e/**' --exclude 'tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts'
```

`coverage/coverage-summary.json` totals: lines 4903/4903 (100%), statements 5107/5107 (100%), functions 1697/1697 (100%), branches 2634/2634 (100%), skipped 0.

Branch-to-test map for the new file (all are outcome assertions):

| Branch / path | Test that takes it |
|---|---|
| Each of the 7 routes (welcome, design preview, 4 system pages, catch-all) | One generated `test()` per route; 70 collected cases |
| Each of the 10 configured projects and all 9 viewport keys, including both rotation directions and 1280↔1920 desktop resize | Playwright runs every generated test in each project; `targetFor` maps the project viewport |
| `hasNoteField === true` (`#shift-note` typed text and focus) | Generated `/design-preview` case: fill and focus `#shift-note`, assert its value after rotation |
| `hasNoteField === false` (shell control focus) | The other 6 route cases: focus `#active-organization`, assert its value after rotation |
| Unsaved selection on every route | `selectOption(..., { index: 1 })` then assert the changed value survives rotation |
| Unsupported notice absent before and after rotation | `toHaveCount(0)` on the exact heading, backed by `main#main` and shell-control visibility |
| Unknown or null viewport fail-fast guard | Test-only scaffolding for a fixed 10-project matrix; not measured by any coverage gate (named here for honesty, not claimed as covered) |
| Real rotation happened | `expect.poll` on `window.innerWidth` and `window.innerHeight` equal to the target before any invariant is measured |

Playwright execution is a remaining verification gap (no browsers installed locally); `--list` proves collection only. See Why this is not finished.

## Clinical and architecture review

Not required. No signed data, cross-organization sharing, patient association, prescribing, critical result or release-of-information behavior changed. No clinical or human acceptance evidence is claimed; the fixture values are synthetic.

## Review verdicts

- smartclinic-plan-reviewer: GO
- smartclinic-code-reviewer: GO
- smartclinic-coverage-auditor: GO
- smartclinic-devsecops: GO

## Findings

- **Pre-existing out-of-scope red (blocks the canonical gate).** `apps/staff-web/tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:86,166` pins `currentSpecs` to 9 e2e specs, but `origin/main` already tracks 14 and this story makes 15. Reproduced with this story's file removed: still 1 failed | 2 passed (expected 9, received 14). History shows the pin was stale at creation (commit `6951647b` added the tripwire after commit `4698687f` had added five more specs). It is outside this task's single-file scope, so it was not edited. A task request is filed below.
- **No local runtime evidence for the E2E spec.** No Playwright browser binaries are installed; `npx playwright test --config e2e/playwright.config.ts e2e/orientationChange.spec.ts --list` collected 70 tests, but no test executed. A grep of `.github/workflows` found no Playwright/e2e workflow, so no CI job currently runs this suite either. The rotation assertions themselves (including `isMobile` projects' `setViewportSize` → `window.innerWidth`) still need one execution run in a browser-enabled job.
- **UX-01 RTL rotation not covered.** `playwright.config.ts` has no Arabic/RTL project, so this spec asserts only the exact English unsupported heading. The Language toggle in `smoke.spec.ts` shows an RTL case is technically possible without a config change, but it is outside this story's one-file scope; filed as a follow-up task request below.
- **Test-only fail-fast guards are not covered by a named test.** The unknown-viewport and null-viewport `throw` paths in the new spec fire only if the fixed device matrix changes; they are scaffolding for a loud failure, not production branches, and no coverage gate measures them.
- **No dependency or static security scan was run or claimed.** The change adds no dependency and no production code; OWASP Dependency-Check and SpotBugs have nothing in scope. DevSecOps reviewed the artifact and returned GO.

## Lessons for workers

- Before adding a new e2e spec, check the WEB-10 spec-set tripwire in `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts`; it pins the exact file list and is already stale on `main`, so `apps/staff-web/check.sh` cannot be green until an owner updates the pin.
- In cloud workers Playwright browsers are often absent: `npx playwright test --list` proves collection only. Disclose that plainly instead of implying the assertions executed.
- The synthetic `#active-organization` select is mounted in the shell on every route and at every breakpoint, so it is a stable focus and form-value target for shell-level rotation invariants; `#shift-note` is the controlled text field that carries the real WEB-09 typed-work evidence on the development preview.

## Why this is not finished

`bash apps/staff-web/check.sh` exits 1, so the canonical frontend gate is not green and this task is not claimed complete on local evidence.

- **What failed:** `npm run test:coverage` inside `bash apps/staff-web/check.sh`, one test: `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:166` — `AssertionError: expected [ …(15) ] to deeply equal [ …(9) ]`. Final lines: `Test Files  1 failed | 267 passed (268)`, `Tests  1 failed | 3044 passed (3045)`.
- **Most likely cause:** `currentSpecs` in that test is a stale pin (9 paths) while `origin/main` already tracks 14 e2e specs; this story's new spec makes 15. It is not a regression of this diff.
- **What was tried:** re-ran that single test with the new spec temporarily moved aside — still failed with expected 9 and received 14; ran every other `check.sh` stage individually (client drift, strict typecheck, lint, build, bundle budget) — all exit 0; obtained a 100% source-coverage report with only that pre-existing failing test excluded. The tripwire file is outside the task scope, so it was deliberately not edited.
- **Exact next step:** the owner or integrator updates `currentSpecs` in `apps/staff-web/tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts` to the full 15-spec set including `e2e/orientationChange.spec.ts` (or replaces the brittle pin with a dynamic assertion), then reruns `bash apps/staff-web/check.sh` and executes `npx playwright test --config e2e/playwright.config.ts e2e/orientationChange.spec.ts` in a job with browsers installed.

## task-requests

```json
[
  {
    "title": "Refresh stale WEB-10 e2e spec-set tripwire in staff web",
    "details": "apps/staff-web/tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:86 pins currentSpecs to 9 e2e specs, but origin/main already tracks 14 and RESP-E2E-ORIENTATION-CHANGE adds orientationChange.spec.ts (15). apps/staff-web/check.sh therefore fails at line 166 with expected 9 / received 15. Reproduced with the new spec removed (still 14 vs 9), so it is a pre-existing stale pin. Update the pin to the full set including e2e/orientationChange.spec.ts, or replace the brittle pin with a dynamic assertion, then rerun bash apps/staff-web/check.sh.",
    "scope": ["apps/staff-web/tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts"],
    "depends": []
  },
  {
    "title": "Add Arabic RTL rotation coverage to the staff-web E2E suite",
    "details": "UX-01 requires RTL support, but playwright.config.ts has no Arabic/RTL project and the orientation story was limited to one spec file. Add a follow-up case (or project) that toggles the Language control to Arabic, asserts dir=rtl, rotates the viewport and asserts preserved route, focus and unsaved text, with the Arabic unsupported heading from apps/staff-web/src/features/system/messages.ts:40 as the negative. Do not edit protected config in a worker change without an owner decision.",
    "scope": ["apps/staff-web/e2e/"],
    "depends": []
  }
]
```
