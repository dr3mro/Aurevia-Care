# RESP-PATIENT-OVERVIEW-SCREEN — fix pass (responsive staff-web `PatientOverviewScreen`)

## Summary

This is fix attempt 1 of 3 after the integrator flagged PR #786 as "the worker did
not finish cleanly". I resumed on branch
`swarm/RESP-PATIENT-OVERVIEW-SCREEN-967`, merged `origin/main` (already up to
date), and re-verified the committed implementation from a clean tree. The
production change from the first pass is already present in commit `3929f0a5` and
is correct: the screen uses the shared `.page` wrapper with `data-breakpoint`,
its identity fields share one `.grid-2` container, and the chart link's content
carries `.touch` for coarse-pointer target floors. No production defect was found,
so no production file was changed in this pass; the required fix-named notes file
was written and committed so the hand-off is complete.

Changed files (net diff against `origin/main`, exactly the declared scope):
- `apps/staff-web/src/features/patients/PatientOverviewScreen.tsx`
- `apps/staff-web/tests/responsive/features-patients/PatientOverviewScreen.responsive.test.tsx`
- this notes file (`.agent/pr-notes/RESP-PATIENT-OVERVIEW-SCREEN-fix-20261122-110702.md`)

## Affected requirements

- WEB-07 — staff web usable from 320 px; layout-only breakpoints; no clinical
  field removed at a breakpoint.
- UX-01 — WCAG 2.2 AA: reflow without horizontal page scroll, long English and
  Arabic values wrap, logical properties, safe-area insets, coarse-pointer
  target sizes, 200 % zoom readability, right-to-left mirroring.

Acceptance mapping (story step 2 → implementation → test assertion):

| Acceptance | Implementation | Test assertion |
|---|---|---|
| 320/360/390/768/820/1024/1280 px, LTR and RTL | `useBreakpoint()` + `data-breakpoint`; three mocked bands | `it.each(BREAKPOINTS)` and the RTL case assert the band and structure at each band (test lines 131-172, 252-283) |
| No horizontal page scroll / no clipping | `.page` `minmax(0,1fr)`; `.grid-2 > *` shrink; `MixedScriptText` wrap | `expectLogicalOnly` (113-122), no `hide-compact`/`only-compact`/`display:none` (165-170), wrapping styles (158-163) |
| Long English and Arabic strings wrap | existing `MixedScriptText` `<bdi dir="auto">` isolation runs | BDI tag, `dir=auto`, `whiteSpace:normal`, `overflowWrap:anywhere` asserted in English and Arabic (158-163, 271-276) |
| Columns stack on phones, side-by-side from medium | one `.grid-2` container (1 col < 768, 2 col >= 768 in `responsive.css`) | `.grid-2` present and all five clinical fields present at every band (150-155) |
| Coarse pointers 44 x 44 targets | `.touch` on the span inside the chart `Link` (24 px fine / 44 px coarse floors in `responsive.css`) | `.touch` span inside the only anchor, `display:inline-flex`, single keyboard stop (190-204) |
| Inputs 16 px | no inputs on this screen | not applicable — recorded, not fabricated |
| Safe-area insets | `.page` logical `max()` padding in `responsive.css` | root carries `.page` (143-146); stylesheet mapping verified by reading `responsive.css:14-27,47-57` |
| Logical properties only | all inline styles logical/neutral | `expectLogicalOnly` rejects physical sides, physical alignment and direction overrides (113-122) |
| No clinical field hidden | no `hide-compact`/`only-compact`/`display:none` | every rendered element checked at every band (165-170) |
| Text readable at 200 % zoom | no fixed block sizes or font sizes | `expectLogicalOnly` forbids fixed pixel block sizes; painted zoom measurement is Playwright-owned (RESP-E2E stories) |

## Risk class

Routine (`docs/WORKFLOW.md`): a contained presentational change to one screen plus
its new test. No state machine, persistence, authorization, integration or
data-flow boundary changed.

## Review owner

- `aurevia-senior-react` — **simulated review this pass** (read-only bounded
  subagent, session `ses_dfd0db647ffeAdMYB5Za8SFAlh`): **no actionable in-scope
  findings**. Confirmed `.page`/`.grid-2`/`.touch` are the correct shared classes;
  the `.touch` span inside `Link` is the only in-scope target mechanism because
  `LinkProps` exposes no `className`; the jsdom test asserts mechanisms without
  fabricated pixel claims; 100 % coverage is plausible with both sides of the one
  branch taken; no type/lint/strictness risk.
- `aurevia-senior-react` and `aurevia-ux-reviewer` — **simulated reviews
  carried forward from the first pass** as notes: no actionable in-scope or
  blocking structural/semantic findings. Two optional UX suggestions (label the
  patient ID; make duplicate candidates clickable) were not applied because
  story step 5 forbids changing text and behaviour.
- No simulated-user, human or clinical participant evidence is claimed in this
  pass. All data is synthetic; no patient data, credential or real identifier
  appears in the change or this note.

## Data changes

None. No migration, backfill, lock or volume impact, rollback or forward-fix. No
reserved migration version was used.

## Authorization boundaries and audit events

None. Tenant scope, role checks, patient linkage and audit events are untouched;
the screen still renders only the projection it is passed and performs no
command.

## Migration and rollout considerations

None. Presentation-only change behind the same route; no contract, generated
client or deployment configuration changed.

## Test evidence

Entry-point-to-test trace: `PatientOverviewPage` loads the view → renders
`PatientOverviewScreen` → `useBreakpoint()` reads the mocked `matchMedia` queries
→ `.page`/`.grid-2`/`MixedScriptText`/`.touch` structure → asserted by
`tests/responsive/features-patients/PatientOverviewScreen.responsive.test.tsx`.
The existing `src/features/patients/PatientOverviewPage.test.tsx` proves the
screen still renders through its real page entry point.

Commands actually run in this pass (tool evidence):

1. `git merge --no-edit origin/main` → `Already up to date.` (run at start).
2. `cd apps/staff-web && bash check.sh > /tmp/staffweb-check.log 2>&1` →
   `CHECK_EXIT=0`. Final lines:
   - `Test Files  159 passed (159)`
   - `Tests  1898 passed (1898)`
   - `No files with missing coverage.`
   - `207 files fully covered.`
   - `Statements   : 100% ( 3722/3722 )`
   - `Branches     : 100% ( 1892/1892 )`
   - `Functions    : 100% ( 1258/1258 )`
   - `Lines        : 100% ( 3556/3556 )`
3. Typecheck final lines (from the passing `check.sh` run), pasted as required:
   ```
   > aurevia-staff-web@0.1.0 typecheck
   > tsc --noEmit -p tsconfig.json
   ```
   No `error TS…` was emitted; `CHECK_EXIT=0` for the run containing it. The
   production build (`tsc --noEmit && vite build`) and `npm run gen:api:check`,
   `npm run lint` and `node scripts/check-bundle-size.mjs` in the same gate also
   passed.
4. `python3 -I scripts/check-names.py` → exit 0, no output (names OK);
   `python3 -I scripts/check-secrets.py` → exit 0, no output (secrets OK).
5. `bash scripts/frontend-check.sh` → `POLICY_EXIT=0`, final line
   `frontend standards: policy and tool commands passed`. It also ran the other
   frontend modules' checks: patient-mobile `Test Suites: 79 passed, 79 total`,
   `Tests: 1127 passed, 1127 total` (4 pre-existing lint warnings, 0 errors).
6. `git grep -nE '^(<<<<<<<|>>>>>>>) ' -- '*.ts' '*.tsx'` → no matches in the
   scoped TypeScript files.
7. `git diff origin/main...HEAD -- <the two scoped files>` → the net diff is
   exactly the responsive implementation; `origin/main` does not contain the
   responsive test file. No work outside the declared scope was introduced.

`scripts/check.sh` was deliberately not run end to end: it invokes the full Java,
database and patient-app gates (well over the 5-minute worker time box) and the
known cloud-worker false positives (OPS-CW-003-01 CI-profile `opencode.json`,
PyYAML/NVD limits). The canonical staff-web gate and `frontend-check.sh` are the
applicable definition of done for this scope; the integrator owns the full
repository gate.

## Coverage

Command: `cd apps/staff-web && bash check.sh` (runs `npm run test:coverage` with
V8 and per-file 100 % thresholds in `vite.config.ts`).

Result: `No files with missing coverage.` / `207 files fully covered.` —
Statements 100 % (3722/3722), Branches 100 % (1892/1892), Functions 100 %
(1258/1258), Lines 100 % (3556/3556). `PatientOverviewScreen.tsx` is fully
covered.

Branch map for the changed production file:
- The only branch in `PatientOverviewScreen.tsx` is the pre-existing
  `overview.duplicates.length === 0 ? … : …` ternary (line 69). True side:
  `keeps the no-duplicates message and the same fields at every band` (test
  211-229) and the existing `PatientOverviewPage.test.tsx` no-duplicates cases.
  False side: `uses the logical page wrapper and keeps every clinical field at %s`
  (131-172), `keeps the headings, warning and link with a single keyboard stop`
  (174-209), `follows a viewport change without remounting` (231-250), the RTL
  case (252-283) and the existing page test.
- The pre-existing `overview.duplicates.map(...)` callback (line 80) is taken by
  the with-duplicates cases, which assert the candidate name and MRN (187-188).
- `useBreakpoint()` has no branch in this file; the test drives all three bands
  and a live `change` event, asserting `data-breakpoint` on the root (146,
  239-247).

No new conditional logic was added; the changed lines are markup, class and
attribute wiring plus a doc comment. No threshold, exclusion, pragma or skip was
added or changed; no existing test was weakened.

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association,
prescribing or critical-result behaviour changed. The simulated UX/RTL and senior
React reviews (this pass and the first pass) reported no blocking findings.
Real-browser pixel checks (44 x 44 measurement, 320 px / 200 % reflow) remain
Playwright work under the RESP-E2E stories and are not claimed here.

## Findings

- The integrator's "did not finish cleanly" flag on PR #786 contained no failing
  command, and this pass found no failing gate or missing production change:
  `origin/main` has no version of the responsive test, the net diff is exactly
  the two scoped files, and all applicable gates are green. The earlier worker's
  notes file exists under a non-fix name
  (`.agent/pr-notes/RESP-PATIENT-OVERVIEW-SCREEN-20261122-104009.md`); this pass
  writes the required `-fix-` notes file so the hand-off and commit are complete.
- Pre-existing defect outside scope, still present on `HEAD` (verified with
  `git grep -nE '^(<<<<<<<|>>>>>>>) '`): unresolved merge-conflict markers in
  `apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7ParseException.java`,
  `.../Hl7Parser.java` and `.../Hl7Segment.java`, inherited from `origin/main`.
  Not edited here; the integrator/owner should route a repair.
- `Link` (`src/app/router.tsx`) exposes no `className`/`style` prop, so the
  `.touch` class sits on the span that is the anchor's content; the anchor's
  clickable box inherits the target floors.
- `responsive.css` has no clinical-value wrapping class (pre-existing);
  `MixedScriptText` uses logical inline styles instead. The shared shrink/wrap
  rule covers `.app-shell > *`, `.cluster > *`, `.stack > *`, `.grid-2 > *` and
  `.card-list > *` but not `.page > *`; this screen is unaffected because its
  long text is inside `.grid-2` and `MixedScriptText`. Note for a future
  stylesheet story.
- No missing task was encountered; no `task-requests` block is needed.

## Lessons for workers

- On a fix pass, write the notes file named in the prompt even when the
  production change was already committed in an earlier pass; a correctly named
  hand-off file is part of "finished cleanly".
- Merge `origin/main` at the start and re-run the canonical module gate after the
  merge; a green gate on a stale tree is not reusable evidence.
- `Error: Uncaught …` stack traces from `ErrorBoundary`, `I18nProvider` and
  `ThemeProvider` negative-path tests are expected jsdom console noise. Judge the
  Vitest run by its `Test Files`/`Tests` summary and exit code, not by those
  lines.
- `scripts/frontend-check.sh` validates all frontend modules; its patient-mobile
  lint warnings (array-type, import/no-duplicates) are pre-existing with 0
  errors and are not caused by a staff-web-only change.
- Follow the `ApiPage` pattern for responsive screens: put `className="page"` and
  `data-breakpoint={useBreakpoint()}` on the root so jsdom can assert the band
  without evaluating media queries, and let `.page` own safe-area padding and
  the `minmax(0,1fr)` grid.
- One DOM at every band is preferred: use `.grid-2` (single column compact, two
  columns >= 768) for stacked-to-side-by-side fields rather than rendering two
  structures, so no clinical field can be hidden and keyboard order is stable.
- Reuse `MixedScriptText` for every long clinical value; it already wraps with
  logical inline styles and exposes `data-breakpoint`. `expectLogicalOnly` plus a
  no-hidden-classes pass are the practical jsdom proxies for reflow; pixel
  measurements belong to Playwright tasks.

Evidence provenance: all commands above were executed with tools in this worker;
the senior React review this pass was a simulated bounded read-only subagent, and
the earlier UX/React reviews are carried forward as notes. No simulated-user
walkthrough, human or clinical participant evidence is claimed.
