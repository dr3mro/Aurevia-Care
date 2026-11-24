# RESP-NOTE-PAGE · Staff web: responsive `NotePage` (features-chart)

PR #758, tested branch tip 0e2fbe6b94fe4afa9c6c90d817ee9ce98d3f9445.

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

Task: RESP-NOTE-PAGE

Swarm worker 4. Local checks: failed. Agent result: ok.

# RESP-NOTE-PAGE — Staff web: responsive `NotePage` (features-chart)

## Summary

Made the API-backed `NotePage` responsive at 320–1280 px in LTR and RTL without
changing behaviour, text, API calls or exported names. The root now uses the
`.page` grid (logical safe-area padding, `minmax(0, 1fr)` single column, 1200 px
reading cap); the patient identity line is a wrapping `.cluster`; the save action
and the in-flow sign/entered-in-error confirmation action rows stick to the block
end on phones so they stay reachable above the on-screen keyboard; the
entered-in-error reason input carries `.field .touch` (16 px text, 44 px coarse
target); compare panels wrap long text. Added a dedicated responsive test file
that mocks `matchMedia` for compact/medium/expanded and proves structure, roles,
keyboard order, breakpoint transitions, RTL, and touch/logical-property rules.

Scope (only these two files changed):
- `apps/staff-web/src/features/chart/NotePage.tsx`
- `apps/staff-web/tests/responsive/features-chart/NotePage.responsive.test.tsx`

## Affected requirements

- WEB-07 (responsive staff web), UX-01 (accessibility / RTL), plus WEB-09
  (unsaved work survives rotation / on-screen keyboard) carried in the story detail.

## Risk class

Routine — contained staff-web view presentation change; no data, authorization,
contract or API change. Reviewed against `docs/WORKFLOW.md` routine class.

## Review owner

Senior React and Vite (`aurevia-senior-react`), Senior TypeScript
(`aurevia-senior-typescript`), UX reviewer (`aurevia-ux-reviewer`).
These skills were loaded; findings below are simulated review over the design
contracts, not clinical or human acceptance.

## Data changes

None. No migrations, backfills, table or lock/volume impact; no rollback needed.

## Authorization boundaries and audit events

None changed. No tenant, role, patient-link or audit behaviour touched; all
synthetic fixtures only (`patient-1`, `org-a`, fabricated names/titles).

## Migration and rollout considerations

None. No configuration, feature flag or deployment change.

## Test evidence

Evidence source: executed tool commands (Vitest, tsc, ESLint, `check.sh`).
No reviewer-only, simulated-user or human-participant execution evidence.

Frontend definition of done — `bash apps/staff-web/check.sh` (gen:api:check →
typecheck → lint → build → bundle budget → 100% coverage):

```
EXIT=0
 Test Files  159 passed (159)
      Tests  1916 passed (1916)
No files with missing coverage.
207 files fully covered.
Statements   : 100% ( 3726/3726 )
Branches     : 100% ( 1900/1900 )
Functions    : 100% ( 1258/1258 )
Lines        : 100% ( 3560/3560 )
```

Typecheck final lines (`npm run typecheck` = `tsc --noEmit -p tsconfig.json`),
no `error TS…`:

```
> aurevia-staff-web@0.1.0 typecheck
> tsc --noEmit -p tsconfig.json
```

Lint final lines (`npm run lint` = `eslint . --max-warnings=0`), no warnings:

```
> aurevia-staff-web@0.1.0 lint
> eslint . --max-warnings 0
```

Red/green evidence (entry point → test trace):
- RED: `node_modules/.bin/vitest run tests/responsive/features-chart/NotePage.responsive.test.tsx --exclude 'e2e/**'` → `Test Files 1 failed (1)`, `Tests 25 failed (25)` (no `data-note-page-view` root, no sticky action rows, no `field touch` input).
- GREEN (new file): same command → `Test Files 1 passed (1)`, `Tests 25 passed (25)`.
- REGRESSION: `vitest run src/features/chart/NotePage.test.tsx tests/responsive/features-chart/NotePage.responsive.test.tsx` → `Test Files 2 passed (2)`, `Tests 63 passed (63)` (all 38 pre-existing NotePage tests still pass).
- Entry point to test: `NotePage` is rendered through the real `RouterProvider` at `/patients/:id/notes/:noteId` with `ThemeProvider`, `I18nProvider` and `ActiveOrganizationProvider`; `send` is mocked with synthetic read/write responses. Rendering reaches `NotePage` → `NoteEditor` → confirmations, and assertions resolve on the rendered DOM.

## Coverage

Command and result: `bash apps/staff-web/check.sh` → Vitest v8 coverage,
per-file thresholds 100%; `No files with missing coverage`, 207 files fully
covered, Statements/Branches/Functions/Lines all 100% (see Test evidence).

Every new or changed branch in `NotePage.tsx` and its covering test:

| Branch | Test that takes each side |
|---|---|
| `breakpoint === 'compact' ? 'sticky-save-action' : 'inline-save-action'` (root `data-note-page-view`) | compact case of `describe.each(... 'resolves the breakpoint…')`; medium/expanded cases assert `inline-save-action` |
| `breakpoint === 'compact' ? stickySave : undefined` (save bar `position`) | compact case asserts `position: sticky`; medium/expanded cases assert empty; `switches the action bar when the viewport grows past compact` fires the media change and asserts the flip |
| `compact ? stickyActions : undefined` in `ConfirmationDialog` (sticky action row) | `keeps the sign confirmation keyboard reachable with a sticky action row on compact` asserts `sheet-actions` + `position: sticky` + `inset-block-end: 0px`; `keeps the sign confirmation actions inline on expanded desktops` asserts `inline-actions` + empty position |
| `compact ? 'sheet-actions' : 'inline-actions'` (dialog view attribute) | compact sign test and entered-in-error test assert `sheet-actions`; expanded sign test asserts `inline-actions` |
| `useBreakpoint` compact / medium / expanded outcomes | `responsive NotePage viewport matrix` `it.each([320, 360, 390, 768, 820, 1024, 1280])` resolves each width to its band |
| `comparing ? <compare section> : null` (wrapped `<pre>` lines) | `wraps long compare text with logical white-space and no fixed block size` triggers a conflict, opens compare, asserts both `<pre>` wrap styles and no fixed block size |

No threshold, exclusion, skip or ignore was added or weakened.

## Clinical and architecture review

No signed data, sharing, prescribing or critical-result behaviour changed, so no
clinical sign-off is required for this presentation-only slice. A simulated UX
review over `docs/design/responsive-layout.md`, `rtl-and-localization.md`,
`accessibility-patterns.md` and `docs/decisions/0004-responsive-staff-web.md`
confirmed: no clinical field hidden with `display: none`; identifiers remain
atomic `dir="ltr"` runs; logical properties only; keyboard/reachable actions
preserved. This is simulated evidence, not human or Arabic-clinician acceptance
(the latter remains a separate launch gate). jsdom cannot paint pixels, so pixel
checks are left to the Playwright tasks as the story directs.

## Findings

- `responsive.css` has no sticky action/footer class (the design doc describes a
  `.form__actions` pattern in section 8 but the stylesheet does not ship it).
  Per story rule 3 the stylesheet was NOT edited; logical inline styles
  (`position: sticky`, `inset-block-end: 0px`, logical padding/border) implement
  the sticky save bar and confirmation action rows. Recorded under Lessons.
- Th
