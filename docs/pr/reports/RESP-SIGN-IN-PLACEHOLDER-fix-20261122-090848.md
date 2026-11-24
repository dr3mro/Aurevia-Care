# RESP-SIGN-IN-PLACEHOLDER — fix pass (responsive `SignInPlaceholder`)

## Summary

Fix/verification pass for task RESP-SIGN-IN-PLACEHOLDER (issue #717, fix attempt 1 of 3).
The scoped implementation (a responsive `SignInPlaceholder` plus its responsive test) was
already implemented and committed on this branch by the first worker (`a6b36adc`); this pass
re-verified it end to end and found **no defect in the story scope**. No production or test
file was changed in this pass — the scoped files are byte-for-byte as committed. This notes
file is the only addition and is the hand-off / pull-request description.

Re-verification result: the authoritative module gate `bash apps/staff-web/check.sh` is green
(EXIT 0, 1905 tests, 100% coverage on all metrics); the two story tests pass 15/15; the
touched production file is 100% covered on lines, statements, functions and branches; all
fast policy checks pass. The single failing repository-level check (`scripts/check.sh` →
`check-pack: opencode.json is stale`) is the pre-existing, out-of-scope cloud-worker artifact
OPS-CW-003-01, proven below and handed to the integrator. The story scope is complete and
mergeable.

## Affected requirements

- **WEB-07** — responsive staff web. The placeholder resolves `compact`/`medium`/`expanded`
  at 320, 360, 390, 768, 820, 1024 and 1280 px (decision 0004 extends support down to 320 px).
- **UX-01** — English and Arabic RTL, logical properties, wrapping long foreign-script text,
  stable keyboard order, no hidden content. Verified with synthetic strings only.

Acceptance mapping (unchanged from the first pass; re-verified, not re-implemented):

| Task requirement | Implementation | Test |
|---|---|---|
| 7 widths, 3 bands | `data-breakpoint={useBreakpoint()}` on the section | `width matrix` (`it.each` over 7 widths) + per-band `describe.each` |
| No horizontal scroll / stacked column | `page` class + `minInlineSize: 0`, `overflowWrap: anywhere` | per-band structure + logical-style tests |
| Long EN/AR strings wrap, no fixed height | `overflow-wrap: anywhere`, `white-space: normal` | per-band wrap assertions on inline styles |
| Logical properties only; safe areas | `page` utility; logical inline styles | `physicalStyleKeys` sweep over every styled element, EN and AR |
| 44×44 coarse targets / 16 px inputs | Not applicable — no interactive control or input | zero focusable elements asserted at every band |
| No clinical column/field hidden | no `display: none`, no conditional rendering | `display`/style assertions in EN and AR |
| RTL mirrors, no local direction override | document `dir`/`lang` from `I18nProvider`; `text-align: start` | Arabic RTL test + logical-style sweep |
| Keyboard order stable | single static DOM order H2 → P | `children` tag order + zero focusable elements |

## Risk class

Routine — contained presentational component plus one new test file, in two scoped paths
(`docs/WORKFLOW.md`). No data, contract, migration, authorization or clinical logic.

## Review owner

Senior React and Vite (`aurevia-senior-react`) — bounded read-only plan review was already
completed in the first pass; its recommendations (no redundant inline `gap`, no `useTheme`
without a provider, static class, zero new branches) are present in the committed code.
Senior TypeScript (`aurevia-senior-typescript`) and UX (`aurevia-ux-reviewer`) skills
were loaded. All review was **simulated**; no human or simulated-user participation in this pass.

## Data changes

None. No migrations, backfills, locks, volume impact, rollback or forward-fix. No reserved
migration version used.

## Authorization boundaries and audit events

None. The placeholder collects no credentials and renders no tenant, role or patient data;
it introduces no endpoint, permission or audit event. Existing behaviour (no email/password
fields) is asserted at every band.

## Migration and rollout considerations

None. Static client-only presentation; no rollout or data step.

## Test evidence

Evidence source: **executed tool commands** in this worker (git, npm, Vitest, `bash
apps/staff-web/check.sh`, policy scripts). Specialist review is explicitly **simulated**.

1. Resync: `git merge --no-edit origin/main` → `Already up to date.` (branch
   `swarm/RESP-SIGN-IN-PLACEHOLDER-861`, HEAD `ab115906`). `git grep -nE '^(<<<<<<<|>>>>>>>) '`
   over scoped paths → no conflict markers. (The known literal markers in
   `exchange/hl7/Hl7ParseException.java` etc. are pre-existing Javadoc text outside scope.)

2. Dependency setup: `cd apps/staff-web && npm ci` → completed (6 moderate advisories reported
   by npm audit; no dependency added or changed by this task).

3. Story tests (re-run directly):
   `cd apps/staff-web && ./node_modules/.bin/vitest run tests/responsive/app/SignInPlaceholder.responsive.test.tsx tests/app/SignInPlaceholder.test.tsx --reporter=dot`
   Final lines: `Test Files 2 passed (2)` / `Tests 15 passed (15)`.

4. Module gate (authoritative for this story):
   `cd apps/staff-web && bash check.sh` → `EXIT=0` (gen:api:check, typecheck, lint, build,
   bundle budget, `test:coverage` all passed).
   - Typecheck final lines (exit 0, **no `error TS…`**):
     `> aurevia-staff-web@0.1.0 typecheck` /
     `> tsc --noEmit -p tsconfig.json`. The build step re-ran
     `tsc --noEmit -p tsconfig.json && vite build` and succeeded.
   - Final test/coverage lines:
     `Test Files 159 passed (159)` / `Tests 1905 passed (1905)` /
     `No files with missing coverage.` / `207 files fully covered.` /
     `Statements : 100% ( 3722/3722 )` / `Branches : 100% ( 1892/1892 )` /
     `Functions : 100% ( 1258/1258 )` / `Lines : 100% ( 3556/3556 )`.
   - The console stack traces in the log come from `tests/theme/ThemeProvider.test.tsx`'s
     intentional negative path; the run reports 0 failures.

5. Fast policy checks (all `EXIT=0`): `python3 -I scripts/check-docs.py`,
   `python3 -I scripts/check-secrets.py`, `python3 -I scripts/check-names.py`,
   `python3 -I scripts/check-wiring.py`, `python3 -I scripts/check-migrations.py
   --require-baseline`, `bash scripts/frontend-check.sh` (final line:
   `frontend standards: policy and tool commands passed`).

6. Repo-level gate diagnosis: `bash scripts/check.sh` stops at line 16 with
   `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. Proven to be OPS-CW-003-01:
   `git ls-files -v opencode.json` → flag `S` (skip-worktree); worktree `opencode.json` is
   39700 bytes and byte-identical to `.github/opencode.ci.json`; `git show HEAD:opencode.json`
   is 38387 bytes (the correct committed local profile). Not caused by this change; the
   integrator's trusted gate uses the committed HEAD file. See Findings.

Entry-point-to-test trace: the only in-repo caller is
`apps/staff-web/src/app/DesignPreview.tsx:63` (`<SignInPlaceholder />`), a development preview;
there is no production sign-in route by design (identity tooling owns authentication). The
test mounts the component directly under `I18nProvider` in
`tests/responsive/app/SignInPlaceholder.responsive.test.tsx`, while
`tests/app/SignInPlaceholder.test.tsx` keeps the original behaviour assertions green.

## Coverage

Command: `bash apps/staff-web/check.sh` (includes `npm run test:coverage`, v8 provider,
`perFile: true`, 100% line/branch/function/statement thresholds, no exclusions changed).
Result: 100% on all metrics — Statements 3722/3722, Branches 1892/1892, Functions 1258/1258,
Lines 3556/3556; 207 files fully covered.

Touched production file read directly from `apps/staff-web/coverage/coverage-summary.json` in
this pass:

```
src/app/SignInPlaceholder.tsx: lines 100% (100%), functions 100%, statements 100%, branches 100%
```

New/changed branches and their tests:

- `SignInPlaceholder.tsx` — **no new branches** in this pass (the file was not changed by this
  pass; the committed version is static markup). Tests: every rendered line executes in compact,
  medium and expanded bands plus Arabic RTL via the new responsive test file.
- `useBreakpoint` (not changed here) is driven to all three bands by the mocked `matchMedia` in
  the new test's `describe.each` and the seven-width `it.each`.

No threshold was lowered, no exclusion added, no test skipped or disabled.

## Clinical and architecture review

Not required: no signed data, orders, results, prescriptions, cross-organization sharing,
patient association, release of information, downtime behaviour or critical results. No PHI or
credential appears in code, fixtures, logs or notes.

## Findings

- **Story scope is complete and green.** No code defect was found in this fix pass; no scoped
  file needed changing. The authoritative module gate passes with 100% coverage.
- **Repo-level `check-pack` failure — known environment artifact (OPS-CW-003-01), not a change
  defect.** Command: `bash scripts/check.sh` → exit 1, final line
  `check-pack: opencode.json is stale: run scripts/gen-opencode.py`.
  - Cause: in this cloud worker `opencode.json` is flagged skip-worktree (`git ls-files -v` →
    `S`) and holds the CI profile copied by `agent-run.sh` (39700 bytes, equal to
    `.github/opencode.ci.json`), while `gen-opencode.py --check` / `check-pack.py` compare it
    against the committed local profile (`git show HEAD:opencode.json`, 38387 bytes). Recorded
    as a closed false positive in BUGS.md/MEMORY.md (OPS-CW-003-01); it affects all PRs in this
    worker environment and is independent of staff-web source.
  - Tried: confirmed flag `S` with `git ls-files -v`; compared byte counts with `wc -c` and
    content with `cmp` (worktree == CI profile, HEAD != CI profile). Did not regenerate or edit
    the protected file (forbidden by task scope; regenerating here would be wrong).
  - Exact next step (integrator/maintainer, not this worker): run the trusted gate with the
    committed HEAD profile in place, or regenerate with `python3 scripts/gen-opencode.py` and
    commit the result in an authorized change; expect `gen-opencode.py --check` and
    `check-pack.py` green. `scripts/check.sh` stops at this step, so all later stages were run
    individually above.
- **Scope observation:** this placeholder has no buttons, inputs, table, columns, drawer or
  rail. The 44×44 coarse-target, 16 px input and drawer/rail/card/table structural rules are
  not applicable; the tests assert the absence of focusable controls and an unchanged H2→P DOM
  order instead.
- **UX observation (not changed):** the Arabic `signInNote` contains a Latin run ("Identity
  provider"). `AppShell` isolates the same key with `MixedScriptText`; this component keeps a
  plain `<p>` to respect "do not change text/behaviour". Flagged for a UX owner decision.
- **No CSS class added:** `responsive.css` already supplies `page` for safe areas, column layout
  and reading width; text wrapping uses logical inline styles because no dedicated placeholder
  wrapper class exists.

## Lessons for workers

- In `apps/staff-web`, ESLint's `@typescript-eslint/no-unused-vars` flags underscore-prefixed
  stub parameters too — use zero-argument no-ops (`addEventListener: () => undefined`) in
  `matchMedia` fakes instead of `_type`/`_listener`.
- Run `npm ci` in `apps/staff-web` before a bare `vitest run`; without local `node_modules`, npx
  pulls a floating Vitest that cannot load `vite.config.ts`.
- Reuse `.page` as-is for safe-area padding, single-column grid and reading measure; keep
  conditional classes out of presentation components so per-file branch coverage stays 100%.
- On `check-pack: opencode.json is stale` in a cloud worker: check `git ls-files -v` (flag `S`)
  and `cmp` the worktree file against `.github/opencode.ci.json` and `git show HEAD:opencode.json`
  before treating it as a defect. The committed HEAD profile is the truth; do not regenerate the
  protected config from a worker PR.
- Missing class for the stylesheet owner: `responsive.css` has no dedicated sign-in/placeholder
  content wrapper (only generic `.page`, `.stack`, `.cluster`, `.card-list`); wrapping currently
  needs logical inline styles (`min-inline-size: 0; overflow-wrap: anywhere; text-align: start`).

## Why this is not finished

The story scope itself is finished: the scoped files are complete, the authoritative module gate
is green and every touched file is 100% covered. This section exists only to hand off the single
repository-level check that still fails for an out-of-scope environment reason, so the next
worker or person does not rediscover it:

- **Failed command and error:** `bash scripts/check.sh` → exit 1,
  `check-pack: opencode.json is stale: run scripts/gen-opencode.py`.
- **Most likely cause:** the cloud worker's skip-worktree `opencode.json` holds the CI profile,
  not the committed local profile (OPS-CW-003-01, closed false positive in BUGS.md/MEMORY.md).
  Independent of staff-web source; already red before this change on `origin/main`.
- **What was tried:** confirmed skip-worktree flag `S` and byte-level identity with the CI
  profile; ran `bash apps/staff-web/check.sh` (EXIT 0, 1905 tests, 100% coverage), the two story
  tests (15/15) and all fast policy checks (all EXIT 0). Editing generated config is forbidden
  by this task's scope, so it was left untouched.
- **Exact next step:** an authorized maintainer/integrator regenerates the config with
  `python3 scripts/gen-opencode.py` (or runs the trusted gate with the committed HEAD profile)
  and re-runs `bash scripts/check.sh`; no further work is needed on the story scope.
