# RESP-NOTE-EDITOR · Staff web: responsive `NoteEditor` (features-chart)

PR #771, tested branch tip 214da063e174eddd08b18a048ffa80af82538764.

**Draft: the worker did not finish cleanly** (agent result: ok; local checks: failed). Its work is saved here so nothing is lost; a fix worker or a person continues from this branch.

**Why it failed** (TypeScript/frontend and gate lines):

```text
python coverage: WARNING coverage is not installed; the tests ran without the coverage check
FAIL unreferenced-service: apps/api/src/main/java/com/x/NewService.java
FAIL unreferenced-service: apps/api/src/main/java/com/x/OrphanService.java
FAIL unreferenced-service: apps/api/src/main/java/com/synthetic/OrphanService.java
ERROR: test_maven_failure_with_failing_reports_still_fails (test_worker_gate_coverage.MainTest.test_maven_failure_with_failing_reports_still_fails)
  File "/__w/Aurevia-Care/Aurevia-Care/scripts/tests/test_worker_gate_coverage.py", line 554, in test_maven_failure_with_failing_reports_still_fails
  File "/__w/Aurevia-Care/Aurevia-Care/scripts/tests/test_worker_gate_coverage.py", line 521, in run_main
```

Task: RESP-NOTE-EDITOR

Swarm worker 3. Local checks: failed. Agent result: ok.

# RESP-NOTE-EDITOR — Staff web: responsive `NoteEditor` (features-chart)

## Summary

Made the SOAP note editor responsive at every supported width (320, 360, 390,
768, 820, 1024 and 1280 px) in both writing directions without changing its
behaviour, copy, API calls or exported names. The component now uses the shared
responsive classes (`page`, `stack`, `cluster`, `field`, `touch`) and the
`useBreakpoint` / `usePointerCoarse` hooks. On compact viewports the signature
control becomes a sticky footer with a logical scroll-space reservation; the
SOAP fields and provenance always remain in the DOM as one stacked reading
column. Added a dedicated jsdom responsive test suite covering the three
breakpoints, roles/labels, keyboard order, right-to-left mirroring and WEB-09
draft preservation across a simulated rotation. Synthetic fixtures only.

## Affected requirements

- WEB-07 — responsive layout at every breakpoint, no horizontal page scroll, no hidden clinical field.
- UX-01 — 44 px coarse-pointer targets, 16 px text entry, logical properties, safe-area insets, wrapping long English/Arabic strings.
- WEB-09 — signature bar reachable above the on-screen keyboard; typed work, attestation and focus survive a breakpoint change.

## Risk class

Routine (docs/WORKFLOW.md): contained presentational change in one staff-web
component plus a new test file. No domain, authorization, persistence or API
boundary changed.

## Review owner

Domain skills loaded and applied: `orevia-delivery`,
`orevia-senior-react`, `orevia-senior-typescript`. No independent
reviewer or human participant was used; all evidence below is **tool evidence**
from executed commands. No clinical or real-user acceptance is claimed.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix.

## Authorization boundaries and audit events

Unchanged. No endpoint, role, tenant check, patient linkage or audit event was
added or altered. All test data is synthetic (`syntheticAlphaChart`,
`syntheticBetaChart`), with no patient identifiers or credentials.

## Migration and rollout considerations

None. Presentation-only change; no public contract, build script or stylesheet
changed.

## Test evidence

Entry-point-to-test trace: `/patients/:id/notes/:noteId` → `NotePage` renders
`<NoteEditor>` → `NoteEditor` reads `useBreakpoint()` / `usePointerCoarse()` →
structure asserted through `tests/responsive/features-chart/NoteEditor.responsive.test.tsx`
(via `getByRole('region', { name: clinicalNote })`).

Commands executed (tool evidence):

1. Red phase, before implementation:
   `cd apps/staff-web && ./node_modules/.bin/vitest run tests/responsive/features-chart/NoteEditor.responsive.test.tsx`
   → `Test Files 1 failed (1) | Tests 7 failed (7)`.
2. Green phase, new suite:
   `cd apps/staff-web && ./node_modules/.bin/vitest run tests/responsive/features-chart/NoteEditor.responsive.test.tsx`
   → `Test Files 1 passed (1) | Tests 7 passed (7)`.
3. Regression, existing chart suites:
   `cd apps/staff-web && ./node_modules/.bin/vitest run src/features/chart/components.test.tsx src/features/chart/chart-screen.test.tsx tests/responsive/features-chart/NoteEditor.responsive.test.tsx`
   → `Test Files 3 passed (3) | Tests 36 passed (36)`.
4. Mandatory frontend gate:
   `cd apps/staff-web && bash check.sh`
   → all stages passed (generated-client check, typecheck, lint, build, bundle
   budget, coverage); final lines:
   `Test Files 159 passed (159)`, `Tests 1898 passed (1898)`,
   `No files with missing coverage.`, `207 files fully covered. 4 empty files skipped.`,
   `Statements 100% (3726/3726)`, `Branches 100% (1896/1896)`,
   `Functions 100% (1258/1258)`, `Lines 100% (3560/3560)`.
   (The console also prints expected React/jsdom noise from unrelated tests that
   deliberately render outside providers; the run exits 0.)
5. Typecheck final lines, as required by the frontend definition of done:
   `cd apps/staff-web && npm run typecheck`
   ```
   > orevia-staff-web@0.1.0 typecheck
   > tsc --noEmit -p tsconfig.json
   ```
   `typecheck exit: 0` — no `error TS…` reported.
6. `cd apps/staff-web && npm run lint` → exit 0 (`eslint . --max-warnings 0`).
7. Branch reconciliation: `git merge --no-edit origin/main` → `Already up to date.`
   (run at start, after implementation and before the finish check).

## Coverage

Command: `cd apps/staff-web && bash check.sh` (uses `npm run test:coverage`,
Vitest v8 with `thresholds.perFile: true` at 100% for lines, branches, functions
and statements), plus the per-file report read from `coverage/coverage-summary.json`.

Result for the only production file changed,
`src/features/chart/NoteEditor.tsx`: `lines 14/14 (100%)`, `branches 24/24
(100%)`, `functions 4/4 (100%)`, `statements 14/14 (100%)`. Repository summary:
all 207 non-empty source files fully covered; no missing coverage.

New or changed branches and the test that takes each side:

| Branch | True side | False side |
|---|---|---|
| `stickySignature = breakpoint === 'compact'` (drives `data-signature-layout` and `signatureStyle`) | `stacks every SOAP field and pins a touch-sized signature bar on compact`; `mirrors under RTL…` | `uses an inline signature row… on medium`; `keeps the inline signature row… on expanded`; `keeps provenance, history…`; `keeps a stable SOAP-to-signature keyboard order…`; `preserves typed work…` |

No other conditional was added. Pre-existing branches in `NoteEditor`
(amendment notice, entered-in-error banner, signed provenance, editable
attestation, amend and entered-in-error actions) are still exercised by the
existing `src/features/chart/components.test.tsx` cases, which remain green in
the full run.

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association,
prescribing or critical-result behaviour changed. No clinical field is hidden or
reordered; only presentation attributes, classes and logical inline styles were
added. Pixel-level layout, zoom and virtual-keyboard behaviour are intentionally
left to the Playwright tasks named in `docs/design/responsive-layout.md`
(RESP-E2E-*), because jsdom cannot paint or measure pixels.

## Findings

- `responsive.css` already provides every class this story needs (`page`,
  `stack`, `cluster`, `field`, `touch`); no stylesheet edit was necessary. The
  only missing utility is a reusable sticky note/form footer class, so the
  sticky signature bar uses logical inline styles, matching the existing `Form`
  implementation.
- Out of scope observation only: three unrelated Java files already contain
  committed conflict markers at branch HEAD (working tree clean; `git merge
  --no-edit origin/main` reported `Already up to date.`):
  `apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7ParseException.java`,
  `Hl7Parser.java`, `Hl7Segment.java` (markers referencing
  `origin/swarm/BUILD-37-469`
