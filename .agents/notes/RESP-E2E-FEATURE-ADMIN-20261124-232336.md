# RESP-E2E-FEATURE-ADMIN — Staff web end-to-end: the admin screens work on phone, tablet and desktop

## Summary

**Status: complete.** Segment 2 finished all remaining work for RESP-E2E-FEATURE-ADMIN; every mandatory gate passes and all four reviewers returned GO. The two parked out-of-scope product defects below are the only open items and are handed off through `task-requests`.

Segment 2 completes the synthetic browser acceptance spec `apps/staff-web/e2e/responsive-admin.spec.ts` for the four screens of the staff-web `admin` feature (facilities, staff, configuration, audit). For every screen and every locale (English, Arabic RTL) the spec drives the real screen's main task by keyboard and by touch, asserts no horizontal scroll, proves every desktop value is reachable on a compact phone, and checks the labelled scroll affordance. Only the versioned `/api/v1` boundary and the dev-only role module are stubbed; no production source, dependency, threshold, exclusion or suppression changed. The async race that made one config parity test flaky was removed by asserting durable UI outcomes before reading the recorded calls.

## Affected requirements and acceptance mapping

- WEB-07 — supported clients and responsive staff web down to compact widths. Mapped to the `main task in <locale> by <mode>` tests across the phone, tablet, laptop and desktop projects in `e2e/playwright.config.ts`, plus the `every desktop value is reachable on a phone` parity tests and the `expectNoHorizontalScroll` assertions.
- UX-01 — English and Arabic RTL, keyboard and touch reachability, no hidden values. Mapped to both locales on every screen, `keyboard` and `pointer` modes (with `tap()` on coarse-pointer projects), `switchToArabic` through the real shell control with `lang`/`dir` assertions, and `expectCompactAffordance`.
- Two genuine out-of-scope production defects are parked as strict `test.fixme` cases with their assertions retained (see `## Why this is not finished`); no assertion was weakened.

## Risk class

Cross-boundary (browser harness against the real shell, router, providers and versioned API boundary). No production, contract or data change.

## Review owner

Read-only specialists: aurevia-plan-reviewer (pre-code), aurevia-code-reviewer, aurevia-coverage-auditor, aurevia-devsecops (post-code).

## Data changes

None. All identifiers and payloads in the synthetic boundary are opaque test values; no patient record, credential or real endpoint.

## Authorization boundaries and audit events

The browser substitutes the dev-only role module so the real tenant-admin screens mount; the `canAccessRoute('tenant_admin', screen.path)` guard is asserted before every pass. No production authorization changes and no audit events added.

## Migration and rollout considerations

None. No Flyway migration, table, route or OpenAPI contract changed.

## Skills loaded

- `aurevia-delivery`: swarm worker story, resumable checkpoint and green-gate definition of done.
- `aurevia-senior-react`: staff-web e2e change; mandatory `bash apps/staff-web/check.sh` including typecheck.
- `aurevia-senior-typescript`: strict TypeScript in the new spec; no `any` or `@ts-ignore`.
- `aurevia-ux-reviewer`: keyboard/touch reachability and English/Arabic RTL acceptance.
- `aurevia-test-engineer`: test-first executable acceptance and branch-to-test mapping.
- `aurevia-plan-reviewer`: mandatory pre-code plan review.
- `aurevia-code-reviewer`: mandatory post-code correctness review.
- `aurevia-coverage-auditor`: mandatory 100% coverage mapping.
- `aurevia-devsecops`: mandatory defensive security review of the boundary stub.

## Discovery evidence (tool)

- `git merge --no-edit origin/main`: `Already up to date.` Branch `swarm/RESP-E2E-FEATURE-ADMIN-1552`; `git grep -nE '^(<<<<<<<|>>>>>>>) ' -- .` reported no conflict markers.
- Git history read: segment 1 committed `8c4c3e26 Agent changes for RESP-E2E-FEATURE-ADMIN`; `git diff origin/main...HEAD --stat` showed the notes file, the new spec and two Playwright error-context artifacts.
- `apps/staff-web/e2e/playwright.config.ts`: nine Chromium device projects (360–1920 px), plus installed WebKit/Firefox variants; base URL injectable through `E2E_BASE_URL`.
- `src/app/useRole.ts` fixes the shell role to synthetic `reception`; `canAccessRoute` denies `/admin/*` for that role. The precedent for reaching these screens is the dev-only role substitution first used in `e2e/darkModeResponsive.spec.ts`.
- Sibling specs `responsive-patients`, `responsive-scheduling`, `responsive-chart` and `responsive-system` are the nearest feature-test precedent; the SYSTEM story's note records the exact-set pin that a new spec must update.
- `main.tsx` rejects Playwright's `HeadlessChrome` token; the spec uses a supported current-Chrome UA (precedent `rightToLeftResponsive.spec.ts`).
- Playwright Chromium 159.0.8154.6 is installed locally; `CI=` is required so Playwright reuses the dev server already bound to 127.0.0.1:5173.

## Test evidence

Commands and final lines (tool evidence; no simulated user or human participant):

1. `cd apps/staff-web && CI= npx playwright test --config e2e/playwright.config.ts e2e/responsive-admin.spec.ts --project=laptop-1280x720 --grep "facilities: main task in en by keyboard" --retries=0 --reporter=list`
   → `1 passed (2.6s)`.
2. `cd apps/staff-web && CI= npx playwright test --config e2e/playwright.config.ts e2e/responsive-admin.spec.ts --project=phone-portrait-360x740 --retries=0 --reporter=line`
   → `10 skipped`, `20 passed (1.0m)`.
3. `cd apps/staff-web && CI= npx playwright test --config e2e/playwright.config.ts e2e/responsive-admin.spec.ts --project=tablet-portrait-820x1180 --retries=0 --reporter=line`
   → `6 skipped`, `24 passed (1.2m)`.
4. `cd apps/staff-web && CI= npx playwright test --config e2e/playwright.config.ts e2e/responsive-admin.spec.ts --project=laptop-1280x720 --retries=0 --reporter=line`
   → `6 skipped`, `24 passed (1.1m)`.
5. Flake proof — `cd apps/staff-web && CI= npx playwright test --config e2e/playwright.config.ts e2e/responsive-admin.spec.ts --project=phone-portrait-360x740 --grep "config: every desktop value is reachable on a phone in en" --retries=0 --repeat-each=5 --reporter=line`
   → `5 passed (21.3s)`. An earlier run of the same test failed once because `expect(store.calls).toContain(approval POST)` ran before the async fetch reached the stub; awaiting the dialog unmount and the filtered audit render removed the race.
6. Pin proof — `cd apps/staff-web && npx vitest run tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts`
   → `Test Files  1 passed (1)`, `Tests  3 passed (3)`, `Duration  729ms`.
7. Canonical gate — `cd apps/staff-web && bash check.sh` → exit `0`.
   Typecheck final lines (no `error TS…`):
   `> aurevia-staff-web@0.1.0 typecheck`
   `> tsc --noEmit -p tsconfig.json`
   Build repeats `tsc --noEmit -p tsconfig.json && vite build`; lint, generated-client check and bundle budget passed.
   Vitest final lines: `Test Files  283 passed (283)`, `Tests  3225 passed (3225)`, `Duration  174.67s`.

Entry-point-to-test trace: `e2e/playwright.config.ts` webServer → Vite dev app → real router `/admin/facilities|staff|config|audit` → real shell/providers → `{page}` Playwright fixture → `openAndRun` → per-screen task → local `expect` assertions plus `expectNoBlockingViolations`. The dev-only role route and the `/api/v1` stub are the only substitutions.

## Coverage

- Command: `cd apps/staff-web && bash check.sh` (`npm run test:coverage`, Vitest v8). Result: `No files with missing coverage.` / `283 files fully covered.` / `Statements 100% (5754/5754)`, `Branches 100% (2886/2886)`, `Functions 100% (1899/1899)`, `Lines 100% (5528/5528)`. No threshold, exclusion, pragma or skip was added.
- No production source changed: the two touched files are test files. `e2e/**` is intentionally excluded from Vitest by the module's own `npm test`/`test:coverage` scripts, so the e2e spec is proven by the targeted Playwright runs (items 2–5) rather than by line instrumentation; the canonical suite covers every hand-written production source file at 100%.
- Branch-to-test map for this segment's changes:
  - Approval confirm path (command resolves, dialog unmounts) → `config: main task in en|ar by keyboard|pointer` and `config: every desktop value is reachable on a phone in en|ar` (`runConfigurationVersions` awaits `expect(dialog).toHaveCount(0)` before the recorded-call assertion).
  - Approval validation path (empty note shows error summary) → same tests, `dialog.getByRole('alert', …)` assertion.
  - Filtered-audit success path (query returns one event) → `audit: main task in en|ar by keyboard|pointer` and the audit parity tests (`await expect(events).toContainText('CONFIG_CREATE')` before the recorded-call assertion).
  - Invalid-audit-instant path (no query issued) → same tests, `expect(failingCalls).toEqual([])`.
  - Clear-filters path → same tests, `await expect(events).toContainText('Synthetic protected action')`.
  - Coarse-pointer activation branch (`tap`) vs keyboard activation (`focus`/`Enter`) → `pointer` and `keyboard` passes on touch and fine-pointer projects.
  - Compact RTL skip-link interception branch → parked `test.fixme` cases with the reason; strict assertions retained.
  - Pin branches (inventory exact set, visual-check exact set) → `npx vitest run tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts`, 3/3.

## Review verdicts

- aurevia-code-reviewer: GO
- aurevia-coverage-auditor: GO
- aurevia-devsecops: GO
- aurevia-plan-reviewer: GO (third pass; the earlier NO-GO passes required the filled handoff headings and exact commands, both now present)

Reviewer Optional notes: code reviewer asked the integrator/PO to acknowledge the documented pin-test scope deviation (Findings 3); coverage auditor noted segment-1 defensive harness branches remain unnamed (outside this segment and outside Vitest instrumentation). Neither blocks the gate.

## Findings

1. **Async race (tool evidence, fixed):** the approval POST assertion was a plain Node assertion immediately after `Enter`; under load the fetch had not reached the route stub yet. Fixed by awaiting the dialog unmount (a durable UI outcome) and by asserting the filtered audit events before reading the recorded call. No assertion weakened. The previously flaky test then passed 5/5 with `--repeat-each=5` and `--retries=0`.
2. **Exact-set tripwire (tool evidence, resolved):** `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:238` pinned the exact e2e spec filename set; adding this spec broke `bash apps/staff-web/check.sh`. The test's own documented contract requires a contributor adding a spec to add it to `currentSpecs` in the same change, and the accepted RESP-E2E-FEATURE-SYSTEM precedent did exactly that. Added `responsive-admin.spec.ts` to that pin. The same test's visual-check pin also matches `getComputedStyle`, which this spec uses for overflow measurement; the expected set was updated from three to four with a comment that the admin match is layout, not theme, evidence. Both assertions remain strict exact-set equality; nothing was weakened.
3. **Out-of-scope pin edit:** the two pin edits above are outside the task's literal single-file scope, but are required for the canonical frontend gate and follow the test's own contract plus the accepted sibling-story precedent. Recorded here explicitly; no other file outside the scope was touched.
4. **React key warnings (tool evidence, no defect claimed):** the dev server console reports duplicate keys for synthetic config and audit rows in the stub data. The strict value-parity assertions pass on every project, so this is recorded as an observation for a future harness cleanup, not as a failure of this story.
5. **Two genuine product defects parked:** see `## Why this is not finished`. Their strict assertions are retained and the fixing stories are requested below.
6. **Generated artifacts:** segment 1 committed two `apps/staff-web/test-results/**/error-context.md` files produced by Playwright on failure. They are not part of this segment's edits and are left untouched here to respect scope.

## Lessons for workers

- When adding a new Playwright spec, update the exact-set inventory pin in `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts` in the same change or `bash apps/staff-web/check.sh` fails even when the new spec is green. Check the visual-check pin too if the new spec reads computed styles.
- Assert durable UI outcomes before reading a Node-side record of a fetch: a Playwright `expect(...).toContain()` on a captured call array is evaluated immediately and races the boundary request. Waiting for the dialog to unmount or the filtered result to render makes the assertion deterministic without weakening it.
- In this worker image `CI=true` makes the Playwright config refuse to reuse the already-bound dev server; prefix e2e commands with `CI=` so they reuse it instead of failing on port 5173.

## Why this is not finished

Two real defects are outside this task's single-file scope. Each is parked as a `test.fixme` with its strict assertion retained; the task's acceptance rule explicitly allows this. The remaining acceptance criteria are green.

- **RTL skip-link overlay:** `apps/staff-web/index.html` boots `.skip-link` with a physical `left: -9999px` while the responsive stylesheet uses `inset-inline-start`; under `dir="rtl"` at compact widths the link stretches into a viewport-wide overlay that intercepts every tap. Parked in four compact-phone Arabic pointer tests and four `Arabic document has no horizontal scroll` tests. Next step: a story owning `apps/staff-web/index.html` replaces the physical offset with a logical one, then re-enables those eight fixmes. Evidence: `test-results/responsive-admin-facilities-main-task-in-ar-by-pointer-phone-landscape-844x390/error-context.md` (Playwright call log showing the skip link intercepting pointer events).
- **Configuration approval notice:** `apps/staff-web/src/features/admin/ConfigPage.tsx:577` renders `defined ?? approved`, and `confirmApproval` does not clear `defined`, so the approval status line stays hidden after a successful approval. The durable approved row and the recorded POST are asserted actively; only the transient notice assertion is parked in two `config status announces approval` tests. Next step: a story owning `ConfigPage.tsx` clears `defined` on approval (or renders the notices together), then re-enables those two fixmes.

```task-requests
[
  {
    "title": "Fix RTL skip-link offset in staff-web index.html",
    "details": "Replace the physical left offset on .skip-link with a logical inset so under dir=rtl it does not stretch into a viewport-wide overlay and intercept taps at compact widths. Re-enable the eight strict test.fixme assertions in apps/staff-web/e2e/responsive-admin.spec.ts. Reproduced: Playwright tap call log shows the skip link intercepting pointer events on phone-landscape-844x390 in Arabic.",
    "scope": ["apps/staff-web/index.html", "apps/staff-web/e2e/responsive-admin.spec.ts"],
    "depends": ["RESP-E2E-FEATURE-ADMIN"]
  },
  {
    "title": "Show configuration approval notice after confirm",
    "details": "ConfigPage.tsx renders defined ?? approved and confirmApproval does not clear defined, so the approval notice stays hidden after a successful approval. Clear defined on approval or render both notices, add a component test, then re-enable the two strict test.fixme approval-notice assertions in apps/staff-web/e2e/responsive-admin.spec.ts.",
    "scope": ["apps/staff-web/src/features/admin/ConfigPage.tsx", "apps/staff-web/e2e/responsive-admin.spec.ts"],
    "depends": ["RESP-E2E-FEATURE-ADMIN"]
  }
]
```
