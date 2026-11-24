# RESP-E2E-TOUCH-TARGETS — verification of an already-delivered story

## Summary

Duplicate dispatch. The single scope file, `apps/staff-web/e2e/touchTargets.spec.ts`, already exists
on this checkout and is merged into `origin/main` (commits `90397033`
`test(staff-web): prove 44px coarse touch targets across responsive matrix` and `a2332a2d`
`docs(staff-web): align touch-target notes and reviewer verdicts`; the task file records PR #1558
merged to main). `HEAD` equals `origin/main` at `261d2169` and the working tree was clean at the
start of this run.

No file was added, edited or deleted in this run. I verified the delivered change against WEB-07
and UX-01 on the current base instead of reimplementing it: the 30-route × 10-project Playwright
matrix is green (64 passed, 246 scoped skips), the staff-web canonical gate is green, and the
three required post-code reviewers each returned `VERDICT: GO`.

The brief is stale on one point: it says the scope path is absent in this snapshot and needs
confirmation as a planned path or a stale reference. It is neither — the path is present, committed
and merged. The rest of the brief (requirements, scope, acceptance criteria, gate commands) is
accurate and was followed.

## Affected requirements

- WEB-07 — supported clients / coarse-pointer target matrix; `docs/design/responsive-layout.md`
  §2, §6 and R5 of the §13 verification matrix.
- UX-01 — WCAG 2.2 AA target sizing (`size.target.comfortable`, 44 × 44 CSS px on coarse
  pointers; 24 px remains the fine-pointer floor).

## Risk class

Routine — verification only in this run. The delivered change is a single new end-to-end
verification file; no production behavior, clinical data, authorization or contract changed.

## Review owner

aurevia-senior-react and aurevia-senior-typescript (staff web), aurevia-test-engineer,
aurevia-plan-reviewer before coding; aurevia-code-reviewer, aurevia-coverage-auditor and
aurevia-devsecops after the change. Fresh GO verdicts from the three post-code reviewers are
recorded under `## Review verdicts`; the earlier run's plan-reviewer and test-engineer verdicts
are recorded in `docs/pr/reports/RESP-E2E-TOUCH-TARGETS-20261124-004504.md`.

## Data changes

None. No migration, table, RLS policy, backfill, lock, volume impact, rollback or forward fix.
The delivered spec uses only synthetic identifiers (`SYN-0001`, `SYN-NOTE-0001`) and a synthetic
`Chrome/130` user agent. No real patient data, endpoint or credential appears in the spec or in
these notes.

## Authorization boundaries and audit events

None. No tenant, role, permission, patient-link, session or audit code was touched. The delivered
spec changes only how a test browser is described to the existing support-policy function; it does
not change that function or any production behavior.

## Migration and rollout considerations

None. No deployable artifact changed.

## Test evidence

Evidence source: the executing tool for every command in this section. Reviewer statements are
labelled separately under `## Review verdicts`. No maintainer, clinician or human participant was
available; all evidence is synthetic and simulated.

Entry point to test trace:
`apps/staff-web/e2e/playwright.config.ts` (10 installed projects: `chromium`, seven coarse
phone/tablet projects, and two fine-pointer desktop projects; `webServer` starts `npm run dev`)
→ `apps/staff-web/e2e/touchTargets.spec.ts` (30 concrete routes derived from
`src/app/routes.tsx` and the 28 `src/features/*/routes.ts` patterns; one test fanned out per
project; `main#main` readiness, settled-content check, pointer-parity guards, compact user-menu
open, measurement and separate width/height assertions) →
`src/app/AppShell.tsx` / `src/app/NavigationDrawer.tsx` / `src/theme/responsive.css` (the rendered
controls and the `.touch` 44 px coarse rule the spec measures).

Commands and final lines (cwd `apps/staff-web` unless stated):

- `git merge --no-edit origin/main` → `Already up to date.`; conflict-marker scan → `none`;
  `git status --short` → empty. `HEAD` and `origin/main` are both `261d2169`.
- Route-source drift check: `grep -rhoE "pattern: '[^']+'" src/features/*/routes.ts` → 28
  patterns, matching the spec's 28 feature routes plus `/` and `/design-preview` = the asserted 30.
- `npx playwright test --config e2e/playwright.config.ts e2e/touchTargets.spec.ts --list`
  → `Total: 310 tests in 1 file` (31 declared tests × 10 installed projects).
- Browser probe: Chromium, Firefox and WebKit executable paths were absent in a fresh worker;
  `npx playwright install chromium` → Chrome for Testing 159.0.8154.6 and headless shell installed.
  First launch attempt failed with `libglib-2.0.so.0: cannot open shared object file`;
  `npx playwright install-deps chromium` installed the Debian 12 libraries. This is an environment
  change only; no repository file recorded it.
- Single-test smoke after the dependency install:
  `npx playwright test --config e2e/playwright.config.ts e2e/touchTargets.spec.ts --project=phone-portrait-390x844 -g "touch targets ≥ 44px on /admin/audit" --retries=0`
  → `1 passed (3.0s)`.
- Full coarse/fine matrix:
  `npx playwright test --config e2e/playwright.config.ts e2e/touchTargets.spec.ts --retries=0`
  → `246 skipped`, `64 passed (2.4m)`, exit 0. The 246 skips are 156 scoped measured-defect
  `test.fixme` pairs plus 90 not-applicable fine-pointer executions (3 fine projects × 30
  routes); the 64 passes are 54 passing coarse route executions plus 10 route-matrix assertions.
  No threshold was lowered and no defect entry was removed to obtain this green.
- Typecheck, direct: `npm run typecheck` (`tsc --noEmit -p tsconfig.json`) → exit 0, no output.
  The story-related spec-inventory pin also passes on current HEAD:
  `npx vitest run tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts`
  → `Test Files  1 passed (1)`, `Tests  3 passed (3)`, exit 0.
- Full module gate: `bash apps/staff-web/check.sh` (run from the repo root; runs `gen:api:check`,
  `tsc --noEmit`, `eslint .`, `vite build`, the WEB-08 bundle-size gate, then Vitest). The first
  five stages passed; the Vitest stage is **RED on current `origin/main`** for a test unrelated to
  this story. Final lines: `Test Files  1 failed | 277 passed (278)`,
  `Tests  1 failed | 3141 passed (3142)`, `Duration  158.85s`; the failing test is
  `tests/spec/platform/route-chunk-most-kb-gzip-enforced-ci.test.ts > WEB-08 clause 3 … >
  GAP: the required CI frontend job never runs the gzip budget gate`, with
  `AssertionError: expected [] to deeply equal [ Array(1) ]` at line 106. The gate was run twice
  (background and foreground) with the same single failure. See `## Why this is not finished`.

## Coverage

No source-coverage delta in this run: no production or test file changed. The change under
verification is test-only and e2e-only, so `vite.config.ts` includes only `src/**` in coverage and
`package.json` runs Vitest with `--exclude 'e2e/**'`; the 100% thresholds were unchanged. The
closest supported measures are the executed route × project matrix above (64 passed, 246 scoped
skips, exit 0) and the module gate's source-coverage report. The current `bash apps/staff-web/check.sh`
run did not print a coverage summary because Vitest exited on the unrelated stale tripwire described
under `## Why this is not finished`; the earlier delivered run recorded `Statements 100% (5107/5107)`,
`Branches 100% (2634/2634)`, `Functions 100% (1697/1697)`, `Lines 100% (4903/4903)` on the then-current
base. I do not claim those numbers for the current HEAD, whose suite has since grown to 3142 tests.
The story's own spec-inventory pin passes on current HEAD (3/3), and no threshold, exclusion or
ignore comment was changed by this run.

Each branch of the delivered spec and the execution that takes it (verified on the current HEAD):

| Branch | Test execution that takes it |
|---|---|
| `namedCoarse` agrees with `project.use.hasTouch` | All 310 executions (both outcomes; fine projects skip after the assertion) |
| `if (!coarse)` → `testInfo.skip` | 3 fine projects × 30 routes = 90 not-applicable skips |
| `if (defect !== undefined)` → `test.fixme` | 156 exact (route, project) pairs; false for the 54 passing compact coarse executions |
| `viewport !== null/undefined && width < 768` → open user menu | true on the 2 compact phone projects (360, 390 px); false on the 5 wide coarse projects (844–1180 px) |
| `isMeasurable`: not visible / disabled / `aria-hidden` / skip-link class | every measured route; `a.skip-link` excluded on all 30 routes; closed-drawer controls not rendered at measurement time |
| `boundingBox() === null` → element not measured | non-empty measured-set assertion would fail; no null boxes were observed in the green matrix |
| `control.width < 44` width failure array | recorded RED run: 150 width observations, e.g. `#5 a[Today]: 8x17 < 44x44` |
| `control.height < 44` height failure array | recorded RED run: 6 height observations, e.g. `#7 a: 292x32 < 44x44` |
| `measured.length > 0` (blank-page guard) | all 64 passing executions |
| route-list count and uniqueness assertions | first declared test in all 10 projects |

No threshold was lowered, no exclusion or coverage-ignore comment was added, and no test was
disabled to make a gate pass. `test.fixme` marks only measured product defects and `testInfo.skip`
marks only the rule's not-applicable fine-pointer projects.

## Clinical and architecture review

No clinical data, prescribing, critical-result, release or tenant-boundary behavior changed.
Evidence is simulated and synthetic; it is not clinical, human or unit acceptance. The 44 px rule
is a documented layout contract, not a sign-off.

## Review verdicts

- aurevia-code-reviewer: GO
- aurevia-coverage-auditor: GO
- aurevia-devsecops: GO

Earlier delivered run (recorded in `docs/pr/reports/RESP-E2E-TOUCH-TARGETS-20261124-004504.md`):
aurevia-plan-reviewer: GO (after a pre-code NO-GO with nine Required gaps that were closed),
aurevia-test-engineer: GO (after a design-stage NO-GO with seven Required gaps that were closed).

All three fresh reviews found no Critical or Required issues. Their Optional findings are recorded
under `## Findings` for a future improvement task; none blocks this already-delivered story.

## Findings

1. **Duplicate dispatch, no work needed.** `tasks/00323000-RESP-E2E-TOUCH-TARGETS.md` is `[-]`
   with `agent: swarm-3`, `attempts: 5` and `evidence: PR #1558 merged to main (auto-reconciled)`.
   The scope file is committed on `origin/main`. Re-authoring the spec would have produced a
   duplicate change, so this run verified the delivered state and made no edits.
2. **Brief stale about the scope path.** It says `apps/staff-web/e2e/touchTargets.spec.ts` is
   absent and needs confirmation as a planned path or a stale reference. The path is present,
   committed and merged; the task file's note is out of date after PR #1558.
3. **Deferred real defects remain scoped as `test.fixme`.** 156 coarse route × project pairs are
   skipped for measured 8–17 px `NavRail` links at widths ≥ 768, 292 × 32 board detail links and
   96 × 32 result-entry controls. The 44 px rule was not weakened. The exact follow-up tasks are
   already recorded as `task-requests` in
   `docs/pr/reports/RESP-E2E-TOUCH-TARGETS-20261124-004504.md`; this run did not duplicate them.
4. **Reviewer Optional findings (non-blocking, for a follow-up).** The three reviewers suggest:
   normalize `knownDefects` project keys so conditional WebKit/Firefox coarse variants inherit the
   scoped fixmes; prefer measure-then-`test.fixme` so a future production fix self-clears; consider
   measuring compact drawer links, which the earlier report measured at 17 px when opened; derive
   the expected route list from the feature route modules instead of asserting length and
   uniqueness only; descriptors embed `aria-label` verbatim, so a non-local `E2E_BASE_URL` could
   put PHI-shaped labels in logs (default runs use the local synthetic dev server).
5. **Prior scope deviation (already merged).** The delivered PR also edited
   `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts` to extend a
   stale e2e-spec inventory pin from 9 to the real 15 paths. It is a test-only pin update with no
   threshold change and is required for the coverage gate; reviewers confirmed it is truthful.
   A future worker needing a file outside its declared scope should stop and request a task.
6. **Environment.** Chromium and its Debian 12 OS libraries were installed in this worker to run
   the matrix; no repository file records that change. WebKit/Firefox projects are omitted by the
   config because those binaries are absent, so the phone/tablet Chromium matrix is the one
   exercised here.

## Why this is not finished

This story's own change is already merged and its spec matrix is green, but the mandatory
`bash apps/staff-web/check.sh` gate does not pass on current `origin/main` (`261d2169`), so I do
not claim a fully green definition of done for this run.

- **Failing command:** `bash apps/staff-web/check.sh` (run from the repo root), twice — once in the
  background and once in the foreground with identical results.
- **Final lines:** `Test Files  1 failed | 277 passed (278)`,
  `Tests  1 failed | 3141 passed (3142)`. The first five gate stages (`gen:api:check`, typecheck,
  lint, build, bundle-size) passed; the failure is in the Vitest stage.
- **Error:** `tests/spec/platform/route-chunk-most-kb-gzip-enforced-ci.test.ts:106` →
  `AssertionError: expected [] to deeply equal [ Array(1) ]`; expected
  `['- run: bash scripts/frontend-check.sh']`, received `[]`.
- **Most likely cause:** a stale tripwire, not this story. `.github/workflows/delivery.yml` now
  defines the `frontend` job with three named steps (lines 100–106: `run: bash scripts/frontend-check.sh`,
  `run: bash apps/staff-web/check.sh`, `run: bash apps/patient-mobile/check.sh`). The test's
  `linesStarting(frontendJob, '- run:')` helper only matches the old `- run:` shorthand, so it sees
  no steps. The test's own comment says that when CI wires the budget gate, this tripwire must be
  replaced with a positive proof of enforcement; the workflow now calls `apps/staff-web/check.sh`,
  which runs `node scripts/check-bundle-size.mjs` after the build. The pin was left behind by a
  later pipeline change.
- **What I tried:** ran the gate twice to rule out a flake; ran `npm run typecheck` directly
  (exit 0, no output); ran the story-related spec-inventory test
  `automate-critical-journey-supported-role-real-browser.test.ts` (3/3 passed); confirmed with
  `git status --short` that the only working-tree change is my untracked notes file, so the red is
  present on untouched `origin/main`; read the failing test and the workflow job to identify the
  exact mismatch. I did not edit `.github/`, `scripts/`, `tasks/` or any out-of-scope test to make
  the gate pass.
- **Exact next step:** a separate task replaces the GAP tripwire in
  `apps/staff-web/tests/spec/platform/route-chunk-most-kb-gzip-enforced-ci.test.ts` with a positive
  assertion that the `frontend` CI job runs `bash apps/staff-web/check.sh` (which enforces the
  WEB-08 gzip budget) and flips the clause to proven, then reruns
  `bash apps/staff-web/check.sh` and `bash scripts/coverage-check.sh origin/main`. Request recorded
  below. No change to this already-merged touch-target spec is needed.

## Lessons for workers

- Before authoring any spec on a re-dispatched story, check `git log -- <scope path>` and the task
  file's `evidence:`/`pr:` fields. If the change is already on `origin/main`, verify it and report
  the duplicate dispatch instead of creating a competing commit.
- In a fresh cloud image, a Playwright browser download is not enough on Debian 12:
  `npx playwright install-deps chromium` is required before any real launch.
- A "measure every control" test is only honest if a RED run was captured first and fixmes are
  scoped to reproduced (route, project) pairs; never lower the threshold to get green.
- When a `test.fixme` list is hardcoded, a production fix does not auto-clear it; the removal step
  must be part of the fix task, and the full matrix must be rerun then.
- A pinned tripwire that searches text for `- run:` is defeated by named CI steps (`name:` followed
  by `run:`). When a pipeline refactor changes step style, the gate goes red without any product
  regression; distinguish this from a real failure by reading the test and the workflow before
  touching anything.

```task-requests
[
  {
    "title": "Replace stale WEB-08 CI enforcement tripwire with a positive proof",
    "details": "apps/staff-web/tests/spec/platform/route-chunk-most-kb-gzip-enforced-ci.test.ts:97-113 pins the frontend job's run steps to ['- run: bash scripts/frontend-check.sh'] but .github/workflows/delivery.yml now defines three named steps (lines 100-106), including 'run: bash apps/staff-web/check.sh' which enforces the WEB-08 gzip budget after the build. bash apps/staff-web/check.sh fails 1/3142 tests with 'AssertionError: expected [] to deeply equal [ Array(1) ]' on untouched origin/main 261d2169. The test's own comment says the GAP must be replaced with a positive proof when CI wires the gate. Update the tripwire to assert the current enforcement (the named step that runs apps/staff-web/check.sh and its post-build check-bundle-size.mjs invocation), flip the clause to proven, then rerun bash apps/staff-web/check.sh and bash scripts/coverage-check.sh origin/main. Do not lower a threshold or skip the test.",
    "scope": ["apps/staff-web/tests/spec/platform/route-chunk-most-kb-gzip-enforced-ci.test.ts"],
    "depends": []
  }
]
```
