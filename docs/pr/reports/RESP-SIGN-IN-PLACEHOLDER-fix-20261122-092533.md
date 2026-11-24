# RESP-SIGN-IN-PLACEHOLDER — fix pass 3 (responsive `SignInPlaceholder`)

## Summary

Fix/verification pass 3 for task RESP-SIGN-IN-PLACEHOLDER (issue #717, PR #716). I started from
the earlier findings and did **not** start over. The scoped implementation — responsive
`apps/staff-web/src/app/SignInPlaceholder.tsx` plus its new
`apps/staff-web/tests/responsive/app/SignInPlaceholder.responsive.test.tsx` — was committed on
this branch by the first worker (`a6b36adc`); fix passes 1 and 2 (`74ddb34c`, `67938620`) added
only notes and found no scope defect. This pass independently re-ran the verification from a
clean dependency install and again found **no defect in the story scope**: no production or test
file was changed. This notes file is the only addition and is the hand-off / pull request
description.

Verdict: the story scope is complete and mergeable. The authoritative module gate
`bash apps/staff-web/check.sh` is green (EXIT 0; 1905 tests; 100% coverage on every metric), the
two story tests pass 15/15, typecheck is clean, and `scripts/frontend-check.sh` passes. The single
failing repository-level check is `bash scripts/check.sh` → `check-pack: opencode.json is stale`,
proven below to be the pre-existing, out-of-scope cloud-worker artifact OPS-CW-003-01. It cannot
be fixed inside this task's two-path scope and is handled by the integrator's trusted
clean-checkout gate.

Evidence source for every claim below: **executed tool commands** in this worker (git, npm,
Vitest, `bash apps/staff-web/check.sh`, `bash scripts/frontend-check.sh`, `bash scripts/check.sh`,
`check-pack.py`, and an in-process `gen-opencode.py` render comparison). Specialist review is
explicitly **simulated** (skills loaded and applied in earlier passes). There was **no**
simulated-user walkthrough and **no** human participant in this task.

## Affected requirements

- **WEB-07** — responsive staff web. The placeholder resolves `compact`/`medium`/`expanded` at
  320, 360, 390, 768, 820, 1024 and 1280 px (decision 0004 extends support down to 320 px).
- **UX-01** — English and Arabic RTL, CSS logical properties, wrapping of long foreign-script
  text, stable keyboard order, no hidden content. Verified with synthetic strings only.

Acceptance mapping (re-verified, not re-implemented this pass):

| Task requirement | Implementation | Test |
|---|---|---|
| 7 widths, 3 bands | `data-breakpoint={useBreakpoint()}` on the section | width `it.each` over 7 widths + per-band `describe.each` |
| No horizontal scroll / stacked column | `page` class + `minInlineSize: 0`, `overflowWrap: anywhere` | per-band structure and logical-style assertions |
| Long EN/AR strings wrap | `overflow-wrap: anywhere`, `white-space: normal`, no fixed height | per-band wrap assertions on inline styles |
| Logical properties only; safe areas | `page` utility + logical inline styles | `physicalStyleKeys` sweep over every styled element, EN and AR |
| 44×44 coarse targets / 16 px inputs | Not applicable — no interactive control or input | zero focusable controls asserted at every band |
| No clinical column/field hidden | no `display: none`, no conditional rendering | `display`/style assertions in EN and AR |
| RTL mirrors, no local direction override | document `dir`/`lang` from `I18nProvider`; `text-align: start` | Arabic RTL test + logical-style sweep |
| Keyboard order stable | single static DOM order H2 → P | `children` tag order + zero focusable elements |

## Risk class

Routine — contained presentational change to one component plus one new test file in two scoped
paths (`docs/WORKFLOW.md`). No data, contract, migration, authorization or clinical logic.

## Review owner

Senior React and Vite (`aurevia-senior-react`) owns the staff-web surface; its definition of
done (`check.sh` + typecheck final lines) is satisfied below. Senior TypeScript
(`aurevia-senior-typescript`) and UX (`aurevia-ux-reviewer`) skills were loaded and applied
in earlier passes. All review is **simulated**; no human or simulated-user participation.

## Data changes

None. No migrations, backfills, locks, volume impact, rollback or forward-fix. No reserved
migration version used.

## Authorization boundaries and audit events

None. The placeholder collects no credentials and renders no tenant, role or patient data; it
introduces no endpoint, permission or audit event. Existing behaviour (no email/password fields)
is asserted at every band.

## Migration and rollout considerations

None. Static client-only presentation; no rollout or data step.

## Test evidence

1. Resync: `git merge --no-edit origin/main` → `Already up to date.` at the start.
   `git grep -nE '^(<<<<<<<|>>>>>>>) ' -- apps/staff-web .agent/pr-notes` → no conflict markers.
   Working tree clean before edits; branch `swarm/RESP-SIGN-IN-PLACEHOLDER-861`.

2. Dependency setup from clean state (`node_modules` was MISSING):
   `cd apps/staff-web && npm ci` → exit 0 (`6 moderate severity vulnerabilities` from the existing
   lockfile; no dependency added or changed by this task).

3. Story tests (direct):
   `cd apps/staff-web && ./node_modules/.bin/vitest run tests/responsive/app/SignInPlaceholder.responsive.test.tsx tests/app/SignInPlaceholder.test.tsx --reporter=dot`
   Final lines:
   ```
   Test Files  2 passed (2)
        Tests  15 passed (15)
   ```
   (The first worker's red evidence is in `RESP-SIGN-IN-PLACEHOLDER-20261122-084254.md`:
   14 failed before the production change.)

4. Module gate (authoritative for this story): `bash apps/staff-web/check.sh` → `EXIT=0`.
   All sub-steps passed: `gen:api:check`, typecheck, `eslint . --max-warnings 0` (no warnings),
   `tsc --noEmit -p tsconfig.json && vite build`, bundle budget and `test:coverage`.
   Typecheck final lines (exit 0 — **no `error TS…`**):
   ```
   > aurevia-staff-web@0.1.0 typecheck
   > tsc --noEmit -p tsconfig.json
   ```
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
   The console stack traces in the log come from `tests/theme/ThemeProvider.test.tsx`'s
   intentional negative path; the run reports 0 failures.

5. Frontend standards policy: `bash scripts/frontend-check.sh` → exit 0, final line
   `frontend standards: policy and tool commands passed`.

6. Repo-level gate diagnosis: `timeout 300 bash scripts/check.sh` → `EXIT=1`, single line
   `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. Confirmed as OPS-CW-003-01
   below. All steps before `check-pack` in `quick-check.sh` (shell lint, project-name) passed; the
   module gate (step 4) covers the staff-web standards independently.

Entry-point-to-test trace: the only in-repo caller is
`apps/staff-web/src/app/DesignPreview.tsx:63` (`<SignInPlaceholder />`), a development preview;
there is no production sign-in route by design (identity tooling owns authentication). The test
mounts the component directly under `I18nProvider` in
`tests/responsive/app/SignInPlaceholder.responsive.test.tsx`, while
`tests/app/SignInPlaceholder.test.tsx` keeps the original behaviour assertions green.

## Coverage

Command: `bash apps/staff-web/check.sh` (includes `npm run test:coverage`, v8 provider,
`perFile: true`, 100% line/branch/function/statement thresholds; no exclusion or threshold was
changed). Result: 100% on all metrics — Statements 3722/3722, Branches 1892/1892, Functions
1258/1258, Lines 3556/3556; 207 files fully covered.

Touched production file read directly from `apps/staff-web/coverage/coverage-summary.json`
generated by this run:

```
src/app/SignInPlaceholder.tsx: lines 3/3 (100%), statements 3/3 (100%),
functions 1/1 (100%), branches 0/0 (100%)
```

New/changed branches and their tests:

- `SignInPlaceholder.tsx` — **no new branches** (static markup; no `if`/ternary/logical operators).
  Every rendered line executes at compact, medium and expanded bands plus Arabic RTL via the new
  responsive test file.
- `useBreakpoint` (not changed here) is driven to all three bands by the mocked `matchMedia` in
  the new test's `describe.each` and its seven-width `it.each`.

No threshold was lowered, no exclusion added, no test skipped or disabled.

## Clinical and architecture review

Not required: no signed data, orders, results, prescriptions, cross-organization sharing, patient
association, release of information, downtime behaviour or critical results. No PHI or credential
appears in code, fixtures, logs or notes.

## Findings

- **Story scope is complete and green.** No code defect found in any of the three passes; the two
  scoped files are byte-for-byte as committed. The authoritative module gate and the frontend
  policy gate pass with 100% coverage on every touched file.
- **Repo-level `check-pack` failure is a proven environment artifact, not a change defect.**
  Command: `bash scripts/check.sh` → exit 1, final line
  `check-pack: opencode.json is stale: run scripts/gen-opencode.py`.
  Evidence gathered this pass:
  - `git ls-files -v opencode.json` → flag `S` (skip-worktree).
  - `wc -c`: worktree `opencode.json` = 39700 B, `.github/opencode.ci.json` = 39700 B,
    `git show HEAD:opencode.json` = 38387 B.
  - `cmp`: worktree `==` CI profile; worktree `!=` HEAD profile.
  - In-process render comparison using `scripts/gen-opencode.py`:
    `git show HEAD:opencode.json == render(root, ci=False)` → **True** (38387 B), and
    `git show HEAD:.github/opencode.ci.json == render(root, ci=True)` → **True** (39700 B).
    So the **committed** profiles are correct and not stale: a clean checkout passes
    `check-pack`. The failure occurs only because this cloud worker's `agent-run.sh` copied the
    CI profile into the skip-worktree worktree file, which `check-pack.py` compares against the
    local render. This is the closed false positive recorded as OPS-CW-003-01 in BUGS.md/MEMORY.md.
  - Tried: ran the module gate (green), frontend policy (green), story tests (15/15) and the
    `gen-opencode.py` render comparison. Did **not** edit, regenerate or commit the protected
    config (forbidden by task scope; the committed file is already correct).
  - Exact next step (integrator/maintainer, not this worker): run the trusted gate on a clean
    checkout of the merge result, where `opencode.json` is the committed HEAD profile; expect
    `check-pack.py` and `gen-opencode.py --check` green. No further story-scope work is needed.
- **Scope observation:** this placeholder has no buttons, inputs, table, columns, drawer or rail.
  The 44×44 coarse-target, 16 px input and drawer/rail/card/table structural rules are not
  applicable; the tests assert the absence of focusable controls and an unchanged H2→P DOM order
  instead.
- **UX observation (not changed):** the Arabic `signInNote` contains a Latin run ("Identity
  provider"). `AppShell` isolates the same key with `MixedScriptText`; this component keeps a
  plain `<p>` to respect "do not change text/behaviour". Flagged for a UX owner decision.
- **No CSS class added:** `responsive.css` already supplies `page` for safe areas, single-column
  layout and reading width; wrapping uses logical inline styles because no dedicated placeholder
  wrapper class exists.
- **Unrelated pre-existing repo noise:** earlier notes flag literal `<<<<<<< HEAD` / `>>>>>>>`
  markers inside Javadoc of three `exchange/hl7` Java files at HEAD; not touched by this task and
  outside its scope.

## Lessons for workers

- The swarm worker gate (`worker-check.sh`/`check.sh`) reports "local checks: failed" at
  `check-pack: opencode.json is stale` in this cloud-worker environment regardless of source
  correctness. Before treating it as a defect, run `git ls-files -v opencode.json` (flag `S`),
  `cmp` the worktree file against `.github/opencode.ci.json` and `git show HEAD:opencode.json`,
  and compare `git show HEAD:opencode.json` with `gen-opencode.py`'s local render. The committed
  HEAD profile is the truth; do not regenerate the protected config from a worker PR. Use
  `bash apps/staff-web/check.sh` as the story-authoritative gate.
- Start a staff-web fix from a clean dependency install: `node_modules` may be absent in a fresh
  worker; run `npm ci` in `apps/staff-web` before any bare `vitest run`, or npx pulls a floating
  Vitest that cannot load `vite.config.ts`.
- Reuse `.page` as-is for safe-area padding, single-column grid and reading measure; keep
  conditional classes out of presentation components so per-file branch coverage stays 100%.
- In `apps/staff-web`, ESLint's `@typescript-eslint/no-unused-vars` flags underscore-prefixed stub
  parameters too — use zero-argument no-ops (`addEventListener: () => undefined`) in `matchMedia`
  fakes instead of `_type`/`_listener`.
- Missing class for the stylesheet owner: `responsive.css` has no dedicated sign-in/placeholder
  content wrapper (only generic `.page`, `.stack`, `.cluster`, `.card-list`); wrapping currently
  needs logical inline styles (`min-inline-size: 0; overflow-wrap: anywhere; text-align: start`).

## Why this is not finished

The story scope itself is finished: the scoped files are complete, the authoritative module gate
and frontend policy gate are green, and every touched file is 100% covered. This section exists
only to hand off the single repository-level check that still fails for an out-of-scope
environment reason, so the next worker or person does not rediscover it.

- **Failed command and error:** `bash scripts/check.sh` → exit 1,
  `check-pack: opencode.json is stale: run scripts/gen-opencode.py`.
- **Most likely cause:** the cloud worker's skip-worktree `opencode.json` holds the CI profile
  (39700 B), not the committed local profile (38387 B), so `check-pack.py` finds a mismatch.
  Proven this pass: the committed HEAD `opencode.json` and `.github/opencode.ci.json` both exactly
  match `gen-opencode.py`'s respective renders, so a clean checkout is not stale. This is the
  closed false positive OPS-CW-003-01; it is independent of staff-web source and was already red
  before this change on `origin/main`.
- **What was tried:** confirmed skip-worktree flag `S`, byte counts and `cmp` identities; compared
  HEAD profiles with the generator renders; ran `bash apps/staff-web/check.sh` (EXIT 0, 1905 tests,
  100% coverage), the two story tests (15/15), `bash scripts/frontend-check.sh` (EXIT 0) and
  `bash scripts/check.sh` (EXIT 1 at check-pack). Editing or regenerating the protected config is
  forbidden by this task's scope and the committed file is already correct, so it was left
  untouched.
- **Exact next step:** the integrator runs its trusted gate on a clean checkout of the merge
  result (committed HEAD profile), where `check-pack.py` and `gen-opencode.py --check` pass; no
  further work is needed on the story scope. If a person must run `scripts/check.sh` inside this
  cloud-worker worktree, restore the committed profile locally first
  (`git show HEAD:opencode.json > opencode.json`) — this is a local-only artifact fix, not a commit.
