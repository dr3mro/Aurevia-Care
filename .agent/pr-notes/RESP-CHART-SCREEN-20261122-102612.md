# RESP-CHART-SCREEN — responsive `ChartScreen` (features-chart)

## Summary

Composed `ChartScreen` with the existing responsive contract instead of new CSS:
the labelled outer region now carries `page`, `data-breakpoint` and the composed
regions carry `stack`/`grid-2` plus `data-layout` so the chart summary and the
note editor stack on phones and sit side by side from 768 px. Both wide clinical
tables are contained in labelled, keyboard-scrollable `table-region` wrappers so
a narrow viewport cannot scroll the whole page sideways. Logical inline styles
(`min-inline-size`, `overflow-wrap`) keep long English and Arabic values wrapping
under either direction. The breakpoint is read with `useBreakpoint()` and exposed
as `data-breakpoint` because jsdom cannot evaluate media queries or paint pixels.

A new responsive test file mocks `matchMedia` for compact, medium and expanded,
drives all seven contract widths through `breakpointFor`, and asserts structure,
labels/roles, keyboard order, RTL behaviour, an empty state and a live breakpoint
change. No text, behaviour, API call or exported name changed. Synthetic fixtures
only; no dependency added; no file outside the declared scope changed.

## Affected requirements

- WEB-07 — responsive supported clients from 320 px; three layout breakpoints.
- UX-01 — English/Arabic, RTL, mixed clinical text, WCAG 2.2 AA reflow and keyboard access.
- Design evidence: `docs/design/responsive-layout.md` §2, §5, §7, §11, §13;
  `docs/decisions/0004-responsive-staff-web.md`; `PROJECT_V1_SPEC.md` WEB-07 line 155, UX-01 line 90.

Acceptance mapping:

| Story step | Evidence |
|---|---|
| Read component, test, layout guide, stylesheet, hook | Tool reads recorded in this run |
| Work at 320/360/390/768/820/1024/1280, LTR and RTL | `it.each(VIEWPORT_WIDTHS)` asserts `data-breakpoint` and structure; RTL test |
| No horizontal page scroll; long values wrap | `.page` + two `.table-region` wrappers + logical `min-inline-size`/`overflow-wrap` asserted |
| Columns stack on phones | compact → `stack` / `single-column` |
| Coarse pointers 44×44; inputs 16 px | child `Button` already renders `class="touch"`; 16 px input gap is a child-component task (see Findings) |
| Safe-area insets; logical properties | `.page` class owns logical safe-area padding; assertion rejects physical inline sides and direction overrides |
| No clinical column/field hidden with `display: none` | full column-header counts (8/8/8/3/9) and no hidden inline style at every band |
| 200% zoom readable | logical wrapping and no fixed block size; real zoom measurement belongs to the Playwright tasks |
| New test file; existing tests still pass | 159 test files / 1904 tests green in the module gate |
| No behaviour/text/API/export change | diff is presentation-only; all original handlers and strings unchanged |

## Risk class

Routine (contained presentation change to one screen plus one new test). A bounded
simulated UX/RTL review was requested because the component composes clinical data.

## Review owner

- `orevia-senior-react` and `orevia-senior-typescript` skills loaded; module gate owns typecheck/lint/build/coverage.
- `orevia-ux-reviewer` bounded simulated review: **no blocking finding; do not hold the task**
  (reviewer evidence, static read, not human or clinical acceptance).

## Data changes

None. No migration, backfill, lock, volume impact, rollback or forward-fix.

## Authorization boundaries and audit events

None changed. The existing synchronous tenant-state reset (`state.organizationId !==
organizationId`) and every clinical handler are byte-for-byte unchanged; no new
audit event, role, tenant scope or patient-link scope was introduced.

## Migration and rollout considerations

None.

## Test evidence

All commands run from the repository root unless noted. Evidence source: executed
tool commands (automated), plus one simulated UX reviewer (static read, no execution
tool) whose verdict was "no blocking finding in this change"; no simulated user,
human participant or clinical evidence is claimed.

- Red (before implementation):
  - `cd apps/staff-web && npx vitest run tests/responsive/features-chart/ChartScreen.responsive.test.tsx`
    → `Test Files  1 failed (1)`, `Tests  13 failed (13)`.
- Green, touched files:
  - `cd apps/staff-web && npx vitest run tests/responsive/features-chart/ChartScreen.responsive.test.tsx src/features/chart/chart-screen.test.tsx src/features/chart/components.test.tsx`
    → `Test Files  3 passed (3)`, `Tests  42 passed (42)`.
- Canonical frontend gate (includes typecheck):
  - `bash apps/staff-web/check.sh` → exit 0.
  - `gen:api:check`: `gen:api: generated client matches the contract`.
  - Typecheck final lines: `> orevia-staff-web@0.1.0 typecheck` /
    `> tsc --noEmit -p tsconfig.json` / (no output — no `error TS…`) /
    `> orevia-staff-web@0.1.0 lint`.
  - Lint: `eslint . --max-warnings=0` clean. Build: `tsc --noEmit -p tsconfig.json && vite build` clean;
    `node scripts/check-bundle-size.mjs` clean.
  - Tests: `Test Files  159 passed (159)`, `Tests  1904 passed (1904)`.
- Policy stages of `scripts/check.sh` run individually (the full script also runs the
  10+ minute API Maven verify, which this worker is instructed not to run):
  - `python3 -I scripts/check-names.py` → exit 0
  - `python3 -I scripts/check-docs.py` → exit 0
  - `python3 -I scripts/check-secrets.py` → exit 0
  - `bash scripts/frontend-check.sh` → exit 0, final line `frontend standards: policy and tool commands passed`
- Branch reconciliation: `git merge --no-edit origin/main` at start and after the
  implementation stage → `Already up to date.`; no conflict from this work.
- Entry point → test trace: `ChartScreen` is an exported presentational component
  (`src/features/chart/ChartScreen.tsx:78`); it is mounted directly through
  `ThemeProvider` + `I18nProvider` in
  `tests/responsive/features-chart/ChartScreen.responsive.test.tsx`, which asserts
  the labelled `.page` region (`role="region"`, `aria-label="Longitudinal chart"`),
  the `data-part="chart-content"` layout, four clinical tables plus the document
  table, SOAP fields, the `role="status"` announcement and the keyboard order.
  Route-level mounting is a pre-existing gap recorded under Findings.

## Coverage

- Command: `bash apps/staff-web/check.sh` → `npm run test:coverage` (Vitest 5.1.1,
  provider v8, `perFile: true`, thresholds lines/branches/functions/statements = 100).
- Result: `No files with missing coverage. 207 files fully covered. 4 empty files skipped.`
  - `Statements   : 100% ( 3723/3723 )`
  - `Branches     : 100% ( 1892/1892 )`
  - `Functions    : 100% ( 1258/1258 )`
  - `Lines        : 100% ( 3557/3557 )`
- Touched production file read from `coverage/coverage-summary.json`:
  - `src/features/chart/ChartScreen.tsx` — lines 37/37, branches 12/12, functions 27/27,
    statements 45/45 → 100% on every metric. No exclusion, skip or threshold change.
- Branch-to-test mapping. No new conditional branch was introduced: the layout is a
  typed lookup keyed by `useBreakpoint()` (`CHART_LAYOUT[breakpoint]`), so presentation
  has no `if`/ternary to leave uncovered. All 12 branches in the file are pre-existing
  and remain covered:
  - tenant reset `state.organizationId !== organizationId` → `chart-screen.test.tsx`
    `resets local state when the active tenant changes`.
  - no-chart early return → new `keeps the concealment-safe empty states inside the
    page wrapper` plus existing `shows a concealment-safe empty state…`.
  - chart optional-chain / `?? []` fallback → existing component and screen tests with
    a chart and with an unknown organization.
  - `error ? … : null` → existing `announces an accessible error…` sign-error test.
  - `revision ? … : …` → existing `shows a note empty state…` test.
  - The three layout bands of the lookup are taken by the new width matrix
    (320/360/390 → compact; 768/820/1024 → medium; 1280 → expanded), the
    `it.each(['medium','expanded'])` test, and the live `compact → expanded` resize test.

## Clinical and architecture review

No signed-data, cross-organization sharing, patient-association, prescribing or
critical-result behaviour changed, so no clinical sign-off is required for this
presentation-only change. The simulated UX reviewer checked keyboard, RTL, wrapping,
touch targets and logical properties and recorded no blocking finding; real-audit,
Arabic-clinician and product-owner acceptance remain separate launch gates. Pixel,
zoom and axe claims remain with the Playwright tasks (responsive-layout §13). No
patient data or credential appears in code, tests, logs or this note.

## Findings

1. **Out of scope, pre-existing:** `ChartScreen` is not mounted by a route today —
   `/patients/:id/chart` (`src/features/chart/routes.ts:16`) renders `ChartPage`, which
   composes `LongitudinalChart` directly and never imports `ChartScreen`. This change
   keeps the exported name and behaviour and cannot wire the screen without editing
   `ChartPage.tsx`/`routes.ts`, outside the declared scope. Wiring is a separate task.
2. **Out of scope, pre-existing:** the `.field` class (16 px input text, prevents iOS
   zoom) must be applied to the SOAP `<textarea>` elements
   (`NoteEditor.tsx:72-79`) and the `.touch` class to the attestation checkbox
   (`NoteEditor.tsx:101-106`); the child component carries neither. A font-size on an
   ancestor wrapper cannot override the textarea's UA font, so this cannot be fixed
   from `ChartScreen.tsx`. Owner: **RESP-NOTE-EDITOR** (`TODO.md:3653-3666`). Likewise
   the phone card reflows of `LongitudinalChart` (RESP-LONGITUDINAL-CHART) and the
   nine-column `DocumentList` (RESP-DOCUMENT-LIST) belong to their own tasks. This
   screen supplies the labelled `table-region` scroll containment and logical wrapping
   for their composed output and hides nothing. No class was missing from
   `responsive.css`, so no stylesheet edit was needed.
3. **Pre-existing repository defect, not introduced here:** three committed files under
   `apps/api/src/main/java/com/aurevia/exchange/hl7/` still contain Git
   conflict markers on HEAD/`origin/main` (`Hl7ParseException.java`, `Hl7Parser.java`,
   `Hl7Segment.java`). Evidence: `git grep -nE '^(<<<<<<<|>>>>>>>) ' HEAD -- apps/api/src/main/java/com/aurevia/exchange/hl7`.
   They are outside this story's scope and were left untouched; the integrator/owner
   must resolve them. `git merge --no-edit origin/main` itself was clean.
4. `scripts/check.sh` was not run end to end because it invokes the full API Maven
   verify/integration suite; its relevant policy stages and the canonical module gate
   were run individually with the results above.
5. **Clinical UX finding, pre-existing and out of scope (simulated reviewer):** the
   void-association buttons in `src/features/chart/DocumentList.tsx:77-83` mutate a
   clinical record with no confirmation step, which the reviewer flagged against the
   destructive-action rule (accessibility-patterns §3). This diff does not touch that
   handler; route the finding to the DocumentList/clinical-safety review rather than
   this responsive story. No task-request is filed because the change needs a separate
   owner decision, not a dependency of this story.

## Lessons for workers

- `useBreakpoint()` must be called before any early return in a screen component,
  including the no-chart empty state, so the hook order stays unconditional.
- jsdom serialises `minInlineSize: 0` as `'0'`, not `'0px'`; set `'0px'` explicitly when
  the test asserts the logical property string.
- For screen-level responsive work in jsdom, `data-breakpoint`/`data-layout` plus the
  existing `page`/`stack`/`grid-2`/`table-region` classes are the testable contract;
  pixel, zoom and pointer claims belong to the Playwright projects.
- After merging `origin/main`, re-run `git diff origin/main...HEAD --name-status` and
  separate pre-existing conflict markers (committed on main) from conflicts introduced
  by the current merge; never edit out-of-scope files to "fix" them.
