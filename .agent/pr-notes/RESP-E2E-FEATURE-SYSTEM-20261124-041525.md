# RESP-E2E-FEATURE-SYSTEM — staff web system screens are responsive

## Scope and requirements

- Story: RESP-E2E-FEATURE-SYSTEM (task `tasks/00337000-RESP-E2E-FEATURE-SYSTEM.md`).
- Requirements: WEB-07 (supported clients, compact layout, browser/device matrix) and UX-01 (English and Arabic, RTL, WCAG 2.2 AA on staff web).
- Only file in scope: `apps/staff-web/e2e/responsive-system.spec.ts` (new). No production code, dependency, tracker, workflow or protected file changed.
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
4. Canonical module check: `cd apps/staff-web && bash check.sh`
   Final lines: `Test Files  2 failed | 276 passed (278)` / `Tests  2 failed | 3140 passed (3142)`, `CHECK_EXIT=1`. Both failures are described under Findings; neither is in the new spec and neither is caused by the new spec except the out-of-scope inventory tripwire.
5. Markdown check: `python3 -I scripts/check-docs.py` → `DOCS_EXIT=1` with pre-existing findings under `docs/pr/reports/` only (broken links, table-cell counts, unknown requirement IDs, unbalanced fence). None reference `.agent/pr-notes/RESP-E2E-FEATURE-SYSTEM-20261124-041525.md`.

Typecheck and lint final lines are included above as required. The new spec is excluded from the Vitest coverage run (`package.json` `test:coverage --exclude 'e2e/**'`), so Playwright execution is the proof for this file.

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

## Findings

1. **Real product defect (tool evidence, fixed by `test.fixme`, not weakened):** `apps/staff-web/index.html` boots `.skip-link` with physical `left: -9999px` while `src/theme/responsive.css` uses `inset-inline-start: -9999px`. Under `dir=rtl` both offsets apply; the link stretches and the document `scrollWidth` measured 11279px at a 1280px viewport. This is the same defect already parked in `e2e/rightToLeftResponsive.spec.ts`. It also intercepts pointer events over the notice, so Arabic pointer activation of the single action is a second `test.fixme`. The standalone notice has no skip link and was verified in both locales.
2. **Out-of-scope inventory tripwire (caused by adding any new spec):** `apps/staff-web/tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:238` pins the exact e2e spec filename set. It now lists 27 names and the repository has 28. The fix belongs to that test's owning story; the spec filename is fixed by this story's scope. No assertion in the new spec was weakened to make it pass.
3. **Pre-existing workflow GAP test (not caused by this change):** `apps/staff-web/tests/spec/platform/route-chunk-most-kb-gzip-enforced-ci.test.ts:106` expects `.github/workflows/delivery.yml` frontend job to contain `- run: bash scripts/frontend-check.sh`; the current job delegates to policy/composite steps and has zero `- run:` steps. That test reads only workflow/script files, so the new e2e spec cannot affect it.
4. `python3 -I scripts/check-docs.py` reports only pre-existing findings under `docs/pr/reports/`.
5. The Arabic seed for the standalone notice fetches the real Vite-transformed `I18nProvider.tsx` module from the configured base URL, replaces its default-locale token in the response body, and fulfils it in the browser only. It asserts `lang=ar` so a missed substitution fails loudly. No repository file, production module or external endpoint is involved.

## Lessons for workers

- When adding a new Playwright spec, the exact-set tripwire in `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts` must be updated by the owning story or the canonical `check.sh` will fail even though the new spec is green.
- Do not close the compact RTL user menu with a pointer click: the stretched skip link intercepts it. Use focus + Enter, and record the overlay as a fixme rather than hiding it with CSS.
- Playwright's `route.fetch()` inside a `page.route` handler can still be in flight when a context closes; fetch the module once with Node `fetch` before navigation and fulfil synchronously instead.

## Why this is not finished

- The two `test.fixme` cases are genuine out-of-scope defects in `apps/staff-web/index.html` (RTL skip link). Their strict assertions are retained; the exact next step is a story in scope for `index.html` that replaces the physical `left` with a logical offset, then re-enable the two fixmes.
- `bash apps/staff-web/check.sh` exits 1 because of the two Findings above: the pinned e2e-spec inventory needs the new filename added by its owning story, and the pre-existing gzip-CI GAP workflow test needs the workflow follow-up. Neither can be changed under this task's single-file scope.

```task-requests
[
  {
    "title": "Update the staff-web e2e spec inventory tripwire for responsive-system.spec.ts",
    "details": "tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:238 pins the exact e2e spec filename set. RESP-E2E-FEATURE-SYSTEM adds apps/staff-web/e2e/responsive-system.spec.ts, so check.sh fails with 27 expected vs 28 actual names. Add the new filename to currentSpecs and keep the assertion strict.",
    "scope": ["apps/staff-web/tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts"],
    "depends": ["RESP-E2E-FEATURE-SYSTEM"]
  },
  {
    "title": "Fix the index.html RTL skip-link overlay",
    "details": "apps/staff-web/index.html uses a physical left: -9999px for .skip-link while responsive.css uses inset-inline-start. Under dir=rtl the skip link stretches (scrollWidth 11279 at 1280px) and intercepts pointer events. Fix with a logical offset, then re-enable the two test.fixme cases in apps/staff-web/e2e/responsive-system.spec.ts.",
    "scope": ["apps/staff-web/index.html", "apps/staff-web/e2e/responsive-system.spec.ts"],
    "depends": ["RESP-E2E-FEATURE-SYSTEM"]
  }
]
```
