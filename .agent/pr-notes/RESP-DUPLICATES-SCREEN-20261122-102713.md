# RESP-DUPLICATES-SCREEN — Staff web: responsive `DuplicatesScreen` (features-duplicates)

## Summary

Made the three presentational duplicate-review screens responsive without changing behaviour, text, API calls or exported names. The compact band wraps both clinical tables in the shared labelled `.table-region` scroll region, stacks the two identity cards with `.stack`, and uses the touch-target class on the row action and confirmation checkboxes; medium and expanded keep the full table in flow and place the identities side by side with the existing `.grid-2`. Long English/Arabic values wrap with logical inline styles; no clinical column or field is hidden and no stylesheet, dependency or shared component was changed. A new jsdom test file asserts the structure, labels, roles, keyboard order and RTL behaviour at compact, medium and expanded.

Scope (unchanged): `apps/staff-web/src/features/duplicates/DuplicatesScreen.tsx` and `apps/staff-web/tests/responsive/features-duplicates/DuplicatesScreen.responsive.test.tsx`. Synthetic fixtures only.

## Affected requirements

- WEB-07: supported clients and compact layout from 320 px; responsive reflow with a labelled table region instead of page-level horizontal scroll.
- UX-01: English and Arabic, RTL mirroring via logical properties, mixed-script isolation, WCAG 2.2 AA patterns (44 px coarse targets, 16 px inputs, text scaling/reflow).
- Decision `0004-responsive-staff-web` and `docs/design/responsive-layout.md` (component pattern matrix, verification matrix R1–R13).

Acceptance mapping:
| Story requirement | Implementation | Test assertion |
|---|---|---|
| 320–1280 px, no page scroll; columns stack on phones | `ResponsiveTableRegion` + `.stack`/`.grid-2` on the identity container | compact region tests; medium/expanded side-by-side tests; breakpoint-change test |
| Two identities stacked on phones, side by side from 768 px, differences in text | `data-layout="stacked\|side-by-side"`; unchanged conflict table headers/cards | identity layout assertions; `Surviving value` / `Duplicate value` text assertion |
| Long English/Arabic wrap; 200 % zoom readable | `overflowWrap`/`whiteSpace`/`minInlineSize` on cells, cards, `<pre>`; no fixed block size | cell and `<pre>` style assertions; no `display: none` assertions |
| Coarse pointers 44 × 44; inputs 16 px | `.touch` on row action and checkboxes; `.field` on all text inputs (from `FormField`) | `.touch`/`.field` class assertions |
| Safe-area insets; logical properties only | handled by the existing shell/`ApiPage` `.page`/`.app-shell` classes; no new physical side | `assertLogicalOnly` scans every inline style in each rendered subtree |
| No clinical column hidden | full table and field set at every band | column-header arrays and `display` assertions |
| LTR and RTL | document direction from `I18nProvider`; `<bdi dir="auto">` and `<code dir="ltr">` runs unchanged | Arabic compact/expanded test |
| Keyboard order | unchanged DOM order; one tab stop on the compact table region | `focusableTags` / `tabIndex` assertions |

## Risk class

Routine (contained staff-web presentation change). Cross-boundary only in the sense that it reuses the shared responsive classes and hook from `src/theme`; no API, database, migration or authorization surface changed.

## Review owner

Senior React and Vite (implementation), Senior TypeScript (typecheck), UX reviewer (RTL and accessibility). Two bounded simulated reviews were run and their findings dispositioned under Findings. Reviewers did not re-run the gate; the exit-0 and coverage claims in these notes come from commands executed by the primary agent (tool evidence). No human or clinical reviewer evidence is claimed.

## Data changes

None. No migration, backfill, lock or volume impact. Synthetic fixtures only (`SYN-SURVIVOR-LONG`, `SYN-DUPLICATE-LONG`, `MRN-1001@SYNTHETIC`, `MRN-1002@SYNTHETIC`); no patient data or credentials in code, tests or notes.

## Authorization boundaries and audit events

None changed. The change is presentational only; no role, tenant, patient-link or release decision is touched and no audit event is added or altered.

## Migration and rollout considerations

None. No schema, contract or build-script change; no reserved migration version used.

## Test evidence

Evidence source: executed shell commands (tool), plus simulated specialist reviews where noted. No simulated-user or human-participant evidence.

- `git merge --no-edit origin/main` → `Already up to date.` (branch `swarm/RESP-DUPLICATES-SCREEN-963`).
- Baseline, before any edit: `npx vitest run --exclude 'e2e/**' src/features/duplicates/DuplicatesScreen.test.tsx` → `Test Files 1 passed (1)`, `Tests 13 passed (13)`.
- Red stage, new test against unchanged component: `npx vitest run --exclude 'e2e/**' tests/responsive/features-duplicates/DuplicatesScreen.responsive.test.tsx` → `Test Files 1 failed (1)`, `Tests 7 failed (7)` (missing region, `data-breakpoint`, `data-layout`, `.touch` and wrapping styles).
- Green stage: `npx vitest run --exclude 'e2e/**' tests/responsive/features-duplicates/DuplicatesScreen.responsive.test.tsx src/features/duplicates/DuplicatesScreen.test.tsx` → `Test Files 2 passed (2)`, `Tests 21 passed (21)` (a second RTL test was added after simulated UX review).
- Canonical gate: `bash apps/staff-web/check.sh` → exit 0; `Test Files 159 passed (159)`, `Tests 1899 passed (1899)`; generated-client drift, lint, production build and bundle-size budget all passed. Re-run twice (before and after the RTL test addition), both exit 0.
- `npm run typecheck` → exit 0, no `error TS…`. Final lines:
  ```
  > smartclinic-staff-web@0.1.0 typecheck
  > tsc --noEmit -p tsconfig.json
  TYPECHECK_EXIT=0
  ```
- Repository gate: `bash scripts/check.sh` stopped at `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. Verified once as the documented cloud-worker artifact (OPS-CW-003-01): `git ls-files -v opencode.json` → `S` (skip-worktree), worktree file byte-equal to `.github/opencode.ci.json` and different from `git show HEAD:opencode.json`. No config was regenerated or edited; the integrator owns the full gate. Remaining stages were run individually: `scripts/check-docs.py`, `check-secrets.py`, `check-names.py`, `check-wiring.py`, `check-migrations.py --require-baseline` and `bash scripts/frontend-check.sh` all exit 0 (`frontend standards: policy and tool commands passed`). `bash scripts/python-coverage.sh` exits 1 on worker-image limits: 6 `test_check_pack` failures from the same CI-profile artifact, 2 git/gh sandbox fixture errors in `test_worker_gate*`, and `coverage is not installed`; no failure points to a changed file.
- Entry-point-to-test trace: `WorklistScreen` (exported from `DuplicatesScreen.tsx`, rendered by `DuplicatesPage.tsx` inside `ApiPage`) → `tests/responsive/features-duplicates/DuplicatesScreen.responsive.test.tsx` compact/medium/expanded worklist tests; `ReviewScreen` → compact identities/conflicts and medium/expanded side-by-side tests; `ApprovalPanel` → compact form keyboard/touch test; all three → RTL and breakpoint-change tests. Existing `src/features/duplicates/DuplicatesScreen.test.tsx` still passes unchanged.
- jsdom limitation: pixel widths, painted focus rings and real media-query evaluation are not observable here; those checks belong to the Playwright tasks (RESP-E2E-*). The tests assert classes, attributes, inline logical styles and structure only.

## Coverage

Command: `bash apps/staff-web/check.sh` (runs `npm run test:coverage`, Vitest v8, `perFile: true`, 100 % thresholds; no threshold or exclusion changed). Result:

```
No files with missing coverage.
207 files fully covered.
4 empty files skipped.
Statements   : 100% ( 3734/3734 )
Branches     : 100% ( 1898/1898 )
Functions    : 100% ( 1260/1260 )
Lines        : 100% ( 3568/3568 )
```

New/changed branches in `DuplicatesScreen.tsx` and the test that takes each side:

| Branch | True side — test | False side — test |
|---|---|---|
| `ResponsiveTableRegion` `if (compact)` (worklist) | `puts the compact worklist in a labelled keyboard-scrollable region with every column`; `mirrors structure under RTL…` compact band | `drops the scroll region and keeps the full table on medium and expanded`; `reflows when the viewport crosses a breakpoint…` after `fire(medium, true)` |
| `ResponsiveTableRegion` `if (compact)` (conflict table) | `stacks the identities and labels conflicts in text on compact phones` | `places the identities side by side from medium without hiding a field` |
| `className={compact ? 'stack' : 'grid-2'}` (identities) | `stacks the identities…`; `reflows…` before the fire | `places the identities side by side…`; `reflows…` after the fire |
| `data-layout={compact ? 'stacked' : 'side-by-side'}` | same two tests as the class branch | same two tests as the class branch |

Existing branches (empty conflicts/dependencies, review present/absent, notice states) are unchanged and remain covered by `src/features/duplicates/DuplicatesScreen.test.tsx` (13 tests, part of the 1898).

## Clinical and architecture review

No clinical data decision, release gate or cross-organization sharing is involved; clinical/architecture sign-off is not required for this presentation-only change. The UX reviewer check is simulated, not human or Arabic-clinician acceptance; the per-site accessibility and Arabic clinical review remain separate launch gates.

## Findings

- No missing responsive class was found: `table-region`, `stack`, `grid-2`, `cluster`, `touch` and `field` all exist in `src/theme/responsive.css`, and `useBreakpoint` exposes the three bands. No stylesheet or hook change was needed.
- Safe-area insets are supplied once by the shell and `ApiPage` `.page`/`.app-shell` rules, which already map the physical `env()` insets into logical custom properties for both directions; the screens render inside that wrapper in production, so no per-screen duplicate mapping was added.
- Pre-existing, out of scope and not touched: `git grep -nE '^(<<<<<<<|>>>>>>>) '` reports committed conflict markers in `apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7/{Hl7ParseException,Hl7Parser,Hl7Segment}.java`. They were already committed on HEAD (the worktree is clean and `git merge origin/main` was up to date), and they are unrelated to staff web. Owner: Java/integrator; no action taken here.
- No user-facing text, exported name, API call or behaviour changed. The `identitiesTitle` message remains "Side-by-side identities"/"الهويات جنبًا إلى جنب" even in the stacked band because message files are outside this task's scope; the section is still labelled correctly and the layout is asserted by `data-layout`.
- Simulated senior-react review (bounded, no edits): confirmed no text/API/exported-name change, logical-only styles, full clinical data at every band, keyboard order and branch coverage. Raised (a) three identically named landmarks on the compact conflict table plus a tab stop with nothing to scroll, and (b) duplication of `DataTable`'s table-region contract. Disposition: (a) the labelled `role="region"`/`tabIndex=0` treatment is mandated by `responsive-layout.md` §5 and decision 0004, and the parent named section comes from the unchanged screen/`ApiPage` structure; a differentiated label would require a new message key outside scope, so this is recorded as a shared-pattern follow-up rather than changed here. (b) extracting a shared component would edit `src/components/DataTable.tsx`, outside this task's scope; recorded below as a follow-up, not a defect introduced by this slice.
- Simulated UX review (bounded, no edits): found RTL evidence covered only `WorklistScreen`, while the new identity/conflict/form layout was scanned for logical properties in English only. Fixed within scope by adding `keeps the review detail and approval form right-to-left at both bands`, which asserts `dir`/`lang`, `<bdi dir="auto" lang>` on Arabic conflict text, `<code dir="ltr">` identifiers, `data-layout` plus `.stack`/`.grid-2`, `.field`/`.touch` classes and `assertLogicalOnly` on both `ReviewScreen` and `ApprovalPanel` at compact and expanded. It also noted the pre-existing MRN run uses `MixedScriptText` (`dir="auto"`, wrapping) instead of an isolated `dir="ltr"` identifier run: pre-existing, presentation/behaviour change out of scope; follow-up for the feature owner.

## Lessons for workers

- Scope a jsdom responsive query with the class, not only the accessible name: a named `<section aria-label>` is itself a `region`, so `getByRole('region', { name })` can match both the section and the nested `.table-region`. Use `getAllByRole(...).find((n) => n.className.includes('table-region'))`.
- An inline `min-height` outranks the `.touch` class's coarse-pointer 44 px rule; remove the inline floor and let the stylesheet own both target sizes.
- When two breakpoint bands are asserted in one `it`, unmount the first render explicitly (`view.unmount()`); Testing Library cleans up only between tests.
- The staff-web coverage gate is per-file and all-source: a new helper with an `if` needs both sides asserted in the same pull request; the two-band table-region and identity-layout tests provide them.
- Apply the RTL structural scan to every component a responsive change touches, not only the first one: a labelled `<bdi>`, an isolated `<code dir="ltr">` and `data-layout` deserve the same compact/expanded assertions as the worklist.
- A simulated reviewer's acceptance of tool evidence is not independent verification of that evidence: re-run the canonical gate yourself after incorporating findings (done here: 1899 tests, 100 % coverage).
- Merge `origin/main` and re-run the targeted tests before the module `check.sh`; a green targeted run is not a substitute for `bash apps/staff-web/check.sh` (typecheck is part of done).
