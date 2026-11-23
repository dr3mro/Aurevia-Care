# MSG-07 clause 6 — Preserve sender and recipient retention duties

## Clause

Preserve sender and recipient retention duties.

## Status

Partly proven

## Evidence

The clause is preserved at the domain, persistence and service layers, and those layers
are proven by active tests. It is not yet provable over HTTP: no controller route returns
the duty pair and no command creates one. The new API-evidence class records that missing
proof without leaving a failing test or changing production code.

Test class: `com.aurevia.smartclinic.messaging.clauses.PreserveSenderRecipientRetentionDutiesTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/messaging/clauses/PreserveSenderRecipientRetentionDutiesTest.java`),
a real-PostgreSQL integration test under the `synthetic-auth` profile. It migrates a fresh
`smartclinic_retention_duty_it` database from `db/migrations`, seeds two isolated tenants
with a tracked resource and directly seeded duty rows (organization A: sender 30 days,
recipient 90 days; organization B: sender 10 days, recipient 20 days), and drives the HTTP
entry points with `X-Synthetic-Subject` and `X-Active-Organization` headers. All fixtures
are synthetic.

Four complete tests are deliberately `@Disabled("GAP: ...")`, as the story requires for a
partly built clause; they assert concrete statuses and codes, never a bare call:

- `retentionDutyKeepsBothDistinctWindowsAfterALegalHold` (rule met) — reads the duty over
  HTTP, asserts `senderRetentionDays` = 30 and `recipientRetentionDays` = 90, places a
  legal hold through `POST /api/v1/messaging-governance/legal-holds`, then reads the duty
  again and asserts both windows are still 30/90.
- `anotherTenantCannotReadTheDutyAndSeesNoWindowValues` (negative) — organization B's
  `privacy_auditor` reading organization A's resource gets HTTP 404 with code `NOT_FOUND`
  and neither window value.
- `receptionCannotReadTheRetentionDuty` (negative) — a `reception` member gets HTTP 403
  with code `FORBIDDEN`, enforcing `AUDIT_READ` role scope.
- `unknownResourceHasNoReadableDuty` (negative) — an unknown resource is concealed as
  HTTP 404 `NOT_FOUND`, not answered with an empty or default pair.

None of these reads `GET /api/v1/messaging-governance/retention/{resourceId}`: that route
returns `RetentionDecision(resourceId, eligible, reason)` and carries no duty pair, so it
cannot prove this clause.

Active green tests proving the layers that do exist:

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
Tests run: 4, Failures: 0, Errors: 0, Skipped: 4
```

The four skips are exactly the four intentionally disabled GAP proofs above; there is no
unintended skip.

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
   `contracts/openapi/missing-routes.txt`). Run `npm --prefix apps/staff-web run gen:api`
   and commit the regenerated `openapi.d.ts`; `gen:api:check` must pass.
3. Enable the four disabled tests above and add a staff-web or operator surface that
   displays the two windows if the product needs them visible.

Files that would change: `MessagingGovernanceController.java`,
`MessagingGovernanceService.java`, `contracts/openapi.yaml`,
`apps/staff-web/src/api/generated/openapi.d.ts`, the new clause test, and a staff-web view
if visibility is required. No new Flyway migration is needed: V62 already provides the
table, its RLS/FORCE RLS, grants and no-DELETE protection. Count: **one backend/contract
task plus one optional staff-web task**. This evidence task changed only a test class and
this note; no production code, existing test, migration or contract was edited.
