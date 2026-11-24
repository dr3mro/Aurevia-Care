# RESP-SIGNED-OUT-PAGE — fix pass (staff web responsive `SignedOutPage`)

## Summary

Fix pass on branch `swarm/RESP-SIGNED-OUT-PAGE-973`. The implementation from the
first worker was already present and committed (`01bc3e5d` plus two
`origin/main` merges). I re-verified it end to end rather than starting over,
confirmed the red→green evidence independently, then applied three refinements
from a bounded read-only `aurevia-senior-react` review and re-verified the
full gate.

`SignedOutPage` wraps the shared `SystemNotice` in the existing `page` and
`stack` utilities from `responsive.css` and exposes the resolved band as
`data-breakpoint={useBreakpoint()}`. Layout comes entirely from those classes —
no inline declarations, so a future breakpoint rule cannot be shadowed and the
same markup mirrors under right-to-left. Text, navigation (`navigate('/')`) and
the exported name are unchanged. The new test drives three synthetic
`matchMedia` bands in English and in Arabic.

## Affected requirements

- WEB-07 — supported staff-web clients and compact layout from 768 px down; a
  data-free page is still acceptable for signed-out/unsupported pages.
- UX-01 — English and Arabic, RTL, WCAG 2.2 AA on staff web, flexible
  presentation styling.
- Design contract `docs/design/responsive-layout.md` (decision 0004) and
  `docs/design/rtl-and-localization.md`: the 320–1280 px bands, logical
  properties, 44 px coarse targets, safe-area insets and no `display: none`
  hiding of content.

## Risk class

Routine — a contained presentation change to one data-free system page plus a
new view test (`docs/WORKFLOW.md`). No data, authorization, tenant, migration,
contract or patient boundary is touched.

## Review owner

- `aurevia-senior-react` for the component and view test; its definition of
  done (full `check.sh` incl. typecheck, typecheck final lines in notes) is met.
- `aurevia-ux-reviewer` structural concerns are covered by the logical-
  property/RTL assertions; real-device, pixel and Arabic-clinician checks remain
  with the separate Playwright/launch stories.

Delegation: `aurevia-delivery` and `aurevia-senior-react` skills were
loaded and applied directly. A bounded read-only senior-react review was
requested as an advisory subagent; its outcome is recorded in Findings. All
checks recorded below are **tool evidence executed by this worker**; no human
participant or real clinical evidence was available or claimed.

## Data changes

None. No migration, backfill, lock, volume impact, rollback or forward-fix. No
reserved migration version was used.

## Authorization boundaries and audit events

None. The page holds no patient data, fetches nothing and adds no audit event.
The single sign-in action still calls `navigate('/')` exactly as before.

## Migration and rollout considerations

None. Presentation-only change to a static, data-free system page.

## Test evidence

All commands run from the repository root unless a `cd` is shown. Exact final
lines quoted.

Branch reconciliation (start of run):

- `git merge --no-edit origin/main` — `Already up to date.`
- Conflict-marker check is clean for both scoped files (`git grep -nE '^(<<<<<<<|>>>>>>>) '`).
  Pre-existing committed markers in unrelated
  `apps/api/.../exchange/hl7/*.java` were observed and left untouched (outside scope).

Dependency install (worker image had no `node_modules`):

- `cd apps/staff-web && npm ci --no-audit --no-fund` — `added 486 packages in 4s`.

Red first, re-confirmed independently in this pass after the reviewer-driven
refinement: original `SignedOutPage.tsx` from `origin/main` copied over the
component, then
`cd apps/staff-web && ./node_modules/.bin/vitest run tests/responsive/features-system/SignedOutPage.responsive.test.tsx --exclude 'e2e/**'`
— `Test Files 1 failed (1)`, `Tests 9 failed | 6 passed (15)` (missing `.page`
wrapper / `.stack`), proving the tests fail against the old markup. The fixed
component was then restored. (Before refinement the same check gave 7 failed /
6 passed of 13.)

Green, scoped:

- `cd apps/staff-web && ./node_modules/.bin/vitest run tests/responsive/features-system/SignedOutPage.responsive.test.tsx tests/features/system/systemPages.test.tsx --exclude 'e2e/**'`
  — `Test Files 2 passed (2)`, `Tests 44 passed (44)` (15 responsive cases,
  including Arabic at all three bands, plus the 29 existing system-page cases).

Mandatory typecheck (explicit), no `error TS…`:

```
> aurevia-staff-web@0.1.0 typecheck
> tsc --noEmit -p tsconfig.json
```
exit 0.

Full module gate:

- `cd apps/staff-web && bash check.sh` — generated-client check, typecheck,
  lint, production build + WEB-08 gzip budget, and 100%-coverage unit tests.
  Final lines: `Test Files 159 passed (159)`, `Tests 1906 passed (1906)`,
  coverage `No files with missing coverage.` / `207 files fully covered.`,
  `EXIT: 0`. A separate `npm run test:coverage` run confirmed the same counts.
  (jsdom traces for expected provider/navigation errors in negative tests appear
  in the output; the gate exited 0.)

Entry point → test trace: route `/signed-out` renders `SignedOutPage`
(`src/features/system/routes.ts`) → `useBreakpoint()` resolves the band →
`.page`/`.stack` wrapper with `data-breakpoint` → `SystemNotice`'s `h1`, `p`
and `Button` → asserted by
`tests/responsive/features-system/SignedOutPage.responsive.test.tsx` at
compact, medium and expanded and in Arabic RTL, and by the pre-existing
`tests/features/system/systemPages.test.tsx` for labels, roles and navigation.

Repository-level `scripts/check.sh` was not re-run in this pass: no script, API,
mobile or workflow file changed, and the integrator/Delivery own the full
repository gate. The first worker observed the documented cloud-worker false
positive there (`check-pack: opencode.json is stale`, OPS-CW-003-01, CI profile
under `skip-worktree`) and ran the remaining stages individually green; no
protected file was edited.

## Coverage

Command and result: `cd apps/staff-web && bash check.sh` → Vitest v8 coverage
with per-file 100% thresholds —
`Statements 100% (3722/3722)`, `Branches 100% (1892/1892)`,
`Functions 100% (1258/1258)`, `Lines 100% (3556/3556)`,
`No files with missing coverage.`, `207 files fully covered.`

`SignedOutPage.tsx` has **no branches** of its own (no `if`, ternary, `&&`,
`||`, `switch` or `catch`), so there is no uncovered branch to enumerate. Every
new line is exercised by the new test file:

| New line | Test that covers it |
|---|---|
| `import { useBreakpoint }` / `const breakpoint = useBreakpoint()` | "delegates layout to the page class…" at compact, medium and expanded, and the Arabic RTL case at each band (asserts `data-breakpoint` equals the band) |
| outer `<div className="page" data-breakpoint={breakpoint}>` | "delegates layout to the page class…" (class membership, not hidden, no inline `style` attribute) at all three bands and in RTL |
| inner `<div className="stack">` | "stacks the notice in DOM order…" (parent is the page wrapper, no inline `style` attribute) at all three bands |
| unchanged `SystemNotice` props and `onAction={() => navigate('/')}` | "keeps the English heading, detail and single primary action with roles", "keeps the single action in keyboard order and navigates home" at all three bands, plus the Arabic RTL case at each band |

The 44 px coarse-pointer floor and the iOS-safe 16 px field size are asserted by
the pre-existing `tests/theme/responsiveStylesheet.test.ts` ("sizes
coarse-pointer targets at 44px and keeps the iOS-safe field font"), which runs
in the same full gate; this page's button carries the `touch` class and the
stylesheet supplies the value. No inline size is added at this layer.

No threshold, exclusion or test was weakened; no test skipped or disabled; no
`any` or `@ts-ignore` added.

## Clinical and architecture review

Not required: no clinical data, prescribing, release of information, critical
results, cross-organization sharing or patient association. Design/UX structure
was checked against `docs/design/responsive-layout.md` and
`rtl-and-localization.md`; this is simulated review, not real-device or
Arabic-clinician acceptance, which remains a separate launch gate.

## Findings

- The first worker's implementation was complete and green. This fix pass added
  the correctly named notes file, an independent re-verification, and three
  refinements requested by the advisory read-only `aurevia-senior-react`
  review (all applied and re-verified):
  1. **Medium** — the page/stack wrappers carried redundant inline styles that
     also introduced unrequested visual rules (`placeItems: center`,
     `textAlign: start`) and would outrank future `responsive.css` rules.
     Removed all inline declarations from the component; layout now comes from
     the shared classes only, matching the `ApiPage` convention. The tests now
     assert class membership, `data-breakpoint` and the absence of an inline
     `style` attribute instead of inline CSS values.
  2. **Low** — the test fixture's `coarse`/`portrait` states were dead: this page
     calls only `useBreakpoint`. Removed them; the 44 px coarse rule is asserted
     by the existing `tests/theme/responsiveStylesheet.test.ts`, which also runs
     in the full gate.
  3. **Low** — Arabic RTL was exercised only at the compact band. Parameterised
     the RTL block over compact, medium and expanded (the responsive test file
     now has 15 cases, up from 13).
- A local presentation wrapper is still necessary because `SystemNotice`
  accepts no `className`/`style` and is shared with other system pages whose
  responsive stories belong to other workers; editing it would exceed scope.
- Out of scope, observed while reconciling with `origin/main`: committed
  conflict markers remain in `apps/api/src/main/java/com/aurevia/exchange/hl7/`
  (`Hl7ParseException.java`, `Hl7Parser.java`, `Hl7Segment.java`). Not in this
  change and not edited; the integrator/owner should confirm or repair them.
- Reviewer evidence source: read-only subagent report from `aurevia-senior-react`
  (no files edited by the reviewer). All command results above are tool evidence
  from this worker; no human participant or simulated-user evidence was claimed.

## Lessons for workers

- For a system page that delegates to `SystemNotice`, wrap it in `page` + `stack`
  with `data-breakpoint={useBreakpoint()}` and **no inline styles**; the shared
  classes own layout, safe area and wrapping, and `Button` owns `touch`,
  `data-breakpoint` and label wrapping. Inline declarations outrank any future
  breakpoint rule in `responsive.css`.
- Do not treat a fixture's `coarse`/`portrait` states as coverage when the
  component does not call `usePointerCoarse`/`useOrientation`; the 44 px coarse
  floor and 16 px field size belong to `responsiveStylesheet.test.ts`.
- Assert absence of an inline `style` attribute on wrapper elements rather than
  asserting individual CSS values: it is a stronger regression guard and avoids
  coupling the view test to presentation details owned by the stylesheet.
- Pixel, real-device and Arabic-clinician claims belong to the Playwright/launch
  tasks, not to jsdom.
- In a cloud worker with no `node_modules`, `npm ci` in `apps/staff-web` is
  quick (a few seconds) and lets the full `check.sh` run locally; do not skip
  the module gate because dependencies look absent.
