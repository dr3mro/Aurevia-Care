# LAB-01 clause 4 — API test evidence

## Clause

Rejected specimens retain reason and notification.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.lab.clauses.RejectedSpecimensRetainReasonNotificationTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/lab/clauses/RejectedSpecimensRetainReasonNotificationTest.java`).
All eight tests are active (none disabled) and use synthetic data on a dedicated PostgreSQL
database migrated from `db/migrations` with forced row-level security. They drive the real HTTP
entry point with the synthetic-auth profile, the same `RestClient` + `tools.jackson` + Flyway
fixtures as the neighbouring `com.aurevia.smartclinic.it.lab.AccessionListIntegrationTest` and
`com.aurevia.smartclinic.scheduling.clauses.RescheduleAtomicallyRetainHistoryTest`. Prior specimen
state is seeded directly so each negative isolates one refusal cause; the rejection itself and the
read-back always go through HTTP.

Verification command: `SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=RejectedSpecimensRetainReasonNotificationTest -Dgroups=integration test`
— final Surefire line: `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0` (no test disabled or skipped).

- `rejectRetainsReasonAndNotification` — positive proof. A received specimen is rejected through
  `POST /api/v1/lab/accessions/{id}/reject` by a `lab_verifier` with a reason, a notification and
  the version read by the caller. The response carries `state=rejected`, the retained
  `rejectionReason` and `rejectionNotification` and the incremented version. `GET /api/v1/lab/accessions/{id}`
  returns the same retained fields and an append-only history: the seeded `received` event is
  unchanged and a new `rejected` event carries the reason, `fromState=received`,
  `toState=rejected` and `accessionVersion=2`. Direct database reads under the seeded tenant
  confirm the persisted row (`state=rejected`, version 2, both rejection columns) and the two
  durable history rows, so the reason and notification survive the commit.
- `labCollectorCannotRejectSpecimen` — negative proof (wrong role). A `lab_collector` with active
  membership is refused with HTTP 403 `FORBIDDEN` and `message=missing laboratory permission`,
  isolating the role branch from the no-membership branch.
- `peerTenantCannotRejectSpecimen` — negative proof (another tenant). A `lab_verifier` of the peer
  organization holds the same reject permission but cannot see the row under forced RLS; the
  request is refused with HTTP 404 `NOT_FOUND`.
- `staleVersionCannotRejectSpecimen` — negative proof (stale version). An outdated
  `expectedVersion` is refused with HTTP 412 `STALE_VERSION`.
- `blankReasonIsRejected` — negative proof (missing input). An empty reason is refused with HTTP
  422 `INVALID_REASON`.
- `blankNotificationIsRejected` — negative proof (missing input). An empty notification is refused
  with HTTP 422 `INVALID_NOTIFICATION`.
- `accessionedSpecimenCannotBeRejected` — negative proof (forbidden source state). With otherwise
  valid input, an `accessioned` specimen cannot move straight to `rejected` and is refused with
  HTTP 409 `INVALID_TRANSITION`.
- `nonPositiveVersionIsRejected` — negative proof (invalid input). `expectedVersion=0` is refused
  with HTTP 422 `INVALID_VERSION`.

Every negative asserts, under the owning tenant's forced-RLS scope, that the specimen row is still
visible in its original state at version 1 with both rejection columns null and that no `rejected`
event was appended. The positive test asserts the row is visible before checking its rejected
values, so a mis-bound database scope cannot make the assertions pass vacuously.

Production classes exercised through the normal HTTP entry point:

- `com.aurevia.smartclinic.lab.accession.LabAccessionController` — thin adapter;
  `POST /api/v1/lab/accessions/{id}/reject` binds `RejectRequest` and delegates,
  `GET /api/v1/lab/accessions/{id}` returns the accession with its history.
- `com.aurevia.smartclinic.lab.accession.LabAccessionService` — `reject(String, RejectRequest)`
  validates reason, notification and version, then `transition(...)` authorizes `SPECIMEN_REJECT`,
  checks version and source state, carries the prior specimen fields forward, persists both
  rejection values, appends the immutable `rejected` event with the reason and audits the commit.
- `com.aurevia.smartclinic.lab.accession.LabAccessionRepository` — `findById`, `update`
  (version compare-and-set that writes `rejection_reason` and `rejection_notification`) and
  `appendEvent` / `history` for the append-only ledger.
- `com.aurevia.smartclinic.lab.accession.LabInputs` — bounded validation that yields
  `INVALID_REASON`, `INVALID_NOTIFICATION` and `INVALID_VERSION`.
- `com.aurevia.smartclinic.lab.accession.LabActionPolicy` — `SPECIMEN_REJECT` is allowed for
  `lab_verifier`, not for the `lab_collector` used by the wrong-role test.
- `com.aurevia.smartclinic.lab.accession.AccessionState` — the state machine permits only
  `collected | received -> rejected`.
- `com.aurevia.smartclinic.lab.accession.LabAccession`, `AccessionView`, `AccessionEvent` and
  `AccessionCommands.RejectRequest` — the stored projection, read view, history entry and command.
- `com.aurevia.smartclinic.api.ApiErrorHandler` — the uniform error body carrying `$.code` and
  `$.message` used by the negative assertions.
- `db/migrations/V26__laboratory_accession.sql` — the `lab_accession` and `lab_accession_event`
  tables with forced RLS, the CHECK that requires both rejection columns when `state='rejected'`,
  and the update guard that permits only the LAB-01 edges.

Neighbouring implementations observed in the source survey (not changed by this task): the
unit-level `LabAccessionServiceTest` already covers the service branches of rejection; the broader
`SpecimenAccessionIsolationTest` rejects a specimen as part of a full LAB-01 journey; and a separate
labflow domain exposes `/api/v1/lab/specimens/{id}/reject` with a different payload and is proven by
`SpecimenRejectionIntegrationTest`. This task adds a clause-focused HTTP proof on the accession
entry point and changes no production code.

## Gap

None.

The LAB-01 clause-4 rule is built and proven by active tests. This task added proof only; it did not
change production code, an existing test, the OpenAPI contract or a migration. Contract publication
of the reject route is separate debt tracked by `FIX-OPENAPI-01..04`, outside this test-only scope.
