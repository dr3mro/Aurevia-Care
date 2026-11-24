# FIX-STAFF-MAIL-PERSISTENCE · Store staff mail state and retry keys durably

PR #682, tested branch tip 51c156dbb424a78b90540ef06c08bbb443a5d833.

**Automatic repair of a failed run on main.** The integrator merges it once the fast gate passes, including changes to workflow, script or agent files.

Task: FIX-STAFF-MAIL-PERSISTENCE

Swarm worker 3. Local checks: passed. Agent result: ok.

# FIX-STAFF-MAIL-PERSISTENCE — Store staff mail state and retry keys durably

## Summary

`MailService` kept drafts, published messages, per-person folders and send
idempotency in four process-local maps. This change moves every one of those to a
durable `MailStore` port with a PostgreSQL adapter (`JdbcMailStore`). V91's
`staff_message`, `staff_mail_metadata` and `staff_mail_folder` are reused for the
message ledger, metadata and per-person folders; reserved migration V95 adds the
two gaps V91 cannot hold (a mutable versioned draft and a send retry ledger) plus
the two metadata columns needed to reconstruct the exact confirmed message.
Draft version checks, recipient confirmation, role expansion and no-blind-copy
rules stay in the domain kernel. Publication, folder placement, draft removal and
the retry claim commit in one transaction, with a deferred message FK and an
`ON CONFLICT DO NOTHING RETURNING` claim so a lost race returns the winning
message instead of a second publication.

## Affected requirements

- **MSG-02** — staff mail composition/send/folders survive a new service instance.
- **ARC-02** — tenant-scoped SQL plus forced RLS on both new tables; two-tenant HTTP isolation.
- **ARC-07** — atomic send + retry key; stale draft and confirmation still enforced; replay idempotent.

Acceptance mapping:
| Acceptance | Evidence |
|---|---|
| send and folder state survive a new service instance | `MailPersistenceIntegrationTest.sentAndFolderStateSurviveANewServiceInstance` reads through a freshly constructed store/service |
| stale draft | `staleDraftRetryAndFailedSendBehaveAtomicallyOverHttp` asserts `412 STALE_VERSION` over HTTP on PostgreSQL |
| retry | same test sends twice with `retry-key`, one message / one idempotency row |
| rollback | same test sends to an unseeded subject, DB FK fails the batch, then asserts no message row, no retry row, draft intact |
| two-tenant isolation | `tenantsAndViewersStaySeparateThroughHttp` sends in ORG A, ORG B requests see no message/inbox |
| separate viewer state | same test moves SENDER to archive; READER still sees the message in inbox; reader flag is visible to sender |

## Risk class

Cross-boundary (persistence port, migration, HTTP entry point) with tenant
isolation. Reviewed by the assigned domain specialists: `aurevia-senior-backend`,
`aurevia-senior-database`, `aurevia-senior-spring-security`,
`aurevia-software-architect`, `aurevia-test-engineer` (simulated reviewer
routing per AGENTS.md; no human participant).

## Review owner

Senior Backend with Senior Database for V95 and Senior Spring Security for the
tenant boundary; integrator runs the full Delivery gate and QA reviews evidence.

## Data changes

- New `db/migrations/V95__durable_staff_mail.sql` (`Change: expand`):
  - `staff_mail_draft` (tenant PK, facility and owner FKs, positive version check,
    RLS + FORCE RLS + `tenant_isolation` policy, grants SELECT/INSERT/UPDATE/DELETE,
    REVOKE TRUNCATE).
  - `staff_mail_idempotency` (PK `(organization_id, actor_subject, client_message_id)`,
    FK to `user_identity` and a `DEFERRABLE INITIALLY DEFERRED` FK to `staff_message`,
    RLS + FORCE RLS + policy, grants SELECT/INSERT, REVOKE UPDATE/DELETE/TRUNCATE).
  - `ALTER TABLE staff_mail_metadata ADD COLUMN resolved_recipients text[]` and
    `attachment_ids text[]`, both `NOT NULL DEFAULT '{}'` (additive, no rewrite of
    existing rows).
- Reuses V91 `staff_message`, `staff_mail_metadata`, `staff_mail_folder` and
  `staff_conversation` (`mail_thread`) unchanged. No V91 object was altered
  destructively; there is no contract migration and no forward-fix.
- Lock/volume: additive DDL on empty new tables plus two column additions with a
  constant default; no backfill. Rollback is a normal forward-fix dropping V95's
  two tables and columns after reverting the adapter.

## Authorization boundaries and audit events

- Every store statement names `organization_id` from the command and runs in the
  tenant-guarded transaction (`app.current_org` / `app.actor`), so forced RLS is a
  second boundary even with a stale context.
- Draft lookup and deletion are keyed by the authenticated `actor`; a cross-actor
  draft read yields 404. Folder placement is keyed by viewer subject, never by a
  request body. The message author is still bound by the V91 sender trigger.
- No audit event schema changed; existing staff-messaging audit coverage remains.

## Migration and rollout considerations

- V95 follows V93 with V94 unused (reserved for another worker); Flyway applies by
  version order and `check-migrations.py --require-baseline` is green.
- `db/check.sh` verified migrations, forced RLS, role separation, isolation and
  append-only behavior on synthetic data.
- The deferred idempotency FK is intentional: the claim is inserted before the
  message so it can win a concurrent race; the FK is validated at commit inside
  the same transaction, so a rolled-back send leaves no orphan claim.

## Test evidence

Entry point → assertion trace:
`POST /api/v1/staff/mail/drafts` → `MailController.create` → `MailService.createDraft`
→ `MailStore.saveDraft` → `JdbcMailStore.saveDraft` → `staff_mail_draft` (V95);
`POST /api/v1/staff/mail/drafts/{id}/send` → `MailService.send` →
`JdbcMailStore.publish` → `staff_message` + `staff_mail_metadata` +
`staff_mail_folder` + `staff_mail_idempotency`; asserted by
`MailPersistenceIntegrationTest` through MockMvc against real PostgreSQL.

Commands (all run locally on this branch, synthetic fixtures only):

- `mvn -q -B -f apps/api/pom.xml test -Dtest=MailServiceTest,MailControllerHttpTest,MailContractTest,MailInputsTest,MailRulesTest,StaffRecipientDirectoryTest -DfailIfNoTests=false` — pass (exit 0). Finals: MailServiceTest 17/0/0, MailControllerHttpTest 6/0/0, MailContractTest 5/0/0, MailInputsTest 4/0/0, MailRulesTest 3/0/0, StaffRecipientDirectoryTest 2/0/0.
- `mvn -q -B -f apps/api/pom.xml test -Dtest=MailPersistenceIntegrationTest -DfailIfNoTests=false` — pass. Final: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`.
- Combined unit+integration run before coverage: exit 0 (no `[ERROR]`).
- `mvn -q -B -f apps/api/pom.xml test -Dtest=OpenApiRouteParityTest -DfailIfNoTests=false -Pno-security` — pass. Final: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` (full Spring context wires the new `MailStore` bean).
- `python3 -I scripts/check-migrations.py --require-baseline` — `MIGRATION CHECK OK`.
- `cd db && bash check.sh` — `db: migrations, forced RLS, role separation, isolation, audit, append-only staff history and versioned config verified` (exit 0).
- `python3 -I scripts/check-names.py`, `check-wiring.py`, `check-docs.py`, `check-secrets.py` — pass (wiring emitted only pre-existing baseline "fixed" notes).
- `python3 -I -m unittest discover -s scripts/tests -q` — pass (Java Javadoc/name conventions).
- `mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check` — exit 0.
- `bash scripts/docs.sh` — exit 0; new public types/methods produced no Javadoc warnings (only pre-existing warnings in other classes).
- `git merge --no-edit origin/main` at start, after implementation and before finish — `Already up to date` each time; no merge conflicts from these merges.

Note: the full `apps/api/check.sh` (`mvn verify`) was intentionally not run locally:
the execution request forbids any command that runs every integration test / full
verify, and leaves the full gate and the bundle-level JaCoCo check to the
integrator and Delivery. `scripts/check.sh` was likewise not run because it
invokes that full verify.

## Coverage

Command: `mvn -q -B -f apps/api/pom.xml test -Dtest=MailServiceTest,MailControllerHttpTest,MailContractTest,MailInputs
