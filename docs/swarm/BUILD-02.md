# BUILD-02 · Role history: immutable membership and role assignment history

PR #293, tested branch tip b95383eca9991e701410f4ed70f6b0ad247cc974.

Task: BUILD-02

Swarm worker 7. Local checks: passed. Agent result: ok.

## Summary

Added an authorization-owned, append-only IAM-02 role-change ledger and a tenant-admin read endpoint. `V71__build_02.sql` creates `public.role_change_history` (who = `actor`, when = `recorded_at`, effective `from`/`to`, `change_kind` GRANT/CHANGE/REVOKE) with ENABLE + FORCE RLS, a tenant policy, owner-proof immutability triggers and SELECT-only grants for the runtime role. Database triggers on the V4 `role_assignment` / `membership` inserts populate the ledger inside the same transaction, so every grant, replacement and revocation is recorded without touching the out-of-scope `staff`/`web` packages. A new `authz.history` subpackage exposes `GET /api/v1/authz/role-history/{subject}` through a thin injected controller and a service that re-derives membership and roles from the authoritative folds on every request.

The existing `staff.StaffHistory` / `GET /api/v1/staff/{subject}/history` was deliberately **not** duplicated or modified: it is an effective-state projection for the staff UI, while this slice is an authorization-owned flat event ledger with explicit change kind, actor and effective window, scoped to tenant admins at a new authz endpoint.

## Affected requirements

IAM-02 (immutable membership/role history; who/when/effective dates; next-request permission effect), ARC-02 (forced RLS, tenant policy, fail closed), OPS-02 (audit of history reads), ARC-03 (read-only versioned endpoint).

## Risk class

Safety-critical (authorization/identity administration): negative authorization, cross-tenant concealment, immutability and next-request revocation are covered by explicit tests. Reviewers: `orevia-senior-spring-security`, `orevia-senior-database`, `orevia-senior-backend`, `orevia-test-engineer`, `orevia-qa-reviewer` (simulated plan/review; code and checks executed by this worker).

## Review owner

Product Owner / Spring Security + Database specialists. No human clinical or pilot evidence was used; all fixtures are synthetic (`admin-a`, `employee-a`, org UUIDs `1111…`/`2222…`).

## Data changes

New table `public.role_change_history` (V71, additive expand; no backfill). New functions/triggers `capture_role_change()`, `role_change_capture_grant`, `role_change_capture_revoke`, `role_change_history_no_update_delete`, `role_change_history_no_truncate`. Index `(organization_id, subject, effective_from DESC, id DESC)`. No existing table, row or constraint is altered. Lock/volume impact: one extra ledger insert per role grant or revocation, one index write per insert; bounded by staff administration volume. Forward-fix for a hypothetical contract migration: drop triggers then table (no destructive DDL in this expand migration).

## Authorization boundaries and audit events

Tenant comes only from `X-Active-Organization`, validated via `TenantContextInterceptor`; actor from `X-Synthetic-Subject` (synthetic-auth profile). The service re-checks `has_active_membership(subject)` and `active_roles(subject)` from the V4 folds on each call and requires the new `ActionPolicy.ROLE_HISTORY_READ` action (tenant_admin only). Unknown/cross-tenant subjects are concealed as 404. Audit events are appended through `AccessRepository.appendAudit` with purpose `ROLE_HISTORY`, scope/resource `AUTHZ`, action `ROLE_HISTORY_READ`, outcomes `SUCCESS`/`DENIED`/`NOT_FOUND`. No write route exists; the ledger is append-only in the database for every role including the migration owner.

## Migration and rollout considerations

V71 is purely additive (`Change: expand`), carries the required Requirement IDs header and passes `scripts/check-migrations.py --require-baseline`. Each new table uses its own ENABLE + FORCE RLS and policy (V2's static list does not cover new tables). The capture function is `SECURITY DEFINER` and saves/restores the `app.current_org` GUC around its insert so a trigger cannot leak tenant context; the GUC is restored to `''` when originally unset, which `public.app_org()` maps back to NULL. Triggers are defensive: a role event whose subject cannot be resolved is skipped rather than failing the originating staff command. Existing staff/access/specialty integration suites were re-run to confirm no seeding regression.

## Test evidence

All commands executed by this worker (tool evidence; no human or simulated-user claim). `EXIT:n` is the observed process exit code.

- `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=RoleHistoryTest,JdbcRoleHistoryStoreTest,RoleHistoryServiceTest,RoleHistoryControllerTest,ActionPolicyTest test` → `EXIT:0` (red first: the same command before implementation failed to compile with `package RoleHistory does not exist`).
- `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=RoleHistoryIntegrationTest -Dgroups=integration test` → `EXIT:0`; final report line: `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0` (`RoleHistoryIntegrationTest`). Covers real PostgreSQL population/immutability/tenant isolation/next-request 403.
- `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=StaffLifecycleIsolationTest,AccessIntegrationTest,SpecialtyAuthorizationFlowTest -Dgroups=integration test` → `EXIT:0`; `Tests run: 7 / 8 / 8, Failures: 0, Errors: 0, Skipped: 0` (no regression from the new triggers).
- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → `EXIT:0` (all unit tests).
- `mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check` → `EXIT:0` (SpotBugs 4.10.5.0, no findings; no new `@SuppressFBWarnings`).
- `python3 -I scripts/check-names.py`, `python3 -I scripts/check-wiring.py`, `python3 -I scripts/check-docs.py`, `python3 -I scripts/check-secrets.py`, `python3 -I scripts/check-migrations.py --require-baseline` → each `EXIT:0`.
- `bash scripts/docs.sh` → `EXIT:0` (`Developer reference: …/build/developer-reference/html/index.html`).
- `bash scripts/quick-check.sh` → `EXIT:1` at `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. This is a pre-existing cloud-worker artifact: `git ls-files -v opencode.json` shows flag `S` (skip-worktree) and the worktree file differs from `git show HEAD:opencode.json` (CI profile copied by `agent-run.sh`), as recorded in MEMORY/BUGS OPS-CW-003-01. No file under `.opencode/`, `scripts/` or `opencode.json` was touched by this change. The same artifact causes the 3 `test_check_pack` failures in `python3 -I -m unittest discover -s scripts/tests -q` (`FAILED (failures=3)` of `Ran 436 tests`); all other 433 tests pass.

Entry-point-to-test trace: `GET /api/v1/authz/role-history/{subject}` → `RoleHistoryController.history` → `RoleHistoryService.history` → `AccessRepository.hasActiveMembership/activeRoles` (V4 folds) + `ActionPolicy.allows(ROLE_HISTORY_READ, role)` → `JdbcRoleHistoryStore.findBySubject` reads `public.role_change_history` → JSON `RoleHistory`. HTTP unit evidence: `RoleHistoryControllerTest.tenantAdminReadsTheHistory` (200 + jsonPath on subject/roleKey/changeKind/actor/effectiveFrom), `...historyHasNoWriteMappings` (POST/DELETE → 405), `...nonAdminReturns403`, `...withoutLoginReturns401`. Real-HTTP + DB evidence: `RoleHistoryIntegrationTest.grantAndRevocationAreRecordedWithWhoWhenAndEffectiveWindow`, `...historyRowsCannotBeUpdatedOrDeleted`, `...crossTenantSubjectIsConcealed`, `...permissionChangeDeniesTheNextProtectedRequest`.

## Coverage

Command: `rm -f apps/api/target/jacoco.exec && mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=RoleHistoryTest,JdbcRoleHistoryStoreTest,RoleHistoryServiceTest,RoleHistoryControllerTest,ActionPolicyTest,RoleHistoryIntegrationTest test jacoco:report` → `EXIT:0`, read `apps/api/target/site/jacoco/jacoco.xml` per source file (format missed/covered):

| File | Instruction | Branch | Line | Method | Class |
|---|---|---|---|---|---|
| `RoleHistory.java` | 0/43 | none | 0/5 | 0/3 | 0/1 |
| `RoleHistory$RoleChange` | 0/27 | none | 0/1 | 0/1 | 0/1 |
| `RoleHistoryStore.java` | interface (no coun
