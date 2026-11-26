# Testing guide: every check, what it proves, how to run it

This page lists the checks used to verify Aurevia and where each one runs. Every
fact below is taken from the scripts named in each row; no script is changed by this
guide. Use synthetic data only — `scripts/check-secrets.py` fails the build on
credential- and PHI-shaped strings, and the repository rule is synthetic fixtures only.

Durations are qualitative where a script gives no fixed time. The only explicit timing
in the scripts is SpotBugs "takes about a minute" (`scripts/worker-check.sh`). The words
*fast/cheap* and *heavy* come from the comments in `scripts/quick-check.sh`,
`scripts/worker-check.sh` and `apps/api/check.sh`. The slowest gate is the full Maven
`verify`, which is deliberately left to the integrator and Delivery.

## The checks

Run commands from the repository root unless the command itself changes directory.
The owning script is cited in the **Check** column.

| Check (owning script) | Command | What it proves | How long | A failure usually means | First thing to try |
|---|---|---|---|---|---|
| Shell syntax — `scripts/check.sh`, `scripts/quick-check.sh` | `bash -n` over `scripts/*.sh`, `infra/bootstrap/start.sh` (plus `apps/api/check.sh`, `db/check.sh`, `quick-check.sh` in `scripts/quick-check.sh`) | Every shell script parses; no unterminated block or bad quote | Fast, seconds (`scripts/quick-check.sh` calls these "cheap, deterministic things") | A recent edit broke shell syntax | Re-run `bash -n <file>` on the named file; fix the parse error |
| ShellCheck lint — `scripts/check.sh`, `scripts/quick-check.sh` | `shellcheck <the same file list>` | Portability and quoting rules hold; `scripts/check.sh` prints "shellcheck not installed: skipping shell lint (CI runs it)" when absent, `scripts/quick-check.sh` only runs it when installed, CI always runs it (`.github/workflows/delivery.yml`) | Fast, seconds | Unquoted expansion, unused variable or unsafe construct | Run `shellcheck <file>` locally and apply the suggested quoting |
| Project identity — `scripts/project-name.py` | `python3 -I scripts/project-name.py displayName` and `... repositorySlug` | `project.json` has a nonempty printable `displayName` and a lowercase kebab-case `repositorySlug` | Fast, seconds | `project.json` was edited into an invalid name or slug | Inspect `project.json`; make `repositorySlug` lowercase kebab-case |
| Agent pack — `scripts/check-pack.py` | `python3 -I scripts/check-pack.py` | `.opencode/opencode.json` matches `.opencode/agents` and `.opencode/skills`, specialists are read-only, and both generated configs are current (it reports "is stale: run scripts/gen-opencode.py") | Fast, seconds | An agent/skill was added or changed without regenerating; in a worker the CI profile is active under `skip-worktree` | Run `python3 scripts/gen-opencode.py`; in a worker compare with `git show HEAD:.opencode/opencode.json` before changing anything |
| Docs links and requirement IDs — `scripts/check-docs.py` | `python3 -I scripts/check-docs.py` | Relative Markdown links point at files that exist and every requirement ID outside the spec is defined in `PROJECT_V1_SPEC.md` (worker notes and archives are skipped) | Fast, seconds | A link target was moved/deleted, or an ID was mistyped | Open the reported `file:link` and fix the path or ID |
| Secret and PHI scan — `scripts/check-secrets.py` | `python3 -I scripts/check-secrets.py` | No private keys, cloud/GitHub/Slack tokens, JWTs, password assignments, SSNs or hard-coded NVD keys; a line with `synthetic-ok` is exempt | Fast, seconds | A credential or lookalike value was committed | Remove the value; for an intentional synthetic fixture add `synthetic-ok` to that line |
| Business-domain names — `scripts/check-names.py` | `python3 -I scripts/check-names.py` | No new folder or Java package is named after a story/task/gate ID (`s_g5_05`, `x_04`, `dlv_02`); legacy paths in an allow-list are tolerated | Fast, seconds | A new folder/package copied a task ID | Rename it after the business domain; do not add it to the legacy list |
| Wiring and durability — `scripts/check-wiring.py` | `python3 -I scripts/check-wiring.py` | No unreferenced `*Service`, `InMemory*` in `src/main`, `*Store` without a `Jdbc*` implementation, controller that builds its own collaborator, new `@SuppressFBWarnings` or requirement-ID class/table name; findings in `scripts/wiring-baseline.json` are tolerated, new ones fail | Fast, seconds | A feature is "covered" but not reachable, not durable, or built by hand | Follow the printed advice: wire the service into a bean, add a `Jdbc` store with a migration, or inject the collaborator |
| Migration change safety — `scripts/check-migrations.py` | `python3 -I scripts/check-migrations.py --require-baseline` | Filenames are `V<n>__name.sql`, versions are unique, destructive SQL carries `Change: contract` with a valid `Forward-fix:` and an earlier `Change: expand` reference; `--require-baseline` fails on an empty/missing migration directory | Fast, seconds | A migration renamed/deleted, duplicated a version, or added destructive DDL without headers | Fix filename/headers; add `Change: contract`, `Forward-fix: V<n>` and a valid `Requires-expand: V<n>` (see `docs/reference/maintainer.md`) |
| Python tooling tests — `python3 -I -m unittest` | `python3 -I -m unittest discover -s scripts/tests -q` | The checking scripts themselves behave correctly on synthetic fixtures | Fast, seconds | A check script regressed or a fixture changed | Run the failing `scripts/tests/test_*.py` directly and fix the checker |
| Python coverage — `scripts/python-coverage.sh` | `bash scripts/python-coverage.sh` | Every script line and branch added by a change is covered by a test; a missing `coverage` package only warns locally but fails Delivery | Fast, seconds | An added line or branch has no test, or `coverage` is missing in CI | Add the named test for the uncovered branch; install `coverage` and re-run |

`REQUIRE_PY_COVERAGE` is an **environment variable** set by Delivery
(`.github/workflows/delivery.yml`), not a repository variable. When it is set,
`scripts/python-coverage.sh` fails instead of falling back to plain unittest if the
`coverage` package is unavailable. It never changes a threshold or excludes a file.
| Java compile + unit tests — `scripts/quick-check.sh` | `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` | Main and test sources compile and unit tests pass; `fast` excludes `@Tag("integration")` suites and `no-security` skips OWASP and SpotBugs | Minutes; unit tier only (`scripts/quick-check.sh`) | Compile error, failing unit test, or a DB test that forgot the integration tag | Run the named test class; tag DB suites `@Tag("integration")` |
| Java full verify — `apps/api/check.sh` | `mvn -q -B verify` (the API module runs `cd apps/api && bash check.sh`) | Unit **and** integration tests pass; JaCoCo enforces 100% instruction/line/branch/method/class (`minimum 1.00` in `apps/api/pom.xml`); OWASP Dependency-Check fails at CVSS ≥ 7; SpotBugs 4.10.5.0 runs | Slowest; this is the full gate (`apps/api/check.sh`, `.github/actions/java-verify/action.yml`) | Failing test, missed branch/line, a high-severity CVE, or a SpotBugs finding | Read the Surefire/JaCoCo/OWASP/SpotBugs reports in `apps/api/target`; add the missing test or fix the finding |
| SpotBugs (standalone) — `scripts/worker-check.sh` | `mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check` | Static defect detection without OWASP or a key; compile first so the scan is not vacuous | "about a minute" (`scripts/worker-check.sh`) | A bug pattern such as a constructor that throws before Delivery | Fix the code; keep the compile step ahead of `spotbugs:check` |
| Java integration tier — `scripts/worker-check.sh`, `scripts/worker_gate.py` | `mvn -q -B -f apps/api/pom.xml -Pno-security -Dgroups=integration test` then `python3 -I scripts/worker_gate.py "$status"` | PostgreSQL-backed integration suites pass; `worker_gate.py` fails only on classes related to the change (repair tasks are strict on their named classes) | Minutes; needs PostgreSQL (`scripts/worker-check.sh`) | A real integration failure, or an unrelated class already red on `main` | Check the class name in the gate output; start PostgreSQL via `start-postgres` if it is unavailable |
| Database policy — `db/check.sh` | `bash db/check.sh` | Migrations apply in version order to a fresh synthetic DB; every tenant table has ENABLE + FORCE RLS and a policy; `aurevia_app` cannot bypass RLS; two tenants with code `MAIN` stay isolated; cross-tenant writes fail; audit is append-only and its hash chain verifies; staff/config history is immutable and effective config folds correctly | Minutes; needs PostgreSQL (`db/check.sh`) | Missing FORCE RLS/policy, a privilege grant, or a broken trigger/fold | Read the failing assertion number in `db/check.sh` and inspect the named table or function |
| Frontend standards — `scripts/frontend-check.sh` | `bash scripts/frontend-check.sh` | Per module: `npm ci`, `npm run typecheck`, `npm run lint`, `npm test`; no `.js`/`.jsx`; `tsconfig` has `strict` and `noUncheckedIndexedAccess`; no `any`, `@ts-ignore` or class components; then root `npm ci`, typecheck, lint, `format:check`, test | Minutes; install + tools per module (`scripts/frontend-check.sh`) | TypeScript error, lint/format violation, forbidden pattern, or a missing strict flag | Fix the reported `error TS…`/lint line; run `npm run typecheck` in the failing module |
| Staff web full check — `apps/staff-web/check.sh` | `bash apps/staff-web/check.sh` | Generated client matches `contracts/openapi.yaml` (`gen:api:check`), typecheck, lint, production build, WEB-08 gzip budget ≤ 256,000 bytes per chunk (`check-bundle-size.mjs`), and Vitest 100% per-file coverage (`vite.config.ts`) | Minutes (`apps/staff-web/check.sh`) | Client drift, type/lint error, an oversized chunk, or a missed line/branch | Run the failing sub-command; regenerate the client with `npm run gen:api`; add the missing test |
| Patient mobile full check — `apps/patient-mobile/check.sh` | `bash apps/patient-mobile/check.sh` | Pinned `npm ci`, typecheck, lint and Jest with 100% global coverage over every production source file (`jest.config.ts`); shell-lints `check.sh` and `docs.sh` when ShellCheck is installed | Minutes (`apps/patient-mobile/check.sh`) | Type error (`import { jest } from '@jest/globals'` is required for `jest.*`), lint failure, or missed coverage | Run `npm run typecheck` and fix every `error TS…`; add tests for uncovered branches |
| Touched-area coverage — `scripts/coverage-check.sh` | `bash scripts/coverage-check.sh [base]` (default `origin/main`) | Runs only the gates for touched areas: JaCoCo 100% via `mvn verify` for `apps/api/`/`db/`, `apps/patient-mobile/check.sh` for mobile, and typecheck + `test:coverage` for staff web | As long as the touched check(s) (`scripts/coverage-check.sh`) | A touched file below 100% | Open the coverage report for that file and add the missing test; never lower a threshold |
| Touched-area typecheck — `scripts/frontend-typecheck-check.sh` | `bash scripts/frontend-typecheck-check.sh [base]` | Typecheck and lint only the frontend modules that changed; Jest/Vitest green is not enough when Delivery fails on one TS error | As long as the touched module's tools (`scripts/frontend-typecheck-check.sh`) | `error TS…` or lint error in a changed frontend module | Run `npm run typecheck` in that module and fix every error before committing |
| Developer reference — `scripts/docs.sh` | `bash scripts/docs.sh` | Doxygen builds the Java/site reference and each module's TypeDoc hook emits `index.html`; fails on a missing hook or empty output | Minutes (`scripts/docs.sh`) | Unqualified Javadoc `{@link}`, a TypeDoc warning, or a missing `docs.sh` hook | Fix the Javadoc/Doc comment or add the module hook; requires Doxygen locally |
| Image build and smoke — `scripts/verify.sh`, `scripts/smoke-image.sh` | `bash scripts/verify.sh <version>` | Unless `AUREVIA_SKIP_CHECKS=1`, runs `scripts/check.sh`, then builds the image with version/revision labels and smoke-tests `/health.txt`, an ephemeral DB query, a container restart and data-loss/permission probes | Minutes; Docker required (`scripts/verify.sh`, `scripts/smoke-image.sh`) | Health check never returns `aurevia bootstrap ready`, DB query fails, or restart loses data | Read `docker logs <container>`; check the health endpoint and DB init; Docker is absent in workers and runs in CI only |
| Full local check — `scripts/check.sh` | `bash scripts/check.sh` | Runs every policy check, Python tooling tests, `frontend-check.sh`, each module `check.sh`, and `db/check.sh` when migrations exist | Long; it includes the full API and DB gates (`scripts/check.sh`) | Any one of the rows above | Identify the first failing sub-command from its output and fix that row |
| Worker gate — `scripts/worker-check.sh` | `bash scripts/worker-check.sh` | Quick gate + frontend standards + standalone SpotBugs + integration tier + `db/check.sh`; if PostgreSQL is unavailable it warns and exits 0 unless `REQUIRE_POSTGRES` is set | Minutes (`scripts/worker-check.sh`) | Related integration failure, SpotBugs finding, or `REQUIRE_POSTGRES` with no PostgreSQL | Start PostgreSQL; read the `worker_gate.py` message; fix the named class |
| Integrator pre-merge gate — `scripts/integrator-gate.sh` | `bash scripts/integrator-gate.sh` | Full `scripts/check.sh` plus `scripts/docs.sh` on the merge result, so Javadoc/TypeDoc breakage is rejected before merge | Long; full check + docs (`scripts/integrator-gate.sh`) | Any full-check or docs failure on the merged tree | Re-run the failing row on the merge result; fix before merging |
| Repair merge gate — `scripts/repair-gate.sh` | `bash scripts/repair-gate.sh` | For `FIX-`/`DLV-` PRs: `REQUIRE_POSTGRES=1`, `scripts/worker-check.sh` (quick + integration through `worker_gate.py` + DB) then `scripts/docs.sh`; OWASP, SpotBugs and JaCoCo are left to Delivery after merge | Minutes (`scripts/repair-gate.sh`) | The repair's named classes still fail, or docs break | Fix the named test class; ensure the integrator has PostgreSQL |

## Which checks run where

**Developer machine (full, before a commit).** Run `bash scripts/check.sh` — it runs the
policy checks, Python tooling tests, `scripts/frontend-check.sh`, the three module
`check.sh` files and `db/check.sh` (`scripts/check.sh`). Use `bash scripts/verify.sh
<version>` for the image build/smoke and `bash scripts/docs.sh` for the reference
(`scripts/verify.sh`, `scripts/docs.sh`). The full Maven `verify` behind
`apps/api/check.sh` is the slowest gate; workers are told to leave it to the integrator
(`scripts/worker-check.sh`, `apps/api/check.sh`).

**Worker (fast feedback during a story).** Workers run `bash scripts/worker-check.sh`
(quick gate, frontend standards, SpotBugs, integration tier, DB) and, on the areas a
change touches, `bash scripts/coverage-check.sh <base>` and
`bash scripts/frontend-typecheck-check.sh <base>` (`scripts/worker-check.sh`,
`scripts/agent-run.sh`). The agent loop retries the coverage and frontend-typecheck
gates before publishing. Workers do not run the full `verify`: OWASP and JaCoCo stay
with the integrator, which holds the vulnerability-feed key (`scripts/worker-check.sh`).

**Pull request run (GitHub Delivery, exact SHA).** `.github/workflows/delivery.yml`
splits verification into dependent jobs: `policy` (shell syntax/lint, actionlint,
agent pack, docs, secrets, names, wiring, migrations), `script-tests` (Python tooling
tests), `frontend` (`scripts/frontend-check.sh`), `java-verify` (the reusable
`.github/actions/java-verify` action runs `apps/api/check.sh` and `db/check.sh` with
PostgreSQL 17 and the full JaCoCo/OWASP/SpotBugs tier), and `docs` (`scripts/docs.sh`).
The single `Delivery gate` job requires every verification job to pass or be skipped
on purpose, and branch protection checks it on the exact commit. A skipped run is never
verification evidence, so the integrator reruns the exact pull-request run instead of
merging. There is no path filter on either trigger: every pull request runs this workflow,
including documentation-only changes. Every push to `main` and every version tag runs it
too, including documentation-only commits; the tag trigger is `v*`. Dispatch and
merge-group runs report the same check on their exact SHA. A path-filtered run would
leave the required exact-SHA `Delivery gate` check unreported and block the protected
branch. Pull requests use the head SHA as their concurrency group, so a new head is
verified without waiting behind an older one; every other trigger queues per ref with
`cancel-in-progress: false` and `queue: max`, so no exact-SHA verdict is dropped
(`.github/workflows/delivery.yml`).

**Integrator (before merge and on tags).** The integrator runs
`bash scripts/integrator-gate.sh` — full `check.sh` plus `docs.sh` — on each merge
result (`scripts/integrator-gate.sh`). Repair PRs instead use
`bash scripts/repair-gate.sh` so unrelated red suites do not block them
(`scripts/repair-gate.sh`). For main pushes and tags, the `verify` job runs
`scripts/verify.sh` for native AMD64 (and ARM64 on tags) image smoke; the `release`
job then requires a green default-branch run for that commit before publishing the
multi-architecture image and release (`.github/workflows/delivery.yml`).
