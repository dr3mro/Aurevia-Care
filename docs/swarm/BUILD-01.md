# BUILD-01 · Staff invitations: single-use expiring invitation, accept and revoke

PR #305, tested branch tip cb935974c89eedd08f6f105e70eb0b53e1f6cb42.

Task: BUILD-01

Swarm worker 6. Local checks: passed. Agent result: ok.

# BUILD-01 — Staff invitations: single-use expiring invitation, accept and revoke

## Summary

Implemented IAM-02 staff invitations inside the declared scope. A tenant administrator issues an invitation carrying bounded roles, a facility and an expiry; only the SHA-256 hash of a cryptographically random one-time token is persisted, and the raw token is returned once in the issue response. The invitee accepts inside an authenticated tenant context; one database function atomically consumes the token, creates the membership, appends one role assignment per invited role, and records the inviter as grantor with an effective date. Pending invitations can be listed and unused ones revoked. All unusable tokens (expired, reused, revoked, unknown, wrong tenant) return the same 409 outcome.

Delivered in `apps/api/src/main/java/com/aurevia/staff` (8 new classes, one one-line visibility change to reuse the canonical role set), matching tests under `apps/api/src/test/java/com/aurevia/staff`, and `db/migrations/V70__build_01.sql`. No dependency, no file outside scope, no tracker file, no push/tag/gh.

## Affected requirements

IAM-02 (primary). Supporting: ARC-01 (opaque tenant-owned keys), ARC-02 (forced RLS, fail-closed tenant context), OPS-02 (append-only audit purpose/outcome on every command), IAM-01 (authorization re-derived from the authoritative membership fold on every administration command).

## Risk class

Safety-critical (identity/permission lifecycle). Static specialist review from `orevia-senior-backend` and `orevia-senior-database` (simulated reviewers; their findings were applied: DB-side admin guard on issue/revoke, column-scoped UPDATE, composite FKs, same-outcome concealment, secure grantor GUC, distinct-role trigger, generic failure for unusable tokens). No clinical, human or patient-participant evidence is claimed.

## Review owner

Senior Java and Spring Boot / Senior Spring Security and OIDC / Senior PostgreSQL, SQL and Flyway (advisory reports received and applied); independent QA and Product Owner acceptance remain with the integrator.

## Data changes

New migration `V70__build_01.sql` (reserved version, additive, `Change: expand`), one new table `public.staff_invitation` and four functions:
- `require_staff_invitation_admin()` — BEFORE INSERT guard requiring an active `tenant_admin` in the selected organization; derives `invited_by`/`created_by` from `app.actor`.
- `require_distinct_invitation_roles()` — BEFORE INSERT guard; PostgreSQL forbids a subquery in a CHECK.
- `revoke_staff_invitation(uuid)` — admin-guarded conditional timestamp update.
- `set_granted_by_from_actor()` — CREATE OR REPLACE of the V4 function so a dedicated transaction-local `app.grantor_subject` GUC can name the inviter without contaminating the actor used by the V3 audit trigger; existing grant paths fall back to `app.actor` unchanged.
- `accept_staff_invitation(text)` — SECURITY INVOKER, tenant-scoped by forced RLS; one conditional `UPDATE` consumes the token, then identity, membership and role assignments are inserted; a dedicated grantor GUC makes the inviter the grantor and `now()` the effective date; every unusable token returns NULL and no partial state survives.

RLS `ENABLE` + `FORCE` + `USING/WITH CHECK (organization_id = public.app_org())`, composite FKs to `organization` and `facility`, `REVOKE ALL` then scoped `GRANT SELECT, INSERT` and `GRANT UPDATE (used_at, revoked_at)` (the column grant must follow the table-level revoke). No existing migration edited. No backfill; table is empty at deploy. No lock/volume impact beyond DDL on one empty table.

## Authorization boundaries and audit events

Administration (`create`, `listPending`, `revoke`) re-derives membership and roles from the authoritative store and applies the existing `STAFF_INVITE` / `STAFF_READ` policy actions (tenant_admin only); no new action constant was needed. Acceptance is authorized by the one-time token plus the active tenant context from `X-Active-Organization` and an authenticated `X-Synthetic-Subject` (same model as IAM-05 patient invitation redemption); it is not an unauthenticated token-only endpoint. DB triggers independently enforce the admin guard on issue/revoke.

Audit actions appended in the same guarded transaction: `STAFF_INVITATION_CREATE`, `STAFF_INVITATION_LIST`, `STAFF_INVITATION_REVOKE`, `STAFF_INVITATION_ACCEPT` (purpose `STAFF_ADMIN`, scope `STAFF`, outcomes `SUCCESS`/`DENIED`/`CONFLICT`/`NOT_FOUND`). The raw token is never placed in an audit resource id, log, list projection or URL.

## Migration and rollout considerations

Additive expand migration; safe to deploy before the code that uses it. `V70__build_01.sql` is the path mandated by the task scope; a business-domain filename would be preferred by CODING_STYLE.md but changing it is outside scope. The V4 `set_granted_by_from_actor` replacement preserves the original behavior for every existing grant path (grantor = `app.actor` unless `app.grantor_subject` is explicitly set). Rollback is a forward contract for a later story; no destructive DDL is used. `scripts/check-migrations.py --require-baseline` and `db/check.sh` both pass.

## Test evidence

Commands (all executed locally; tool evidence, synthetic fixtures only):

1. `mvn -s /tmp/maven-settings-gcs.xml -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest='StaffInvitationServiceTest,JdbcStaffInvitationStoreTest,StaffInvitationControllerTest,StaffServiceTest,StaffListServiceTest,StaffListControllerTest' test` → exit 0; Surefire reports: 18 + 5 + 9 + 27 + 11 + 5 tests, 0 failures, 0 errors.
2. `mvn -s /tmp/maven-settings-gcs.xml -q -B -f apps/api/pom.xml -Pno-security,fast test` → exit 0 (whole fast unit tier, no regressions).
3. `mvn -s /tmp/maven-settings-gcs.xml -q -B -f apps/api/pom.xml -Pno-security -Dtest=StaffInvitationIntegrationTest test` → exit 0; report: `StaffInvitationIntegrationTest tests=3 errors=0 failures=0` against real PostgreSQL 17 with Flyway V1–V70.
4. `mvn -s /tmp/maven-settings-gcs.xml -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check` → exit 0 (no findings after freezing `CreateInvitationRequest.roles`).
5. `python3 -I scripts/check-migrations.py --require-baseline` → exit 0. `python3 -I scripts/check-names.py`, `check-wiring.py`, `check-docs.py`, `check-secrets.py` → exit 0. `bash -n` + `shellcheck` on all scripts → pass. `bash scripts/frontend-check.sh` → exit 0. `(cd db && bash check.sh)` → exit 0 (`db: migrations, forced RLS, role separation, isolation, audit, append-only staff history and versioned config verified`).
6. `python3 -I -m unittest discover -s scripts/tests -q` → `Ran 436 tests`, 3 failures, all in `test_check_pack.py` and all the known cloud-worker `opencode.json` artifact (see Findings); 433 unrelated tests pass. `python3 -I scripts/check-pack.py` passes when HEAD's `opencode.json` is temporarily restored (`Delivery tooling validated: 33 agents, 33 skills`).
7. `bash scripts/check.sh` stopped at `check-pack: opencode.json is stale: run scripts/gen-opencode.py` — the same environmental skip-worktree artifact, not a change defect. The remaining root-gate steps were run individually above with exit 0. The full `mvn verify` was intentionally not run in this worker (integrator/Delivery own it).

A Maven mirror (`https://maven-central.storage-download.googleapis.com/maven2/`) was required via `-s /tmp/maven-settings-gcs.xml`; Central returned HTTP 429 for the surefire provider on this shared runner.

Entry point → test trace:
- `POST /api/v1/staff-invitations` → `StaffInvitationControllerTest.createReturns201WithALocationHeader`, `createForbiddenMapsTo403`; HTTP integration `createListAcceptRecordsGrantorAndEffectiveDate`.
- `GET /api/v1/staff-invitations/pending` → `StaffInvitationControllerTest.listPendingReturns200`; HTTP integration `createListAcceptRecordsGranto
