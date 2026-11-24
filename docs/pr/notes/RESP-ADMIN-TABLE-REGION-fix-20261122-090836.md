# RESP-ADMIN-TABLE-REGION — fix pass (verification and clean hand-off)

Fix attempt 1 of 3, continuing from the earlier worker's committed work on branch
`swarm/RESP-ADMIN-TABLE-REGION-861` (implementation commit `fb99340a`, two
`origin/main` merges). The earlier notes are in
`docs/pr/notes/RESP-ADMIN-TABLE-REGION-20261122-084243.md`. This pass made **no
new production code changes**: it re-verified the scoped change, reconciled the
branch, ran the mandatory frontend gate and recorded clean evidence. The scoped
story was already implemented test-first by the earlier worker (red stage:
5 failed assertions before the component change), using synthetic data only.

## Summary

`AdminTableRegion` renders as a labelled, keyboard-focusable `div.table-region`
with `role="region"`, `aria-label`, `tabIndex={0}` and `data-breakpoint={useBreakpoint()}`,
preserving the original `overflowX: 'auto'` inline style. The shared `.table-region`
rule supplies direction-neutral scrolling and a logical focus ring at every band;
no new CSS, dependency or behaviour was introduced. A new responsive test drives the
real breakpoint hook with a deterministic `matchMedia` stand-in at compact, medium
and expanded, and asserts structure, labels, roles, keyboard order and RTL with
synthetic English and Arabic data. Changed files remain inside the task scope:
`apps/staff-web/src/features/admin/AdminTableRegion.tsx` and
`apps/staff-web/tests/responsive/features-admin/AdminTableRegion.responsive.test.tsx`.

## Affected requirements

- **WEB-07** — compact staff layout down to 320 px. The wrapper reuses the existing
  `.table-region` rule from `apps/staff-web/src/theme/responsive.css` (direction-neutral
  `overflow: auto`, logical focus ring and scroll padding) instead of adding CSS. The
  mocked media-query bands cover compact (0–767), medium (768–1279) and expanded
  (1280+) from `src/theme/breakpoints.ts`.
- **UX-01** — English and Arabic, RTL, WCAG 2.2 AA. The RTL case sets `dir="rtl"`,
  `lang="ar"`, an Arabic accessible name and long Arabic cells/action, and asserts no
  physical side, no direction override, no hidden column and preserved keyboard order.
  Long English and Arabic values remain present and unhidden at every band.

Acceptance mapping (task step 2): no horizontal page scroll → scrolling is confined to
the labelled region; long EN/AR text wraps and 200% zoom stays readable → no fixed
block size or physical side added (pixel/zoom proof belongs to the Playwright tasks,
design guide §13); columns stack on phones → for a table the documented pattern is one
labelled scroll region with every column present, not stacking (design guide §5);
coarse pointers 44×44 → the row action keeps its `.touch` class (asserted); inputs 16 px
→ `.field` owns that rule and this wrapper contains no input; safe-area insets →
`.page`/`.sheet` own them and this wrapper adds no edge padding; logical properties only
→ asserted; no clinical column hidden with `display: none` → asserted for every header
and cell; table follows the DataTable pattern → same `.table-region` class, structure,
roles and unhidden captioned table.

## Risk class

Routine — contained UI presentation change in one staff-web component plus a new test.
No API, data, authorization or deployment boundary changed (`docs/WORKFLOW.md`).

## Review owner

`aurevia-senior-react` and `aurevia-ux-reviewer` — bounded simulated subagent
reviews were recorded by the earlier worker and found no blocking defect. I did not
re-run them in this pass and edited no reviewer code. No human, clinician or
participant review was available or claimed.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix. All
fixtures are synthetic (`SYN-0001`, synthetic facility name, synthetic Arabic strings);
no patient data or credential was read or written.

## Authorization boundaries and audit events

None changed. The wrapper only presents data: it adds no command, role check, tenant
scope or audit event and does not alter any API call.

## Migration and rollout considerations

None. Presentation-only change; no contract, generated client, build script or
stylesheet change. `gen:api:check` passed unchanged.

## Test evidence

Evidence source: **executed tool commands** (my local shell, Vitest and the mandatory
`apps/staff-web/check.sh`) plus **reviewer evidence from simulated subagents** recorded
in the earlier notes. No simulated-user, clinician or human-participant evidence.

- Branch reconciliation, `git merge --no-edit origin/main` → `Already up to date.`; exit 0.
  No new conflict markers resulted from the merge.
- Targeted regression,
  `cd apps/staff-web && npx vitest run --exclude 'e2e/**' tests/responsive/features-admin/AdminTableRegion.responsive.test.tsx src/features/admin/admin-screen.test.tsx`
  → `Test Files 2 passed (2); Tests 19 passed (19)`.
- Mandatory frontend definition of done (exit 0),
  `bash apps/staff-web/check.sh` — typecheck final lines (no `error TS…` anywhere;
  `grep -c 'error TS'` returned 0):

```
> aurevia-staff-web@0.1.0 gen:api:check
> bash scripts/gen-api-types.sh --check

✨ openapi-typescript 7.4.2
gen:api: generated client matches the contract

> aurevia-staff-web@0.1.0 typecheck
> tsc --noEmit -p tsconfig.json

> aurevia-staff-web@0.1.0 lint
> eslint . --max-warnings 0

> aurevia-staff-web@0.1.0 build
> tsc --noEmit -p tsconfig.json && vite build
✓ built in 158ms
```

  Coverage/test summary from the same command:

```
 Test Files  159 passed (159)
      Tests  1902 passed (1902)
No files with missing coverage.
207 files fully covered.
4 empty files skipped.

=============================== Coverage summary ===============================
Statements   : 100% ( 3722/3722 )
Branches     : 100% ( 1892/1892 )
Functions    : 100% ( 1258/1258 )
Lines        : 100% ( 3556/3556 )
================================================================================
```

- Repository check, `bash scripts/check.sh` → exit 1 at
  `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. This is the known
  cloud-worker environment artifact, not a change defect. Independently verified with
  read-only tool commands: `git ls-files -v opencode.json` → `S opencode.json`
  (skip-worktree); worktree file `cmp`-identical to `.github/opencode.ci.json` (CI
  profile) and different from `git show HEAD:opencode.json` (the committed local
  profile); the two profiles differ. No protected file was generated or edited. See
  "Why this is not finished" below.

Entry-point-to-test trace: a staff admin page renders `<AdminTableRegion label>` around
a captioned table (`src/features/admin/{FacilitiesPage,StaffPage,ConfigPage,AuditEventList,ConfigEditor,StaffHistoryView}.tsx`)
→ `AdminTableRegion` calls `useBreakpoint()` (media queries from `src/theme/breakpoints.ts`)
and renders `div.table-region[role=region][aria-label][tabindex=0][data-breakpoint]`
→ `tests/responsive/features-admin/AdminTableRegion.responsive.test.tsx` queries the
region by role/name and asserts class, band, focus, table semantics and RTL structure;
`src/features/admin/admin-screen.test.tsx` proves the unchanged `overflowX: 'auto'`
wrapper still satisfies the existing admin-screen contract.

## Coverage

Command: `bash apps/staff-web/check.sh` → `npm run test:coverage`
(`vitest run --coverage --exclude 'e2e/**'`, v8, `perFile: true`, thresholds unmodified).
Result: **100% on every touched file and the whole module** — `No files with missing
coverage.`; `207 files fully covered`; Statements 100% (3722/3722), Branches 100%
(1892/1892), Functions 100% (1258/1258), Lines 100% (3556/3556).

New branches introduced in `AdminTableRegion.tsx`: **none** in this fix pass (no code
change), and **none** in the earlier implementation (a hook call plus two attributes;
no `if`, ternary, `&&`, `||`, `switch` or `catch`). Branches exercised through the
touched component's hook call and their tests (attributed to the earlier worker's
notes; re-verified green here):

- `useBreakpoint` → `expanded` (both `min-width` queries match): `describe.each` case
  `expanded width` asserts `data-breakpoint === 'expanded'`.
- `useBreakpoint` → `medium` (only 768 px query matches): case `medium width` and the
  resize test assert `data-breakpoint === 'medium'`.
- `useBreakpoint` → `compact` (neither query matches): case `compact width` and the RTL
  case assert `data-breakpoint === 'compact'`.
- Post-mount `setMatches(list.matches)` / change-listener path in `useMediaQuery`: the
  resize test fires the medium and expanded listeners and asserts the same region's
  `data-breakpoint` updates without losing its table.

Existing branch and failure-path coverage in `useBreakpoint.ts` / `useMediaQuery` is
owned by `src/theme/breakpoints.test.ts` and was not modified. No threshold, exclusion,
`@Generated`, ignore comment or skipped test was added.

## Clinical and architecture review

None required for this presentation-only change: no signed data, cross-organization
sharing, patient association, prescribing or critical-result workflow was touched.
Earlier simulated UX and senior-React reviews found no accessibility regression and no
blocking correctness defect.

## Findings

- **Pre-existing, out of scope:** committed conflict markers in
  `apps/api/src/main/java/com/aurevia/exchange/hl7/{Hl7ParseException,Hl7Parser,Hl7Segment}.java`
  are present in `HEAD` (verified with `git show HEAD:<path>`) and predate this work.
  The `git merge --no-edit origin/main` was "Already up to date", so these are not
  merge conflicts from this pass. They are Java API files outside the task scope and
  were not touched. A separate owner/task is needed to repair them.
- **`scripts/check.sh` is red at `check-pack`** on the CI-profile-under-skip-worktree
  artifact (verified above). It is not caused by the scoped change; the module gate
  `apps/staff-web/check.sh` is green. The earlier worker's notes record that running
  the repo check with HEAD `opencode.json` bytes temporarily in place reached a Python
  tooling suite that failed on two pre-existing environment tests
  (`test_worker_gate.MainTest.test_integration_failure_still_fails_the_gate` and
  `test_worker_gate_coverage.MainTest.test_maven_failure_with_failing_reports_still_fails`),
  both `FileNotFoundError: 'apps/api/target/surefire-reports'`; agent-run CI bytes were
  restored byte-for-byte afterwards (their note, line 68).
- **Decision carried forward (earlier simulated senior-React review F1):** the labelled
  region is kept at all bands. `DataTable` renders its region only on compact, but
  strict parity is impossible inside this scope: the frozen
  `src/features/admin/admin-screen.test.tsx` renders without a `matchMedia` stub
  (resolving to `expanded`) and asserts a `tabindex=0` region with
  `style.overflowX === 'auto'`. Removing the wrapper would break an existing test and
  change behaviour, which the story forbids. The wrapper adopts DataTable's
  `.table-region` class and structure; at medium/expanded `overflow: auto` does not
  scroll when content fits, so the region is inert there. A separate story must update
  `admin-screen.test.tsx` if strict structural parity is wanted.
- The inline `style={{ overflowX: 'auto' }}` is physical and redundant with
  `.table-region`'s `overflow: auto`, but it is pre-existing behaviour required by the
  frozen assertion, so it was preserved. It has no RTL mirroring effect.
- No `task-requests` block is needed: every step was completed within the declared
  scope. The HL7 conflict markers and the Python tooling environment issue are
  pre-existing defects outside this task's scope, not work this task requires.

## Lessons for workers

- Before calling a shared wrapper "responsive by breakpoint", grep existing tests for
  its structural assertions. A frozen test with no `matchMedia` stub resolves to
  `expanded`; exposing the band as `data-breakpoint` while keeping the wrapper
  satisfies both structure checks and behaviour preservation.
- Where two patterns conflict (DataTable's compact-only region vs an always-present
  admin region), keep existing behaviour, adopt the shared class, and record the
  decision in notes rather than editing an out-of-scope test.
- Reuse `tests/components/Button.responsive.test.tsx`'s `installMatchMedia` stand-in:
  one mutable answers map plus listener sets proves both band resolution and a live
  resize with no jsdom pixel claims.
- In this cloud worker a red `check-pack` is the CI profile under `skip-worktree`;
  confirm with `git ls-files -v opencode.json`, `cmp` against
  `.github/opencode.ci.json`, and `git show HEAD:opencode.json` before regenerating
  anything. Never commit that protected file.
- After `git merge --no-edit origin/main`, run `git grep -nE '^(<<<<<<<|>>>>>>>) '` and
  compare with `git show HEAD:<file>`: committed markers that predate the merge are
  out-of-scope defects, not conflicts to resolve in a scoped worker.

## Why this is not finished

The scoped story code is complete and fully green (`bash apps/staff-web/check.sh`,
exit 0, 100% coverage; targeted tests 19/19). One repository-wide check remains red
for an environment reason outside this task's scope:

- **Command and error:** `bash scripts/check.sh` → exit 1 at
  `check-pack: opencode.json is stale: run scripts/gen-opencode.py`.
- **Most likely cause:** the worktree `opencode.json` is the CI profile copied under
  Git `skip-worktree`, while the committed HEAD blob is a different local profile;
  the pack check judges the CI profile. Verified: `S opencode.json`, worktree equals
  `.github/opencode.ci.json`, differs from `git show HEAD:opencode.json`.
- **Already tried:** independent read-only verification of the skip-worktree state
  (above); module gate and targeted tests re-run green; branch reconciled with
  `origin/main`. The earlier worker additionally ran the repo check with HEAD bytes
  temporarily in place and found two pre-existing Python tooling tests failing on a
  missing `apps/api/target/surefire-reports` directory (no Maven reports generated
  locally), then restored the CI bytes byte-for-byte.
- **Exact next step:** the trusted integrator/Delivery runs the full gate in the proper
  CI environment (correct HEAD `opencode.json` profile and generated Maven reports).
  Do not regenerate or commit `opencode.json` from a worker; do not run full
  `mvn verify` locally to manufacture reports. If the full gate is still red, open a
  separate repair task for the environment/tooling failure — not for this scoped
  presentation change.
