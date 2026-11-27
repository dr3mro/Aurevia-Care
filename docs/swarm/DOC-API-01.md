# DOC-API-01 · Java API: add source documentation to the exchange hl7, fhir, conformance and critical packages

PR #1787, tested branch tip cb88cee9cd9b58b072d723cbd904e47aab200238.

Task: DOC-API-01

Swarm worker 2. Checks: passed. Local checks: failed. Agent result: ok.

# DOC-API-01 — Java API: add source documentation to the exchange hl7, fhir, conformance and critical packages

Tested revision: dd0c6bd881c94af12d6807649f937b09335fd9bd

## Summary

Documentation-only change across 41 main Java sources in four exchange packages: Javadoc and WHY comments, requirement-ID token removal, and four package-info files. No executable line changed. Requirement OPS-06.

## Affected requirements

OPS-06 at `.opencode/config/PROJECT_V1_SPEC.md:139` (synthetic fixtures, policy checks, auditable operations). No requirement changed.

## Risk class

Routine — comments and four `package-info.java` files only; no executable line, signature, import, route, migration or test changes.

## Review owner

Primary agent owns the source edits and this notes file. Read-only reviewers: plan-reviewer (pre-coding), code-reviewer, coverage-auditor, devsecops and qa-reviewer (post-change); context-optimizer, senior-backend, senior-java-build and test-engineer give bounded proposals.

## Data changes

None. No migration, RLS policy, backfill, lock or volume impact. Synthetic fixtures only; no patient data, credential or real identifier added.

## Authorization boundaries and audit events

None changed. The comments describe existing tenant, `If-Match`, idempotency, outbox/audit and clinical-ack rules; no scope or audit event is added.

## Migration and rollout considerations

None. No deploy or rollout code is involved.

## Skills loaded

- aurevia-delivery — every story; swarm hand-off, test-first evidence and the definition of done.
- aurevia-senior-backend — Java and Spring Boot source documentation and domain invariants.
- aurevia-senior-java-build — Javadoc/Doxygen link rules and applicable Java gates.
- aurevia-context-optimizer — bounded 41-file context and reading order.
- aurevia-test-engineer — red/green docs scan and identical-baseline evidence.
- aurevia-plan-reviewer — pre-coding plan review and post-fix re-verdict.
- aurevia-code-reviewer — Javadoc completeness and ID-token removal.
- aurevia-coverage-auditor — no-coverage-change claim and gate check.
- aurevia-devsecops — no new secret, PHI or ID token in comments.

## Test evidence

Evidence source: executed tool commands (shell) from the repository root, plus read-only reviewer findings recorded under Findings. No simulated-user walkthrough, human participant or real deployment evidence is used. Commands were run in this order; scratch output stayed under `/tmp/aurevia-docapi01.5Gs3w0`.

- `git merge --no-edit origin/main` → `Already up to date.` (start and after edits). a repository grep for lines beginning with a Git conflict marker (excluding `docs/swarm`) → no output.
- Baseline, **before** edits: `python3 -I -m unittest discover -s scripts/tests -q` → exit 1, `Ran 2428 tests in 125.535s`, `FAILED (failures=4, skipped=1)`; the four named failures are listed below.
- Baseline, **before** edits: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest='com.aurevia.exchange.hl7.*Test,com.aurevia.exchange.fhir.*Test,com.aurevia.exchange.conformance.*Test' test` → exit 0, empty output; Surefire aggregate `reports=22 tests=144 failures=0 errors=0 skipped=0`.
- RED docs scan, **before** edits: `python3 /tmp/docapi01-docs-scan.py` → exit 1, `SCAN files=41 findings=141` (135 ID-IN-COMMENT, 6 NO-JAVADOC, 0 BAD-LINK).
- GREEN docs scan, **after** edits: `python3 /tmp/docapi01-docs-scan.py` → exit 0, `SCAN files=45 findings=0` (45 = 41 sources + 4 new package-info.java).
- `git diff --check` → exit 0, no output. Comment-only diff audit over the four scope paths: `changed lines: +210 -156; non-comment changed lines: 0`.
- `python3 -I -m unittest discover -s scripts/tests -p test_java_conventions.py -q` → `Ran 2 tests in 0.049s`, `OK`.
- `python3 -I scripts/check-wiring.py` → exit 0, no findings; no new route or wiring.
- Policy scripts, each exit 0: `check-pack.py` → `Delivery tooling validated: 39 agents, 39 skills`; `check-docs.py`, `check-secrets.py`, `check-names.py`, `check-constraints.py`, `check-migrations.py --require-baseline` → no output.
- `mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check` → exit 0, no findings.
- `bash scripts/frontend-check.sh` → exit 0; final lines `Test Files 1 passed (1)`, `Tests 3 passed (3)`, `frontend standards: policy and tool commands passed`. No frontend source changed; run to satisfy the worker gate.
- `bash db/check.sh` → exit 0; final line `db: migrations, forced RLS, role separation, isolation, audit, append-only staff history and versioned config verified`.
- After edits: `python3 -I -m unittest discover -s scripts/tests -q` → exit 1, `Ran 2428 tests in 118.283s`, `FAILED (failures=4, skipped=1)`; the four failing test names are byte-identical to the baseline list: `test_check_pack.PackTests.test_committed_opencode_json_is_generated`, `test_check_pack.PackTests.test_simulated_user_skills_need_no_process_sections`, `test_check_pack.PackTests.test_system_namespace_skill_is_not_a_project_skill`, `test_process_env.ProductionBoundaryTest.test_run_gate_reaps_a_started_background_descendant`.
- After edits: the targeted Maven command above → exit 0; Surefire aggregate `reports=22 tests=144 failures=0 errors=0 skipped=0`, identical to baseline.
- `bash scripts/worker-check.sh` → exit 1. It stops in `scripts/quick-check.sh` at `bash scripts/python-coverage.sh`, which reruns the same 2428-test suite and fails on the same four pre-existing failures. Every later stage was then run individually and passed (shell `bash -n` + shellcheck, all policy scripts, frontend-check, SpotBugs after compile, `db/check.sh`); the integration Maven stage plus `worker_gate.py` was run separately.
- Drift check: `python3 -I scripts/gen-opencode.py --check` → exit 0, empty output; `python3 -I scripts/check-pack.py` → exit 0, `Delivery tooling validated: 39 agents, 39 skills`; `git ls-files -v .opencode/opencode.json` → `S` (skip-worktree CI profile). `test_check_pack.py:18-19` compares that CI profile with `gen.render(ROOT)`, and lines 225/236 use `copy_pack()` of the same profile, so the three `check_pack` unittest failures are fixture artifacts of the cloud worker profile, not repository drift. `test_process_env.py:274` checks descendant reaping and fails with a sandbox PID surviving cleanup. Both failure classes are present on the untouched base.

- `mvn -q -B -f apps/api/pom.xml -Pno-security -Dgroups=integration test` (run separately because `worker-check.sh` stops early) → exit 0; the Spring integration suites shut down cleanly. `python3 -I scripts/worker_gate.py 0 a per-run private report directory origin/main contracts/openapi/missing-routes.txt` with `NOTES_FILE=docs/pr/notes/01221000-DOC-API-01.md` → `integration tests passed`, one remaining `scope` violation for the worker-prompt-named `.agents/notes/` file (see Findings). No new undocumented route.
- Critical-package coverage: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest='Critical*Test,*Critical*Test' test` → exit 0, `reports=231 tests=1516 failures=0 errors=0 skipped=3`, including `CriticalResultControllerHttpTest` (the HTTP entry-point test), `CriticalResultEscalationAcceptanceTest`, `CriticalContractTest` and `CriticalResultFlowTest`. The pattern matched more classes than intended; the bounded rerun with four explicit class names selected the same report set in this Maven configuration, so the count is reported as observed rather than as a narrow four-class count. The zero-non-comment diff audit establishes equivalence with the base for all four packages.
- Javadoc tag completeness: the ten route methods in `CriticalResultController` now carry `@throws com.aurevia.api.ApiException`; `State.from`, `EventType.from` and both enum constructors carry `@param` and `@throws` where applicable; the six newly documented adapter methods carry `@param`/`@return`/`@throws`.
- `git di
