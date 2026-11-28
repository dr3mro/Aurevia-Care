# DOC-API-02 · Java API: add source documentation to the exchange order, release, response and result packages

PR #1822, tested branch tip e380d35b991a59a56315f0c59d798fab2875a4c4.

Task: DOC-API-02

Swarm worker 2. Checks: passed. Local checks: failed. Agent result: ok.

# DOC-API-02 — Java API: add source documentation to the exchange order, release, response and result packages

Tested revision: 7e3b1e1699c71dad73987dca46aaf929f88c4f9d

## Summary

Documentation-only change across the 41 main Java sources in `exchange/order`, `exchange/release`, `exchange/response` and `exchange/result`, plus four new annotation-free `package-info.java` files. Every public member now carries complete Javadoc (including route-tailored `@throws com.aurevia.api.ApiException` on the 31 controller route methods), all story/requirement ID tokens were removed from comments, and ten stale or over-broad comments from pre-code review (seven) and security review (two, plus one alignment fix) and five Required items plus three Optional refinements from code review were corrected against the code. Every `{@link}`/`@see` target is fully qualified. No executable line changed. Requirement OPS-06.

## Affected requirements

OPS-06 at `.opencode/config/PROJECT_V1_SPEC.md:139` (synthetic fixtures, CI policy checks, auditable operations). Documentation traceability is indirect; no requirement or specification file changed.

## Risk class

Routine — comments and four `package-info.java` files only. The verified `git diff -U0` audit reports `changed lines: +191 -90; non-comment changed lines: 0`.

## Review owner

Primary agent owns the scoped source edits and both notes files. Read-only specialists: context-optimizer, test-engineer, senior-backend, senior-java-build (pre-code proposals); plan-reviewer (pre-code plan); code-reviewer, coverage-auditor, devsecops, qa-reviewer (post-change).

## Data changes

None. No migration, RLS policy, backfill, lock, volume impact, route, OpenAPI entry or regenerated client. Synthetic fixtures only; no patient data, credential, real identifier or ID token remains in a comment.

## Authorization boundaries and audit events

None changed. The comments describe the existing tenant derivation, membership/role re-derivation, `If-Match` version precondition, optimistic comparison, hold/withdraw/correct rules, retention-after-acceptance rule, reconciliation idempotency and audit/outbox ordering. No scope, role, permission or audit event is added.

## Migration and rollout considerations

None. No deploy, rollout or schema code is involved.

## Test evidence

Evidence source: executed tool commands (shell) from the repository root, plus read-only specialist findings recorded under Findings. No simulated-user walkthrough, authorized human feedback or real deployment evidence is used. Generated scratch artifacts stayed under `/tmp/aurevia-docapi02.1795818453`; the only files written into the repository are the scoped Java sources and the two notes copies intended for this PR.

- `git merge --no-edit origin/main` → `Already up to date.`; `git grep -nE '^(<<<<<<<|>>>>>>>) '` (excluding `docs/swarm`) → no output.
- Baseline HEAD: `7e3b1e1699c71dad73987dca46aaf929f88c4f9d` on branch `swarm/DOC-API-02-1747`; clean worktree before the first edit.
- File inventory: 41 main `.java` files — order 11, release 10, response 7, result 13. No existing `package-info.java` in the four packages.
- Baseline **before** edits: `python3 -I -m unittest discover -s scripts/tests -q` → exit 1, `Ran 2477 tests in 132.265s`, `FAILED (failures=5, skipped=1)`. The five named failures: `test_check_pack.PackTests.test_committed_opencode_json_is_generated`, `test_check_pack.PackTests.test_simulated_user_skills_need_no_process_sections`, `test_check_pack.PackTests.test_system_namespace_skill_is_not_a_project_skill`, `test_notes_files.NotesFileTests.test_daily_archives_keep_the_documented_dated_shape`, `test_process_env.ProductionBoundaryTest.test_run_gate_reaps_a_started_background_descendant`.
- RED docs scan **before** edits, detector run against pristine `HEAD` sources extracted into `/tmp`: `python3 /tmp/aurevia-docapi02.1795818453/docs-scan-baseline.py` → exit 1, `SCAN files=41 findings=132 ID-IN-COMMENT=101 NO-JAVADOC=0 BAD-LINK=0 TAG-INCOMPLETE=31 PACKAGE-INFO=0 per_pkg=order:17,release:31,response:48,result:36`.
- GREEN docs scan **after** edits: `python3 /tmp/aurevia-docapi02.1795818453/docs-scan.py` → exit 0, `SCAN files=45 findings=0 ID-IN-COMMENT=0 NO-JAVADOC=0 BAD-LINK=0 TAG-INCOMPLETE=0 PACKAGE-INFO=0 per_pkg=order:0,release:0,response:0,result:0` (45 = 41 sources + 4 new package-info files). The scan masks string literals and text blocks before reading comments and checks `@see` as well as `{@link}`.
- Targeted Maven command (before and after): `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=OrderExchangeIntegrationTest,PatientReleaseControllerHttpTest,PatientReleasedRevisionsControllerHttpTest,ResultControllerHttpTest,ExchangeResponseControllerTest,ExchangeResponseExchangeTest test` → baseline exit 0; canonical Surefire dump `6 suites, 62 tests, 0 errors, 0 failures, 0 skipped`.
- `bash scripts/worker-check.sh` → exit 1. It stops in `scripts/quick-check.sh` at `bash scripts/python-coverage.sh`, whose final summary is `Ran 2477 tests in 224.249s` and `FAILED (failures=5, skipped=1)` — the same five pre-existing failures. Every later stage was run individually (see below).
- After edits: `python3 -I -m unittest discover -s scripts/tests -q` → exit 1, `Ran 2477 tests in 184.009s`, `FAILED (failures=5, skipped=1)`. Normalizing only the elapsed-seconds line, `diff -u` of the before/after failure-name-and-summary canonical dumps is empty: the same five named failures and the same counts; no new failure and none suppressed.
- After edits: the targeted Maven command above → exit 0; canonical Surefire dump `6 suites, 62 tests, 0 errors, 0 failures, 0 skipped`; `diff -u` against the baseline canonical dump is empty (each suite, test case and outcome identical).
- After edits: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check` → exit 0, no findings (SpotBugs ran after a compile).
- After edits: `mvn -q -B -f apps/api/pom.xml -Pno-security jacoco:report` → exit 0; `grep -o 'name="[^"]*package-info"' apps/api/target/site/jacoco/jacoco.xml | wc -l` → `0`.
- After notes were written: `python3 -I scripts/check-docs.py` and `python3 -I scripts/check-secrets.py` → exit 0 each.
- Individually-run later stages of `worker-check.sh`: shell `bash -n` over the shell scripts → exit 0; `shellcheck` over those scripts → exit 0; `python3 -I scripts/project-name.py displayName/repositorySlug` → `Aurevia Care` / `aurevia-care`; `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → exit 0 (unit tests only); `bash scripts/frontend-check.sh` → exit 0, final line `frontend standards: policy and tool commands passed`; `bash db/check.sh` → exit 0, final line `db: migrations, forced RLS, role separation, isolation, audit, append-only staff history, versioned config and durable reply keys verified`; `mvn -q -B -f apps/api/pom.xml -Pno-security -Dgroups=integration test` plus `python3 -I scripts/worker_gate.py` → see the explicit result below.
- Fast gates after edits, each exit 0: `python3 -I scripts/check-wiring.py` (no findings); `python3 -I -m unittest discover -s scripts/tests -p test_java_conventions.py -q` → `Ran 2 tests ... OK`; `python3 -I scripts/check-pack.py` → `Delivery tooling validated: 39 agents, 39 skills`; `python3 -I scripts/check-docs.py`, `check-secrets.py`, `check-names.py`, `check-constraints.py`, `check-migrations.py --require-baseline`, `python3 -I scripts/gen-opencode.py --check`.
- Full integration stage, run separately because `worker-check.sh` stops early: `timeout 280 mvn -q -B -f apps/api/pom.xml -Pno-security -Dgroups=integration test` → exit 124 (the 280-second worker cap). It wrote 105 Surefire reports covering 810 tests with 0 errors, 0 failures and 1 skip before the cap; the six targeted suites above, including all five HTTP entry points, completed green. `NOTES_FILE=.agents/notes/DOC-API-02-202
