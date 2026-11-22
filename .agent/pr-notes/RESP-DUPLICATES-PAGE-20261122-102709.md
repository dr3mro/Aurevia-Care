# RESP-DUPLICATES-PAGE — Staff web: responsive `DuplicatesPage`

## Summary

Made the tenant-keyed duplicate-review page container responsive without changing
behaviour, text, API calls or exported names. `DuplicatesContent` and
`ReviewWorkflow` now render their columns with the shared `.stack` class from
`src/theme/responsive.css` instead of inline `display: grid`, and expose the
resolved layout band as `data-breakpoint` from the existing `useBreakpoint()`
hook. The `.page` class is left to `ApiPage`, which already supplies logical
safe-area padding and the 1200 px reading cap for both the worklist and detail
regions; nesting a second `.page` would double the gutters at 320 px. The
detail branch and header are also `.stack` columns. All inline styles remain
logical-only (`gap` only), no clinical content is hidden, and no CSS file was
changed. Added a new jsdom structural test that mocks `matchMedia` at compact,
medium and expanded and asserts classes, band, roles/labels, keyboard order,
the 7-width matrix and the right-to-left case.

## Affected requirements

- WEB-07 — responsive staff web from 320 px upward (three layout bands).
- UX-01 — WCAG 2.2 AA patterns: logical properties, wrapping, no `display: none`
  on clinical content, coarse-target floor, RTL mirroring, text scaling.

Acceptance mapping:

| Story step | Where satisfied | Evidence |
|---|---|---|
| 320/360/390/768/820/1024/1280 px | `data-breakpoint` resolves via real hook with mocked media queries | width-matrix test (7 widths) |
| LTR + RTL, no local direction override | document `dir`/`lang` from `I18nProvider`; logical-only inline styles | RTL tests |
| No horizontal page scroll; long EN/AR wrap | `.stack > *` gives `min-inline-size: 0; overflow-wrap: anywhere` | class assertions + logical-style test |
| Columns stack on phones | single-column `.stack` at every band; side-by-side identities stay in `DuplicatesScreen` (sibling story) | per-band root and detail tests |
| 44×44 coarse targets | `Back`/`Open review` use `Button.touch`; fields use `FormField.field.touch` | `touch` class assertions |
| Inputs 16 px; safe-area insets | `.field` and `.page` stylesheet contract | `gen:api`/stylesheet tests (unchanged) + `page` region assertion |
| Logical properties only; no clinical `display: none` | inline styles limited to `gap`; no `hide-compact`/`only-compact` added | logical-style test |
| Compare/review action reachable | worklist review button keeps its name, `tabIndex 0` and focus | keyboard-order test |
| No behaviour/text/API/export change | diff only classes, `data-breakpoint`, one import | `git diff` in evidence |

## Risk class

Routine — contained presentational change to one staff-web component plus a new
test. No API, database, authorization or workflow change. The layout contract is
cross-boundary in principle, but every boundary it touches (`Button`,
`FormField`, `ApiPage`, `DataTable`, `responsive.css`, `DuplicatesScreen`) is
owned by an already-merged or sibling story; this slice changes none of them.

## Review owner

Senior React and Vite specialist (bounded plan review, `smartclinic-senior-react`,
advisory) and UX reviewer (`smartclinic-ux-reviewer` skill loaded). No clinical,
human or real-patient review was performed; this is a simulated structural
review. Pixel accuracy, 200% zoom reflow and painted RTL are deferred to the
Playwright responsive tasks as the story directs.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix.
Synthetic fixtures only (`syntheticPair`, `syntheticDetail`, synthetic
organizations); no patient data, identifiers or credentials in code, tests or
notes.

## Authorization boundaries and audit events

Unchanged. The tenant key (`useActiveOrganization`) still scopes the loader and
the `X-Active-Organization` descriptor; `currentSubject` still drives the
advisory same-reviewer guard; no role, permission, patient-link or audit event
is added. No PHI or secrets touched.

## Migration and rollout considerations

None. Presentation-only change to a staff-web component; no schema, contract,
build script or workflow file changed. Ships with the normal staff-web bundle.

## Test evidence

Evidence source: executed tool commands (primary); the senior-react specialist
returned an advisory plan only and ran no checks; no simulated-user or human
participant was exercised for this layout-structure slice.

Commands actually run (from `apps/staff-web` unless noted):

1. `npm ci --no-audit --no-fund` → `added 486 packages in 7s` (worker image had
   no `node_modules`; pinned install, no dependency added or changed).
2. Red phase — `./node_modules/.bin/vitest run tests/responsive/features-duplicates/DuplicatesPage.responsive.test.tsx`:
   `Test Files 1 failed (1)`, `Tests 22 failed (22)` — missing `.stack` root,
   `data-breakpoint` and band-aware structure confirmed the tests fail before
   the change.
3. Green phase — same command after implementation:
   `Test Files 1 passed (1)`, `Tests 22 passed (22)`.
4. Regression — `./node_modules/.bin/vitest run src/features/duplicates tests/responsive/features-duplicates`:
   `Test Files 7 passed (7)`, `Tests 110 passed (110)`.
5. Full canonical gate — `bash apps/staff-web/check.sh` (from repo root):
   `gen:api: generated client matches the contract`;
   `Test Files 159 passed (159)`; `Tests 1913 passed (1913)`;
   `No files with missing coverage.`; exit 0.
6. Frontend policy subset — `bash scripts/frontend-check.sh`:
   `frontend standards: policy and tool commands passed` (exit 0).
7. Policy stages run individually (see Findings for the two documented worker
   false positives): `check-docs.py`, `check-secrets.py`, `check-names.py`,
   `check-wiring.py`, `check-migrations.py --require-baseline` — all exit 0.

Typecheck final lines (from `bash apps/staff-web/check.sh`, then re-run alone
with explicit exit status):

```
> smartclinic-staff-web@0.1.0 typecheck
> tsc --noEmit -p tsconfig.json
```

`typecheck exit=0` — no `error TS…` remains. An earlier run failed with
`tests/responsive/features-duplicates/DuplicatesPage.responsive.test.tsx(4,1): error TS6133: 'RequestDescriptor' is declared but its value is never read.`;
the unused import was removed and the gate re-run green.

Entry-point-to-test trace:

- `DuplicatesPage` → `DuplicatesContent` → `ApiPage` (`section.page[data-breakpoint]`)
  → `WorklistScreen` table "Duplicate review worklist" → "Review pair …" button
  (`setSelected`) → `ReviewPanel` → `ApiPage` detail → `ReviewWorkflow`
  (`.stack[data-breakpoint]`) → `ReviewScreen` + `ApprovalPanel`.
- Worklist load is asserted by `responsive DuplicatesPage at %s width › renders a
  single labelled page column…` and `…keeps every worklist column…`; navigation to
  detail and the back action by `responsive DuplicatesPage review detail › keeps
  the back action and stacked review regions reachable at %s`; RTL by
  `responsive DuplicatesPage right-to-left › mirrors the document…`; width
  resolution by `responsive DuplicatesPage width matrix › resolves %ipx…`.

`scripts/check.sh` was **not** run in full: it executes `apps/api/check.sh`
(`mvn -q -B verify`, every Java integration test), which the task explicitly
forbids in a worker. Its policy/tooling stages were run individually instead,
with results above and in Findings.

## Coverage

Command: `bash apps/staff-web/check.sh` (script `npm run test:coverage` →
`vitest run --coverage --exclude 'e2e/**'`).

Result:

```
No files with missing coverage.
207 files fully covered.
Statements   : 100% ( 3723/3723 )
Branches     : 100% ( 1892/1892 )
Functions    : 100% ( 1258/1258 )
Lines        : 100% ( 3557/3557 )
```

`apps/staff-web/src/features/duplicates/DuplicatesPage.tsx` is included in the
100% result (no per-file gaps reported). New/changed branches and their tests:

| Added line / branch | Test that covers it |
|---|---|
| `useBreakpoint()` call in `DuplicatesContent` (statement; hook's three-way branch returns compact/medium/expanded) | `responsive DuplicatesPage at %s width` (all three bands) and `width matrix` (7 widths) |
| `useBreakpoint()` call in `ReviewWorkflow` (statement; same three outcomes) | `responsive DuplicatesPage review detail` (compact, medium, expanded) |
| `data-breakpoint` on the root `.stack` — three possible values | `renders a single labelled page column…`, `resolves %ipx…`, `follows a viewport change…` |
| `data-breakpoint` on the `ReviewWorkflow` `.stack` — three possible values | `keeps the back action and stacked review regions reachable at %s` |
| Pre-existing `selected === null ? … : …` — both sides (unchanged branch, re-exercised) | worklist tests (null side) and `reachDetail` detail tests (non-null side) |

No new `if`, ternary, `switch`, `&&`, `||` or `catch` was added; no inline
style with a new branch; no threshold, exclusion or skip was changed. The new
test file is excluded from coverage by the documented `**/*.test.{ts,tsx}` rule
and adds no coverage obligation.

## Clinical and architecture review

Not required for this slice: no signed data, cross-organization sharing,
patient association change, prescribing or critical-result release. Clinical
safety and architecture ownership stay with the existing duplicate-review
stories; this change only alters CSS classes and test metadata on the page
container. The physical pixel claims (44×44 targets, no overlap at 200% zoom,
RTL painted flow) remain unverified in jsdom and are explicitly delegated to
the Playwright projects, as `docs/design/responsive-layout.md` section 13
requires.

## Findings

1. **Pre-existing conflict markers committed on `main` (outside scope).** At
   base `0e9f8955`, `git grep -nE '^(<<<<<<<|>>>>>>>) '` reports markers in
   three Java HL7 files (`Hl7ParseException.java`, `Hl7Parser.java`,
   `Hl7Segment.java`); `git show HEAD:...` confirms they are committed, not a
   local merge. They predate this story, are outside its scope and were not
   touched. The next person should open a repair task for them.
2. **Known worker false positive — `opencode.json` staleness.** `scripts/check-pack.py`
   and `python3 -I scripts/gen-opencode.py --check` report
   `opencode.json is out of date: run scripts/gen-opencode.py` because the CI
   runtime profile is copied over the worktree file under `skip-worktree`.
   Confirmed once: `git ls-files -v opencode.json` → `S opencode.json`, and the
   worktree file differs from `git show HEAD:opencode.json`. The protected file
   was not edited or regenerated (OPS-CW-003-01).
3. **Known worker false positive — Python tooling tests.** `scripts/python-coverage.sh`
   could not install the `coverage` module (`No module named coverage`) and the
   fallback `python3 -I -m unittest discover -s scripts/tests -q` ended with
   `FAILED (failures=6, errors=2)`: six `test_check_pack` failures from the same
   skip-worktree profile and two `test_worker_gate` errors from a missing
   `apps/api/target/surefire-reports` directory (no Java build in this worker).
   No script was changed; the integrator tooling has these prerequisites.
4. **Sibling ownership boundary.** The worklist `<table>` (including its raw
   review-action button and any `.table-region` wrapper) and the side-by-side
   `IdentityCard` layout live in `DuplicatesScreen.tsx`, owned by
   `RESP-DUPLICATES-SCREEN` (swarm-10). This test deliberately does not assert
   identity-card stacking, a labelled table scroll region or `touch` on the raw
   worklist button; those are that story's contract. The two stories must land
   together for the full page to reflow at phone widths.
5. **Safe-area on the header.** The title/notice `<header>` sits above
   `ApiPage`'s `.page`, so it does not itself receive `.page`'s safe-area
   padding. This matches the existing `PatientsPage` shape; the shell boundary
   supplies safe-area padding in the real app, the notice is short/non-clinical
   and wraps, and pixel accuracy is a Playwright claim. No new CSS class was
   available for a padded-but-not-double-padded wrapper, so none was added.

## Lessons for workers

- For a feature page that renders `ApiPage`, make the page's own root `.stack`,
  not `.page`: `ApiPage` already emits the single `.page` section, and a second
  one doubles inline padding and applies the reading cap twice at 320 px.
- A raw `<button>` in a screen file is not the shared `Button` component: assert
  `touch`/`data-breakpoint` on components that own them, and keep screen-internal
  buttons to role, label and keyboard assertions when your scope is the page.
- In a cloud worker, treat `opencode.json` and Python tooling test failures as
  CI-profile/worker-image results (`git ls-files -v` shows `S`); confirm once and
  never "fix" them by regenerating or editing protected files.
- Run the canonical module gate (`bash apps/staff-web/check.sh`) before the
  repository gate: `scripts/check.sh` calls `mvn verify` and is a worker timeout,
  not a worker check.

## Why this is not finished

Not applicable — the story is finished. All scoped tests, typecheck, lint, build,
bundle-size and 100% coverage gates passed locally; no push, tag or `gh` call was
made. Items under Findings are pre-existing or sibling-scope residuals, not
blockers for this slice.
