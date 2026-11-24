# RESP-ACCESS-STATE-NOTICE — fix pass (2026-11-22 12:27 UTC)

## Summary

Continued from the earlier findings on PR #710 / issue #711 instead of starting over. The responsive implementation of `AccessStateNotice` and its test were already committed on this branch by the first worker and are complete, inside the two-file scope, and green: `bash apps/staff-web/check.sh` passes with 1905/1905 tests and 100% coverage, `npm run typecheck` is clean, and the only touched production file is 100% covered on every metric. **No source change was needed or made in this pass.** The recurring "Delivery failed" verdict is caused by a pre-existing, out-of-scope tooling/test defect in the protected `scripts/` area (`scripts/tests/test_worker_gate*.py` + `scripts/worker_gate.py`), which I reproduced with a real command (below). It cannot be repaired from this story's scope; a fix there would edit a protected file and block this pull request from auto-merging. This file is the hand-off at the exact path named in my prompt.

Scope of the committed change remains only `apps/staff-web/src/features/admin/AccessStateNotice.tsx` and `apps/staff-web/tests/responsive/features-admin/AccessStateNotice.responsive.test.tsx`. This pass adds only this notes file. No dependency, stylesheet, config, tracker, workflow, script or `.opencode` file was changed. No push, tag or `gh` call was made.

## Affected requirements

- **WEB-07** — responsive staff web from 320 px; no horizontal page scroll, long values wrap, columns stack on phones.
- **UX-01** — English and Arabic, RTL, CSS logical properties, safe-area insets, keyboard/DOM order, no hidden clinical value, text readable at 200% zoom.

## Acceptance mapping

| Story detail | Evidence (tool / reviewer) |
|---|---|
| 1. Read component, test, responsive doc, `responsive.css`, `useBreakpoint.ts` | Done by the first worker; conventions from `docs/design/responsive-layout.md` followed; no stylesheet edit (no notice-surface class exists). |
| 2. Works 320–1280 px, LTR/RTL, no scroll/clip, long EN/AR wrap, stacking, logical properties, safe area, no hidden value, 200% zoom | `data-breakpoint` plus `stack` (compact) / `cluster` (medium, expanded), `min-inline-size: 0`, `overflow-wrap: anywhere`, logical `padding-block-*`/`padding-inline-*` floored by `max(space, var(--safe-*))`, no fixed block size — asserted at all three bands, both directions and across a media-query change. Pixel checks remain with the Playwright tasks (jsdom cannot lay out pixels). |
| 3. Use existing classes/hook, no stylesheet edit, record missing class | Uses `useBreakpoint()` and existing `stack`/`cluster`; the missing notice-surface class is recorded under Lessons for workers. |
| 4. New test mocks matchMedia for compact/medium/expanded, asserts structure, labels, roles, keyboard order, RTL; existing tests pass | `AccessStateNotice.responsive.test.tsx` — 14 tests over three bands plus direction/continuity; targeted run 40/40 with `admin-components.test.tsx`; full suite 1905/1905. |
| 5. No behaviour/text/API/export change | `role` mapping, glyphs, message keys, props interface and export are unchanged. |

## Risk class

Routine (docs/WORKFLOW.md): one contained staff-web presentation change with an added view-level test; no data, contract, authorization or workflow boundary crossed. `git merge --no-edit origin/main` reported `Already up to date.` at the start; the tree was clean, and neither scoped file contains conflict markers.

## Review owner

`aurevia-senior-react` (staff React/Vite), supported by `aurevia-senior-typescript` (strict TS) and `aurevia-ux-reviewer` (RTL/keyboard). A bounded `aurevia-senior-react` review and a bounded UX review were routed in earlier passes (source: **reviewer, simulated**); both found no blocking findings. No new implementation work exists in this notes-only pass, so no further specialist routing was required.

## Data changes

None. No migration, table, backfill, lock or rollout impact. Synthetic fixture labels only; no patient data, credential or real identifier.

## Authorization boundaries and audit events

None. The component only displays a pre-existing synthetic `AccessState` (`permitted`/`denied`/`concealed`); its `role` mapping, `data-access-state`, glyphs and message text are unchanged. No tenant, role or patient-link logic and no audit event was added or changed.

## Migration and rollout considerations

None. Presentation-only change to a display component already reachable from `AdminScreen.tsx`; no production deployment action.

## Test evidence

Evidence source: **executed tool commands** (git, npm, Vitest, tsc, Python unittest) plus **reviewer findings** from earlier passes (source: reviewer, simulated). No human, clinical or simulated-user evidence was produced in this pass.

- Reconcile — `git merge --no-edit origin/main` → `Already up to date.`; `git status --short` empty before and after. A `git grep` for Git conflict-marker lines shows markers only in three HL7 Java files outside scope (see Findings); neither scoped file contains markers.
- Dependency setup — `cd apps/staff-web && npm ci --no-audit --no-fund` → `added 486 packages in 6s`, exit 0 (the worktree arrived with no `node_modules`).
- Targeted + regression — `cd apps/staff-web && node_modules/.bin/vitest run tests/responsive/features-admin/AccessStateNotice.responsive.test.tsx src/features/admin/admin-components.test.tsx` → final lines:
  ```
   Test Files  2 passed (2)
        Tests  40 passed (40)
  ```
  exit 0.
- MANDATORY FRONTEND GATE — `bash apps/staff-web/check.sh` from the repo root (generated-client check, typecheck, lint, production build, WEB-08 bundle budget, 100%-coverage tests). exit 0; final lines:
  ```
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
  The `useTheme must be used inside ThemeProvider` stack traces printed during the run come from the existing intentional fail-closed tests in `tests/theme/ThemeProvider.test.tsx` (and the analogous `I18nProvider` tests); the suite and coverage gate passed.
- TYPECHECK (mandatory, standalone) — `cd apps/staff-web && npm run typecheck`, final lines (no `error TS…`):
  ```
  > aurevia-staff-web@0.1.0 typecheck
  > tsc --noEmit -p tsconfig.json

  TYPECHECK_EXIT:0
  ```
  exit 0, no diagnostics.
- Out-of-scope blocker reproduction (executed, tool evidence) — `cd /__w/Aurevia-Care/Aurevia-Care && python3 -I -m unittest discover -s scripts/tests -p 'test_worker_gate*.py' -q`, with `apps/api/target/surefire-reports` absent, ended:
  ```
  FileNotFoundError: [Errno 2] No such file or directory: 'apps/api/target/surefire-reports'
  Ran 143 tests in 0.044s
  FAILED (errors=2)
  ```
  After `mkdir -p apps/api/target/surefire-reports` (an uncommitted worktree-only conditions fix), the same command ended `Ran 143 tests ... OK`, exit 0. This proves the failure is a missing-directory condition in pre-existing protected tooling, not a defect in this story's code.
- `bash scripts/check.sh` was **not** run as one command: it invokes `apps/api/check.sh` → `mvn verify` (full OWASP/SpotBugs/JaCoCo/integration suite, over ten minutes), expressly forbidden in this worker prompt. Its constituent in-scope stage (`apps/staff-web/check.sh`) was run and is recorded above; the integrator and Delivery run the full gate.
- Entry-point-to-test trace: `AdminScreen.tsx` renders `AccessStateNotice` with `state` and `label` in the access-review section → the new test renders the same component under the real `ThemeProvider` + `I18nProvider` with a deterministic per-query `matchMedia` stub → asserts `data-breakpoint`, `data-access-state`, role, glyph, both bidirectional text runs, the logical style string, long EN/AR wrapping, no tab stop, RTL behaviour, and continuity across a media-query change.

## Coverage

Command: `bash apps/staff-web/check.sh` (its `npm run test:coverage` step uses the V8 provider with per-file 100% thresholds; no threshold, exclusion or skip was changed). Result: 100% overall — `3722/3722` statements, `1894/1894` branches, `1258/1258` functions, `3556/3556` lines; `No files with missing coverage. 207 files fully covered.` The touched file read from `apps/staff-web/coverage/coverage-summary.json`:

`src/features/admin/AccessStateNotice.tsx` — lines 6/6 (100%), branches 4/4 (100%), functions 1/1 (100%), statements 6/6 (100%). No source file was changed in this pass; there is no new production line or branch to cover here.

New/changed branches from the original implementation and the test that takes each side:
- **NEW** `className={breakpoint === 'compact' ? 'stack' : 'cluster'}` — compact side taken by the compact-width test (asserts `stack`); non-compact side taken by the medium and expanded assertions (asserts `cluster`) and by the viewport-change test (`stack` to `cluster` through a real media-query change).
- Pre-existing `role={state === 'denied' ? 'alert' : 'status'}` — `denied` side asserted by `admin-components.test.tsx` and the responsive test (`alert`); `permitted`/`concealed` sides asserted by both (`status`). Still 100%.
- New `data-breakpoint`, `useBreakpoint()` call and logical inline-style lines are unconditional and run in every responsive test; the safe-area, no-hidden-value, no-tab-stop, long-English/Arabic, RTL and viewport-change cases assert their outcomes. No other conditional, catch, empty-result or async path exists in the component.

## Clinical and architecture review

Not required: display-only change to a synthetic access-state notice. No signed data, cross-organization sharing, patient association, prescribing or critical result is involved; no migration, state machine, event contract or system boundary changed. Risk class is routine.

## Findings

- **No source change required.** The implementation and test are complete, in scope and green. The recurring blocker is external to this story.
- **Out-of-scope pre-existing tooling defect (observed, reproduced, untouched).** In a fresh checkout, `apps/api/target/surefire-reports` does not exist. Two unit tests in `scripts/tests/test_worker_gate.py` and `scripts/tests/test_worker_gate_coverage.py` patch `Path.is_dir` to `True`; `scripts/worker_gate.py:63` then calls `Path('apps/api/target/surefire-reports').glob('TEST-*.xml')` on the absent directory and raises `FileNotFoundError`. That fails `scripts/python-coverage.sh`, and therefore `scripts/check.sh` and the Delivery `script-tests`/`policy` jobs, before any staff-web code is tested. The defect was introduced by commit `8f363e02` (FIX-WORKER-REPORTS), is present on `origin/main`, and is not caused by this branch. The real worker-gate invocation (`scripts/worker-check.sh:46`) passes an existing `mktemp -d` directory and is unaffected — only the unit tests hit the absent default path. Creating the directory makes all 143 `worker_gate` tests pass with no code change, confirming it is a test-fixture condition. Fixing it requires editing protected `scripts/` files (maintainer-owned); that is outside this task's two-file scope and, per AGENTS.md, would stop this pull request from auto-merging. It is requested below.
- **Earlier hand-offs preserved:** `docs/pr/notes/RESP-ACCESS-STATE-NOTICE-20261122-084247.md`, `...-fix-20261122-090205.md`, `...-fix-20261122-091248.md` and `...-fix-20261122-092217.md`. None was edited or deleted; this file is the hand-off for the current pass only.
- **Committed conflict markers in HL7 Javadoc (pre-existing, untouched):** `apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7ParseException.java`, `Hl7Parser.java` and `Hl7Segment.java`, present in both `HEAD` and `origin/main`. They are inside comments, so bounded `mvn test-compile` passes; a full Java/docs run may warn. Out of scope — route a separate repair.
- **Pre-existing behaviour retained, not a defect introduced here:** the reviewed `label` run is rendered by `MixedScriptText` with `lang="en"` hard-coded, independent of the active locale, while the translated message run carries `lang={locale}`. Left unchanged because the story forbids changing behaviour/text; a localization follow-up could type the label's language.
- **Non-applicable acceptance items:** the notice has no input and no interactive control, so the 16 px input-text rule and the 44×44 coarse-pointer target rule do not apply (owned by the responsive Form/FormField/Button stories); it adds no tab stop.
- **Pixel checks deferred:** per the story, jsdom asserts classes, attributes and structure only; pixel-level no-scroll/no-overlap/zoom checks remain with the Playwright responsive tasks.
- **Non-blocking advisories from the earlier bounded reviewer (not changed):** inline `gap: tokens.spacing.sm` supersedes the utility gaps; the pre-existing border uses a literal pixel; `useBreakpoint()` is also subscribed in `StateBadge`/`MixedScriptText` (minor resize churn); and the test verifies padding by inline-style string matching. None affect correctness or acceptance, and the story forbids styling changes beyond responsiveness.

## Lessons for workers

- On a swarm fix pass whose prompt names a timestamped `-fix-` notes path, create that exact file even when earlier notes files exist; never overwrite the earlier ones.
- Always run the module `check.sh` plus a standalone `npm run typecheck`: `check.sh` caught two unused imports (`TS6133`) in the first worker's test that Vitest ignored, and the prompt requires the typecheck final lines in the notes.
- A cloud worktree can arrive with no `node_modules`; run `npm ci` in `apps/staff-web` before any JS gate. `opencode.json` may be a skip-worktree CI profile (`git ls-files -v` shows `S`); that is the documented OPS-CW-003-01 artifact, not HEAD truth — do not treat its staleness as this change's defect.
- The two `worker_gate` unit tests that stub `Path.is_dir` to `True` fail with `FileNotFoundError` unless `apps/api/target/surefire-reports` exists. `mkdir -p` that directory (uncommitted) to run the local tooling suite; the real worker gate is unaffected because it passes a fresh `mktemp -d`.
- Editing `scripts/*` from a swarm story worker is forbidden by scope and blocks auto-merge; route such fixes through a maintainer task instead of doing them here.
- For an embedded notice there is no safe-area notice class in `responsive.css`; use the hook's `stack`/`cluster` plus logical inline padding floored by `max(space, var(--safe-*))`, never physical sides. A deterministic `matchMedia` stub must reuse one state object per query string so the hook's initializer and listener share it.

## Why this is not finished

The story's implementation is finished and every in-scope gate is green, but the pull request cannot go green from this worker because of an out-of-scope blocker:

- **Failing command:** `python3 -I -m unittest discover -s scripts/tests -p 'test_worker_gate*.py' -q` (and, in CI, the Delivery `script-tests`/`policy` stage that runs the whole tooling suite in a fresh checkout before any Maven step).
- **Error:** `FileNotFoundError: [Errno 2] No such file or directory: 'apps/api/target/surefire-reports'` raised at `scripts/worker_gate.py:63` in `usable_reports()`; `Ran 143 tests ... FAILED (errors=2)`.
- **Most likely cause:** two pre-existing unit tests in protected `scripts/tests/test_worker_gate*.py` patch `Path.is_dir` to `True` but let `usable_reports()` glob the default, nonexistent `apps/api/target/surefire-reports` path. Introduced by commit `8f363e02` (FIX-WORKER-REPORTS), present on `origin/main`, unrelated to this story's code. The real worker gate is unaffected because it passes an existing temporary directory.
- **What was tried:** reproduced the failure with the command above; confirmed `mkdir -p apps/api/target/surefire-reports` makes all 143 tests pass (worktree-only, uncommitted); confirmed the staff-web implementation itself passes 1905/1905 tests, standalone typecheck, and 100% coverage. Did not edit `scripts/`: it is protected, outside this task's two-file scope, and would block this PR's auto-merge.
- **Exact next step:** a maintainer / protected-tooling worker should apply the fix requested below to `scripts/worker_gate.py` (make `usable_reports()` return `False` for an absent or unreadable reports directory, preserving the nonzero-build compile/setup-failure semantics) and add a deterministic absent-directory test in `scripts/tests/`, then re-run `python3 -I -m unittest discover -s scripts/tests -q`, `bash scripts/python-coverage.sh` and the Delivery `script-tests`/`policy` jobs. Once those are green, PR #710 can merge and the integrator can mark this story done. No further action is possible from this story's scope.

## Task requests

```task-requests
[
  {
    "title": "Tolerate a missing surefire reports directory in worker_gate report scanning",
    "details": "In a fresh checkout, scripts/python-coverage.sh fails before any application code is tested: two unit tests in scripts/tests/test_worker_gate.py and scripts/tests/test_worker_gate_coverage.py patch Path.is_dir to True, so scripts/worker_gate.py:63 usable_reports() globs the nonexistent default path apps/api/target/surefire-reports and raises FileNotFoundError. This fails scripts/check.sh and the Delivery script-tests/policy jobs. Make usable_reports() return False for an absent/unreadable directory (or give those tests a real temporary reports directory), keep the nonzero-build compile/setup-failure semantics intact, and add a deterministic absent-directory test. Introduced by commit 8f363e02 (FIX-WORKER-REPORTS) and present on origin/main; discovered while diagnosing repeated Delivery failures on the staff-web story RESP-ACCESS-STATE-NOTICE. Protected tooling change; needs maintainer review.",
    "scope": ["scripts/worker_gate.py", "scripts/tests"],
    "depends": ["FIX-WORKER-REPORTS"]
  }
]
```
