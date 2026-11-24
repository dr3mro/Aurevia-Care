# RESP-STAFF-HISTORY-VIEW — Staff web: responsive `StaffHistoryView` (features-admin)

## Summary

Made `StaffHistoryView` responsive down to 320 px. The component now reads the
shared `useBreakpoint()` hook and exposes the active band as `data-breakpoint`.
At compact widths the eight append-only history fields are rendered as labelled
cards (one real table per event with a caption and `th scope="row"` pairs); at
medium and expanded widths the existing labelled table is kept. Values are
shared through one `eventValues` helper so no clinical field can appear in one
layout and disappear in the other. The section uses the `page` utility for
safe-area-aware logical padding, the compact list uses the `card-list` utility,
and all new inline styles use logical properties with wrapping for long English
and Arabic runs. Text, labels, API calls and exported names are unchanged.

## Affected requirements

- **WEB-07** — supported clients and compact layout: the history view renders a
  usable, non-clipped layout at 320/360/390/768/820/1024/1280 px.
- **UX-01** — English/Arabic RTL, keyboard order, no hidden clinical column,
  wrapping text and accessible structure across breakpoints.

Acceptance mapping:

| Story requirement | Evidence |
|---|---|
| Cards on phones; table on medium/expanded | `renders labelled cards with the full column set on every phone width`; `keeps a full table with no cards on every medium and expanded width` |
| 320/360/390/768/820/1024/1280 px | Width matrix in both tests above plus `switches from cards to the table when the viewport crosses the compact edge` |
| Full column set, no `display: none` | Both structure tests assert all eight labels via `rowheader`/`columnheader`; cell `style.display` is asserted not `none` |
| Long English/Arabic wrap | `wraps long English and Arabic clinical values without clipping them`; cells and `bdi` runs assert `overflowWrap: anywhere`, `whiteSpace: normal` |
| Logical properties and safe areas | `mirrors the Arabic RTL card layout without a direction override or physical styles`; section uses `page`, compact list uses `card-list`; caption `textAlign: start` |
| Keyboard order and roles | `keeps a single keyboard order: the labelled region is the only tab stop`; region has `role=region`, `aria-label` and `tabIndex=0`; cells are not tab stops |
| RTL | `mirrors the Arabic RTL card layout...` with `I18nProvider initialLocale='ar'` and `reasonLang='ar'` |
| Existing behaviour/text | `src/features/admin/admin-components.test.tsx` unchanged and green (5 `StaffHistoryView` cases) |
| Inputs 16 px / 44 px targets | Not applicable: this view renders no inputs and no pointer actions; it is a read-only history region. The shared `field`/`touch` utilities own those rules elsewhere. |

## Risk class

Routine (contained staff-web view change) with UX-01 accessibility/RTL review.
No authorization, tenant, patient-identity, prescribing or release behaviour is
touched. Assessment via `docs/WORKFLOW.md` and simulated review only.

## Review owner

Senior React and Vite specialist (skill `aurevia-senior-react` loaded),
Senior TypeScript specialist (`aurevia-senior-typescript` loaded) and UX
reviewer (`aurevia-ux-reviewer` loaded) for the accessibility/RTL rules.
A bounded read-only specialist review was requested and returned **no blocking
findings** after checking card semantics/full column set, logical-only styling
and RTL, branch/test alignment, and unchanged behaviour/text/exports (evidence
source: reviewer, `aurevia-senior-react` subagent; it did not re-run commands
or edit files). All review evidence is simulated; no human clinical or unit
participant was involved.

## Data changes

None. No migration, backfill, schema, lock or volume impact. Synthetic fixtures
from `src/features/admin/fixtures.ts` only (`SYN-*` style subjects and actors);
no real patient data, identifier or credential appears in code, tests or notes.

## Authorization boundaries and audit events

Unchanged. The view only renders the events supplied by its caller and filters
them through the existing tenant-scoped `historyFor` domain function. No role,
permission, scope check or audit event was added or changed.

## Migration and rollout considerations

None. Presentation-only change to one staff-web component; no contract,
migration, environment variable or rollout gate changed.

## Test evidence

Commands run (all exit 0 unless noted):

- `cd apps/staff-web && npm ci --no-audit --no-fund` → added 486 packages.
- `cd apps/staff-web && node_modules/.bin/vitest run tests/responsive/features-admin/StaffHistoryView.responsive.test.tsx src/features/admin/admin-components.test.tsx` → `Test Files 2 passed (2)`, `Tests 34 passed (34)`.
- `cd apps/staff-web && npm run typecheck` → `tsc --noEmit -p tsconfig.json`; no output, exit 0. **Typecheck final lines:** the command prints only the npm script header (`> aurevia-staff-web@0.1.0 typecheck` / `> tsc --noEmit -p tsconfig.json`) and exits 0 — no `error TS…` diagnostics.
- `cd apps/staff-web && npm run lint` → `eslint . --max-warnings 0`; no output, exit 0.
- `cd apps/staff-web && npm run test:coverage` → `Test Files 159 passed (159)`, `Tests 1899 passed (1899)`, `207 files fully covered`, `Coverage summary: Statements 100% (3734/3734), Branches 100% (1898/1898), Functions 100% (1263/1263), Lines 100% (3568/3568)`. The `useTheme must be used inside ThemeProvider` stack traces are the expected negative path of the pre-existing `tests/theme/ThemeProvider.test.tsx` and do not fail the run.
- `cd apps/staff-web && bash check.sh` → completed all stages (generated-client check, typecheck, lint, build, bundle-size budget, coverage) and ended with the same 100% coverage summary; exit 0.
- `cd apps/staff-web && npm run gen:api:check` (first stage of `check.sh`) → exit 0, no generated-client drift.
- `python3 -I scripts/check-docs.py` → exit 0.
- `python3 -I scripts/check-secrets.py`, `python3 -I scripts/check-names.py`, `python3 -I scripts/check-wiring.py` → each exit 0.
- `git merge --no-edit origin/main` → `Already up to date`; `git grep -nE '^(<<<<<<<|>>>>>>>) '` found no markers in files I touched (see Findings).
- `bash scripts/check.sh` → **exit 1**, stopped at its first gating stage: `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. This is the documented cloud-worker measurement artifact, not a change in this story: `git ls-files -v opencode.json` reports flag `S` (skip-worktree) and the worktree hash `50005c9c…` differs from `git show HEAD:opencode.json` hash `0dc2ac8e…`. The committed HEAD profile is authoritative; `opencode.json` is a protected file outside this task's scope and was not edited or regenerated. The remaining stages were run individually where relevant to this change (frontend `check.sh`, docs, secrets, names, wiring above), all green.

Committed locally to `swarm/RESP-STAFF-HISTORY-VIEW-884`; no push, tag or `gh` call. The final `git merge --no-edit origin/main` reported `Already up to date` and the working tree is clean.

Red/green trace: before implementation the new test file failed 5/8 tests
(`history section with data-breakpoint was not rendered`, `expected '' to contain 'card-list'`);
after implementation 34/34 targeted tests pass and the full suite is green.
Entry-point-to-test trace: `StaffPage`/`AdminScreen` render `StaffHistoryView`
→ the component reads `useBreakpoint()` and branches on `compact`
→ the new test renders the component directly inside `ThemeProvider` +
`I18nProvider` with a deterministic `matchMedia` stand-in and asserts the
resulting section, region, cards/table, roles, keyboard stops and RTL layout.

Evidence source: executed tool commands (`vitest`, `tsc`, `eslint`, `check.sh`),
file reads of the component, tests, theme utilities and design contract, plus
loaded specialist skills. No reviewer, simulated user or human participant
supplied runtime evidence beyond the requested read-only review.

## Coverage

Command: `cd apps/staff-web && npm run test:coverage` (also the final stage of
`bash apps/staff-web/check.sh`).
Result: 100% statements (3734/3734), branches (1898/1898), functions
(1263/1263) and lines (3568/3568); 207 files fully covered, 4 empty files
skipped; 1899 tests passed. `StaffHistoryView.tsx` is one of the fully covered
files — no missed line or branch.

New or changed branches in `src/features/admin/StaffHistoryView.tsx` and the
test that takes each side:

| Branch | Test covering it |
|---|---|
| `breakpoint === 'compact'` → cards | `renders labelled cards with the full column set on every phone width` (320/360/390); `mirrors the Arabic RTL card layout...` |
| `breakpoint === 'compact'` → wide table | `keeps a full table with no cards on every medium and expanded width` (768/820/1024/1280); existing `admin-components.test.tsx` cases (no `matchMedia` → hook fallback `expanded`) |
| matchMedia `change` listener updates band | `switches from cards to the table when the viewport crosses the compact edge` |
| `history.length === 0` true/false (card list) | `keeps the defined empty state inside the card list on a phone` / phone-width card test |
| `history.length === 0` true/false (wide table) | existing `shows an empty state for an unknown subject` / `shows append-only events and current roles for an active subject` |
| `event.kind === 'role_granted'` true/false (grantor and role values) | existing `shows the grantor only for role grants and renders effective UTC instants` (invited vs granted); RTL card test (deactivated) |
| `event.kind === 'deactivated'` true/false (reason) | existing `shows deactivation as its own event and clears current roles`; RTL card test |
| `index === 0 ? th scope=row : td` both sides | every render with events in both the table and card tests |
| `deactivated ? status badge : null` true/false | existing active vs deactivated `StaffHistoryView` cases |
| `roles.length > 0 ? roles : noRoles` true/false | existing active vs deactivated `StaffHistoryView` cases |

No threshold, exclusion or skip was added; no test was disabled or weakened.

## Findings

- No production defect found. The two pre-existing `overflowX`/`width` usages in
  `AdminTableRegion` remain unchanged because that component is outside this
  task's scope; they are direction-neutral and not a physical side rule.
- The new card rendering deliberately uses one `<table>` per event rather than
  a grid of `div`s, because `docs/decisions/0004-responsive-staff-web.md`
  permits a card treatment only when real table semantics and the full column
  set survive (decision line 29).
- Simulated UX check: the read-only history has no interactive control, so the
  44 px coarse-pointer and 16 px input rules are not exercised here; the shared
  `touch` and `field` utilities own them for views that have controls.
- Observed repo state (tool evidence, out of scope, not modified): committed
  `main` already contains unresolved conflict markers in
  `apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7ParseException.java`,
  `Hl7Parser.java` and `Hl7Segment.java` (lines 6/10, 14/18 and 10/14). My
  working tree contains only the two scoped source/test files and this note;
  the merge of `origin/main` reported `Already up to date`. These markers
  belong to another worker's domain and are left for the integrator.

## Lessons for workers

- `responsive.css` has no dedicated clinical-cell/value class. Following the
  precedent in `MixedScriptText` and `DataTable`, use logical inline styles
  (`minInlineSize: 0`, `overflowWrap: anywhere`, `whiteSpace: normal`) instead
  of editing the stylesheet; keep the band in `data-breakpoint` for layout
  assertions.
- `AdminTableRegion` hard-codes `style={{ overflowX: 'auto' }}` and exposes no
  `className`, so the `table-region` utility cannot be applied from a caller
  without editing an out-of-scope file. A future bounded task could add a
  `className` prop there; this story did not change it.
- jsdom cannot paint pixels: assert classes, roles, `tabIndex`, logical style
  strings and `data-breakpoint`, and leave pixel measurement to the Playwright
  responsive projects.

## Clinical and architecture review

Not required for this slice: no signed data, cross-organization sharing,
patient association, prescribing or critical result is changed. The view is
read-only and tenant-filtered by the existing `historyFor` domain function.
If a person later accepts WEB-10 evidence, the 200%/400% zoom and painted RTL
checks remain the real-browser launch gate; this story supplies simulated,
synthetic jsdom evidence only.
