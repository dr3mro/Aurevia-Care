# RESP-REVISION-HISTORY · Staff web: responsive `RevisionHistory` (features-chart)

PR #764, tested branch tip e10862fd9c6870aa4de2f734fa10fd7ffe42176b.

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

Task: RESP-REVISION-HISTORY

Swarm worker 5. Local checks: failed. Agent result: ok.

# RESP-REVISION-HISTORY — responsive `RevisionHistory` (features-chart)

## Summary

Made the read-only `RevisionHistory` panel responsive without changing its text,
labels, DOM semantics, API calls or exported names. It now resolves the shared
breakpoint hook: on compact phones the revisions use the existing `.card-list`
class (cards), and from the medium breakpoint upward the original grid list is
kept. Logical wrapping (`minInlineSize`, `overflowWrap: anywhere`,
`whiteSpace: normal`) is applied to every revision row and to the empty state so
long English and Arabic labels wrap instead of clipping or forcing a horizontal
scroll. `data-breakpoint` and `data-revision-view` attributes expose the layout
band to jsdom tests (the same convention used by `OrgSwitcher` and `DataTable`).
No new CSS class or stylesheet edit was needed; no dependency was added.

Scope stayed at the two paths in the task. All fixtures are synthetic.

## Affected requirements

- **WEB-07** — responsive staff web from 320 px upward; labelled cards/list, logical
  properties, no horizontal page scroll and no hidden clinical field.
- **UX-01** — readable, wrapable clinical text in English and Arabic at every
  breakpoint, with stable labels, roles and keyboard order.

Acceptance mapping:

| Story step | Evidence in this change |
|---|---|
| 320/360/390/768/820/1024/1280 px, LTR and RTL | viewport-matrix test (`it.each` over all seven widths) and RTL test |
| Cards on phones, list on larger | `data-revision-view` `cards`/`list` + `.card-list` class at compact/medium/expanded |
| Long English and Arabic strings wrap; no clipped/overlapping text | logical wrapping styles asserted on every `<li>` and the empty `<p>`; Arabic locale labels asserted |
| Columns stack on phones | the history is a single-column list at every band; compact rows become independent cards |
| No clinical field hidden with `display: none` | every list item asserted visible and `hidden === false` at all bands |
| Logical properties only | physical-style scan of every inline-styled element; `paddingInline` retained on the entered-in-error marker |
| Keyboard order / reachable actions | DOM order of `li` asserted; no `button`/`input`/`select`/`textarea`/`a`/`[tabindex]` introduced; list items are not tab stops |
| Safe-area insets / 16 px inputs / 44 px coarse targets | N/A to this read-only list — owned by the shell and the shared `.field`/`.touch` primitives; no input or control is rendered here |
| Text at 200 percent zoom stays readable | wrap styles, `min-inline-size: 0` and no fixed block size; jsdom asserts the CSS contract, pixel checks remain with the Playwright tasks |
| Compare action stays reachable | the compare action lives in `NotePage` (out of scope, see Findings); this component's content and identity are unchanged, so nothing here removes it |

## Risk class

Routine (contained staff-web presentation change). No data, authorization,
clinical decision or migration boundary changed.

## Review owner

Senior React and Vite specialist (bounded plan/risks requested and received from
`smartclinic-senior-react`); primary agent owned the edits and all commands.
The specialist's two material recommendations were adopted: keep hooks above the
empty-state early return, and style the existing `<li>` instead of inserting new
`<span>` nodes (zero DOM change, no risk to existing Testing Library queries).

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix.

## Authorization boundaries and audit events

None. Tenant scoping is unchanged: the React key is still
`${organizationId}:${id}`, `data-revision-id` still carries only the tenant-local
revision id, and no audit event is added or changed. No patient data or
credential appears in code, fixtures, logs or notes.

## Migration and rollout considerations

None — presentation-only. The `data-*` attributes are inert test hooks.

## Test evidence

Red seen first: the new test file against the unchanged component gave
`Test Files 1 failed (1); Tests 24 failed | 1 passed (25)` (missing
`data-breakpoint` root, missing wrapping styles). After implementation:

- `node_modules/.bin/vitest run tests/responsive/features-chart/RevisionHistory.responsive.test.tsx src/features/chart/components.test.tsx`
  → `Test Files 2 passed (2); Tests 42 passed (42)`. Existing chart component
  tests (including `components.test.tsx` `RevisionHistory`, `DocumentList`,
  `EnteredInErrorBanner`, `NoteEditor`) still pass unchanged.
- `npm run typecheck` (from `apps/staff-web`) final lines:
  `> smartclinic-staff-web@0.1.0 typecheck` /
  `> tsc --noEmit -p tsconfig.json` — exit 0, no `error TS…`.
- `npm run lint` → `> eslint . --max-warnings 0` — exit 0.
- `bash apps/staff-web/check.sh` → `exit=0`; generated-client check, typecheck
  (`tsc --noEmit -p tsconfig.json`), lint, production Vite build, bundle-size
  gate and coverage all passed. Final lines:
  `Test Files 159 passed (159)`, `Tests 1916 passed (1916)`,
  `No files with missing coverage.`, `207 files fully covered.`
  (`4 empty files skipped`). The React "must be used inside Provider" stack
  traces in the log are expected console output from the existing provider-guard
  tests, which deliberately render consumers outside a provider; the run is
  green.
- `bash scripts/check.sh` (end-of-task repository gate, run once) → `exit=1`,
  stopping at its second stage with
  `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. Confirmed the
  documented cloud-worker false positive: `git ls-files -v opencode.json` →
  `S opencode.json` (skip-worktree), so the worktree holds the CI profile while
  `git show HEAD:opencode.json` is the committed local profile. Did not regenerate,
  edit or commit that file. Remaining stages were run individually:
  - bash syntax + shellcheck, project-name, `check-docs.py`, `check-secrets.py`,
    `check-names.py`, `check-wiring.py`, `check-migrations.py --require-baseline` →
    all `exit=0` (`check-wiring` emitted only informational stale-baseline notes).
  - `bash scripts/frontend-check.sh` → `exit=0`, final line
    `frontend standards: policy and tool commands passed`.
  - `bash scripts/python-coverage.sh` → `exit=1`; `Ran 1125 tests in 27.355s`,
    `FAILED (failures=6, errors=2)`, plus
    `python coverage: WARNING coverage is not installed; the tests ran without the coverage check`.
    The failures are worker-image limits unrelated to this change (no Python file
    was touched): `FileNotFoundError: 'apps/api/target/surefire-reports'` (Maven not
    run in a worker), two `opencode.json is stale` assertions (same skip-worktree
    artifact), gh/shell sandbox-fixture diffs, and other tracker/gate fixture
    assertions. The integrator runs these with PyYAML, coverage and cached NVD data.
- `git merge --no-edit origin/main` run at the start and after implementation:
  `Already up to date.` No conflict m
