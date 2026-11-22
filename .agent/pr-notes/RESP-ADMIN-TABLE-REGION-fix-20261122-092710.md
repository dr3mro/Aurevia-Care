# RESP-ADMIN-TABLE-REGION — fix pass 3 (independent re-verification, clean hand-off)

Fix attempt 3 of 3, continuing on branch `swarm/RESP-ADMIN-TABLE-REGION-861`.
Earlier records on this branch: `.agent/pr-notes/RESP-ADMIN-TABLE-REGION-20261122-084243.md`
(implementation, commit `fb99340a`), `...-fix-20261122-090836.md` (fix 1, `628aa8ff`,
verification only) and `...-fix-20261122-091642.md` (fix 2, `b9f33b15`, verification only).
This pass made **no new production or test change**: the scoped story is already
implemented test-first, is inside the declared scope, and is green under the mandatory
frontend gate. I independently re-verified red/green and the full gate with fresh tool
commands in this worker (the earlier passes' `node_modules` were absent here, so I ran
`npm ci` first), reconciled with `origin/main`, confirmed the exact environment blocker,
and routed a fresh bounded senior-React review. No tracker, workflow, script, agent file,
stylesheet, generated client or `opencode.json` was edited or committed.

## Summary

`AdminTableRegion` renders as a labelled, keyboard-focusable `div.table-region` with
`role="region"`, `aria-label`, `tabIndex={0}`, `data-breakpoint={useBreakpoint()}` and the
original inline `overflowX: 'auto'`. It reuses the existing `.table-region` rule from
`apps/staff-web/src/theme/responsive.css` (direction-neutral `overflow: auto`, logical
`scroll-padding-inline-start`, logical focus ring) — the same class and structure as
`src/components/DataTable.tsx`. No new CSS, dependency, behaviour, text, API call or
exported name was introduced. Changed files remain inside the declared scope:
`apps/staff-web/src/features/admin/AdminTableRegion.tsx` and
`apps/staff-web/tests/responsive/features-admin/AdminTableRegion.responsive.test.tsx`.

## Affected requirements

- **WEB-07** — supported clients / compact staff layout. The wrapper applies the shared
  `.table-region` rule instead of adding CSS. The synthetic test drives the real
  `useBreakpoint()` hook (media queries from `src/theme/breakpoints.ts`) at compact
  (0–767 px), medium (768–1279 px) and expanded (1280+ px), covering the story's 320,
  360, 390, 768, 820, 1024 and 1280 px matrix by band.
- **UX-01** — English and Arabic, RTL, WCAG 2.2 AA. The right-to-left case sets
  `dir="rtl"` / `lang="ar"`, an Arabic accessible name and long Arabic cell/action text,
  and asserts no physical side, no direction override, no hidden column and preserved
  keyboard order.

Acceptance mapping (task step 2): no horizontal page scroll → scrolling is confined to the
labelled region; long EN/AR text and 200% zoom → no fixed block size or physical side added
(painted-pixel proof belongs to the Playwright tasks, design guide §13); columns stack on
phones → the documented phone pattern for a table is one labelled scroll region with every
column present, not stacking (design guide §5); 44×44 coarse targets → the row action keeps
its `.touch` class (asserted); 16 px inputs → `.field` owns that rule and this wrapper has
no input; safe-area insets → `.page`/`.sheet` own them and this wrapper adds no edge
padding; logical properties only → asserted; no clinical column hidden with `display: none`
→ asserted for every header and cell; DataTable pattern → same class, structure, roles and
unhidden captioned table.

## Risk class

Routine — one contained staff-web presentation component plus its new test. No API, data,
authorization, contract or deployment boundary changed (`docs/WORKFLOW.md`).

## Review owner

`smartclinic-senior-react` (one bounded read-only simulated review dispatched in this
pass; verdict recorded below) and `smartclinic-ux-reviewer` plus an earlier senior-React
review recorded by the implementation worker. No reviewer edited code or ran builds/tests.
No human, clinician or participant review was available or claimed.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix. All
fixtures are synthetic (`SYN-0001`, a synthetic facility name, synthetic Arabic strings);
no patient data or credential was read or written.

## Authorization boundaries and audit events

None changed. The wrapper only presents data: it adds no command, role check, tenant scope
or audit event and does not alter any API call.

## Migration and rollout considerations

None. Presentation-only change; no contract, generated client, build script or stylesheet
change. `gen:api:check` passed unchanged in the full gate.

## Test evidence

Evidence source: **executed tool commands** in this pass (local shell, `npm ci`, Vitest
red/green, the mandatory `bash apps/staff-web/check.sh`, `bash scripts/check.sh`) plus a
**simulated specialist review** (bounded senior-React subagent). No simulated-user,
clinician or human-participant evidence.

- Branch reconciliation, `git merge --no-edit origin/main`, run at the start →
  `Already up to date.`; exit 0. No new conflict markers; `git status --short` empty.
- Environment setup: `node_modules` was absent in this fresh worker, so
  `cd apps/staff-web && npm ci --no-audit --no-fund` → `added 486 packages`; exit 0.
  (The module `check.sh` performs this same install automatically when needed.)

- Red stage, re-established with the pre-story component restored from `fb99340a^`
  (`git show fb99340a^:apps/staff-web/src/features/admin/AdminTableRegion.tsx`), then the
  committed file restored byte-for-byte (`cmp` verified, `git status` empty afterwards):
  `cd apps/staff-web && npx vitest run --exclude 'e2e/**' tests/responsive/features-admin/AdminTableRegion.responsive.test.tsx`
  → exit 1, five assertion failures: missing `table-region` class at the compact/medium/
  expanded/RTL cases (test lines 110/194) and missing `data-breakpoint` in the resize case
  (line 174). This proves the new test is not vacuous.

- Green stage after restoring the committed component:
  `npx vitest run --exclude 'e2e/**' tests/responsive/features-admin/AdminTableRegion.responsive.test.tsx src/features/admin/admin-screen.test.tsx`
  → `Test Files 2 passed (2); Tests 19 passed (19)`; exit 0.

- Mandatory frontend definition of done (exit 0), `bash apps/staff-web/check.sh`. Typecheck
  final lines (no `error TS…` anywhere — `grep 'error TS'` returned no matches; both
  `tsc --noEmit` steps produced empty output):

```
> smartclinic-staff-web@0.1.0 gen:api:check
> bash scripts/gen-api-types.sh --check
✨ openapi-typescript 7.4.2
gen:api: generated client matches the contract

> smartclinic-staff-web@0.1.0 typecheck
> tsc --noEmit -p tsconfig.json

> smartclinic-staff-web@0.1.0 lint
> eslint . --max-warnings 0

> smartclinic-staff-web@0.1.0 build
> tsc --noEmit -p tsconfig.json && vite build
✓ built in 170ms

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

  A non-failing jsdom event-listener stack trace appears in the log from a test that
  intentionally exercises an error path; all 1902 tests passed and the command exited 0.

- Repository check, `bash scripts/check.sh` → exit 1 at
  `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. This is the known
  cloud-worker CI-profile-under-skip-worktree environment artifact, not a change defect:
  `git ls-files -v opencode.json` → `S opencode.json`; the worktree file is `cmp`-identical
  to `.github/opencode.ci.json` (39,700 bytes) and differs from `git show HEAD:opencode.json`
  (the different committed local profile, 38,387 bytes). `scripts/check-pack.py` compares
  the worktree bytes with the generator's local-profile output, so the CI copy always reads
  as stale. It fails in `quick-check.sh` before any application test, which is why the
  worker-side gate reports "local checks: failed"; `scripts/integrator-gate.sh` runs the
  same `scripts/check.sh` in CI, where the committed profile is in place and no CI copy is
  overlaid. No protected file was generated or edited. See "Why this is not finished".

Entry-point-to-test trace: a staff admin page renders `<AdminTableRegion label>` around a
captioned table (`src/features/admin/{FacilitiesPage,StaffPage,ConfigPage,AuditEventList,ConfigEditor,StaffHistoryView}.tsx`)
→ `AdminTableRegion` calls `useBreakpoint()` (media queries from `src/theme/breakpoints.ts`)
and renders `div.table-region[role=region][aria-label][tabindex=0][data-breakpoint]` →
`tests/responsive/features-admin/AdminTableRegion.responsive.test.tsx` queries the region
by role/name and asserts class, band, focus, table semantics and RTL structure;
`src/features/admin/admin-screen.test.tsx` proves the unchanged `overflowX: 'auto'` wrapper
still satisfies the existing admin-screen contract (it renders with no `matchMedia` stub,
so `useBreakpoint` falls back to `expanded`, and still finds the `tabindex=0` region).

## Coverage

Command: `bash apps/staff-web/check.sh` → `npm run test:coverage`
(`vitest run --coverage --exclude 'e2e/**'`, v8, `perFile: true`, thresholds unmodified).
Result: **100% on every touched file and the whole module** — `No files with missing
coverage.`; 207 fully covered files; Statements 100% (3722/3722), Branches 100%
(1892/1892), Functions 100% (1258/1258), Lines 100% (3556/3556). No threshold, exclusion,
`@Generated`, ignore comment or skipped test was added; no `any` or `@ts-ignore` was added.

New branches introduced in `AdminTableRegion.tsx`: **none** in this pass (no code change)
and **none** in the implementation (a hook call plus two attributes; no `if`, ternary,
`&&`, `||`, `switch` or `catch`). Branches exercised through the touched component's hook
call and their tests (re-verified green here, attributed to the implementation worker):

- `useBreakpoint` → `expanded` (both `min-width` queries match): `describe.each` case
  `expanded width` asserts `data-breakpoint === 'expanded'`.
- `useBreakpoint` → `medium` (only the 768 px query matches): case `medium width` and the
  resize test assert `data-breakpoint === 'medium'`.
- `useBreakpoint` → `compact` (neither query matches): case `compact width` and the RTL case
  assert `data-breakpoint === 'compact'`.
- Post-mount `setMatches(list.matches)` / change-listener path in `useMediaQuery`: the resize
  test fires the medium and expanded listeners and asserts the same region's band updates
  without losing its table.

Existing branch and failure-path coverage in `useBreakpoint.ts` / `useMediaQuery` belongs to
`src/theme/breakpoints.test.ts` and was not modified.

## Clinical and architecture review

Not required for this presentation-only change: no signed data, cross-organization sharing,
patient association, prescribing or critical-result workflow was touched. Recorded simulated
senior-React and UX reviews found no accessibility regression and no blocking correctness
defect.

## Findings

- **Senior-React review verdict (this pass, bounded simulated subagent):** **"No blocking finding exists."** Static source inspection confirmed: the component reuses the existing `useBreakpoint()`/`Breakpoint` domain type and the shared `.table-region` rule (no new CSS, no physical side/direction override → RTL-neutral); the frozen `admin-screen.test.tsx` `tabindex=0`/`overflowX='auto'` assertion is preserved; the component is genuinely wired into six production admin views; and the test is non-vacuous (role/name/class/band/tabindex/columns/cells/RTL/focus order) with full branch exercise and no `any`/`@ts-ignore`. Two non-blocking observations (programmatic `.focus()` as a DOM proxy for real Tab traversal, and `data-breakpoint` as a documented observation seam) require no action. The reviewer ran no build/test (read-only brief); build and coverage evidence above came from my executed `bash apps/staff-web/check.sh`.
- **Decision carried forward (simulated senior-React review F1):** the labelled region is
  kept at **all** bands. `DataTable` renders its region only on compact, but strict parity
  is impossible inside this scope: the frozen `src/features/admin/admin-screen.test.tsx`
  renders without a `matchMedia` stub (so `useBreakpoint` resolves to `expanded`) and asserts
  a `tabindex=0` region with `style.overflowX === 'auto'`. Removing the wrapper would break
  an existing test and change behaviour, which the story forbids. The wrapper adopts
  DataTable's `.table-region` class and structure; at medium/expanded `overflow: auto` is
  inert when content fits. A separate story must update that test if strict structural
  parity is wanted.
- The inline `style={{ overflowX: 'auto' }}` is physical and redundant with `.table-region`'s
  `overflow: auto`, but it is pre-existing behaviour required by the frozen assertion, so it
  was preserved. It has no RTL mirroring effect.
- **Pre-existing, out of scope:** conflict-marker lines in three
  `apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7/{Hl7ParseException,Hl7Parser,Hl7Segment}.java`
  Javadoc comments are committed on `origin/main` (verified with `git grep ... origin/main`),
  predate this work, sit inside comments so they do not break compilation, and are not merge
  conflicts from this branch. They are Java API files outside the task scope and were not
  touched. A separate owner/task is needed to repair them.
- **Pre-existing, out of scope:** `bash scripts/check.sh` is red at `check-pack` for the
  skip-worktree CI-profile artifact documented above. The earlier workers also observed two
  Python tooling tests failing on a missing `apps/api/target/surefire-reports` directory when
  the repo check was run with HEAD bytes temporarily in place; those reports are produced by
  the Maven step in CI. Neither failure involves staff-web code.
- No `task-requests` block is needed: every story step was completed inside the declared
  scope. The environment artifacts above are not work this task requires.

## Lessons for workers

- A fresh cloud worker may start with no `node_modules`; the module `check.sh` runs
  `npm ci` itself, but for red/green run `npm ci` first or the first Vitest run dies with
  `ERR_MODULE_NOT_FOUND` on `vitest/config`, not an assertion failure.
- Re-establish red/green yourself before signing off inherited work: restore the pre-story
  component from its parent commit, run the new test (five failures), restore the committed
  file byte-for-byte, run it green. It takes seconds and proves the test is meaningful
  without changing any file.
- Before calling a shared wrapper responsive, grep its frozen structural tests. A test with
  no `matchMedia` stub resolves to `expanded`; keep the wrapper and expose the band as
  `data-breakpoint` rather than removing behaviour.
- In this cloud worker a red `check-pack` is the CI profile under `skip-worktree`: confirm
  with `git ls-files -v opencode.json`, `cmp` against `.github/opencode.ci.json` and
  `git show HEAD:opencode.json`. Never regenerate or commit that protected file.
- After `git merge --no-edit origin/main`, run the conflict-marker grep and compare any hits
  with `git show origin/main:<path>`; committed markers that predate the merge are
  out-of-scope defects, not conflicts to resolve in a scoped worker.

## Why this is not finished

The **scoped story is complete and green**: red (5 failures) and green (19 passes) were
re-established with fresh commands, `bash apps/staff-web/check.sh` exited 0 with 100%
coverage and zero `error TS…`, and the `origin/main` reconciliation was clean. One
repository-wide check remains red for an environment reason outside this task's scope:

- **Command and error:** `bash scripts/check.sh` → exit 1 at
  `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. It fails in
  `quick-check.sh` before any application test, which is also why the swarm worker-side gate
  reports "local checks: failed" for every pass on this branch.
- **Most likely cause:** the worktree `opencode.json` is the CI profile copied under Git
  `skip-worktree`, while the committed HEAD blob is a different local profile;
  `check-pack.py` judges the worktree copy against the generator's local-profile output.
  Verified: `S opencode.json`; worktree equals `.github/opencode.ci.json` (39,700 bytes);
  differs from `git show HEAD:opencode.json` (38,387 bytes).
- **Already tried:** independent read-only verification of the skip-worktree state and
  `check-pack.py` logic; clean `origin/main` reconciliation; `npm ci`; targeted red/green
  re-establishment; full module gate re-run green. The earlier workers ran the repo check
  with HEAD bytes temporarily in place and found two pre-existing Python tooling tests
  needing `apps/api/target/surefire-reports` (generated by the Maven step in CI), then
  restored the CI bytes byte-for-byte.
- **Exact next step:** the trusted integrator/Delivery runs the full gate in the proper CI
  environment with the committed `opencode.json` profile and generated Maven reports; on
  that evidence it accepts and merges the scoped change. Do not regenerate or commit
  `opencode.json` from a worker, and do not run full `mvn verify` locally to manufacture
  reports. If CI is still red, open a separate repair task for the environment/tooling
  failure, not for this presentation change.
