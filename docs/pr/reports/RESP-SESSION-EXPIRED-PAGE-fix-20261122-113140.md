# RESP-SESSION-EXPIRED-PAGE — fix pass (attempt 1 of 3)

## Summary

Fix pass for the earlier run that the integrator flagged as "did not finish cleanly"
(issue #804 / PR #803, Step 1 integrator, 2026-11-22T11:22). The implementation from
the first worker was already committed on this branch
(`fa2a434f RESP-SESSION-EXPIRED-PAGE: responsive acceptance test for SessionExpiredPage`,
plus two `origin/main` merges) and its notes are in
`.agent/pr-notes/RESP-SESSION-EXPIRED-PAGE-20261122-110700.md`. I did **not** restart or
rewrite the story: I re-verified the committed change end to end with the actual
commands, confirmed the only reported failure is the documented OPS-CW-003-01
worker-environment false positive, ran an independent simulated specialist review,
and added this fix-pass notes file. No production code or test needed a change: every
gate is green with 100% coverage.

Evidence sources in this pass: executed shell/tool commands (primary) and one bounded
read-only specialist review via the `aurevia-senior-react` skill (simulated, not a
human or clinical participant). No simulated-user or human-participant evidence.

## Affected requirements

- **WEB-07** — staff web usable from 320 px upward; asserted through the rendered
  alert panel at 320/360/390/768/820/1024/1280 px.
- **UX-01** — readable, non-clipped, wrapped and keyboard-reachable state in English
  LTR and Arabic RTL.

Acceptance mapping (each row is asserted in
`tests/responsive/features-system/SessionExpiredPage.responsive.test.tsx`):

| Story requirement | Test |
|---|---|
| 320, 360, 390, 768, 820, 1024, 1280 px | `it.each(widths)` — `breakpointFor(width)` plus band structure |
| compact / medium / expanded structure | `data-breakpoint`, `stack` vs `cluster` action row |
| no horizontal scroll, wrapping text, logical properties | `expectWrappedLogicalSurface` — `overflow-wrap: anywhere`, `text-align: start`, `max-inline-size: 44rem`, no physical padding/margin or text-align |
| safe-area insets, 16 px fields | `page` class assertion; page has no inputs (`queryByRole('textbox')` is null) |
| 44 × 44 coarse targets | `touch` class on the sign-in wrapper (rule lives in `responsive.css`) |
| no hidden clinical column / field | no `display: none`, no table, no navigation landmark on this fixed page |
| 200 percent zoom readable | reflow test on inline styles; painted zoom checks belong to the Playwright tasks |
| labels, roles, keyboard order | `role="alert"`, `aria-live="assertive"`, `h2`, detail paragraph, one `Sign in` button focused in DOM order |
| right-to-left | Arabic `it.each` — `dir="rtl"`, `lang="ar"`, Arabic heading/detail/button, no `direction: rtl` override |
| behavior unchanged | sign-in click still navigates to `/` in all three bands |

## Risk class

Routine (docs/WORKFLOW.md): contained staff-web test addition plus a doc comment;
no boundary, data or policy change.

## Review owner

Senior React and Vite. Skills loaded: `aurevia-delivery`, `aurevia-senior-react`,
`aurevia-senior-typescript`, `aurevia-test-engineer`, `aurevia-ux-reviewer`.
An independent bounded read-only review was executed through the
`aurevia-senior-react` subagent; it returned **no blocking issues** (three LOW
notes and one INFO note, listed under Findings). Review is simulated via skill and
executed tools, not a human or real-clinical participant.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix.

## Authorization boundaries and audit events

None. The page holds no patient data and adds or changes no tenant, role,
patient-link scope or audit event.

## Migration and rollout considerations

None. Presentation and test-only change.

## Test evidence

Evidence source: executed tool commands in this worktree. Entry-point-to-test trace:
route `/session-expired` (`src/features/system/routes.ts`) → `SessionExpiredPage` →
`SessionExpiredState` (`src/components/StateViews.tsx`) → `StatePanel`; the new test
renders the page through `ThemeProvider` + `I18nProvider` and asserts the resulting
alert panel. The existing `tests/features/system/systemPages.test.tsx` continues to
cover the bilingual titles and the `navigate('/')` action.

Commands run in this fix pass and their final lines:

1. `git merge --no-edit origin/main` → `Already up to date.` (run at start; HEAD
   `7f2dea8356c20e89fdf6bb695cdce47db980c9d3`, no conflicts).
2. `cd apps/staff-web && npm ci --no-audit --no-fund` → `added 486 packages in 8s`
   (fresh worker checkout had no `node_modules`).
3. Focused red/green regression:
   `cd apps/staff-web && ./node_modules/.bin/vitest run tests/responsive/features-system/SessionExpiredPage.responsive.test.tsx --reporter=verbose`
   → `Test Files  1 passed (1)` / `Tests  14 passed (14)`.
4. Full mandatory gate `bash apps/staff-web/check.sh` — every stage passed:
   - generated client: `gen:api: generated client matches the contract`
   - **typecheck final lines** (no diagnostics means clean, exit 0):
     ```
     > aurevia-staff-web@0.1.0 typecheck
     > tsc --noEmit -p tsconfig.json

     ```
     The build stage repeats `tsc --noEmit -p tsconfig.json` and also passed.
   - lint: `eslint . --max-warnings 0` — no output, exit 0.
   - build/bundle budget: build succeeded; every chunk `ok`.
   - tests: `Test Files  159 passed (159)` / `Tests  1905 passed (1905)`.
     The stack traces in the log are expected caught `console.error` assertions from
     the existing provider-guard tests (`useI18n must be used inside I18nProvider`,
     `useTheme must be used inside ThemeProvider`); those tests pass.
5. `bash scripts/frontend-typecheck-check.sh origin/main` →
   `frontend typecheck: passed (or no frontend files changed)` (exit 0; ran staff-web
   `tsc --noEmit` and `eslint . --max-warnings 0` with no diagnostics).
6. `bash scripts/coverage-check.sh origin/main` → `coverage check: passed` (exit 0).
7. `python3 -I scripts/check-docs.py` → exit 0 (no output).
8. `bash scripts/docs.sh` (integrator gate stage 2) → exit 0; produced
   `Developer reference: build/developer-reference/html/index.html` and the staff-web
   TypeDoc index. Doxygen emitted only pre-existing warnings about undocumented Java
   filter methods and unresolved Markdown `\ref` links outside this task's scope.
9. `bash scripts/check.sh` → exit 1, final line
   `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. This is the
   documented OPS-CW-003-01 worker false positive (proof below), not a change defect.
   The remaining individually runnable stages were green in the first worker's pass
   and are unchanged by this fix pass.

`apps/api/check.sh`, `db/check.sh` and `apps/patient-mobile/check.sh` are
integrator-owned / untouched modules and were not run (no files in those areas changed).

## Coverage

Command: `bash apps/staff-web/check.sh` (stage `npm run test:coverage` →
`vitest run --coverage --exclude 'e2e/**'`, provider v8, per-file thresholds 100%),
independently confirmed by `bash scripts/coverage-check.sh origin/main`. Result:

```
No files with missing coverage.
207 files fully covered.
4 empty files skipped.
Statements   : 100% ( 3721/3721 )
Branches     : 100% ( 1892/1892 )
Functions    : 100% ( 1258/1258 )
Lines        : 100% ( 3555/3555 )
```

New production branches: **none**. The only production edit is the expanded TSDoc
comment on `SessionExpiredPage`; it adds no statement, branch, function or class, so
no new branch needs a named test. `SessionExpiredPage.tsx` remains covered by the
existing `systemPages.test.tsx` and the new responsive test. No coverage threshold,
exclusion, skip or generated-file rule was changed.

## Clinical and architecture review

Not required: a fixed system page with no clinical data, no signed record, no
cross-organization sharing, no prescribing and no critical results.

## Findings

1. **No defect behind the fix flag.** The integrator's only recorded reason was
   "the worker did not finish cleanly" with no failing check or code evidence. Every
   gate I re-ran is green, so the earlier run most likely ended before emitting its
   completion signal rather than leaving broken work. No implementation change was
   warranted.
2. **OPS-CW-003-01 false positive proven, not assumed.** `git ls-files -v opencode.json`
   → `S` (skip-worktree) and `git diff --quiet -- opencode.json` → no diff. I copied the
   generator and `.opencode/agents` to an isolated temp directory, regenerated both
   profiles there, and compared with `git show HEAD:opencode.json` and
   `git show HEAD:.github/opencode.ci.json`: both were byte-for-byte `MATCH`. The
   committed files are correct; `gen-opencode.py --check` / `check-pack.py` only fail
   because they read the CI profile the worker image masked over the worktree. No
   protected file was regenerated or edited; the integrator owns the full gate.
3. **Pre-existing conflict markers remain out of scope.** `git grep -nE '^(<<<<<<<|>>>>>>>) '`
   reports markers in `apps/api/.../exchange/hl7/Hl7ParseException.java`, `Hl7Parser.java`
   and `Hl7Segment.java` (referencing `origin/swarm/BUILD-37-469`). Confirmed committed
   on main and outside this task's scope; the integrator/owner should reconcile them.
4. **Simulated review outcome (no blocking issues).** LOW: the inert `(pointer: coarse)`
   mock entry (the `touch`/44 px floor is CSS-only and jsdom cannot paint it); LOW: the
   200%-zoom test repeats wrapped-surface assertions at 4 of 7 widths and is a DOM
   proxy only; LOW: `queryByRole('table'|'textbox'|'navigation')` is a vacuous proxy on a
   page with no clinical content; INFO: the doc comment could add `@returns`. All are
   non-blocking and left as-is to avoid churn; the story's explicit "do not change
   behaviour, text, API calls or exported names" and pixel-level checks being assigned
   to the Playwright tasks support keeping the test unchanged.
5. Pixel measurements and real 200/400 percent zoom cannot be evaluated in jsdom;
   per the story and `docs/design/responsive-layout.md` section 13 they remain with the
   Playwright responsive projects. This test asserts classes, attributes and structure.

## Lessons for workers

- On a "did not finish cleanly" fix attempt, first verify the committed work is actually
  broken before rewriting it: re-run the focused test and the module `check.sh`, then
  check the integrator gate stages individually. Green work usually means the prior
  session simply ended early.
- To prove an `opencode.json is stale` report without touching the protected file, run
  the generator in a throwaway directory containing a copy of `.opencode/agents` and a
  `.github/` folder, then `diff` its output against `git show HEAD:opencode.json` and
  `git show HEAD:.github/opencode.ci.json`. An `S` skip-worktree flag plus a byte match
  confirms OPS-CW-003-01.
- A fresh swarm worker checkout has no `node_modules`; run `npm ci` in `apps/staff-web`
  before focused Vitest runs or `vitest/config` will not resolve.
- When `git grep` shows conflict markers, confirm with `git show HEAD:<path>` whether
  another worker committed them before attributing them to your own merge; do not edit
  out-of-scope files to "clean" them.
- For fixed pages built on `StateViews`, read the shared component before adding styles:
  the page often needs no layout change, and wrapping it in another `page` div
  double-applies the safe-area padding.
