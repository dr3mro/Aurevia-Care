# RESP-PATIENT-OVERVIEW-SCREEN — Staff web: responsive `PatientOverviewScreen`

## Summary

Made the presentational `PatientOverviewScreen` responsive without changing its
behaviour, text, API calls or exported names. The root now uses the shared
`page` wrapper (single-column grid, logical safe-area padding, 1200 px reading
cap), the identity fields share one `grid-2` container (stacked on phones, two
columns from the medium breakpoint), and the chart link's clickable content
carries the shared `touch` class (24 px fine / 44 px coarse target floors). The
active layout band is exposed as `data-breakpoint` so jsdom can assert it;
width changes layout only, never clinical values or actions. Added a dedicated
responsive test file covering compact/medium/expanded, keyboard order, the
no-duplicates branch, a live media-query change and right-to-left Arabic.

Changed files (exactly the declared scope):
- `apps/staff-web/src/features/patients/PatientOverviewScreen.tsx`
- `apps/staff-web/tests/responsive/features-patients/PatientOverviewScreen.responsive.test.tsx`

## Affected requirements

- WEB-07 — staff web usable from 320 px; layout-only breakpoints; no clinical
  field removed at a breakpoint.
- UX-01 — WCAG 2.2 AA: reflow without horizontal page scroll, long English and
  Arabic values wrap, logical properties, safe-area insets, coarse-pointer
  target sizes, 200 % zoom readability, right-to-left mirroring.

Acceptance mapping (story step 2 → implementation → test):

| Acceptance | Implementation | Test assertion |
|---|---|---|
| 320/360/390/768/820/1024/1280 px, LTR and RTL | `useBreakpoint()` + `data-breakpoint`; three mocked bands | `it.each(BREAKPOINTS)` and the RTL case assert the band hook and structure at each band |
| No horizontal page scroll / no clipping | `.page` `minmax(0,1fr)`, `.grid-2 > *` shrink, `MixedScriptText` wrap | `expectLogicalOnly`, no `display:none`, `overflowWrap:'anywhere'`, `whiteSpace:'normal'` |
| Long English and Arabic strings wrap | existing `MixedScriptText` isolation runs | BDI tag, `dir=auto`, wrapping styles asserted in English and Arabic |
| Columns stack on phones, side-by-side from medium | one `grid-2` container (1 col < 768, 2 col ≥ 768) | `.grid-2` present and all five fields present at every band |
| Coarse pointers 44 × 44 targets | `.touch` on the span inside the chart `Link` | `.touch` span inside the only anchor, `display:inline-flex`, single keyboard stop |
| Inputs 16 px | no inputs on this screen | not applicable (recorded, not fabricated) |
| Safe-area insets | `.page` logical `max()` padding | root carries `.page`; stylesheet mapping verified in `responsive.css` reads |
| Logical properties only | all inline styles logical/neutral | `expectLogicalOnly` rejects physical sides, alignment and direction overrides |
| No clinical field hidden | no `hide-compact`/`only-compact`/`display:none` | every element checked for hidden classes/styles at every band |
| Text readable at 200 % zoom | no fixed block sizes or font sizes | `expectLogicalOnly` forbids fixed pixel block sizes; pixel zoom is Playwright-owned |

## Risk class

Routine (`docs/WORKFLOW.md`): a contained presentational change to one screen
plus its new test. No state machine, persistence, authorization, integration or
data-flow boundary changed.

## Review owner

- `orevia-senior-react` — simulated review: **no actionable in-scope
  findings**. Confirmed logical-only styling, `.page`/`.grid-2`/`.touch`
  behaviour, preserved exports and 100 % per-file coverage. Flagged that a real
  "no horizontal scroll at 320 px" measurement needs a Playwright project
  (out of scope, owned by the RESP-E2E stories).
- `orevia-ux-reviewer` — simulated review: **no blocking structural or
  semantic defects**. Confirmed roles/labels, single keyboard stop, BDI
  isolation, RTL mirroring, wrapping and no hidden clinical fields. Two
  optional suggestions (label the patient ID; make duplicate candidates
  clickable) were **not applied** because story step 5 forbids changing text
  and behaviour.
- No simulated-user or human/clinical participant evidence was claimed.

## Data changes

None. No migration, backfill, lock, volume impact, rollback or forward-fix.

## Authorization boundaries and audit events

None. Tenant scope, role checks, patient linkage and audit events are untouched;
the screen still renders only the projection it is passed and performs no
command.

## Migration and rollout considerations

None. Presentation-only change behind the same route; no contract, generated
client or deployment configuration changed.

## Test evidence

Entry-point-to-test trace: `PatientOverviewPage` loads the view → renders
`PatientOverviewScreen` → `useBreakpoint()` reads the mocked `matchMedia`
queries → `.page`/`.grid-2`/`MixedScriptText`/`.touch` structure → asserted by
`tests/responsive/features-patients/PatientOverviewScreen.responsive.test.tsx`.
The existing `src/features/patients/PatientOverviewPage.test.tsx` proves the
screen still renders through its real page entry point.

Commands actually run (tool evidence):

1. Red phase, before the production change:
   `cd apps/staff-web && ./node_modules/.bin/vitest run tests/responsive/features-patients/PatientOverviewScreen.responsive.test.tsx src/features/patients/PatientOverviewPage.test.tsx --reporter=dot`
   → `Test Files 1 failed (1)`, `Tests 7 failed (7)` — missing `.page`,
   `data-breakpoint`, `.grid-2` and `.touch` as expected.
2. Green phase, same command after the change → `Test Files 2 passed (2)`,
   `Tests 23 passed (23)`.
3. Canonical module gate: `cd apps/staff-web && bash check.sh` → `CHECK_EXIT=0`;
   final lines:
   - `Test Files  159 passed (159)`
   - `Tests  1898 passed (1898)`
   - `No files with missing coverage.`
   - `207 files fully covered.`
   - `Statements   : 100% ( 3722/3722 )`
   - `Branches     : 100% ( 1892/1892 )`
   - `Functions    : 100% ( 1258/1258 )`
   - `Lines        : 100% ( 3556/3556 )`
4. Typecheck (final lines pasted as required):
   `cd apps/staff-web && npm run typecheck` →
   ```
   > orevia-staff-web@0.1.0 typecheck
   > tsc --noEmit -p tsconfig.json
   ```
   `TYPECHECK_EXIT=0` (no errors emitted).
5. `python3 -I scripts/check-names.py` → `names OK`;
   `python3 -I scripts/check-secrets.py` → `secrets OK`.
6. `bash scripts/frontend-check.sh` → `FRONTEND_CHECK_EXIT=0`,
   `frontend standards: policy and tool commands passed`.
7. `git merge --no-edit origin/main` → `Already up to date.` (run at start,
   after implementation and before finishing). No conflict markers in either
   changed file.

`scripts/check.sh` was deliberately not run end to end: it invokes the full
Java, database and patient-app gates (well over the 5-minute worker time box)
and the known cloud-worker false positives (OPS-CW-003-01 CI-profile
`opencode.json`, PyYAML/NVD limits). The canonical staff-web gate and
`frontend-check.sh` are the applicable definition of done for this scope; the
integrator owns the full repository gate.

## Coverage

Command: `cd apps/staff-web && bash check.sh` (runs `npm run test:coverage`
with V8, per-file thresholds of 100 %).

Result: `No files with missing coverage.` / `207 files fully covered.` —
Statements 100 % (3722/3722), Branches 100 % (1892/1892), Functions 100 %
(1258/1258), Lines 100 % (3556/3556). `PatientOverviewScreen.tsx` is fully
covered.

Branch map for the changed production file:
- No new production branch was added: the change only adds class/attribute
  wiring and elements with no conditional logic.
- Pre-existing `overview.duplicates.length === 0 ? … : …` — true side covered
  by the no-duplicates cases in both the new test and
  `PatientOverviewPage.test.tsx`; false side covered by the duplicate-warning
  cases in both files.
- Pre-existing `overview.duplicates.map(...)` callback covered by the
  duplicate-warning cases (candidate name and MRN asserted).
- `useBreakpoint()` band resolution is owned by the hook's own tests; this
  test additionally drives all three bands and a live `change` event, asserting
  `data-breakpoint` on the root and on the isolated runs.

No threshold, exclusion, pragma or skip was added or changed; no test was
weakened.

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association,
prescribing or critical-result behaviour changed. A simulated UX/RTL review
(and the senior React review) were run and reported no blocking findings.
Real-browser pixel checks (44 × 44 measurement, 320 px/200 % reflow) remain
Playwright/E2E work and are not claimed here.

## Findings

- Pre-existing defect outside scope: three committed files on `HEAD` still
  contain unresolved merge-conflict markers (`<<<<<<< HEAD` / `>>>>>>> origin/swarm/BUILD-37-469`):
  `apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7ParseException.java`,
  `.../Hl7Parser.java`, `.../Hl7Segment.java`. Verified with
  `git show HEAD:<path> | grep -nE '^(<<<<<<<|>>>>>>>) '`. This is inherited
  from `origin/main`, not introduced by this story, and is outside the declared
  scope; it was not edited. The integrator/owner should route a repair.
- `Link` (`src/app/router.tsx`) exposes no `className`/`style` prop, so the
  `touch` class cannot be put on the anchor itself; it is placed on the span
  that is the anchor's content, which still makes the clickable box the target.
- `responsive.css` has no clinical-value wrapping class (pre-existing;
  `MixedScriptText` uses logical inline styles). All long strings here are
  already isolated by that component, so no new inline workaround was needed.
- The shared shrink/wrap rule in `responsive.css` covers `.app-shell > *`,
  `.cluster > *`, `.stack > *`, `.grid-2 > *` and `.card-list > *`, but not
  `.page > *`. This screen puts wrapping text inside `.grid-2` and
  `MixedScriptText`, so it is unaffected; note for a future stylesheet story.
- No missing task was encountered; no `task-requests` block is needed.

## Lessons for workers

- Follow the `ApiPage` pattern for responsive screens: put `className="page"`
  and `data-breakpoint={useBreakpoint()}` on the root so jsdom can assert the
  band without evaluating media queries, and let `.page` own safe-area padding
  and the `minmax(0,1fr)` grid instead of adding inline page styles.
- `Link` cannot take `className`/`style`; to give a link a coarse-pointer
  target, put `className="touch"` with `display:inline-flex` on a span inside
  the link so the anchor's clickable box inherits the target floors. Keep the
  accessible name on the link (a nested span does not change it).
- One DOM at every band is preferred: use `.grid-2` (single column compact, two
  columns ≥ 768) for stacked→side-by-side fields rather than rendering two
  structures, so no clinical field can be hidden and keyboard order is stable.
- Reuse `MixedScriptText` for every long clinical value; it already wraps with
  logical inline styles and exposes `data-breakpoint`. Do not add a new CSS
  class for this in a screen story.
- In RTL tests, a synthetic value used as both the patient name and a duplicate
  candidate appears twice: use `getAllByText(...)[0]` (or scope with `within`)
  to avoid a multiple-elements failure.
- `expectLogicalOnly`-style assertions plus a check that no element carries
  `hide-compact`/`only-compact`/`display:none` are the practical jsdom proxies
  for reflow and no-hidden-field acceptance; pixel measurements belong to
  Playwright tasks.
- `npx vitest` without installed `node_modules` pulls a wrong global Vitest
  and fails on `vitest/config`; run `npm ci` first (as `check.sh` does) and call
  `./node_modules/.bin/vitest` for targeted runs.

Evidence provenance: all commands above were executed with tools in this
worker; the two specialist reviews were simulated (`orevia-senior-react`,
`orevia-ux-reviewer`). No simulated-user walkthrough, human or clinical
participant evidence is claimed. All data is synthetic; no patient data,
credential or real identifier appears in the change or this note.
