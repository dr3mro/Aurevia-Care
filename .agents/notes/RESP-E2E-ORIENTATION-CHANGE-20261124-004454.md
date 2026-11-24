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

aurevia-senior-react (staff web), aurevia-senior-typescript (strict TS), aurevia-test-engineer (assertions), aurevia-plan-reviewer, aurevia-code-reviewer, aurevia-coverage-auditor, aurevia-devsecops. All are read-only reviewers; the primary agent owns the edit and the commit.

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

Repair attempt 1 (tool evidence) — after updating the stale `currentSpecs` pin to the full 15-spec set:

- `npx vitest run tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts --reporter=dot` → `Test Files  1 passed (1)`, `Tests  3 passed (3)`, exit 0. This is the previously failing tripwire; it is now green.
- `bash apps/staff-web/check.sh` → exit 0. Final lines: `Test Files  268 passed (268)`, `Tests  3045 passed (3045)`, coverage `Statements 100% (5107/5107)`, `Branches 100% (2634/2634)`, `Functions 100% (1697/1697)`, `Lines 100% (4903/4903)`, `No files with missing coverage`, `267 files fully covered, 8 empty files skipped`. The `jsdom "Not implemented: navigation"` stack traces in the log are pre-existing console noise from unrelated `UnsupportedBrowserPage` tests; they are not failures (the gate exited 0).
- `python3 -I scripts/check-constraints.py --base origin/main --require-base` → exit 0. The change is a stricter artifact pin (15 paths vs 9) plus a doc comment; no suppression, exclusion, skip or lowered threshold was added.

The canonical frontend gate is now green on local evidence.

Entry point → test trace: `page.goto(route)` → Vite dev server started by `webServer` in `e2e/playwright.config.ts` → `src/main.tsx` (client-support check and `resize`/`orientationchange` re-render) → `App` → `AppShell`/`RouterProvider` → the registered route component. The spec then asserts shell rendering (`main#main`, `#active-organization`), the exact unsupported heading is absent, the focused element id (`document.activeElement`), the pathname from `page.url()`, and both unsaved form values, before and after `page.setViewportSize(target)`.

Reviewer evidence is read-only: `aurevia-plan-reviewer` reviewed the plan twice (first NO-GO with two Required gaps, both fixed, then GO), and `aurevia-code-reviewer`, `aurevia-coverage-auditor` and `aurevia-devsecops` reviewed the finished file. No human, clinical or patient evidence was used.

## Coverage

No production source file was added or changed, so "100% coverage of changed production source" is **N/A** and is stated rather than fabricated. No threshold, exclusion or generated-file rule was changed.

Diagnostic coverage command and result (run while the stale tripwire was still red; the canonical `npm run test:coverage` is green after repair attempt 1, with identical 100% totals):

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

### Repair attempt 1 — branch-to-test list

The coverage gate failed on the WEB-10 spec-set tripwire, not on an uncovered production line: `automate-critical-journey-supported-role-real-browser.test.ts:166` compared the on-disk spec set (15) against a stale `currentSpecs` pin (9). No production source was changed, so no new production branch exists to map to a test. The change updates that test's own expected-set pin to the full 15 paths (a stricter assertion) and documents it. Each changed pin entry is exercised by the single tripwire assertion `expect(specs.sort()).toEqual([...currentSpecs].sort())`, backed by a real filesystem read via `e2eSpecs()` → `filesBelow(e2e)`:

| Changed element (pin entry) | Test that takes it |
|---|---|
| `adminflow/admin-journey.spec.ts` | `GAP: all-role, staging-RC and light/dark visual evidence is not yet recorded` — set equality, file read by `e2eSpecs()` |
| `financeflow/finance-journey.spec.ts` | same tripwire assertion |
| `orientationChange.spec.ts` (this story's new spec) | same tripwire assertion |
| `pharmacyflow/pharmacy-bilingual-journey.spec.ts` | same tripwire assertion |
| `radflow/radiology-bilingual-journey.spec.ts` | same tripwire assertion |
| `therapyflow/therapy-journey.spec.ts` | same tripwire assertion |
| The 9 pre-existing pin entries (unchanged) | same tripwire assertion, still guarded |

The negative side of the equality (a spec added or removed without updating the pin) is what makes the tripwire fail; restoring the pin to the true 15-file set makes the positive side pass. `check-constraints.py` exit 0 confirms no threshold/exclusion change. The other two tests in the file (`browserJourneysCoverSupportedRolesBilinguallyWithBlockingAxeGate`, `wrongRoleIsRefusedAnotherRolesJourney`) are unchanged and pass.

Playwright execution is a remaining verification gap (no browsers installed locally); `--list` proves collection only. See Why this is not finished.

## Clinical and architecture review

Not required. No signed data, cross-organization sharing, patient association, prescribing, critical result or release-of-information behavior changed. No clinical or human acceptance evidence is claimed; the fixture values are synthetic.

## Review verdicts

- aurevia-plan-reviewer: GO
- aurevia-code-reviewer: GO
- aurevia-coverage-auditor: GO
- aurevia-devsecops: GO

## Findings

- **Stale WEB-10 tripwire — FIXED in repair attempt 1.** `apps/staff-web/tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:86,166` pinned `currentSpecs` to 9 e2e specs, but `origin/main` already tracked 14 and this story made 15. Reproduced with this story's file removed: still 1 failed | 2 passed (expected 9, received 14), so it was a stale pin, not a regression of this diff. History shows the pin was stale at creation (commit `6951647b` added the tripwire after commit `4698687f` had added five more specs). The coverage repair loop authorized fixing it; the pin now lists all 15 specs, the tripwire passes 3/3, and `bash apps/staff-web/check.sh` is green. The first task request below is now resolved.
- **No local runtime evidence for the E2E spec.** No Playwright browser binaries are installed; `npx playwright test --config e2e/playwright.config.ts e2e/orientationChange.spec.ts --list` collected 70 tests, but no test executed. A grep of `.github/workflows` found no Playwright/e2e workflow, so no CI job currently runs this suite either. The rotation assertions themselves (including `isMobile` projects' `setViewportSize` → `window.innerWidth`) still need one execution run in a browser-enabled job.
- **UX-01 RTL rotation not covered.** `playwright.config.ts` has no Arabic/RTL project, so this spec asserts only the exact English unsupported heading. The Language toggle in `smoke.spec.ts` shows an RTL case is technically possible without a config change, but it is outside this story's one-file scope; filed as a follow-up task request below.
- **Test-only fail-fast guards are not covered by a named test.** The unknown-viewport and null-viewport `throw` paths in the new spec fire only if the fixed device matrix changes; they are scaffolding for a loud failure, not production branches, and no coverage gate measures them.
- **No dependency or static security scan was run or claimed.** The change adds no dependency and no production code; OWASP Dependency-Check and SpotBugs have nothing in scope. DevSecOps reviewed the artifact and returned GO.

## Lessons for workers

- Before adding a new e2e spec, update the WEB-10 spec-set tripwire in `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts` in the same change: it pins the exact file list, so any new spec makes `apps/staff-web/check.sh` red until the pin is refreshed. The pin was stale from 9 to 15 paths on `main` and is now current.
- In cloud workers Playwright browsers are often absent: `npx playwright test --list` proves collection only. Disclose that plainly instead of implying the assertions executed.
- The synthetic `#active-organization` select is mounted in the shell on every route and at every breakpoint, so it is a stable focus and form-value target for shell-level rotation invariants; `#shift-note` is the controlled text field that carries the real WEB-09 typed-work evidence on the development preview.

## Why this is not finished

Only one environmental verification gap remains; the canonical frontend gate is green on local evidence after repair attempt 1.

- **Resolved in repair attempt 1:** the `npm run test:coverage` failure at `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:166` (`AssertionError: expected [ …(15) ] to deeply equal [ …(9) ]`) was a stale `currentSpecs` pin. The pin now lists all 15 e2e specs; the tripwire passes 3/3 and `bash apps/staff-web/check.sh` exits 0 with `268 passed (268)` test files, `3045 passed (3045)` tests and 100% statements/branches/functions/lines. `check-constraints.py --base origin/main --require-base` exits 0 (no lowered threshold, exclusion or skip).
- **Remaining gap (environmental, not a coverage failure):** no Playwright browser binary is installed in this sandbox, so `npx playwright test --config e2e/playwright.config.ts e2e/orientationChange.spec.ts --list` collected 70 tests but none executed. No `.github/workflows` job currently runs the Playwright suite. The rotation assertions still need one runtime execution in a browser-enabled job.
- **Exact next step:** in a job with browsers installed, run `npx playwright test --config e2e/playwright.config.ts e2e/orientationChange.spec.ts` and confirm the 70 route × project cases pass; the coverage and policy gates need no further change.

## task-requests

```json
[
  {
    "title": "Add Arabic RTL rotation coverage to the staff-web E2E suite",
    "details": "UX-01 requires RTL support, but playwright.config.ts has no Arabic/RTL project and the orientation story was limited to one spec file. Add a follow-up case (or project) that toggles the Language control to Arabic, asserts dir=rtl, rotates the viewport and asserts preserved route, focus and unsaved text, with the Arabic unsupported heading from apps/staff-web/src/features/system/messages.ts:40 as the negative. Do not edit protected config in a worker change without an owner decision.",
    "scope": ["apps/staff-web/e2e/"],
    "depends": []
  }
]
```
