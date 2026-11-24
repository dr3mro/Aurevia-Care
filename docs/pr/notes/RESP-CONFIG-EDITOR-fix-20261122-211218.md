# RESP-CONFIG-EDITOR — fix pass — responsive `ConfigEditor` (features-admin)

## Summary

Continuation (fix attempt 1 of 3) of swarm story RESP-CONFIG-EDITOR. The change from
the first worker (commit `d7f386cc`) is complete and was re-validated in this pass:
`ConfigEditor` is usable from 320 px to desktop in English and Arabic with every
clinical column, action and behaviour intact. Presentation-only — the root
`<section>` reuses the shared `stack` class and exposes `data-breakpoint` /
`data-coarse-pointer` observation seams via the existing `useBreakpoint()` /
`usePointerCoarse()` hooks; the table uses the logical `inline-size: 100%`. A new
jsdom structural test covers seven widths, LTR and RTL. No behaviour, text, API
call, route, export name or dependency changed; no CSS was added and no column or
field was hidden. No further source edit was needed in this pass.

## Affected requirements

- WEB-07 — supported clients; staff web compact layout down to 768 px (tested to 320 px).
- UX-01 — English/Arabic, RTL, mixed-script clinical text, WCAG 2.2 AA, logical presentation styling.

## Risk class

Routine — contained staff-web presentation change (docs/WORKFLOW.md). No API,
database, tenant, authorization or clinical-data boundary is touched.

## Review owner

Primary agent; read-only reviews by aurevia-plan-reviewer (pre-code), and
aurevia-code-reviewer, aurevia-coverage-auditor and aurevia-devsecops
(post-code). Verdicts are listed under `## Review verdicts`.

## Data changes

None. Presentation-only; no migration, backfill, lock or volume impact.

## Authorization boundaries and audit events

None changed. `ConfigurationEditorProps`, `onPropose` / `onApprove` payloads,
routes and tenant handling are unchanged; no audit event added or changed. The new
data attributes carry only the resolved layout band and a coarse-pointer boolean —
no tenant, identity, patient or credential state. `organizationId` reaches only
the domain lookups and a React reconciliation key, never the DOM.

## Migration and rollout considerations

None. No schema or route change; no rollout step beyond the normal staff-web build.

## Test evidence

Evidence is from executed tools; reviewer findings are simulated/static; all
fixtures are synthetic. No human, clinical or patient participant. All commands
run from the repository root unless noted.

- Branch sync at start — `git merge --no-edit origin/main` → `Already up to date.` (HEAD `141f8eb0` already contains `origin/main` `98021d87`). No conflicts in scoped files.
- Focused scoped test — `cd apps/staff-web && npx vitest run tests/responsive/features-admin/ConfigEditor.responsive.test.tsx --exclude 'e2e/**'` → `Test Files 1 passed (1)`, `Tests 13 passed (13)`, exit 0. Re-run after the coverage auditor's optional gap assertion → `Test Files 1 passed (1)`, `Tests 13 passed (13)`, exit 0; the full-gate numbers below are from the final rerun after that test edit.
- Full canonical gate — `bash apps/staff-web/check.sh` → `CHECK_EXIT=0`; final lines:
  - `Test Files 207 passed (207)`
  - `Tests 2555 passed (2555)`
  - `Coverage report from v8` → `No files with missing coverage.` / `213 files fully covered.` / `4 empty files skipped.`
  - `Statements : 100% ( 4061/4061 )`, `Branches : 100% ( 2071/2071 )`, `Functions : 100% ( 1346/1346 )`, `Lines : 100% ( 3878/3878 )`
  - Stack traces mentioning `useTheme must be used inside ThemeProvider` are the expected output of the pre-existing negative test `tests/theme/ThemeProvider.test.tsx`; the run is green.
- Explicit typecheck (senior-react / senior-typescript definition of done) — `cd apps/staff-web && npm run typecheck` → `TYPECHECK_EXIT=0`; output:
  - `> aurevia-staff-web@0.1.0 typecheck`
  - `> tsc --noEmit -p tsconfig.json`
  - No `error TS…`.
- Repo-wide `scripts/check.sh` was intentionally not run: AGENTS.md ("Known worker-environment false positives") says it delegates to `apps/api/check.sh` (full `mvn verify`, 10+ minutes) and that a swarm worker must leave the full gate to the integrator. This is a staff-web-only change, so `bash apps/staff-web/check.sh` is the canonical module gate and was run to green; constraint check `python3 -I scripts/check-constraints.py --base $(git rev-parse d7f386cc^) --require-base` → exit 0.
- Flake investigation (recorded, not a defect of this change): the first `check.sh` invocation exited 1 with a single failure in the unrelated `tests/responsive/features-admin/FacilitiesPage.responsive.test.tsx:199` (`Unable to find an accessible element with the role "rowheader" and name "MAIN"`, empty `<tbody>`). That file passes in isolation (`npx vitest run tests/responsive/features-admin/FacilitiesPage.responsive.test.tsx --exclude 'e2e/**'` → `Test Files 1 passed (1)`, `Tests 16 passed (16)`, exit 0) and the immediate full-gate rerun was green (207/207, 2555/2555). No staff-web source changed between the first worker's green run and this pass (`git diff d7f386cc..HEAD -- apps/staff-web/` is empty). Treated as a transient parallel-worker flake, not changed here (outside scope).

**Entry point → test trace:** `ConfigPage` renders `ConfigEditor` (`apps/staff-web/src/features/admin/ConfigPage.tsx:87`). The new test renders `ConfigEditor` directly under `ThemeProvider` + `I18nProvider` and reads the root `<section>` seams: the `stack` class and `data-breakpoint` / `data-coarse-pointer` at 7 widths, the 5 column headers inside `AdminTableRegion`, the `[data-part="fields"]` single-column layout from `Form`, the `field touch` controls from `FormField`, the `touch` submit / approve buttons from `Button`, the isolated `<bdi dir="auto">` from `MixedScriptText`, and the `inline-size: 100%` table. jsdom asserts classes, attributes, roles and DOM order; pixel-level claims (horizontal scroll, safe-area insets, 200% zoom, painted target size) remain with the Playwright tasks per story step 4 and are not claimed here.

## Coverage

Command and result: `bash apps/staff-web/check.sh` → exit 0, per-file 100% thresholds in `apps/staff-web/vite.config.ts`; `No files with missing coverage. 213 files fully covered.`; Statements 100% (4061/4061), Branches 100% (2071/2071), Functions 100% (1346/1346), Lines 100% (3878/3878). `ConfigEditor.tsx` is one of the fully covered files.

New/changed branches in the two in-scope files and the test that covers each:

- **No new behavioural branch in `ConfigEditor.tsx`.** The added statements (`useBreakpoint()`, `usePointerCoarse()`, the three root attributes and the table `inlineSize`) are unconditional. No new `if`, `else`, `?:`, `&&`, `||`, `switch`, `catch` or early return; nothing to take two sides. The Javadoc edit added no runnable statement.
- `const breakpoint = useBreakpoint()` → executed on every render; `exposes the layout seam and keeps every column logical at $width px ($band)` asserts `data-breakpoint` is `compact` at 320/360/390, `medium` at 768/820/1024 and `expanded` at 1280, so all three hook outcomes are observed.
- `const coarsePointer = usePointerCoarse()` → executed on every render; the same test asserts `data-coarse-pointer` is `true` at 320–1024 and `false` at 1280, so both hook outcomes are observed.
- Root `className="stack"` / `data-breakpoint` / `data-coarse-pointer` → asserted by `exposes the layout seam…` at all seven widths and by `mirrors under right-to-left…` in Arabic.
- Root `style={{ gap: tokens.spacing.md }}` (changed line, unconditional) → `exposes the layout seam…` asserts `root.style.gap` is non-empty at all seven widths. This was added in this pass to close the coverage auditor's Optional assertion gap.
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

- **Pre-existing committed conflict markers on `origin/main` (observed, out of scope, not touched).** `git grep -nE '^(<<<<<<<|>>>>>>>) '` finds unresolved markers in `apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7ParseException.java:6,10`, `Hl7Parser.java:14,18` and `Hl7Segment.java:10,14`, all referencing `origin/swarm/BUILD-37-469`; `git show origin/main:…/Hl7ParseException.java` confirms they are committed on main. These would break Java compilation in the integrator's full gate and are the most likely cause of the earlier "integration run failed without fresh, usable test results (compile or setup error)" signals. This is an `apps/api` file set, outside this task's staff-web scope; flagged for the integrator/owner, not edited.
- **Transient staff-web test flake (observed, out of scope).** First full-gate run failed once on `FacilitiesPage.responsive.test.tsx:199`; the file passes alone (16/16) and the immediate rerun of the full gate was green. No staff-web source changed in this fix pass. Recorded, not changed.
- **Earlier findings block in the prompt** is pipeline/wave status from previous runs (issue #723 and PR #1048), not a component defect; treated as untrusted data. The numbered `{"violations": [{"file": "TODO.md", "rule": "tracker"}]}` signals concern tracker state owned by the integrator, not this scoped change.
- **Test-only seams.** `data-breakpoint` / `data-coarse-pointer` follow the existing convention in `FormField.tsx` and `AdminTableRegion.tsx`; they are observation points, never behaviour switches or authorization state.
- **`assertLogicalOnly` scope (code reviewer Optional, deliberately not widened).** The helper checks `direction`, physical margin/padding sides, `left`/`right` and `text-align`, plus physical table `width` separately; it does not check `border-left/right`, `top/bottom` or `overflow-x`. Widening it to `overflow-x` would fail on the out-of-scope `AdminTableRegion` (`overflowX: 'auto'`), which the design contract explicitly permits as the direction-neutral fallback where `overflow-inline` is unsupported. No current defect; left as-is.
- **Presentation scope of jsdom.** No painted-layout claim is made; pixel-level rules (44×44 targets, 16 px inputs, 200% zoom reflow, safe-area insets) remain with the dependency component tasks and the Playwright responsive matrix (story step 4), which must be green before final acceptance.
- **"Columns stack on phones" interpretation.** Per `responsive-layout.md` §5/R4 the five-column configuration table stays intact inside `AdminTableRegion`'s labelled inline scroll region (all five headers asserted present, none `display:none`); it is the *form fields* that stack (`[data-part="fields"].stack`, `data-layout="single-column"`). No card-style table stacking is intended.

## Lessons for workers

- Confirm a red staff-web test is a real regression before editing: re-run the single file and then the full gate. A one-off failure in an unrelated responsive test under parallel workers can be a flake (`FacilitiesPage.responsive.test.tsx:199` failed once, then passed alone and on rerun) — record it with exact commands rather than "fixing" out-of-scope code.
- The first worker's green notes are still valid when no source changed in the fix pass (`git diff <story-commit>..HEAD -- apps/staff-web/` empty); re-run the canonical gate instead of restarting implementation.
- `tsc --noEmit` prints nothing on success; run `npm run typecheck` and record its exit code separately. The staff-web definition of done is `bash apps/staff-web/check.sh` (typecheck, lint, build, gzip budget, 100% per-file coverage), not Vitest alone; do not run `scripts/check.sh` from a worker (it invokes the full Java gate).
- Use logical `inline-size` (not physical `width`) for full-width tables; `assertLogicalOnly` does not check `width`, so a structural test alone does not prove logical-only styling. The table's inline style is pre-existing (`width: '100%'` before this story) and was only converted to `inlineSize: '100%'` — no new inline style and no missing utility class, so `responsive.css` was not edited. The root's `display:grid` was replaced by the shared `stack` class, which is the only shared class newly used here.
- Expected React error stack traces from the pre-existing `ThemeProvider.test.tsx` negative test are not failures; read the final `Test Files` / `Tests` and coverage lines.
