# RESP-E2E-ACCESSIBILITY-SCAN-RESPONSIVE — responsive WCAG 2.2 AA sweep

## Summary

Added one new Playwright spec, `apps/staff-web/e2e/accessibilityScanResponsive.spec.ts`, that enumerates every registered staff-web route at 360 / 768 / 1280 px in English (ltr) and Arabic (rtl) and runs a tagged WCAG 2.2 AA axe scan with a serious/critical zero-violation gate. The 16 patterns the synthetic `reception` role cannot open are registered as `test.fixme` with the reason and concrete route in the title rather than reporting a green scan of the shell's denial page; a follow-up authorized-role harness is requested. No production source changed.

## Affected requirements

- **WEB-07** — three layout bands (compact 360, medium 768, expanded 1280) are exercised in every generated test; the measured `window.innerWidth` is asserted per band.
- **UX-01** — English/Arabic document `lang` and `dir` are asserted before and after the in-page language toggle; the 320 px decision-0004 floor is out of this story's 360 px scope.
- **WEB-10** — tagged axe scan (`wcag2a`, `wcag2aa`, `wcag21a`, `wcag21aa`, `wcag22aa`) filters serious/critical and asserts zero, with route/width/locale/band/project in the failure message.

Acceptance mapping: story step 1 (route inventory) → `SCAN_TARGETS` (30 entries read from `src/app/routes.tsx` and all 17 feature `routes.ts` modules; `*` excluded); step 2 (WCAG 2.2 AA at three widths, two locales) → `VIEWPORT_BANDS` × `LOCALES` and the tagged scan; step 3 (one test per route, failure messages, fixme policy) → generated `test`/`test.fixme` titles and `context` message; step 4 (`--list` because browsers are absent) → Test evidence.

## Risk class

Cross-boundary for verification scope (all routes, two locales, three layout bands, browser matrix) but test-only: no production behavior, data or contract changes.

## Review owner

Senior React and Vite (staff-web e2e), with Senior TypeScript for the strict spec code.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix.

## Authorization boundaries and audit events

No new boundary or audit event. The spec reads the app's own `canAccessRoute` to derive denied patterns and marks those cells `test.fixme`; it does not grant access or change role policy. All four path parameters are opaque synthetic ids (`patient-…`, `note-…`, `prescription-…`) and the API stub returns only empty JSON. No patient data or credential appears in the file, logs or notes.

## Migration and rollout considerations

None. Test-only change.

## Test evidence

Commands executed in `apps/staff-web` (tool evidence; final lines quoted):

- `npm run typecheck` — final lines:
  `> smartclinic-staff-web@0.1.0 typecheck` / `> tsc --noEmit -p tsconfig.json` (exit 0; tsc prints nothing on success).
- `npx tsc --noEmit -p tsconfig.lint.json` — `TSC_LINT_EXIT=0` (needed because `tsconfig.json` excludes `e2e`; this config includes it).
- `npx eslint e2e/accessibilityScanResponsive.spec.ts --max-warnings 0` — `ESLINT_EXIT=0`.
- `python3 -I scripts/check-constraints.py` (from repo root) — `CONSTRAINTS_EXIT=0`.
- `npx playwright test --config e2e/playwright.config.ts e2e/accessibilityScanResponsive.spec.ts --list` — final line:
  `Total: 1800 tests in 1 file` (180 declarations × 10 configured Chromium projects; 840 active registrations, 960 `test.fixme` registrations). Cross-browser projects are omitted because no WebKit/Firefox binary exists.
- `bash apps/staff-web/check.sh` (first run) — **FAILED**: `Test Files 1 failed | 267 passed (268)`, `Tests 1 failed | 3044 passed (3045)`. The sole failure was the pre-existing e2e spec-set tripwire `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:166` (`expected [ …(15) ] to deeply equal [ …(9) ]`), because the new spec changed the committed spec set.
- `npx vitest run tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts` (repair pass 1, before fix) — **RED**: `Tests 1 failed | 2 passed (3)`, same `expected [ …(15) ] to deeply equal [ …(9) ]` at line 166.
- `npx vitest run tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts` (repair pass 1, after fix) — **GREEN**: `Test Files 1 passed (1)`, `Tests 3 passed (3)`.
- `bash apps/staff-web/check.sh` (repair pass 1, after fix) — **GREEN**: `Test Files 268 passed (268)`, `Tests 3045 passed (3045)`, `No files with missing coverage. 267 files fully covered. 8 empty files skipped.` Coverage summary: `Statements 100% (5107/5107)`, `Branches 100% (2634/2634)`, `Functions 100% (1697/1697)`, `Lines 100% (4903/4903)`. The jsdom `Not implemented: navigation` stack traces on stderr are pre-existing noise from unrelated view tests; they are not assertion failures (all tests and the gate passed).
- `npx vitest run tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts` (repair pass 2, current state) — **GREEN**: `Test Files 1 passed (1)`, `Tests 3 passed (3)`.
- `python3 -I scripts/check-constraints.py --base ff86f18d^ --require-base` (repair pass 2) — `CONSTRAINTS_EXIT=0` (no suppression, exclusion, skip or lowered threshold introduced).
- `python3 -I scripts/check-verdicts.py .agent/pr-notes/RESP-E2E-ACCESSIBILITY-SCAN-RESPONSIVE-20261124-004505.md --base 29467f47` (repair pass 2) — gate passes after the code-reviewer GO line below.
- `bash apps/staff-web/check.sh` (repair pass 2, final state after the comment-only fix) — **GREEN**: `CHECK_EXIT=0`, `Test Files 268 passed (268)`, `Tests 3045 passed (3045)`, `267 files fully covered`, `Statements 100% (5107/5107)`, `Branches 100% (2634/2634)`, `Functions 100% (1697/1697)`, `Lines 100% (4903/4903)`. A separate first run in this pass failed on a pre-existing flake at `tests/spec/platform/resilient-screens.test.ts:250` (`savedLeave.defaultPrevented` expected false, got true — a beforeunload cross-test-ordering issue unrelated to this test-only change); a clean re-run passed 3045/3045. No code change was made for that flake.

**No live axe scan ran.** All Playwright engine executable paths resolve to absent files in this sandbox (`chromium`, `firefox`, `webkit` → `existsSync() === false`; no system browser found). The `--list` result is discovery evidence only; it does not establish WCAG compliance. CI on the exact SHA must execute the scan. Entry-point trace: Playwright project → `e2e/accessibilityScanResponsive.spec.ts` → `page.goto(route)` → real `AppShell`/router → `AxeBuilder.analyze()` → serious/critical zero assertion.

## Coverage

No production source changed; e2e specs are excluded from Vitest (`--exclude 'e2e/**'`) and from the `tsconfig.json` typecheck, so no per-file production coverage metric applies. The canonical per-area command is `bash apps/staff-web/check.sh` (result recorded above). Every line/branch introduced is in the spec itself and enumerated below.

### Repair pass 1 — branch-to-test list

The coverage gate failed only because a test-data pin was stale, not because of an uncovered production line. No threshold was lowered and no exclusion, suppression or skip was added. The change is confined to `apps/staff-web/tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts`: the doc comment on `currentSpecs` now states the invariant, and the array gained the six committed specs the pin omitted. The deterministic test that exercises every newly enumerated case is the existing GAP tripwire `GAP: all-role, staging-RC and light/dark visual evidence is not yet recorded`, whose assertion at line 179 compares `e2eSpecs().sort()` with `currentSpecs.sort()`.

| Enumerated case added to the pin | Deterministic test (tool evidence) |
|---|---|
| `accessibilityScanResponsive.spec.ts` (this story's new spec) | GAP tripwire line 179, via `npx vitest run tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts` — RED at line 166 before the fix, GREEN after |
| `adminflow/admin-journey.spec.ts` | same assertion — set equality covers every member, so the 9→15 transition is asserted as a whole |
| `financeflow/finance-journey.spec.ts` | same |
| `pharmacyflow/pharmacy-bilingual-journey.spec.ts` | same |
| `radflow/radiology-bilingual-journey.spec.ts` | same |
| `therapyflow/therapy-journey.spec.ts` | same |

The other two assertions in the same GAP test are unchanged and still pass: the workflow filter at line 184 asserts `[]`, and the visual-check filter at line 191 asserts `[]`. Full re-run `bash apps/staff-web/check.sh` proved 100% statements/branches/functions/lines over all 267 non-empty source files.

Generation-time branches — exercised at module load, proven by `--list`:

| Branch | Combination that takes it |
|---|---|
| `for (target of SCAN_TARGETS)` — 30 targets | all 30 concrete routes |
| `for (band of VIEWPORT_BANDS)` — 3 | phone 360, tablet 768, desktop 1280 |
| `for (localeCase of LOCALES)` — 2 | en/ltr, ar/rtl |
| `target.pattern ?? target.path` left / right | 4 parameterised targets / 26 fixed paths |
| `canAccessRoute(...) === true` → `test` | 14 reachable routes × 3 × 2 = 84 declarations |
| `canAccessRoute(...) === false` → `test.fixme` | 16 denied routes × 3 × 2 = 96 declarations |

Runtime branches — declared, **not executed here** (no browser; discovery only):

| Branch | Intended combination |
|---|---|
| `locale === 'ar'` true / false | all ar / all en cells; `lang`/`dir` assertions before and after toggle |
| `bandWidth < COMPACT_MAX_WIDTH` true / false | ar × 360 (open `User menu` first) / ar × 768,1280 (header button) |
| `isStateScreen !== true` true / false | ordinary routes (settled-heading poll) / `/forbidden`, `/session-expired` (exempt) |
| settled-heading poll: state heading → not state heading | transient `Loading…`/error panel → real screen before axe scan |
| h1 vs h2 title | system notice pages (`/signed-out`, `/not-found`) / all other screens |
| `syntheticBody` pathname branches | appointments and pharmacy inbox → cursor page `{items:[],nextCursor:null}`; patients → `[]`; pharmacy inbox detail → shape-valid `InboxItemResponse`; pharmacy status → shape-valid `StatusViewResponse` with `events: []`; remaining local-fixture/role-denied endpoints → `{}` |
| serious/critical filter included sides | a real serious or critical finding (defect-detection; fails the zero assertion) |
| filter excluded side | minor/moderate finding passes through to the zero assertion |
| zero-violation assertion empty / non-empty | green run / defect detection |
| `SUBSTITUTE_HEADINGS` derived from both locale bundles | localized guard in en and ar |

The "true"/"included" sides of the substitute-heading guard and the serious/critical filter are defect-detection branches: no green test takes them, and no fault injection is possible without a browser. They are reported as unexecuted, not claimed covered.

## Review verdicts

- smartclinic-devsecops: GO
- smartclinic-coverage-auditor: GO
- smartclinic-test-engineer: GO
- smartclinic-plan-reviewer: NO-GO
- smartclinic-code-reviewer: GO

**Repair pass 2 (reviewer + tool evidence).** The prior NO-GO verdicts were based solely on the out-of-scope `currentSpecs` pin that kept `check.sh` red; repair pass 1 fixed that pin. The read-only smartclinic-code-reviewer was re-run against the final committed diff (`git diff 29467f47...HEAD`) and answered `VERDICT: GO`: the 15-spec pin is complete, the 30-route inventory is accurate, the 14-reachable/16-denied split matches AppShell's access logic, breakpoint/i18n/state-screen guards hold, synthetic fixtures carry no PHI, and no gate, contract or production rule is broken. No unresolved Critical or Required findings. Its one Optional finding — a misleading doc comment on `COMPACT_MAX_WIDTH` — was applied as a comment-only change and the reviewer re-confirmed `VERDICT: GO`. All three post-code reviewers required by `scripts/check-verdicts.py` now stand at GO. The historical `smartclinic-plan-reviewer: NO-GO` above shared the same pin-based cause and is a pre-code review, not one of the three gated post-code reviewers.

## Findings

- **Brief discrepancy (minor):** the brief says the Arabic toggle is labelled `العربية`; the shell actually labels it `اللغة` (`src/i18n/messages.ts:68`). The spec clicks the English name `Language` once to enter Arabic, so the difference does not affect the test. Harness journeys that use `العربية` mount different pages.
- **Role denial:** `useRole()` is hard-coded to `reception` (`src/app/useRole.ts:10`). Sixteen registered patterns are denied to that role by `canAccessRoute`; their 960 cells are `test.fixme` because the only reachable screen would be `ForbiddenPage`. The story's "zero violations on every route" cannot be claimed for those routes in this harness.
- **Spec-set pin — RESOLVED in repair pass 1:** `apps/staff-web/tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:85,179` pins the exact e2e spec file list. Its own comment says "a new journey must replace this pin". Repair pass 1 added the six omitted committed specs (`accessibilityScanResponsive`, `adminflow/admin-journey`, `financeflow/finance-journey`, `pharmacyflow/pharmacy-bilingual-journey`, `radflow/radiology-bilingual-journey`, `therapyflow/therapy-journey`) and documented the invariant; the targeted test and the full `check.sh` are green.
- **No live scan:** no browser binary is installed; `--list` is discovery only. The WCAG verdict must be produced by CI on the exact SHA.
- **DEV-only target:** `/design-preview` is registered only when `import.meta.env.DEV` is true; the default harness starts the Vite dev server, so it resolves. A staged `E2E_BASE_URL` build would render the catch-all for that path.
- **Test count:** 180 declarations become 1,800 registrations across the ten configured Chromium projects; with `fullyParallel: false` and retries this is a long CI job. The project name is in every failure message.
- **Evidence sources:** file reads and executed commands (tool); plan/test/code/coverage/security reviews (read-only reviewer agents). No simulated user and no human or clinical participant was involved.
- **Pre-existing flaky test (observed, not changed):** one `bash apps/staff-web/check.sh` run in repair pass 2 failed at `apps/staff-web/tests/spec/platform/resilient-screens.test.ts:250` (`expected savedLeave.defaultPrevented to be false` but got `true`) — a `beforeunload` listener/cross-test-ordering flake in a file this task does not touch. A clean re-run passed 3,045/3,045 with 100% coverage. No fix was attempted because the defect is outside task scope and the authoritative final run is green; recorded here so the next worker does not chase it.

## Why this is not finished

1. **RESOLVED across repair passes 1–2.** The red `bash apps/staff-web/check.sh` was caused solely by the stale `currentSpecs` pin at `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:85`. The pin now lists all 15 committed e2e specs; the targeted test passed 3/3 and the full gate passed 3,045/3,045 with 100% coverage (final run after the comment-only fix re-confirmed green). The read-only code reviewer re-ran on the final committed diff and answered `VERDICT: GO`; all three post-code reviewers required by `scripts/check-verdicts.py` are GO and the verdict gate passes. No gate step remains.
2. 16 role-denied routes are not scanned: `/patients/:id`, `/patients/:id/chart`, `/patients/:id/notes/:noteId`, `/admin/facilities`, `/admin/staff`, `/admin/config`, `/admin/audit`, `/messages`, `/results`, `/results/critical`, `/lab/accessions`, `/radiology`, `/pharmacy`, `/operations/exports`, `/operations/imports`, `/operations/queues`. Next step: an authorized-role + synthetic-API harness, then un-fixme those cells.
3. No WCAG verdict is established here because no browser binary exists; CI must run the real scan on the exact SHA.

The earlier request to create a task for the spec-set pin is withdrawn: the pin was fixed in repair pass 1 and is part of this pull request, so creating that task at merge would duplicate completed work.

```task-requests
[
  {
    "title": "Authorized-role harness for role-denied responsive accessibility scans",
    "details": "useRole() is hard-coded to reception, so 16 registered patterns render ForbiddenPage and their 960 responsive accessibility cells are test.fixme. Provide a synthetic authorized role plus endpoint-shaped API fixtures and un-fixme those cells so WCAG 2.2 AA is proven on the intended screens.",
    "scope": ["apps/staff-web/src/app/useRole.ts", "apps/staff-web/e2e/accessibilityScanResponsive.spec.ts"],
    "depends": []
  }
]
```

## Lessons for workers

- Before adding any file under `apps/staff-web/e2e/`, check `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts`: its `currentSpecs` pin must be updated in the same change or the canonical gate goes red.
- An accessibility sweep of a real route can silently scan `ForbiddenPage`, a Suspense fallback or a WEB-09 state panel. Assert the pathname, a settled title that is not a state heading, and mark role-denied cells fixme.
- `tsconfig.json` excludes `e2e`; run `npx tsc --noEmit -p tsconfig.lint.json` as well as `npm run typecheck` for a new spec.
- `test.fixme` is permitted by the story AC and is not matched by the constraint checker's `test.skip`/`.only` regex, but it must be reported under `## Why this is not finished`.
