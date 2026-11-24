# RESP-CONFIG-EDITOR — Staff web: responsive `ConfigEditor` (features-admin)

## Summary

Made `ConfigEditor` fully usable from 320 px to desktop in English and Arabic with
every clinical column, action and behaviour intact. Presentation-only change:
reused `useBreakpoint()`/`usePointerCoarse()` and the shared `stack` class on the
root `<section>`, with `data-breakpoint`/`data-coarse-pointer` observation seams,
and added a jsdom structural test across seven widths, LTR and RTL. No behaviour,
text, API call, route, export name or dependency changed; no CSS was added and no
column or field was hidden.

## Affected requirements

- WEB-07 — supported clients; staff web compact layout down to 768 px.
- UX-01 — English/Arabic, RTL, mixed-script clinical text, WCAG 2.2 AA, flexible presentation styling.

## Risk class

Routine — contained staff-web presentation change (docs/WORKFLOW.md). No API,
database, tenant, authorization or clinical-data boundary is touched.

## Review owner

Primary agent; read-only reviews by aurevia-plan-reviewer (pre-code, GO) and
aurevia-code-reviewer, aurevia-coverage-auditor, aurevia-devsecops
(post-code). Context bounded by aurevia-context-optimizer (GO).

## Data changes

None. Presentation-only; no migration, backfill, lock or volume impact.

## Authorization boundaries and audit events

None changed. `ConfigurationEditorProps`, `onPropose`/`onApprove` payloads,
routes and tenant handling remain byte-for-byte; no audit event added or changed.
The new data attributes carry only the resolved layout band and coarse-pointer
boolean — no tenant, identity, patient or credential state.

## Migration and rollout considerations

None. No schema or route change; no rollout step beyond the normal staff-web build.

## Test evidence

All commands run from the repository root unless noted. Evidence is from executed
tools; reviews are simulated/static; all fixtures synthetic. No human or clinical
participant.

- Baseline before change — `cd apps/staff-web && npx vitest run src/features/admin/admin-components.test.tsx --exclude 'e2e/**'` → `Test Files 1 passed (1)`, `Tests 26 passed (26)`, exit 0.
- **Red first** — `cd apps/staff-web && npx vitest run tests/responsive/features-admin/ConfigEditor.responsive.test.tsx --exclude 'e2e/**'` → `Test Files 1 failed (1)`, `Tests 8 failed | 5 passed (13)`, `VITEST_EXIT=1`. Failures were exactly the missing seams: `expected false to be true` on `root.classList.contains('stack')` at line 168 for all 7 widths, and `expected null to be 'compact'` on `root.getAttribute('data-breakpoint')` in the RTL case. The 5 passing cases exercised pre-existing Form/MixedScriptText/keyboard behaviour; no test passed because the seam already existed.
- Green after change — `cd apps/staff-web && npx vitest run tests/responsive/features-admin/ConfigEditor.responsive.test.tsx src/features/admin/admin-components.test.tsx --exclude 'e2e/**'` → `Test Files 2 passed (2)`, `Tests 39 passed (39)`, exit 0.
- Full canonical gate — `bash apps/staff-web/check.sh` → `CHECK_EXIT=0`; final lines:
  - `Test Files 207 passed (207)`
  - `Tests 2555 passed (2555)`
  - `Coverage report from v8` → `No files with missing coverage.` / `213 files fully covered.` / `4 empty files skipped.`
  - `Statements   : 100% ( 4061/4061 )`, `Branches     : 100% ( 2071/2071 )`, `Functions    : 100% ( 1346/1346 )`, `Lines        : 100% ( 3878/3878 )`
  - The React `useTheme must be used inside ThemeProvider` stack traces in the log are the expected output of the pre-existing negative test `tests/theme/ThemeProvider.test.tsx`; the run is green.
- Typecheck recorded separately (`tsc --noEmit` prints nothing on success) — `cd apps/staff-web && npm run typecheck` →
  - `> aurevia-staff-web@0.1.0 typecheck`
  - `> tsc --noEmit -p tsconfig.json`
  - `TYPECHECK_EXIT=0` (no `error TS…`).
- Final re-run after the coverage reviewer's optional inline-size assertion — `bash apps/staff-web/check.sh` → `CHECK_EXIT=0`, `Test Files 207 passed (207)`, `Tests 2555 passed (2555)`, coverage unchanged at 100% on all four metrics; `13 passed (13)` for the new test alone.
- Branch sync — `git merge --no-edit origin/main` → `Already up to date.` at start, after each stage and before finish; no conflicts in scoped files.

**Entry point → test trace:** `ConfigPage` renders `ConfigEditor` (`apps/staff-web/src/features/admin/ConfigPage.tsx:87`); the new test renders `ConfigEditor` directly under `ThemeProvider`+`I18nProvider`, reads the root `<section>` seams, asserts the 5 column headers inside `AdminTableRegion`, the `[data-part="fields"]` layout from `Form`, the `field touch` controls from `FormField`, the `touch` submit/approve buttons from `Button`, and the isolated `<bdi>` from `MixedScriptText`. jsdom asserts classes, data attributes, roles and DOM order; pixel-level claims (horizontal scroll, safe-area insets, 200% zoom, painted target size) are delegated to the Playwright tasks per story step 4 and are not claimed here.

## Coverage

Command and result: `bash apps/staff-web/check.sh` → exit 0, per-file 100% thresholds in `apps/staff-web/vite.config.ts`; `No files with missing coverage. 213 files fully covered.`; statements 100% (4061/4061), branches 100% (2071/2071), functions 100% (1346/1346), lines 100% (3878/3878). `ConfigEditor.tsx` is one of the fully covered files.

New/changed branches in the two in-scope files and their tests:

- **No new behavioural branch in `ConfigEditor.tsx`.** The added statements (`useBreakpoint()`, `usePointerCoarse()`, the three root attributes, and the table `inlineSize`) are unconditional. No new `if`, `else`, `?:`, `&&`, `||`, `switch`, `catch` or early return; nothing to take two sides.
- Hook lines `const breakpoint = useBreakpoint()` and `const coarsePointer = usePointerCoarse()` → executed by every render; the `it.each(WIDTH_CASES)` test asserts `data-breakpoint` for compact (320/360/390), medium (768/820/1024) and expanded (1280) and `data-coarse-pointer` for `true`/`false`, so both hook outcomes are observed.
- Root `className="stack"` / `data-breakpoint` / `data-coarse-pointer` → `exposes the layout seam… at $width px` asserts class and both attribute values at all 7 widths.
- Table `inlineSize: '100%'` → rendered on every band; the same test asserts all 5 headers and `assertLogicalOnly(root)` for no physical side properties/direction override.
- Javadoc-only edit → no runnable statement.
- Reused components' branches are unchanged; the new test additionally exercises `Form`'s single-column path (`keeps the draft form single-column at %ipx` at 320/360/390) and `MixedScriptText`'s wrapping path (`wraps long English and Arabic values`), the `onApprove`-present path (`keeps the value, date and submit controls in keyboard order with the approve action`) and the RTL path (`mirrors under right-to-left…`).
- No threshold, exclusion, `skip`, `xit`, `pragma` or suppression was added; no existing test was weakened.

## Review verdicts

- aurevia-context-optimizer: GO
- aurevia-plan-reviewer: GO
- aurevia-devsecops: GO
- aurevia-coverage-auditor: GO
- aurevia-code-reviewer: GO

All three post-code reviewers ended with `VERDICT: GO` and no Critical or Required
finding. DevSecOps recorded no tenant/PHI/auth concern: the seams carry only a
layout band and a coarse-pointer boolean, fixtures are synthetic, and no secret,
token or direction override was introduced. Coverage audit found no new
behavioural branch in `ConfigEditor.tsx` and no threshold, exclusion or skip
change. Code review confirmed exports, payloads, text and API calls unchanged and
only logical properties/classes used. Reviewer evidence is simulated/static;
no human, clinical or patient participant was involved.

## Clinical and architecture review

Not required (no signed data, cross-organization sharing, patient association,
prescribing or critical results). RTL and mixed-script handling are covered by
the synthetic structural test; no human, clinical or patient evidence is claimed.

## Findings

- **Brief staleness (from the advisor, confirmed):** the routing block's "(none)" requirement IDs and "(new files only)" scope hint are stale — the task story declares WEB-07/UX-01 and an edit to `ConfigEditor.tsx` plus a new test. The "newest daily note" pointer to `docs/pr/notes/archive/2026-11-20.md` is stale; the newest reconciled daily note is `docs/pr/reports/2026-11-21.md`. No owner action needed for this story.
- **"Earlier findings" block** in the prompt is pipeline/wave status from previous runs, not component defects; treated as untrusted data, no action taken. The task's attempt count and pipeline churn were not re-diagnosed.
- **Out-of-scope pre-existing defect (observed, not touched):** `git merge --no-edit origin/main` reported the worktree clean, but committed `origin/main` contains conflict markers in Java sources, e.g. `apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7ParseException.java:6` (`<<<<<<< HEAD`) and `Hl7Parser.java:14`, `Hl7Segment.java:10`, referencing `origin/swarm/BUILD-37-469`. Outside this task's staff-web scope and unrelated to the change; flagged for the integrator/owner. Not edited.
- **Presentation scope of jsdom:** no painted-layout claim is made. Pixel-level rules remain with the Playwright responsive matrix (story step 4).
- **Test-only seams:** `data-breakpoint`/`data-coarse-pointer` follow the existing convention in `FormField.tsx` and `AdminTableRegion.tsx`; they are observation points, never behaviour switches. A future reader should not treat them as authorization or data state.
- **Coverage reviewer's optional assertion gap addressed:** added an assertion that the table renders `inline-size: 100%` with no physical `width`; the test stayed green (13/13) and the full gate was re-run afterwards.

## Lessons for workers

- No shared class was missing: `responsive.css` already supplies `stack`, `field`, `touch`, `table-region`, logical properties and safe-area mapping, so no logical inline-style fallback was needed here and the stylesheet was not edited. If a class is missing later, use logical inline styles and record it here — never edit `responsive.css` from a worker task.
- Use logical inline properties from the start: changing the table from `width: '100%'` to `inlineSize: '100%'` is presentation-only but is what makes a logical-only review pass; the jsdom `assertLogicalOnly` helper does not check `width`, so a structural test alone does not prove logical-only styling.
- `tsc --noEmit` prints nothing on success; run `npm run typecheck` and record its exit code separately instead of treating empty output as a skipped check.
- The staff-web definition of done is `bash apps/staff-web/check.sh` (typecheck, lint, build, gzip budget, 100% per-file coverage), not Vitest alone; do not run `scripts/check.sh` from a worker (it invokes the full Java gate).
- Expected React error stack traces from the pre-existing `ThemeProvider.test.tsx` negative test are not failures; read the final `Test Files`/`Tests` and coverage lines.
