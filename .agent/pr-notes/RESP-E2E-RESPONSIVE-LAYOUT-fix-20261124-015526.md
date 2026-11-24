## Summary

Fix pass for `RESP-E2E-RESPONSIVE-LAYOUT`. The in-scope deliverable
`apps/staff-web/e2e/responsiveLayout.spec.ts` (31 registered routes plus one RTL
journey across 10 Playwright projects; 320 enumerated tests) is retained
byte-identical. The previous fix attempt also edited
`apps/staff-web/tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts`
to refresh a stale e2e-spec pin. That file is outside this task's scope and the
integrator rejected the pass with a `scope` violation. This pass reverts that
out-of-scope edit (commit `66ca5759`) so the net diff against `origin/main` is
only the in-scope spec plus notes files. No production source, configuration,
dependency, migration, OpenAPI contract or workflow changed.

## Affected requirements

WEB-07 (supported clients and responsive browser matrix), UX-01 (English/Arabic
RTL, accessibility-compatible presentation). Acceptance mapping:

| Acceptance criterion | Assertion in `responsiveLayout.spec.ts` |
|---|---|
| No horizontal page scroll on every route at every project width | `expectNoHorizontalScroll` (`:80-89`) in every per-route test and the RTL test |
| Navigation is a drawer, icon rail or full rail as the width demands | `expectNavigation` (`:107-124`): compact drawer, medium `[data-rail="icon"]`, expanded `[data-rail="full"]`, drawer absent when non-compact |
| Main landmark reachable and a visible page heading | `expectLandmarks` (`:127-137`): `main#main`, `header[role="banner"] > h1`, `main#main h1, main#main h2` |
| One test per route and project; messages name route, project and measurement | `responsiveRoutes` loop (`:182-187`) plus the RTL test (`:189-212`), with the `context` string in every assertion message |
| RTL toggle keeps the shell intact | language-toggle test asserts `html[dir="rtl"]` and re-runs the shell contract |
| Run `--list` when no browser is installed | `--list` executed; live run reported unavailable; no test skipped or weakened |

## Risk class

Routine — one new end-to-end spec, no production or contract change. The
mandatory plan, code, coverage and security reviews were still run because the
spec asserts a cross-device contract.

## Review owner

Primary agent. Read-only reviewers: `smartclinic-plan-reviewer`,
`smartclinic-code-reviewer`, `smartclinic-coverage-auditor`,
`smartclinic-devsecops`.

## Data changes

None. No migration, backfill, lock, volume impact, rollback or forward-fix. All
identifiers are synthetic (`SYN-0001`, `SYN-0002`, `/no-such-route`); no patient
data, credential or real domain appears in the spec, logs or notes.

## Authorization boundaries and audit events

None. No role, permission, tenant or patient-link rule changed. The test
observes the fixed synthetic `reception` role in production `AppShell`; it does
not grant or override access. No audit event added or changed.

## Migration and rollout considerations

None. No schema or deployment change.

## Test evidence

Evidence source: executed tool commands in the worker checkout on branch
`swarm/RESP-E2E-RESPONSIVE-LAYOUT-1361`, final HEAD `66ca5759`; reviewer
verdicts are simulated read-only review, not human or clinical evidence. No
browser is installed in this sandbox, so the live Playwright run is unavailable
and the enumeration command was used instead.

- `git merge --no-edit origin/main` -> `Already up to date.` (at story start).
- Tool reproduction of the integrator finding: `python3 -I` +
  `worker_gate.validate_candidate` on the pre-revert committed diff returned
  `[{"file": "apps/staff-web/tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts", "rule": "scope"}]`.
  After the revert it returns `[]`; `changed_paths()` =
  `['.agent/pr-notes/RESP-E2E-RESPONSIVE-LAYOUT-20261124-004500.md', 'apps/staff-web/e2e/responsiveLayout.spec.ts']`.
- `npx eslint e2e/responsiveLayout.spec.ts --max-warnings=0` (in
  `apps/staff-web`) -> exit 0, no output.
- `npx playwright test --config e2e/playwright.config.ts e2e/responsiveLayout.spec.ts --list`
  -> exit 0; final line `Total: 320 tests in 1 file`. Ten projects: `chromium`
  plus `phone-portrait-360x740`, `phone-portrait-390x844`,
  `phone-landscape-844x390`, `tablet-portrait-768x1024`,
  `tablet-portrait-820x1180`, `tablet-landscape-1024x768`,
  `tablet-landscape-1180x820`, `laptop-1280x720`, `desktop-1920x1080`. WebKit
  and Firefox projects are omitted by `playwright.config.ts` because those
  binaries are absent.
- `npm run typecheck` -> exit 0, no `error TS…` line; output ended at
  `> tsc --noEmit -p tsconfig.json`. (`tsconfig.json` includes only `src`,
  `tests` and `vite.config.ts`, so the spec is validated by ESLint and
  Playwright transpilation, not by this command.)
- `npm run lint` -> exit 0, no findings.
- `npx vitest run tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts`
  on the final tree -> `Test Files  1 failed (1)`, `Tests  1 failed | 2 passed (3)`,
  failure at line 166 `expected [ …(15) ] to deeply equal [ …(9) ]`. With the
  out-of-scope pin fix still present this command passed `3/3`, confirming the
  failure is the pin, not this spec.
- `npm run -s test:coverage` on the final tree -> EXIT 1,
  `Test Files  1 failed | 270 passed (271)`,
  `Tests  1 failed | 3050 passed (3051)`. The only failure is the same
  pre-existing GAP tripwire at line 166; no coverage table is printed on a
  failing run. No production source changed by this task.
- `bash apps/staff-web/check.sh` on the final tree -> `CHECK_EXIT=1`. The
  non-test stages are green: `gen:api: generated client matches the contract`;
  typecheck / build (`> tsc --noEmit -p tsconfig.json && vite build`) ->
  `✓ built in 172ms`; `eslint . --max-warnings 0` and
  `node scripts/check-bundle-size.mjs` green. The single failure is the same
  pre-existing GAP tripwire: `Test Files  1 failed | 270 passed (271)`,
  `Tests  1 failed | 3050 passed (3051)`, final diagnostic
  `expected [ …(15) ] to deeply equal [ …(9) ]` at
  `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:166`.
  No TypeScript error (`error TS…`) was reported.
- `python3 -I scripts/check-constraints.py --base origin/main` -> exit 0.
- `git diff --name-only origin/main...HEAD` after the revert -> only the spec
  and notes files; the out-of-scope tripwire pin no longer appears.

Repair attempt 1 (2026-11-24, executed tool evidence in this checkout, HEAD
`e0c1a640`): the integrator re-ran the coverage gate and reported the same GAP
tripwire failure. Re-verified from the working tree:

- `npx vitest run tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts`
  (in `apps/staff-web`) -> exit 1, `Test Files  1 failed (1)`,
  `Tests  1 failed | 2 passed (3)`, `expected [ …(15) ] to deeply equal [ …(9) ]`
  at line 166. The six extra paths are the five pre-existing specs plus this
  task's `responsiveLayout.spec.ts`.
- `git ls-tree -r --name-only origin/main apps/staff-web/e2e | grep '\.spec\.ts$'`
  -> 14 spec files on `origin/main` while its pin lists 9, so the tripwire is
  already red on the base commit before this task's spec exists.
- `python3 -I` + `worker_gate.validate_candidate([pin, notes], scope, ...)`
  -> `[{'file': 'apps/staff-web/tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts', 'rule': 'scope'}]`;
  `parse_scope` -> `['apps/staff-web/e2e/responsiveLayout.spec.ts']`;
  `under_scope(pin, scope)` -> `False`. The only edit that would green the gate
  is outside this task's scope and was already rejected and reverted once.
- No in-scope change can make `e2eSpecs()` (15 paths, or 14 with the deliverable
  removed) equal the 9-name pin. Therefore no test was added or removed, no code
  deleted, and no threshold, exclusion, skip or suppression changed in this
  repair pass.

Entry-point-to-test trace: `playwright.config.ts` starts the Vite dev server ->
`page.goto('/route')` -> `src/main.tsx` browser-support guard ->
`src/app/routes.tsx` and feature `routes.ts` glob -> `AppShell.tsx` renders
`data-breakpoint`, `NavigationDrawer.tsx` drawer button
(`aria-controls="staff-navigation-drawer"`), `NavRail` (`data-rail="icon|full"`),
`main#main` and `header[role="banner"] > h1` -> spec assertions at
`responsiveLayout.spec.ts:80-161`.

## Coverage

No production file changed, so Vitest/JaCoCo coverage of production source is
unchanged and there is no new production branch. **The 100% figures below are
an inference from "no production source changed", not a green executed gate on
the final tree.** `scripts/coverage-check.sh` triggers
`npm run -s test:coverage` for this staff-web diff, and that command exits 1 on
the final tree because of the pre-existing out-of-scope tripwire (same
`1 failed | 3050 passed` result as under Test evidence; no coverage table is
printed on the failing run). The task therefore stays `[-]` with a
`## Why this is not finished` and is not releasable until the tripwire task
lands; no threshold, exclusion or skip was changed to make it green.

The first worker's green gate run (with the now-reverted out-of-scope pin fix)
recorded
`npm run test:coverage` -> `Test Files  268 passed (268)` /
`Tests  3045 passed (3045)`, `Statements 100% (5107/5107)`,
`Branches 100% (2634/2634)`, `Functions 100% (1697/1697)`,
`Lines 100% (4903/4903)`, `No files with missing coverage.` Reverting a test
pin cannot change production coverage. The final-tree coverage run is red only
on the pre-existing out-of-scope tripwire described under Findings, and no
coverage table is emitted for that failing run.

**Branch-to-test list for repair attempt 1 (unchanged from the original pass;
reaffirmed here as requested).** No production line or branch was added or
changed by this task, so there is no new uncovered line or branch to test-cover.
The deliverable's branches are enumerated by the Playwright list command
(`Total: 320 tests in 1 file`) and each is exercised by a named assertion:

| Branch | Test |
|---|---|
| compact band: drawer button present, rail absent | `expectNavigation` (`:110-113`) on both phone-portrait projects |
| medium band: `[data-rail="icon"]` | `expectNavigation` (`:115-119`) on phone-landscape and both tablet-portrait projects |
| expanded band: `[data-rail="full"]` | `expectNavigation` (`:115-119`) on both tablet-landscape projects, laptop, desktop and `chromium` |
| non-compact drawer button absent | `expectNavigation` (`:120-123`) |
| overflow true/false | `expectNoHorizontalScroll` (`:80-89`) in every per-route test and the RTL test |
| RTL compact user-menu branch | RTL test (`:199-201`) on compact projects |
| RTL medium/expanded direct Language button | RTL test (`:202`) on the other projects |
| lazy page mounted vs Suspense fallback; API skeleton settled | `expectPageReady` (`:154-161`) in every per-route test and the RTL test |
| shell banner heading vs page heading | `expectLandmarks` (`:127-137`) in every per-route test and the RTL test |

No `.skip`, `.only`, `test.fixme`, exclusion, lowered threshold or
coverage-ignore comment was added; no assertion was weakened. A live browser
run would be required to convert this enumeration into executed green results;
it is blocked by the absent browser binaries recorded above.

## Clinical and architecture review

Not required: the change makes no clinical decision, signs or releases no data,
changes no patient identity or sharing boundary and adds no architectural
contract. Evidence is simulated and synthetic; it is not clinical or human
acceptance.

## Review verdicts

- smartclinic-plan-reviewer: GO
- smartclinic-code-reviewer: GO
- smartclinic-coverage-auditor: GO
- smartclinic-devsecops: GO

## Findings

- **Integrator scope violation (tool evidence, RESOLVED):** the earlier fix
  pass edited `apps/staff-web/tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts`,
  which is outside this task's `scope` (`apps/staff-web/e2e/responsiveLayout.spec.ts`).
  `worker_gate.validate_candidate` reproduced the exact
  `{"rule": "scope"}` violation. That edit was reverted in commit `66ca5759`;
  the final net diff contains only the in-scope spec and notes files, and the
  gate now reports no violations.
- **Pre-existing red gate (tool evidence, NOT resolved in scope):**
  `apps/staff-web/tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:166`
  pins the e2e spec set to 9 names while the tree holds 15. Five of the six
  extra specs predate this task; with this task's spec temporarily removed the
  test still failed (14 vs 9). The pin is owned by the already-complete
  `SPEC-WEB-10-C02` task and cannot be refreshed inside this task's scope
  without another `scope` violation. No suppression, skip, exclusion or lowered
  threshold was used. A task-request is included below for the owning repair.
- **Role limitation (plan reviewer, simulated):** production role is fixed to
  `reception`, so routes granted only to other roles render `ForbiddenPage`
  inside the same real `AppShell`. The matrix still measures the production
  shell's width, navigation, landmarks and overflow on every registered route
  path, but does not prove the layout of the intended content screens on those
  denied routes. A dev-only role-override harness needs a production-scope
  change; it is requested in the earlier task notes rather than attempted out
  of scope.
- **320 px floor (design doc, simulated):** `docs/design/responsive-layout.md`
  §13 and decision 0004 set 320 px as the compact floor, but
  `playwright.config.ts` has no 320 px project (its narrowest is 360 px).
  Cross-referenced to task `RESP-E2E-REFLOW-AND-ZOOM`; this spec does not claim
  full supported-width coverage.
- **`/design-preview` (route table, tool evidence):** registered only when
  `import.meta.env.DEV` is true. The default `playwright.config.ts` dev server
  makes the case apply; an `E2E_BASE_URL` pointing at a production build
  degrades it to the catch-all, which still exercises the shell.
- **Live browser evidence unavailable (tool evidence):** all three Playwright
  browser executable paths are absent in this sandbox. Enumeration and lint
  were run; a live red/green run and any genuine layout-defect detection remain
  for CI.
- **Superseded prior notes (Required, resolved here):** the earlier notes file
  `.agent/pr-notes/RESP-E2E-RESPONSIVE-LAYOUT-20261124-004500.md` records the
  now-reverted tripwire pin refresh and claims a green gate (`3045/3045`,
  "RESOLVED", "the full gate is now green", "the first task request below is
  now satisfied and can be closed"), sourced from intermediate commits
  `b5812de4`, `91918c59` and `83bca3d1`. Those claims belong to a tree that no
  longer exists at HEAD `66ca5759`. This fix notes file is the authoritative
  hand-off (it is the newest `RESP-E2E-RESPONSIVE-LAYOUT-*.md` file that the
  integrator selects). It supersedes those claims: the tripwire is RED on a
  pre-existing out-of-scope defect, the pin refresh was reverted, the gate and
  live-browser verdicts are not green here, and the tripwire task-request is
  still open. The older file is left untouched because a worker writes only to
  its own named notes file; its git history and this file preserve the record.
- **Optional spec observations (code review, coverage audit):** `expectBand`
  derives the expected band from the same production `breakpointFor` as the
  shell, so a change to the 768/1280 constants would move both sides; an
  independent threshold pin already exists at
  `tests/responsive/features-system/SessionExpiredPage.responsive.test.tsx:106`.
  `waitForLoadState('networkidle')` is retained because it was added to stop a
  Suspense/API skeleton being measured as the settled screen; the project has
  no persistent polling request. The overflow formula
  `scrollWidth <= innerWidth` is exactly the one the task specifies; a stricter
  `clientWidth` comparison would diverge from the acceptance criterion. None of
  these is a gate blocker or a weakened assertion.
- **Brief inaccuracy:** the brief stated that no `.agent/pr-notes/*.md` files
  existed; the directory contains reconciled daily notes and many task notes.
  Its step-3 heading assertion named only `header h1`; the plan reviewer
  required a page heading inside `main` as well, which was added without
  removing the shell heading check. The axe scan is owned by the separate
  `RESP-E2E-ACCESSIBILITY-SCAN-RESPONSIVE` story and was not duplicated.

## Lessons for workers

- A worker's task `scope` is enforced on the committed diff against the base;
  a file outside it must never be edited even to green a failing gate. Stop,
  document the blocker and use a `task-requests` entry instead. The integrator
  rejects such a PR with a `scope` violation regardless of test results.
- Survey the module's tripwire tests before adding a new e2e spec:
  `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts`
  pins the complete spec list and goes red on any addition.
- `tsconfig.json` excludes `e2e`, so `npm run typecheck` cannot catch a spec
  error; validate with `npx eslint e2e/<file>.spec.ts --max-warnings=0` and
  `npx playwright test --config e2e/playwright.config.ts <file> --list`.
- A `Suspense` fallback with no heading can make a width measurement falsely
  green; wait for `main#main h1, main#main h2` before measuring a lazily
  imported route.
- In this cloud image all Playwright browser binaries are absent; report the
  `--list` result instead of installing a browser or weakening a test.

## Why this is not finished

The in-scope deliverable is complete; the remaining red result is a
pre-existing, out-of-scope gate blocker. Original failing command and final
lines: `bash apps/staff-web/check.sh` ->
`Test Files  1 failed | 270 passed (271)` /
`Tests  1 failed | 3050 passed (3051)`, test
`automate-critical-journey-supported-role-real-browser.test.ts > GAP: all-role, staging-RC and light/dark visual evidence is not yet recorded`
at line 166 (`expected [ …(15) ] to deeply equal [ …(9) ]`). Cause: the GAP
tripwire's `currentSpecs` pin lists 9 e2e spec paths while the tree holds 15;
five of those were added by earlier completed tasks, so the pin was already
stale on `origin/main` before this task's spec existed. Verification: with
this task's spec temporarily removed the single test still failed (14 vs 9),
and with the out-of-scope pin refresh applied it passed 3/3. The refresh was
reverted because the integrator's `worker_gate` reported a `scope` violation
for that out-of-scope file; no suppression, skip, exclusion or lowered
threshold was used. Already tried: full gate, isolated single test, pin refresh
and its revert, and worker-gate validation before and after the revert. Exact
next step: the owning repair task below refreshes the pin to the committed spec
set and keeps the tripwire active; then a runner with Playwright browsers
installed executes `npx playwright test --config e2e/playwright.config.ts e2e/responsiveLayout.spec.ts`,
and any genuine defect is handled with a single `test.fixme` plus a follow-up
task, never a weakened assertion.

Repair attempt 1 conclusion: the integrator's coverage re-check is still red on
the same pre-existing out-of-scope tripwire. Fresh tool runs (Test evidence)
confirm 15-vs-9 on this tree, 14-vs-9 on `origin/main` without this task's spec,
and a `scope` violation for the only gate-greening edit. No in-scope test,
deletion, threshold, exclusion, skip or suppression can close this gap. The next
step is unchanged: the owning task refreshes the pin in its own scope (the
`task-requests` entry below); this task stays `[-]` and is not releasable here.

```task-requests
[
  {
    "title": "Refresh the staff-web e2e spec tripwire list",
    "details": "The GAP tripwire in apps/staff-web/tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts pins 9 e2e spec names but the tree has 15; bash apps/staff-web/check.sh is red on main because of it. Pin the current 15 paths (including e2e/responsiveLayout.spec.ts) so the gate is green, keeping the tripwire active. This is the same request recorded in .agent/pr-notes/RESP-E2E-RESPONSIVE-LAYOUT-20261124-004500.md; create one task only.",
    "scope": ["apps/staff-web/tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts"],
    "depends": ["RESP-E2E-RESPONSIVE-LAYOUT"]
  }
]
```
