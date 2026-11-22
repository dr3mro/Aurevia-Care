# RESP-SIGN-IN-PLACEHOLDER — fix pass 4 (responsive `SignInPlaceholder`)

## Summary

Fix/verification pass 4 for task RESP-SIGN-IN-PLACEHOLDER (issue #717, PR #716). I started from
the earlier findings and the step-6 debugger report and did **not** start over. The scoped
implementation — `apps/staff-web/src/app/SignInPlaceholder.tsx` plus
`apps/staff-web/tests/responsive/app/SignInPlaceholder.responsive.test.tsx` — is already committed
on this branch by the first worker; fix passes 1–3 changed no production or test file. This pass
again re-ran verification from a clean dependency install and again found **no defect in the story
scope**. No production or test file was changed this pass; this notes file is the only addition.

The story scope is complete and its authoritative gate is green (EXIT 0; 2043 tests; 100% coverage
on every metric; clean `tsc --noEmit`). The worker gate is still red for two reasons that are both
**outside this task's two-path scope** and **pre-existing on `origin/main`**: (a) two order-dependent
unit tests in the protected `scripts/tests/` tree that raise `FileNotFoundError` from a clean tree,
and (b) the documented OPS-CW-003-01 cloud-worker `opencode.json` profile overlay. I reproduced both
with commands below. Because the delivery skill and AGENTS.md forbid a swarm worker from editing
`scripts/`, `opencode.json`, workflows or trackers — and a PR touching them is never merged
automatically — I did not change them. A maintainer/person must own the `scripts/tests` fix; after
three prior fix rounds the debugger's own guidance is that a person takes over.

Evidence source for every claim: **executed tool commands** in this worker (git, `npm ci`, Vitest
via the module `check.sh`, the Python unittest suite, and direct file reads). Specialist review is
**simulated** (skills loaded: smartclinic-delivery, smartclinic-debugger, smartclinic-senior-python,
smartclinic-senior-react). There was **no** simulated-user walkthrough and **no** human participant.

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

Senior React and Vite (`smartclinic-senior-react`) owns the staff-web surface; its definition of
done (`check.sh` + typecheck final lines) is satisfied below. Senior TypeScript
(`smartclinic-senior-typescript`), UX (`smartclinic-ux-reviewer`), Debugger
(`smartclinic-debugger`) and Python (`smartclinic-senior-python`) skills were loaded. All review is
**simulated**; no human or simulated-user participation.

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

Evidence source: executed tool commands in this worker; no reviewer, simulated user or human
participant produced evidence in this task.

1. Resync and clean-tree checks:
   - `git merge --no-edit origin/main` → `Already up to date.` (branch
     `swarm/RESP-SIGN-IN-PLACEHOLDER-861`, HEAD `6525f846`).
   - `git grep -nE '^(<<<<<<<|>>>>>>>) ' -- apps/staff-web .agent/pr-notes` → no output (no markers).
   - `git diff --name-only origin/main...HEAD` → only the two scoped staff-web files plus
     `.agent/pr-notes/*` additions; no `scripts/`, workflow, tracker or config change.
   - `apps/api/target/surefire-reports` → **absent** (clean state, no stale Surefire XML).

2. Dependency setup from clean state: `cd apps/staff-web && npm ci` → `NPM_CI_EXIT=0`
   (existing lockfile audit notices only; no dependency added or changed).

3. Story tests / module gate (authoritative for this story): `bash apps/staff-web/check.sh` →
   `CHECK_EXIT=0`. All sub-steps passed (`gen:api:check`, typecheck, lint, `tsc --noEmit &&
   vite build`, bundle budget, `test:coverage`).

   Typecheck final lines (exit 0 — **no `error TS…`**):
   ```
   > smartclinic-staff-web@0.1.0 typecheck
   > tsc --noEmit -p tsconfig.json
   ```
   The build step re-ran `tsc --noEmit -p tsconfig.json && vite build` and succeeded.

   Test/coverage final lines from that run:
   ```
   Test Files  172 passed (172)
        Tests  2043 passed (2043)
   No files with missing coverage.
   213 files fully covered.
   4 empty files skipped.
   Statements   : 100% ( 3914/3914 )
   Branches     : 100% ( 1990/1990 )
   Functions    : 100% ( 1320/1320 )
   Lines        : 100% ( 3737/3737 )
   ```

4. Python gate suite from a clean tree, **before** restoring the profile:
   `timeout 280 python3 -I -m unittest discover -s scripts/tests` → `EXIT=1`,
   `Ran 1127 tests`, `FAILED (failures=6, errors=2)`.
   The 6 failures were all `test_check_pack.*` (the OPS-CW-003-01 profile overlay); the 2 errors
   were the order-dependent worker_gate tests below.

5. Same suite **after** a local-only profile restore
   (`git show HEAD:opencode.json > opencode.json`; file under `skip-worktree`, not committed,
   HEAD remains `38387` B): `Ran 1127 tests` → `FAILED (errors=2)`. The 6 `check_pack` failures
   disappeared, confirming they are the environment overlay. The 2 errors remain and are real.

6. Reproduced error trace (from the clean-tree run):
   ```
   ERROR: test_integration_failure_still_fails_the_gate (test_worker_gate.MainTest...)
     File "scripts/tests/test_worker_gate.py", line 336, in test_integration_failure_still_fails_the_gate
       self.assertEqual(worker_gate.main(["1"]), 1)
     File "scripts/worker_gate.py", line 260, in main
       usable = reports.is_dir() and usable_reports(reports)
     File "scripts/worker_gate.py", line 63, in usable_reports
       for report in sorted(Path(reports_dir).glob("TEST-*.xml")):
   FileNotFoundError: [Errno 2] No such file or directory: 'apps/api/target/surefire-reports'

   ERROR: test_maven_failure_with_failing_reports_still_fails (test_worker_gate_coverage.MainTest...)
     File "scripts/tests/test_worker_gate_coverage.py", line 554, in test_maven_failure_with_failing_reports_still_fails
       self.assertEqual(self.run_main(argv=["1"], env={"STORY_ID": "FIX-01"}, ...
     File "scripts/tests/test_worker_gate_coverage.py", line 521, in run_main
       return worker_gate.main(argv if argv is not None else ["0"])
     ... same usable_reports() -> FileNotFoundError ...
   ```

7. Final repo gate with the committed profile restored locally:
   `timeout 180 bash scripts/check.sh` → `CHECK_EXIT=1`. `check-pack` now passes
   (`Delivery tooling validated: 33 agents, 33 skills`), confirming OPS-CW-003-01; the only
   remaining failure is the Python suite, `Ran 1127 tests` → `FAILED (errors=2)`, the same two
   `scripts/tests` order-dependent tests as in step 5. This is the exact hand-off in
   "Why this is not finished".

Entry-point-to-test trace: the only in-repo caller is
`apps/staff-web/src/app/DesignPreview.tsx:63` (`<SignInPlaceholder />`), a development preview;
there is no production sign-in route by design (identity tooling owns authentication). The test
mounts the component directly under `I18nProvider` in
`tests/responsive/app/SignInPlaceholder.responsive.test.tsx`, while
`tests/app/SignInPlaceholder.test.tsx` keeps the original behaviour assertions green.

## Coverage

Command: `bash apps/staff-web/check.sh` (includes `npm run test:coverage`, v8 provider,
`perFile: true`, 100% line/branch/function/statement thresholds; no exclusion or threshold was
changed). Result: 100% on all metrics — Statements 3914/3914, Branches 1990/1990, Functions
1320/1320, Lines 3737/3737; 213 files fully covered.

Touched production file (`src/app/SignInPlaceholder.tsx`) is static markup with **no new branches**
(no `if`/ternary/logical operator). Every rendered line executes at compact, medium and expanded
bands plus Arabic RTL via the new responsive test file; `useBreakpoint` (not changed here) is
driven to all three bands by the mocked `matchMedia`. No threshold was lowered, no exclusion
added, no test skipped or disabled. No production code was changed by this pass, so no additional
branch-to-test mapping is required.

## Clinical and architecture review

Not required: no signed data, orders, results, prescriptions, cross-organization sharing, patient
association, release of information, downtime behaviour or critical results. No PHI or credential
appears in code, fixtures, logs or notes.

## Findings

1. **Story scope is complete and green.** No code defect found in any of four passes; the two
   scoped files are byte-for-byte as committed. The authoritative module gate passes with 100%
   coverage on every touched file and a clean typecheck.

2. **The real worker-gate blocker is a pre-existing, order-dependent defect in protected
   `scripts/tests/` — not the branch's staff-web change.** Two tests mock
   `worker_gate.Path.is_dir` → `True` but pass no report directory, so `worker_gate.main(["1"])`
   falls back to the default `apps/api/target/surefire-reports`; `usable_reports()` then globs a
   path that a clean tree does not have and raises `FileNotFoundError`. Exact locations:
   - `scripts/tests/test_worker_gate.py:328-336` —
     `MainTest.test_integration_failure_still_fails_the_gate`; `main(["1"])` at line 336.
   - `scripts/tests/test_worker_gate_coverage.py:552-555` —
     `MainTest.test_maven_failure_with_failing_reports_still_fails`; `run_main(argv=["1"], ...,
     reports=True)` at line 554; `run_main` helper at lines 513-521; the `is_dir=True` patch is at
     line 509.
   - Underlying code: `scripts/worker_gate.py:54-69` (`usable_reports`) and `:260` (`main`).
   These files are **byte-identical on `origin/main`** (`git diff --name-only
   origin/main...HEAD -- scripts/` is empty), so the defect predates this branch. The recurring
   `integration run failed without fresh, usable test results (compile or setup error)` lines in
   the issue context are captured `worker_gate.main()` stdout from these unit-test fixtures, not a
   failing real integration run; the actual integration run is green.

3. **`check-pack: opencode.json is stale` is OPS-CW-003-01, a documented environment artifact.**
   `git ls-files -v opencode.json` → flag `S` (skip-worktree); worktree file was `39700` B
   (identical to `.github/opencode.ci.json`) while `git show HEAD:opencode.json` is `38387` B.
   `agent-run.sh`'s `activate_ci_config` copied the CI profile over the worktree file. Restoring
   the committed profile locally removes all 6 `test_check_pack` failures; no source file was
   regenerated or committed. A clean checkout passes `check-pack`.

4. **No task-request block is included, by design.** The fix is in protected `scripts/tests/*`;
   `scripts/swarm.py:scope_ok()` rejects any `scripts/*` scope path (`PROTECTED` includes
   `"scripts/*"`), so a `task-requests` block would be silently dropped by the integrator, and a
   worker PR editing `scripts/` is never auto-merged (AGENTS.md). This needs a maintainer/person,
   which matches the debugger's "after 3 rounds a person must take over" guidance.

5. **Scope observation (unchanged):** this placeholder has no buttons, inputs, table, columns,
   drawer or rail. The 44×44 coarse-target, 16 px input and drawer/rail/card/table structural rules
   are not applicable; the tests assert the absence of focusable controls and an unchanged H2→P
   DOM order instead.

6. **UX observation (not changed):** the Arabic `signInNote` contains a Latin run ("Identity
   provider"). `AppShell` isolates the same key with `MixedScriptText`; this component keeps a
   plain `<p>` to respect "do not change text/behaviour". Flagged for a UX owner decision.

7. **No CSS class added:** `responsive.css` already supplies `page` for safe areas, single-column
   layout and reading width; wrapping uses logical inline styles because no dedicated placeholder
   wrapper class exists.

8. **Unrelated pre-existing repo noise:** earlier notes flag literal `<<<<<<< HEAD` / `>>>>>>>`
   markers inside Javadoc of three `exchange/hl7` Java files at HEAD; not touched and out of scope.

## Lessons for workers

- Run the **authoritative worker gate** (`bash scripts/worker-check.sh`) and the full Python suite
  (`python3 -I -m unittest discover -s scripts/tests`) from a **clean** tree
  (`rm -rf apps/api/target/surefire-reports`) before declaring a worker gate green. Three earlier
  fix passes ran only `bash apps/staff-web/check.sh` and mistook a green module gate for a green
  worker gate, which is why the integrator kept rejecting the PR.
- In this cloud worker, `git ls-files -v opencode.json` flag `S` plus a `39700`-byte worktree file
  means the CI profile is overlaid; `git show HEAD:opencode.json` (`38387` B) is the truth. Restore
  it locally with `git show HEAD:opencode.json > opencode.json` (local-only, not a commit) before
  running policy/gate checks, so `check_pack` failures do not mask real ones.
- Start a staff-web fix from a clean dependency install: `node_modules` may be absent; run
  `npm ci` in `apps/staff-web` before any bare `vitest run`, or npx pulls a floating Vitest that
  cannot load `vite.config.ts`.
- Reuse `.page` as-is for safe-area padding, single-column grid and reading measure; keep
  conditional classes out of presentation components so per-file branch coverage stays 100%.
- A worker cannot request a task in protected `scripts/*`: `scope_ok()` rejects it. Document the
  blocker with exact locations and hand off to a person instead.

## Why this is not finished

The story scope itself is finished: the scoped files are complete, the authoritative module gate
is green (2043 tests, 100% coverage, clean typecheck), and only this notes file is added. This
section records the single worker-gate failure that a person must resolve, so it is not rediscovered.

- **Failed command and error:** from a clean tree with the committed `opencode.json` profile
  restored locally, `python3 -I -m unittest discover -s scripts/tests` → `EXIT=1`,
  `Ran 1127 tests`, `FAILED (errors=2)`:
  `test_worker_gate.MainTest.test_integration_failure_still_fails_the_gate` and
  `test_worker_gate_coverage.MainTest.test_maven_failure_with_failing_reports_still_fails`, both
  `FileNotFoundError: 'apps/api/target/surefire-reports'` raised by `usable_reports()`
  (`scripts/worker_gate.py:63`). Before restoring the profile the same run also reported 6
  `test_check_pack` failures (OPS-CW-003-01 overlay).
- **Most likely cause:** the two tests pass `Path.is_dir=True` mocks but no real report directory,
  so `main(["1"])` uses the nonexistent default path. They are order-dependent on a pre-existing
  `apps/api/target/surefire-reports` directory from earlier Maven runs and are byte-identical on
  `origin/main`; they are not caused by this branch's responsive placeholder change.
- **What was tried:** ran `git merge --no-edit origin/main` (up to date), `npm ci`, the module gate
  (`bash apps/staff-web/check.sh`, EXIT 0), the Python suite twice (with and without the profile
  restore), and read the exact test/gate source. Did not edit `scripts/`, `opencode.json`,
  workflows or trackers — the delivery skill and AGENTS.md forbid it for a swarm worker, and such
  a PR is never auto-merged.
- **Exact next step (maintainer/person, not this worker):**
  1. In `scripts/tests/test_worker_gate.py:328-336` and
     `scripts/tests/test_worker_gate_coverage.py:552-555`, stop relying on `Path.is_dir` mocks plus
     the default path. Create a `tempfile.TemporaryDirectory()`, write one minimal well-formed
     `TEST-<Name>.xml` into it, and pass it as the second argument:
     `worker_gate.main(["1", str(tmpdir)])` (or mock `worker_gate.usable_reports` → `True`).
     Keep the existing assertions; add no pragma, exclusion or skip.
  2. Add a regression test that a strict repair task naming a failing class returns 1 with the
     repair-task message using an explicit fresh report directory, and confirm both fixed tests
     pass with no `apps/api/target/` directory.
  3. From that clean state run `python3 -I -m unittest discover -s scripts/tests` → `OK`, then
     `bash scripts/worker-check.sh` → exit 0 ("worker check: quick gate, frontend standards,
     integration tests and database policy passed"), with the committed profile in place.
  4. Re-run `bash apps/staff-web/check.sh` (expected EXIT 0, as proven above) to confirm no
     regression in the story scope, then close issue #717 / PR #716.
