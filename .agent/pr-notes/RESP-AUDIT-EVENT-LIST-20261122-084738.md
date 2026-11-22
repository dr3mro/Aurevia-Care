# RESP-AUDIT-EVENT-LIST — responsive `AuditEventList` (features-admin)

## Summary

`AuditEventList` now renders a stacked, labelled card list on phone-width viewports and keeps the existing full table at medium and expanded widths. All nine audit fields (sequence, actor, purpose, scope, correlation ID, outcome, action, resource and occurrence time) remain visible in the cards; no field is hidden with `display: none`. The change uses the existing `useBreakpoint` hook and the existing `card-list` class from `responsive.css` plus logical inline styles. No behaviour, text, API call or exported name changed. Synthetic fixtures only.

## Affected requirements

- WEB-07 — supported client widths: the component now reflows from a table to cards at compact widths (the 320 px rule of decision 0004 supersedes the older 768 px wording in the spec table; see `docs/design/responsive-layout.md` section 2).
- UX-01 — English/Arabic, RTL, mixed-script text, wrapping long values, keyboard-reachable UTC instant, WCAG 2.2 AA reflow/text scaling (1.4.4, 1.4.10).

Acceptance mapping:
| Requirement / story step | Evidence in this change |
|---|---|
| Cards on phones with actor, action, resource and time visible | `ol.card-list` + `li` + `dl/dt/dd` card fields; test "renders event cards with every field, stacked, on compact phones" |
| No horizontal page scroll / no hidden clinical column | no `display: none` in inline styles; `min-inline-size: 0`, `overflow-wrap: anywhere`, `white-space: normal` on values; empty state is a visible card, not a hidden row |
| Columns stack on phones / long English and Arabic wrap | `dl` is a one-column grid; long English and Arabic fixtures asserted to wrap; test "keeps Arabic values, logical styling and labelled cards under right-to-left" |
| Coarse pointers 44×44, 16 px inputs, safe-area insets | unchanged shared `responsive.css` (`.touch`, `.field`, `--safe-*`) applies to this component; no new control or input was added, so no new target or font-size rule is needed |
| CSS logical properties only | inline styles use `padding-inline-start`, `margin`, `gap`, `min-inline-size`, `overflow-wrap`; asserted in the RTL test and by the pre-existing source-level check in `admin-components.test.tsx` |
| Keyboard order and focus | time elements remain the only tab stops in cards (region + times in the table); test "keeps only the time elements as tab stops in keyboard order on compact" and "reveals the UTC instant on focus inside a compact card" |
| jsdom asserts structure, not pixels | class/role/attribute/style assertions only; pixel checks remain with the Playwright tasks |

## Risk class

Routine (contained staff-web view change). One implementation specialist and one targeted reviewer per `docs/WORKFLOW.md`.

## Review owner

Senior React and Vite specialist; UX/accessibility reviewer for RTL and reflow.

## Data changes

None. No migration, backfill, table, lock or volume impact; no rollback or forward-fix.

## Authorization boundaries and audit events

None. The component only displays tenant-scoped audit events passed in through props; no tenant, role or patient-link scope changed and no audit event was added or altered.

## Migration and rollout considerations

None. Presentation-only change; it does not alter authorization, data or workflow.

## Test evidence

Tool evidence (commands run in this worker):

- `npx vitest run --exclude 'e2e/**' tests/responsive/features-admin/AuditEventList.responsive.test.tsx` → `Test Files 1 passed (1)`, `Tests 8 passed (8)` (this file failed first with 6 card-structure failures before the implementation, recorded as the red stage).
- `npx vitest run --exclude 'e2e/**' src/features/admin/admin-components.test.tsx` → `Test Files 1 passed (1)`, `Tests 26 passed (26)` (existing tests, no regression).
- `npm run typecheck` (`tsc --noEmit -p tsconfig.json`) → exit 0. Typecheck final lines: the command printed only the script banners and `> tsc --noEmit -p tsconfig.json`, then exited 0 with no diagnostics (`error TS…` count 0).
- `bash apps/staff-web/check.sh` → exit 0. Final lines:
  - `Test Files  159 passed (159)`
  - `Tests  1899 passed (1899)`
  - `No files with missing coverage.`
  - `207 files fully covered.`
  - `Statements   : 100% ( 3727/3727 )`
  - `Branches     : 100% ( 1898/1898 )`
  - `Functions    : 100% ( 1262/1262 )`
  - `Lines        : 100% ( 3561/3561 )`
  - (Console noise from the negative `useTheme` test in `ThemeProvider.test.tsx` is expected and is not a failure.)
- `bash scripts/frontend-check.sh` → exit 0, final line `frontend standards: policy and tool commands passed`.
- `npx prettier --check src/features/admin/AuditEventList.tsx tests/responsive/features-admin/AuditEventList.responsive.test.tsx` → final line `All matched files use Prettier code style!`

Entry point to test trace: `AuditEventList` is rendered by `AdminScreen` and `AuditPage` (both import it); the responsive test mounts the component through `ThemeProvider` and `I18nProvider` with a stubbed `matchMedia`, then asserts the compact `ol.card-list` and the medium/expanded `AdminTableRegion` table.

The repo-wide `scripts/check.sh` was deliberately not run: it invokes `apps/api/check.sh` (full Maven verify/OWASP/integration tests), which the worker instructions forbid for a worker time budget. The module gate, repo frontend policy check and Prettier were run instead.

## Coverage

Command and result: `bash apps/staff-web/check.sh` → per-file V8 coverage gate passed, 100% on all four metrics for all 207 hand-written source files. Specifically for `src/features/admin/AuditEventList.tsx` (from `coverage/coverage-summary.json`): lines 21/21, branches 36/36, functions 9/9, statements 22/22 — all 100%.

Every new or changed branch and the test that takes it:

| Branch | Side | Test |
|---|---|---|
| `breakpoint === 'compact' ? cards : table` (component) | compact | "renders event cards with every field, stacked, on compact phones"; "keeps only the time elements as tab stops in keyboard order on compact"; "shows the empty state inside the compact card list without hiding data"; "reveals the UTC instant on focus inside a compact card"; "keeps Arabic values, logical styling and labelled cards under right-to-left" |
| same | medium | "keeps the full, unhidden table on medium" (`it.each` case) |
| same | expanded | "keeps the full, unhidden table on expanded" (`it.each` case) |
| compact `chain.length === 0 ? noEventsCard : map` | empty | "shows the empty state inside the compact card list without hiding data" |
| same | non-empty | "renders event cards with every field, stacked, on compact phones" |
| `event.outcome === 'SUCCESS' ? 'success' : 'denied'` in `cardFields` | success | event 1 of "renders event cards with every field, stacked, on compact phones" |
| same | denied | event 2 of the same test |
| `occurredAt === '' ? unknown : formatInstant` (UTC + visible) and `dateTime={…=== '' ? undefined : …}` | real instant | both events of "renders event cards with every field, stacked, on compact phones" |
| same | empty instant | event 2 of "renders event cards with every field, stacked, on compact phones" and "keeps only the time elements as tab stops in keyboard order on compact" |
| `focused ? … : …` (`aria-describedby` and UTC reveal span) | focused | "reveals the UTC instant on focus inside a compact card" (also existing `admin-components.test.tsx` on the table path) |
| same | unfocused | every other render of the component |

Pre-existing branches (`showChain`, chain-empty, `verified`, broken-chain hint, table empty state) remain covered by `src/features/admin/admin-components.test.tsx` (26 tests green). No threshold, exclusion or skip was changed and no test was disabled.

## Clinical and architecture review

Not required. Presentation-only change to a display component; no signed data, cross-organization sharing, patient association, prescribing or critical result is added or changed. Simulated React/UX review findings, if any, are recorded under Findings.

## Findings

- **Simulated UX/RTL review (smartclinic-ux-reviewer, no blockers to layout acceptance):** confirmed all nine fields visible in compact cards, no `display: none`, wrapping values, logical properties only, correct document-driven RTL, reachable UTC instant and no new CSS/text/export changes. It raised two non-blocking findings, both pre-existing and outside this story's "do not change behaviour, text, API calls or exported names" constraint:
  1. **MEDIUM, UX-01** — `MixedScriptText` call sites in `AuditEventList` pass `lang="en"` even for Arabic values, so an Arabic run is announced with an English voice hint (`rtl-and-localization.md` §§1, 7). The card path preserves the existing table behaviour exactly; the responsive test now labels this explicitly as a known deviation rather than as correct behaviour. Needs an owner decision and a separate story (requested below).
  2. **LOW** — `aria-describedby`/`id` use `audit-utc-${event.sequence}` only, so two list instances with overlapping sequences can produce duplicate DOM ids. This is inherited unchanged from the existing table path; not modified here to avoid an out-of-scope behaviour change.
- **Simulated Senior React review (smartclinic-senior-react, no blockers):** confirmed React semantics, `<dl>/<dt>/<dd>` grouping, table `<caption>`/`scope`, reachable UTC reveal, all nine fields visible, full branch coverage and unchanged exports. It raised three LOW findings, handled as follows:
  1. **FIXED in scope** — the table used physical `width: '100%'`; changed to `inlineSize: '100%'` to honour the logical-properties-only contract. The RTL test's style guard was strengthened with `/(?:^|;)\s*(?:width|height|overflow-[xy])\s*:/` so physical sizing/overflow axes are now detected.
  2. **FIXED in scope** — `list-style: none` on the `<ol>` can drop implicit list semantics in Safari/VoiceOver; added an explicit `role="list"` while keeping the accessible name. The `getByRole('list')` assertions still pass.
  3. Same `lang="en"` issue as the UX MEDIUM finding above; deliberately not changed and recorded as a known deviation with a follow-up request.
  It also noted that `AdminTableRegion.tsx:18` uses physical `overflowX: 'auto'` instead of the existing `.table-region` class; out of scope here and requested as a separate task below.
- **Environment/baseline observation (tool evidence):** the checked-out branch HEAD `06a0fa82` already contains committed conflict markers in unrelated Java files (`apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7/Hl7ParseException.java`, `Hl7Parser.java`, `Hl7Segment.java`, from `origin/swarm/BUILD-37-469`). `git merge --no-edit origin/main` was clean ("Already up to date") and these files are outside this story's scope, so they were left untouched for the integrator.

## Lessons for workers

- In this component `getByRole('region', { name })` is ambiguous: the `<section aria-label>` and the table wrapper both expose role `region` when the section has an accessible name. Query the explicit wrapper with `document.querySelector('[role="region"]')` instead.
- `useBreakpoint` falls back to `expanded` when `matchMedia` is absent, so adding the hook to an existing component keeps all existing tests on the table path; a new responsive test must stub both breakpoint queries to reach the compact branch.
- `MixedScriptText` already calls `useBreakpoint` internally (it emits `data-breakpoint`), so a compact render shows `data-breakpoint="compact"` on every value without extra wiring.
- `responsive.css` has `card-list` for the card container but no companion class for a stacked label/value definition grid or for removing `<ol>` markers. Per the story rule, no stylesheet edit was made: logical inline styles (`display: grid`, `gap`, `min-inline-size: 0`, `overflow-wrap: anywhere`, `white-space: normal`, `margin: 0`, `padding-inline-start: 0`, `list-style: none`) were used instead.
- When a list uses `list-style: none`, add an explicit `role="list"` so WebKit/VoiceOver keeps the list semantics that jsdom reports implicitly.
- A source-level RTL regex that only checks `margin-/padding-/border-(left|right)` and `text-align` misses physical `width`, `height` and `overflow-x/y`; extend it or use logical `inline-size`/`block-size`/`overflow` from the start.

```task-requests
[
  {
    "title": "Use script-correct lang hints in staff-web mixed-script runs",
    "details": "Audit and other admin values rendered through MixedScriptText pass lang=\"en\" even when the value is Arabic, which gives screen readers the wrong voice hint (rtl-and-localization.md sections 1 and 7). Derive the run language from the active locale/script at the call sites or inside MixedScriptText, keep dir=\"auto\" isolation, and update English, Arabic and mixed-script tests. Raise the duplicate audit-utc id (use the organization scope in the id) in the same change if it can be covered deterministically.",
    "scope": ["apps/staff-web/src/components/MixedScriptText.tsx", "apps/staff-web/src/features/admin/AuditEventList.tsx", "apps/staff-web/tests/components/MixedScriptText.test.tsx", "apps/staff-web/tests/responsive/features-admin/AuditEventList.responsive.test.tsx"],
    "depends": []
  },
  {
    "title": "Use logical overflow in the admin table region",
    "details": "apps/staff-web/src/features/admin/AdminTableRegion.tsx:18 sets a physical inline overflowX: 'auto' on the labelled table scroll region. responsive.css:78 already provides a .table-region class with direction-neutral overflow: auto, a focus ring and scroll padding. Reuse that class (or inline overflow) so the region honours the logical-properties-only contract, and update any affected tests. Coordinate with other screens that share AdminTableRegion.",
    "scope": ["apps/staff-web/src/features/admin/AdminTableRegion.tsx", "apps/staff-web/tests"],
    "depends": []
  }
]
```
