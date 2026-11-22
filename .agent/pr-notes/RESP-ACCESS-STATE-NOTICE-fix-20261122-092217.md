# RESP-ACCESS-STATE-NOTICE — fix pass (2026-11-22 09:22 UTC)

## Summary

Continued from the earlier findings (issue #711 / PR #710) instead of starting over. The responsive implementation and its test were already committed on this branch by the first worker (`327693f3 Make AccessStateNotice responsive with breakpoint-aware layout`) and are complete, in scope and green. **No production or test source change was needed in this pass.** I reconciled the branch with `origin/main`, installed the frontend toolchain, re-ran every applicable gate, and — critically — diagnosed the actual cause of the repeated "local checks: failed" verdict: two worker-environment gaps that make `scripts/python-coverage.sh` (and therefore `check.sh` / `quick-check.sh`) fail before any staff-web code is even reached. Both were corrected in the worktree; every applicable gate is now green with 100% coverage. This file is the hand-off at the exact path named in my prompt.

Scope: the implementation changed only `apps/staff-web/src/features/admin/AccessStateNotice.tsx` and `apps/staff-web/tests/responsive/features-admin/AccessStateNotice.responsive.test.tsx`. This pass adds only this notes file. No dependency, stylesheet, config, tracker, workflow, script or `.opencode` file was changed or committed. No push, tag or `gh` call was made.

## Affected requirements

- **WEB-07** — responsive staff web from 320 px; no horizontal page scroll, long values wrap, columns stack on phones.
- **UX-01** — English and Arabic, RTL, logical properties, safe-area insets, keyboard/DOM order, no hidden clinical value, text readable at 200% zoom.

## Acceptance mapping

| Story detail | Evidence |
|---|---|
| 1. Read component, test, responsive doc, `responsive.css`, `useBreakpoint.ts` | All read; `docs/design/responsive-layout.md` §1, §2, §7, §11, §12 conventions followed; no stylesheet edit (no notice-surface class exists). |
| 2. Works 320–1280 px, LTR/RTL, no scroll/clip, long EN/AR wrap, stacking, logical properties, safe area, no hidden value, 200% zoom | `data-breakpoint` + `stack`(`compact`)/`cluster`(`medium`,`expanded`), `min-inline-size: 0`, `overflow-wrap: anywhere`, logical `padding-block-*/padding-inline-*` with `max(space, var(--safe-*))`, no fixed block size — asserted at all three bands, both directions and across a media-query change. Pixel checks remain with the Playwright tasks (jsdom cannot lay out pixels). |
| 3. Use existing classes/hook, no stylesheet edit, record missing class | Uses `useBreakpoint()` and existing `stack`/`cluster`; missing notice-surface class recorded under Lessons for workers. |
| 4. New test mocks matchMedia for compact/medium/expanded, asserts structure, labels, roles, keyboard order, RTL; existing tests pass | `AccessStateNotice.responsive.test.tsx` — 14 tests over three bands plus direction/continuity; targeted run 40/40 with `admin-components.test.tsx`; full suite 1905/1905. |
| 5. No behaviour/text/API/export change | `role` mapping, glyphs, message keys, props interface and export unchanged. |

## Risk class

Routine (docs/WORKFLOW.md): one contained staff-web presentation change with an added view-level test; no data, contract, authorization or workflow boundary crossed. `git merge --no-edit origin/main` reported `Already up to date.` at the start; the tree was clean and no conflict markers were in either scoped file.

## Review owner

`smartclinic-senior-react` (staff React/Vite), supported by `smartclinic-senior-typescript` (strict TS) and `smartclinic-ux-reviewer` (RTL/keyboard). A bounded read-only `smartclinic-senior-react` review was routed in this pass (source: **reviewer, simulated**): **no blocking findings**, no untested new branch; it confirmed the breakpoint layout, strict TS of production and test, logical/RTL/safe-area/zoom handling, and both sides of the one new conditional. A prior bounded UX review (source: **reviewer, simulated**) also found no blocking findings.

## Data changes

None. No migration, table, backfill, lock or rollout impact. Synthetic fixture labels only (`Synthetic probe SYN-PROBE-01`); no patient data, credential or real identifier.

## Authorization boundaries and audit events

None. The component only displays a pre-existing synthetic `AccessState` (`permitted`/`denied`/`concealed`); its `role="alert"|"status"`, `data-access-state`, glyphs and message text are unchanged. No tenant, role or patient-link logic and no audit event was added or changed.

## Migration and rollout considerations

None. Presentation-only change to a display component already reachable from `AdminScreen.tsx`; no production deployment action.

## Test evidence

Evidence source: **executed tool commands** (git, npm, Vitest, tsc, ESLint, Vite build, bundle-size gate, V8 coverage, Python tooling tests and repository policy scripts) plus **reviewer findings** (bounded simulated specialist review); no human, clinical or simulated-user evidence.

- Reconcile — `git merge --no-edit origin/main` → `Already up to date.`; `git status --short` empty before and after edits. `git grep -nE '^(<<<<<<<|>>>>>>>) '` shows markers only in three HL7 Java files outside this scope (see Findings); neither scoped file contains markers.
- Dependency setup — `cd apps/staff-web && npm ci --no-audit --no-fund` → `added 486 packages in 6s`, exit 0. (The worktree arrived without `node_modules`; without this step every JS gate fails with `node_modules/.bin/vitest: No such file or directory`.)
- Targeted + regression (re-run this pass) — `cd apps/staff-web && node_modules/.bin/vitest run tests/responsive/features-admin/AccessStateNotice.responsive.test.tsx src/features/admin/admin-components.test.tsx`, final lines:
  ```
  Test Files  2 passed (2)
       Tests  40 passed (40)
  ```
  exit 0.
- MANDATORY FRONTEND GATE (re-run this pass) — `bash apps/staff-web/check.sh` from the repo root (generated-client check, typecheck, lint, production build, WEB-08 bundle budget, 100%-coverage tests). exit 0; final lines:
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
  (The `useI18n/useTheme must be used inside …Provider` stack traces in the output come from the existing intentional fail-closed tests in `tests/i18n/I18nProvider.test.tsx` and `tests/theme/ThemeProvider.test.tsx`; the suite and coverage gate passed.)
- TYPECHECK (mandatory, standalone) — `cd apps/staff-web && npm run typecheck`, final lines (no `error TS…`):
  ```
  > smartclinic-staff-web@0.1.0 typecheck
  > tsc --noEmit -p tsconfig.json
  ```
  exit 0 (no diagnostics).
- `bash scripts/frontend-typecheck-check.sh` → `frontend typecheck: passed (or no frontend files changed)`, exit 0.
- `bash scripts/coverage-check.sh` → `coverage check: passed` with the same 100% summary above, exit 0.
- `bash scripts/frontend-check.sh` → `frontend standards: policy and tool commands passed`, exit 0.
- Policy — `python3 -I scripts/check-docs.py`, `python3 -I scripts/check-secrets.py`, `python3 -I scripts/check-names.py`, `python3 -I scripts/check-wiring.py`, `python3 -I scripts/check-migrations.py --require-baseline` → each exit 0.
- Python tooling gate — `bash scripts/python-coverage.sh` → exit 0 after the two environment corrections in Findings (before them it failed with `integration run failed without fresh, usable test results (compile or setup error)` and `check-pack: opencode.json is stale`). `python3 -I -m unittest discover -s scripts/tests -q` → `Ran 1118 tests … OK`.
- Java compile sanity (bounded, not the full verify) — `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test-compile` → exit 0; the three HL7 conflict markers are inside Javadoc comments and do not break compilation.
- `bash scripts/check.sh` was **not run as a single command**: it invokes `apps/api/check.sh` → `mvn -q -B verify` (full OWASP/SpotBugs/JaCoCo/integration suite), expressly forbidden in this worker prompt (over ten minutes) and outside this story's scope. Its constituent policy, Python, frontend and staff-web stages were all run individually and are recorded above; the integrator and Delivery run the full gate.
- Entry-point-to-test trace: `AdminScreen.tsx` renders `<AccessStateNotice state label />` in the access-review section → the new test renders the same component under the real `ThemeProvider` + `I18nProvider` with a deterministic per-query `matchMedia` stub → asserts `data-breakpoint`, `data-access-state`, role, glyph, both `<bdi>` runs, logical style string, long EN/AR wrapping, no tab stop, RTL, and continuity across a media-query change.

## Coverage

Command: `bash apps/staff-web/check.sh` (its `npm run test:coverage` step uses the V8 provider with per-file 100% thresholds; no threshold, exclusion or skip was changed). Result: 100% overall — `3722/3722` statements, `1894/1894` branches, `1258/1258` functions, `3556/3556` lines; `No files with missing coverage. 207 files fully covered.` `bash scripts/coverage-check.sh` independently reported `coverage check: passed`.

Read from `apps/staff-web/coverage/coverage-summary.json` for the only touched production file:
`src/features/admin/AccessStateNotice.tsx` — lines 6/6 (100%), branches 4/4 (100%), functions 1/1 (100%), statements 6/6 (100%).

New/changed branches and the test that takes each side:
- **NEW** `className={breakpoint === 'compact' ? 'stack' : 'cluster'}` (`:45`) — compact side: `responsive AccessStateNotice at compact width … > exposes the band …` asserts `stack` (`:119`); non-compact side: the same test at `medium` and `expanded` asserts `cluster`, and `follows a viewport change without remounting …` observes `stack → cluster → cluster` through real media-query change events (`:245–248`).
- Pre-existing `role={state === 'denied' ? 'alert' : 'status'}` (`:43`) — `denied` side asserted by `admin-components.test.tsx` and the responsive test (`:117`, `:215`); `permitted`/`concealed` sides asserted by both (`status`). Still 100%.
- New `data-breakpoint`, `useBreakpoint()` call and logical inline style lines are unconditional and run in every responsive test; the safe-area, no-hidden-value, no-tab-stop, long-English/Arabic, RTL and viewport-change cases assert their outcomes. No other conditional, catch, empty-result or async path exists in the component.

## Clinical and architecture review

Not required: display-only change to a synthetic access-state notice. No signed data, cross-organization sharing, patient association, prescribing or critical result is involved; no migration, state machine, event contract or system boundary changed. Risk class is routine. A bounded `smartclinic-senior-react` review was routed this pass (source: **reviewer, simulated**): no blocking findings and no untested new branch.

## Findings

- **No source change required in this fix pass.** The first worker's implementation and test are complete, in scope and green on the current branch after merging `origin/main`. The recurring hand-off gap was the missing notes file at the exact fix-pass path named in my prompt; earlier fix passes wrote to their own timestamped paths.
- **Earlier hand-offs, preserved:** `.agent/pr-notes/RESP-ACCESS-STATE-NOTICE-20261122-084247.md` (first worker), `.agent/pr-notes/RESP-ACCESS-STATE-NOTICE-fix-20261122-090205.md` and `.agent/pr-notes/RESP-ACCESS-STATE-NOTICE-fix-20261122-091248.md` (earlier fix passes). None was edited or deleted; this file is the hand-off for the current pass only.
- **Actual cause of the repeated "local checks: failed" verdict (with evidence).** It is a worker-environment setup gap, not a defect in this change, and previous fix passes never ran `bash scripts/python-coverage.sh`, which is why they concluded "all checks passed". Two independent gaps made `scripts/python-coverage.sh` exit 1, which fails `scripts/check.sh` and `scripts/quick-check.sh`, which in turn fails the fix-PR gate:
  1. The worktree `opencode.json` was the CI profile copied under skip-worktree (`git ls-files -v opencode.json` → `S`). `scripts/check-pack.py` reported `opencode.json is stale`, and six `test_check_pack` cases failed (`test_committed_opencode_json_is_generated`, `test_repository_pack_is_valid`, `test_main_reports_success`, `test_cli_entry_point`, `test_check_mode_detects_drift`, `test_simulated_user_skills_need_no_process_sections`). The committed profile is current — `diff -q` of `gen-opencode.py` output against `git show HEAD:opencode.json` reported them identical (exit 0). Correction: regenerated the local profile with `python3 -I scripts/gen-opencode.py`; `check-pack.py` then validated `33 agents, 33 skills` and all six tests passed. `opencode.json` is skip-worktree, so this is a worktree-only correction and is not part of the commit.
  2. `apps/api/target/surefire-reports` did not exist in a fresh local checkout. Two `worker_gate` tests (`test_integration_failure_still_fails_the_gate`, `test_maven_failure_with_failing_reports_still_fails`) patch `Path.is_dir` to `True`, so `usable_reports()` scanned a nonexistent directory and raised `FileNotFoundError`. Correction: `mkdir -p apps/api/target/surefire-reports` (the state a prior Maven run produces in CI). The full tooling suite then reported `Ran 1118 tests … OK` and `python-coverage.sh` exited 0.
  Both corrections are made in the worktree so the post-session local gate can pass; neither is committed. The second condition can also occur in a **fresh CI checkout** that runs `check.sh` before any Maven step has created `apps/api/target/surefire-reports`; if the fix-PR gate is still red after the local environment is corrected, this is the most likely cause and is outside this story's scope. A separate repair is requested below. Exact next step if the integrator still sees red: confirm which command failed, check these two environment conditions, and only then treat it as a task defect.
- **Out-of-scope pre-existing defect (observed, untouched):** committed conflict markers remain in `apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7/Hl7ParseException.java`, `Hl7Parser.java` and `Hl7Segment.java`, present in both `HEAD` and `origin/main`. They are inside Javadoc comments, so bounded `mvn … test-compile` passed; a full Java build/docs run may still warn. They predate this task, are outside this story's scope, and were left untouched; the integrator/owner should route a separate repair.
- **Pre-existing behaviour retained, not a defect introduced here:** the reviewed `label` run is rendered by `MixedScriptText` with `lang="en"` hard-coded in the component, independent of the active locale, while the translated message run carries `lang={locale}`. An Arabic label is isolated (`dir="auto"`) and wraps but carries `lang="en"`. Left unchanged because the story forbids changing behaviour/text; a localization follow-up could type the label's language.
- **Non-applicable acceptance items:** the notice has no input and no interactive control, so the 16 px input-text rule and the 44×44 coarse-pointer target rule do not apply here (owned by the responsive Form/FormField/Button stories); it adds no tab stop.
- **Pixel checks deferred:** per the story, jsdom asserts classes, attributes and structure only; pixel-level no-scroll/no-overlap/zoom checks remain with the Playwright responsive tasks.
- **Non-blocking advisories from the bounded `smartclinic-senior-react` reviewer (not changed):** inline `gap: tokens.spacing.sm` (4 px) supersedes the `.stack`/`.cluster` utility gaps; the pre-existing `border: 1px solid ${tokens.border}` uses a literal pixel instead of `var(--sc-border-width-thin, 1px)`; `useBreakpoint()` is also subscribed inside `StateBadge`/`MixedScriptText`, so a notice holds several `matchMedia` listeners (functionally correct, minor resize churn); and the test verifies padding via inline-style string matching, sensitive to React serialization changes. All are cosmetic/structural, none affect correctness or acceptance, and the story forbids behaviour/styling changes beyond responsiveness.

## Lessons for workers

- On a swarm fix pass whose prompt names a `-fix-<timestamp>.md` notes path, create that exact file even when earlier notes files are already committed; do not overwrite the earlier ones. Earlier passes here wrote to their own timestamps, which left the required path missing.
- **Always run `bash scripts/python-coverage.sh`**, not only the JS checks: it is the stage in `check.sh`/`quick-check.sh` that actually failed here. A green Vitest/typecheck run says nothing about it.
- A cloud worktree can arrive with no `node_modules` and with `opencode.json` as a skip-worktree CI profile. Run `npm ci` in `apps/staff-web` and regenerate the local profile with `python3 -I scripts/gen-opencode.py` before any gate; restore the CI profile with `cp` only if you must leave the environment exactly as found, knowing that doing so reintroduces `check-pack.py` and six tooling-test failures.
- Two `worker_gate` tests require `apps/api/target/surefire-reports` to exist (they stub `is_dir` to `True`). In a fresh checkout run a Maven step that creates it (or `mkdir -p`) before `python-coverage.sh`, or those tests raise `FileNotFoundError` and the gate fails for an environmental reason.
- Run the module `check.sh`, not just Vitest: it caught two unused imports (`TS6133`) in the first worker's new test that Vitest ignored. Fix every `error TS…` before stopping.
- For an embedded notice there is no safe-area notice class in `responsive.css`; use the hook's `stack`/`cluster` plus logical inline `padding-block-*/padding-inline-*` with `max(space, var(--safe-*))` (RTL-mapped in `:root[dir='rtl']`), never physical `padding-left/right`.
- A deterministic `matchMedia` stub must reuse one state object per query string so the hook's `useState` initializer and `useEffect` listener share it; otherwise a change event never reaches the component.

## Task requests

```task-requests
[
  {
    "title": "Make worker_gate report scanning safe when the surefire reports directory is absent",
    "details": "python-coverage.sh fails in a fresh checkout because two worker_gate tests stub Path.is_dir to True, so usable_reports() calls Path('apps/api/target/surefire-reports').glob('TEST-*.xml') on a nonexistent directory and raises FileNotFoundError. That fails scripts/python-coverage.sh, and therefore scripts/check.sh and scripts/quick-check.sh, before application code is tested. Make usable_reports() tolerate a missing/unreadable directory (or give the two tests a real temporary reports directory), add a deterministic test for the absent-directory case, and keep the compile-or-setup-failure semantics intact. This is a protected tooling change and needs maintainer review; discovered while diagnosing repeated 'local checks: failed' verdicts on the staff-web story RESP-ACCESS-STATE-NOTICE.",
    "scope": ["scripts/worker_gate.py", "scripts/tests"],
    "depends": ["FIX-WORKER-REPORTS"]
  }
]
```
