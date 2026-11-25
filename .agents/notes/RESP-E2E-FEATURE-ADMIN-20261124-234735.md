# RESP-E2E-FEATURE-ADMIN — staff web admin screens at phone, tablet and desktop

## Scope and requirements

- Task: RESP-E2E-FEATURE-ADMIN (`tasks/00336000-RESP-E2E-FEATURE-ADMIN.md`).
- Requirements: WEB-07 (supported clients, responsive browser matrix), UX-01 (English/Arabic, RTL, WCAG 2.2 AA on staff web).
- Only repository file changed: `apps/staff-web/e2e/responsive-admin.spec.ts` (new). No production code, dependency, tracker, workflow or protected file was edited.
- Synthetic data only: no patient record, credential, real identifier, endpoint or token.

## Loaded skills and routing

- `aurevia-delivery` — matched "use when working any story or swarm task".
- No additional skill matched: the brief named only delivery, and the change is a single Playwright acceptance spec.
- Specialists consulted: `aurevia-plan-reviewer` before code; `aurevia-code-reviewer`, `aurevia-coverage-auditor`, `aurevia-devsecops` after code.

## Durable-directive conflict (brief vs repository rules)

- The brief asks for `git merge`, commit and publish. `.opencode/config/MEMORY.md` line 7 records the owner's durable directive: do not run any script that uses Git, directly or indirectly. The brief is advisory and cannot change that rule, so no Git command, Git-backed script, push, tag or `gh` call was run.
- The brief names `docs/pr/notes/<task-id>.md`; the prompt and MEMORY.md line 58 name `.agents/notes/<task-id>-<stamp>.md`. The prompt's file wins; this file is the hand-off.
- `scripts/check-constraints.py`, `scripts/check-verdicts.py` and `scripts/quality-gate.sh` were not run: they are Git-backed repository gates owned by the integrator.

## Acceptance mapping

| Requirement | Acceptance | Where asserted |
|---|---|---|
| WEB-07 | Every admin screen works at phone, tablet and desktop projects | All tests run in every project in `e2e/playwright.config.ts` (base Chromium, nine device viewports) |
| WEB-07 | Main task by keyboard and by touch | `the main task works by keyboard in English and Arabic`, `the main task works by pointer in English`, `the main task works by tap at a touch-enabled copy of every project viewport` |
| WEB-07 | No horizontal document scroll | `the administration screens have no horizontal scroll in English` (initial render and after the command); Arabic case is a strict `test.fixme` (see Why this is not finished) |
| WEB-07 | Desktop values still reachable on phone | `every desktop value is visible and reachable on a phone in English and Arabic`: compares distinct visible-value sets, scrolls each phone node into view, asserts its box is inside the viewport, and checks the labelled table region is `role="region"`/`tabindex="0"` on screens that use one |
| UX-01 | English and Arabic with `lang`/`dir` mirrored | `openScreen`; bilingual word tables copied from `src/features/admin/messages.ts` |
| UX-01 | Accessible screens | `expectNoBlockingViolations` (serious/critical axe gate from `e2e/accessibility.ts`) in the identity test at every project |
| Boundary safety | Commands carry tenant, correlation, CSRF and idempotency metadata | `expectCommandMetadata` on every command journey and on the phone parity journey (audit is read-only) |

The four screens are the real tenant-admin routes `/admin/facilities`, `/admin/staff`, `/admin/config` and `/admin/audit`. The shell's hard-coded pre-login role is replaced only in the Vite-transformed browser module; the guarded fetch fails loudly if that module shape changes. Only `/api/v1/**` is stubbed; every unmocked path returns `404 {code:'NOT_FOUND'}`.

Main tasks: facilities creates a facility; staff invites a member; config looks up a key, approves a pending version, proposes a version and defines a catalog entry; audit applies an action filter and reads the matching event. Both command outcomes and their recorded calls are asserted.

## Test evidence

All commands were run from `apps/staff-web` unless stated; evidence source is executed tool output (no simulated user or human participant).

1. `npm run typecheck` → no output, `TYPECHECK_EXIT=0`.
2. `npx eslint e2e/responsive-admin.spec.ts --max-warnings=0` → no output, `ESLINT_EXIT=0`.
3. Full Chromium device matrix (final file):
   `E2E_BASE_URL=http://127.0.0.1:5173 npm run e2e -- e2e/responsive-admin.spec.ts --retries=0 --workers=3`
   Final line: `30 skipped | 70 passed (3.8m)`, `E2E_EXIT=0` (the identical run before the last hardening reported `3.9m`). `E2E_BASE_URL` is set because the worker's `CI` environment disables the config's `reuseExistingServer`; the Vite dev server was started separately with `npm run dev -- --host 127.0.0.1`. WebKit and Firefox binaries are not installed in this worker, so the config omits those variants.
   Final targeted re-run after the last hardening: `7 passed | 3 skipped (41.7s)` on `phone-portrait-390x844`, exit 0.
4. Canonical module check: `bash apps/staff-web/check.sh` (from repository root) → `CHECK_EXIT=1`; final lines `Test Files  1 failed | 282 passed (283)` / `Tests  1 failed | 3224 passed (3225)`. The only failure is the out-of-scope exact-spec pin at `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:239` (`responsive-admin.spec.ts` is absent from `currentSpecs`). Typecheck, lint, build and gzip budget steps all passed before that test failure.
5. Coverage diagnostic (not the canonical gate, which stops at the pin above):
   `npx vitest run --coverage --exclude 'e2e/**' --exclude 'tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts'`
   Final lines: `Test Files  282 passed (282)` / `Tests  3222 passed (3222)`, `No files with missing coverage.`, `283 files fully covered.`, `Statements 100% (5754/5754)`, `Branches 100% (2886/2886)`, `Functions 100% (1899/1899)`, `Lines 100% (5528/5528)`, `COVERAGE_EXIT=0`.
6. Markdown check (from repository root): `python3 -I scripts/check-docs.py` → no output, `DOCS_EXIT=0`.
7. Environment preparation (no repository change): `npx playwright install chromium`, `npx playwright install-deps chromium`.

### Segment 2 — post-merge verification (tool evidence)

The branch was merged with `origin/main` before this segment. Per the durable directive in `.opencode/config/MEMORY.md` (line 64) no Git command or Git-backed operation was run; `git log` / `git diff origin/main...HEAD` were replaced by direct reads of the current tracked files and by the checks below. All three blockers were confirmed unchanged in the merged tree: `index.html:16-27` still boots `.skip-link` with physical `left`, `ConfigPage.tsx:577` still renders `defined ?? approved ?? ''`, and `currentSpecs` in `automate-critical-journey-supported-role-real-browser.test.ts:107-153` still omits `responsive-admin.spec.ts`. No conflict markers exist in the scoped spec.

8. `npm run typecheck` (from `apps/staff-web`) → no output, `TYPECHECK_EXIT=0`.
9. `bash apps/staff-web/check.sh` (from repository root) → `CHECK_EXIT=1`; final lines `Test Files  1 failed | 282 passed (283)` / `Tests  1 failed | 3224 passed (3225)`. The single failure is the same out-of-scope exact-spec pin at line 239; the diff shows the only extra received entry as `+ "responsive-admin.spec.ts"`. Every other stage (generated client, typecheck, lint, build, gzip budget, 100% coverage) passed.
10. `npm run e2e -- e2e/responsive-admin.spec.ts --project=phone-portrait-390x844 --retries=0` (from `apps/staff-web`) → `E2E_EXIT=0`, final lines `3 skipped` / `7 passed (42.0s)`. This confirms the merged production code still satisfies the scoped spec at a phone viewport; the dev server was started by the Playwright config.
11. Source reads (tool evidence): `apps/staff-web/index.html`, `apps/staff-web/src/features/admin/ConfigPage.tsx` and `apps/staff-web/tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts` were read in full at their current merged state; no defect was silently fixed and no assertion was weakened.

## Review verdicts

- aurevia-plan-reviewer: GO
- aurevia-code-reviewer: GO
- aurevia-coverage-auditor: GO
- aurevia-devsecops: GO

## Coverage

No production source file changed. `apps/staff-web/package.json` excludes `e2e/**` from the Vitest coverage run; Playwright execution above is the proof for the new spec. The diagnostic Vitest run reports 100% on every metric for all production files. Every branch added to the spec is exercised by a named test:

| Branch / helper side | Test that covers it |
|---|---|
| Four screen ids × en/ar identity, prose, baseline values, axe gate | `render the exact bilingual identity and pass the accessibility gate` |
| Four screen tasks × two locales, keyboard activation and focused control assertion | `the main task works by keyboard in English and Arabic` |
| Tap (coarse projects) and click (fine projects), English, plus post-command scroll check | `the main task works by pointer in English` |
| Arabic pointer at tall/wide projects; early return at compact/short | `the main task works by tap or click in Arabic at tall tablet and desktop widths` + strict `test.fixme` for the compact/short side |
| Config proposal followed by approval status masking | strict `test.fixme` with the natural propose→approve order |
| English document overflow before and after the command | `the administration screens have no horizontal scroll in English`; Arabic side is a strict `test.fixme` |
| Touch emulation at fine-pointer project widths | `the main task works by tap at a touch-enabled copy of every project viewport` |
| Desktop/phone value parity, sorted distinct sets, `scrollIntoViewIfNeeded`, bounding boxes, table-region focusability, phone command metadata | `every desktop value is visible and reachable on a phone in English and Arabic` |
| `roleModule` cache miss and cache hit; guarded shape check | first and later `prepareContext` calls in every journey; guard throws before any substitution if the module shape drifts |
| `prepareContext` first install and repeat no-op | `openInContext` calls `prepareContext` then `openScreen` calls it again on the same context |
| `syntheticResponse` fixed body, query-aware body, filtered and unfiltered audit reads | all command journeys; `searchAudit` asserts the `action=CONFIG_CREATE` query and the row that only the filtered body returns |
| `activate`: keyboard / tap / click; `enterValue`: tap / focus | keyboard journey, pointer journey, touch-copy journey |
| `switchToArabic`: compact menu open/close and wide direct toggle | phone and desktop/tablet Arabic steps in every journey |
| `pointerMode` both sides; `skipLinkCoversAction` both sides | pointer journey + Arabic pointer active test and its fixme |
| `visibleValues` empty/non-empty node text | parity test across the four screens |
| `expectCommandMetadata` present and non-empty headers | command journeys; audit screen is read-only and excluded |

No threshold, exclusion, suppression or skip was added. The three `test.fixme` cases are the story-prescribed outcome for real out-of-scope defects and keep their strict assertions.

## Findings

1. **RTL skip-link defect (tool evidence, out of scope):** `apps/staff-web/index.html:16-27` boots `.skip-link` with physical `left: -9999px` while `src/theme/responsive.css` uses `inset-inline-start`. Under `dir=rtl` both offsets apply; measured document `scrollWidth` was 10359px at a 360px viewport, and the stretched link intercepts pointer events at compact or short viewports. Strict assertions are parked in two `test.fixme` cases; keyboard routes remain active. The same defect is recorded in `e2e/responsive-system.spec.ts`.
2. **Config status masking (tool evidence, out of scope):** `apps/staff-web/src/features/admin/ConfigPage.tsx:576-578` renders `defined ?? approved`, so an approval performed after a proposal keeps announcing "Defined a pending version…". Both commands reach the API. The active task approves before proposing and still asserts both announcements; the natural propose→approve order is kept as a strict `test.fixme`.
3. **Exact-spec pin (tool evidence):** `bash apps/staff-web/check.sh` fails only because `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:107-153` pins the exact e2e spec set and omits `responsive-admin.spec.ts`. That file is outside this task's scope and owned by the integrator; the request below asks for the update. No assertion was weakened to make the gate pass.
4. **Diagnostic coverage command:** the canonical check stops at finding 3, so the 100% evidence is from a Vitest run that excludes only that one pin test file. No coverage threshold or configured exclusion was changed.
5. **Environment:** Playwright browser binaries and OS dependencies were installed in the worker (`npx playwright install chromium`, `npx playwright install-deps chromium`). No dependency was added to the repository.
6. Review history (tool evidence from read-only reviewers): the plan reviewer's first pass was NO-GO with nine Required gaps, all closed before the GO; the code reviewer's first pass was NO-GO on missing notes sections and a stale evidence tally, both closed; devsecops first pass was NO-GO on an absent-header false green, closed with presence assertions. Each reviewer then answered GO.
7. Evidence sources: executed tool commands and read-only specialist reviews; no simulated user walkthrough and no authorized human feedback.
8. Merge outcome (tool evidence): the merge with `origin/main` did not change any of the three blockers, so the scoped spec and its three strict `test.fixme` cases remain the correct final form. No new review was required for segment 2 because no production or test file changed; the four GO verdicts below still apply to the unchanged spec.
9. React console warnings (tool evidence): the targeted e2e run logged `Encountered two children with the same key` for `synthetic.reception` and for `11111111-...:1` in the served app. These are non-failing console messages from the synthetic fixtures; they do not affect any assertion and are outside this single-file scope. Recorded for the owning staff-web story rather than masked here.

## In-scope status: complete

The scoped deliverable `apps/staff-web/e2e/responsive-admin.spec.ts` is complete and stable after the merge. It covers all four admin screens at every configured phone, tablet and desktop project, drives each main task by keyboard, pointer and real tap, asserts no horizontal scroll, English and Arabic `lang`/`dir` and exact bilingual words, an axe serious/critical gate, desktop-to-phone value reachability and command boundary metadata. No further in-scope change is needed or possible. The story cannot be accepted or marked `[x]` yet because the canonical check is still red on the out-of-scope inventory pin and three product defects remain out of scope (below); each has a task request and keeps its strict assertion.

## Lessons for workers

- A new `*.spec.ts` under `e2e/` trips the exact-set pin in `automate-critical-journey-supported-role-real-browser.test.ts`; ask for that one-line pin update in a separate task instead of editing a file outside scope.
- Playwright's default headless UA is `HeadlessChrome/...` and is correctly rejected by `browserSupport.ts`; set a supported `Chrome/<major>` UA both in `test.use` and in every directly created browser context.
- The shell's hard-coded `useRole` can be overridden in the browser by replacing the Vite-transformed module, but fetch and guard the real module first so a production shape change fails loudly.
- `expect(headers['x-correlation-id']).not.toBe('')` passes for an absent header; assert `toBeDefined()` as well before asserting a non-empty value.
- Compact screens reflow tables into labelled cards (`th`/`td` become `dt`/`dd`), so compare distinct value sets across viewports rather than DOM shape or duplicate rows.

## Why this is not finished

- Segment 2 stop: the in-scope spec is complete and re-verified after the merge (see "In-scope status: complete"). No file outside `apps/staff-web/e2e/responsive-admin.spec.ts` may be edited by this worker, so there is no remaining in-scope action; this worker stops here rather than weakening an assertion or masking a defect.
- The canonical `bash apps/staff-web/check.sh` remains red on the out-of-scope exact-spec pin (finding 3). The exact next step is for the integrator to add `'responsive-admin.spec.ts'` to `currentSpecs` in `apps/staff-web/tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts`, then rerun the canonical check.
- Three strict `test.fixme` cases remain: Arabic document overflow, Arabic pointer activation at compact/short viewports (fix `apps/staff-web/index.html` to use a logical skip-link offset and re-enable), and the config approve-after-propose announcement (fix `ConfigPage.tsx` status precedence and re-enable). All three keep their assertions unchanged.

```task-requests
[
  {
    "title": "Add responsive-admin.spec.ts to the e2e inventory pin",
    "details": "tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts pins the exact e2e spec set in currentSpecs (around line 107). Add 'responsive-admin.spec.ts' to that list so bash apps/staff-web/check.sh is green while the exact-set assertion stays strict. No other change.",
    "scope": ["apps/staff-web/tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts"],
    "depends": ["RESP-E2E-FEATURE-ADMIN"]
  },
  {
    "title": "Fix the RTL skip-link overlay in the staff web",
    "details": "apps/staff-web/index.html boots .skip-link with physical left: -9999px while src/theme/responsive.css uses inset-inline-start. Under dir=rtl the link stretches (document scrollWidth about 10359px at 360px) and intercepts pointer events. Use a logical offset, then re-enable the two test.fixme cases in apps/staff-web/e2e/responsive-admin.spec.ts (Arabic horizontal scroll and Arabic pointer at compact/short viewports).",
    "scope": ["apps/staff-web/index.html", "apps/staff-web/e2e/responsive-admin.spec.ts"],
    "depends": ["RESP-E2E-FEATURE-ADMIN"]
  },
  {
    "title": "Announce the latest config command outcome",
    "details": "apps/staff-web/src/features/admin/ConfigPage.tsx renders defined ?? approved in the config status paragraph, so an approval after a proposal keeps showing the proposal announcement. Track the newest command result explicitly, then re-enable the test.fixme 'a proposal followed by an approval announces the approval' in apps/staff-web/e2e/responsive-admin.spec.ts.",
    "scope": ["apps/staff-web/src/features/admin/ConfigPage.tsx", "apps/staff-web/e2e/responsive-admin.spec.ts"],
    "depends": ["RESP-E2E-FEATURE-ADMIN"]
  }
]
```
