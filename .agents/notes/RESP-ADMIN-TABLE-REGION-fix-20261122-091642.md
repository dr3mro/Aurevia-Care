# RESP-ADMIN-TABLE-REGION — fix pass 2 (fresh verification and clean hand-off)

Fix attempt 2 of 3, continuing from the branch
`swarm/RESP-ADMIN-TABLE-REGION-861`. Earlier records are
`docs/pr/notes/RESP-ADMIN-TABLE-REGION-20261122-084243.md` (implementation,
commit `fb99340a`) and `docs/pr/notes/RESP-ADMIN-TABLE-REGION-fix-20261122-090836.md`
(fix attempt 1, commit `628aa8ff`, verification only). This pass made **no new
production or test change**: the earlier implementation is already complete,
inside the declared scope, and green under the mandatory frontend gate. I
re-established the failing-before/passing-after evidence myself with fresh
tool commands, re-ran the full module gate, bound a new senior-React review,
reconciled with `origin/main` twice, and recorded the exact environment
blocker that keeps the worker-side repository check red. No tracker, workflow,
script, agent file, stylesheet or generated client was edited or committed.

## Affected requirements

- **WEB-07** — compact staff layout from 320 px. `AdminTableRegion` now applies
  the existing `.table-region` rule from `apps/staff-web/src/theme/responsive.css`
  (direction-neutral `overflow: auto`, logical `scroll-padding-inline-start`,
  logical focus ring) and exposes the resolved band through `data-breakpoint`
  from the real `useBreakpoint()` hook. No CSS or dependency was added.
- **UX-01** — English/Arabic, RTL, WCAG 2.2 AA. The synthetic test drives
  compact, medium and expanded bands, checks the full column set, focus order,
  logical-only inline styles and a `dir="rtl"`/`lang="ar"` case with long Arabic
  values and a coarse-pointer `.touch` row action.

Acceptance mapping (task step 2): no horizontal page scroll → scrolling is
confined to the labelled region; long EN/AR text and 200% zoom → no fixed block
size or physical side added (painted-pixel proof belongs to the Playwright
tasks, design guide §13); columns stack on phones → the documented phone pattern
for a table is one labelled scroll region with every column present, not stack-
ing (design guide §5); 44×44 coarse targets → the row action keeps `.touch`
(asserted); 16 px inputs → `.field` owns that rule and this wrapper has no
input; safe-area insets → `.page`/`.sheet` own them and this wrapper adds no
edge padding; logical properties only → asserted; no clinical column hidden →
asserted for every header and cell; DataTable pattern → same class, structure,
roles and unhidden captioned table.

## Risk class

Routine — one contained staff-web presentation component plus its new test.
No API, data, authorization, contract or deployment boundary changed
(`docs/WORKFLOW.md`).

## Review owner

`aurevia-senior-react` — one bounded read-only review of the current scoped
change (simulated subagent, this pass) returned **"No blocking finding exists"**;
its verdict was based on file reads of the two scoped files plus
`src/theme/breakpoints.ts`, `responsive.css`, `vite.config.ts`, `tests/setup.ts`,
`admin-screen.test.tsx` and a usage grep. `aurevia-ux-reviewer` and a senior-
React review were also recorded by the implementation worker. No reviewer
edited code or ran builds/tests. No human, clinician or participant review was
available or claimed.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix.
All fixtures are synthetic (`SYN-0001`, a synthetic facility name, synthetic
Arabic strings). No patient data or credential was read or written.

## Authorization boundaries and audit events

None changed. The wrapper only presents data; it adds no command, role check,
tenant scope or audit event and does not alter any API call.

## Migration and rollout considerations

None. Presentation-only change; no contract, generated client, build script or
stylesheet change. `gen:api:check` passed unchanged.

## Test evidence

Evidence source: **executed tool commands** in this pass (local shell, Vitest,
the mandatory `bash apps/staff-web/check.sh`, `bash scripts/check.sh`) plus a
**simulated specialist review**. No simulated-user, clinician or
human-participant evidence.

Branch reconciliation, `git merge --no-edit origin/main` — run at the start and
again before finishing: `Already up to date.` both times; exit 0. No new
conflict markers were introduced (the conflict-marker grep reports
only the three pre-existing HL7 Javadoc-comment markers, verified present in
`git show origin/main:` against the named file path, and outside this task's scope).

Red stage, re-established in this pass by temporarily restoring the pre-story
component (`git show fb99340a^:apps/staff-web/src/features/admin/AdminTableRegion.tsx`)
and restoring the committed file byte-for-byte afterwards:
`cd apps/staff-web && npx vitest run --exclude 'e2e/**' tests/responsive/features-admin/AdminTableRegion.responsive.test.tsx`
→ `Test Files 1 failed (1); Tests 5 failed | 6 passed (11)`; failures were the
missing `table-region` class (three bands and RTL) and missing `data-breakpoint`
(resize case). This proves the new test is not vacuous.

Green stage after restoring the committed component, same command →
`Test Files 1 passed (1); Tests 11 passed (11)`; exit 0. `git status --short`
was empty after the restore, so no component or test content changed in this
pass.

Mandatory frontend definition of done (exit 0), `bash apps/staff-web/check.sh`.
Typecheck/lint/build final lines (there is **no** `error TS…` anywhere in the
run; `grep -c 'error TS'` returned 0; the typecheck and build `tsc --noEmit`
steps produced empty output):

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
✓ built in 134ms
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
===============================================================================
```

Repository check, `bash scripts/check.sh` → exit 1 at
`check-pack: opencode.json is stale: run scripts/gen-opencode.py`. This is the
known cloud-worker CI-profile-under-skip-worktree artifact, not a change defect:
`git ls-files -v opencode.json` → `S opencode.json`, the worktree file is
byte-identical to `.github/opencode.ci.json`, and `git show HEAD:opencode.json`
is the different committed local profile. It fails in `quick-check.sh` before
any application test runs, which is why the swarm worker-side gate reports
red for every story in this environment; the integrator runs the full gate with
the committed profile restored. See "Why this is not finished".

Entry-point-to-test trace: a staff admin page renders
`<AdminTableRegion label>` around a captioned table
(`src/features/admin/{FacilitiesPage,StaffPage,ConfigPage,AuditEventList,ConfigEditor,StaffHistoryView}.tsx`)
→ `AdminTableRegion` calls `useBreakpoint()` (media queries from
`src/theme/breakpoints.ts`) and renders
`div.table-region[role=region][aria-label][tabindex=0][data-breakpoint]` →
`tests/responsive/features-admin/AdminTableRegion.responsive.test.tsx` queries
the region by role/name and asserts class, band, focus, table semantics and RTL
structure; `src/features/admin/admin-screen.test.tsx` proves the unchanged
`overflowX: 'auto'` wrapper still satisfies the existing admin-screen contract.

## Coverage

Command: `bash apps/staff-web/check.sh` → `npm run test:coverage`
(`vitest run --coverage --exclude 'e2e/**'`, v8, `perFile: true`, thresholds
unmodified). Result: **100% on every touched file and the whole module** —
`No files with missing coverage.`; 207 fully covered files; Statements 100%
(3722/3722), Branches 100% (1892/1892), Functions 100% (1258/1258), Lines 100%
(3556/3556). No threshold, exclusion, `@Generated`, ignore comment or skipped
test was added.

New branches introduced in `AdminTableRegion.tsx`: **none** — the change is a
hook call plus two attributes, with no `if`, ternary, `&&`, `||`, `switch` or
`catch`. Branches exercised through that hook call and their tests:

- `useBreakpoint` → `expanded` (both `min-width` queries match): `describe.each`
  case `expanded width` asserts `data-breakpoint === 'expanded'`.
- `useBreakpoint` → `medium` (only the 768 px query matches): case `medium width`
  and the resize test assert `data-breakpoint === 'medium'`.
- `useBreakpoint` → `compact` (neither query matches): case `compact width` and
  the RTL case assert `data-breakpoint === 'compact'`.
- Post-mount `setMatches(list.matches)` / change-listener path in
  `useMediaQuery`: the resize test fires the medium and expanded listeners and
  asserts the same region's band updates without losing its table.

Existing branch and failure-path coverage in `useBreakpoint.ts` belongs to
`src/theme/breakpoints.test.ts` and was not modified.

## Clinical and architecture review

Not required for this presentation-only change: no signed data, cross-
organization sharing, patient association, prescribing or critical-result
workflow was touched. Recorded simulated reviews found no accessibility
regression and no blocking correctness defect.

## Findings

- **Decision carried forward (simulated senior-React review F1):** the labelled
  region is kept at **all** bands. `DataTable` renders its region only on
  compact, but strict parity is impossible inside this scope: the frozen
  `src/features/admin/admin-screen.test.tsx` renders without a `matchMedia`
  stub (so `useBreakpoint` resolves to `expanded`) and asserts a `tabindex=0`
  region with `style.overflowX === 'auto'`. Removing the wrapper would break an
  existing test and change behaviour, which the story forbids. The wrapper
  adopts DataTable's `.table-region` class and structure; at medium/expanded
  `overflow: auto` is inert when content fits. A separate story must update that
  test if strict structural parity is wanted.
- The inline `style={{ overflowX: 'auto' }}` is physical and redundant with
  `.table-region`'s `overflow: auto`, but it is pre-existing behaviour required
  by the frozen assertion, so it was preserved. It has no RTL mirroring effect.
- **Pre-existing, out of scope:** conflict-marker lines in three
  `apps/api/src/main/java/com/aurevia/exchange/hl7/*` Javadoc
  comments are committed on `origin/main` (verified with `git show`), predate
  this work, and are inside comments so they do not break compilation. They are
  not merge conflicts from this pass and were not touched.
- **Pre-existing, out of scope:** `bash scripts/check.sh` is red at `check-pack`
  because the worktree `opencode.json` is the CI profile under `skip-worktree`;
  the integrator/CI environment uses the committed profile and does not have
  this artifact. The earlier worker additionally observed two Python tooling
  tests failing on a missing `apps/api/target/surefire-reports` directory when
  the repository check was run with HEAD bytes temporarily in place; those
  reports are produced by the Maven step in CI. Neither failure involves
  staff-web code.
- An attempted extra test (document-direction cleanup) was reverted: the
  proposed regression **passed before any fix**, showing the document state is
  already reset between tests (`tests/setup.ts:6-7`), so it would have been a
  test with no failing cause.
- Non-blocking observations from this pass's simulated senior-React review:
  the assertion at `AdminTableRegion.responsive.test.tsx:115`
  (`style.overflow` is not `hidden`) is trivially true because only `overflowX`
  is set inline; and `useBreakpoint()` adds a `matchMedia` subscription plus
  resize re-renders purely as a jsdom observation seam. Neither changes data,
  actions, text, API calls or exported names, and the resize test proves DOM
  and focus stability, so both were left as-is within this scope. The physical
  `overflowX` declaration is pre-existing and covered above.
- No `task-requests` block is needed: every story step was completed inside the
  declared scope. The environment artifacts above are not work this task
  requires.

## Lessons for workers

- Re-establish red/green yourself before signing off a inherited fix: restore
  the pre-story component from its parent commit, run the new test (five
  failures), restore the committed file, run it green. It takes seconds and
  proves the test is meaningful without changing any file.
- Do not add a "cleanup" test unless you first show it fails. `tests/setup.ts`
  already resets `document.documentElement` between tests in this Vitest setup,
  so a direction-restoration assertion passed before any fix and was correctly
  dropped.
- Before calling a shared wrapper responsive, grep its frozen structural tests.
  A test with no `matchMedia` stub resolves to `expanded`; keep the wrapper and
  expose the band as `data-breakpoint` rather than removing behaviour.
- In this cloud worker a red `check-pack` is the CI profile under
  `skip-worktree`: confirm with `git ls-files -v opencode.json`, `cmp` against
  `.github/opencode.ci.json` and `git show HEAD:opencode.json`. Never regenerate
  or commit that protected file.
- After `git merge --no-edit origin/main`, run the conflict-marker grep and
  compare any hits with `git show HEAD:` for that file path; committed markers that predate
  the merge are out-of-scope defects, not conflicts to resolve in a scoped worker.

## Why this is not finished

The **scoped story is complete and green**: red (5 failures) and green (11
passes) were re-established with fresh commands, `bash apps/staff-web/check.sh`
exited 0 with 100% coverage and zero `error TS…`, and both `origin/main` merges
were clean. One repository-wide check remains red for an environment reason
outside this task's scope:

- **Command and error:** `bash scripts/check.sh` → exit 1 at
  `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. It fails in
  `quick-check.sh`, before any application test, which is also why the worker
  gate reports "local checks: failed" for this and the earlier passes.
- **Most likely cause:** the worktree `opencode.json` is the CI profile copied
  under Git `skip-worktree`, while the committed HEAD blob is a different local
  profile; `check-pack.py` judges the worktree copy. Verified: `S opencode.json`,
  worktree equals `.github/opencode.ci.json`, differs from
  `git show HEAD:opencode.json`.
- **Already tried:** independent read-only verification of the skip-worktree
  state; both branch reconciliations (`Already up to date.`); targeted red/green
  re-establishment; full module gate re-run green. The earlier worker ran the
  repo check with HEAD bytes temporarily in place and found two pre-existing
  Python tooling tests needing `apps/api/target/surefire-reports` (generated by
  the Maven step in CI), then restored the CI bytes byte-for-byte.
- **Exact next step:** the trusted integrator/Delivery runs the full gate in the
  proper CI environment with the committed `opencode.json` profile and generated
  Maven reports; on that evidence it merges the scoped change. Do not regenerate
  or commit `opencode.json` from a worker, and do not run full `mvn verify`
  locally to manufacture reports. If CI is still red, open a separate repair
  task for the environment/tooling failure, not for this presentation change.
