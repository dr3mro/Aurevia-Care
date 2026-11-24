# BUILD-30 · Persist released reports and release history with JDBC

PR #306, tested branch tip 044a0f297e91faac07e5f7c82ff8379e6a9d234b.

Task: BUILD-30

Swarm worker 9. Local checks: passed. Agent result: ok.

# BUILD-30 — Persist released reports and release history with JDBC

## Summary

Extracted `ReportReleaseStore` from the in-memory report-release adapter and added `JdbcReportReleaseStore`, a tenant-guarded Spring JDBC adapter backed by a new V84 migration. A release decision records the immutable revision, release policy version, effective time and optional hold reason/expiry, updates current access and appends an immutable history row in the same transaction. Withdrawal stops the next download while history stays queryable; a withdrawn revision is terminal and cannot be re-released. Two tenants with identical local identifiers remain isolated by forced row-level security. The synthetic adapter moved to `src/test`, and the controller now receives the service by injection (no `new ...Service(...)` in main). All fixtures are synthetic.

## Affected requirements

XCH-06 (separate patient release authorization, immutable revision, policy version, effective time, hold reason/expiry, withdrawal/correction keeps history, terminal withdrawal), PAT-02 (released report download authorized against linked patient and current release). Supporting: ARC-01, ARC-02, OPS-02.

## Risk class

Cross-boundary (new persistence port, Spring wiring and a new Flyway migration on a patient-facing download path). No clinical sign-off or real-patient data in this slice.

## Review owner

Senior Java/Spring Boot and Senior PostgreSQL/Flyway bounded reviews were run; their findings and dispositions are recorded under Findings. Reviewer evidence is simulated specialist review, not human or clinical acceptance.

## Data changes

New V84 migration (`db/migrations/V84__report_release_store.sql`, `Change: expand`, additive only) creates three tenant-owned tables:

- `report_chart_link` — current verified/active patient link per (organization, subject).
- `report_release_revision` — immutable revision identity and rendered artifact, current status and latest policy version; `guard_report_revision_update()` rejects identity changes and any `WITHDRAWN → RELEASED` transition.
- `report_release_history` — append-only release decisions (policy version, effective time, hold reason/expiry, status, recorded at); UPDATE/DELETE/TRUNCATE triggers cover every role including the migration owner.

Each table has ENABLE + FORCE RLS, a `tenant_isolation` policy using `public.app_org()` with `WITH CHECK`, and explicit grants/revokes for `orevia_app`. `report_release_revision` also has BEFORE DELETE and BEFORE TRUNCATE triggers; TRUNCATE is additionally blocked by the history foreign key. No backfill; lock/volume impact is DDL only on small new tables. Rollback is a forward-fix contract migration; no destructive DDL is needed. No dependency added.

## Authorization boundaries and audit events

Tenant comes only from `TenantContext` (bound to `app.current_org()` inside the guarded transaction and written on every insert). The verified, active chart link is re-read on every protected action; `ReleasedReportService` still denies unlinked/inactive subjects with 403 and checks status, effective date, supersession and current revision before a download. Existing audit outcomes (`REPORT_LIST`, `REPORT_DOWNLOAD`, purposes `PATIENT_REPORT_ACCESS`/`PATIENT_REPORT`) are unchanged. The audit sink remains the pre-existing in-memory implementation; a durable audit adapter is a separate follow-up and is not a regression from this change.

## Migration and rollout considerations

V84 is the reserved version and is additive. `db/check.sh` and Flyway both apply it in numeric order; the integration suite applied it as `84 - report release store`. No production table is altered, so rollout needs no data backfill. The append-only history and terminal-withdrawal invariants are protected by database constraints/triggers, not grants alone.

## Test evidence

Evidence source for every command below: executed tool commands in this checkout (no human or clinical participant; the two specialist reviews are simulated reviewers, distinguished under Findings).

- Red first: `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=JdbcReportReleaseStoreBranchTest test` — failed with `cannot find symbol: class JdbcReportReleaseStore` and `class ReportRelease` (the new port did not exist yet).
- Green targeted: `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=JdbcReportReleaseStoreBranchTest,ReleasedReportServiceTest,ReleasedReportControllerHttpTest test` — exit 0.
- Full unit profile: `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` — exit 0; Surefire reports total 2067 tests, 0 failures, 0 errors, 0 skipped (run before the final review-driven test additions; the targeted suite was re-run after them).
- Real PostgreSQL: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dgroups=integration -Dtest=JdbcReportReleaseStoreIntegrationTest test` — exit 0; final report `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`. Entry point to test trace: `GET /api/v1/patient/reports/{id}/download?revision=1` through `ReleasedReportController` → injected `ReleasedReportService` → `JdbcReportReleaseStore` → V84 tables, under `TenantContextInterceptor` and the guarded transaction. Tests cover withdrawal stops the next download (200 then 410 and absent from the list), correction makes the original stale (409) and shows the corrected revision (200), identical local IDs isolated across two tenants, hold reason/expiry persisted, history UPDATE/DELETE/TRUNCATE rejected with SQLSTATE `44000`, revision identity UPDATE rejected with `42501` while status/policy_version updates succeed, cross-tenant writes rejected by `WITH CHECK`, and a withdrawn revision rejected on re-release with the download still 410.
- Migration/policy: `bash db/check.sh` — exit 0; final line `db: migrations, forced RLS, role separation, isolation, audit, append-only staff history and versioned config verified`.
- Static analysis: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check` — exit 0 (run before the final review-driven changes; no new production construct was added afterwards beyond a guarded SQL upsert and Javadoc).
- Policy checks: `python3 -I scripts/check-names.py` (exit 0), `python3 -I scripts/check-migrations.py --require-baseline` (exit 0), `python3 -I scripts/check-wiring.py` (exit 0; notes only: the now-fixed baseline lines for `inmemory-in-main: .../InMemoryReportReleaseStore.java` and `controller-builds: .../ReleasedReportController.java`; baseline is owned by the orchestrator and was not edited), `python3 -I scripts/check-docs.py` (exit 0), `python3 -I scripts/check-secrets.py` (exit 0).
- Known environment artifact (pre-existing, not caused by this change): `python3 -I -m unittest discover -s scripts/tests -q` → `Ran 436 tests` / `FAILED (failures=3)`, all three in `test_check_pack.py` (`opencode.json is stale`). `scripts/check.sh` stops at the same check: `check-pack: opencode.json is stale: run scripts/gen-opencode.py` (exit 1). Evidence that this is a worker-profile artifact: `git ls-files -v opencode.json` reports `S` (skip-worktree), worktree md5 `2225aa1420662e9e9b34d8ac78904974` differs from `git show HEAD:opencode.json` md5 `33f05dee9a1ae5e7581a75be2ca5ad59`; `opencode.json` is protected and was not touched. `git diff --name-only HEAD -- opencode.json .github/opencode.ci.json` is empty. Per MEMORY.md this is the CI profile copied by `agent-run.sh`, not HEAD truth.
- `worker-check.sh` was not run as a whole: the story prompt forbids commands that run every integration suite and caps commands at five minutes. Its relevant constituent gates were run individually above (unit suite, targeted integration, db policy, SpotBugs, coverage); frontend checks were skipped because no frontend file is in scope.

## Coverage

Command: `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=JdbcReportReleaseStoreBranchTest,ReleasedReportS
