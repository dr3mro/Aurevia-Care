# RESP-DESIGN-PREVIEW — fix pass (2026-11-22T12:42Z)

## Summary

Continuation of the earlier fix passes, not a restart. The story deliverable —
a responsive `DesignPreview` for the staff web — is complete, scoped to the two
authorised files and green with 100% coverage. This pass changed **no**
production or test file and added only this notes entry. The reason the branch
has not reached a green local repository gate is a **pre-existing, out-of-scope**
defect in two protected `scripts/tests/` unit tests, confirmed again here from
executed tool output and byte-identical to `origin/main`. A story worker is not
authorised to edit `scripts/`; the required repair is requested below as an
integrator-owned `FIX-` task.

## Affected requirements

- WEB-07 — staff web usable from 320 px; reflow, no hidden clinical column, logical properties.
- UX-01 — WCAG 2.2 AA, keyboard/screen-reader roles and English/Arabic RTL.

Decision context: `docs/decisions/0004-responsive-staff-web.md`,
`docs/design/responsive-layout.md`.

## Acceptance mapping

| Acceptance scenario (TODO RESP-DESIGN-PREVIEW) | Implementation | Test |
|---|---|---|
| Works at 320/360/390/768/820/1024/1280 px, LTR and RTL | `className="page"`, `data-breakpoint`, `useBreakpoint()` wrapper, logical inline styles, `MixedScriptText` for the measurement | `DesignPreview.responsive.test.tsx` 7-width viewport matrix + three bands × en/ar |
| Columns stack / table region on phones, labelled region on medium/expanded; no clinical column hidden | existing `DataTable`/`Form`/`Dialog` primitives read the same breakpoint; full `<caption>`/`<th scope="col">` set unchanged | "shows every component with the table, fields and dialog for the band" (3 cases) |
| Coarse pointers 44×44, inputs 16 px, safe-area insets | existing `.touch`/`@media (pointer: coarse)` and `.field` classes; `page` logical padding | asserts `touch`/`field` classes and `data-coarse-pointer` per band (computed pixels are Playwright evidence) |
| Logical properties only; no hidden clinical column | no new CSS; `page` class + logical inline styles only | authored-inline-style regex + full header/cell set present in every band |
| Preview shows each component at all three width classes | every child renders at every band with its own band structure | compact labelled region vs plain table; `data-breakpoint` on form/input/dialog/button/`bdi`; dialog `sheet`/`centered` |
| Labels, roles, keyboard order unchanged | no text or API change | `getByRole`/`getByLabelText` and focusable-order array at each band |
| Existing tests still pass | minimal production diff (24 lines) | `tests/app/AppShell.test.tsx` + full 2054-test suite green |

## Risk class

Routine — a contained staff-web presentation change in one component plus one
new test file. A simulated UX review is in scope because RTL/WCAG is involved
(`docs/WORKFLOW.md`). No clinical, patient-identity, permission or data change.

## Review owner

`smartclinic-senior-react` and `smartclinic-ux-reviewer` — the advisory
subagent verdicts recorded on PR #708 found **no blocking defects**. Evidence is
simulated/reviewer only; no human, clinical or patient participant. The two
skills were reloaded this pass; no new review was commissioned because the diff
is unchanged from the reviewed one.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix.
No reserved migration version was used. No synthetic identifiers beyond the
existing `fixtures/synthetic.ts` values.

## Authorization boundaries and audit events

None. `DesignPreview` is a development-only visual page; no endpoint, tenant,
role, patient link or audit event was added or changed.

## Migration and rollout considerations

None. No build script, contract, workflow or tracker file was edited by this worker.

## Test evidence

Evidence source for every line: executed tool commands this pass (tool evidence)
plus the earlier subagent verdicts above (reviewer evidence). Environment:
Node v22.23.5, npm 10.9.9, Vitest 5.1.1, Python 3.11.2, `opencode.json` tracked
as `S` (CI profile under `skip-worktree`).

1. Branch reconciliation: `git merge --no-edit origin/main` → `Already up to date.`
   (exit 0). Head `f678529d`. Conflict-marker scan finds only the three
   `exchange/hl7/Hl7*.java` Javadoc blocks that are committed on `origin/main`
   (verified with `git show origin/main:<file>`), outside this scope.
2. Mandatory frontend gate: `bash apps/staff-web/check.sh` (exit 0). `npm ci`
   installed the pinned dependencies, then `gen:api:check`, `npm run typecheck`,
   `npm run lint`, `npm run build`, `node scripts/check-bundle-size.mjs` and
   `npm run test:coverage` all passed. Final lines:
   `Test Files  172 passed (172)`, `Tests  2054 passed (2054)`,
   `No files with missing coverage.`, `213 files fully covered.`,
   `4 empty files skipped.` The `useTheme must be used inside ThemeProvider`
   stack traces are the expected output of the negative provider tests; the run
   is green.
3. **Typecheck final lines (frontend definition of done, explicit re-run):**
   `cd apps/staff-web && npm run typecheck` →
   `> smartclinic-staff-web@0.1.0 typecheck` /
   `> tsc --noEmit -p tsconfig.json`, exit 0, **no `error TS…`**.
4. Coverage gate: `bash scripts/coverage-check.sh origin/main` → `coverage check: passed` (exit 0).
5. Frontend policy: `bash scripts/frontend-check.sh` → `frontend standards: policy and tool commands passed`, 1 test passed (exit 0).
6. Naming: `python3 -I scripts/check-names.py` → exit 0, no output.
7. Out-of-scope defect reproduced deliberately:
   `python3 -I -m unittest discover -s scripts/tests -p 'test_worker_gate*.py'`
   → `Ran 143 tests`, `FAILED (errors=2)`, both
   `FileNotFoundError: [Errno 2] No such file or directory: 'apps/api/target/surefire-reports'`
   at `scripts/worker_gate.py:63`. Environment confirmed: Python 3.11.2 and no
   `apps/api/target/surefire-reports` directory on this fresh runner.
8. Byte-identity check: `git diff --quiet origin/main --` on
   `scripts/tests/test_worker_gate.py`, `scripts/tests/test_worker_gate_coverage.py`
   and `scripts/worker_gate.py` → all identical to `origin/main`; the defect is
   pre-existing, not introduced by this branch.
9. End-of-run repository check (capped at 280 s to respect the 5-minute limit):
   `bash scripts/check.sh` → exit 1 at the first stage with the single line
   `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. Provenance
   confirmed this pass: `git ls-files -v opencode.json` → `S`; the worktree file
   is byte-identical to `.github/opencode.ci.json` (CI profile under
   `skip-worktree`) while `git show HEAD:opencode.json` is the correct generated
   local profile. No later stage ran and no file was changed. This is the
   documented OPS-CW-003-01 worker artifact, not a change defect.

Entry-point-to-test trace: dev-only route `/design-preview` → lazy
`createRoutes(includeDesignPreview)` in `src/app/routes.tsx` → `DesignPreview` →
`tests/app/AppShell.test.tsx` ("keeps the development preview interactive for
visual review": click/submit/echo, dev only) and the new
`tests/responsive/app/DesignPreview.responsive.test.tsx` (structure, roles, RTL,
keyboard order at all bands). Pixel/44 px/16 px/safe-area/200 %-zoom paint checks
remain Playwright E2E evidence in the separate RESP-E2E tasks, as the story
directs; jsdom cannot lay out pixels.

## Coverage

- Command: `bash apps/staff-web/check.sh` → `npm run test:coverage` (Vitest v8,
  `include: ['src/**/*.{ts,tsx}']`, per-file 100% thresholds). Confirmed by
  `bash scripts/coverage-check.sh origin/main` and by reading
  `apps/staff-web/coverage/coverage-summary.json`.
- Result: `No files with missing coverage.` / `213 files fully covered.` /
  `Statements 100% (3914/3914)` / `Branches 100% (1990/1990)` /
  `Functions 100% (1320/1320)` / `Lines 100% (3737/3737)`. No threshold,
  exclusion or skip was changed; no test was disabled or ignored.
- Changed production file `apps/staff-web/src/app/DesignPreview.tsx`: lines
  9/9, statements 9/9, functions 4/4, branches 0/0 → all 100%.
- New branches added by the implementation: **none**. It adds one hook call, one
  `className`, one `data-*` attribute and one existing-primitive wrapper; no
  `if`, `?:`, `&&`, `||`, `switch` or `catch`. This fix pass added no production
  branch at all (notes file only).
- Named tests for the implementation's behaviors: root `page` class +
  `data-breakpoint=band` → "renders the page with responsive classes …" (3 bands)
  and the 7-width matrix; compact labelled table region vs medium/expanded plain
  table → "shows every component with the table, fields and dialog for the band";
  dialog `sheet`/`centered` and sticky header → "opens the dialog with the
  band-appropriate view and restores focus" (3 bands); Arabic measurement
  `<bdi dir="auto">` → RTL block (3 bands); dialog-open/close and note-submit
  callbacks → `AppShell.test.tsx`.

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association,
prescribing or critical result is touched. The simulated UX review is recorded
under Review owner.

## Findings

1. **The story deliverable is complete and green (tool evidence).** All in-scope
   gates pass at 100% coverage; no production or test file was changed in this
   or the last four fix passes. Do not revert or rewrite the `DesignPreview.tsx`
   diff or its test.
2. **Out-of-scope gate defect, reproduced again (tool evidence).**
   `usable_reports()` in protected `scripts/worker_gate.py:54-69` calls
   `Path(reports_dir).glob("TEST-*.xml")` on the default
   `apps/api/target/surefire-reports`. `main()` guards with `reports.is_dir()`,
   but two tests in protected `scripts/tests/` mock `Path.is_dir → True`:
   - `test_worker_gate.MainTest.test_integration_failure_still_fails_the_gate`
     (~line 333),
   - `test_worker_gate_coverage.MainTest.test_maven_failure_with_failing_reports_still_fails`
     (~line 552).
   On Python 3.11.2, `Path.glob` then calls `os.scandir` on the absent directory
   and raises `FileNotFoundError`. Both files are byte-identical to
   `origin/main`; the failure is environmental/pre-existing, not caused by this
   branch. `scripts/` is protected (AGENTS.md, delivery skill), and a story
   PR that edits it is never auto-merged, so it was deliberately left untouched.
3. **Exact repair (from the step-9 debugger report; test-only, no production
   change).** In `test_integration_failure_still_fails_the_gate`, add
   `mock.patch.object(worker_gate, "usable_reports", return_value=True)` to the
   inner `with` block so `decide()` is reached and no glob occurs. In
   `test_maven_failure_with_failing_reports_still_fails`, wrap the existing
   `self.assertEqual(self.run_main(...), 1)` call in
   `with mock.patch.object(worker_gate, "usable_reports", return_value=True):`.
   Do **not** change `worker_gate.py`, `main()`, any threshold/exclusion/skip,
   `REPORTS` or the existing `UsableReportsTest` cases. Prove it with
   `python3 -I -m unittest discover -s scripts/tests -p 'test_worker_gate*.py'`
   → `OK`, then `bash scripts/python-coverage.sh` (no production line changed)
   and `bash scripts/worker-check.sh`. This must be an integrator-owned `FIX-`
   repair worker with `scripts/` authority — not this story worker.
4. **Environment artifact — CI `opencode.json` (tool evidence).** `git ls-files -v
   opencode.json` → `S`; the worktree holds the CI profile, so `check.sh`'s
   `check-pack` stage reports `opencode.json is stale: run scripts/gen-opencode.py`
   (OPS-CW-003-01). HEAD truth (`git show HEAD:opencode.json`) is the correct
   generated local profile. The file was not regenerated or committed; this is
   a documented worker-image artifact, not a change defect.
5. **Pre-existing and out of scope (tool evidence):** committed conflict markers
   inside Javadoc blocks in
   `apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7/{Hl7ParseException,Hl7Parser,Hl7Segment}.java`
   are on `origin/main`, do not break compilation and were left untouched.
6. jsdom cannot paint pixels, so 44×44 / 16 px / safe-area / 200 %-zoom claims
   are asserted through classes, attributes and structure as the story directs;
   computed values remain separate Playwright E2E evidence.

```task-requests
[
  {
    "title": "FIX-WORKER-GATE-REPORTS: make two worker_gate tests self-contained on Python 3.11",
    "details": "Two pre-existing unit tests error on a fresh runner (Python 3.11.2, no apps/api/target/surefire-reports): test_worker_gate.MainTest.test_integration_failure_still_fails_the_gate and test_worker_gate_coverage.MainTest.test_maven_failure_with_failing_reports_still_fails. Both mock Path.is_dir to True, so main() calls usable_reports(), whose Path.glob on the absent directory raises FileNotFoundError. Fix (test-only): add mock.patch.object(worker_gate, 'usable_reports', return_value=True) to the inner with block of the first test (~scripts/tests/test_worker_gate.py:333), and wrap the existing self.assertEqual(self.run_main(argv=['1'], env={'STORY_ID': 'FIX-01'}, ...), 1) call (~scripts/tests/test_worker_gate_coverage.py:552-555) in the same usable_reports patch. Keep assertions, comments' intent, all thresholds, REPORTS, main() and UsableReportsTest cases unchanged; do not touch worker_gate.py. Verify: python3 -I -m unittest discover -s scripts/tests -p 'test_worker_gate*.py' -> OK; bash scripts/python-coverage.sh passes (no production line changed); bash scripts/worker-check.sh passes. This is an integrator-owned repair because a RESP story worker may not edit scripts/.",
    "scope": "scripts/tests/test_worker_gate.py, scripts/tests/test_worker_gate_coverage.py",
    "depends": []
  }
]
```

## Why this is not finished

The staff-web deliverable itself **is** finished: `bash apps/staff-web/check.sh`
exits 0 with 100% coverage, typecheck has no `error TS…`, and the coverage and
frontend-policy gates pass. The branch is not finished only because one
repository-level check fails for a reason outside this protected scope:

- **Command:** `python3 -I -m unittest discover -s scripts/tests -p 'test_worker_gate*.py'`
  (and therefore `scripts/check.sh` / `scripts/worker-check.sh` / the integrator
  gate). **Error:** `FileNotFoundError: [Errno 2] No such file or directory:
  'apps/api/target/surefire-reports'` at `scripts/worker_gate.py:63`, for two
  tests. **Cause:** global `Path.is_dir → True` mocks in protected `scripts/tests/`
  combined with Python 3.11.2's `Path.glob` calling `os.scandir` on the absent
  directory; the recorded baseline is Python 3.15 and CI runs where surefire
  reports already exist, which mask the bug. **Tried this pass:** reproduced the
  two errors in isolation with executed output, confirmed the three `scripts/`
  files are byte-identical to `origin/main`, confirmed Python 3.11.2 and the
  missing directory, and re-ran every in-scope gate green. Earlier passes proved
  that creating the empty report directory makes the suite pass, which is why a
  rerun/Python bump is not a code fix. **Next step:** dispatch the `task-requests`
  entry above as an integrator-owned `FIX-` repair worker with `scripts/`
  authority, then merge and let the integrator re-run the full gate. Do **not**
  have a story worker edit `scripts/` and do **not** regenerate `opencode.json`.

## Lessons for workers

- Reproduce a reported gate failure before accepting the prior verdict: check
  the Python version (`python3 --version`), whether
  `apps/api/target/surefire-reports` exists, and whether the named test files
  are byte-identical to `origin/main`. That trio distinguishes a change defect
  from a pre-existing environment-dependent test bug in minutes.
- A mock of `Path.is_dir` returning `True` does not create a directory. On
  Python < 3.13 a later `Path.glob` calls `os.scandir` on the mocked path and
  raises `FileNotFoundError`; mock the decision function (`usable_reports`)
  instead of the filesystem primitive.
- Repeating "no production defect, integrator should re-judge" with no new
  evidence does not advance a failed fix pass. Either land an in-scope change
  or emit a precise `task-requests` entry with the exact file/line edits and
  verification commands for the authorised owner.
- A swarm PR that edits `scripts/` is never auto-merged, so a story worker
  "fixing" a protected test would block its own task; use the task-request path.
