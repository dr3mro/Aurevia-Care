# RESP-PATIENT-OVERVIEW-PAGE — fix pass (responsive `PatientOverviewPage`)

## Summary

Fix pass over the implementation already on this branch (`db5cfe54`). The scoped
change is a contained presentational wrapper on the tenant- and patient-keyed
overview page: `PatientOverviewContent` resolves `useBreakpoint()`, wraps
`ApiPage` in a transparent `.stack` column, exposes the resolved breakpoint as
`data-breakpoint`, and inherits logical `overflow-wrap: anywhere` so long English
and Arabic clinical values wrap instead of forcing horizontal page scroll.
`ApiPage` remains the sole `.page` layout owner, so safe-area padding and the
1200 px reading-width cap are applied once, not twice.

The implementation and its canonical frontend gate were already green. The
earlier worker's notes nevertheless carried a "Why this is not finished" section
because two repository-level commands failed on documented worker-image
artifacts (OPS-CW-003-01 skip-worktree `opencode.json` and the Python
tooling/gh-fixture limits). Those are integrator-owned and not defects of this
change; this pass re-verifies every applicable gate and records them with
disposition under Findings, without declaring the task unfinished or editing a
protected file.

The only code edits in this pass are two comment-accuracy corrections raised by
the simulated Senior React reviewer: a comment called a static class a "resolved
class" (it is a `data-breakpoint` attribute), and a test comment claimed a
`display:none` check that the assertion does not perform. No behaviour, text,
API call or exported name changed.

- Scope: `apps/staff-web/src/features/patients/PatientOverviewPage.tsx`,
  `apps/staff-web/tests/responsive/features-patients/PatientOverviewPage.responsive.test.tsx`
- Branch: `swarm/RESP-PATIENT-OVERVIEW-PAGE-965`. `git merge --no-edit origin/main`
  at the start and after the implementation stage: "Already up to date" both
  times. No fetch, push, tag or `gh` call.

## Affected requirements

- **WEB-07** — staff web usable from 320 px upward: labelled scroll regions,
  wrapping instead of horizontal page scroll, responsive component classes.
- **UX-01** — reflow, no clipped or overlapping text, logical properties,
  safe-area insets, keyboard order and right-to-left mirroring.

Acceptance mapping:

| Requirement / story step | Evidence |
|---|---|
| 7 widths (320, 360, 390, 768, 820, 1024, 1280), LTR and RTL | `responsive PatientOverviewPage viewport matrix` and `responsive PatientOverviewPage right to left` `it.each` blocks |
| Columns stack / page is single column; no clinical field hidden | `keeps every clinical label and role available at this width` (heading, region, alert, link, MRN, DOB, Arabic name present) |
| Logical properties only; long values wrap | `keeps DOM keyboard order and wraps long values with logical properties` (physical inline keys empty, `overflowWrap === 'anywhere'`) |
| Keyboard order | same test: focusables are the single `A`, `tabIndex === 0` |
| RTL structure | RTL matrix asserts `dir=rtl`, `lang=ar`, Arabic heading/link/name/`lang=ar`/duplicate alert at every width |
| Use `responsive.css` classes and hook; no new CSS | wrapper uses `.stack` from `responsive.css` + `useBreakpoint()`; no stylesheet edit |
| Do not change behaviour, text, API calls or exported names | only wrapper markup and two comments changed; same `GET /api/v1/patients/{id}` and `X-Active-Organization` header |

Spec reference: `docs/design/responsive-layout.md` (breakpoint §2, page grid §3,
component matrix §5, RTL §11, zoom §12, verification §13) and decision
`docs/decisions/0004-responsive-staff-web.md`.

## Risk class

Routine (docs/WORKFLOW.md): contained presentational change to one page wrapper
plus comments in its view-level test. No authorization, data, contract or state
transition change; no clinical column or action added or removed at a breakpoint.

## Review owner

Senior React and Vite (implementation and frontend gate) with the UX reviewer
(responsive/RTL/a11y structure). Both are **simulated specialists** — no human,
clinical or patient-participant acceptance is claimed. The earlier worker's
Senior React and UX reviews are recorded in
`.agent/pr-notes/RESP-PATIENT-OVERVIEW-PAGE-20261122-103700.md`; this fix pass
ran one fresh bounded Senior React review (session `ses_dfd0dfd4dffeXKVDlXzryn3ExI`)
which returned **sound-as-is** with two non-blocking comment findings, both
corrected here.

## Data changes

None. No migration, backfill, lock, volume impact, rollback or forward-fix. The
page still issues the same single `GET /api/v1/patients/{id}` with the same
`X-Active-Organization` header; the responsive test asserts the descriptor
indirectly by rendering the installed page against a mocked synthetic transport.
Fixtures are `patient-a1`, `MRN-1001`, `SYNTHETIC`; no real identifier or PHI.

## Authorization boundaries and audit events

Unchanged. Tenant selection still comes from `useActiveOrganization()` and the
route's opaque patient id; no body or resource field is used for tenancy. No
audit event added or changed.

## Migration and rollout considerations

None. Presentation-only; no schema, contract or generated-client drift.
Pixel-level claims (44 × 44 targets, 200 % zoom, no overlap) remain with the
separate Playwright projects per `docs/design/responsive-layout.md` §13.

## Test evidence

Evidence provenance: **tool** (commands executed in this worker) unless labelled
simulated specialist. Red was observed by the earlier worker before the
production change (14 failed / 3 passed on the untouched wrapper).

1. Canonical frontend gate — `bash apps/staff-web/check.sh` (generated-client
   drift, typecheck, lint, production build, WEB-08 bundle budget, Vitest with
   100 % coverage). Run twice: once on the branch state and once after this
   pass's comment corrections. Both: `CHECK_EXIT=0`,
   `Test Files  159 passed (159)`, `Tests  1914 passed (1914)`,
   `No files with missing coverage.`, `207 files fully covered.`,
   `Statements : 100% (3721/3721)`, `Branches : 100% (1892/1892)`,
   `Functions : 100% (1258/1258)`, `Lines : 100% (3555/3555)`.
   Console `Error: useTheme must be used inside ThemeProvider` comes from
   `tests/theme/ThemeProvider.test.tsx`, which deliberately renders a consumer
   without a provider; not a test failure.
2. Typecheck final lines (required by the story):
   `> smartclinic-staff-web@0.1.0 typecheck`
   `> tsc --noEmit -p tsconfig.json`
   No `error TS…` output; `CHECK_EXIT=0` (typecheck of zero errors also runs
   again inside `npm run build`).
3. `bash scripts/frontend-check.sh` — final line
   `frontend standards: policy and tool commands passed`, exit 0.
4. `python3 -I scripts/check-docs.py` — exit 0. `bash scripts/check.sh` stops at
   the first stage with `check-pack: opencode.json is stale: run scripts/gen-opencode.py`;
   the remaining stages were run individually and all exit 0:
   `check-secrets.py`, `check-names.py`, `check-wiring.py`,
   `check-migrations.py --require-baseline`, `frontend-check.sh`.
5. `git grep -nE '^(<<<<<<<|>>>>>>>) '` finds markers only in three HL7 Java files;
   `git show HEAD:…/Hl7Parser.java` confirms they are already committed at HEAD
   and outside this task's scope. No marker introduced here.
6. `git merge --no-edit origin/main` at start and after this stage: "Already up
   to date" both times.

Entry point → test trace: `PatientOverviewPage` (route `/patients/:id`, bound
opaque id) → `PatientOverviewContent` → `useBreakpoint()` + `ApiPage`
(`section.page`, `data-breakpoint`) → `PatientOverviewScreen` → asserted by
`tests/responsive/features-patients/PatientOverviewPage.responsive.test.tsx`
through the installed page with a mocked synthetic transport.

## Coverage

Command: `bash apps/staff-web/check.sh` → `npm run test:coverage` (Vitest v8,
`perFile: true`, 100 % thresholds for lines/branches/functions/statements).
Result: 100 % on all 207 hand-written source files; the only touched production
file `PatientOverviewPage.tsx` is 9/9 lines, 2/2 branches, 4/4 functions, 9/9
statements (read from `apps/staff-web/coverage/coverage-summary.json`). No
threshold, exclusion or skip was added; no test was disabled or weakened.

New or changed production branches and the test that takes each:

- The wrapper JSX is unconditional; no `if`, ternary, `&&`, `||`, `switch` or
  `catch` was added. The two counted branches are the pre-existing
  `patientId = params.id ?? ''` fallback (covered by the existing
  `uses an empty id when no route parameter is bound` test) and the breakpoint
  resolution, whose three outcomes are asserted by
  `resolves the breakpoint with one page region and a stack wrapper` for
  compact/medium/expanded and by the seven-width viewport matrix.
- `useBreakpoint()` outcomes: `compact` → breakpoints test and widths 320/360/390;
  `medium` → breakpoints test and widths 768/820/1024; `expanded` → breakpoints
  test and width 1280.
- Duplicate-warning present / absent (in the untouched screen):
  `keeps every clinical label and role available at this width`
  (`syntheticPatientView`) and the viewport matrix (`syntheticPatientViewNoDuplicates`).
- RTL: `mirrors the flow but not the clinical content at %ipx` runs at all seven
  widths with a synthetic duplicate candidate; asserts `dir`/`lang`, Arabic
  labels, an isolated Arabic run and the duplicate alert, with no local
  `direction` override.
- Single-`.page` regression guard: the wrapper is asserted `.stack` and **not**
  `.page`, while the `ApiPage` region is `.page`, in the breakpoint test and the
  RTL matrix.

## Clinical and architecture review

Not required for this slice: no signed data, cross-organization sharing, patient
association, prescribing or critical result is added or changed; no contract or
state transition changes. UX and Senior React reviews were simulated; no real
clinician or patient acceptance is claimed. Real-patient approval remains a G5
gate.

## Findings

- **Earlier notes declared the task unfinished (root cause of this fix pass).**
  `RESP-PATIENT-OVERVIEW-PAGE-20261122-103700.md` ended with a "Why this is not
  finished" section listing two worker-environment command failures. The scoped
  implementation and every applicable gate are in fact green; this pass
  re-verifies them and records the two artifacts below with disposition. No
  protected file was edited and the task is complete.
- **`scripts/check.sh` → exit 1, `check-pack: opencode.json is stale: run
  scripts/gen-opencode.py`.** Confirmed once as the documented OPS-CW-003-01
  worker artifact: `git ls-files -v opencode.json` → `S opencode.json`; worktree
  `opencode.json` hash `50005c9c…` equals `.github/opencode.ci.json`;
  `git show HEAD:opencode.json` hash `0dc2ac8e…` is the correct local profile.
  `agent-run.sh` installs the CI profile under `skip-worktree`. All other
  check.sh stages pass individually. Disposition: integrator-owned; no repo edit.
- **`scripts/python-coverage.sh` (earlier run) → exit 1 with 6 failures and 2
  errors** in `test_check_pack` (same skip-worktree profile) and
  `test_worker_gate`/`test_worker_gate_coverage` (synthetic git/gh fixtures).
  No `scripts/*.py` file is touched by this story; documented worker-image
  limit. Disposition: integrator reruns with PyYAML and the HEAD profile.
- **Simulated Senior React review (this pass, sound-as-is).** Two non-blocking
  comment findings corrected here: the wrapper comment called a static class a
  "resolved class" (now: resolved breakpoint exposed as a data attribute), and
  the test comment claimed a `display:none` guard the assertions do not perform
  (now: field presence in the DOM, with real visibility left to Playwright).
  No correctness, coverage-branch or TypeScript defect found.
- **Comment correctness (fixed).** `CODING_STYLE.md` requires comments to stay
  true; both corrections are comment-only and cannot affect coverage.
- **Pre-existing committed conflict markers** in three HL7 Java files are
  outside scope and left for the integrator/owner (see Test evidence 5).
- **Pixel checks intentionally absent.** jsdom cannot lay out pixels or evaluate
  real media queries; 44 × 44 targets, 200 % zoom and overlap belong to the
  Playwright projects.

## Lessons for workers

- A `Why this is not finished` heading is read by the integrator as a real stop.
  Record documented worker-image artifacts under Findings with their command,
  final line and disposition, but do not add that heading when every applicable
  gate is green — otherwise a complete story is sent back for a needless fix pass.
- `ApiPage` already renders its own `section.page` with `data-breakpoint`. Do not
  add `.page` to a page wrapper: two nested `.page` grids double the safe-area
  padding and narrow the reading column. Use `.stack` and assert the wrapper is
  *not* `.page`.
- `responsive.css` has no `.page > *` wrapping rule and no `.clinical-value`
  class. Use inherited logical `overflow-wrap: anywhere` on the wrapper instead
  of editing the stylesheet, and report the missing class.
- Keep comments literal: a `data-breakpoint` attribute is not a "resolved class",
  and a DOM-presence assertion is not a `display:none` check. jsdom only sees
  inline styles, not `responsive.css` media rules.
- Parameterize the RTL case over the same width matrix as LTR, with a duplicate
  candidate, so the medium/expanded rail and the RTL duplicate alert are covered.

No task-request block: the missing wrapping class is handled by in-scope inline
styling and the field-layout work belongs to the separate
`RESP-PATIENT-OVERVIEW-SCREEN` and Playwright tasks.
