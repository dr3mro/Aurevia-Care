# RESP-E2E-FEATURE-ADMIN — fix pass (verification only)

## Summary

Fix pass on `apps/staff-web/e2e/responsive-admin.spec.ts` for the staff-web admin screens (facilities, staff, configuration, audit). The task body supplied to this worker was empty; the earlier run's findings were read from `.agents/notes/RESP-E2E-FEATURE-ADMIN-20261124-232336.md` before any work. That run had completed the spec and parked two genuine production defects outside the single-file scope as strict `test.fixme` assertions.

This pass made one bounded in-scope fix. The code reviewer found that the RTL skip-link `test.fixme` guard keyed only on `viewport.width < 768`, so the four Arabic pointer cells on `phone-landscape-844x390` (width 844, height 390) still ran and timed out behind the overlay. The guard now reuses the short-viewport-aware predicate measured in `responsive-system.spec.ts`. The canonical staff-web gate is green with 100% coverage and the four policy checks pass. The two underlying production defects remain outside scope and their fixing stories are requested again below.

## Affected requirements and acceptance mapping

- **WEB-07** (supported clients / responsive staff web): the existing spec's `main task in <locale> by <mode>` tests cover all four admin screens across every Chromium device project in `apps/staff-web/e2e/playwright.config.ts` (360–1920 px) plus installed WebKit/Firefox variants; `every desktop value is reachable on a phone` compares desktop and phone value pairs. This pass re-verified the typecheck/lint/build/coverage gate; browser-level verification is blocked (see `## Test evidence`).
- **UX-01** (English/Arabic RTL, keyboard and touch): both locales are exercised, `switchToArabic` drives the real shell control and asserts `lang`/`dir`, `activate` uses `tap()` on coarse-pointer projects and focus/`Enter` otherwise, `expectNoCompactAffordance`/`expectCompactAffordance` prove the labelled scroll region or audit cards, and `expectNoBlockingViolations` runs axe checks. Four compact-AR pointer cells and ten document-overflow cells remain parked for out-of-scope production defects; the strict assertions are retained.
- No requirement was weakened and no acceptance assertion was deleted.

## Risk class

Cross-boundary (browser harness mounted through the real router, shell, providers and typed API client). This pass changed no production, contract or data file.

## Skills loaded

- `aurevia-delivery` — swarm worker checkpoint, notes-as-PR-description and green-gate definition of done.
- `aurevia-senior-react` — staff-web canonical `check.sh` including typecheck.
- `aurevia-senior-typescript` — strict TypeScript in the Playwright spec.
- `aurevia-ux-reviewer` — keyboard/touch and English/Arabic RTL acceptance.
- `aurevia-test-engineer` — test-first acceptance and branch-to-test mapping.
- `aurevia-plan-reviewer`, `aurevia-code-reviewer`, `aurevia-coverage-auditor`, `aurevia-devsecops` — mandatory quality loop.

## Test evidence

Tool evidence only; no simulated-user walkthrough and no authorized human participant was available in this pass. Commands and final lines:

1. `bash apps/staff-web/check.sh` → exit 0. Final lines:
   `Test Files  283 passed (283)`, `Tests  3225 passed (3225)`, `Duration  127.04s (tests 39%, environment 37%, setup 16%, worker 3%, transform 3%, import 2%)`.
   Canonical gate includes generated-client drift, typecheck, lint, production build, bundle budget and 100%-coverage Vitest.
2. `cd apps/staff-web && npm run typecheck` → final lines (no `error TS…`):
   `> aurevia-staff-web@0.1.0 typecheck`
   `> tsc --noEmit -p tsconfig.json`
3. `cd apps/staff-web && npm run lint` → final lines:
   `> aurevia-staff-web@0.1.0 lint`
   `> eslint . --max-warnings 0`
4. `python3 -I scripts/check-constraints.py` → `constraints=0`; `python3 -I scripts/check-secrets.py` → `secrets=0`; `python3 -I scripts/check-docs.py` → `docs=0`; `python3 -I scripts/check-names.py` → `names=0`.
5. Initial e2e attempt failed at browser launch: `npx playwright test ... --project=tablet-portrait-820x1180 --grep "facilities: main task in en by keyboard" --retries=0` → `1 failed`, `browserType.launch: Executable doesn't exist at /github/home/.cache/ms-playwright/chromium_headless_shell-1255/...`. After `npx playwright install chromium` and `npx playwright install-deps chromium` (both exit 0, tool evidence), the same command → `1 passed (2.7s)`. The env is now usable; no repository file records these installs.
6. Red proof for the Required review finding: `npx playwright test --config e2e/playwright.config.ts e2e/responsive-admin.spec.ts --project=phone-landscape-844x390 --retries=0 --reporter=line` before the guard fix → Arabic pointer cells ran (width 844 is not `< 768`) and failed; the generated `test-results/responsive-admin-facilities-main-task-in-ar-by-pointer-phone-landscape-844x390/error-context.md` records `Test timeout of 90000ms exceeded.` with the still-focused language button and skip link in the page snapshot.
7. Green proof after the guard fix: (to be appended)

Entry-point-to-test trace: `e2e/playwright.config.ts` webServer → Vite dev app → real router `/admin/facilities|staff|config|audit` → real shell/providers → `{page}` fixture → `openAndRun` → per-screen task → local assertions plus `expectNoBlockingViolations`. Only the `/api/v1` boundary and dev-only role module are substituted. That trace could not be executed in this image because of finding 1.

## Coverage

- Command: `bash apps/staff-web/check.sh` (`npm run test:coverage`, Vitest v8). Result: `No files with missing coverage.` / `283 files fully covered.` / `Statements 100% (5754/5754)`, `Branches 100% (2886/2886)`, `Functions 100% (1899/1899)`, `Lines 100% (5528/5528)`.
- No production or test source changed in this pass, so there are no new lines or branches to cover. `e2e/**` is intentionally outside Vitest instrumentation; the spec's branches are asserted by the Playwright project matrix, which is blocked here by the missing browser binary.
- Inherited branch-to-test map from the earlier pass (still accurate): keyboard vs coarse-pointer activation → `keyboard`/`pointer` passes; approval validation and resolved command → config main-task and parity tests; audit filtered success / invalid instant / clear filters → audit main-task and parity tests; compact affordance → `expectCompactAffordance`; RTL overlay and config approval notice → parked strict `test.fixme` cases.
- No threshold, exclusion, pragma or skip was added by this pass.

## Review verdicts

(to be filled from the four mandatory reviewers)

## Findings

1. **E2e blocker (tool evidence, environmental):** this worker image has no Playwright browser binary and no local dev server. The canonical Vitest/typecheck/lint/build gate is green, but the browser acceptance matrix cannot be executed here. The two committed `error-context.md` files are consequences of that missing binary, not evidence of a new product defect.
2. **No new in-scope defect:** a read of the existing spec, config and the earlier findings showed the four screens, both locales, keyboard/touch paths, value parity and overflow assertions already present. Speculative edits to an e2e suite that cannot be executed locally would add unverifiable risk, so no source change was made.
3. **Out-of-scope RTL skip-link defect (still open):** `apps/staff-web/index.html` boots `.skip-link` with physical `left: -9999px`; `src/theme/responsive.css` uses logical `inset-inline-start`, so under `dir="rtl"` the element stretches across the top of the viewport and intercepts taps. Four compact-Arabic pointer cells and ten Arabic document-overflow tests remain parked with strict assertions.
4. **Out-of-scope configuration notice defect (still open):** `apps/staff-web/src/features/admin/ConfigPage.tsx:577` renders `defined ?? approved`; `confirmApproval` does not clear `defined`, so the approval notice stays hidden. The durable approved row and recorded POST are asserted actively; only the transient notice assertion is parked.
5. **Git publication blocked:** the durable no-Git directive in `.opencode/config/MEMORY.md` and this session's runtime forbid commit, merge and `gh`. No commit or `git merge origin/main` was performed; the trusted integrator owns publication.
6. No credential, patient datum or real endpoint was added; all fixtures remain opaque synthetic values.

## Lessons for workers

- Confirm the Playwright browser cache before diagnosing an `error-context.md` failure as a product defect: `browserType.launch: Executable doesn't exist` means the image cannot run the matrix at all.
- When a fix pass has an empty story body, read the previous notes file first; a verification-only outcome with honest blocker evidence is safer than a speculative edit to an unexecutable suite.
- `bash apps/staff-web/check.sh` proves typecheck, lint, build, bundle and 100% Vitest coverage, but it does not execute `e2e/**`; browser acceptance still needs a runner with installed browsers.

## Why this is not finished

Two acceptance gaps remain, both real production defects outside the task's single-file scope, plus one environmental blocker:

1. **Browser matrix not executed here:** `npx playwright test ... --project=tablet-portrait-820x1180 ...` fails with `Executable doesn't exist at /github/home/.cache/ms-playwright/chromium_headless_shell-1255/...`. Most likely cause: this worker image does not install Playwright browsers and no dev server is running. Tried: targeted run, cache listing, system-browser lookup, loopback probe. Exact next step: run the spec in the Delivery browser-matrix job (`npx playwright install --with-deps chromium` in a runner with network access, or the existing e2e CI job), using `--retries=0` on phone/tablet/laptop projects.
2. **RTL skip-link overlay:** see finding 3. Next step: a story owning `apps/staff-web/index.html` replaces the physical offset with a logical one, then re-enables the eight strict parked assertions.
3. **Configuration approval notice:** see finding 4. Next step: a story owning `ConfigPage.tsx` clears `defined` on approval, then re-enables the two strict parked assertions.
4. **Commit/merge unavailable:** see finding 5. Next step: trusted integrator commits the notes file and reconciles with `origin/main`.

```task-requests
[
  {
    "title": "Fix RTL skip-link offset in staff-web index.html",
    "details": "Replace the physical left offset on .skip-link with a logical inset so under dir=rtl it does not stretch into a viewport-wide overlay and intercept taps at compact widths. Re-enable the eight strict test.fixme assertions in apps/staff-web/e2e/responsive-admin.spec.ts. Reproduced earlier by Playwright tap logs on phone-landscape-844x390 in Arabic.",
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
