# RESP-WELCOME-PAGE — Staff web: responsive `WelcomePage` (app)

## Summary

`WelcomePage` now renders through the shared `page` utility and the real
`useBreakpoint` hook, exposing `data-breakpoint` and direction-neutral logical
inline styles so the heading and the empty state reflow at every supported band
in English and Arabic. A new responsive test drives `matchMedia` for compact,
medium and expanded and asserts structure, labels, roles, DOM/keyboard order,
the full 320–1280 px width matrix and the RTL case. Text, behaviour, API calls
and exported names are unchanged; no new dependency, stylesheet or CSS class was
added.

## Affected requirements

WEB-07, UX-01

## Risk class

Routine (contained staff-web view plus a new isolated test; no cross-boundary or
safety-critical contract). Classified per `docs/WORKFLOW.md`.

## Review owner

Primary agent. Simulated reviews: `orevia-senior-react` (no actionable
findings) and `orevia-ux-reviewer` (no actionable findings); both were
static reviews that relied on the primary agent's tool evidence because those
subagents had no shell. No human or clinical participant.

## Data changes

None. No migration, backfill, lock or volume impact.

## Authorization boundaries and audit events

None. This is a presentation-only change to a placeholder route; no tenant,
role, patient-link or audit behaviour changed.

## Migration and rollout considerations

None.

## Test evidence

Commands run (final lines quoted):

- `npm ci --no-audit --no-fund` → `added 486 packages in 4s` (fresh worker; needed before local vitest).
- Red first, before the production change:
  `./node_modules/.bin/vitest run tests/responsive/app/WelcomePage.responsive.test.tsx`
  → `Test Files 1 failed (1)`, `Tests 19 failed (19)` (no `data-breakpoint`, no logical wrapper styles).
- Green after the change:
  `./node_modules/.bin/vitest run tests/responsive/app/WelcomePage.responsive.test.tsx`
  → `Test Files 1 passed (1)`, `Tests 19 passed (19)`.
- `bash apps/staff-web/check.sh` →
  `Test Files 159 passed (159)`, `Tests 1910 passed (1910)`,
  `No files with missing coverage.` / `207 files fully covered.` / `4 empty files skipped.`
- `npm run typecheck` → `tsc --noEmit -p tsconfig.json` (no errors, exit 0).
- `npm run lint` → `eslint . --max-warnings 0` (no warnings, exit 0).
- `npm run gen:api:check` → `gen:api: generated client matches the contract`.
- `node -e` over `coverage/coverage-summary.json` → `src/app/WelcomePage.tsx`: lines 3/3 (100%), functions 1/1 (100%), statements 3/3 (100%), branches 0/0 (100%).
- `scripts/check.sh` (run once at the end, capped at 5 min) → stopped immediately at `check-pack: opencode.json is stale: run scripts/gen-opencode.py` (exit 1). This is the documented cloud-worker CI-profile artifact, not a change defect — see Findings. It was not re-run or bypassed.

Entry point → test trace: `apps/staff-web/src/app/routes.tsx` lazily registers
`WelcomePage` for pattern `/`; `AppShell` renders it inside the `<main>` landmark;
`WelcomePage` renders `section.page[data-breakpoint]` plus the `EmptyState`
`role="status"` panel; both are asserted in
`apps/staff-web/tests/responsive/app/WelcomePage.responsive.test.tsx` across the
three bands, the width matrix and Arabic RTL. Note: `check.sh` logs expected
React error stacks from existing negative `useI18n`/`useTheme` provider tests;
they do not fail the run.

## Coverage

`bash apps/staff-web/check.sh` includes `npm run test:coverage` (Vitest/v8,
per-file thresholds 100/100/100/100). Result:

```
Statements   : 100% ( 3722/3722 )
Branches     : 100% ( 1892/1892 )
Functions    : 100% ( 1258/1258 )
Lines        : 100% ( 3556/3556 )
```

New/changed branches and their tests (WelcomePage adds no `if`, ternary,
`&&`/`||`, `switch` or `catch`; the only variability is the value returned by
the existing `useBreakpoint` hook and the active locale):

- `useBreakpoint() === 'compact'` → `responsive WelcomePage at compact` tests and the 320/360/390 px rows of the width matrix assert `data-breakpoint="compact"`.
- `useBreakpoint() === 'medium'` → `responsive WelcomePage at medium` tests and the 768/820/1024 px rows assert `data-breakpoint="medium"`.
- `useBreakpoint() === 'expanded'` → `responsive WelcomePage at expanded` tests and the 1280 px row assert `data-breakpoint="expanded"`.
- `locale === 'ar'` → the `responsive WelcomePage right-to-left` tests assert `dir="rtl"`, `lang="ar"` and the Arabic welcome label at all three bands; the default English path is asserted by the per-band and width-matrix tests.

No threshold, exclusion, `skip` or pragma was added or changed.

## Clinical and architecture review

Not required: presentation-only placeholder route, no signed data, patient
association, prescribing, critical results or cross-organization sharing.

## Findings

- `EmptyState` renders a nested `<section class="page" role="status">` with its own `data-breakpoint`; the outer welcome wrapper also uses `page`. The nesting is intentional (outer = centred reading measure and safe-area padding, inner = centred state panel) and is asserted by the tests. Because the panel contains a second `<h2>` ("Nothing to show" / "لا يوجد شيء لعرضه"), heading queries are scoped by accessible name to stay unambiguous.
- No class was missing from `apps/staff-web/src/theme/responsive.css`: `page` already supplies safe-area-aware logical padding, a centred reading measure and a single `minmax(0, 1fr)` column. Only shrink-to-fit, wrapping of long labels and a logical text start needed inline styles, and no dedicated class exists for those on a section wrapper, so logical inline styles were used per the task rule. The stylesheet was not edited.
- This placeholder route has no inputs and no interactive controls, so the 16 px input-text and 44 × 44 coarse-pointer target rules are not applicable here; they are owned by the shared `Form`/`FormField` and `Button` components. The keyboard-order test asserts no forced `tabindex` and the heading-before-status DOM order.
- Pre-existing, outside scope and not touched: committed `origin/main` contains literal conflict markers in three `apps/api/src/main/java/com/aurevia/exchange/hl7/` Java files (`Hl7ParseException.java`, `Hl7Parser.java`, `Hl7Segment.java`). The merge with `origin/main` reported "Already up to date"; no marker exists in either scoped file.
- `scripts/check.sh` cannot complete in this cloud worker: verified `git ls-files -v opencode.json` → `S` (skip-worktree), `opencode.json` is byte-identical to `.github/opencode.ci.json`, and `opencode.json` is absent from my diff. `check-pack.py` therefore judges the CI profile rather than the HEAD/local profile and reports it stale. Per AGENTS.md/TODO rule 8 the protected file was not regenerated, edited or committed; the canonical module gate `bash apps/staff-web/check.sh` passed in full instead. Integrator/Delivery own the repository-wide gate.
- Simulated `orevia-senior-react` review: no actionable findings; confirmed the change is presentational, follows the established `data-breakpoint` convention, uses logical properties only, and that no behaviour/text/export changed. It could not re-run checks (no shell) and relied on primary-agent evidence.
- Simulated `orevia-ux-reviewer` review: no actionable findings; confirmed RTL mirroring with no local direction override, wrapping, no hidden content, DOM/keyboard order and safe-area via `page`. Its non-blocking observation — outer `.page` nesting the `EmptyState` `StatePanel` also uses `.page` and applies safe-area padding twice — is an existing shared pattern (`Form.tsx`, `ApiPage.tsx`) outside this scope, not introduced here.

## Lessons for workers

- `within(root)` does not exclude nested state panels: when a page renders `EmptyState`, scope heading queries by accessible name (for example `{ level: 2, name: 'Welcome' }`) instead of level alone, or the two `<h2>` elements make `getByRole` ambiguous.
- Install pinned dependencies with `npm ci` before running vitest in a fresh worker; `npx vitest` downloads an unpinned version and fails to resolve the module's local `vite.config.ts`.
- Prefer the shared `page` utility for safe-area/logical padding and add only the missing wrap/shrink/logical-text-start inline styles; do not duplicate the safe-area `env()` mapping in a component.
- A locally red `scripts/check.sh` at `check-pack.py` in a cloud worker is usually the CI `opencode.json` under `skip-worktree`: check `git ls-files -v opencode.json` and compare the worktree file with `.github/opencode.ci.json` before concluding the change is at fault. Never regenerate or commit that protected file as a worker.
- `data-breakpoint` is the established convention for exposing the responsive band; use it on the wrapper rather than inventing a new attribute or a breakpoint-specific DOM branch.

## Why this is not finished

Not applicable — the story is complete: the new test is green, existing tests pass, and `apps/staff-web/check.sh` proves 100% coverage on every touched area.
