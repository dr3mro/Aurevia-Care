# RESP-ADMIN-TABLE-REGION — Staff web: responsive `AdminTableRegion` (features-admin)

## Summary

`AdminTableRegion` now opts into the shared responsive stylesheet and exposes its resolved layout band: it renders with `className="table-region"` and `data-breakpoint={useBreakpoint()}`. Its `role="region"`, `aria-label`, `tabIndex={0}` and inline `overflowX: 'auto'` are unchanged, so the labelled keyboard-scrollable wrapper around every wide administration table behaves exactly as before at every width. A new responsive test drives the real `useBreakpoint` hook with a deterministic `matchMedia` stand-in at compact, medium and expanded, and asserts structure, labels, roles, keyboard order and right-to-left behaviour with synthetic English and Arabic data. No other files were changed; no dependency, tracker, stylesheet, workflow or script was touched.

## Affected requirements

- **WEB-07** — supported clients / compact staff layout. The wrapper uses the existing `.table-region` rule from `apps/staff-web/src/theme/responsive.css` (direction-neutral `overflow: auto`, logical focus ring and scroll padding) instead of any new CSS. The three mocked media-query bands cover 320–767 (compact), 768–1279 (medium) and 1280+ (expanded) px from `src/theme/breakpoints.ts`.
- **UX-01** — English and Arabic, RTL, accessibility (WCAG 2.2 AA). RTL test sets `dir="rtl"`, `lang="ar"`, uses an Arabic accessible name and long Arabic cells/action, and asserts no physical side, no direction override, no hidden column and preserved keyboard order. Long English and Arabic values remain present and unhidden at every band.

Acceptance mapping (task step 2): no horizontal page scroll → `.table-region` scrolls only inside the labelled region; long EN/AR text wraps and 200% zoom stays readable → no fixed block size or physical side added, wrapping/focus pixel proofs belong to the Playwright tasks (design guide §13); columns stack on phones → for a table the documented pattern is one labelled scroll region with every column present, not stacking (design guide §5); coarse pointers 44×44 → the row action keeps its `.touch` class (asserted); inputs 16 px → `.field` owns that rule and this wrapper contains no input; safe-area insets → `.page`/`.sheet` own them and this wrapper adds no edge padding; logical properties only → asserted; no clinical column hidden with `display: none` → asserted for every header and cell; table follows the DataTable pattern → same `.table-region` class, `role="region"`, `aria-label`, `tabIndex=0` and unhidden captioned table.

## Risk class

Routine — contained UI presentation change in one staff-web component plus a new test. No API, data, authorization or deployment boundary changed (`docs/WORKFLOW.md`).

## Review owner

`orevia-senior-react` (bounded review, simulated) and `orevia-ux-reviewer` (bounded review, simulated). Both were advisory subagent reviews; no code was edited by them. No human or clinical-participant review was available or claimed.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix needed. All fixtures are synthetic (`SYN-0001`, synthetic facility and Arabic strings); no patient data or credentials were read or written.

## Authorization boundaries and audit events

None changed. The wrapper presents data only; it adds no command, role check, tenant scope or audit event and does not alter any API call.

## Migration and rollout considerations

None. Presentation-only change; no contract, generated client or build script change. `gen:api:check` passed unchanged.

## Test evidence

Evidence source: **executed tool commands** (local shell/Vitest/Vitest coverage/typecheck/lint/build) and **simulated specialist reviewers** (subagent reports). No simulated-user, clinician or human-participant evidence.

Red stage (before the component change), `cd apps/staff-web && npx vitest run --exclude 'e2e/**' tests/responsive/features-admin/AdminTableRegion.responsive.test.tsx` → `Test Files 1 failed (1); Tests 5 failed | 6 passed (11)`; the five failures were the new `table-region` class and `data-breakpoint` assertions (lines 110/173/193).

Green stage, targeted regression:
`cd apps/staff-web && npx vitest run --exclude 'e2e/**' tests/responsive/features-admin/AdminTableRegion.responsive.test.tsx src/features/admin/admin-screen.test.tsx src/features/admin/admin-components.test.tsx tests/components/DataTable.responsive.test.tsx` → `Test Files 4 passed (4); Tests 51 passed (51)`. After the review-driven assertion additions: `npx vitest run --exclude 'e2e/**' tests/responsive/features-admin/AdminTableRegion.responsive.test.tsx src/features/admin/admin-screen.test.tsx` → `Test Files 2 passed (2); Tests 19 passed (19)`.

Final mandatory frontend definition of done (exit 0), `bash apps/staff-web/check.sh` — exact final lines:

```
> orevia-staff-web@0.1.0 gen:api:check
> bash scripts/gen-api-types.sh --check
✨ openapi-typescript 7.4.2
gen:api: generated client matches the contract

> orevia-staff-web@0.1.0 typecheck
> tsc --noEmit -p tsconfig.json

> orevia-staff-web@0.1.0 lint
> eslint . --max-warnings 0

> orevia-staff-web@0.1.0 build
> tsc --noEmit -p tsconfig.json && vite build
✓ built in 167ms

 Test Files  159 passed (159)
      Tests  1902 passed (1902)
No files with missing coverage.
```

Typecheck produced no `error TS…` (empty output in both the `typecheck` and `build` steps). Bundle-size gate passed as part of the same command.

Repository check, `bash scripts/check.sh`: exit 1 at `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. Verified environment artifact, not a change defect: `git ls-files -v opencode.json` → `S opencode.json` (skip-worktree), the worktree file equals `.github/opencode.ci.json`, and `git show HEAD:opencode.json` is the different committed local profile. Re-running with HEAD bytes temporarily in place (CI bytes restored byte-for-byte afterwards, verified with `cmp`) reached the Python tooling suite, which failed on two pre-existing environment tests (`test_worker_gate.MainTest.test_integration_failure_still_fails_the_gate` and `test_worker_gate_coverage.MainTest.test_maven_failure_with_failing_reports_still_fails`), both `FileNotFoundError: 'apps/api/target/surefire-reports'`; `FAILED (errors=2)` of 1118 tests. Neither involves staff-web code. No protected file was edited or committed.

Entry-point-to-test trace: a staff admin page renders `<AdminTableRegion label>` around a captioned table (`src/features/admin/{FacilitiesPage,StaffPage,ConfigPage,AuditEventList,ConfigEditor,StaffHistoryView}.tsx`) → `AdminTableRegion` calls `useBreakpoint()` (media queries from `src/theme/breakpoints.ts`) and renders `div.table-region[role=region][aria-label][tabindex=0][data-breakpoint]` → `tests/responsive/features-admin/AdminTableRegion.responsive.test.tsx` queries that region by role/name and asserts class, band, focus, table semantics and RTL structure; `src/features/admin/admin-screen.test.tsx` proves the unchanged `overflowX: 'auto'` wrapper still satisfies the existing admin-screen contract.

## Coverage

Command: `bash apps/staff-web/check.sh` → `npm run test:coverage` (`vitest run --coverage --exclude 'e2e/**'`, v8, `perFile: true`, 100% thresholds unmodified). Result: **100% on every touched file and the whole module** — `No files with missing coverage.`; `207 files fully covered`; Statements 100% (3722/3722), Branches 100% (1892/1892), Functions 100% (1258/1258), Lines 100% (3556/3556).

New branches introduced in `AdminTableRegion.tsx`: **none** (no `if`, ternary, `&&`, `||`, `switch` or `catch` added — the change is a hook call plus two attributes). Branches exercised through the touched component's hook call and their tests:

- `useBreakpoint` → `expanded` (both `min-width` queries match): `describe.each` case `expanded width` asserts `data-breakpoint === 'expanded'`.
- `useBreakpoint` → `medium` (only the 768 px query matches): case `medium width` asserts `data-breakpoint === 'medium'`; the resize test fires the medium query and re-asserts it.
- `useBreakpoint` → `compact` (neither query matches): case `compact width` and the RTL case assert `data-breakpoint === 'compact'`.
- The post-mount `setMatches(list.matches)` / change-listener path in `useMediaQuery`: the resize test fires the medium and expanded query listeners and asserts the same region's `data-breakpoint` updates without losing its table.

Existing branch and failure-path coverage in `useBreakpoint.ts` / `useMediaQuery` is owned by `src/theme/breakpoints.test.ts` and was not modified. No threshold, exclusion, `@Generated`, ignore comment or skipped test was added.

## Clinical and architecture review

Not required for this presentation-only change: no signed data, cross-organization sharing, patient association, prescribing or critical-result workflow was touched. Simulated UX and senior-React reviews found no accessibility regression and no blocking correctness defect.

## Findings

- **Decision (from simulated senior-React review F1):** the labelled region is kept at **all** bands. `DataTable` renders its region only on compact, but strict parity is impossible inside this scope: the frozen `src/features/admin/admin-screen.test.tsx:63-76` (outside declared scope) renders without a `matchMedia` stub, so `useBreakpoint` falls back to `expanded`, and it asserts a `tabindex=0` region with `style.overflowX === 'auto'`. Removing the wrapper would break an existing test and change behaviour, which the story forbids. The wrapper adopts DataTable's `.table-region` class and structure; at medium/expanded `overflow: auto` does not scroll when content fits, so the region is inert there. If strict structural parity is wanted, a separate story must update `admin-screen.test.tsx`. Recorded here as a decision for the integrator; no tracker file was edited (worker restriction).
- The inline `style={{ overflowX: 'auto' }}` is a physical property and redundant with `.table-region`'s `overflow: auto`, but it is pre-existing behaviour required by the frozen assertion, so it was preserved rather than removed. It has no RTL mirroring effect.
- The wrapper does not own cell-level wrapping, 16 px inputs, safe-area padding or 44 px controls; those belong to the child table/`.field`/`.touch`/`.page` classes and are covered by their own stories. jsdom cannot paint pixels, so pixel/zoom assertions stay with the Playwright responsive tasks (design guide §13).
- Pre-existing, out of scope and unrelated to this change: committed conflict markers in `apps/api/src/main/java/com/aurevia/exchange/hl7/{Hl7ParseException,Hl7Parser,Hl7Segment}.java` (present in `HEAD` before this work); the `opencode.json` skip-worktree profile artifact; two Python tooling tests that need `apps/api/target/surefire-reports`. None was modified.

## Lessons for workers

- Before making a shared wrapper "responsive by breakpoint", grep existing tests for the wrapper's structural assertions. A frozen test with no `matchMedia` stub resolves to `expanded`, so removing a wrapper at wide widths can silently break it; exposing the band as `data-breakpoint` while keeping the wrapper satisfies both structure checks and behaviour preservation.
- Where two patterns conflict (here DataTable's compact-only region vs an always-present admin region), keep the existing behaviour, adopt the shared class, and record the decision in the notes instead of editing an out-of-scope test.
- Reuse `tests/components/Button.responsive.test.tsx`'s `installMatchMedia` stand-in: one mutable answers map plus listener sets lets a single test prove both band resolution and a live resize, with no jsdom pixel claims.
- In this cloud worker, `scripts/check.sh` failing on `opencode.json` staleness is the CI profile under skip-worktree; verify with `git ls-files -v`, `git show HEAD:opencode.json` and `cmp` against `.github/opencode.ci.json`, restore the original bytes after any temporary swap, and never regenerate or commit that protected file.
