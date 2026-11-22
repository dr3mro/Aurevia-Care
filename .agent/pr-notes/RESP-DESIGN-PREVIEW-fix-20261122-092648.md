# RESP-DESIGN-PREVIEW — fix pass 3 (RESP-DESIGN-PREVIEW-fix-20261122-092648)

## Summary

Fix pass 3 on the already-committed responsive `DesignPreview` slice (implementation
commit `68556991`, branch `swarm/RESP-DESIGN-PREVIEW-859`). I read the earlier findings
as notes and continued from them rather than restarting. The production change and its
new jsdom test are complete, scoped and green. I re-ran every in-scope gate myself with
executed commands and **found no production defect; no production file was changed in
this pass** — the only file added is this notes file. All staff-web gates pass with 100%
coverage. The single remaining `scripts/check.sh` failure is an environment artifact
(Python 3.11 + a missing empty surefire report directory), which I reproduced and for
which I proved the exact workaround; it is outside this story's protected scope.

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
React verdict: no blocking defects. UX verdict: no blocking defect after the mixed-script
measurement fix. This pass re-verified by executed command; no new reviewer was needed
because no production code changed.

## Data changes

None. No Flyway migration, backfill, lock, volume impact, rollback or forward-fix. No
reserved migration version used. No synthetic identifiers beyond the existing
`fixtures/synthetic.ts` values.

## Authorization boundaries and audit events

None. `DesignPreview` is a development-only visual page: no endpoint, tenant, role,
patient link or audit event was added or changed.

## Migration and rollout considerations

None. No build script, contract, workflow or tracker file was edited by this pass.

## Test evidence

Evidence source for every line below: **executed tool commands in this pass** (no human,
clinical or simulated-user evidence). Versions: Node v22.23.5, npm 10.9.9, Vitest 5.1.1,
Python 3.11.2. `npm ci` was required first (`node_modules` were absent).

1. Branch reconciliation: `git merge --no-edit origin/main` → `Already up to date.`
   Conflict-marker check in scope (`git grep -nE '^(<<<<<<<|>>>>>>>) ' -- apps/staff-web/`)
   → no matches.
2. `cd apps/staff-web && npm ci` — succeeded (exit 0); node_modules populated.
3. Targeted: `npx vitest run tests/responsive/app/DesignPreview.responsive.test.tsx
   tests/app/AppShell.test.tsx --reporter=dot` → `Test Files 2 passed (2)` /
   `Tests 37 passed (37)`, exit 0.
4. Typecheck (explicit re-run, frontend definition of done):
   `cd apps/staff-web && npm run typecheck` →
   `> smartclinic-staff-web@0.1.0 typecheck` / `> tsc --noEmit -p tsconfig.json`,
   exit 0, **no `error TS…`**.
5. Mandatory gate: `bash apps/staff-web/check.sh` → exit 0; all of `gen:api:check`,
   `npm run typecheck`, `npm run lint`, `npm run build`,
   `node scripts/check-bundle-size.mjs` and `npm run test:coverage` passed. Final lines:
   `Test Files 159 passed (159)` / `Tests 1916 passed (1916)` /
   `No files with missing coverage.` / `207 files fully covered.` /
   `Statements 100% (3722/3722)`, `Branches 100% (1892/1892)`,
   `Functions 100% (1258/1258)`, `Lines 100% (3556/3556)`. The React
   "useI18n/useTheme must be used inside …Provider" stack traces in the log are expected
   output from the negative provider tests; the run is green.
6. Coverage gate: `bash scripts/coverage-check.sh origin/main` → exit 0,
   `coverage check: passed` (same 100% figures).
7. Frontend policy: `bash scripts/frontend-check.sh` → exit 0,
   `frontend standards: policy and tool commands passed`.
8. Policy checks, all exit 0: `python3 -I scripts/check-docs.py`, `check-secrets.py`,
   `check-names.py`, `check-wiring.py`; `check-pack.py` →
   `Delivery tooling validated: 33 agents, 33 skills` after restoring `opencode.json`
   to HEAD truth (Finding 2).
9. Root check: `bash scripts/check.sh` — first run exit 1 **only** at the two Python
   tests in Finding 3 (`FileNotFoundError: apps/api/target/surefire-reports`); every
   staff-web and policy gate before it passed. After creating the empty report dir the
   full Python suite passes: `python3 -I -m unittest discover -s scripts/tests` →
   `Ran 1118 tests` / `OK`, exit 0. A full `scripts/check.sh` re-run with the directory
   present was started but stopped per the worker 5-minute guidance once it moved past
   the already-proven gates into the multi-minute Java/docs steps; no failing line
   appeared before it was stopped. The integrator/Delivery run the full gate.

Entry-point-to-test trace: dev-only route `/design-preview` → lazy
`createRoutes(includeDesignPreview)` in `src/app/routes.tsx` → `DesignPreview` →
`tests/app/AppShell.test.tsx` ("keeps the development preview interactive for visual
review": click/submit/echo) and the new
`tests/responsive/app/DesignPreview.responsive.test.tsx` (structure, roles, RTL and
keyboard order at all bands). Pixel / 44 px / 16 px / safe-area / 200 %-zoom paint checks
are Playwright E2E evidence (separate RESP-E2E tasks), as the story directs.

## Coverage

- Command: `bash apps/staff-web/check.sh` → `npm run test:coverage` (Vitest 5.1.1 / v8,
  `include: ['src/**/*.{ts,tsx}']`, `perFile: true`, 100% thresholds). Also confirmed by
  `bash scripts/coverage-check.sh origin/main`.
- Result: `No files with missing coverage.` / `207 files fully covered.` /
  `Statements 100% (3722/3722)` / `Branches 100% (1892/1892)` /
  `Functions 100% (1258/1258)` / `Lines 100% (3556/3556)`. No threshold, exclusion or
  skip was changed; no test disabled or ignored.
- Changed production file `src/app/DesignPreview.tsx`: 100% statements, branches,
  functions and lines. New branches introduced by this pass: **none** (only this notes
  file was added).
- Branches/behaviours from the earlier implementation, re-verified here: the change adds
  one hook call (`useBreakpoint`), one `className`, one `data-*` attribute and one
  existing-primitive `<MixedScriptText>` wrapper — no `if`, `?:`, `&&`, `||`, `switch` or
  `catch` in the changed code. Named tests: root `page` class + `data-breakpoint` →
  "renders the page with responsive classes …" (3 cases) + the 7-width viewport matrix;
  compact table region vs plain table → "shows every component with the table, fields
  and dialog for the band"; dialog `sheet`/`centered` → "opens the dialog with the
  band-appropriate view and restores focus" (3 cases); measurement `<bdi dir="auto">`
  under Arabic → RTL block (3 cases) with `measurement.tagName === 'BDI'`; dialog/submit
  callbacks → `AppShell.test.tsx`.

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association,
prescribing or critical results. The UX/WCAG-01 surface was reviewed by a simulated
subagent only — synthetic review, not clinical or human acceptance.

## Findings

1. **No production defect.** The earlier implementation is complete, scoped and green;
   no production file was changed in any fix pass. All in-scope gates pass.
2. **Environment artifact — CI `opencode.json` (tool evidence).** The worktree file was
   the CI profile copied under `skip-worktree` (`git ls-files -v opencode.json` → `S`),
   so `check-pack.py` would report it stale. Proven HEAD truth is correct: the committed
   `git show HEAD:opencode.json` equals the generator's local render and the worktree
   equalled `.github/opencode.ci.json`. I ran
   `git update-index --no-skip-worktree opencode.json && git checkout -- opencode.json`
   so the local check read HEAD truth (`H`, no diff, not committed). Do not regenerate or
   commit that protected file.
3. **Environment defect — Python 3.11 report glob (tool evidence, out of scope).**
   `scripts/check.sh` runs 1118 Python tests; two error:
   `test_worker_gate.MainTest.test_integration_failure_still_fails_the_gate` and
   `test_worker_gate_coverage.MainTest.test_maven_failure_with_failing_reports_still_fails`,
   both `FileNotFoundError: apps/api/target/surefire-reports`. Root cause (confirmed by
   reading `scripts/worker_gate.py:63` and the two tests): each test mocks
   `worker_gate.Path.is_dir → True`; `usable_reports()` then calls
   `Path(reports_dir).glob("TEST-*.xml")`, and Python 3.11's `Path.glob` calls
   `os.scandir` on the absent directory and raises. This is a pre-existing artifact of
   protected `scripts/tests/` (unchanged by this branch) surfacing on this worker's
   Python 3.11.2; the recorded local baseline is Python 3.15, where glob tolerates the
   missing directory. **Proven workaround:** create the empty directory
   (`mkdir -p apps/api/target/surefire-reports`) before the Python suite; then
   `python3 -I -m unittest discover -s scripts/tests` → `Ran 1118 tests` / `OK`. The dir
   is untracked and is not a code change. Left `scripts/` untouched as required.
4. **Pre-existing and out of scope (tool evidence):** committed conflict markers remain
   at HEAD in `apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7/Hl7ParseException.java`,
   `Hl7Parser.java` and `Hl7Segment.java`; none in `apps/staff-web/` (checked). Left
   untouched.
5. **Deliberate decision (earlier UX reviewer, simulated):** the root keeps its
   pre-existing inline `style={{ display:'grid', gap: tokens.spacing.lg }}` next to the
   new `page` class; it duplicates only display/gap, does not override safe-area padding
   or breakpoint rules, and preserves the theme spacing subscription.
6. jsdom cannot paint pixels, so 44×44 / 16 px / safe-area / 200 %-zoom claims are
   asserted through classes, attributes and structure as the story directs; computed
   values remain Playwright evidence (separate RESP-E2E tasks).

## Why this is not finished

The staff-web task deliverable **is complete**: every in-scope gate passes with 100%
coverage. The only failing command locally is `scripts/check.sh`, for one environmental
reason outside this story's protected scope:

- **Command:** `bash scripts/check.sh` (Python 3.11.2). **Error:** two `worker_gate` tests
  raise `FileNotFoundError: apps/api/target/surefire-reports`. **Cause:** a global
  `Path.is_dir → True` mock in protected `scripts/tests/` combined with Python 3.11's
  `Path.glob` calling `os.scandir` on a directory that is absent in a fresh worker.
  **Tried:** isolated both tests, read `worker_gate.py:40-69` and `:254-264`, and proved
  that `mkdir -p apps/api/target/surefire-reports` makes the full 1118-test Python suite
  pass (`OK`). **Next step:** run this gate under the documented Python 3.15 baseline or
  ensure the report directory exists (Maven creates it on a real run); separately, an
  owner should narrow those two test mocks or have `usable_reports()` treat a missing
  directory as empty — both in protected `scripts/`, not editable in this story.

No production or test change is needed for RESP-DESIGN-PREVIEW; the integrator's own gate
should re-judge at HEAD with the correct config profile and Python version.

## Lessons for workers

- A fix pass on a green, already-committed slice should re-run the full module gate and
  read the earlier notes on disk; a truncated PR-description snapshot is not proof of
  unfinished production work. Record verification honestly instead of inventing a change.
- In a cloud worker a red `check-pack` is the CI `opencode.json` under `skip-worktree`.
  Confirm with `git ls-files -v`, `cmp` against `.github/opencode.ci.json` and
  `git show HEAD:opencode.json`; restore to HEAD truth before a local check but never
  regenerate or commit that protected file.
- `npm ci` is required in a fresh worker before `npx vitest` / `npm run typecheck`.
- If `scripts/check.sh` fails in the Python suite on `apps/api/target/surefire-reports`,
  check `python3 --version` first: a global `Path.is_dir` mock in two `worker_gate` tests
  breaks `Path.glob` on Python 3.11 but not the 3.15 baseline. Creating the empty report
  dir makes the suite pass; treat it as an environment issue, not a staff-web defect.
- Expected negative-provider tests print React/jsdom stack traces in a green run; judge
  Vitest by the final `Test Files` / `Tests` lines, not by trace text.
