# RESP-SESSION-EXPIRED-PAGE — responsive `SessionExpiredPage`

## Summary

Added a page-level responsive acceptance/regression test for the fixed session-expired
page and clarified in its doc comment that layout is delegated to the shared
`SessionExpiredState`. No behavior, text, API call or exported name changed: the
page already renders the fully responsive state panel from the completed
RESP-STATEVIEWS prerequisite, which supplies the safe-area-aware `page` grid,
breakpoint-driven action row, `touch` targets and logical properties. The new test
locks that contract for all seven required widths, both directions and all three
breakpoint bands. Synthetic text only; no new dependency; no file outside scope.

## Affected requirements

- **WEB-07** — staff web usable from 320 px upward; tested through the rendered
  alert panel at 320/360/390/768/820/1024/1280 px.
- **UX-01** — readable, non-clipped, wrapped and keyboard-reachable state in
  English LTR and Arabic RTL.

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

Senior React and Vite (skills `aurevia-senior-react`, `aurevia-senior-typescript`,
`aurevia-test-engineer`, `aurevia-ux-reviewer` loaded). Review is simulated
via skills and executed tools; no human or real-clinical participant.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix.

## Authorization boundaries and audit events

None. The page holds no patient data and adds or changes no tenant, role,
patient-link scope or audit event.

## Migration and rollout considerations

None. Presentation and test-only change.

## Test evidence

Evidence source: executed tool commands in this worktree (no reviewer, simulated
user or human participant). Entry-point-to-test trace: route `/session-expired`
(`src/features/system/routes.ts`) → `SessionExpiredPage` → `SessionExpiredState`
(`src/components/StateViews.tsx`) → `StatePanel`; the new test renders the page
through `ThemeProvider` + `I18nProvider` and asserts the resulting panel. The
existing `tests/features/system/systemPages.test.tsx` continues to cover the
bilingual titles and the `navigate('/')` action.

Commands and final lines:

1. `git merge --no-edit origin/main` → `Already up to date.` (run at start and again
   before finishing; HEAD `75c80512a9c038abd6f6f58204097e4b2689759d` matched
   `origin/main` both times).
2. `cd apps/staff-web && npm ci --no-audit --no-fund` → `added 486 packages in 4s`.
3. Focused red/green run:
   `cd apps/staff-web && ./node_modules/.bin/vitest run tests/responsive/features-system/SessionExpiredPage.responsive.test.tsx --reporter=verbose`
   → `Test Files  1 passed (1)` / `Tests  14 passed (14)`.
4. Full mandatory gate: `cd apps/staff-web && bash check.sh` — all stages passed:
   - generated client: `gen:api: generated client matches the contract`
   - **typecheck final lines** (no diagnostics means clean, exit 0):
     ```
     > aurevia-staff-web@0.1.0 typecheck
     > tsc --noEmit -p tsconfig.json

     ```
     The build stage repeats `tsc --noEmit -p tsconfig.json` and also passed.
   - lint: `eslint . --max-warnings 0` — no output, exit 0.
   - build: `✓ built in 157ms`; bundle budget: every chunk `ok`
     (`SessionExpiredPage-BYy6VrTK.js 174 ok`).
   - tests: `Test Files  159 passed (159)` / `Tests  1905 passed (1905)`.
     The stack traces in the log are expected caught `console.error` assertions
     from the existing provider-guard tests (`useI18n must be used inside
     I18nProvider`, `useTheme must be used inside ThemeProvider`); those tests pass.
5. `python3 -I scripts/check-docs.py` → exit 0 (no output).
6. Repository check once at the end: `bash scripts/check.sh` → **exit 1**, final
   line `check-pack: opencode.json is stale: run scripts/gen-opencode.py`.
   This is the documented OPS-CW-003-01 worker false positive: `git ls-files -v
   opencode.json` → `S` (skip-worktree), `git diff --quiet -- opencode.json` → no
   diff, and `agent-run.sh` has masked the worktree file with the CI profile, so
   the check judged that profile rather than the committed one. No protected file
   was regenerated; the integrator owns the full gate.
7. Remaining `check.sh` stages run individually, all exit 0: shell `bash -n` over
   `scripts/*.sh` and `infra/bootstrap/start.sh`; `shellcheck` over the same set;
   `scripts/project-name.py displayName` → `Aurevia Care`; `scripts/check-secrets.py`;
   `scripts/check-names.py`; `scripts/check-wiring.py` (only informational baseline
   notes); `scripts/check-migrations.py --require-baseline`;
   `bash scripts/frontend-check.sh` → `frontend standards: policy and tool commands
   passed`. `apps/api/check.sh` (full Java verify, >10 min), `db/check.sh` and
   `apps/patient-mobile/check.sh` are integrator-owned / untouched modules and were
   not run.

## Coverage

Command: `bash apps/staff-web/check.sh` (stage `npm run test:coverage` →
`vitest run --coverage --exclude 'e2e/**'`, provider v8, per-file thresholds 100%).
Result:

```
No files with missing coverage.
207 files fully covered.
4 empty files skipped.
Statements   : 100% ( 3721/3721 )
Branches     : 100% ( 1892/1892 )
Functions    : 100% ( 1258/1258 )
Lines        : 100% ( 3555/3555 )
```

New production branches: **none**. The only production edit is a doc comment on
`SessionExpiredPage`; it adds no statement, branch, function or class, so no new
branch needs a named test. `SessionExpiredPage.tsx` remains covered by the
existing `systemPages.test.tsx` and the new responsive test. No threshold,
exclusion, skip or generated-file rule was changed.

## Clinical and architecture review

Not required: a fixed system page with no clinical data, no signed record, no
cross-organization sharing, no prescribing and no critical results.

## Findings

1. The page already satisfies the responsive contract by delegating to
   `SessionExpiredState`; the completed RESP-STATEVIEWS prerequisite owns the
   `page` grid, breakpoint action row, `touch` targets and logical inline styles.
   Consequently **no red failure was observable** for the new test — it passes
   against the unmodified component and is an acceptance/regression guard, not a
   red-to-green fix. No behavior change was warranted by the story's constraint
   "do not change behaviour, text, API calls or exported names".
2. No CSS class was missing. `page`, `stack`, `cluster` and `touch` all exist in
   `apps/staff-web/src/theme/responsive.css`; no inline style or new CSS was added.
3. `git grep -nE '^(<<<<<<<|>>>>>>>) '` reports conflict markers in three
   **pre-existing, committed** `apps/api/.../exchange/hl7/` Java files
   (`Hl7ParseException.java`, `Hl7Parser.java`, `Hl7Segment.java`, referencing
   `origin/swarm/BUILD-37-469`). Verified with `git show HEAD:<path>`; my
   `git merge --no-edit origin/main` was a no-op and did not create them. They are
   outside this task's scope, so they were left untouched — the integrator/owner
   should reconcile them.
4. `bash scripts/check.sh` failed only on the known skip-worktree `opencode.json`
   policy artifact (OPS-CW-003-01); every other individually runnable stage was
   green, as was the full `apps/staff-web/check.sh`. The protected file was not
   edited and the artifact was not re-diagnosed on a later pass.
5. Pixel measurements and real 200/400 percent zoom cannot be evaluated in jsdom;
   per the story and `docs/design/responsive-layout.md` section 13 they remain
   with the Playwright responsive projects. This test asserts classes, attributes
   and structure only.

## Lessons for workers

- For fixed pages built on `StateViews` or `SystemNotice`, read the shared
  component before adding styles: the page often needs no layout change, and
  wrapping it in another `page` div double-applies the safe-area padding.
- `npx vitest` without installed local modules downloads a different Vitest and
  fails to resolve `vitest/config` in `vite.config.ts`; run `npm ci` (or
  `bash check.sh`, which installs it) before focused test runs.
- When `git grep` shows conflict markers, confirm with `git show HEAD:<path>`
  whether they were committed by another worker before attributing them to your
  own merge; do not edit out-of-scope files to "clean" them.
