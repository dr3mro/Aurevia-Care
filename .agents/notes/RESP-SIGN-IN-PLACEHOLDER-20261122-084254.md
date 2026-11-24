# RESP-SIGN-IN-PLACEHOLDER · Staff web: responsive `SignInPlaceholder`

## Summary

Made the development-preview sign-in placeholder present correctly from 320 px to 1280 px in
English and Arabic RTL without changing its exported name, text, behaviour or API calls.
The section now uses the shared `page` utility (safe-area-aware logical padding, single-column
grid, reading measure), exposes `data-breakpoint` via the real `useBreakpoint` hook, and uses
logical inline styles for text wrapping because `responsive.css` has no dedicated placeholder
class. Added a dedicated responsive Vitest file that mocks `matchMedia` for compact, medium and
expanded bands and checks structure, labels/roles, DOM (keyboard) order, the seven-width matrix
and the Arabic RTL case. No dependency, tracker, stylesheet or file outside the two-path scope
was changed.

## Affected requirements

- **WEB-07** — responsive staff web. The placeholder resolves `compact`/`medium`/`expanded`
  at 320, 360, 390, 768, 820, 1024 and 1280 px and keeps one non-scrolling single-column
  reading stack (decision 0004 extends support down to 320 px).
- **UX-01** — English and Arabic, RTL, logical properties, wrapping long foreign-script text,
  stable keyboard order, no hidden content. Verified with synthetic strings only.

Acceptance mapping:

| Rule | Implementation | Test |
|---|---|---|
| Breakpoints at 768/1280 | `data-breakpoint={useBreakpoint()}` on the section | `width matrix` (`it.each` over seven widths) + per-band `describe.each` |
| No horizontal scroll / stacked column | `page` class + `minInlineSize: 0`, `overflowWrap: anywhere` | per-band structure + logical-properties tests |
| Long EN/AR strings wrap, no clipping at 200% | `overflow-wrap: anywhere`, `white-space: normal`, no fixed height | per-band wrap test asserting style attributes |
| Logical properties only; safe areas | `page` utility; inline `min-inline-size`, `text-align: start` | `physicalStyleKeys` sweep over every styled element |
| 44×44 coarse targets / 16 px inputs | **Not applicable** — component has no interactive control or input | asserted zero focusable controls at every band |
| No clinical column/field hidden | no `display: none`, no conditional rendering | `display`/style assertions in both EN and AR tests |
| RTL mirrors, no local direction override | document `dir`/`lang` from `I18nProvider`; `text-align: start` | Arabic RTL test (`dir=rtl`, `lang=ar`, logical-style sweep) |
| Keyboard order stable | single static DOM order H2 → P | `children` tag order + zero focusable elements |

## Risk class

Routine — contained presentational change to one presentational component plus a new test file
(route `docs/WORKFLOW.md`). No data, contract, migration, authorization or clinical logic.

## Review owner

Senior React and Vite (`aurevia-senior-react`) — bounded read-only plan review completed;
recommendations (drop redundant inline `gap`, avoid `useTheme` without a provider, static class,
zero new branches) were applied. Senior TypeScript (`aurevia-senior-typescript`) and UX
(`aurevia-ux-reviewer`) skills loaded. UX review is **simulated**, not a human/synthetic-user
walkthrough.

## Data changes

None. No migrations, backfills, locks, volume impact, rollback or forward-fix.

## Authorization boundaries and audit events

None. The placeholder collects no credentials and renders no tenant, role or patient data; it
introduces no endpoint, permission or audit event. Existing behaviour (no email/password fields)
is asserted at every band.

## Migration and rollout considerations

None. Static client-only presentation; no rollout or data step.

## Test evidence

Evidence source: **executed tool commands** (local Vitest, typecheck, lint, build, coverage and
policy scripts). The specialist review is explicitly **simulated**; there was no human or
simulated-user participation in this task.

1. Red (before production change):
   `cd apps/staff-web && ./node_modules/.bin/vitest run tests/responsive/app/SignInPlaceholder.responsive.test.tsx --reporter=dot`
   Final lines: `Test Files 1 failed (1)` / `Tests 14 failed (14)` —
   `Error: responsive sign-in placeholder root was not rendered` (old markup had no
   `data-breakpoint`, `page` class or logical inline styles).

2. Green (new test + pre-existing test):
   `cd apps/staff-web && ./node_modules/.bin/vitest run tests/responsive/app/SignInPlaceholder.responsive.test.tsx tests/app/SignInPlaceholder.test.tsx --reporter=dot`
   Final lines: `Test Files 2 passed (2)` / `Tests 15 passed (15)`.

3. Module gate (authoritative for this story):
   `bash apps/staff-web/check.sh` → `EXIT=0` (gen:api:check, typecheck, lint, build, bundle budget,
   test:coverage all passed).

   Typecheck final lines (from `npm run typecheck`, exit 0 — no `error TS…`):
   ```
   > aurevia-staff-web@0.1.0 typecheck
   > tsc --noEmit -p tsconfig.json
   ```
   The build step re-ran `tsc --noEmit -p tsconfig.json && vite build` and succeeded.

   `tsc --noEmit`, `eslint . --max-warnings 0`, `prettier --check`, build and bundle budget are all
   covered by the module `check.sh`, which exited 0. (The console stack traces printed by
   `tests/theme/ThemeProvider.test.tsx` are that existing test's intentional negative path; the
   run reports 0 failures.)

   Test/coverage final lines from that run:
   ```
   Test Files  159 passed (159)
        Tests  1905 passed (1905)
   No files with missing coverage.
   207 files fully covered.
   Statements   : 100% ( 3722/3722 )
   Branches     : 100% ( 1892/1892 )
   Functions    : 100% ( 1258/1258 )
   Lines        : 100% ( 3556/3556 )
   ```

4. Repo-level final gate: `timeout 300 bash scripts/check.sh` → `EXIT=1` with the single line
   `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. This is a pre-existing,
   out-of-scope generated-config drift (see Findings / "Why this is not finished"); the module
   gate above is green. Fast policy checks run individually all exited 0:
   `check-docs.py`, `check-secrets.py`, `check-names.py`, `check-wiring.py`,
   `check-migrations.py --require-baseline`, `bash scripts/frontend-check.sh`.

5. Resync: `git merge --no-edit origin/main` → `Already up to date.` at start and before finish;
   targeted tests re-run after the final resync (15/15 passed).

Entry-point-to-test trace: the only in-repo caller is
`apps/staff-web/src/app/DesignPreview.tsx:63` (`<SignInPlaceholder />`), a development preview;
there is no production sign-in route by design (identity tooling owns authentication). The test
mounts the component directly under `I18nProvider` in
`tests/responsive/app/SignInPlaceholder.responsive.test.tsx`, while
`tests/app/SignInPlaceholder.test.tsx` keeps the original behaviour assertions green.

## Coverage

Command: `bash apps/staff-web/check.sh` (includes `npm run test:coverage`, v8 provider,
`perFile: true`, 100% line/branch/function/statement thresholds, no exclusions changed).
Result: 100% on all metrics — Statements 3722/3722, Branches 1892/1892, Functions 1258/1258,
Lines 3556/3556; 207 files fully covered.

Touched production file read directly from `apps/staff-web/coverage/coverage-summary.json`:
```
src/app/SignInPlaceholder.tsx: lines 3/3 (100%), functions 1/1 (100%),
statements 3/3 (100%), branches 0/0 (100%)
```

New/changed branches and their tests:

- `SignInPlaceholder.tsx` — **no new branches** (static markup; `useBreakpoint()` is an existing
  hook whose branches are already covered). Tests: all per-band cases in the new responsive file
  execute every rendered line in compact, medium and expanded bands, plus Arabic RTL.
- `useBreakpoint` transition from the hook (not changed here) is exercised at all three bands by
  the mocked `matchMedia` in the new test's `describe.each` and seven-width `it.each`.

No threshold was lowered, no exclusion added, no test skipped or disabled.

## Clinical and architecture review

Not required: no signed data, orders, results, prescriptions, cross-organization sharing,
patient association, release of information, downtime behaviour or critical results. No
PHI or credential appears in code, fixtures, logs or notes.

## Findings

- **Pre-existing repo gate blocker (not caused by this change):** `bash scripts/check.sh` exits 1
  at `check-pack.py` with `opencode.json is stale`. My two files cannot affect generated agent
  config; editing it is forbidden by this task. See "Why this is not finished".
- **Pre-existing, unrelated:** three HL7 Java files committed at HEAD
  (`apps/api/src/main/java/com/aurevia/exchange/hl7/Hl7ParseException.java`,
  `Hl7Parser.java`, `Hl7Segment.java`) contain literal `<<<<<<< HEAD` / `>>>>>>> origin/swarm/BUILD-37-469`
  markers inside Javadoc. Not touched; flagged so the integrator is not surprised by a repo-wide
  marker grep.
- **Scope observation:** this placeholder has no buttons, inputs, table, columns, drawer or rail.
  The 44×44 coarse-target, 16 px input and drawer/rail/card/table structural rules do not apply;
  the tests assert the absence of focusable controls and an unchanged H2→P DOM order instead.
- **UX observation (not changed):** the Arabic `signInNote` contains a Latin run ("Identity
  provider"). `AppShell` isolates the same key with `MixedScriptText`; this component still uses
  a plain `<p>` to respect "do not change text/behaviour". Flagged for a UX owner decision.
- **No CSS class added:** `responsive.css` already supplies `page` for safe areas, column layout
  and reading width. Text wrapping uses logical inline styles because no dedicated placeholder
  wrapper class exists.

## Lessons for workers

- In `apps/staff-web`, ESLint's `@typescript-eslint/no-unused-vars` flags underscore-prefixed stub
  parameters too — use zero-argument no-ops (`addEventListener: () => undefined`) in `matchMedia`
  fakes instead of `_type`/`_listener`.
- Run `npm ci` in `apps/staff-web` before a bare `vitest run`; without local `node_modules`, npx
  pulls a floating Vitest that cannot load `vite.config.ts`.
- Reuse `.page` as-is for safe-area padding, single-column grid and reading measure; do not
  redeclare `gap` or physical insets inline on it. Keep conditional classes out of presentation
  components so per-file branch coverage stays trivially 100%.
- Missing class for the stylesheet owner: `responsive.css` has no dedicated sign-in/placeholder
  content wrapper (only generic `.page`, `.stack`, `.cluster`, `.card-list`); wrapping currently
  needs logical inline styles (`min-inline-size: 0; overflow-wrap: anywhere; text-align: start`).

## Why this is not finished

The story's own scope is complete and its authoritative module gate is green. This section only
records an unrelated repository-level gate so it is not rediscovered:

- **Failed command and error:** `bash scripts/check.sh` → exit 1, output
  `check-pack: opencode.json is stale: run scripts/gen-opencode.py`.
- **Most likely cause:** the committed generated `opencode.json` has drifted from
  `scripts/gen-opencode.py` (or the documented CI-profile/worktree mismatch in MEMORY.md,
  OPS-CW-003-01). It is independent of staff-web source and was already red before this change.
- **What was tried:** ran `bash apps/staff-web/check.sh` (exit 0, 1905 tests, 100% coverage) and
  the individual policy scripts (`check-docs`, `check-secrets`, `check-names`, `check-wiring`,
  `check-migrations --require-baseline`, `frontend-check.sh`) — all exit 0; only `check-pack.py`
  fails.
- **Exact next step:** an authorized worker regenerates the config with
  `python3 scripts/gen-opencode.py`, commits the generated `opencode.json`, and re-runs
  `bash scripts/check.sh`. This worker must not edit generated config or `.opencode`, so it was
  left untouched.
