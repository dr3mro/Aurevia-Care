# RESP-SIGNED-OUT-PAGE — Staff web responsive `SignedOutPage`

## Summary

Made the fixed signed-out page responsive at every supported width without
changing its text, navigation behaviour or exported name. `SignedOutPage` now
uses the existing `page` and `stack` utilities from `responsive.css` and the
`useBreakpoint` hook, plus logical inline styles for the centred notice panel
(no dedicated panel class exists). The active layout band is exposed as
`data-breakpoint` so jsdom structure tests can assert it. Added a new responsive
test file that drives compact, medium and expanded synthetic media states and
an Arabic RTL case. No new CSS, dependency, tracker, workflow or script change.

## Affected requirements

- WEB-07 — responsive staff web from 320 px upward, no data-free page.
- UX-01 — logical properties, wrapping, touch targets, RTL, no hidden clinical
  content (design contract `docs/design/responsive-layout.md`, decision 0004).

## Risk class

Routine — contained presentation change to one staff-web page plus a new test
(`docs/WORKFLOW.md`). No data, authorization, migration or contract boundary.

## Review owner

- Senior React and Vite specialist (`orevia-senior-react`) for the component
  and view test.
- UX reviewer (`orevia-ux-reviewer`) for the logical-property/RTL structure.

Specialist routing: the `orevia-delivery`, `orevia-senior-react` and
`orevia-test-engineer` SKILL.md instructions were loaded and applied
(routine, contained change). No separate delegated subagent review was awaited
or claimed; the senior-react definition of done (full `check.sh`, typecheck
lines recorded, no `any`/`@ts-ignore`) was satisfied directly.

All checks below are **tool evidence** executed by this worker. No reviewer,
simulated-user or human-participant evidence was available or claimed for this
slice; pixel, real-device and Arabic-clinician verification remains with the
separate Playwright/launch tasks in the design matrix.

## Data changes

None. No migration, backfill, lock, volume impact or rollback/forward-fix.

## Authorization boundaries and audit events

None. The page holds no patient data, fetches nothing and adds no audit event.
The sign-in action still navigates to `/` exactly as before.

## Migration and rollout considerations

None. Presentation-only change to a static system page.

## Test evidence

Red first (before the component change), scoped run:

- `cd apps/staff-web && ./node_modules/.bin/vitest run tests/responsive/features-system/SignedOutPage.responsive.test.tsx --exclude 'e2e/**'`
  — `Test Files 1 failed (1)`, `Tests 7 failed | 6 passed (13)`; the seven
  failures were `signed-out page has no responsive page wrapper` / missing
  `.stack`, proving the new tests can fail against the old markup.

Green after the change, scoped runs:

- `cd apps/staff-web && ./node_modules/.bin/vitest run tests/responsive/features-system/SignedOutPage.responsive.test.tsx tests/features/system/systemPages.test.tsx --exclude 'e2e/**'`
  — `Test Files 2 passed (2)`, `Tests 42 passed (42)` (existing system-page
  tests still pass).
- `bash apps/staff-web/check.sh` (generated-client check, typecheck, lint, build,
  bundle budget, coverage) — `Test Files 159 passed (159)`,
  `Tests 1904 passed (1904)`.

Final lines of the mandatory typecheck (run explicitly to capture them):

```
> orevia-staff-web@0.1.0 typecheck
> tsc --noEmit -p tsconfig.json
typecheck exit: 0
```

No `error TS…` occurred. Other gate stages, captured explicitly:

- `npm run lint` — `> eslint . --max-warnings 0` — exit 0.
- `npm run gen:api:check` — `gen:api: generated client matches the contract` — exit 0.
- `bash apps/staff-web/check.sh` coverage summary (final lines):
  `No files with missing coverage.` / `207 files fully covered.` /
  `Statements 100% (3722/3722)`, `Branches 100% (1892/1892)`,
  `Functions 100% (1258/1258)`, `Lines 100% (3556/3556)`.

(React/jsdom console traces for expected provider/navigation errors in negative
tests appear in the full output; all 1904 tests passed and the gate exited 0.)

Repository-level check: `timeout 300 bash scripts/check.sh` stopped at the
documented cloud-worker false positive — final line
`check-pack: opencode.json is stale: run scripts/gen-opencode.py`, exit 1.
Confirmed once, without editing any protected file: `git ls-files -v opencode.json`
→ `S opencode.json` (skip-worktree, CI profile installed by `agent-run.sh`) and
`git show HEAD:opencode.json` differs from the worktree file. Per AGENTS.md this
is OPS-CW-003-01, not a change defect. Remaining check.sh stages were then run
individually and all exited 0: `check-docs.py`, `check-secrets.py`,
`check-names.py`, `check-wiring.py`, `check-migrations.py --require-baseline`,
`bash scripts/frontend-check.sh` (`frontend standards: policy and tool commands
passed`). `apps/staff-web/check.sh` (the relevant module gate) passed in full;
`python-coverage.sh` and the unrelated API/mobile module gates were not re-run
because no script, API or mobile file changed and the integrator/Delivery own
the full-repository gate.

Branch reconciliation: `git merge --no-edit origin/main` run at the start and
after implementation — both "Already up to date". Conflict-marker check on my
scoped files: clean (pre-existing committed markers in unrelated `apps/api/.../hl7`
Java files were observed but are outside this task's scope and were not edited).

Entry point → test trace: route `/signed-out` renders `SignedOutPage`
(`src/features/system/routes.ts`) → `useBreakpoint` hook resolves the band →
`.page`/`.stack` wrapper with `data-breakpoint` → `SystemNotice`'s `h1`, `p` and
`Button` → asserted by `tests/responsive/features-system/SignedOutPage.responsive.test.tsx`
at each band and in Arabic RTL, and by the existing
`tests/features/system/systemPages.test.tsx` for labels/roles/navigation.

## Coverage

Command and result: `bash apps/staff-web/check.sh` → Vitest v8 coverage
per-file thresholds 100% → `No files with missing coverage.`, 207 files fully
covered, 100% statements/branches/functions/lines (see exact numbers above).

`SignedOutPage.tsx` has **no branches** of its own (no `if`, ternary, `&&`,
`||`, `switch` or `catch`), so there is no uncovered branch to enumerate. Every
new line is exercised by the new test file:

| New line | Test that covers it |
|---|---|
| `import { useBreakpoint }` and `const breakpoint = useBreakpoint()` | "exposes the resolved breakpoint…" at compact, medium and expanded (asserts `data-breakpoint` equals each band) |
| outer `<div className="page" data-breakpoint style={logical…}>` | "exposes the resolved breakpoint…" (class, display/place-items, logical props, no physical props, not hidden) at all three bands and in RTL |
| inner `<div className="stack" style={logical…}>` | "stacks the notice in DOM order…" (parent is the page wrapper, logical props, no `display: none`) at all three bands |
| unchanged `SystemNotice` props and `onAction={() => navigate('/')}` | "keeps the English heading, detail and single primary action with roles", "keeps the single action in keyboard order and navigates home" at all three bands, plus the Arabic RTL case |

No threshold, exclusion or test was weakened; no test skipped or disabled; no
`any`/`@ts-ignore` added.

## Clinical and architecture review

Not required: no clinical data, prescribing, release of information, critical
results, cross-organization sharing or patient association. Design/UX structure
was checked against `docs/design/responsive-layout.md` and `rtl-and-localization.md`
as a simulated review; real RTL and device verification remains a separate gate.

## Findings

- A presentation-only wrapper was necessary because `SystemNotice` accepts no
  `className`/`style`; it is shared with other system pages whose responsive
  stories are owned by other workers, so this story wraps it locally instead of
  editing the shared component (outside scope).
- `responsive.css` has no dedicated class for a centred notice/state panel;
  logical inline styles were used as the story directs.
- Out of scope, observed while reconciling with `origin/main`: committed
  conflict markers remain in `apps/api/src/main/java/com/aurevia/exchange/hl7/`
  (`Hl7ParseException.java`, `Hl7Parser.java`, `Hl7Segment.java`, from
  `origin/swarm/BUILD-37-469`). They are not in this change and were not edited;
  the integrator/owner should confirm they are intentional or repair them.

## Lessons for workers

- For a system page that delegates to `SystemNotice`, wrap it in `page` + `stack`
  with `data-breakpoint={useBreakpoint()}`; the shared notice and the `Button`
  (which owns `touch`, `data-breakpoint` and wrapping itself) do not need edits.
- jsdom normalises a zero logical size to `'0'` (not `'0px'`); assert
  `style.minInlineSize === '0'` when checking inline logical styles.
- No centered-notice/state-panel class exists in `responsive.css`; use logical
  inline styles and record the gap rather than editing the shared stylesheet.
- `min-inline-size: 0` plus `overflow-wrap: anywhere` on the `stack` child is
  what lets long English and Arabic strings wrap; pixel/zoom claims belong to
  the Playwright tasks, not to jsdom.
