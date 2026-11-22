# RESP-FORBIDDEN-PAGE — Staff web: responsive `ForbiddenPage`

## Summary

Made the `/forbidden` staff-web page adapt to the three responsive bands without changing its behaviour, text, API calls or exported name. `ForbiddenState` already renders a centred, safe-area-aware `.page` alert panel capped at 44rem; the page's single "Go home" action was a bare button outside any responsive row. The action now sits in a `.page` container capped to the same 44rem reading measure, inside a `stack` row on compact and a `cluster` row from medium upward, with a `.touch` wrapper and logical inline styles so long English and Arabic text wraps, coarse pointers keep the 44 px target from the stylesheet, and the layout mirrors under RTL. Width changes layout only: no text or action is added or removed.

Files changed (only the task scope):
- `apps/staff-web/src/features/system/ForbiddenPage.tsx`
- `apps/staff-web/tests/responsive/features-system/ForbiddenPage.responsive.test.tsx` (new)

## Affected requirements

- **WEB-07** — supported clients and responsive staff-web layout; the page now works in the compact, medium and expanded bands (320–1280 px matrix) rather than assuming a desktop-only action row.
- **UX-01** — English/Arabic RTL, logical properties, keyboard order, coarse-pointer targets and readable wrapping for the state view and its action.

## Risk class

Routine (docs/WORKFLOW.md): a contained staff-web presentation change to one page and its new view test. No data, contract, authorization or deployment boundary changed. Reused the existing responsive classes and hook; no new CSS or dependency.

## Review owner

Senior React and Vite specialist (skills `smartclinic-senior-react`, `smartclinic-senior-typescript` and `smartclinic-ux-reviewer` were loaded; a bounded review-only pass was requested from the senior-react specialist). The primary worker owns the edits and history.

## Data changes

None. No migration, backfill, table, lock or volume impact; no rollback or forward-fix.

## Authorization boundaries and audit events

None. `/forbidden` remains a data-free page for a role with no access; it still explains without revealing data and performs no API call. No tenant, role or patient-link scope changed and no audit event was added or changed.

## Migration and rollout considerations

None. Presentation-only change; no schema, contract or generated-client change. The responsive stylesheet and breakpoint hook already exist and were reused unchanged.

## Test evidence

Evidence source: **tool** (commands executed locally). Simulated specialist review evidence is labelled separately below. No human, clinical or patient-participant evidence is claimed.

- Red first: `cd apps/staff-web && npx vitest run tests/responsive/features-system/ForbiddenPage.responsive.test.tsx --exclude 'e2e/**'` — 8 tests, 1 failed (`expected null to be 'compact'` at the action-row `data-breakpoint` assertion) against the original component. The failure was caused by the missing responsive action row.
- Green after the change, same command plus the existing page test: `npx vitest run tests/responsive/features-system/ForbiddenPage.responsive.test.tsx tests/features/system/systemPages.test.tsx --exclude 'e2e/**'` → `Test Files 2 passed (2)`, `Tests 37 passed (37)`. (A pre-existing jsdom "Not implemented: navigation" console error comes from the existing `UnsupportedBrowserPage` test; it does not fail the run.)
- Full module gate (run twice — initially, and again after the two low-severity review refinements): `bash apps/staff-web/check.sh` → `Test Files 159 passed (159)`, `Tests 1899 passed (1899)`, then `No files with missing coverage.` / `207 files fully covered.` / `4 empty files skipped.` Exit 0. This gate ran generated-client drift, `tsc --noEmit`, ESLint (`--max-warnings 0`), the Vite production build, the WEB-08 bundle-size check and Vitest coverage.
- Targeted re-run after the refinements: `cd apps/staff-web && npx vitest run tests/responsive/features-system/ForbiddenPage.responsive.test.tsx --exclude 'e2e/**'` → `Test Files 1 passed (1)`, `Tests 8 passed (8)`.
- Repository frontend standards gate: `bash scripts/frontend-check.sh` → `frontend standards: policy and tool commands passed`, exit 0 (both frontend modules' `npm ci`/typecheck/lint/test, dependency audit, TypeScript-only policy and root Prettier/typecheck/lint/test).
- Documentation/secret/name policy for the new Markdown notes: `python3 -I scripts/check-docs.py`, `python3 -I scripts/check-secrets.py`, `python3 -I scripts/check-names.py` — all exit 0.
- `scripts/check.sh` was deliberately **not** run: it invokes `apps/api/check.sh` (full `mvn verify`, OWASP and SpotBugs) and `db/check.sh`, which the task instruction forbids running in a worker (over ten minutes; the integrator and Delivery run it). Its frontend portion was covered by `scripts/frontend-check.sh` above and the doc/secret/name checks.
- Typecheck final lines (required by the frontend definition of done), command `npm run typecheck` in `apps/staff-web`:
  ```
  > smartclinic-staff-web@0.1.0 typecheck
  > tsc --noEmit -p tsconfig.json

  typecheck exit: 0
  ```
  No `error TS…` output.

Entry point → test trace: `/forbidden` is registered in `apps/staff-web/src/features/system/routes.ts`; `AppShell` renders the matched route component inside `<main>`. The new test renders `ForbiddenPage` directly under `ThemeProvider` + `I18nProvider` with a stubbed `matchMedia`, asserting the `ForbiddenState` alert panel (`role="alert"`, `data-breakpoint`, `.page`, wrapped text), the action row (`stack` compact / `cluster` medium+expanded, `data-breakpoint`), the single native button (`role="button"`, "Go home" / "الصفحة الرئيسية", `.touch`, no inline size overrides), keyboard focus order, logical-only inline styles, Arabic `dir="rtl"` and navigation back to `/`. The existing `systemPages.test.tsx` case "registered route /forbidden resolves its page" still passes, confirming route wiring and unchanged text/behaviour.

Pixel measurements (320/360/390/768/820/1024/1280 px, 200% zoom, painted focus rings) are deliberately **not** claimed: jsdom cannot lay out pixels. The new test asserts classes, attributes and structure per the design contract §13; painted checks remain with the Playwright responsive tasks.

## Coverage

Command: `bash apps/staff-web/check.sh` (runs `npm run test:coverage`, V8 provider, `perFile: true`, 100% thresholds on lines/branches/functions/statements).

Result: `Statements 100% (3723/3723)`, `Branches 100% (1894/1894)`, `Functions 100% (1258/1258)`, `Lines 100% (3557/3557)`; "No files with missing coverage", 207 files fully covered.

Touched production file, from `apps/staff-web/coverage/coverage-summary.json`:
- `src/features/system/ForbiddenPage.tsx` — lines 5/5 (100%), branches 2/2 (100%), functions 2/2 (100%), statements 5/5 (100%).

New/changed branches and the test that takes each side:
- `breakpoint === 'compact' ? 'stack' : 'cluster'` (two branches):
  - `compact` → `stack`: test `stacks the action on phones and keeps it inline from the medium band up`, layout `compact`.
  - `medium` and `expanded` → `cluster`: same test, layouts `medium` and `expanded` (also asserted not to contain `stack`).
- The `onClick={() => navigate('/')}` handler: test `returns to the home page from the single action`.
- The alert panel, labels and roles at each band: `keeps the explanation and one labelled action at compact/medium/expanded width`.
- Logical-only styles and wrapping under RTL: `wraps long labels and uses only logical properties at every width` and `mirrors the Arabic page right to left without hiding any text`. After review, the new page wrapper (`row.parentElement`) was added to the logical-style assertion list and its `max-inline-size: 44rem` alignment and the absence of a forced width on the `.touch` wrapper are asserted in `stacks the action on phones and keeps it inline from the medium band up`.

No threshold, exclusion, ignore comment or skipped test was added or changed.

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association, prescribing or critical result is involved. Simulated UX review (skill `smartclinic-ux-reviewer`, applied to the change) confirmed the page keeps a single named action, preserves DOM focus order under RTL, wraps long English and Arabic strings and hides no text; a real Arabic-speaking clinician pass remains a separate launch gate and is not claimed here. A bounded, review-only `smartclinic-senior-react` specialist pass (evidence source: **simulated reviewer**, no gates run by the reviewer) returned "no blocking findings" for wrapping, logical properties, behaviour/text/export parity, ternary coverage, lint/types and accessibility; its two low-severity notes were applied (see Findings).

## Findings

- No defect found in the change. Both gates are green and coverage on the touched file is 100%.
- Simulated senior-react review notes, both fixed in this change: (L1) the action `.page` inherited the 1200 px reading cap instead of the state panel's 44rem, so the single button could stretch far wider than the alert text — added `maxInlineSize: '44rem'` and removed the forced `inlineSize: '100%'` from the `.touch` wrapper to match `StateAction` in `StateViews`; (L2) the logical-style test did not inspect the new page wrapper — added `row.parentElement` to the assertion list. No behaviour or text changed.
- Pre-existing, out-of-scope observation: `git grep -nE '^(<<<<<<<|>>>>>>>) '` reports committed conflict markers in three Java files under `apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7/` (`Hl7ParseException.java`, `Hl7Parser.java`, `Hl7Segment.java`). `git merge --no-edit origin/main` reported "Already up to date" and `git show HEAD:...` confirms the markers are already committed on the base branch, not introduced by this story. They were left untouched (outside scope); the integrator/owner should decide whether they need repair before a Java build.
- The pre-existing jsdom "Not implemented: navigation" console stack from `UnsupportedBrowserPage` is test noise, present before this change and non-failing.

## Lessons for workers

- `responsive.css` has `.page`, `.stack`, `.cluster` and `.touch` but no dedicated "state action row aligned to the state panel" class; per the story rule the stylesheet was not edited. I reused the existing classes and added only logical inline styles (`minInlineSize`, `overflowWrap`, `inlineSize`, `textAlign: 'start'`) plus `data-breakpoint`. If a future story adds such a class, it should own safe-area padding and the 44rem reading measure so pages do not repeat inline styles.
- Reuse `StateViews` as-is: `ForbiddenState` is already responsive, so the fix belongs in the page's action row, not in the shared component (which is outside this scope).
- In a jsdom responsive test, derive the layout row from the button's DOM ancestry (`button.parentElement.parentElement`) instead of adding a test-only selector; assert class/attribute/structure, never painted pixels.
- `npm ci` is needed before a first local Vitest run in a fresh worker; `apps/staff-web/check.sh` performs it automatically when `node_modules/.bin/vitest` is absent.
