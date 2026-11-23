# MSG-07 clause 6 — Preserve sender and recipient retention duties

## Clause

Preserve sender and recipient retention duties.

## Status

Partly proven

## Evidence

The clause is preserved at the domain, persistence and service layers, and those layers
are now proven by active tests. It is not yet provable over HTTP: no controller route
returns the duty pair and no command creates one. The API-evidence class therefore drives
the authoritative application entry point that does exist — the real
`MessagingGovernanceService` — against a real PostgreSQL database under forced row-level
security, and leaves the remaining HTTP exposure as the documented gap.

Test class: `com.aurevia.smartclinic.messaging.clauses.PreserveSenderRecipientRetentionDutiesTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/messaging/clauses/PreserveSenderRecipientRetentionDutiesTest.java`),
a real-PostgreSQL integration test under the `synthetic-auth` profile. It migrates a fresh
`smartclinic_retention_duty_it` database from `db/migrations`, seeds two isolated tenants
with a tracked resource and directly seeded duty rows (organization A: sender 30 days,
recipient 90 days; organization B: sender 10 days, recipient 20 days), binds a synthetic
`TenantContext` for each proof and calls the injected service. All fixtures are synthetic.
There are no disabled or skipped tests.

Four active tests prove the clause and its refusals; each asserts a concrete value, status
or code:

- `retentionDutyKeepsBothDistinctWindowsAfterALegalHold` (rule met) — reads the duty as
  auditor A, asserts `senderRetentionDays` = 30 and `recipientRetentionDays` = 90, places a
  legal hold through `MessagingGovernanceService.placeHold` and asserts the returned hold is
  active on that resource in that tenant (so the premise is proven, not assumed), then
  re-reads and asserts both windows are still 30/90 and releases the hold for rerun safety.
- `anotherTenantCannotReadTheDutyAndSeesNoWindowValues` (negative) — organization B's
  `privacy_auditor` reading organization A's resource gets a 404 `NOT_FOUND` `ApiException`
  with no duty fields or window values in the refusal, while the same duty is readable
  inside organization A.
- `receptionCannotReadTheRetentionDuty` (negative) — a `reception` member gets a 403
  `FORBIDDEN` `ApiException`, enforcing `AUDIT_READ` role scope.
- `unknownResourceHasNoReadableDuty` (negative) — an unknown resource is concealed as a
  404 `NOT_FOUND` `ApiException`, not answered with an empty or default pair.

None of these reads `GET /api/v1/messaging-governance/retention/{resourceId}`: that route
returns `RetentionDecision(resourceId, eligible, reason)` and carries no duty pair, so it
cannot prove this clause.

Other active green tests proving the layers that do exist:

- `com.aurevia.smartclinic.messaging.governance.MessagingGovernanceServiceTest.retentionDutiesPreserveSenderAndRecipientWindows`
  — stores a 30/90 pair, reads it back with both fields and its resource/organization
  intact, gets 404 for a missing resource, then places a legal hold and confirms the
  recipient window is still 90. Executed locally: `Tests run: 30, Failures: 0, Errors: 0,
  Skipped: 0` for the whole `MessagingGovernanceServiceTest` class.
- `com.aurevia.smartclinic.messaging.governance.it.JdbcMessagingGovernanceStoreIntegrationTest.resourcesAndRetentionDutiesAreTenantScoped`
  — under forced row-level security, organization A stores `(10, 20)` and organization B
  stores `(30, 40)` on the same resource id; each reads back only its own pair and A gets
  an empty result for an unrecorded resource.

Production types and data exercised by the preserved behavior:

- `com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.RetentionDuty` —
  the immutable sender/recipient pair; its contract states a hold on one party never
  waives the other's duty.
- `com.aurevia.smartclinic.messaging.governance.MessagingGovernanceStore` —
  `putRetentionDuty` / `retentionDuty`, with `JdbcMessagingGovernanceStore` (tenant-filtered
  upsert and read) and a test-only in-memory fake.
- `com.aurevia.smartclinic.messaging.governance.MessagingGovernanceService.retentionDuty` —
  UUID validation, `requireContext`, `AUDIT_READ` authorization and 404 `NOT_FOUND`
  concealment. `placeHold` never reads or mutates the duty pair, which is why both windows
  survive a hold.
- `com.aurevia.smartclinic.messaging.governance.MessagingGovernanceController` — the thin
  HTTP adapter; it has no duty route today (see Gap).
- `db/migrations/V62__messaging_governance.sql` — `messaging_retention_duty`, with RLS and
  FORCE RLS, a tenant policy, `GRANT SELECT, INSERT, UPDATE` and explicit
  `REVOKE DELETE, TRUNCATE`. No DELETE path exists, so a recorded duty cannot be removed.

Verification command and result (tool evidence):

```
SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security \
  -Dtest=PreserveSenderRecipientRetentionDutiesTest test
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
```

All four tests ran; there is no unintended skip and no disabled test.

## Gap

The duty pair is durable and distinct in domain, store, service and migration, but it is
not reachable over HTTP. `MessagingGovernanceController` has no route that returns a
`RetentionDuty`, and `MessagingGovernanceService` has no command that creates or updates
one; the only existing read route, `GET /api/v1/messaging-governance/retention/{resourceId}`,
returns an eligibility decision, not the window pair. Duties can only be written today by
calling `MessagingGovernanceStore.putRetentionDuty` from an authorized setup path or a test.

Closing the gap needs one cross-boundary backend/contract task (plus independent review):

1. Add a thin read route `GET /api/v1/messaging-governance/retention-duty/{resourceId}` to
   `MessagingGovernanceController`, delegating to the existing
   `MessagingGovernanceService.retentionDuty(resourceId)`. A companion create/update command
   (for example `POST /api/v1/messaging-governance/retention-duties`) needs an authorized
   service command there too, so real workflows can record the two windows.
2. Add both operations to `contracts/openapi.yaml` with the required contract rules: a tag
   on every operation, an `Idempotency-Key` header on the POST, `cursor`, `limit` and
   `nextCursor` if any list GET is added, and a route-parity entry (do not add the routes to
   `contracts/openapi.yaml`). Run `npm --prefix apps/staff-web run gen:api`
   and commit the regenerated `openapi.d.ts`; `gen:api:check` must pass.
3. When the route exists, add HTTP-level proofs of the same four behaviors; the four
   active service-level proofs above remain valid. Add a staff-web or operator surface that
   displays the two windows if the product needs them visible.

Files that would change: `MessagingGovernanceController.java`,
`MessagingGovernanceService.java`, `contracts/openapi.yaml`,
`apps/staff-web/src/api/generated/openapi.d.ts`, and a staff-web view if visibility is
required. No new Flyway migration is needed: V62 already provides the table, its
RLS/FORCE RLS, grants and no-DELETE protection. Count: **one backend/contract task plus one
optional staff-web task**. This evidence task changed only a test class and this note; no
production code, existing test, migration or contract was edited.
