# RESP-E2E-ACCESSIBILITY-SCAN-RESPONSIVE — responsive WCAG 2.2 AA sweep (verification run)

## Summary

This run verified the already-delivered scope file `apps/staff-web/e2e/accessibilityScanResponsive.spec.ts`. No code changed in this run: the spec was added at `08aae4e9`, amended at `7e7126ee`, and is present at HEAD on a branch that is `Already up to date` with `origin/main`. The brief's statement that the scope path is absent in this snapshot is stale — the file exists (12,643 bytes) and its registration and typecheck/lint checks pass. Acceptance mapping was re-checked, all available gates were re-run, and the remaining gaps are recorded below.

The spec enumerates every registered staff-web route at 360 / 768 / 1280 px in English (ltr) and Arabic (rtl), runs a tagged WCAG 2.2 AA axe scan, and fails on any serious or critical violation. Sixteen route patterns the synthetic `reception` role cannot open are registered as `test.fixme` with the reason and concrete path in the title, because the only screen that role can reach is the denial page; those cells are reported as unfinished rather than scanned on a substitute page.

## Affected requirements

- **WEB-07** — three layout bands (phone 360, tablet 768, desktop 1280) are generated for every route and `window.innerWidth` is asserted per cell.
- **UX-01** — English/Arabic document `lang` and `dir` are asserted before and after the in-page language toggle; the compact-band User menu path and the 768+ header path are both exercised by generation.
- **WEB-10** — cited by story step 2: `wcag2a`, `wcag2aa`, `wcag21a`, `wcag21aa`, `wcag22aa` tags with a serious/critical zero-violation gate.

Acceptance mapping: step 1 (route inventory) → `SCAN_TARGETS` 30 entries from `src/app/routes.tsx` and all 17 feature `routes.ts` modules, `*` excluded; step 2 (WCAG 2.2 AA at three widths, two locales) → `VIEWPORT_BANDS` × `LOCALES` and the tagged scan; step 3 (one test per route and project, failure messages, fixme policy) → generated `test`/`test.fixme` titles plus the `context` string naming route, width, locale, direction and Playwright project; step 4 (`--list` because browsers are absent) → Test evidence.

## Risk class

Cross-boundary for verification scope (all routes × two locales × three widths × browser matrix) but test-only: no production behaviour, data, contract or permission changed. `docs/WORKFLOW.md`.

## Review owner

Senior React and Vite (staff-web e2e), with Senior TypeScript for the strict spec code. Named read-only reviewers: plan, code, coverage and devsecops; all verdicts are copied under `## Review verdicts`.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward fix. All identifiers are opaque synthetic values and every API response is a stubbed empty/shape-valid JSON body.

## Authorization boundaries and audit events

No new boundary and no audit event. The spec imports and reads `canAccessRoute` and fixes the harness role to the synthetic `reception`; it does not grant access or alter role policy. Role-denied cells are `test.fixme`, not silently passed. No patient record, credential, token or real endpoint appears in the file, logs or notes.

## Migration and rollout considerations

None. Test-only change.

## Test evidence

Tool evidence. Commands run from the repository root unless stated; final lines quoted.

- `git merge --no-edit origin/main` → `Already up to date.` (`MERGE_EXIT=0`); repeated after each stage and before finishing.
- `bash apps/staff-web/check.sh` → `CHECK_EXIT=1`. All earlier stages passed (`gen:api:check`, `npm run typecheck`, `npm run lint`, `npm run build`, `node scripts/check-bundle-size.mjs`); the only failure was in the final `test:coverage` stage: `Test Files  1 failed | 277 passed (278)`, `Tests  1 failed | 3141 passed (3142)`. Final failure line: `FAIL tests/spec/platform/route-chunk-most-kb-gzip-enforced-ci.test.ts > WEB-08 clause 3 — route chunks at most 250 KB gzip, enforced in CI > GAP: the required CI frontend job never runs the gzip budget gate` → `AssertionError: expected [] to deeply equal [ Array(1) ]` at line 106. This is a pre-existing, deterministic, out-of-scope GAP tripwire on main: it pins exactly one `- run:` step in the `frontend` job, while `.github/workflows/delivery.yml:102-106` now has three. It is unrelated to this story and no protected file was edited.
- `cd apps/staff-web && npm run typecheck` → `TYPECHECK_EXIT=0`; final lines `> aurevia-staff-web@0.1.0 typecheck` / `> tsc --noEmit -p tsconfig.json` (tsc prints nothing on success).
- `cd apps/staff-web && npx tsc --noEmit -p tsconfig.lint.json` → `TSC_LINT_EXIT=0`. Needed because `tsconfig.json` excludes `e2e`; this config includes it.
- `cd apps/staff-web && npx eslint e2e/accessibilityScanResponsive.spec.ts --max-warnings 0` → `ESLINT_EXIT=0` (no output).
- `cd apps/staff-web && npx vitest run tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts` → `SPEC_PIN_EXIT=0`; `Test Files  1 passed (1)`, `Tests  3 passed (3)`. This is the in-scope-adjacent spec-set tripwire that pins the new e2e spec name.
- `cd apps/staff-web && npx playwright test --config e2e/playwright.config.ts e2e/accessibilityScanResponsive.spec.ts --list` → `PW_LIST_EXIT=0`; final line `Total: 1800 tests in 1 file` (180 declarations × 10 configured Chromium projects; 840 active registrations, 960 `test.fixme`). No WebKit/Firefox project is configured because those binaries are absent.
- Browser availability probe (`require('playwright')` + `fs.existsSync(executablePath())`): `chromium … false`, `firefox … false`, `webkit … false`. **No live axe scan ran.** `--list` is discovery evidence only and does not establish WCAG compliance; CI must run the real scan on the exact SHA, as story step 4 permits.
- `python3 -I scripts/check-constraints.py` → `CONSTRAINTS_EXIT=0` (no suppression, exclusion, skip or lowered threshold).
- `python3 -I scripts/check-docs.py` → `CHECK_DOCS_EXIT=1` on pre-existing Markdown in `.agents/reports/` (for example `AUD-20261120-07.md:28`), none in this change. The notes file is under `.agent/`, which `scripts/check-docs.py:71` skips.
- Direct `npx prettier --check e2e/accessibilityScanResponsive.spec.ts` from the module reported a style diff, but the canonical root `npm run format:check` ignores `apps/**` via `.prettierignore`, and `npm run lint` is green; no formatting change was made.

Entry point to test trace: Playwright project (`e2e/playwright.config.ts`) → `e2e/accessibilityScanResponsive.spec.ts` → `page.route('**/api/v1/**', answerSyntheticApi)` → `page.goto(route)` → real `AppShell`/router → settled real screen assertion → `AxeBuilder({ page }).withTags([...WCAG_22_AA_TAGS]).analyze()` → serious/critical zero assertion with route, band, locale, direction and project in the message.

Evidence sources: executed commands (tool), read-only specialist reviewers. No simulated user walkthrough, no human or clinical participant was involved in this run.

## Coverage

No production source file was changed in this run, so no per-file production coverage obligation is introduced. E2E specs are excluded from Vitest (`package.json` `test:coverage` uses `--exclude 'e2e/**'`) and from `tsconfig.json`; there is no e2e runtime coverage metric. The closest supported measures are the Playwright `--list` generation-time registration count, the `tsconfig.lint.json` typecheck and the ESLint result above. The prior report records a green full gate with `Statements 100% (5107/5107)`, `Branches 100% (2634/2634)`, `Functions 100% (1697/1697)`, `Lines 100% (4903/4903)` on an earlier main state; that is historical evidence, not a result of this run. No threshold, exclusion or suppression was added or lowered.

Generation-time branches — executed at module load, proven by `--list` (`Total: 1800 tests in 1 file`):

- `for (target of SCAN_TARGETS)` — all 30 concrete routes.
- `for (band of VIEWPORT_BANDS)` — phone 360, tablet 768, desktop 1280.
- `for (localeCase of LOCALES)` — en/ltr and ar/rtl.
- `target.pattern ?? target.path` — 4 parameterised targets and 26 fixed paths.
- `canAccessRoute(...) === true` → `test` — 14 reachable routes × 3 × 2 = 84 declarations.
- `canAccessRoute(...) === false` → `test.fixme` — 16 denied routes × 3 × 2 = 96 declarations.
- `Object.values(stateViewMessages).flatMap(...)` — both locale bundles feed the substitute-heading guard.

Runtime branches — declared with named assertions but **not executed here** because no browser binary exists. These are reported as unexecuted, not claimed covered:

- `locale === 'ar'` true/false and the lang/dir assertions (spec:254-258) — tests are the generated ar/en cells.
- `bandWidth < COMPACT_MAX_WIDTH` true/false in `switchToArabic` (spec:232-235) — 360 px opens User menu; 768/1280 use the header button.
- `isStateScreen !== true` true/false (spec:272-288) — ordinary routes poll for a settled heading; `/forbidden` and `/session-expired` are exempt.
- The poll's H2-and-known-text true/false and `textContent ?? ''` both sides (spec:277-283).
- `main#main h1` vs `h2` title selector (spec:270).
- All five arms of `syntheticBody` plus its `||` (spec:191-204) — cursor page for appointments/inbox, `[]` for patients, prescription detail, status view, default `{}`.
- The serious/critical filter included/excluded sides (spec:291-293).
- The zero-violation assertion empty/non-empty sides (spec:294-297) — green run versus defect detection.

## Clinical and architecture review

Not required for this change: no signed data, cross-organization sharing, patient association, prescribing or critical-result behaviour was altered. No clinical or human acceptance is claimed; the sweep is synthetic and simulated.

## Review verdicts

- aurevia-plan-reviewer: GO
- aurevia-code-reviewer: GO
- aurevia-coverage-auditor: GO
- aurevia-devsecops: GO

## Findings

- **Brief is stale on two points:** (1) the scope file already exists at HEAD, so no new spec was needed in this run; (2) the brief's "Consult these read-only specialists" list is empty and its skill list omits the mandated reviewer skills, so this run followed `AGENTS.md` and the story's mandatory quality loop instead (plan, code, coverage and devsecops reviewers).
- **No live WCAG verdict:** all three Playwright engine paths resolve to absent files. `--list` proves registration only; CI on the exact SHA must execute the axe scans.
- **Role denial:** `useRole()` is fixed to `reception` (`src/app/useRole.ts:10`); 16 registered patterns render the denial page and their 960 cells are `test.fixme`. The story's "zero violations on every route" claim does not hold for those cells until an authorized-role harness exists.
- **Pre-existing red gate:** `bash apps/staff-web/check.sh` fails only at the out-of-scope WEB-08 GAP tripwire (`tests/spec/platform/route-chunk-most-kb-gzip-enforced-ci.test.ts:106`), which pins an old single-step frontend CI job. `.github/workflows/delivery.yml` now runs `frontend-check.sh`, `apps/staff-web/check.sh` and `apps/patient-mobile/check.sh`; the test's own comment says a follow-up task must replace it. No workflow or test outside scope was edited.
- **Pre-existing docs failure:** `scripts/check-docs.py` reports malformed tables in unrelated `.agents/reports/` files, none introduced here.
- **Optional clarity items** (not applied; no Required gap): `LOCALES` has no one-line TSDoc, and the heading locator `title` at spec:270 shadows the test-title string `title` at spec:243. Both were raised as Optional by the plan reviewer.
- **Evidence classification:** tool output for commands, read-only reviewer verdicts for review; no simulated user and no authorized human feedback was available or claimed.

## Lessons for workers

- Before adding a file under `apps/staff-web/e2e/`, check `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts`: its `currentSpecs` pin must list the new spec or the canonical gate goes red.
- A sweep of a real route can silently scan `ForbiddenPage`, a Suspense fallback or a WEB-09 state panel. Assert the pathname, a settled non-state heading and the measured width before axe, and mark role-denied cells fixme.
- `tsconfig.json` excludes `e2e`; run `npx tsc --noEmit -p tsconfig.lint.json` as well as `npm run typecheck` for a new spec.
- `test.fixme` is permitted by this story's AC and is not matched by the constraint checker's skip regex, but it must be reported under `## Why this is not finished`.
- The root `.prettierignore` contains `apps/**`; a direct `npx prettier --check` inside a module is not the canonical formatting gate.

## Why this is not finished

1. **Live axe scan unavailable here.** `npx playwright test --config e2e/playwright.config.ts e2e/accessibilityScanResponsive.spec.ts --list` → `Total: 1800 tests in 1 file`, but every browser executable is absent. The WCAG 2.2 AA verdict must be produced by CI on the exact SHA; this run cannot claim zero serious/critical violations.
2. **Sixteen role-denied routes are not scanned.** The synthetic `reception` role cannot open: `/patients/:id`, `/patients/:id/chart`, `/patients/:id/notes/:noteId`, `/admin/facilities`, `/admin/staff`, `/admin/config`, `/admin/audit`, `/messages`, `/results`, `/results/critical`, `/lab/accessions`, `/radiology`, `/pharmacy`, `/operations/exports`, `/operations/imports`, `/operations/queues`. Their 960 cells are `test.fixme` with the route in the title. Next step: an authorized-role plus synthetic-API harness, then un-fixme those cells.
3. **The canonical gate is red for an unrelated pre-existing defect.** `bash apps/staff-web/check.sh` → `Tests  1 failed | 3141 passed (3142)` at `tests/spec/platform/route-chunk-most-kb-gzip-enforced-ci.test.ts:106` (`expected [] to deeply equal [ Array(1) ]`). The test pins an old `frontend` job shape; the workflow now has three `- run:` steps. It is outside this task's single-file scope and protected files were not edited. Next step: a separate task to update that tripwire to the current workflow.

```task-requests
[
  {
    "title": "Authorized-role harness for role-denied responsive accessibility scans",
    "details": "useRole() is hard-coded to reception, so 16 registered patterns render ForbiddenPage and their 960 responsive accessibility cells are test.fixme. Provide a synthetic authorized role plus endpoint-shaped API fixtures and un-fixme those cells so WCAG 2.2 AA is proven on the intended screens.",
    "scope": ["apps/staff-web/src/app/useRole.ts", "apps/staff-web/e2e/accessibilityScanResponsive.spec.ts"],
    "depends": []
  },
  {
    "title": "Refresh stale WEB-08 frontend CI GAP tripwire",
    "details": "tests/spec/platform/route-chunk-most-kb-gzip-enforced-ci.test.ts:106 pins exactly one run step in the frontend job of .github/workflows/delivery.yml, but the job now runs frontend-check.sh, apps/staff-web/check.sh and apps/patient-mobile/check.sh. The active GAP test therefore fails every check.sh run. Replace it with a positive proof that the current workflow enforces the 250 KB gzip budget and keep the clause status honest.",
    "scope": ["apps/staff-web/tests/spec/platform/route-chunk-most-kb-gzip-enforced-ci.test.ts", "docs/spec/clauses/platform/route-chunk-most-kb-gzip-enforced-ci.md"],
    "depends": []
  }
]
```
