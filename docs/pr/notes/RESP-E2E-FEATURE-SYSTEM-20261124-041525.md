# RESP-E2E-FEATURE-SYSTEM — staff web system screens are responsive

## Scope and requirements

- Story: RESP-E2E-FEATURE-SYSTEM (task `tasks/00337000-RESP-E2E-FEATURE-SYSTEM.md`).
- Requirements: WEB-07 (supported clients, compact layout, browser/device matrix) and UX-01 (English and Arabic, RTL, WCAG 2.2 AA on staff web).
- Story file: `apps/staff-web/e2e/responsive-system.spec.ts` (new). The repair pass additionally updated two deterministic artifact-pinning platform tests (the e2e inventory set and the WEB-08 CI-enforcement proof) so the canonical gate is green; see Findings. No production code, dependency, tracker, workflow or protected file changed.
- Synthetic data only: no patient record, credential, real identifier or endpoint.

## Acceptance mapping

| Requirement | Acceptance | Where asserted |
|---|---|---|
| WEB-07 | Every system screen runs at phone, tablet and desktop projects; primary task by keyboard and tap/click | `routed system screens` and `standalone unsupported-browser notice` describes; touch-enabled copy test |
| WEB-07 | No horizontal document scroll | `expectNoHorizontalScroll` at every project, English on routed screens and both locales on the standalone notice; Arabic routed case is a documented fixme |
| WEB-07 | Values visible on desktop remain reachable on phone | `every desktop value is visible and reachable on a phone in English and Arabic` |
| UX-01 | English and Arabic, `lang`/`dir` mirrored, bilingual heading/detail/action | `screens` word table; `openScreen` asserts both locales |
| UX-01 | Accessible notice | `expectNoBlockingViolations` (axe serious/critical gate) from `e2e/accessibility.ts` |

The five screens are signed-out, session-expired, forbidden, not-found (shell routes) and the standalone unsupported-browser notice. `ForbiddenPage` and `SessionExpiredPage` take heading/detail/action from `src/components/stateViewMessages.ts` (h2); the other three take them from `src/features/system/messages.ts` (h1).

## Test evidence

1. Full Playwright device matrix (tool evidence):
   `cd apps/staff-web && E2E_BASE_URL=http://127.0.0.1:5173 npm run e2e -- e2e/responsive-system.spec.ts --retries=0 --workers=3`
   Final line: `20 skipped | 90 passed (2.9m)` — 10 projects (base chromium, nine device viewports), 11 cases each. WebKit/Firefox variants are omitted by `playwright.config.ts` because those binaries are not installed in this worker; Chromium headless shell plus `npx playwright install-deps chromium` were installed locally to run the suite.
2. Strict TypeScript over e2e files: `cd apps/staff-web && npx tsc --noEmit -p tsconfig.lint.json`
   Final line: no output, `TSC_EXIT=0`.
3. Lint: `cd apps/staff-web && npx eslint e2e/responsive-system.spec.ts --max-warnings=0`
   Final line: no output, `ESLINT_EXIT=0`.
4. Canonical module check (pre-repair): `cd apps/staff-web && bash check.sh`
   Final lines: `Test Files  2 failed | 276 passed (278)` / `Tests  2 failed | 3140 passed (3142)`, `CHECK_EXIT=1`. Both stale-pinning failures are described under Findings 2 and 3 and were resolved in the repair pass; the post-repair coverage gate is item 7.
5. Markdown check: `python3 -I scripts/check-docs.py` → `DOCS_EXIT=1` with pre-existing findings under `docs/pr/reports/` only (broken links, table-cell counts, unknown requirement IDs, unbalanced fence). None reference `.agent/pr-notes/RESP-E2E-FEATURE-SYSTEM-20261124-041525.md`.
6. Repair pass — targeted pinned tests (tool evidence):
   `cd apps/staff-web && npx vitest run tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts tests/spec/platform/route-chunk-most-kb-gzip-enforced-ci.test.ts`
   Final lines: `Test Files  2 passed (2)` / `Tests  6 passed (6)`, `EXIT=0`.
7. Repair pass — full coverage gate (tool evidence):
   `bash scripts/coverage-check.sh origin/main`
   Final lines: `Test Files  278 passed (278)` / `Tests  3142 passed (3142)`, `No files with missing coverage. 276 files fully covered.`, `Statements 100% (5439/5439)`, `Branches 100% (2754/2754)`, `Functions 100% (1810/1810)`, `Lines 100% (5220/5220)`, `coverage check: passed`, `COVERAGE_EXIT=0`.

Typecheck and lint final lines are included above as required. The new spec is excluded from the Vitest coverage run (`package.json` `test:coverage --exclude 'e2e/**'`), so Playwright execution is the proof for this file. The jsdom `Not implemented: navigation` lines in the full run are expected warnings from the UnsupportedBrowserPage navigation test, not failures (3142/3142 passed).

## Review verdicts

- aurevia-plan-reviewer: GO
- aurevia-devsecops: GO
- aurevia-coverage-auditor: GO
- aurevia-code-reviewer: GO
- aurevia-plan-reviewer follow-up after code: GO

## Coverage

No production source file changed, so the 100% JaCoCo/Vitest source-coverage gate is unchanged. This change adds one e2e spec; its coverage is the executed acceptance matrix above. Branches and their tests:

- Five screens × two locales × keyboard activation → `the recovery action works by keyboard in English and Arabic`.
- Five screens × English × pointer (tap on touch projects, click on fine-pointer projects) → `the recovery action works by pointer in English`.
- Five screens × Arabic × pointer at wide, tall layouts → `the recovery action works by tap or click in Arabic at tall tablet and desktop widths` (seven projects; keyboard Arabic and standalone Arabic pointer remain active everywhere).
- Standalone notice × two locales × keyboard and pointer → `Go home performs a document load by keyboard and pointer in English and Arabic`.
- Touch at fine-pointer project widths → `the recovery action works by tap at a touch-enabled copy of every project viewport`.
- Desktop-to-phone reachability and value parity × five screens × two locales → `every desktop value is visible and reachable on a phone in English and Arabic`.
- English document overflow and standalone bilingual overflow → the two active horizontal-scroll tests.
- Two out-of-scope defects → two `test.fixme` with strict assertions kept (see Why this is not finished).

Repair pass — no production line or branch was added; the repair is confined to two deterministic artifact-pinning tests. Every assertion branch touched, with its test:

| Branch / pin | Test that asserts it |
|---|---|
| Inventory pin now includes `responsive-system.spec.ts` so the exact-set comparison stays strict (28 expected = 28 actual) | `records light/dark visual evidence and pins the remaining all-role and staging-RC gaps` — `expect(specs.sort()).toEqual([...currentSpecs].sort())` |
| Visual-check vocabulary pin (unchanged; the new spec matches none of it) | same test — `expect(visualChecks.sort()).toEqual([darkMode, reflowAndZoom, rightToLeft])` |
| Frontend job exact three-step pin (`frontend-check.sh`, `apps/staff-web/check.sh`, `apps/patient-mobile/check.sh`) | `CI Frontend standards job enforces the gzip budget through the canonical check` — `expect(runSteps).toEqual([...])` |
| Shared script command pin (`npm ci` / `typecheck` / `lint` / `test`) | same test — retained `moduleCommands` assertion |
| Canonical check build + budget-script commands (`npm run build`, `node scripts/check-bundle-size.mjs`) | same test — positive enforcement proof that replaced the stale GAP absence check |
| Budget ceiling, gzip measurement and over-budget refusal branches (unchanged) | the two original tests in `route-chunk-most-kb-gzip-enforced-ci.test.ts` |

No threshold, exclusion or suppression was added; the stale negative tripwire was replaced with a stricter positive proof of enforcement. `scripts/coverage-check.sh origin/main` reports 100% for all four metrics.

## Findings

1. **Real product defect (tool evidence, fixed by `test.fixme`, not weakened):** `apps/staff-web/index.html` boots `.skip-link` with physical `left: -9999px` while `src/theme/responsive.css` uses `inset-inline-start: -9999px`. Under `dir=rtl` both offsets apply; the link stretches and the document `scrollWidth` measured 11279px at a 1280px viewport. This is the same defect already parked in `e2e/rightToLeftResponsive.spec.ts`. It also intercepts pointer events over the notice, so Arabic pointer activation of the single action is a second `test.fixme`. The standalone notice has no skip link and was verified in both locales.
2. **Inventory tripwire — resolved in the repair pass (tool evidence):** `apps/staff-web/tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:238` pinned the exact e2e spec filename set (27 names) while the repository has 28. The test's own contract says a contributor adding a spec must add it to `currentSpecs` in the same change, so `responsive-system.spec.ts` was added to that pin. The assertion stays strict (sorted exact-set equality); no assertion was weakened. Targeted test now passes.
3. **Stale WEB-08 CI-enforcement GAP — resolved in the repair pass (tool evidence):** `apps/staff-web/tests/spec/platform/route-chunk-most-kb-gzip-enforced-ci.test.ts:106` expected the frontend job to contain exactly `- run: bash scripts/frontend-check.sh` and asserted the budget gate was absent. Commit `28cfb5b4` hardened `.github/workflows/delivery.yml`: the `Frontend standards` job now runs three steps (trimmed `run:` lines), including `bash apps/staff-web/check.sh`, which builds and invokes `node scripts/check-bundle-size.mjs` (`check.sh:18-19`). The test's own doc says the GAP case must be replaced with a positive proof of enforcement on the day CI wires the gate; that day has arrived. The stale negative assertion was replaced with a positive, exactly pinned enforcement proof. No workflow file was edited. Targeted test now passes.
4. `python3 -I scripts/check-docs.py` reports only pre-existing findings under `docs/pr/reports/`.
5. The Arabic seed for the standalone notice fetches the real Vite-transformed `I18nProvider.tsx` module from the configured base URL, replaces its default-locale token in the response body, and fulfils it in the browser only. It asserts `lang=ar` so a missed substitution fails loudly. No repository file, production module or external endpoint is involved.

## Lessons for workers

- When adding a new Playwright spec, the exact-set tripwire in `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts` must be updated by the owning story or the canonical `check.sh` will fail even though the new spec is green.
- Do not close the compact RTL user menu with a pointer click: the stretched skip link intercepts it. Use focus + Enter, and record the overlay as a fixme rather than hiding it with CSS.
- Playwright's `route.fetch()` inside a `page.route` handler can still be in flight when a context closes; fetch the module once with Node `fetch` before navigation and fulfil synchronously instead.

## Why this is not finished

- The two `test.fixme` cases are genuine out-of-scope defects in `apps/staff-web/index.html` (RTL skip link). Their strict assertions are retained; the exact next step is a story in scope for `index.html` that replaces the physical `left` with a logical offset, then re-enable the two fixmes.
- The coverage-gate failures from Findings 2 and 3 were resolved in the repair pass: `bash scripts/coverage-check.sh origin/main` now exits 0 with 3142/3142 tests and 100% on all four metrics. Only the index.html RTL skip-link fix remains blocked by this story's scope.

```task-requests
[
  {
    "title": "Fix the index.html RTL skip-link overlay",
    "details": "apps/staff-web/index.html uses a physical left: -9999px for .skip-link while responsive.css uses inset-inline-start. Under dir=rtl the skip link stretches (scrollWidth 11279 at 1280px) and intercepts pointer events. Fix with a logical offset, then re-enable the two test.fixme cases in apps/staff-web/e2e/responsive-system.spec.ts.",
    "scope": ["apps/staff-web/index.html", "apps/staff-web/e2e/responsive-system.spec.ts"],
    "depends": ["RESP-E2E-FEATURE-SYSTEM"]
  }
]
```
