# FIX-JOBS-EXPORTS-TEST-COMPILE — worker notes

Story: FIX-JOBS-EXPORTS-TEST-COMPILE (task `01276000`), requirements **ARC-02**, **OPS-06**.
Scope: `apps/api/src/test/java/com/aurevia/tenancy/clauses/JobsExportsSearchAttachmentsIntegrationsApplySameTest.java` only (plus this notes file). No production Java, migration, script, config or tracker was edited.

## Loaded skills

- `aurevia-delivery` — matched "work any story or swarm task, from claiming it to the notes file and the pull request".
- No additional skill matched: the brief named only delivery, and this run touched no production Java, migration, frontend, workflow or infrastructure.

## Acceptance mapping

- **ARC-02** — "Jobs, exports, search, attachments and integrations apply the same policy": the 5-test suite exercises all five surfaces; both export refusal helpers still call the real `TenantExportService` inside `as(...)` and their caught `ApiException.status()` is asserted at lines 503 (404), 606 (410) and 583–586 (403 × 4). Missing-subject 401 and missing-organization 422 assertions remain at lines 552–577.
- **OPS-06** — synthetic fixtures only: synthetic UUIDs, `MRN-SHARED`, `CBC-SHARED`, `shared-scan.pdf`, repeated-char digest and `%PDF-1.4 synthetic original` payload; DB admin read from `AUREVIA_PG_ADMIN` env, no embedded credential, no PHI or real identifier.
- **Compile repair** — CI run 45337601399 failed at lines 397/419 with `cannot find symbol: variable status`. Verified in the current tree: zero `status.setRollbackOnly()` calls remain; the comments at ~395 and ~415 now state the service owns its transaction and there is no outer transaction to mark rollback-only. The fix is already present in this checkout (previously landed on `main` as recorded in the task file); **this worker run made no code change** and did not fabricate a delta.

## Test evidence

Tool evidence (executed in this run), commands and final lines:

1. `mvn -q -f apps/api/pom.xml -DskipTests test-compile` → `EXIT=0` (no output; `-q`).
2. `mvn -q -f apps/api/pom.xml -Pno-security -Dgroups=integration -Dtest=JobsExportsSearchAttachmentsIntegrationsApplySameTest test` → `EXIT=0`; Surefire report `apps/api/target/surefire-reports/com.aurevia.tenancy.clauses.JobsExportsSearchAttachmentsIntegrationsApplySameTest.txt` final line: `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 10.62 s -- in com.aurevia.tenancy.clauses.JobsExportsSearchAttachmentsIntegrationsApplySameTest`. The `-Pno-security` profile was used **without** `fast` (the `fast` profile sets `excludedGroups=integration` and would silently skip the suite).
3. `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` → `Ran 2 tests in 0.058s` / `OK` / `EXIT=0`.
4. Static in-scope scan for conflict markers: `grep -nE '^(<<<<<<<|>>>>>>>) '` on the scoped file → no matches.

Entry point to test: `@SpringBootTest` RANDOM_PORT + `@Tag("integration")` class → `allFiveSurfacesApplyTheSameTenantPolicy()` and the four status assertions named above → real `TenantExportService`, `JdbcExportStore` and `ObjectStorePort` on the disposable `aurevia_arc02_c05_it` PostgreSQL database.

Not run, with reason (durable owner directive 2026-11-24 in MEMORY.md: "do not run Git-using scripts or tests"): `bash scripts/worker-check.sh` (delegates to `quick-check.sh` → `check-pack.py`, and `worker_gate.py` resolves a Git base ref), `git merge --no-edit origin/main`, `git diff`, `git grep`, and the full `mvn verify` / all-integration-test command (also exceeds the worker time budget). The branch-refresh instruction in the advisor brief is superseded by that durable directive. No commits, pushes, tags or `gh` calls were made.

Evidence sources: executed tool commands (above), read-only specialist reviewers (below). No simulated-user walkthrough and no authorized human feedback applies to a compile repair.

## Review verdicts

- aurevia-plan-reviewer: GO
- aurevia-code-reviewer: GO
- aurevia-coverage-auditor: GO
- aurevia-devsecops: GO

## Coverage

No production line, branch, method or class changed — the only edits in scope were the earlier deletion of two non-compiling statements in **test** code and the correction of two comments. The 100% production-coverage bar is therefore vacuous for this change; no threshold, exclusion, suppression or skipped test was added, and no JaCoCo result was weakened or fabricated.

Command and result for the touched area: `mvn -q -f apps/api/pom.xml -Pno-security -Dgroups=integration -Dtest=JobsExportsSearchAttachmentsIntegrationsApplySameTest test` → exit 0, `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`. Both helper catch branches are taken and asserted: `exportDownloadRefusal` (line 393) at lines 503 (404) and 606 (410); `exportStartRefusal` (line 414) four times at lines 583–586 (403). Removing the two statements introduced no branch and left no unreachable code. The full JaCoCo `verify` is the integrator's gate.

## Findings

- Diagnosis: `status.setRollbackOnly()` was called inside a `Supplier` lambda passed to `as(...)`, which binds only `TenantContext` and provides no transaction `status`; the surrounding Javadoc also described an outer `TransactionTemplate` that the service's own transaction makes unnecessary. Both calls and the inaccurate claims are absent from the current tree.
- The compile fix was already on `main` before this worker run (task file and WORKSPACE.md record commit `97dcbb0f`). This run re-proves compilation and the targeted suite; it adds no Java delta. No missing contract, wiring, migration or OpenAPI change is implied.
- Durable conflict: the advisor brief asked for `git merge --no-edit origin/main`, `git diff` checks and a commit. The owner's durable directive forbids Git commands and Git-backed operations, so those steps were not executed. The integrator owns tracker marks and publication.
- No reproducible product defect was found beyond the already-fixed compile error; nothing to add to BUGS.md.

## Lessons for workers

- Do not re-diagnose an already-landed fix: read the task `evidence:` first. Here the correct outcome is verification-only, not a synthetic code delta.
- Use `-Pno-security -Dgroups=integration` without the `fast` profile; otherwise Surefire reports zero tests and a "green" run proves nothing. Confirm the Surefire `Tests run:` line.
- When an advisor brief conflicts with a durable MEMORY.md directive, follow the directive and record the conflict in the notes rather than silently taking either path.
