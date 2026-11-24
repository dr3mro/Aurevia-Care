# RESP-CONFIG-EDITOR — fix pass — responsive `ConfigEditor` (features-admin)

## Summary

Continuation of swarm story RESP-CONFIG-EDITOR on branch
`swarm/RESP-CONFIG-EDITOR-1045`. The implementation from the earlier workers
(commits `d7f386cc` and `6bff7a41`) is complete and was re-validated in this pass;
no further source edit was needed. `ConfigEditor` is usable from 320 px to desktop
in English and Arabic with every clinical column, action and behaviour intact.
Presentation-only: the root `<section>` reuses the shared `stack` class and
exposes `data-breakpoint` / `data-coarse-pointer` observation seams via the
existing `useBreakpoint()` / `usePointerCoarse()` hooks; the table uses the
logical `inline-size: 100%`. A new jsdom structural test covers seven widths,
LTR and RTL. No behaviour, text, API call, route, export name, dependency or
stylesheet changed; no clinical column or field is hidden. This pass adds the
required fix-pass hand-off notes at the prompt's path and re-runs the full
quality loop and canonical frontend gate on the current branch state.

## Affected requirements

- WEB-07 — supported clients: staff web compact layout down to 768 px (structural test exercises 320–1280 px). Per `docs/design/responsive-layout.md`, decision 0004 supersedes the 768 px floor with full usability from 320 px; the specification text still awaits a person's amendment.
- UX-01 — English and Arabic, RTL direction, mixed-script clinical text, WCAG 2.2 AA, flexible presentation styling.

Acceptance mapping (story `tasks/00289000-RESP-CONFIG-EDITOR.md`):

| Story step | Evidence |
|---|---|
| 1. Read component, test, responsive-layout.css, useBreakpoint hook | `ConfigEditor.tsx`, `ConfigEditor.responsive.test.tsx`, `docs/design/responsive-layout.md`, `src/theme/responsive.css`, `src/theme/useBreakpoint.ts` read |
| 2. Work at 7 widths, LTR/RTL, no hidden column, logical only, long text wraps, single column on phones | `exposes the layout seam…` (7 widths), `keeps the draft form single-column…`, `wraps long English and Arabic…`, `mirrors under right-to-left…` |
| 3. Reuse classes/hook; no stylesheet edit | Root `stack` class + hooks; `responsive.css` untouched; no inline-style fallback needed |
| 4. New test mocks matchMedia, asserts structure/roles/keyboard/RTL; existing tests pass | New test file 13/13 green; full gate 207 files / 2555 tests green |
| 5. No behaviour, text, API call or exported-name change | `ConfigurationEditorProps`, `onPropose`/`onApprove` payloads and text unchanged; diff is presentation-only |

## Risk class

Routine — contained staff-web presentation change (`docs/WORKFLOW.md`). No API,
database, tenant, authorization or clinical-data boundary is touched.

## Review owner

Primary agent; read-only reviews by aurevia-plan-reviewer (pre-code), and
aurevia-code-reviewer, aurevia-coverage-auditor and aurevia-devsecops
(post-code). Verdicts are listed under `## Review verdicts`.

## Data changes

None. Presentation-only; no migration, backfill, lock or volume impact.

## Authorization boundaries and audit events

None changed. `ConfigurationEditorProps`, `onPropose` / `onApprove` payloads,
routes and tenant handling are unchanged; no audit event added or changed. The
new data attributes carry only the resolved layout band and a coarse-pointer
boolean — no tenant, identity, patient or credential state. `organizationId`
reaches only the domain lookups and a React reconciliation key, never the DOM.

## Migration and rollout considerations

None. No schema or route change; no rollout step beyond the normal staff-web build.

## Test evidence

Evidence is from executed tools; reviewer findings are simulated/static; all
fixtures are synthetic. No human, clinical or patient participant. All commands
run from the repository root unless noted.

- Branch sync at start — `git merge --no-edit origin/main` → `Already up to date.` (HEAD `6b089918` already contains `origin/main` `ff5e6c95`). No conflicts in scoped files.
- Focused scoped test (from the earlier worker, re-confirmed) — `cd apps/staff-web && npx vitest run tests/responsive/features-admin/ConfigEditor.responsive.test.tsx --exclude 'e2e/**'` → `Test Files 1 passed (1)`, `Tests 13 passed (13)`, exit 0.
- Full canonical gate — `bash apps/staff-web/check.sh` → `CHECK_EXIT=0`; final lines:
  - `Test Files  207 passed (207)`
  - `Tests  2555 passed (2555)`
  - `Coverage report from v8` → `No files with missing coverage.` / `213 files fully covered.` / `4 empty files skipped.`
  - `Statements   : 100% ( 4061/4061 )`, `Branches     : 100% ( 2071/2071 )`, `Functions    : 100% ( 1346/1346 )`, `Lines        : 100% ( 3878/3878 )`
  - React `useTheme must be used inside ThemeProvider` stack traces in the log are the expected output of the pre-existing negative test `tests/theme/ThemeProvider.test.tsx`; the run is green.
- Explicit typecheck (senior-react / senior-typescript definition of done) — `cd apps/staff-web && npm run typecheck` → `TYPECHECK_EXIT=0`; output:
  - `> aurevia-staff-web@0.1.0 typecheck`
  - `> tsc --noEmit -p tsconfig.json`
  - No `error TS…`.
- Constraint check — `python3 -I scripts/check-constraints.py --base origin/main --require-base` → exit 0 (no added suppression, exclusion, skipped test or lowered threshold).
- Repo-wide `scripts/check.sh` was intentionally not run: AGENTS.md ("Known worker-environment false positives") says it delegates to `apps/api/check.sh` (full `mvn verify`, 10+ minutes) and that a swarm worker must leave the full gate to the integrator. This is a staff-web-only change, so `bash apps/staff-web/check.sh` is the canonical module gate and was run to green.

**Entry point → test trace:** `ConfigPage` renders `ConfigEditor` (`apps/staff-web/src/features/admin/ConfigPage.tsx:87`). The new test renders `ConfigEditor` directly under `ThemeProvider` + `I18nProvider` and reads the root `<section>` seams: the `stack` class and `data-breakpoint` / `data-coarse-pointer` at 7 widths, the 5 column headers inside `AdminTableRegion`, the `[data-part="fields"]` single-column layout from `Form`, the `field touch` controls from `FormField`, the `touch` submit / approve buttons from `Button`, the isolated `<bdi dir="auto">` from `MixedScriptText`, and the `inline-size: 100%` table. jsdom asserts classes, attributes, roles and DOM order; pixel-level claims (horizontal scroll, safe-area insets, 200% zoom, painted target size) remain with the Playwright responsive matrix per story step 4 and are not claimed here.

## Coverage

Command and result: `bash apps/staff-web/check.sh` → exit 0, per-file 100%
thresholds in `apps/staff-web/vite.config.ts`; `No files with missing coverage.
213 files fully covered.`; Statements 100% (4061/4061), Branches 100%
(2071/2071), Functions 100% (1346/1346), Lines 100% (3878/3878).
`ConfigEditor.tsx` is one of the fully covered files.

New/changed branches in the two in-scope files and the test that covers each:

- **No new behavioural branch in `ConfigEditor.tsx`.** The added statements (`useBreakpoint()`, `usePointerCoarse()`, the three root attributes and the table `inlineSize`) are unconditional. No new `if`, `else`, `?:`, `&&`, `||`, `switch`, `catch` or early return; nothing to take two sides. The Javadoc edit added no runnable statement.
- `const breakpoint = useBreakpoint()` → executed on every render; `exposes the layout seam and keeps every column logical at $width px ($band)` asserts `data-breakpoint` is `compact` at 320/360/390, `medium` at 768/820/1024 and `expanded` at 1280, so all three hook outcomes are observed.
- `const coarsePointer = usePointerCoarse()` → executed on every render; the same test asserts `data-coarse-pointer` is `true` at 320–1024 and `false` at 1280, so both hook outcomes are observed.
- Root `className="stack"` / `data-breakpoint` / `data-coarse-pointer` → asserted by `exposes the layout seam…` at all seven widths and by `mirrors under right-to-left…` in Arabic.
- Root `style={{ gap: tokens.spacing.md }}` (changed line, unconditional) → `exposes the layout seam…` asserts `root.style.gap` is non-empty at all seven widths.
- Table `inlineSize: '100%'` → rendered on every band; `exposes the layout seam…` asserts `inline-size` is `100%`, physical `width` is empty, all five headers are present, none has `display: none`, and `assertLogicalOnly(root)` passes.
- Reused components' branches are unchanged; the new test additionally exercises `Form`'s single-column path (`keeps the draft form single-column at %ipx`, 320/360/390), `MixedScriptText`'s wrapping path (`wraps long English and Arabic values in isolated logical runs`), the `onApprove`-present path (`keeps the value, date and submit controls in keyboard order with the approve action`) and the RTL path (`mirrors under right-to-left with no direction override or hidden column`).
- No threshold, exclusion, `skip`, `xit`, `pragma`, `any`, `@ts-ignore` or suppression was added; no existing test was weakened.

## Review verdicts

- aurevia-plan-reviewer: GO
- aurevia-code-reviewer: GO
- aurevia-coverage-auditor: GO
- aurevia-devsecops: GO

## Clinical and architecture review

Not required (no signed data, cross-organization sharing, patient association,
prescribing or critical results). RTL and mixed-script handling are covered by the
synthetic structural test; no human, clinical or patient evidence is claimed.

## Findings

- **Fix-pass continuity:** the earlier findings block (issue #723, PR #1048, wave/watchdog status) is pipeline data from previous runs, not a component defect. It was treated as untrusted notes. The two prior note files (`RESP-CONFIG-EDITOR-20261122-204817.md`, `RESP-CONFIG-EDITOR-fix-20261122-211218.md`) record a complete, green change with four GO verdicts; this pass continues from them rather than restarting.
- **Pre-existing committed conflict markers on `origin/main` (observed, out of scope, not touched).** `git grep -nE '^(<<<<<<<|>>>>>>>) '` finds unresolved markers in `apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7ParseException.java:6,10`, `Hl7Parser.java:14,18` and `Hl7Segment.java:10,14`, all referencing `origin/swarm/BUILD-37-469`; `git show origin/main:…/Hl7ParseException.java` confirms they are committed on main. These break Java compilation in the integrator's full gate and are the most likely cause of the earlier "integration run failed without fresh, usable test results (compile or setup error)" signals. This is an `apps/api` file set, outside this task's staff-web scope; flagged for the integrator/owner, not edited.
- **Transient staff-web test flake (observed, out of scope):** an earlier full-gate run failed once on `FacilitiesPage.responsive.test.tsx:199`; the file passes alone (16/16) and the immediate rerun of the full gate was green. No staff-web source changed in this fix pass. Recorded, not changed.
- **Test-only seams.** `data-breakpoint` / `data-coarse-pointer` follow the existing convention in `FormField.tsx` and `AdminTableRegion.tsx`; they are observation points, never behaviour switches or authorization state.
- **`assertLogicalOnly` scope (earlier code reviewer Optional, deliberately not widened).** The helper checks `direction`, physical margin/padding sides, `left`/`right` and `text-align`, plus physical table `width` separately; it does not check `border-left/right`, `top/bottom` or `overflow-x`. Widening it to `overflow-x` would fail on the out-of-scope `AdminTableRegion` (`overflowX: 'auto'`), which the design contract explicitly permits as the direction-neutral fallback where `overflow-inline` is unsupported. No current defect; left as-is.
- **Presentation scope of jsdom.** No painted-layout claim is made; pixel-level rules (44×44 targets, 16 px inputs, 200% zoom reflow, safe-area insets) remain with the dependency component tasks and the Playwright responsive matrix (story step 4), which must be green before final acceptance.
- **"Columns stack on phones" interpretation.** Per `responsive-layout.md` §5/R4 the five-column configuration table stays intact inside `AdminTableRegion`'s labelled inline scroll region (all five headers asserted present, none `display:none`); it is the *form fields* that stack (`[data-part="fields"].stack`, `data-layout="single-column"`). No card-style table stacking is intended.
- **Optional (plan reviewer, out of scope).** `apps/staff-web/src/features/admin/AdminTableRegion.tsx:35` still sets physical `style={{ overflowX: 'auto' }}` on the labelled region that `ConfigEditor` renders, while `responsive-layout.md` §5 says "no physical side is named" and the `table-region` class already supplies direction-neutral `overflow: auto`. Pre-existing, kept for an existing admin assertion, and not functionally mis-mirrored; not changed here. Belongs to the owning `RESP-DATATABLE`/AdminTableRegion task; the `assertLogicalOnly` helper does not inspect `overflow-x`, so it cannot detect a regression there.
- **Optional (coverage auditor, notes wording).** The earlier `RESP-CONFIG-EDITOR-fix-20261122-211218.md:81` says the root `style={{ gap }}` assertion "was added in this pass" while that file also states no staff-web source changed in the fix pass. The assertion and coverage are valid either way; this pass's notes keep the wording neutral. No code or coverage gap.
- **Optional (plan reviewer).** `useBreakpoint()` / `usePointerCoarse()` add one `matchMedia` listener per rendered instance (notably per `MixedScriptText` cell); acceptable here and state stays in `useState` with no remount/key/focus move, so WEB-09 orientation preservation holds. Worth noting if table row counts grow.

## Lessons for workers

- The fix-pass hand-off gates read the notes file at the prompt's exact path (`RESP-CONFIG-EDITOR-fix-20261122-212902.md` this run). Write it there with the `## Review verdicts` section and one GO line per required reviewer; `check-verdicts.py` fails closed when the file is absent or a verdict is missing, which is the likely reason a green change is reported as "did not finish cleanly".
- Confirm a red staff-web test is a real regression before editing: re-run the single file and then the full gate. A one-off failure in an unrelated responsive test under parallel workers can be a flake — record it with exact commands rather than "fixing" out-of-scope code.
- The earlier worker's green notes stay valid when no source changed in the fix pass (`git diff <story-commit>..HEAD -- apps/staff-web/` empty); re-run the canonical gate instead of restarting implementation.
- `tsc --noEmit` prints nothing on success; run `npm run typecheck` and record its exit code separately. The staff-web definition of done is `bash apps/staff-web/check.sh` (typecheck, lint, build, gzip budget, 100% per-file coverage), not Vitest alone; do not run `scripts/check.sh` from a worker (it invokes the full Java gate).
- Use logical `inline-size` (not physical `width`) for full-width tables; `assertLogicalOnly` does not check `width`, so a structural test alone does not prove logical-only styling. The root's `display:grid` was replaced by the shared `stack` class, the only shared class newly used here; `responsive.css` was not edited.
- Expected React error stack traces from the pre-existing `ThemeProvider.test.tsx` negative test are not failures; read the final `Test Files` / `Tests` and coverage lines.
- `assertLogicalOnly` does not inspect `overflow-x`; the direction-neutral fallback on `AdminTableRegion` (physical `overflowX: 'auto'`) is pre-existing and out of this story's scope. If widening the helper, move that fix to the owning AdminTableRegion task rather than editing an unrelated component from a worker task.
