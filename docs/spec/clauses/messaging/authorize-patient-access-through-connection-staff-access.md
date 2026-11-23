# ARC-07 clause 4 — API test evidence

## Clause

Authorize patient access through the connection and staff access through membership, unit assignment and role.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.messaging.clauses.AuthorizePatientAccessThroughConnectionStaffAccessTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/messaging/clauses/AuthorizePatientAccessThroughConnectionStaffAccessTest.java`).
All fifteen tests are active (none are disabled) and pass with synthetic tenants,
subjects, charts, units and connections only.

Patient access through the connection:

- `patientOpenPersistsServerDerivedIdentitiesFromTheVerifiedConnection` — positive proof that
  `ConversationService.open` persists the identities returned by `PatientConnectionVerifier` (a
  stub that deliberately returns a different patient, connection and unit than the request). The
  stored patient, connection and unit are the server-derived values, not the request-body values.
- `patientOpenThroughAnActiveConnectionPersistsTheDirectoryDerivedIdentities` — positive proof
  through the real directory adapter: an active connection owned by the caller's verified chart
  yields a conversation bound to that connection and the directory's unit category, with a
  successful audit and no connection-denial audit.
- `patientOpenDeniesARevokedConnection` — a revoked connection is refused with HTTP 403
  `FORBIDDEN`, no conversation is written and the connection audit records `DENIED`.
- `patientOpenDeniesAnotherPatientsConnection` — a connection owned by another chart is concealed
  with HTTP 404 `NOT_FOUND`, no conversation is written and the audit records `NOT_FOUND`.
- `patientOpenDeniesAnUnknownConnection` — an unknown connection is concealed with HTTP 404
  `NOT_FOUND` and the same fail-closed audit.
- `patientOpenDeniesAConnectionForAnotherUnit` — a connection for another unit is refused with
  HTTP 403 `FORBIDDEN` because the directory-derived unit key wins over the client-named key.
- `patientOpenRejectsAnUnboundedUnitKeyBeforeAnyLookup` — a value outside the bounded unit key
  set is rejected with HTTP 422 `INVALID_UNITKEY` before any repository or audit interaction.
- `patientOpenConcealsAnotherSubjectWithoutProbingExistence` — a patient naming another subject
  receives HTTP 404 `NOT_FOUND` before any patient-existence or link lookup.

Staff access through membership, unit and role:

- `staffOpenAllowsAnEligibleRoleInItsUnit` — positive proof: an active `lab_collector` in the lab
  unit opens a conversation with a successful audit and no patient connection use.
- `staffOpenDeniesInactiveMembership` — an inactive membership is refused with HTTP 403
  `FORBIDDEN` and an audited denial before any role or patient lookup.
- `staffOpenDeniesARoleWithoutTheOpenPermission` — an active `tenant_admin` (which passes the unit
  check) is still refused with HTTP 403 `FORBIDDEN` because the action permission is missing.
- `staffOpenForAnUnassignedUnitIsDenied` — a role eligible for the lab cannot open a clinic unit
  conversation (HTTP 403 `FORBIDDEN`) and no conversation is written.
- `staffWorkItemDeniesWrongRoleAndCrossUnit` — a patient cannot take a work-item action (audited
  HTTP 403 `FORBIDDEN` for the missing permission), and an active `reception` member cannot act on
  a lab thread (HTTP 403 `FORBIDDEN` at the object-level unit check) with no write.
- `getConcealsAnotherPatientsThreadFromThePatient` — a patient cannot read another patient's
  existing thread (HTTP 404 `NOT_FOUND`).
- `getDeniesStaffOfAnIneligibleUnitOnAnExistingThread` — a staff member cannot read an existing
  thread in a unit their role does not cover (HTTP 403 `FORBIDDEN`).

Production classes exercised through the normal domain-service entry points
(`ConversationService.open`, `assign` and `get`):

- `com.aurevia.smartclinic.messaging.conversations.ConversationService` — re-derives membership and
  roles inside the protected transaction, checks the action permission, derives patient identities
  from the connection verifier for a patient, checks the recipient unit for staff, applies the
  object-level access check on existing threads and audits success and gate denials.
- `com.aurevia.smartclinic.messaging.units.CareUnitDirectoryService` — the real
  `PatientConnectionVerifier` implementation: tenant-scoped resolution of the exact connection,
  chart-ownership and active-state checks, verified-chart and active-connection checks, directory
  unit-category resolution and fail-closed connection audits.
- `com.aurevia.smartclinic.messaging.conversations.PatientConnectionVerifier` and
  `com.aurevia.smartclinic.messaging.conversations.ConversationUnitResolver` — the verifier port and
  the bounded conversion from a directory unit category to a conversation unit key.
- `com.aurevia.smartclinic.messaging.conversations.ConversationActions` and
  `com.aurevia.smartclinic.messaging.units.MessagingActions` — the bounded action matrix and the
  role-to-unit capability matrix that express unit assignment for staff.

For staff, unit assignment is expressed by the role-to-unit capability matrix in
`ConversationActions.allowsUnit` together with the recipient unit stored on the conversation;
there is no separate per-subject unit-assignment row. The tests assert both halves: an eligible
role in its unit succeeds, while the same action in another unit is refused. Controllers in
`com.aurevia.smartclinic.web` delegate these decisions to the owning service; this task did not
change production code, an existing test, the contract or a migration.

## Gap

None.

The clause is built and proven by active tests. This task added proof only.
