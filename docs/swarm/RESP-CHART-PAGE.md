# RESP-CHART-PAGE · Staff web: responsive `ChartPage` (features-chart)

PR #754, tested branch tip 375a45693fdb1e610ca56f699c8c80cc830f1c13.

**Draft: the worker did not finish cleanly** (agent result: ok; local checks: failed). Its work is saved here so nothing is lost; a fix worker or a person continues from this branch.

**Why it failed** (TypeScript/frontend and gate lines):

```text
python coverage: WARNING coverage is not installed; the tests ran without the coverage check
FAIL unreferenced-service: apps/api/src/main/java/com/x/NewService.java
FAIL unreferenced-service: apps/api/src/main/java/com/x/OrphanService.java
FAIL unreferenced-service: apps/api/src/main/java/com/synthetic/OrphanService.java
ERROR: test_maven_failure_with_failing_reports_still_fails (test_worker_gate_coverage.MainTest.test_maven_failure_with_failing_reports_still_fails)
  File "/__w/Aurevia-Care/Aurevia-Care/scripts/tests/test_worker_gate_coverage.py", line 554, in test_maven_failure_with_failing_reports_still_fails
  File "/__w/Aurevia-Care/Aurevia-Care/scripts/tests/test_worker_gate_coverage.py", line 521, in run_main
```

Task: RESP-CHART-PAGE

Swarm worker 7. Local checks: failed. Agent result: ok.

# RESP-CHART-PAGE — responsive staff-web `ChartPage`

## Summary

Made `ChartPage` responsive at every supported band (320–1280 px, LTR and RTL)
without changing behaviour, text, API calls or exported names. The page now
uses the shared `.page` utility (safe-area-aware logical gutters, single reading
column, centred maximum width), exposes the resolved band as `data-breakpoint`,
and wraps the wide clinical tables in one labelled, keyboard-scrollable
`.table-region` on compact only. Long identity text gets logical wrapping;
`Load more` keeps the shared `touch` target. A new jsdom responsive test drives
the real route through its normal providers with synthetic data and asserts
structure, labels, roles, keyboard order and RTL at compact/medium/expanded.

Files changed (only the declared scope):
- `apps/staff-web/src/features/chart/ChartPage.tsx`
- `apps/staff-web/tests/responsive/features-chart/ChartPage.responsive.test.tsx`

## Affected requirements

- **WEB-07** — responsive staff web from 320 px; no page-level horizontal scroll;
  the labelled scroll region owns wide tables and all clinical columns stay
  reachable. Evidence: `.page` + compact-only `.table-region` and the
  compact/medium/expanded tests.
- **UX-01** — logical properties only, RTL mirroring without a direction
  override, no hidden clinical field, long English/Arabic wrapping, 44 px
  coarse-pointer target on the action, 200 % zoom readability (no fixed block
  size on clinical regions). Evidence: logical-property assertions, RTL test,
  long-string tests, touch-target test.

Acceptance mapping (story steps 1–5): read the named docs and source (1); the
component now works at all widths/directions with the listed properties (2);
only classes in `responsive.css` and `useBreakpoint` were used, with logical
inline styles for identity wrapping (3); the new scoped test mocks `matchMedia`
for all three bands and checks structure, roles, keyboard order and RTL while
all prior tests stay green (4); no behaviour, text, API or export changed (5).

## Risk class

Routine (docs/WORKFLOW.md): contained presentation change in one component and
one new test. No data, authorization, state-machine or clinical-semantics change.

## Review owner

Senior React/Vite and TypeScript specialists (skills loaded); one bounded
simulated UX review dispatched. No clinical or human evidence claimed.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback/forward-fix.

## Authorization boundaries and audit events

None changed. Tenant scope still comes from `useActiveOrganization()`; the
patient id still comes from the route. No audit event added or changed.

## Migration and rollout considerations

None. Presentation-only; existing `.page` / `.table-region` classes are already
shipped in `apps/staff-web/src/theme/responsive.css`.

## Test evidence

Evidence source: **tool** (executed commands) unless labelled simulated reviewer.
All fixtures are synthetic (`SYN-0001`, `patient-1`, `org-a`, long synthetic EN/AR
names). No patient data, credential or raw log in this note.

Entry-point-to-test trace: route `/patients/:id/chart` (`src/features/chart/routes.ts`)
→ lazy `ChartPage` → `.page` wrapper + compact `.table-region` →
`tests/responsive/features-chart/ChartPage.responsive.test.tsx` renders through
`ThemeProvider` → `I18nProvider` → `ActiveOrganizationProvider` → `RouterProvider`
with `send` mocked by URL and a deterministic `matchMedia` stand-in.

Commands (run from the repository root unless shown):

- Red first, against the unmodified component:
  `cd apps/staff-web && ./node_modules/.bin/vitest run tests/responsive/features-chart/ChartPage.responsive.test.tsx`
  → **8 failed | 2 passed (10 tests)** (missing `.page`, `data-breakpoint`,
  compact region).
- Green after the change, same command: **1 passed | 10 tests**.
- Existing and new chart coverage:
  `cd apps/staff-web && ./node_modules/.bin/vitest run src/features/chart tests/responsive`
  → **7 files passed, 135 tests passed**.
- Mandatory module gate:
  `bash apps/staff-web/check.sh` → `gen:api` client matches contract;
  typecheck, lint, build and bundle-size gate all passed;
  **159 test files passed, 1901 tests passed**; coverage summary below.
- Typecheck final lines (explicit, required by the frontend definition of done):
  ```
  > aurevia-staff-web@0.1.0 typecheck
  > tsc --noEmit -p tsconfig.json
  ```
  Exit code **0**, no `error TS…` (`cd apps/staff-web && npm run typecheck`).
- Branch reconciliation after each stage and before finishing:
  `git merge --no-edit origin/main` → `Already up to date.` / `MERGE_OK`;
  working tree contains only the two scoped paths.
- Repo-level `bash scripts/check.sh` stopped at the **documented cloud-worker
  `opencode.json` false positive** (OPS-CW-003-01): `check-pack: opencode.json is
  stale: run scripts/gen-opencode.py`. Verified with
  `git ls-files -v opencode.json` → `S` (skip-worktree) and worktree differs from
  `git show HEAD:opencode.json`; the committed local profile is HEAD truth and
  was not edited. Remaining stages were run individually, all rc=0:
  `project-name.py displayName` → `Aurevia Care`, `repositorySlug` →
  `aurevia-care`; `check-docs.py`, `check-secrets.py`, `check-names.py`,
  `check-wiring.py`, `check-migrations.py --require-baseline` all rc=0;
  `bash scripts/frontend-check.sh` → `frontend standards: policy and tool
  commands passed` (rc=0). `bash scripts/python-coverage.sh` → rc=1 from
  pre-existing worker-image limits (a `TODO.md` tracker violation and
  "integration run failed without fresh, usable test results (compile or setup
  error)" in unrelated Python fixtures); no script, tracker or test was edited
  by this worker. `db/check.sh` and full Java verification belong to the
  integrator; this story changes no backend or migration.

The `useI18n must be used inside I18nProvider` / `useTheme must be used inside
ThemeProvider` console lines in the full run come from pre-existing negative
tests that deliberately render consumers without providers; the run is green.

## Coverage

Command: `bash apps/staff-web/check.sh` (Vitest v8, per-file thresholds 100 %
for lines/branches/functions/statements; include `src/**/*.{ts,tsx}`).

Result: **100 % (3722/3722 statements, 1894/1894 branches, 1258/1258 functions,
3556/3556 lines); 207 files fully covered, 4 empty files skipped.**
"No files with missing coverage." No threshold, exclusion, generated marker or
skip was added; no test disabled.

New/changed production branches and the test that takes each side:

| Branch (ChartPage.tsx) | Test taking it |
|---|---|
| `breakpoint === 'compact'` **true** → labelled `table-region` | `adds a labelled, keyboard-scrollable chart region only on compact`; `switches the chart region off at the medium edge …` (compact side) |
| `breakpoint === 'compact'` **false** → direct chart | same test's medium/expanded loop; `keeps a labell
