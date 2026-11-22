# RESP-DESIGN-PREVIEW — fix pass 2 (RESP-DESIGN-PREVIEW-fix-20261122-091037)

## Summary

Fix pass 2 on the already-committed responsive `DesignPreview` slice (implementation
commit `68556991`, branch `swarm/RESP-DESIGN-PREVIEW-859`). I took the earlier
findings as notes and did not restart the work. The production change and its new
jsdom test are complete, scoped and green: I re-ran the targeted tests, the mandatory
`apps/staff-web/check.sh` gate, the explicit typecheck, the coverage gate, the
frontend policy gate and every policy check. **No production defect was found; no
production file was changed in this pass.** This notes file is the only file added by
this pass. All in-scope gates pass with 100% coverage; the only failing commands are
two environment issues documented under `## Findings` and `## Why this is not
finished` (CI-profile `opencode.json` under `skip-worktree`, and two pre-existing
Python tooling tests that error on this worker's Python 3.11.2). Both are outside
this story's staff-web scope and protected from worker edits.

## Affected requirements

- **WEB-07** — supported clients; staff-web responsive layout and browser matrix.
- **UX-01** — English/Arabic RTL, mixed-script clinical text and WCAG 2.2 AA layout.

No other requirement IDs touched.

## Risk class

Routine (contained staff-web presentation change to one component plus one new test
file), with a simulated UX review because RTL/WCAG is in scope (`docs/WORKFLOW.md`).

## Review owner

`smartclinic-senior-react` and `smartclinic-ux-reviewer` — earlier advisory subagent
reviews, simulated/synthetic evidence only; no human or clinical participant. Senior
React verdict: no blocking defects. UX verdict: no blocking defect after the
mixed-script measurement fix. This fix pass re-verified by executed command; no new
reviewer was needed because no production code changed.

## Data changes

None. No Flyway migration, backfill, lock, volume impact, rollback or forward-fix.
No reserved migration version used. No synthetic identifiers beyond existing
`fixtures/synthetic.ts` values.

## Authorization boundaries and audit events

None. `DesignPreview` is a development-only visual page: no endpoint, tenant, role,
patient link or audit event was added or changed.

## Migration and rollout considerations

None. No build script, contract, workflow or tracker file edited by this pass.

## Test evidence

Evidence source for every line below: **executed tool commands in this pass** (no
human, clinical or simulated-user evidence). Versions: Node v22.23.5, npm 10.9.9,
Vitest 5.1.1, Python 3.11.2. `npm ci` was required first (node_modules were absent).

1. `cd apps/staff-web && npm ci` — succeeded (exit 0); node_modules populated.
2. Branch reconciliation: `git merge --no-edit origin/main` → `Already up to date.`
   Conflict-marker check in scope (`git grep` for merge-marker line prefixes under
   `apps/staff-web/`) → no matches.
3. Targeted: `npx vitest run tests/responsive/app/DesignPreview.responsive.test.tsx
   tests/app/AppShell.test.tsx --reporter=dot` → `Test Files 2 passed (2)` /
   `Tests 37 passed (37)`, exit 0.
4. Mandatory gate: `bash apps/staff-web/check.sh` → exit 0; all of `gen:api:check`,
   `npm run typecheck`, `npm run lint`, `npm run build`,
   `node scripts/check-bundle-size.mjs`, `npm run test:coverage` passed. Final lines:
   `Test Files 159 passed (159)` / `Tests 1916 passed (1916)` /
   `No files with missing coverage.` / `207 files fully covered.` /
   `4 empty files skipped.` The React "useI18n/useTheme must be used inside …Provider"
   stack traces are expected output from the negative provider tests
   (`tests/i18n/I18nProvider.test.tsx`, `tests/theme/ThemeProvider.test.tsx`); the run
   is green.
5. Typecheck final lines (explicit re-run, frontend definition of done):
   `cd apps/staff-web && npm run typecheck` →
   `> smartclinic-staff-web@0.1.0 typecheck` / `> tsc --noEmit -p tsconfig.json`,
   exit 0, **no `error TS…`**.
6. Coverage gate: `bash scripts/coverage-check.sh origin/main` → exit 0,
   `coverage check: passed` (runs staff-web typecheck + test:coverage).
7. Frontend policy: `bash scripts/frontend-check.sh` → exit 0,
   `frontend standards: policy and tool commands passed`.
8. Policy checks exit 0, no output: `check-docs.py`, `check-secrets.py`,
   `check-names.py`, `check-wiring.py`; `check-pack.py` reported 33 agents/33 skills
   after the `opencode.json` restoration in Finding 2.
9. `bash scripts/check.sh` → exit 1 at two environmental points, NOT at any
   staff-web gate: (a) with the worktree CI profile it stops at
   `check-pack: opencode.json is stale`; (b) after restoring HEAD truth it reaches the
   Python suite, which ends `FAILED (errors=2)` on the two `worker_gate` tests in
   Finding 3. See `## Why this is not finished`.

Entry-point-to-test trace: dev-only route `/design-preview` → lazy
`createRoutes(includeDesignPreview)` in `src/app/routes.tsx` → `DesignPreview` →
`tests/app/AppShell.test.tsx` ("keeps the development preview interactive for visual
review": click/submit/echo) and the new
`tests/responsive/app/DesignPreview.responsive.test.tsx` (structure, roles, RTL,
keyboard order at all bands). Pixel, 44 px, 16 px, safe-area and 200 %-zoom paint
checks remain Playwright E2E evidence (separate RESP-E2E tasks), as the story directs.

## Coverage

- Command: `bash apps/staff-web/check.sh` → `npm run test:coverage` (Vitest 5.1.1 / v8,
  `include: ['src/**/*.{ts,tsx}']`, `perFile: true`, 100 % thresholds). Also confirmed
  by `bash scripts/coverage-check.sh origin/main`.
- Result: `No files with missing coverage.` / `207 files fully covered.` /
  `Statements 100% (3722/3722)` / `Branches 100% (1892/1892)` /
  `Functions 100% (1258/1258)` / `Lines 100% (3556/3556)`. No threshold, exclusion or
  skip was changed; no test disabled or ignored.
- Changed production file `src/app/DesignPreview.tsx`: 100 % statements, branches,
  functions and lines. New branches introduced by this fix pass: **none** (only this
  notes file was added).
- Branches/behaviours from the earlier implementation, re-verified here: the change
  adds one hook call, one `className`, one `data-*` attribute and one existing
  primitive wrapper — no `if`, `?:`, `&&`, `||`, `switch` or `catch` in the changed
  code. Named tests: root `page` class + `data-breakpoint` → "renders the page with
  responsive classes …" (3 cases) + 7-width viewport matrix; compact table region vs
  plain table → "shows every component with the table, fields and dialog for the
  band"; dialog `sheet`/`centered` → "opens the dialog with the band-appropriate view
  and restores focus" (3 cases); measurement `<bdi dir="auto">` under Arabic → RTL
  block (3 cases) with `measurement.tagName === 'BDI'`; dialog/submit callbacks →
  `AppShell.test.tsx`.

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association,
prescribing or critical results. The UX/WCAG-01 surface was reviewed by a simulated
subagent only — synthetic review, not clinical or human acceptance.

## Findings

1. **No production defect.** The earlier implementation is complete, scoped and
   green; no production file was changed in either fix pass. All in-scope gates pass.
2. **Environment artifact — CI `opencode.json` (tool evidence).** The worktree file
   is the CI profile copied under `skip-worktree` (`git ls-files -v` → `S`), so
   `check-pack.py` reports it stale. Proven HEAD truth is correct: the committed
   `git show HEAD:opencode.json` equals the generator's local render, and worktree
   equals `.github/opencode.ci.json` (the CI render). I mirrored `agent-run.sh`
   `restore_config` (`git update-index --no-skip-worktree` + `git checkout --`) so the
   local check reads HEAD truth; this produces no diff and is not committed. Do not
   regenerate or commit that protected file.
3. **Environment defect — Python 3.11 test mock (tool evidence, out of scope).**
   `scripts/check.sh` → `bash scripts/python-coverage.sh` runs 1118 Python tests; two
   error: `test_worker_gate.MainTest.test_integration_failure_still_fails_the_gate`
   and `test_worker_gate_coverage.MainTest.test_maven_failure_with_failing_reports_still_fails`,
   both `FileNotFoundError: apps/api/target/surefire-reports`. Root cause: each test
   globally mocks `worker_gate.Path.is_dir → True`; `Path.glob()` (Python 3.11) then
   calls `os.scandir` on the absent directory and raises. This is pre-existing in
   protected `scripts/tests/` (unchanged by this branch, reproduces in isolation),
   unrelated to this staff-web change, and left untouched. The recorded local Python
   baseline is 3.15, where this mock does not break glob.
4. **Pre-existing and out of scope (tool evidence):** committed conflict markers
   remain at HEAD in `apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7/Hl7ParseException.java`,
   `Hl7Parser.java` and `Hl7Segment.java`; none in `apps/staff-web/`. Left untouched.
5. **Deliberate decision (earlier UX reviewer, simulated):** the root keeps its
   pre-existing inline `style={{ display:'grid', gap: tokens.spacing.lg }}` next to
   the new `page` class; it duplicates only display/gap, does not override safe-area
   padding or breakpoint rules, and preserves the theme spacing subscription.
6. jsdom cannot paint pixels, so 44×44 / 16 px / safe-area / 200 %-zoom claims are
   asserted through classes, attributes and structure as the story directs; computed
   values remain Playwright evidence (separate RESP-E2E tasks).

## Why this is not finished

The staff-web task deliverable itself **is complete**: all in-scope gates pass with
100 % coverage. Two commands still fail locally, both environmental and outside this
story's protected scope, so no change here can make them green:

- **Command:** `bash scripts/check.sh`. **Error A:** with the CI profile active it
  stops at `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. **Cause:**
  cloud-worker `skip-worktree` CI profile (Finding 2). **Tried:** restored the file to
  HEAD truth (mirroring `agent-run.sh restore_config`); no diff, not committed.
  **Next step:** integrator/Delivery judge this gate at HEAD, where the committed
  profile is correct; do not regenerate the file.
- **Error B (after the restore):** Python suite ends `FAILED (errors=2)` on the two
  `worker_gate` tests in Finding 3. **Cause:** pre-existing global `Path.is_dir` mock
  incompatible with Python 3.11.2 `Path.glob`; the documented local baseline is 3.15.
  **Tried:** isolated both tests, reproduced the `FileNotFoundError`, and traced it
  to `mock.patch.object(worker_gate.Path, "is_dir", return_value=True)` combined with
  the absent `apps/api/target/surefire-reports`. **Next step:** run this gate under
  the supported Python version; separately, an owner should narrow those two test
  mocks (e.g. patch only `worker_gate.Path.is_dir` for the single Path under test or
  create the report dir) — protected `scripts/tests/`, not editable in this story.

No production or test change is needed for RESP-DESIGN-PREVIEW; the integrator's own
gate should re-judge at HEAD with the correct config profile and Python version.

## Lessons for workers

- A fix pass on a green, already-committed slice should re-run the full module gate
  and read the earlier notes on disk; a truncated PR-description snapshot is not proof
  of unfinished production work. Record verification honestly instead of inventing a
  change, and keep the notes concise so the integrator's description capture is not
  truncated mid-sentence.
- In a cloud worker, a red `check-pack` is the CI `opencode.json` under
  `skip-worktree`. Confirm with `git ls-files -v`, `cmp` against both
  `.github/opencode.ci.json` and `git show HEAD:opencode.json`; restore to HEAD truth
  before a local check (mirroring `restore_config`) but never regenerate or commit it.
- `npm ci` is required in a fresh worker before `npx vitest` / `npm run typecheck`;
  without `node_modules`, `npx` can install a different Vitest and fail on
  `vitest/config` — an environment failure, not a test failure.
- If `scripts/check.sh` fails in the Python suite on `apps/api/target/surefire-reports`,
  check `python3 --version` first: a global `Path.is_dir` mock in two `worker_gate`
  tests breaks `Path.glob` on Python 3.11 but not the recorded 3.15 baseline. Treat it
  as an environment issue, not a staff-web defect.
- Expected negative-provider tests print React/jsdom stack traces in a green run;
  judge Vitest by the final `Test Files` / `Tests` lines, not by trace text.
