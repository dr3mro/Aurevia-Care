# RESP-APP-RELEASED-REPORTS-SCREEN (fix pass — continuation, 2026-11-22 04:07Z)

## Summary

Continuation fix pass for the responsive `ReleasedReportsScreen` work on branch
`swarm/RESP-APP-RELEASED-REPORTS-SCREEN-505` (HEAD `27dad431`). I rehydrated from
the earlier findings and the newest hand-off note, merged `origin/main`, and
independently re-ran every gate for the touched area. The committed fix
(`5eccf8dc`, already on this branch before I started) repairs the ROOT
strict-typecheck failure

```
apps/patient-mobile/src/features/reports/ReleasedReportsScreen.tablet.test.tsx(233,11):
error TS2375: Type '{ ...; result: undefined; ... }' is not assignable to type
'ReleasedReport' with 'exactOptionalPropertyTypes: true'
```

by **omitting** the optional `result` key in the no-result synthetic fixture via
rest destructuring (`const { result: _discardedResult, ...withoutResult } = report;`)
instead of writing `result: undefined`. The root `tsconfig.json` sets
`exactOptionalPropertyTypes`, which forbids assigning `undefined` to an optional
property; the screen's `report.result === undefined` guard treats an absent key
identically, so runtime behaviour and the asserted outcome are unchanged.

Independently of the earlier reports, I confirmed the fix is present and every
applicable gate is green. No production code, type, config, threshold, exclusion,
tracker or workflow was changed in this pass — this note is the only new file.

## Affected requirements

PAT-01, PAT-02, UX-01 (per `tasks/00319000-RESP-APP-RELEASED-REPORTS-SCREEN.md`).

Acceptance mapping: presentation-only story — responsive two-pane layout on large
windows, centred/capped content, 44x44 touch targets, reflow at 200% text,
safe-area insets, unchanged focus order and screen-reader labels, RTL mirroring,
and no persistent clinical cache (PAT-02). No behaviour, text, API call or
exported-name change; synthetic data only. This fix removes only a type-illegal
test assignment, so all acceptance scenarios are unaffected.

## Risk class

Routine (`docs/WORKFLOW.md`). Test-only type repair; no clinical decision,
authorization, patient association, data, contract, migration or release change.

## Review owner

Senior React Native and Expo specialist (`orevia-senior-mobile`, loaded for
this pass). An earlier bounded, read-only advisory review found no blocking
issue: rest-omission is the smallest correct fix, `Omit<ReleasedReport, 'result'>`
is assignable to the `reports` prop, `_discardedResult` is exempt from
`noUnusedLocals` (rest-sibling rule) and `@typescript-eslint/no-unused-vars`
(`varsIgnorePattern: '^_'`), and runtime behaviour is identical. No new code was
added in this pass, so no new specialist review was required. Evidence sources:
**tool** (commands I executed) and **reviewer** (the earlier advisory finding).
No simulated user or human participant evidence was involved or claimed.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix.
Synthetic fixture only — no patient data, identifier or credential appears in the
change or this note. No reserved migration version was used.

## Authorization boundaries and audit events

Unchanged. No tenant, role, patient-link or audit event was touched. The screen
remains presentational; all report data and download authorization still arrive
from the authenticated client.

## Migration and rollout considerations

None. No deployment or rollout change; existing app users keep the same data flow.

## Test evidence

Evidence source: **tool** — I, the primary agent, executed every command below
and observed the results. No simulated user or human participant was involved.

Entry-point-to-test trace: `ReleasedReportsScreen` → `ScreenFrame` →
`reports-content` wrapper → `report-panes-<id>` → `report-summary-<id>` /
`report-detail-<id>`. The repaired test
(`keeps a single summary pane when a report has no result detail and tolerates no
provider`) renders the exported screen directly with a synthetic report whose
`result` key is omitted, injects `SafeAreaInsetsContext`, drives `Dimensions.get`
through `useWindowDimensions`, and asserts the rendered tree and accessibility
labels.

Exact commands and final lines (all run from repo root unless noted):

- `git merge --no-edit origin/main` → `Already up to date.` (run at start, after
  implementation/verification and again before finishing). No conflict markers
  in scoped files: `git grep -nE '^(<<<<<<<|>>>>>>>) ' -- 'apps/patient-mobile/src/features/reports/*.tsx'`
  returned none.
- `bash apps/patient-mobile/check.sh` → EXIT 0 (`npm ci` → typecheck → lint →
  `npm run test:coverage -- --ci`). Final lines:
  ```
  Test Suites: 79 passed, 79 total
  Tests:       1127 passed, 1127 total
  Snapshots:   0 total
  Time:        18.69 s
  Ran all test suites.
  ```
- `cd apps/patient-mobile && npm run typecheck` → EXIT 0. **Mandatory frontend
  typecheck final lines** (zero `error TS…`):
  ```
  > patient-mobile@0.1.0 typecheck
  > tsc --noEmit
  ```
- root `npm ci --no-audit --no-fund` → EXIT 0 (`added 164 packages in 2s`), then
  root `npm run typecheck` → EXIT 0. This is the **strict root typecheck** that
  originally produced TS2375 (`tsconfig.json` sets `exactOptionalPropertyTypes`
  and includes `apps/patient-mobile/src/**/*.tsx`). Final lines:
  ```
  > typecheck
  > tsc --noEmit -p tsconfig.json
  ```
  No `error TS…` output — the original defect does not reproduce.
- `bash scripts/frontend-check.sh` → EXIT 0. Final line:
  `frontend standards: policy and tool commands passed`.
- `bash scripts/frontend-typecheck-check.sh` → EXIT 0. Final line:
  `frontend typecheck: passed (or no frontend files changed)` (only pre-existing
  warnings in `enrollment/screens.tablet.test.tsx` and `tracker/presentation.test.ts`;
  0 errors, none in scoped files).
- staff-web `npm ci --no-audit --no-fund` → EXIT 0 (`added 486 packages in 7s`),
  needed so the strict root typecheck resolves React types.
- `bash scripts/check.sh` → EXIT 1, stopping immediately at
  `check-pack: opencode.json is stale: run scripts/gen-opencode.py` — a
  pre-existing cloud-worker skip-worktree artifact (see Findings), before any
  module or Java step ran.

Not run locally: `apps/api/check.sh` / full `mvn verify` and `db/check.sh`. The
standing "no full mvn verify" rule and the frontend-only scope leave these to the
integrator and Delivery. No Java file or migration was changed.

## Coverage

Command: `bash apps/patient-mobile/check.sh` (`npm ci` → `npm run typecheck` →
`npm run lint` → `npm run test:coverage -- --ci` with the module's enforced 100%
global threshold).

Result: `All files` 100/100/100/100; the `features/reports` directory's
`ReleasedReportsScreen.tsx` at **100/100/100/100**;
`Test Suites: 79 passed, 79 total`; `Tests: 1127 passed, 1127 total`. No
threshold, exclusion, `@Generated`/ignore comment or skip was added or removed.
(Type-only `types.ts` files show 0/0 statements and do not lower the enforced
global threshold.)

The committed fix adds no production line or branch. Every new/changed branch
and the test that takes it remains the same as the original implementation:

| New/changed branch | Test |
|---|---|
| `report.result === undefined` true → no result-detail pane (now type-legal via key omission); `SafeAreaInsetsContext ?? NO_INSETS_CONTEXT` — context undefined | `keeps a single summary pane … and tolerates no provider` (`renderScreen(..., false)` with `result` omitted) |
| `windowClass === 'large'` true → `maxWidth: 960`, row panes, `flex: 1` panes | Matrix cases `large / 1`, `large / 1.5`, `large / 2`; Arabic RTL case; no-result case |
| `windowClass === 'large'` false → column panes, natural-height panes | Matrix cases for `compact` and `regular` |
| `locale === 'ar'` true → `paddingStart` from `insets.right`, `paddingEnd` from `insets.left` | `mirrors the panes, safe-area insets and row direction under Arabic RTL` |
| `locale === 'ar'` false → LTR inset mapping | All 9 matrix cases |
| `useContext(...) ?? NO_INSETS` — non-null insets | Matrix + RTL cases (provider value supplied) |
| `useContext(...) ?? NO_INSETS` — null insets | `keeps a single summary pane … and tolerates no provider` (provider value `null`) |

Other production branches (`error`, empty state, `withdrawn`, optional `title`,
`correction`, `isRtl`, `locale` default, `showTabBar`, result-present) remain
covered by `ReleasedReportsScreen.tablet.test.tsx` and `reports-screen.test.tsx`.

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association,
prescribing or critical result was changed. Presentation-only test repair; no
domain contract, state machine, API or data boundary changed. A named
VoiceOver/TalkBack pass on device remains a separate launch gate and is not
claimed here.

## Findings

1. **Fix present, correct and verified — no further code change needed.** The
   committed repair at `5eccf8dc` is correct. Root `npm run typecheck`,
   `scripts/frontend-check.sh`, `scripts/frontend-typecheck-check.sh` and
   `bash apps/patient-mobile/check.sh` (100% coverage, 1127 tests) are all green
   in my run. The TS2375 failure reported by earlier integrator runs does not
   reproduce on this branch HEAD.
2. **`scripts/check.sh` fails only on a pre-existing environment artifact.**
   Measured in this checkout: `git ls-files -v opencode.json` = `S`
   (skip-worktree); the worktree file sha256 prefix is `50005c9c`, identical to
   `.github/opencode.ci.json` (the CI profile), while `HEAD:opencode.json` and
   `origin/main:opencode.json` are byte-identical (sha256 prefix `0dc2ac8e`).
   `agent-run.sh`'s `activate_ci_config` causes this. It is not a branch
   regression and is not fixable from task scope (editing `opencode.json`,
   `scripts/` or any tracker is forbidden and would block auto-merge). In the
   integrator/CI environment the committed profile is checked out normally and
   `check-pack` passes.
3. **Out-of-scope gate-tooling defect (not fixed).** Earlier findings recorded two
   `worker_gate` unit tests raising `FileNotFoundError` when
   `apps/api/target/surefire-reports` is absent. That is a `scripts/`-owned
   tooling task (COVPY-WORKER-GATE), not part of this story's two-file scope; not
   modified. Maven supplies surefire reports in the integrator environment.
4. **Pre-existing conflict markers, out of scope.** Earlier findings recorded
   markers in `apps/api/.../exchange/hl7/{Hl7ParseException,Hl7Parser,Hl7Segment}.java`
   on `origin/main` too. Not touched here; no markers in scoped `.ts`/`.tsx` files.
5. **Out-of-scope React `act()` warning** from `src/app/PatientResource.tsx:54`
   appeared in earlier full-suite runs; tests still pass. Unrelated to the
   scoped file and not fixed here.
6. **No new defect was introduced by this fix.**

## Lessons for workers

A patient-mobile module-local `tsc --noEmit` does **not** enable
`exactOptionalPropertyTypes`; the root `npm run typecheck` (inside
`scripts/frontend-check.sh`) does. Run `bash apps/patient-mobile/check.sh`,
`bash scripts/frontend-typecheck-check.sh` **and** `bash scripts/frontend-check.sh`
before calling a frontend fix green. Under `exactOptionalPropertyTypes`, omit an
optional key with rest destructuring (`const { key: _unused, ...rest } = value;`)
rather than writing `key: undefined`. In this worker environment, `check-pack.py`'s
"opencode.json is stale" is a known skip-worktree CI-profile artifact — verify
with `git ls-files -v opencode.json` and the sha256 comparison above, record it
as pre-existing, and do not edit `opencode.json`, `scripts/` or any tracker. The
root strict typecheck needs both modules' `node_modules` present (React types
resolve from `apps/staff-web/node_modules`), so run the module `npm ci` steps
before judging root `error TS…` output.

## Why this is not finished

**The scoped implementation IS finished** — this section exists only to record,
per the hand-off contract, the single residual repository-wide command failure so
the next worker or person does not rediscover it. No code change is needed.

- Command/error: `bash scripts/check.sh` → EXIT 1 with the single line
  `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. The gate
  stops before any module or full-`mvn` step.
- Most likely cause: pre-existing skip-worktree `opencode.json` holding the CI
  profile (`git ls-files -v` = `S`, worktree sha256 `50005c9c` =
  `.github/opencode.ci.json`), while `HEAD` and `origin/main` agree on blob
  `0dc2ac8e`. Not caused by this branch; not fixable from task scope.
- What I tried: confirmed the exact error; confirmed the skip-worktree state and
  sha256 equality; confirmed no conflict markers in scoped files; ran every
  applicable frontend gate directly (all green, including the strict root
  typecheck and `frontend-check.sh`). I did not edit any out-of-scope file to
  force `check.sh` green.
- Exact next step: the trusted integrator/CI runs `scripts/check.sh` in an
  environment with the committed `opencode.json` profile and freshly generated
  surefire reports, where `check-pack` and the worker-gate tests pass. Accept and
  merge this branch on that verdict; no further code change is required.
