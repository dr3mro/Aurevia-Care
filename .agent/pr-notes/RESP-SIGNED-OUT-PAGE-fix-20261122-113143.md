# RESP-SIGNED-OUT-PAGE — fix pass (staff web responsive `SignedOutPage`)

## Summary

Fix pass on branch `swarm/RESP-SIGNED-OUT-PAGE-973`. The implementation from the
first worker was already present and committed (`01bc3e5d` plus two
`origin/main` merges). I re-verified it end to end rather than starting over,
confirmed the red→green evidence independently, and produced this notes file at
the path named in the fix-pass prompt. No production or test file needed a new
edit: the scoped component and its new responsive test already satisfy the
story and pass every gate.

`SignedOutPage` wraps the shared `SystemNotice` in the existing `page` and
`stack` utilities from `responsive.css`, exposes the resolved band as
`data-breakpoint={useBreakpoint()}`, and uses only logical inline styles so the
same markup mirrors under right-to-left. Text, navigation (`navigate('/')`) and
the exported name are unchanged. The new test drives three synthetic
`matchMedia` bands plus an Arabic RTL case.

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

- `smartclinic-senior-react` for the component and view test; its definition of
  done (full `check.sh` incl. typecheck, typecheck final lines in notes) is met.
- `smartclinic-ux-reviewer` structural concerns are covered by the logical-
  property/RTL assertions; real-device, pixel and Arabic-clinician checks remain
  with the separate Playwright/launch stories.

Delegation: `smartclinic-delivery` and `smartclinic-senior-react` skills were
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

Red first, re-confirmed independently in this pass: original `SignedOutPage.tsx`
from `origin/main` copied over the component, then
`cd apps/staff-web && ./node_modules/.bin/vitest run tests/responsive/features-system/SignedOutPage.responsive.test.tsx --exclude 'e2e/**'`
— `Test Files 1 failed (1)`, `Tests 7 failed | 6 passed (13)` (missing `.page`
wrapper / `.stack`), proving the tests fail against the old markup. The fixed
component was then restored and `git status --short` was clean again.

Green, scoped:

- `cd apps/staff-web && ./node_modules/.bin/vitest run tests/responsive/features-system/SignedOutPage.responsive.test.tsx tests/features/system/systemPages.test.tsx --exclude 'e2e/**'`
  — `Test Files 2 passed (2)`, `Tests 42 passed (42)` (existing system-page
  tests still pass).

Mandatory typecheck (explicit), no `error TS…`:

```
> smartclinic-staff-web@0.1.0 typecheck
> tsc --noEmit -p tsconfig.json
```
exit 0.

Full module gate:

- `cd apps/staff-web && bash check.sh` — generated-client check, typecheck,
  lint, production build + WEB-08 gzip budget, and 100%-coverage unit tests.
  Final lines: `Test Files 159 passed (159)`, `Tests 1904 passed (1904)`,
  coverage `No files with missing coverage.` / `207 files fully covered.`,
  `EXIT: 0`. (jsdom traces for expected provider/navigation errors in negative
  tests appear in the output; the gate exited 0.)

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
| `import { useBreakpoint }` / `const breakpoint = useBreakpoint()` | "exposes the resolved breakpoint…" at compact, medium and expanded (asserts `data-breakpoint` equals the band) |
| outer `<div className="page" data-breakpoint style={logical…}>` | "exposes the resolved breakpoint…" (class, `display`, `placeItems`, logical props, no physical props, not hidden) at all three bands and in RTL |
| inner `<div className="stack" style={logical…}>` | "stacks the notice in DOM order…" (parent is the page wrapper, logical props, no `display: none`) at all three bands |
| unchanged `SystemNotice` props and `onAction={() => navigate('/')}` | "keeps the English heading, detail and single primary action with roles", "keeps the single action in keyboard order and navigates home" at all three bands, plus the Arabic RTL case |

No threshold, exclusion or test was weakened; no test skipped or disabled; no
`any` or `@ts-ignore` added.

## Clinical and architecture review

Not required: no clinical data, prescribing, release of information, critical
results, cross-organization sharing or patient association. Design/UX structure
was checked against `docs/design/responsive-layout.md` and
`rtl-and-localization.md`; this is simulated review, not real-device or
Arabic-clinician acceptance, which remains a separate launch gate.

## Findings

- The first worker's implementation was complete and green; the only gap this
  fix pass closes is the correctly named notes file and an independent
  re-verification. No source change was necessary.
- A local presentation wrapper was necessary because `SystemNotice` accepts no
  `className`/`style`, and is shared with other system pages whose responsive
  stories belong to other workers; editing it would exceed this task's scope.
- `responsive.css` has no dedicated centred-notice/state-panel class, so the
  story's prescribed fallback — logical inline styles — was used. Recorded as
  a lesson below rather than editing the shared stylesheet.
- Out of scope, observed while reconciling with `origin/main`: committed
  conflict markers remain in `apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7/`
  (`Hl7ParseException.java`, `Hl7Parser.java`, `Hl7Segment.java`). Not in this
  change and not edited; the integrator/owner should confirm or repair them.
- An advisory read-only `smartclinic-senior-react` review was requested; no
  actionable finding had been received at commit time. If the reviewer reports a
  defect, it is addressed in a follow-up tested commit and appended here.

## Lessons for workers

- For a system page that delegates to `SystemNotice`, wrap it in `page` + `stack`
  with `data-breakpoint={useBreakpoint()}`; the shared notice and `Button`
  (which owns `touch`, `data-breakpoint` and wrapping) do not need edits.
- jsdom normalises a zero logical size to `'0'` (not `'0px'`); assert
  `style.minInlineSize === '0'` when checking inline logical styles.
- No centred-notice/state-panel class exists in `responsive.css`: use logical
  inline styles and record the gap rather than editing the shared stylesheet.
- `min-inline-size: 0` plus `overflow-wrap: anywhere` on the `stack` child is
  what lets long English and Arabic strings wrap; pixel/zoom claims belong to
  the Playwright tasks, not to jsdom.
- In a cloud worker with no `node_modules`, `npm ci` in `apps/staff-web` is
  quick (a few seconds) and lets the full `check.sh` run locally; do not skip
  the module gate because dependencies look absent.
