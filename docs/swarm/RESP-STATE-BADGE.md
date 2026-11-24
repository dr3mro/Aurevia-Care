# RESP-STATE-BADGE · Staff web: responsive `StateBadge` (features-admin)

PR #752, tested branch tip d548f058fd92ab1341250530a25ef027676442d6.

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

Task: RESP-STATE-BADGE

Swarm worker 6. Local checks: failed. Agent result: ok.

# RESP-STATE-BADGE — Staff web: responsive `StateBadge` (features-admin)

## Summary

Made the exported `StateBadge` presentation responsive without changing its props,
text, glyph, exported names or behaviour. It now reads the existing `useBreakpoint`
hook, exposes the active band as `data-breakpoint`, and uses the existing
`.cluster` class from `apps/staff-web/src/theme/responsive.css` so the glyph and
state word flex-wrap and the long label wraps instead of clipping or forcing a
page-level horizontal scroll; the original inline gap/presentation is preserved.
Added a new jsdom responsive
test that mocks `matchMedia` for compact, medium and expanded bands and asserts
structure, labels/roles, keyboard order, the supported widths and the RTL case.

Acceptance mapping:

| Requirement | Acceptance | Evidence |
|---|---|---|
| WEB-07 | Reflow at 320–1280 px with no page-level horizontal scroll; no clinical value hidden; responsive suites use the existing stylesheet classes and the breakpoint hook | `.cluster` + `useBreakpoint` in `StateBadge.tsx`; width-map and band tests in the new test file; `MixedScriptText` keeps `white-space: normal`, `overflow-wrap: anywhere` |
| UX-01 | Long English/Arabic wrap, logical properties only, shape+word cue retained, keyboard order unchanged, RTL mirrors without a direction override, 200%-zoom-safe (no fixed height/nowrap) | per-band, RTL, keyboard-order and long-string assertions; full gate green |

## Affected requirements

WEB-07, UX-01 (decision `docs/decisions/0004-responsive-staff-web.md`,
`docs/design/responsive-layout.md` §5 badge row / status chips, §11 RTL, §12 zoom).

## Risk class

Routine — contained UI presentation change (docs/WORKFLOW.md). One implementation
specialist area (React/TypeScript) and targeted tool evidence.

## Review owner

Senior React and Vite (`aurevia-senior-react` skill loaded); strict typing
checked with `aurevia-senior-typescript`; loop followed with
`aurevia-delivery`. A bounded React-specialist review was dispatched and
returned **no correctness findings**; its only note was a minor test-isolation
nit (RTL document attributes not restored in the new test's own `afterEach`),
which was fixed. The reviewer ran no commands, so its verdict is an advisory
review, not independent test evidence.

## Data changes

None. No migration, schema, backfill, lock or volume impact.

## Authorization boundaries and audit events

None. The change is presentation-only; no tenant, role, patient-link or audit
behaviour changed. No PHI, credential or real identifier used — all labels are
synthetic status text.

## Migration and rollout considerations

None. No contract, API, build script or stylesheet changed.

## Test evidence

Evidence source: executed tool commands (primary), plus bounded React-specialist
review. No simulated user or human participant for this presentation-only story.

- Red first (new test against unchanged component):
  `cd apps/staff-web && ./node_modules/.bin/vitest run tests/responsive/features-admin/StateBadge.responsive.test.tsx --reporter=dot`
  → **6 failed | 4 passed (10)**; failure `expected null to be 'compact'` —
  `data-breakpoint` / `.cluster` absent.
- Green, targeted (new test + existing consumer test):
  `cd apps/staff-web && ./node_modules/.bin/vitest run tests/responsive/features-admin/StateBadge.responsive.test.tsx src/features/admin/admin-components.test.tsx --reporter=dot`
  → **2 passed (2), 36 passed (36)**.
- Final full required gate on the final tree: `cd apps/staff-web && bash check.sh`
  → `FINAL_CHECK_EXIT=0`; all stages passed;
  `Test Files 159 passed (159)`, `Tests 1901 passed (1901)`,
  `No files with missing coverage. 207 files fully covered. 4 empty files skipped.`
  (The `useTheme must be used inside ThemeProvider` console lines are expected
  output from `tests/theme/ThemeProvider.test.tsx` negative cases; all tests pass.)
- Typecheck final lines (mandatory frontend gate):
  `cd apps/staff-web && npm run typecheck`
  ```
  > aurevia-staff-web@0.1.0 typecheck
  > tsc --noEmit -p tsconfig.json

  TYPECHECK_EXIT=0
  ```
  No `error TS…`. Also observed: `npm run gen:api:check` exit 0, `npm run lint` exit 0.
- Branch reconciliation: `git merge --no-edit origin/main` → "Already up to date"
  at start and after implementation; no new conflict markers introduced.
- Root `bash scripts/check.sh` (environment note, known false positive
  OPS-CW-003-01): exits 1 with `check-pack: opencode.json is stale: run
  scripts/gen-opencode.py`. Evidence: `git ls-files -v opencode.json` → `S`
  (skip-worktree), the worktree file is the CI runtime profile installed by
  `agent-run.sh`, while `git show HEAD:opencode.json` is the committed local
  profile (md5 differs from the worktree copy). `python3 -I scripts/check-docs.py`
  → exit 0. No protected file was edited; the applicable canonical module gate
  (`apps/staff-web/check.sh`) passed end to end. `scripts/check.sh` needs execute
  permission here, so it was run as `bash scripts/check.sh`.

Entry point → test trace: `StateBadge` is consumed by `ConfigEditor`,
`AuditEventList`, `StaffHistoryView` and `AccessStateNotice`
(`src/features/admin/`). The new test renders the exported component through the
same `ThemeProvider` + `I18nProvider` wrappers used in production with a synthetic
`matchMedia`; the pre-existing `admin-components.test.tsx` still exercises the
consumer render path.

## Coverage

Command: `cd apps/staff-web && bash check.sh` (Vitest v8, `perFile: true`, all
thresholds 100%). Result:
```
Statements   : 100% ( 3722/3722 )
Branches     : 100% ( 1892/1892 )
Functions    : 100% ( 1258/1258 )
Lines        : 100% ( 3556/3556 )
```
StateBadge.tsx introduced no new branch (one hook call plus three attributes and
a `className`). Every new line is exercised by the 10 new cases across compact,
medium and expanded bands, the width map, keyboard order, live-band change and
RTL/long-string assertions. Existing `StateBadge` tests were not weakened, skipped
or excluded; no threshold or exclusion was changed.

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association,
prescribing or critical-result logic touched. The state word and shape-distinct
glyph are unchanged at every band, so colour is not the only cue.

## Findings

- `responsive.css` has no dedicated badge / status-chip or clinical-value class.
  Reused the existing `.cluster` rule (flex-wrap, logical gap, child
  `min-inline-size: 0`) and the logical wrapping already in `MixedScriptText`;
  no new CSS was added and the stylesheet was not edited.
- Pre-existing, out of scope: committed conflict markers exist in HEAD in
  unrelated Java files (`apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7ParseException.java`,
  `Hl7Parser.java`, `Hl7Segment.java`). `git merge --no-edit origin/main` reported
  "Already up to date" — these are not fr
