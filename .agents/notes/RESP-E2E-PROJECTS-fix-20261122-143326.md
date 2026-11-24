# RESP-E2E-PROJECTS — fix pass: confirm the Playwright device matrix gate is green

## Summary

This is fix pass 1 of 3 for task RESP-E2E-PROJECTS (issue #892, "stopped before
it finished"). The scoped implementation was already committed on this branch
by the first worker (`02635d99 test(staff-web): add responsive device projects
to Playwright`) and is correct and unchanged by this pass: `e2e/playwright.config.ts`
defines nine Chromium device-viewport projects plus WebKit/Firefox variants for
the three representative sizes, gated on the engine binary being installed.
`smoke.spec.ts` remains untouched.

The first worker stopped because two checks were red at that time. On the
current branch, after merging `origin/main`, the blocking one is now green: the
pre-existing flaky responsive component assertions that failed its
`bash apps/staff-web/check.sh` run were fixed on main (notably `20bbffe6 Fix
responsive landmarks and export fixture`) and are now included here. I re-ran
the mandatory frontend gate and every applicable configuration check; all are
green. No source file needed editing in this pass; the only new file is these
notes, which become the pull request description.

Evidence source: executed tool commands run by me in this cloud worker, plus
the first worker's committed tool-run notes for the strict-typecheck rationale.
No reviewer, simulated user or human participant produced new evidence in this
pass. The earlier bounded `aurevia-senior-react` review was simulated and
advisory (it edited no files).

## Affected requirements

- **WEB-07** — supported browsers and responsive device matrix (spec line 155).
- **UX-01** — accessibility and responsive layout evidence (spec line 90).
- Acceptance mapping:
  - Nine requested viewports exist as Chromium projects — verified by project
    enumeration (360x740, 390x844, 844x390, 768x1024, 820x1180, 1024x768,
    1180x820, 1280x720, 1920x1080).
  - WebKit/Firefox only for 390x844, 820x1180 and 1280x720 and only when the
    binaries exist — verified by listing before and after `playwright install`.
  - Original `chromium` project and its timeouts (`retries: 1`,
    `webServer.timeout: 120_000`) kept — visible in the config and unchanged.
  - `smoke.spec.ts` unchanged — `git diff` is empty.
- References: `docs/design/responsive-layout.md` §13,
  `docs/decisions/0004-responsive-staff-web.md`.

## Risk class

Routine. Single-file, test-harness-only configuration change; no production
source, contract, data path, permission boundary or clinical behavior. No
production file was changed in this fix pass either.

## Review owner

`aurevia-senior-react` (bounded, advisory review recorded by the first
worker). Strict TypeScript rules were applied with a focused `tsc` and ESLint
on the config because the module `tsconfig.json` excludes `e2e/`. No specialist
edited any file.

## Data changes

None. No migration, backfill, schema, lock or volume impact; no forward-fix.
No patient data or credential is present in the config, tests or these notes.

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
worker; evidence is from executed tool commands, not a reviewer or human.

1. `git merge --no-edit origin/main` — `Already up to date.` (branch already
   contained main). Working tree clean; no conflict markers introduced by this
   pass.
2. `bash apps/staff-web/check.sh` (from repo root) — **exit 0**. Milestone lines:
   - `gen:api: generated client matches the contract`
   - typecheck final lines (mandatory paste, no `error TS…`):
     ```
     > aurevia-staff-web@0.1.0 typecheck
     > tsc --noEmit -p tsconfig.json
     ```
   - `> eslint . --max-warnings 0` (no warnings)
   - `✓ built in 171ms` (`vite v8.3.8`)
   - `Test Files  206 passed (206)`
   - `Tests  2542 passed (2542)`
   - Duration 127.07s. Stack traces in the log are from tests that assert
     thrown errors (for example `useTheme must be used inside ThemeProvider`),
     not failures; the final summary is all green.
   This resolves the first worker's only blocking red check: its 18 failing
   responsive component assertions are fixed on the current branch.
3. `npx playwright test --list --config e2e/playwright.config.ts` from
   `apps/staff-web` **before** installing non-Chromium binaries — exit 0,
   `Total: 150 tests in 6 files` = 10 Chromium projects × 15 tests. Unique
   project names (verbatim tool output):
   ```
   [chromium]
   [desktop-1920x1080]
   [laptop-1280x720]
   [phone-landscape-844x390]
   [phone-portrait-360x740]
   [phone-portrait-390x844]
   [tablet-landscape-1024x768]
   [tablet-landscape-1180x820]
   [tablet-portrait-768x1024]
   [tablet-portrait-820x1180]
   ```
4. `npx playwright install firefox webkit` — exit 0 (host-library warnings for
   `libxkbcommon.so.0`, `libGLESv2.so.2`, `libx264.so` confirm this image cannot
   launch real browsers; that is a worker-image limit, not a change defect).
5. `npx playwright test --list --config e2e/playwright.config.ts` **after**
   install — exit 0, `Total: 240 tests in 6 files` = 16 projects × 15 tests.
   Unique project names (verbatim tool output):
   ```
   [chromium]
   [desktop-1920x1080]
   [laptop-1280x720-firefox]
   [laptop-1280x720-webkit]
   [laptop-1280x720]
   [phone-landscape-844x390]
   [phone-portrait-360x740]
   [phone-portrait-390x844-firefox]
   [phone-portrait-390x844-webkit]
   [phone-portrait-390x844]
   [tablet-landscape-1024x768]
   [tablet-landscape-1180x820]
   [tablet-portrait-768x1024]
   [tablet-portrait-820x1180-firefox]
   [tablet-portrait-820x1180-webkit]
   [tablet-portrait-820x1180]
   ```
   Cross-browser suffixes appear only on 390x844, 820x1180 and 1280x720.
6. Focused strict typecheck of the scoped file (module `tsconfig.json` excludes
   `e2e/`): `./node_modules/.bin/tsc --noEmit --strict --noUncheckedIndexedAccess --exactOptionalPropertyTypes --noUnusedLocals --noUnusedParameters --noFallthroughCasesInSwitch --isolatedModules --verbatimModuleSyntax --target ES2022 --lib ES2022,DOM,DOM.Iterable --module ESNext --moduleResolution Bundler --skipLibCheck --types node e2e/playwright.config.ts` — exit 0, no `error TS…`.
7. `./node_modules/.bin/eslint e2e/playwright.config.ts --max-warnings 0` — exit 0.
8. `git diff -- apps/staff-web/e2e/smoke.spec.ts` — empty; the spec is unchanged.

## Coverage

Only changed file is a Playwright configuration under `e2e/`. It is not
production source: `apps/staff-web/check.sh` runs
`vitest run --coverage --exclude 'e2e/**'` and the module `tsconfig.json`
includes only `src`, `tests` and `vite.config.ts`. No production line, branch,
method or class changed, so there is no source-coverage denominator to raise and
no threshold, exclusion or skip was added. The canonical
`bash apps/staff-web/check.sh` coverage summary on the current branch is
`Statements 100% (4059/4059)`, `Branches 100% (2071/2071)`,
`Functions 100% (1346/1346)`, `Lines 100% (3876/3876)`, `No files with missing
coverage`, `213 files fully covered`.

Configuration branches and the command that takes each (evidence: executed
tool, this worker unless noted):

| Branch | Command that takes it | Result |
|---|---|---|
| `existsSync(...)` true for webkit and firefox | `--list` after install (step 5) | 6 cross-browser projects listed |
| `existsSync(...)` false | `--list` before install (step 3) | only 10 Chromium projects listed |
| `crossBrowserViewportNames.has(device.name)` true | step 5 | `-webkit`/`-firefox` only on the three named sizes |
| `...has(...)` false | steps 3 and 5 | other six viewports have no cross-browser projects |
| `browser === 'chromium'` name ternary | steps 3 and 5 | unsuffixed Chromium names vs engine-suffixed names |
| `device.hasTouch ? {...} : {}` both sides | step 5 | phone/tablet rows set touch; laptop/desktop omit it |
| `device.isMobile && browser === 'chromium'` both sides | step 5 | phones on Chromium get `isMobile`; non-Chromium phone variants and all other rows omit it |
| `E2E_BASE_URL` present / absent | step 3 and earlier worker's notes step 4 | webServer key omitted vs present; both list successfully |
| `reuseExistingServer: !process.env['CI']` both sides | earlier worker's notes step 5 vs steps 3/5 | CI unset and CI=true both list successfully |

A real browser run is not possible in this image (`libglib-2.0.so.0` and other
host libraries missing). `--list` enumerates every project without launching a
browser and is the available executable check; the full browser-matrix runtime
is exercised in CI.

## Clinical and architecture review

Not required: no signed data, cross-organization sharing, patient association,
prescribing or critical results. No clinical reviewer or human participant was
involved; all evidence is synthetic and tool-generated.

## Findings

1. **First worker's blocking red check is resolved.** Its
   `bash apps/staff-web/check.sh` failed on 18 assertions in
   `tests/responsive/features-*`. Those files are fixed on the current branch
   (merged main includes `20bbffe6`), and the gate now passes 2542/2542 with
   100% coverage. No change to this task's config was required to make it green.
2. **Known worker false positive (not a blocker).** `scripts/check.sh` /
   `check-pack.py` still print `opencode.json is stale: run scripts/gen-opencode.py`
   in this image. Verified `git ls-files -v opencode.json` → `S opencode.json`:
   the worktree file is the CI profile installed under `skip-worktree` by
   `agent-run.sh` (OPS-CW-003-01), not a defect. The integrator runs the
   authoritative full gate; I did not regenerate or commit `opencode.json`, and
   per AGENTS.md did not re-diagnose it. The earlier worker ran the remaining
   policy stages individually (`check-docs.py`, `check-secrets.py`,
   `check-names.py`, `check-wiring.py`, `check-migrations.py --require-baseline`)
   green, and nothing in those areas changed since.
3. **Pre-existing committed conflict markers outside scope (tool evidence).**
   `git grep -nE '^(<<<<<<<|>>>>>>>) '` finds three markers in
   `apps/api/src/main/java/com/aurevia/exchange/hl7/`
   (`Hl7ParseException.java`, `Hl7Parser.java`, `Hl7Segment.java`). They are in
   HEAD **and** in `origin/main`, introduced by `b6b349f0 BUILD-37`. They are
   unrelated to this task and outside its scope, so I did not edit them. The
   owner/integrator should route them to a BUILD-37 repair; they will break a
   Java build policy check until resolved.
4. **Accepted matrix consequence.** With `testDir: '.'`, all six spec files —
   including the five journey specs — run on every project. This is inherent to
   adding projects to the shared config. To restrict journeys to desktop engines
   in future, scope them at invocation time (`--project=chromium`), not by
   editing `smoke.spec.ts`.

## Lessons for workers

- When an earlier worker stops on a red module gate caused by unrelated files,
  merge `origin/main` and re-run the canonical `check.sh` first: a later main
  fix (here `20bbffe6`) may already have turned it green, so no scoped edit is
  needed and the task can be completed rather than handed off again.
- `scripts/check.sh` stops at the `check-pack.py` "opencode.json is stale"
  message in a cloud worker because the CI profile is mounted under
  `skip-worktree` (`S opencode.json`). Confirm once, record it, run the other
  policy stages individually, and do not edit or regenerate `opencode.json`.
- Always run `npx playwright test --list` with
  `--config e2e/playwright.config.ts` (or from `e2e/`); from the module root it
  picks up Vitest `.test.ts` files. Enumerate once before and once after
  `playwright install` to cover both `existsSync` branches without a browser
  launch.
- `apps/staff-web/tsconfig.json` excludes `e2e/`, so `npm run typecheck` never
  checks the Playwright config; run a focused `tsc` with
  `--exactOptionalPropertyTypes` on the file.
