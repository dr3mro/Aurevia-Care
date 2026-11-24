# RESP-E2E-MOBILE-PERFORMANCE — staff web end-to-end mobilePerformance across phone, tablet and desktop

Notes are the hand-off and pull request description. All execution evidence below is **tool** evidence (commands run in this checkout) or **reviewer** evidence (read-only specialists). There is no simulated-user or human-participant evidence in this story.

## Summary

Adds one new Playwright spec, `apps/staff-web/e2e/mobilePerformance.spec.ts`, that measures the built staff application on the welcome route `/` and the sign-in-free feature route `/signed-out` across every project in `apps/staff-web/e2e/playwright.config.ts`, and proves the WEB-08 gzip route-chunk budget against the value published by `apps/staff-web/scripts/check-bundle-size.mjs`. No production source, configuration, route, dependency, trackers or threshold was changed.

The advisor brief was complete for the spec file but incomplete about the repository-side effect of adding an e2e spec: `apps/staff-web/tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:86-96` pins the exact e2e spec set and was already stale on `main` before this change (nine entries against fourteen specs). This story's new spec made the gap six. The coverage gate failed on that tripwire in repair attempt 1; the pin was updated to all fifteen committed specs (the five earlier journeys plus `mobilePerformance.spec.ts`) in this repair. That is a deterministic test-data correction — the pin's own contract is "the exact e2e spec set committed today" — not a lowered threshold, exclusion or skip.

## Affected requirements

- **WEB-07** — supported clients and the browser/device matrix. The spec runs in all ten Chromium projects (default `chromium`, nine phone/tablet/laptop/desktop viewports) and capability-gates optional WebKit/Firefox projects with `test.fixme`.
- **UX-01** — accessibility/i18n context for the responsive staff web; the spec navigates the same two routes used by the responsive journey work and asserts the `main#main` landmark is present before measuring.
- **WEB-08** — only the 250 KB gzip chunk budget is normative here (`MAX_CHUNK_GZIP_BYTES = 256_000` decimal bytes at `scripts/check-bundle-size.mjs:14`). WEB-08's laptop p75 LCP/INP/CLS are **not** claimed: the 4 s mobile LCP is task-proposed at `tasks/00330000-RESP-E2E-MOBILE-PERFORMANCE.md:15`. This spec records a single CDP-throttled lab run, not WEB-08 field p75 evidence.

## Risk class

Cross-boundary only in the sense that it exercises the built application through a browser; contained to one new e2e file. No clinical, identity, patient or tenancy boundary is touched.

## Review owner

Senior React and Vite, with Senior TypeScript, Test Engineer, Plan Reviewer, Code Reviewer, Coverage Auditor and DevSecOps (all consulted at the stages named in the brief).

## Data changes

None. No migration, backfill, lock or volume impact. Synthetic routes only; no patient, facility or credential fixture is used.

## Authorization boundaries and audit events

None. `/` and `/signed-out` are unrestricted routes; no role, tenant or patient-link scope is exercised, and no audit event is added or changed.

## Migration and rollout considerations

None. The spec builds `dist/` when absent and starts a loopback-only `vite preview` on `127.0.0.1:4200 + workerIndex`; both are in-process test setup and are torn down after the run. No deployment or production access is involved.

## Test evidence

Commands were run from `apps/staff-web` unless a repository-relative path is shown. Final lines are quoted.

- `npx tsc --noEmit -p tsconfig.lint.json` — exit 0, no output. This is the explicit e2e typecheck: `tsconfig.json:21` includes only `src`, `tests` and `vite.config.ts`, so `npm run typecheck` alone does not check `e2e/**`; `tsconfig.lint.json:3` adds `e2e`.
- `npx eslint e2e/mobilePerformance.spec.ts --max-warnings=0` — exit 0, no output.
- `E2E_BASE_URL=http://127.0.0.1:5173 npx playwright test --config e2e/playwright.config.ts e2e/mobilePerformance.spec.ts --list` — `Total: 40 tests in 1 file`, exit 0. That is 2 budget tests plus 2 route tests in each of ten Chromium projects. `E2E_BASE_URL` was set only to stop the config's dev `webServer` from starting during collection.
- `E2E_BASE_URL=http://127.0.0.1:5173 npx playwright test --config e2e/playwright.config.ts e2e/mobilePerformance.spec.ts --project=chromium -g "gzip" --reporter=line` — `2 passed (640ms)`, after the red/green restoration `2 passed (811ms)`, after hardening `2 passed (812ms)`, after the final bug-fix pass `2 passed (827ms)`, and on the final pre-commit rerun `2 passed (665ms)`, exit 0. These are browser-free tests and ran for real.
- **Red first (tool):** the real chunk read in the budget loop was temporarily replaced with `randomBytes(300 * 1024)` through the same `requireWithinGzipBudget` assertion. `... -g "built route chunks" --reporter=line` → `1 failed`, with `Error: AdminTableRegion-CsH92TdA.js: 307313 bytes gzipped exceeds the 256000 byte budget (WEB-08)` thrown at `requireWithinGzipBudget` (before hardening `mobilePerformance.spec.ts:129`, now `:151`) called from the budget loop (before hardening `:281`, now `:310`). The edit was then restored and the same command reported green. No threshold, assertion, exclusion or skip was changed.
- `npx --yes prettier@3.6.2 --check apps/staff-web/e2e/mobilePerformance.spec.ts` — `All matched files use Prettier code style!`, exit 0 (the root `package.json:16` pins prettier `^3.6.2`).
- `python3 -I scripts/check-constraints.py` — exit 0, no output.
- `python3 -I scripts/check-docs.py` — exit 0, no output (Markdown notes were edited).
- `bash apps/staff-web/check.sh` — **exit 1** on the pre-change baseline and in the first worker run, on one tripwire: `Test Files  1 failed | 267 passed (268)`, `Tests  1 failed | 3044 passed (3045)`, with the pin reporting `expected [ …(15) ] to deeply equal [ …(9) ]` (14 specs on `main` plus this new one). All other stages ran: generated-client check, typecheck, lint, production build, `node scripts/check-bundle-size.mjs` and all coverage checks passed; the sole failure was `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:166`.
- **Repair attempt 1 (tool):** after updating `currentSpecs` to the fifteen committed specs, `npx vitest run tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts` → `Test Files  1 passed (1)`, `Tests  3 passed (3)`. The full `bash apps/staff-web/check.sh` then passed end to end: `Test Files  268 passed (268)`, `Tests  3045 passed (3045)`, `No files with missing coverage.`, `267 files fully covered.` and coverage summary `Statements 100% (5107/5107)`, `Branches 100% (2634/2634)`, `Functions 100% (1697/1697)`, `Lines 100% (4903/4903)`. The jsdom `Not implemented: navigation` lines are pre-existing console noise from existing tests, not failures. The pin update touched only a test file; no threshold, exclusion, skip or production line changed.

**Entry point to test trace:** `npx playwright test --config e2e/playwright.config.ts e2e/mobilePerformance.spec.ts` → `ensureProductionBuild()` (serialised by an exclusive lock directory) → `startPreviewServer(workerIndex)` serves `dist/` on loopback → `page.goto(route)` → `main#main` visible → buffered `PerformanceObserver`s record LCP, interaction INP and CLS → `page.locator('header button:visible').first().click()` → `expect.poll` for the INP entry → threshold assertions naming route, `test.info().project.name` and metric → `session.detach()` and observer `disconnect()` in teardown.

Browsers are not installed in this sandbox (no `~/.cache/ms-playwright`, no system Chromium), so the route-metric tests were collected with `--list` but their live LCP/INP/CLS values were **not observed here**. CI must execute the full spec before acceptance.

## Coverage

No production source file was added or changed. Vitest coverage excludes `e2e/**` (`package.json:17`), so there is no new production line, branch, method or class to cover and no Vitest threshold was touched. The repair's green `bash apps/staff-web/check.sh` run reported `No files with missing coverage.`, `267 files fully covered.` and 100% statements/branches/functions/lines. The `currentSpecs` pin update is test data only: it adds no new production branch and the branch-to-test map below is unchanged and current.

Branch/failure-path map for the new spec (tool and reviewer evidence):

| Branch in `e2e/mobilePerformance.spec.ts` | Test that takes it |
|---|---|
| gzip size over 256000 → throw (`requireWithinGzipBudget`) | `the gzip budget assertion rejects an oversized synthetic chunk` (asserts throw, label and WEB-08 text); the temporary real-path red run also took it with a 307313-byte payload |
| gzip size at or under 256000 → return | same test with a 10 KiB compressible buffer, and every real chunk in `built route chunks stay within the WEB-08 gzip budget` |
| published constant drift (`publishedGzipBudget()`) | `built route chunks stay within the WEB-08 gzip budget` asserts the gate text equals the mirror |
| `dist/assets` absent → build and self-provision; present → reuse | `built route chunks stay within the WEB-08 gzip budget`; lock contention path is exercised structurally by the exclusive lock (no second concurrent run was available in this sandbox) |
| preview not ready → retry; ready → return; timeout → throw | exercised by the polling loop when the worker runs a route test; the timeout throw is a defensive failure path, not a suppressed condition |
| non-Chromium project → `test.fixme` | collected WebKit/Firefox projects when browsers are present; not present in this sandbox, so unexecuted here and reported as a capability gate |
| LCP null → `expect.poll` retry then `requireMetric` throw | route tests, with the message naming route and project |
| INP null (no `interactionId` entry) → `expect.poll` retry then `requireMetric` throw | route tests after the real click, with the message naming route and project |
| layout shift with `hadRecentInput` → skipped; without → summed | injected observer in every route test; the pre-click read excludes drawer/theme interaction shifts |
| `PerformanceObserver` entries present → disconnect in teardown | every route test's `page.evaluate` reads and disconnects before `session.detach()` |
| lock held by another worker → retry; non-`EEXIST` error → rethrow | `built route chunks stay within the WEB-08 gzip budget` exercises the uncontended path; the rethrow is a defensive loud-failure path with no suppression |
| budget regex miss → empty text → drift assertion fails | `built route chunks stay within the WEB-08 gzip budget` first assertion |
| `previewServer` undefined → build and start; already set → reuse | first and subsequent route tests in each worker (worker reuse) |
| `afterAll` with and without a preview server | observed in both runs: the budget-only runs had no server; the full collected suite starts one per route-test worker |
| observer guard false side (passive event without `interactionId`, shift without `value`) | every route test's injected observers ignore passive load entries; asserted by the INP poll failing without a real click |
| LCP/INP larger-entry update branch | every route test's observers take the update side when a later, larger candidate arrives |
| `samples === null` after the polls → typed narrowing throw | every route test; a real possibility if page state is replaced between the poll and the read, and required for strict TS narrowing |
| build lock contended → bounded retry; stale lock → remove; deadline exceeded → explicit throw | `built route chunks stay within the WEB-08 gzip budget` took the uncontended path; the other two are defensive loud-failure paths so a killed worker cannot hang a later run |
| build lock owner matches → release; owner missing or foreign → leave it | `built route chunks stay within the WEB-08 gzip budget` releases its own lock; contention paths leave another worker's lock untouched |
| preview spawn emits `error`, stderr under/over 4000 characters | every route test; captured into the readiness failure message and capped so a noisy local tool cannot flood diagnostics |
| samples object missing before the click → explicit throw | every route test; fails closed instead of treating CLS as a vacuous zero |
| preview readiness: app HTML present → ready; absent → retry; deadline → throw with captured stderr | every route test; the body match prevents a process squatting the loopback port from being mistaken for the app |
| double `requestAnimationFrame` settle before the CLS read | every route test; deterministic frame-based settle, no wall-clock timeout |

## Clinical and architecture review

Not required. No signed data, cross-organization sharing, patient association, prescribing or critical result is involved.

## Review verdicts

- smartclinic-code-reviewer: GO (initial review GO with four Optional notes; re-review after hardening GO with five Optional notes; final re-review after the route fix GO with two harmless Optional notes. The `//signed-out` navigation bug was fixed, not suppressed.)
- smartclinic-plan-reviewer: GO (initial review NO-GO with four Required gaps: built bundle, observer thresholds, real red step, serialised build. Re-review after implementation confirmed all four closed in the single scoped file.)
- smartclinic-devsecops: GO (initial review: Optional `test.fixme` capability gate and loopback readiness flakiness, both bounded and documented. Re-review after hardening: no Critical, Required or Optional findings; no secret/PHI in diagnostics, no external network, no untrusted-code execution, no new dependency.)
- smartclinic-test-engineer: GO (initial review GO; final re-review confirmed `new URL(route, baseUrl)` reaches both real routes and the CLS read fails closed. Thresholds and the negative-control test are unchanged. INP cannot pass without a real interaction; red proof exercised the real budget assertion and was restored.)
- smartclinic-coverage-auditor: GO (Optional: defensive test-infrastructure branches not individually named; the branch map below was extended to name them. e2e is outside Vitest coverage and no threshold or exclusion changed.)

## Findings

1. **Stale e2e spec pin — resolved in repair attempt 1 (tool).** `bash apps/staff-web/check.sh` failed on the baseline and first worker run at `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:166`: `expect(specs.sort()).toEqual([...currentSpecs].sort())`. The pin at `:86-96` listed 9 specs while `e2e/` already contains 14 on `main`; the five missing before this story are `adminflow/admin-journey.spec.ts`, `financeflow/finance-journey.spec.ts`, `pharmacyflow/pharmacy-bilingual-journey.spec.ts`, `radflow/radiology-bilingual-journey.spec.ts` and `therapyflow/therapy-journey.spec.ts`. Adding `mobilePerformance.spec.ts` made six. The pin was updated to all fifteen committed specs in the repair; the previously failing test and the full `check.sh` now pass. The accompanying `task-requests` block was therefore removed so no duplicate follow-up task is created.
2. **Development server is not representative (reviewer).** The shared config starts `npm run dev` (`playwright.config.ts:139`), whose unbundled module graph inflates LCP under 4× CPU plus slow 4G. The spec resolves this inside its scope: it provisions the production build and navigates to a `vite preview` of `dist/`, so the metrics are from built assets.
3. **Task-proposed versus normative thresholds (context reviewer).** 4 s LCP belongs to this task, not WEB-08; WEB-08 field p75 collection is still a GAP tripwire in `tests/spec/platform/front-end-performance.test.ts:189-207`. The notes and assertions label the 4 s target as task-proposed and never claim WEB-08 metric compliance.
4. **Interaction target differs by breakpoint (React reviewer).** `header button:visible` is the drawer toggle on compact phones and the theme toggle from 768 px upward. Both are real pointer interactions; the spec asserts only that an interaction entry was observed, never a particular button outcome, and reads CLS before the click so the drawer's deliberate reflow is not charged to page stability.
5. **Duplicate gzip rule (context reviewer).** The browser-free budget test mirrors `tests/spec/platform/front-end-performance.test.ts:94-123` and `check.sh:19`. It is retained because the task explicitly requires the Playwright spec to assert the chunk budget; it pins the published constant so the two mirrors cannot drift silently.
6. **Responsive layout design doc is stale (context reviewer).** `docs/design/responsive-layout.md:422-436` still lists 320 px and RTL projects that the delivered config does not have. Project names in this spec follow `playwright.config.ts`, not the doc. No Arabic performance project exists in the current config; the device-class suggestion to run only phone projects was not followed because the story explicitly requires phone, tablet and desktop coverage.
7. **Reviewer Optional hardening applied (reviewer + tool).** Code review and DevSecOps raised Optional robustness notes, all addressed in the same scoped file: bounded build-lock wait with stale-lock removal, chunk list read inside the lock, preview stderr captured into the timeout error, readiness verified against the app title, and a double-`requestAnimationFrame` settle before the CLS read. All gates were rerun green after these edits. No threshold, assertion, exclusion or skip changed.
8. **Double-slash route would have measured the wrong page (reviewer + tool).** The first navigation built the URL by string concatenation, producing `http://127.0.0.1:<port>//signed-out`; the route matcher rejects the empty segment, so the `/signed-out` test would have measured the not-found page. Fixed to `page.goto(new URL(route, baseUrl).href)`; a direct `node -e` tool check confirmed `http://127.0.0.1:4200/` → `http://127.0.0.1:4200/signed-out`. The same pass made the lock owner-aware, captured the chunk list inside the lock, added a preview `error` listener with a stderr cap, and made the pre-click CLS read fail closed. All gates rerun green. This was a test defect, not a production defect, so no `test.fixme` was used.

## Lessons for workers

- Adding any new `apps/staff-web/e2e/*.spec.ts` also requires updating the `currentSpecs` pin in `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts` in the same change; a single-file scope that omits it cannot leave `check.sh` green. The pin is a pinned inventory of *all* specs, so replace (not append to) the whole list after checking `find e2e -name '*.spec.ts'`.
- `npm run typecheck` does not include `e2e/**`; run `npx tsc --noEmit -p tsconfig.lint.json` for any e2e change, then `npx eslint <file> --max-warnings=0`.
- TS 5.7's DOM lib omits `durationThreshold` on `PerformanceObserverInit` and `interactionId` on `PerformanceEventTiming`; a local extending interface and an `in`-based type guard keep the observer fully typed with no cast.
- A red proof must break the assertion under test, not a different precondition: swapping the real chunk bytes for an incompressible buffer makes the gzip assertion itself fail and is then restored.
- Browser-free Playwright tests run without any browser installed; set `E2E_BASE_URL` to skip the config's dev `webServer` and use `--list` for browser-dependent tests when no browser binary is present.

## Why this is not finished

1. Browsers are absent in this sandbox, so the route-metric assertions were collected but not executed. `npx playwright test --list` reported `Total: 40 tests in 1 file`; live LCP/INP/CLS values remain unobserved. Exact next step: CI (or a worker with Playwright browsers) runs the full spec and records the per-project outcomes; a genuine over-threshold result on a specific route gets `test.fixme('<reason> — <route>')` on that single test and a defect entry, never a lowered threshold. The coverage gate's own failure is resolved: the `currentSpecs` pin now lists all fifteen specs and `bash apps/staff-web/check.sh` passes green, including 100% coverage.

No task requests remain: the stale-pin follow-up was completed in repair attempt 1 of this task.
