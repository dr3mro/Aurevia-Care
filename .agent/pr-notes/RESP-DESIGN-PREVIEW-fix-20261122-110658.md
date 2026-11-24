# RESP-DESIGN-PREVIEW — fix pass 4 (RESP-DESIGN-PREVIEW-fix-20261122-110658)

## Summary

Continuation of the already-committed responsive `DesignPreview` slice on
`swarm/RESP-DESIGN-PREVIEW-859` (implementation commit `68556991`). I treated the
earlier findings as notes, did not restart the work, and re-verified every
in-scope gate myself. The production change and its new jsdom test are complete,
scoped and green; **no production defect was found and no production or test file
was changed in this pass**. This notes file is the only file added.

The remaining failure is a pre-existing defect in protected `scripts/worker_gate.py`
surfacing on this worker's Python 3.11.2. It is outside the declared staff-web
scope, forbidden by TODO rule 8, and would itself be rejected as a `scope`
violation by `worker_gate.validate_candidate` if a story worker edited it. It
therefore needs an integrator-owned automatic repair or explicit owner approval,
exactly as the earlier debugger report concluded.

## Affected requirements

- **WEB-07** — supported clients; staff-web responsive layout and browser matrix.
- **UX-01** — English/Arabic RTL, mixed-script clinical text, WCAG 2.2 AA layout.

No other requirement IDs touched.

## Acceptance mapping

| Story acceptance point | Implementation (commit `68556991`) | Test evidence in this pass |
|---|---|---|
| 320/360/390/768/820/1024/1280 px → compact/medium/expanded | root `useBreakpoint()` → `data-breakpoint`; existing `breakpoints.ts` thresholds | "resolves %ipx to the correct layout band" (`it.each`, 7 widths) + per-band root assertion |
| LTR and RTL at every band | document `dir`/`lang`; no subtree `direction` override; `<bdi dir="auto">` | "mirrors the whole preview with Arabic labels …" (3 bands) |
| No horizontal page scroll; columns stack; safe-area insets | existing `page` class (single `minmax(0,1fr)` column, logical safe-area padding) | `classList.contains('page')` + authored inline-style regex (physical props forbidden) |
| Long English/Arabic text wraps; 200 % zoom readable | existing `MixedScriptText` + wrapping cells/buttons | `bdi` dir/lang/`overflowWrap:anywhere`, no `nowrap` or fixed block size |
| Coarse pointers 44×44; inputs 16 px | existing `.touch` + `@media (pointer: coarse)`, `.field` 16 px | `.touch`/`.field` classes + `data-coarse-pointer` per band (painted pixels are Playwright evidence) |
| Logical properties only; no hidden clinical column | no new CSS; `page` class + logical inline styles | inline-style regex; full `<th scope="col">` set; no `.hide-compact`/`.only-compact` |
| Preview shows every component at the three width classes | all children render at each band with their own band structure | compact labelled `role=region` vs plain table; form/input/dialog/`bdi` `data-breakpoint`; dialog `sheet` vs `centered` |
| Labels, roles, keyboard order unchanged; existing tests pass | no text/API/export change | `getByRole`/`getByLabelText` + focusable-order array per band; `AppShell.test.tsx`; full suite green |

## Risk class

Routine (contained staff-web presentation change to one component plus one new
test file), with a simulated UX review because RTL/WCAG is in scope
(`docs/WORKFLOW.md`).

## Review owner

`orevia-senior-react` and `orevia-ux-reviewer` — earlier advisory
subagent reviews, simulated/synthetic evidence only; no human or clinical
participant. Senior React verdict: no blocking defects. UX verdict: no blocking
defect after the mixed-script measurement fix. No new reviewer was needed in this
pass because no production code changed.

## Data changes

None. No Flyway migration, backfill, lock, volume impact, rollback or forward-fix.
No reserved migration version used. No synthetic identifiers beyond the existing
`apps/staff-web/src/fixtures/synthetic.ts` values.

## Authorization boundaries and audit events

None. `DesignPreview` is a development-only visual page: no endpoint, tenant,
role, patient link or audit event was added or changed.

## Migration and rollout considerations

None. No build script, contract, workflow, tracker or protected file was edited
by this pass.

## Test evidence

Evidence source: executed tool commands in this pass (tool evidence), plus the
earlier advisory subagent verdicts above (reviewer evidence). No simulated-user,
human, clinical or patient evidence. Environment: Node v22.23.5, npm 10.9.9,
Vitest 5.1.1, Python 3.11.2; `node_modules` were already present.

1. Branch reconciliation: `git merge --no-edit origin/main` → `Already up to date.`
   (exit 0). `git diff --name-only origin/main...HEAD` → only the four earlier
   notes files plus `apps/staff-web/src/app/DesignPreview.tsx` and
   `apps/staff-web/tests/responsive/app/DesignPreview.responsive.test.tsx`.
   No conflict markers in the scope.
2. Mandatory frontend gate: `bash apps/staff-web/check.sh` → exit 0. All of
   `gen:api:check`, `npm run typecheck`, `npm run lint`, `npm run build`,
   `node scripts/check-bundle-size.mjs` and `npm run test:coverage` passed. Final
   lines: `Test Files  159 passed (159)`, `Tests  1916 passed (1916)`,
   `No files with missing coverage.`, `207 files fully covered.`, `4 empty files
   skipped.` The `useI18n`/`useTheme must be used inside …Provider` stack traces
   are the expected output of the negative provider tests; the run is green.
3. Typecheck final lines (explicit re-run, frontend definition of done):
   `cd apps/staff-web && npm run typecheck` →
   `> orevia-staff-web@0.1.0 typecheck` /
   `> tsc --noEmit -p tsconfig.json`, exit 0, **no `error TS…`**.
4. Coverage gate: `bash scripts/coverage-check.sh origin/main` → exit 0,
   `coverage check: passed`.
5. Frontend policy: `bash scripts/frontend-check.sh` → exit 0,
   `frontend standards: policy and tool commands passed` (1 test passed).
6. Out-of-scope defect reproduced deliberately (see Findings):
   `python3 -I -m unittest discover -s scripts/tests -p 'test_worker_gate.py' -k 'integration_failure_still_fails_the_gate'`
   → `Ran 1 test`, `FAILED (errors=1)`, `FileNotFoundError: apps/api/target/surefire-reports`
   at `scripts/worker_gate.py:63`.
7. End-of-run repository check (capped at 240 s to avoid the multi-minute Java
   stages): `bash scripts/check.sh` → exit 1 at the first stage with the single
   line `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. This
   is the documented CI-profile artifact of Finding 4; no later stage ran and
   no file was changed by the check (`git status --short` empty after it).

Entry-point-to-test trace: dev-only route `/design-preview` → lazy
`createRoutes(includeDesignPreview)` in `src/app/routes.tsx` → `DesignPreview` →
`tests/app/AppShell.test.tsx` ("keeps the development preview interactive for
visual review": click/submit/echo) and the new
`tests/responsive/app/DesignPreview.responsive.test.tsx` (structure, roles, RTL,
keyboard order at all bands). Pixel / 44 px / 16 px / safe-area / 200 %-zoom
paint checks are Playwright E2E evidence (separate RESP-E2E tasks), as the story
directs.

## Coverage

- Command: `bash apps/staff-web/check.sh` → `npm run test:coverage` (Vitest 5.1.1
  / v8, `include: ['src/**/*.{ts,tsx}']`, per-file 100% thresholds). Confirmed by
  `bash scripts/coverage-check.sh origin/main` and by reading
  `apps/staff-web/coverage/coverage-summary.json`.
- Result: `No files with missing coverage.` / `207 files fully covered.` /
  `Statements 100% (3722/3722)` / `Branches 100% (1892/1892)` /
  `Functions 100% (1258/1258)` / `Lines 100% (3556/3556)`. No threshold,
  exclusion or skip changed; no test disabled or ignored.
- Changed production file `apps/staff-web/src/app/DesignPreview.tsx`: 100%
  lines (9/9), functions (4/4), statements (9/9), branches (0/0 → 100%).
- New branches introduced by this pass: **none** (only this notes file was
  added). The earlier implementation adds one hook call (`useBreakpoint`), one
  `className`, one `data-*` attribute and one existing-primitive wrapper; no
  `if`, `?:`, `&&`, `||`, `switch` or `catch` in the changed code.
- Named tests for the implementation's behaviors: root `page` class +
  `data-breakpoint=band` → "renders the page with responsive classes …" (3
  bands) and the 7-width viewport matrix; compact labelled table region vs
  medium/expanded plain table → "shows every component with the table, fields
  and dialog for the band"; dialog `sheet`/`centered` and sticky header →
  "opens the dialog with the band-appropriate view and restores focus" (3
  bands); Arabic measurement `<bdi dir="auto">` → RTL block (3 bands) with
  `measurement.tagName === 'BDI'`; dialog-open/close and note-submit callbacks →
  `AppShell.test.tsx`.

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association,
prescribing or critical results. The UX/WCAG-01 surface was reviewed by a
simulated subagent only — synthetic review, not clinical or human acceptance.

## Findings

1. **No production defect (tool evidence).** The implementation is complete,
   scoped and green; all in-scope gates pass with 100% coverage. No production
   or test file was changed in any fix pass.
2. **Out-of-scope gate defect, reproduced here (tool evidence).**
   `scripts/worker_gate.py:54-69 usable_reports()` calls
   `Path(reports_dir).glob("TEST-*.xml")` on the default
   `apps/api/target/surefire-reports`. `main()` line 260 guards with
   `reports.is_dir()`, but two tests in protected `scripts/tests/`
   (`test_worker_gate.MainTest.test_integration_failure_still_fails_the_gate` and
   `test_worker_gate_coverage.MainTest.test_maven_failure_with_failing_reports_still_fails`)
   globally mock `worker_gate.Path.is_dir → True`. On Python 3.11.2
   `Path.glob` then calls `os.scandir` on the absent directory and raises
   `FileNotFoundError`. The documented local baseline is Python 3.15, where the
   mock does not break glob. `scripts/` is protected (TODO rule 8), the files
   are byte-identical to `origin/main`, and editing them would fail this task's
   own scope validation (`worker_gate.validate_candidate`), so they were left
   untouched.
3. **Authorized repair needed (hand-off, from the earlier debugger report).** In
   `usable_reports()` wrap the glob in
   `try: candidates = sorted(Path(reports_dir).glob("TEST-*.xml"))` /
   `except FileNotFoundError: return False`, iterate `candidates` for a parseable
   XML, and add one regression test in `scripts/tests/test_worker_gate.py`
   covering the missing-directory side. Keep `main()`, its thresholds and
   `REPORTS` unchanged; leave the existing `UsableReportsTest` cases untouched.
   This must be done by an integrator-owned automatic repair or with explicit
   owner approval — not by this story worker.
4. **Environment artifact — CI `opencode.json` (tool evidence).** The worktree
   file is the CI profile under `skip-worktree` (`git ls-files -v opencode.json`
   → `S`), so `check-pack.py` calls it stale: the executed
   `bash scripts/check.sh` exited 1 at `check-pack: opencode.json is stale: run
   scripts/gen-opencode.py`. HEAD truth
   (`git show HEAD:opencode.json`) is the correct generated local profile; the
   documented `OPS-CW-003-01` false positive. The file was not regenerated or
   committed.
5. **Pre-existing and out of scope (tool evidence):** committed conflict markers
   remain inside Javadoc blocks in
   `apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7ParseException.java`,
   `Hl7Parser.java` and `Hl7Segment.java`; none in `apps/staff-web/`. They do not
   break compilation and were left untouched.
6. jsdom cannot paint pixels, so 44×44 / 16 px / safe-area / 200 %-zoom claims
   are asserted through classes, attributes and structure as the story directs;
   computed values remain Playwright evidence (separate RESP-E2E tasks).

## Why this is not finished

The staff-web deliverable is complete: `bash apps/staff-web/check.sh` exits 0
with 100% coverage, typecheck has no `error TS…`, and the coverage and frontend
policy gates pass. One repository-level check still fails for a reason outside
this protected scope:

- **Command:** `python3 -I -m unittest discover -s scripts/tests -p 'test_worker_gate.py' -k 'integration_failure_still_fails_the_gate'`
  (and therefore `scripts/check.sh` / `scripts/worker-check.sh`). **Error:**
  `FileNotFoundError: [Errno 2] No such file or directory:
  'apps/api/target/surefire-reports'` at `scripts/worker_gate.py:63`. **Cause:**
  a global `Path.is_dir → True` mock in protected `scripts/tests/` combined with
  Python 3.11.2's `Path.glob` calling `os.scandir` on the absent directory; the
  recorded baseline is Python 3.15. **Tried:** reproduced the error in isolation
  with executed tool output, read `worker_gate.py:40-69` and `:254-264`, and
  earlier passes proved that creating the empty report directory makes the full
  Python suite pass. **Next step:** apply Finding 3 as an integrator-owned repair
  (or run this gate under Python 3.15). Do not have a story worker edit
  `scripts/`, and do not regenerate `opencode.json`.

No production or test change is needed for RESP-DESIGN-PREVIEW.

## Lessons for workers

- A fix pass on a green, already-committed slice should re-run the full module
  gate and read the earlier notes on disk; a truncated PR-description snapshot
  is not proof of unfinished production work. Record verification honestly
  instead of inventing a change.
- Before editing a protected script to fix a gate, check whether the task's own
  scope validator would reject it: `worker_gate.validate_candidate` flags any
  changed file outside `scope`, so a story worker cannot unblock a gate by
  editing `scripts/`. Route it as an integrator repair instead.
- In a cloud worker a red `check-pack` is the CI `opencode.json` under
  `skip-worktree`; confirm with `git ls-files -v` and `git show HEAD:opencode.json`
  and never regenerate or commit that protected file.
- If the Python suite fails on `apps/api/target/surefire-reports`, check
  `python3 --version` first: a global `Path.is_dir` mock in two `worker_gate`
  tests breaks `Path.glob` on Python 3.11 but not the 3.15 baseline.
- After `git merge --no-edit origin/main`, re-check `git diff origin/main...HEAD
  --name-status` so auto-resolved conflicts cannot leave out-of-scope edits.
- Expected negative-provider tests print React/jsdom stack traces in a green
  run; judge Vitest by the final `Test Files` / `Tests` lines, not by trace text.
