# RESP-WELCOME-PAGE — fix pass: responsive `WelcomePage` (staff web)

## Summary

Fix pass for the swarm branch `swarm/RESP-WELCOME-PAGE-861`. The implementation
from the first worker was already complete and correct: `WelcomePage` renders a
labelled `section.page[data-breakpoint]` through the real `useBreakpoint` hook,
adds only logical inline styles for shrink-to-fit, wrapping and a direction-neutral
text start, and a new responsive test drives `matchMedia` across the three bands,
the 320–1280 px width matrix and Arabic RTL. No production or test code needed
changing in this pass.

This pass independently re-ran the canonical staff-web gate from a clean pinned
dependency install, confirmed 100% coverage on the only changed production file,
and reconciled the branch with `origin/main` (already up to date). The draft pull
request and issue #707 were caused by a cloud-worker-only `scripts/check.sh`
failure at `check-pack` — the CI `opencode.json` is installed under
`skip-worktree` — and not by a change defect. See Findings. All evidence below is
from executed tool commands (primary agent); no human or clinical participant.

## Affected requirements

WEB-07, UX-01.

Acceptance mapping: the responsive-band attribute and logical inline styles in
`apps/staff-web/src/app/WelcomePage.tsx` satisfy WEB-07/UX-01 reflow, wrapping,
safe-area and logical-property requirements; `apps/staff-web/tests/responsive/app/WelcomePage.responsive.test.tsx`
asserts the structure, labels, roles, DOM/keyboard order, the width matrix and the
RTL case. Pixel-level checks remain with the Playwright tasks, as the task directs.

## Risk class

Routine (contained staff-web view plus one isolated test; no cross-boundary or
safety-critical contract). Classified per `docs/WORKFLOW.md`.

## Review owner

Primary agent (tool-verified). Prior simulated `aurevia-senior-react` and
`aurevia-ux-reviewer` reviews on the first pass recorded no actionable
findings; those subagents had no shell and relied on the primary agent's evidence.
No new public contract, migration or clinical data is involved, so no new
cross-boundary or clinical review is required.

## Data changes

None. No migration, backfill, lock or volume impact.

## Authorization boundaries and audit events

None. This is a presentation-only change to a placeholder route; no tenant, role,
patient-link or audit behaviour changed.

## Migration and rollout considerations

None. No reserved migration version was used.

## Test evidence

Commands run and their final lines (this pass, all observed):

- `git merge --no-edit origin/main` → `Already up to date.`; a conflict-marker grep (`git grep` for the leading merge markers) over scoped files → no conflict markers.
- `bash apps/staff-web/check.sh` (installed pinned deps first: `added 486 packages in 4s`) → exit 0 with:
  - `gen:api: generated client matches the contract`
  - typecheck: `> tsc --noEmit -p tsconfig.json` then `> eslint . --max-warnings 0` — no `error TS…`, exit 0 (final lines of the typecheck stage).
  - build: `✓ built in 126ms`; bundle budget table: every chunk `ok`, including `WelcomePage-DcjT3cc2.js  473  ok`.
  - `Test Files  159 passed (159)` / `Tests  1910 passed (1910)` / `Duration  57.20s`.
  - `No files with missing coverage.` / `207 files fully covered.` / `4 empty files skipped.`
  - `Statements   : 100% ( 3722/3722 )` / `Branches     : 100% ( 1892/1892 )` / `Functions    : 100% ( 1258/1258 )` / `Lines        : 100% ( 3556/3556 )`
  - The log prints expected React error stacks from existing negative `useI18n`/`useTheme` provider tests; they do not fail the run.
- `node -e` over `apps/staff-web/coverage/coverage-summary.json` → `src/app/WelcomePage.tsx` `{"lines":{"total":3,"covered":3,"skipped":0,"pct":100},"statements":{"total":3,"covered":3,"skipped":0,"pct":100},"functions":{"total":1,"covered":1,"skipped":0,"pct":100},"branches":{"total":0,"covered":0,"skipped":0,"pct":100}}`.
- `python3 -I scripts/check-docs.py`, `check-secrets.py`, `check-names.py`, `check-wiring.py`, `check-migrations.py --require-baseline` → exit 0 (run individually because `check-pack` blocks the aggregate script; see Findings).
- `bash scripts/check.sh` → stops immediately at `check-pack: opencode.json is stale: run scripts/gen-opencode.py` (exit 1). Environmental artifact, not a change defect; not re-run, bypassed or edited. Evidence source: tool.

Entry point → test trace: `apps/staff-web/src/app/routes.tsx` lazily registers
`WelcomePage` for pattern `/`; `AppShell` renders it inside the app main landmark;
`WelcomePage` renders `section.page[data-breakpoint]` plus the `EmptyState`
`role="status"` panel; both are asserted in
`apps/staff-web/tests/responsive/app/WelcomePage.responsive.test.tsx` across the
three bands, the width matrix and Arabic RTL.

## Coverage

`bash apps/staff-web/check.sh` includes `npm run test:coverage` (Vitest/v8,
per-file thresholds 100/100/100/100). Result: Statements 100% (3722/3722),
Branches 100% (1892/1892), Functions 100% (1258/1258), Lines 100% (3556/3556).
The only changed production file, `src/app/WelcomePage.tsx`, is 100% on lines,
statements, functions and branches (0 branches).

New/changed branches and their tests (WelcomePage adds no `if`, ternary,
`&&`/`||`, `switch` or `catch`; the only variability is the value returned by the
existing `useBreakpoint` hook and the active locale):

- `useBreakpoint() === 'compact'` → `responsive WelcomePage at compact` tests and the 320/360/390 px rows of the width matrix assert `data-breakpoint="compact"`.
- `useBreakpoint() === 'medium'` → `responsive WelcomePage at medium` tests and the 768/820/1024 px rows assert `data-breakpoint="medium"`.
- `useBreakpoint() === 'expanded'` → `responsive WelcomePage at expanded` tests and the 1280 px row assert `data-breakpoint="expanded"`.
- `locale === 'ar'` → the `responsive WelcomePage right-to-left` tests assert `dir="rtl"`, `lang="ar"` and the Arabic welcome label at all three bands; the default English path is asserted by the per-band and width-matrix tests.

No threshold, exclusion, `skip` or pragma was added or changed. No code was
changed in this fix pass, so no new branch was introduced.

## Clinical and architecture review

Not required: presentation-only placeholder route, no signed data, patient
association, prescribing, critical results or cross-organization sharing.

## Findings

- Root cause of the draft pull request and issue #707: in the cloud worker, `opencode.json` is the CI profile (`.github/opencode.ci.json`) installed with `skip-worktree` (`git ls-files -v opencode.json` → `S`; worktree equals CI profile and differs from `HEAD:opencode.json`). `scripts/check-pack.py` judges that CI profile against `scripts/gen-opencode.py`, reports it stale, and makes `scripts/check.sh` and `scripts/worker-check.sh` exit 1 at their first policy step. The worker's `checks` status was therefore `failed` and the publish step marked the PR draft and opened a handoff issue. This is environmental and reproduces unchanged on a branch with no code edits; it is not caused by the scoped files. Per AGENTS.md and TODO.md rule 8 the protected file was not regenerated, edited or committed. Integrator/Delivery own the repository-wide gate in a clean checkout.
- The first worker's notes contained a `## Why this is not finished` section whose body said the story was complete. That heading is reserved for genuine early stops (`scripts/worker_gate.py` uses it to flag handoffs), so this fix pass deliberately omits it.
- Verification result: the scoped implementation is complete and passes every applicable gate — typecheck, lint, build, WEB-08 bundle budget, 159 test files / 1910 tests, and 100% coverage globally and on the changed production file. No corrective code change was needed.
- Pre-existing, outside scope and untouched: committed `origin/main` contains literal conflict markers in three `apps/api/src/main/java/com/aurevia/exchange/hl7/` Java files (`Hl7ParseException.java`, `Hl7Parser.java`, `Hl7Segment.java`). The merge reported "Already up to date" and no marker exists in either scoped file.
- `EmptyState` renders a nested `section.page[role="status"]`; the outer wrapper also uses `page`. The nesting is intentional and asserted by the tests. Heading queries are scoped by accessible name because the status panel has a second `<h2>`.

## Lessons for workers

- In a cloud worker, a locally red `check-pack` is usually the CI `opencode.json` under `skip-worktree`: check `git ls-files -v opencode.json` and compare the worktree file with `.github/opencode.ci.json` before attributing it to the change. Never regenerate or commit that protected file as a worker; run the canonical module gate instead and record the artifact.
- Omit `## Why this is not finished` when the story is complete. That heading is a machine-read handoff signal for an early stop, not a place for a "not applicable — complete" note; including it makes the orchestrator treat a finished worker as stopped early.
- `bash apps/staff-web/check.sh` is the authoritative frontend gate: it covers generated-client drift, typecheck, lint, build, bundle budget and 100% coverage in one command. Paste its typecheck lines and coverage summary into the notes rather than relying on a standalone Vitest run.
- `within(root)` does not exclude nested state panels; scope heading queries by accessible name when a page renders `EmptyState`.
- `data-breakpoint` is the established convention for the responsive band; prefer the shared `page` utility for safe-area/logical padding and add only the missing wrap, shrink and logical-text-start inline styles.
