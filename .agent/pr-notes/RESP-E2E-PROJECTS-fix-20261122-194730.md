# RESP-E2E-PROJECTS — fix pass 2: re-verify the device matrix and hand off the blocking pre-existing defects

## Summary

Fix pass 2 of 3 for task RESP-E2E-PROJECTS (issue #892, "stopped before it
finished"). The scoped implementation is already committed on this branch
(`02635d99 test(staff-web): add responsive device projects to Playwright`) and
is correct: `apps/staff-web/e2e/playwright.config.ts` defines nine Chromium
device-viewport projects plus WebKit/Firefox variants for the three
representative widths, gated on the engine binary being installed. The original
`chromium` project and its timeouts are kept and `smoke.spec.ts` is unchanged.

The integrator rejected fix pass 1 because the worker's reported local checks
were `failed` even though that worker had re-run only `bash apps/staff-web/check.sh`
(which was green). The worker gate configured for swarm workers is actually
`scripts/worker-check.sh`, not the module check. In this pass I re-ran every gate
that can be executed in this worker and confirmed the scoped change is green. The
remaining red checks are pre-existing defects on `main` that are outside this
task's strict scope (`apps/staff-web/e2e/playwright.config.ts`); I did not edit
them because doing so would itself fail the worker gate's scope rule.

Evidence source: executed tool commands run by me in this cloud worker and the
read-only reviewer verdicts below. No simulated user or human participant
produced evidence in this pass.

## Affected requirements

- **WEB-07** — supported browsers and responsive device matrix (spec line 155).
- **UX-01** — accessibility and responsive layout evidence (spec line 90).
- Acceptance mapping (all verified by project enumeration below):
  - Nine requested Chromium viewports: 360x740, 390x844, 844x390, 768x1024,
    820x1180, 1024x768, 1180x820, 1280x720, 1920x1080.
  - WebKit/Firefox only for 390x844, 820x1180 and 1280x720, only when the
    engine binary exists.
  - Original `chromium` project and its timeouts (`retries: 1`,
    `webServer.timeout: 120_000`) kept unchanged.
  - `smoke.spec.ts` unchanged (`git diff` empty).
- References: `docs/design/responsive-layout.md` §13,
  `docs/decisions/0004-responsive-staff-web.md`.

## Risk class

Routine. Single-file, test-harness-only configuration change; no production
source, contract, data path, permission boundary or clinical behavior. No
production file was changed in this fix pass.

## Review owner

`smartclinic-senior-react` (bounded, advisory review from the first worker).
Strict TypeScript rules were applied with a focused `tsc` and ESLint because the
module `tsconfig.json` excludes `e2e/`. No specialist edited any file. The
read-only verdicts for this pass are under "Review verdicts".

## Data changes

None. No migration, backfill, schema, lock or volume impact; no forward-fix. No
patient data or credential is present in the config, tests or these notes.

## Authorization boundaries and audit events

None. Playwright project definitions do not alter tenant, role, patient-link or
audit behavior. No audit event added or changed.

## Migration and rollout considerations

None. Browser availability is detected at config load with
`existsSync(engine.executablePath())`; a Chromium-only worker stays green and a
full browser-matrix job exercises all three engines. No workflow, script or
environment variable was changed.

## Test evidence

Entry point → test trace: `apps/staff-web/e2e/playwright.config.ts` (`projects`)
→ `npx playwright test --list --config e2e/playwright.config.ts` from
`apps/staff-web` → enumerates the specs in `e2e/smoke.spec.ts` and the five
journey spec folders across every project. All commands below were run in this
worker; evidence is from executed tool commands, not from a reviewer or human.
All commands were run from the repository root unless a `cd` is shown.

1. `git merge --no-edit origin/main` — `Already up to date.` (branch already
   contained main at `7ccbac12`). Working tree clean; no new conflict markers
   introduced by this pass. (Main's final line: `Already up to date.`)
2. `cd apps/staff-web && npx playwright install firefox webkit chromium` — exit
   0. Host-library warnings (`libxkbcommon.so.0`, `libGLESv2.so.2`,
   `libx264.so`) confirm this image cannot launch real browsers; the engine
   executable paths now exist for `existsSync`.
3. `cd apps/staff-web && npx playwright test --list --config e2e/playwright.config.ts`
   (after install) — exit 0, `Total: 240 tests in 6 files` = 16 projects × 15
   tests. Unique project names (verbatim tool output):
   ```
   chromium
   desktop-1920x1080
   laptop-1280x720
   laptop-1280x720-firefox
   laptop-1280x720-webkit
   phone-landscape-844x390
   phone-portrait-360x740
   phone-portrait-390x844
   phone-portrait-390x844-firefox
   phone-portrait-390x844-webkit
   tablet-landscape-1024x768
   tablet-landscape-1180x820
   tablet-portrait-768x1024
   tablet-portrait-820x1180
   tablet-portrait-820x1180-firefox
   tablet-portrait-820x1180-webkit
   ```
   Cross-browser suffixes appear only on 390x844, 820x1180 and 1280x720.
4. `E2E_BASE_URL=http://127.0.0.1:9999 npx playwright test --list --config
   e2e/playwright.config.ts` — exit 0, `Total: 240 tests in 6 files`
   (webServer-omitted branch).
5. `env -u CI npx playwright test --list --config e2e/playwright.config.ts` —
   exit 0, `Total: 240 tests in 6 files` (`reuseExistingServer: true` branch).
6. Focused strict typecheck of the scoped file (the module `tsconfig.json`
   excludes `e2e/`):
   `./node_modules/.bin/tsc --noEmit --strict --noUncheckedIndexedAccess --exactOptionalPropertyTypes --noUnusedLocals --noUnusedParameters --noFallthroughCasesInSwitch --isolatedModules --verbatimModuleSyntax --target ES2022 --lib ES2022,DOM,DOM.Iterable --module ESNext --moduleResolution Bundler --skipLibCheck --types node e2e/playwright.config.ts`
   — exit 0, no `error TS…`.
7. `./node_modules/.bin/eslint e2e/playwright.config.ts --max-warnings 0` —
   exit 0.
8. `git diff -- apps/staff-web/e2e/smoke.spec.ts` — empty; the spec is unchanged.
9. Individual policy stages (after restoring the local `opencode.json` profile
   from HEAD): `check-docs.py`, `check-secrets.py`, `check-names.py`,
   `check-wiring.py`, `check-constraints.py --require-base`,
   `check-migrations.py --require-baseline` — all exit 0. `check-pack.py` still
   prints `opencode.json is stale` before `restore_config`; this is the
   documented worker-image false positive (OPS-CW-003-01).
10. `bash scripts/coverage-check.sh $(git rev-parse HEAD)` — `coverage check:
    passed` (docs-only diff). `bash scripts/frontend-typecheck-check.sh
    $(git rev-parse HEAD)` — `frontend typecheck: passed (or no frontend files
    changed)`.
11. `bash apps/staff-web/check.sh` (from the repository root) — **exit 0**, the
    mandatory frontend definition of done. Milestone lines:
    - `gen:api: generated client matches the contract`
    - typecheck final lines (mandatory paste; no `error TS…`):
      ```
      > smartclinic-staff-web@0.1.0 typecheck
      > tsc --noEmit -p tsconfig.json
      ```
    - `✓ built in 164ms` (`vite`)
    - `Test Files  206 passed (206)`
    - `Tests  2542 passed (2542)`
    - Coverage summary: `Statements 100% (4059/4059)`, `Branches 100%
      (2071/2071)`, `Functions 100% (1346/1346)`, `Lines 100% (3876/3876)`,
      `No files with missing coverage.`
    jsdom stack traces in the log come from tests that assert thrown errors, not
    failures; the final summary is all green.
12. `timeout 220 bash scripts/check.sh` (end-of-story run, `opencode.json`
    restored to HEAD so `check-pack.py` passes) — **exit 1**, stopping at the
    authoritative `python-coverage.sh` stage with the same blocking defect as
    Finding 3:
    ```
    ERROR: test_integration_failure_still_fails_the_gate (test_worker_gate.MainTest...)
    FileNotFoundError: [Errno 2] No such file or directory: 'apps/api/target/surefire-reports'
    ERROR: test_maven_failure_with_failing_reports_still_fails (test_worker_gate_coverage.MainTest...)
    FileNotFoundError: [Errno 2] No such file or directory: 'apps/api/target/surefire-reports'
    Ran 1187 tests in 33.080s
    FAILED (errors=2)
    ```
    No stage after that point ran; the failure is independent of this story's
    config (which is exercised by steps 2-11, all green).

## Coverage

Only changed file is a Playwright configuration under `e2e/`. It is not
production source: `apps/staff-web/check.sh` runs
`vitest run --coverage --exclude 'e2e/**'` and the module `tsconfig.json`
includes only `src`, `tests` and `vite.config.ts`. No production line, branch,
method or class changed, so there is no source-coverage denominator to raise and
no threshold, exclusion or skip was added. The canonical staff-web coverage
summary on the current branch (from the first worker's and fix pass 1's runs) is
`Statements 100% (4059/4059)`, `Branches 100% (2071/2071)`,
`Functions 100% (1346/1346)`, `Lines 100% (3876/3876)`, `No files with missing
coverage`, `213 files fully covered`; this pass re-confirms it in step 11.

Configuration branches and the command that takes each (evidence: executed
tool, this worker). Loading the config with `--list` executes every branch at
config load; the project-name- and count-affecting branches are *observable* in
the output, while the `use`-option branches (touch, mobile, webServer) are
executed on both sides but not value-asserted by `--list` (no e2e spec imports
the config). This distinction is stated precisely after the table per the
coverage auditor's Optional finding:

| Branch | Command that takes it | Observable result |
|---|---|---|
| `existsSync(...)` true for webkit and firefox | step 3 (after install) | 6 cross-browser projects listed (150→240 tests) |
| `existsSync(...)` false | first worker's notes step 1 / fix pass 1's notes step 3 | only 10 Chromium projects listed (150 tests) |
| `crossBrowserViewportNames.has(device.name)` true | step 3 | `-webkit`/`-firefox` only on the three named sizes |
| `...has(...)` false | step 3 | other six viewports have no cross-browser projects |
| `browser === 'chromium'` name ternary | step 3 | unsuffixed Chromium names vs engine-suffixed names |
| `device.hasTouch ? {...} : {}` both sides | step 3 | executed at config load; not value-asserted by `--list` |
| `device.isMobile && browser === 'chromium'` both sides | step 3 | executed at config load; not value-asserted by `--list` |
| `E2E_BASE_URL` present / absent | steps 4 and 3 | both list 240 tests without a crash; webServer key not echoed |
| `reuseExistingServer: !process.env['CI']` both sides | steps 5 and 3 | both list successfully; value not echoed by `--list` |

A real browser run is not possible in this image (`libglib-2.0.so.0` and other
host libraries missing). `--list` enumerates every project without launching a
browser and is the available executable check; the full browser-matrix runtime
is exercised in CI.

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association,
prescribing or critical results. No clinical reviewer or human participant was
involved; all evidence is synthetic and tool-generated.

## Review verdicts

- smartclinic-plan-reviewer: GO
- smartclinic-code-reviewer: GO
- smartclinic-coverage-auditor: GO
- smartclinic-devsecops: GO

Disposition of reviewer findings: the `smartclinic-plan-reviewer` first returned
NO-GO on this fix pass's plan with two Required findings — (a) diagnose the gate
that actually sets the integrator's `checks` value rather than re-running green
sub-checks, and (b) narrow the task-requests. Both are addressed above: Finding 1
records the authoritative `worker-check.sh` → `quick-check.sh` →
`python-coverage.sh` failure with its exact command and final lines, and no
task-request is filed because the two out-of-scope defects are already covered
by open/owner tasks or are protected paths. On re-review the plan reviewer
returned GO. The code reviewer's Optional finding about the config doc comment
citing `docs/design/responsive-layout.md` §13 (whose listed viewport values
differ from the nine task-mandated sizes) is recorded in Findings 6; the config
is already committed and out of scope for this fix pass. All three post-code
reviewers returned GO on the first pass with no Critical or Required findings.

## Findings

1. **Fix pass 1 reported the wrong gate as its check — and this pass diagnosed
   the authoritative failure.** Its notes record `bash apps/staff-web/check.sh`
   green (2542/2542), but the swarm worker gate that sets the integrator's
   `checks` value is `scripts/worker-check.sh` (`.github/workflows/swarm-worker.yml:57`
   `CHECK_SCRIPT`; `scripts/agent-run.sh:347-373`;
   `scripts/swarm.py:2082-2086,2132-2135`), whose first stage is
   `scripts/quick-check.sh` → `scripts/python-coverage.sh`. I ran that
   authoritative stage and it fails — exact command and final lines:
   `timeout 280 bash scripts/quick-check.sh` → exit 1,
   `Ran 1187 tests in 33.362s` / `FAILED (errors=2)`; the two errors are finding
   3 below. This is the gate whose result fix pass 1 could not flip by pasting
   green module-check output, and it is why this pass is handed off as
   not-finished rather than presented as complete.
2. **Committed merge-conflict markers outside scope (executed `git grep`, tool
   evidence).** `git grep -nE '^(<<<<<<<|=======|>>>>>>>) ' -- ':!*.md'` finds
   nine marker lines in three Javadoc comments:
   `apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7/Hl7ParseException.java`
   (lines 6, 8, 10), `Hl7Parser.java` (14, 16, 18) and `Hl7Segment.java`
   (10, 12, 14). Both sides of each conflict are identical duplicated Javadoc
   lines. They are in HEAD and in `origin/main`, introduced by `b6b349f0
   BUILD-37`, and are tracked by the **open** task `AUD-20261122-20` (TODO.md
   "Remove unresolved Git conflict markers committed in production HL7 Javadoc",
   scope `apps/api/src/main/java/com/aurevia/smartclinic/exchange/hl7`). They sit
   inside `/** … */`, so `mvn -q -B -f apps/api/pom.xml -Pno-security,fast
   -DskipTests compile` (exit 0) and `bash scripts/docs.sh` (exit 0) both pass
   here and runtime behavior is unchanged. I did not edit them because the task
   scope is `apps/staff-web/e2e/playwright.config.ts`; a scope change would fail
   the worker gate. **No task-request is filed** — `AUD-20261122-20` already
   covers these files and the request validator dedupes by title, so a duplicate
   would create a spurious `FU-` task.
3. **Two Python tooling tests break the authoritative worker gate when no Java
   reports exist (executed tool evidence — the blocking failure).** Exact
   command and final lines: `timeout 280 bash scripts/quick-check.sh` → exit 1,
   `Ran 1187 tests in 33.362s`, `FAILED (errors=2)`; isolating them with
   `python3 -I -m unittest discover -s scripts/tests -p 'test_worker_gate*.py'`
   errors in
   `test_worker_gate.MainTest.test_integration_failure_still_fails_the_gate` and
   `test_worker_gate_coverage.MainTest.test_maven_failure_with_failing_reports_still_fails`
   with `FileNotFoundError: [Errno 2] No such file or directory:
   'apps/api/target/surefire-reports'`. Both tests patch `Path.is_dir` to `True`
   but then call `Path.glob` on the real, absent reports directory.
   `worker-check.sh` runs `python-coverage.sh` before any mvn build, so a fresh
   worker has no `apps/api/target/` and the gate fails regardless of this
   story's config. **No in-scope edit can flip `checks`:** the fix belongs in
   `scripts/tests/` or `scripts/`, which are protected paths —
   `scripts/swarm.py scope_ok()` rejects any task-request whose scope reaches
   `scripts/*`, so I did not file an invalid request. The prior owner tasks
   `tasks/00398000-COVPY-WORKER-GATE.md` (PR #670) and
   `tasks/00385000-WQUALITY-03.md` are marked `[x]`, so this is a regression in
   their file set. Next step for the integrator or daily audit (which own
   protected files): make the two fixtures self-contained (create a temporary
   reports directory or patch `usable_reports`) and rerun
   `bash scripts/python-coverage.sh`.
4. **Known worker false positive (not a blocker).** `check-pack.py` prints
   `opencode.json is stale` while the worktree file is the CI profile installed
   under `skip-worktree` (`git ls-files -v opencode.json` → `S`); the committed
   HEAD profile is correct. `restore_config` is called by `agent-run.sh` before
   the real gate. No regeneration or commit of `opencode.json` was made.
5. **Accepted matrix consequence.** With `testDir: '.'`, all six spec files —
   including the five journey specs — run on every project. To restrict journeys
   to desktop engines in future, scope them at invocation time
   (`--project=chromium`), not by editing `smoke.spec.ts`.
6. **Doc-comment accuracy (Optional, code reviewer).** The config header
   (`playwright.config.ts:14`) says the projects mirror the device classes in
   `docs/design/responsive-layout.md`, but that document's §13 matrix lists
   different viewport values (320×720, 360×780, 720×320, 1280×800 plus RTL
   rows) than the nine task-mandated sizes. The sizes come from the task
   acceptance criteria, not §13. The config is already committed and outside
   this fix pass's scope, so no edit was made; a future cleanup should cite the
   acceptance criteria for the exact sizes and say only the device classes are
   mirrored. The three phone projects' `isMobile: true` with
   `smoke.spec.ts:91` `page.setViewportSize(...)` is source-verified against
   Playwright 1.65.1 but cannot be browser-verified in this image; the CI job
   with browser libs is the authoritative check.

## Lessons for workers

- The worker's reported `checks` value comes from `scripts/worker-check.sh`
  (configured as `CHECK_SCRIPT` in the swarm worker workflow), not from the
  module `bash apps/staff-web/check.sh`. A green module gate is not enough; run
  the configured gate or state plainly which stages you could not run.
- Run `npx playwright test --list` with `--config e2e/playwright.config.ts` (or
  from `e2e/`); from the module root it picks up Vitest `.test.ts` files.
  Enumerate once before and once after `playwright install` to cover both
  `existsSync` branches without a browser launch.
- `apps/staff-web/tsconfig.json` excludes `e2e/`, so `npm run typecheck` never
  checks the Playwright config; run a focused `tsc` with
  `--exactOptionalPropertyTypes` on the file.
- A committed merge-conflict marker is not the same as a merge conflict in your
  own branch. If it is pre-existing on `main` and outside your scope, document
  it with the owning task and next step instead of editing it: a scope change
  fails the worker gate and sends the work back.

## Why this is not finished

The scoped change itself is complete and every gate that exercises it is green
(project enumeration, focused strict `tsc`, ESLint, policy stages, module
typecheck/lint/build/tests — `bash apps/staff-web/check.sh` exit 0, 2542/2542,
100% coverage). The task cannot be accepted as fully green because the
authoritative worker gate fails before it ever reaches this config, and that
failure is a pre-existing, out-of-scope defect that no in-scope edit can flip.

- **Blocking (Finding 3):** `bash scripts/worker-check.sh`'s first stage
  `scripts/quick-check.sh` → `scripts/python-coverage.sh` fails. Command:
  `timeout 280 bash scripts/quick-check.sh` → exit 1, `FAILED (errors=2)`;
  isolated `python3 -I -m unittest discover -s scripts/tests -p
  'test_worker_gate*.py'` → `FileNotFoundError:
  apps/api/target/surefire-reports`. Most likely cause: two tooling tests patch
  `Path.is_dir` but not `Path.glob` and assume a prior Java build created the
  reports directory; `python-coverage.sh` runs before any mvn build. Tried:
  ran the authoritative stage and isolated the two errors; confirmed Java
  compile creates `target/` but not `target/surefire-reports`. The fix lives in
  protected `scripts/` paths, which a task-request cannot scope. Next step:
  the integrator or daily audit fixes those two fixtures (self-contained temp
  reports dir) and reruns `bash scripts/python-coverage.sh`; then this branch's
  `checks` can turn green.
- **Also pre-existing (Finding 2):** command `git grep -nE
  '^(<<<<<<<|=======|>>>>>>>) ' -- ':!*.md'`; result nine marker lines in three
  HL7 Java files from `b6b349f0 BUILD-37`. Tracked by the open task
  `AUD-20261122-20`. Tried: confirmed Java compile and `docs.sh` still pass, so
  it does not break runtime, but it breaches the comment-quality rule. Next
  step: that task's owner removes the nine marker lines and reruns the Java
  build and `scripts/docs.sh`. Neither defect is in this task's scope
  (`apps/staff-web/e2e/playwright.config.ts`), and editing either would fail the
  worker gate's scope rule. No `task-requests` block: the markers already have
  an open task, and the protected-area Python fix cannot be expressed as a
  valid request (`scripts/swarm.py scope_ok()` rejects `scripts/*`).
