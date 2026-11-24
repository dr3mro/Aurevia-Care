# RESP-ACCESS-STATE-NOTICE — fix pass (2026-11-22 09:12 UTC)

## Summary

Continued from the earlier findings (issue #711 / PR #710) instead of starting over. The implementation and its responsive test were already committed on this branch by the first worker (`327693f3 Make AccessStateNotice responsive with breakpoint-aware layout`) and were reviewed in fix attempt 1. **No production or test source change was needed in this pass.** I reconciled the branch with `origin/main`, installed the missing Node dependencies, re-ran every applicable gate on the current tree, confirmed 100% coverage for the touched file, established that the earlier "local checks: failed" verdict is the known `check-pack.py` CI-profile false positive, and wrote this hand-off at the exact notes path named in my prompt. The story is ready for the integrator to accept.

Scope respected: only the two files already in scope were changed by the implementation; this pass adds only this notes file (the required hand-off, committed with the work). No dependency, stylesheet, config, tracker, workflow or `.opencode` file was changed. No push, tag or `gh` call was made.

## Affected requirements

- **WEB-07** — supported clients / responsive staff web down to 320 px; no horizontal page scroll, long values wrap, columns stack on phones.
- **UX-01** — English and Arabic, RTL, logical properties, safe-area insets, keyboard/DOM order, no hidden clinical value, text readable at 200% zoom.

## Acceptance mapping

| Story detail | Evidence |
|---|---|
| 1. Read component, test, responsive layout doc, `responsive.css`, `useBreakpoint.ts` | Component/test read; `docs/design/responsive-layout.md` §1 and the `stack`/`cluster`/safe-area conventions followed; no stylesheet edit (no notice-surface class exists). |
| 2. Works 320–1280 px, LTR/RTL, no scroll/clip, long EN/AR wrap, stacking, logical properties, safe area, no hidden value, 200% zoom | `data-breakpoint` + `stack`(`compact`)/`cluster`(`medium`,`expanded`), `min-inline-size:0`, `overflow-wrap:anywhere`, logical `padding-block-*/padding-inline-*` with `max(space, var(--safe-*))`, no fixed block size — asserted by the responsive test at all three bands. Pixel checks remain with the Playwright tasks (jsdom cannot lay out pixels). |
| 3. Use existing classes/hook, no stylesheet edit, record missing class | Uses `useBreakpoint()` and existing `stack`/`cluster`; missing notice-surface class recorded under Lessons for workers. |
| 4. New test mocks matchMedia for compact/medium/expanded, asserts structure, labels, roles, keyboard order, RTL; existing tests pass | `AccessStateNotice.responsive.test.tsx` — 14 tests across three bands plus direction/continuity; targeted run 40/40 with `admin-components.test.tsx`; full suite 1905/1905. |
| 5. No behaviour/text/API/export change | `role` mapping, glyphs, message keys, props interface and export unchanged. |

## Risk class

Routine (docs/WORKFLOW.md): one contained staff-web presentation change with an added view-level test; no data, contract, authorization or workflow boundary crossed. `git merge --no-edit origin/main` reported `Already up to date.` at the start and the tree was clean before and after verification.

## Review owner

`aurevia-senior-react` (staff React/Vite), supported by `aurevia-senior-typescript` (strict TS) and `aurevia-ux-reviewer` (RTL/keyboard). A bounded read-only `aurevia-senior-react` review was routed again in this pass; its outcome is recorded under Clinical and architecture review and Findings. A previous bounded UX review (source: **reviewer, simulated**) found no blocking findings.

## Data changes

None. No migration, table, backfill, lock or rollout impact. Synthetic fixture label only (`Synthetic probe SYN-PROBE-01`); no patient data, credential or real identifier.

## Authorization boundaries and audit events

None. The component only displays a pre-existing synthetic `AccessState` (`permitted`/`denied`/`concealed`); its `role="alert"|"status"`, `data-access-state`, glyphs and message text are unchanged. No tenant, role or patient-link logic and no audit event was added or changed.

## Migration and rollout considerations

None. Presentation-only change to a display component already reachable from `AdminScreen.tsx`; no production deployment action.

## Test evidence

Evidence source: **executed tool commands** (git, npm, Vitest, tsc, ESLint, Vite build, bundle-size gate, V8 coverage, repository policy scripts) plus **reviewer findings** (bounded simulated specialist review); no human, clinical or simulated-user evidence.

- Reconcile — `git merge --no-edit origin/main` → `Already up to date.`; `git status --short` empty afterwards.
- Dependency setup — `cd apps/staff-web && npm ci --no-audit --no-fund` → `added 486 packages in 7s`, exit 0. (The worktree arrived with no `node_modules`; without this step every JS gate fails with `node_modules/.bin/vitest: No such file or directory`.)
- RED (recorded by the first worker) — `node_modules/.bin/vitest run tests/responsive/features-admin/AccessStateNotice.responsive.test.tsx` → `Test Files 1 failed (1)`, `Tests 11 failed | 3 passed (14)`; failures were exactly the missing `data-breakpoint`, `stack`/`cluster` class and logical safe-area padding. Not repeated: the committed implementation makes it green.
- Targeted + regression (re-run this pass) — `cd apps/staff-web && node_modules/.bin/vitest run tests/responsive/features-admin/AccessStateNotice.responsive.test.tsx src/features/admin/admin-components.test.tsx`, final lines:
  ```
  Test Files  2 passed (2)
       Tests  40 passed (40)
  ```
  exit 0.
- MANDATORY FRONTEND GATE (re-run this pass) — `bash apps/staff-web/check.sh` from the repo root (generated-client check, typecheck, lint, production build, WEB-08 bundle budget, 100%-coverage tests). All stages passed; final lines:
  ```
  gen:api: generated client matches the contract
  Test Files  159 passed (159)
       Tests  1905 passed (1905)
  No files with missing coverage.
  207 files fully covered.
  4 empty files skipped.

  =============================== Coverage summary ===============================
  Statements   : 100% ( 3722/3722 )
  Branches     : 100% ( 1894/1894 )
  Functions    : 100% ( 1258/1258 )
  Lines        : 100% ( 3556/3556 )
  ================================================================================
  ```
  exit 0. (The `useI18n/useTheme must be used inside …Provider` stack traces in the output come from the existing intentional fail-closed tests in `tests/i18n/I18nProvider.test.tsx` and `tests/theme/ThemeProvider.test.tsx`; the suite and coverage gate passed.)
- TYPECHECK (mandatory, standalone) — `cd apps/staff-web && npm run typecheck`, final lines (no `error TS…`):
  ```
  > aurevia-staff-web@0.1.0 typecheck
  > tsc --noEmit -p tsconfig.json
  ```
  exit 0 (no diagnostics).
- `bash scripts/frontend-typecheck-check.sh` → `frontend typecheck: passed (or no frontend files changed)`, exit 0.
- `bash scripts/coverage-check.sh` → `coverage check: passed` with the same 100% summary above, exit 0.
- Policy — `python3 -I scripts/check-docs.py`, `python3 -I scripts/check-secrets.py`, `python3 -I scripts/check-names.py` → each exit 0.
- `python3 -I scripts/check-pack.py` and `python3 -I scripts/gen-opencode.py --check` → exit 1, `opencode.json is stale`. **Known worker-profile artifact, not a defect of this change** (see Findings). Evidence: `git ls-files -v opencode.json` shows `S` (skip-worktree); the worktree file is the CI profile copied by `agent-run.sh` (`activate_ci_config`), while a byte-for-byte comparison of the generator output with `git show HEAD:opencode.json` reported `HEAD opencode.json IDENTICAL to generator` (exit 0 from `diff -q`). I then restored the CI profile from the saved copies so the worktree was left exactly as found; no protected file was edited or committed. This matches the closed false positive OPS-CW-003-01 in MEMORY.md.
- `bash scripts/check.sh` and `bash scripts/worker-check.sh` were **not** run: they invoke the full `mvn verify` / all integration tests, expressly forbidden in this worker prompt (over ten minutes) and outside this story's scope. The three HL7 Java files listed under Findings carry pre-existing committed conflict markers that would fail a Java build independently of this change.
- Entry-point-to-test trace: `AdminScreen.tsx` renders `<AccessStateNotice state label />` in the access-review section → the new test renders the same component under the real `ThemeProvider` + `I18nProvider` with a deterministic per-query `matchMedia` stub → asserts `data-breakpoint`, `data-access-state`, role, glyph, both `<bdi>` runs, logical style string, long EN/AR wrapping, no tab stop, RTL, and continuity across a media-query change.

## Coverage

Command: `bash apps/staff-web/check.sh` (its `npm run test:coverage` step uses the V8 provider with per-file 100% thresholds; no threshold, exclusion or skip was changed). Result: 100% overall — `3722/3722` statements, `1894/1894` branches, `1258/1258` functions, `3556/3556` lines; `No files with missing coverage. 207 files fully covered.` `bash scripts/coverage-check.sh` independently reported `coverage check: passed`.

Read from the coverage summary for the only touched production file:
`src/features/admin/AccessStateNotice.tsx` — 100% lines, 100% branches, 100% functions, 100% statements (6/6 lines, 4/4 branches, 1/1 function, 6/6 statements as recorded in the earlier notes).

New/changed branches and the test that takes each side:
- **NEW** `className={breakpoint === 'compact' ? 'stack' : 'cluster'}` (`:45`) — compact side: `responsive AccessStateNotice at compact width … > exposes the band …` asserts `stack`; non-compact side: the same test at `medium` and `expanded` asserts `cluster`, and `follows a viewport change without remounting …` observes `stack → cluster → cluster` through real media-query change events.
- Pre-existing `role={state === 'denied' ? 'alert' : 'status'}` (`:43`) — `denied` side asserted by `admin-components.test.tsx` and the responsive test (`alert`); `permitted`/`concealed` sides asserted by both (`status`). Still 100%.
- New `data-breakpoint`, `useBreakpoint()` call and logical inline style lines are unconditional and run in every responsive test; the safe-area, no-hidden-value, no-tab-stop, long-English/Arabic, RTL and viewport-change cases assert their outcomes. No other conditional, catch, empty-result or async path exists in this component.

## Clinical and architecture review

Not required: display-only change to a synthetic access-state notice. No signed data, cross-organization sharing, patient association, prescribing or critical result is involved; no migration, state machine, event contract or system boundary changed. Risk class is routine. A bounded `aurevia-senior-react` review was routed in this pass (source: **reviewer, simulated**): **no blocking findings**; it confirmed both conditionals have both sides asserted, strict TS (no `any`/`@ts-ignore`), logical/RTL/safe-area/zoom handling and TSDoc compliance. Advisories are non-blocking and recorded under Findings.

## Findings

- **No source change required in this fix pass.** The first worker's implementation and test are complete, in scope and green on the current branch after merging `origin/main`. The hand-off gap was the missing notes file at the fix-pass path named in my prompt; it is created here.
- **Earlier hand-offs, preserved:** `docs/pr/notes/RESP-ACCESS-STATE-NOTICE-20261122-084247.md` (first worker) and `docs/pr/notes/RESP-ACCESS-STATE-NOTICE-fix-20261122-090205.md` (fix attempt 1). I did not edit or delete either; this file is the hand-off for the current pass only.
- **Cause of the earlier "local checks: failed" verdict (most likely, with evidence):** the worktree arrives with no `node_modules` and with `opencode.json` as a skip-worktree CI profile. Before `npm ci`, every JS gate fails with a missing-binary error; `scripts/quick-check.sh` calls `check-pack.py`, which fails on the CI-profile `opencode.json`. After `npm ci` every applicable gate on the current tree passed, and the committed config was proven identical to the generator output. No change to this task is implicated; the integrator's trusted checkout (real local profile) is not affected. Exact next step if the integrator still sees red: confirm which command failed and check whether it is `check-pack.py` on a CI-profile worktree (OPS-CW-003-01) before treating it as a task defect.
- **`check-pack.py` false positive (recorded, not edited):** see Test evidence; no protected file was changed or committed.
- **Out-of-scope pre-existing defect (observed, untouched):** committed conflict markers remain in `apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7ParseException.java`, `Hl7Parser.java` and `Hl7Segment.java` (verified present in `HEAD`). They predate this task, are outside this story's scope, and would fail a full Java build; the integrator/owner should route a separate repair.
- **Pre-existing behaviour retained, not a defect introduced here:** the reviewed `label` run is rendered by `MixedScriptText` with `lang="en"` hard-coded in the component, independent of the active locale, while the translated message run carries `lang={locale}`. An Arabic label is isolated (`dir="auto"`) and wraps but carries `lang="en"`. Left unchanged because the story forbids changing behaviour/text; a localization follow-up could type the label's language.
- **Non-applicable acceptance items:** the notice has no input and no interactive control, so the 16 px input-text rule and the 44×44 coarse-pointer target rule do not apply here (owned by the responsive Form/FormField/Button stories); it adds no tab stop.
- **Pixel checks deferred:** per the story, jsdom asserts classes, attributes and structure only; pixel-level no-scroll/no-overlap/zoom checks remain with the Playwright responsive tasks.
- **Non-blocking advisories from the bounded `aurevia-senior-react` reviewer (not changed):** inline `gap: tokens.spacing.sm` (4 px) supersedes the `.stack`/`.cluster` utility gaps and bypasses the responsive spacing scale; the pre-existing `border: 1px solid ${tokens.border}` uses a literal pixel instead of `var(--sc-border-width-thin, 1px)`; `useBreakpoint()` is also subscribed inside `StateBadge`/`MixedScriptText`, so a notice holds multiple `matchMedia` listeners (functionally correct, only minor resize churn); and the test verifies padding via inline-style string matching, which is sensitive to React serialization changes. All are cosmetic/structural, none affect correctness or acceptance, and the story forbids behaviour/styling changes beyond responsiveness.

## Lessons for workers

- On a swarm fix pass whose prompt names a `-fix-<timestamp>.md` notes path, create that exact file even when an earlier notes file is already committed; do not overwrite the earlier one.
- A cloud worktree can arrive with no `node_modules`: run `npm ci` in `apps/staff-web` before any Vitest/typecheck gate, or every JS check fails with a missing-binary error that is not a code defect.
- A worktree `opencode.json` flagged `S` by `git ls-files -v` is the CI profile under skip-worktree. `check-pack.py`/`gen-opencode.py --check` failures there are the known false positive OPS-CW-003-01: prove it by comparing the generator output with `git show HEAD:opencode.json`, restore the CI profile with `cp`, and never edit or commit the protected file.
- Run the module `check.sh`, not just Vitest: it caught two unused imports (`TS6133`) in the first worker's new test that Vitest ignored. Fix every `error TS…` before stopping.
- For an embedded notice there is no safe-area notice class in `responsive.css`; use the hook's `stack`/`cluster` plus logical inline `padding-block-*/padding-inline-*` with `max(space, var(--safe-*))` (RTL-mapped in `:root[dir='rtl']`), never physical `padding-left/right`.
- A deterministic `matchMedia` stub must reuse one state object per query string so the hook's `useState` initializer and `useEffect` listener share it; otherwise a change event never reaches the component.
