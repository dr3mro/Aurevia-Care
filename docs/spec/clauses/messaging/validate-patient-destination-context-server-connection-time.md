# MSG-01 clause 3 — validate patient, destination and context on the server

## Clause

Validate patient, destination and context on the server at connection time and every protected action.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.messaging.clauses.ValidatePatientDestinationContextServerConnectionTimeTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/messaging/clauses/ValidatePatientDestinationContextServerConnectionTimeTest.java`).
All six tests are active, pass with synthetic data only, and assert the authoritative
HTTP status and machine-readable code of every refusal through `ApiException`.
The proof boundary is the domain/service boundary: the real service is driven with
the synthetic `InMemoryMessagingStore`, a fixed `Clock` and a synthetic
`TenantContext`; no HTTP request, real identity or patient data is involved. The
neighbouring `com.aurevia.smartclinic.messaging.units.CareUnitDirectoryServiceTest`
independently covers the same guards through the same service, including invitation,
listing and revocation-conflict behaviour that this clause-proof deliberately does
not repeat.

Test methods (one positive rule proof and five refusals):

- `connectAcceptsOnlyVerifiedPatientUnitAndContext` — positive proof. A verified
  `order` relationship yields an ACTIVE connection, and the protected-action
  verifier then returns patient id, connection id and unit key derived from the
  store rather than from the caller.
- `connectRefusesUnverifiedPatient` — an unknown chart is concealed with 404
  `NOT_FOUND` and a known chart not linked to the account is refused with 403
  `FORBIDDEN`; neither creates a connection.
- `connectRefusesInactiveOrForeignUnit` — an inactive unit and a unit in another
  tenant are both concealed with 404 `NOT_FOUND`, so a directory listing cannot
  be turned into a destination without a verified relationship.
- `connectRefusesContextOrReferenceMismatch` — a malformed context fails field
  validation with 422 `INVALID_CONTEXT` and no audit fact is written, while a
  wrong context or unmatched reference fails with 422 `NO_ELIGIBLE_RELATIONSHIP`.
- `requireActiveRefusesRevokedWrongUnitOrUnknownConnection` — a protected action
  refuses an unknown connection (404 `NOT_FOUND`), a connection owned by another
  chart (404 `NOT_FOUND`), a wrong unit category (403 `FORBIDDEN`) and a revoked
  connection (403 `FORBIDDEN`).
- `requireUsableConnectionRefusesInactiveStaffMembership` — the staff protected
  action succeeds while membership is active and is refused with 403 `FORBIDDEN`
  on the very next call after that membership is deactivated, with no session
  change. Together with the patient paths above, this exercises all four
  protected-action entry points: `requireActive` (direct),
  `requireUsableConnection` (direct), and `requireActiveConnectionById` and
  `requireActiveConnection` (both reached transitively through `requireActive`).

Production classes exercised:

- `com.aurevia.smartclinic.messaging.units.CareUnitDirectoryService` — `connect`
  validates the patient (`patientExists`, `verifiedCharts`), the destination
  (`findUnit` restricted to active units in the active tenant) and the context
  (`MessagingInputs.context`, then the exact unit, context and reference match
  against an active, unconsumed, unexpired relationship). Its protected-action
  methods `requireActive`, `requireActiveConnectionById`,
  `requireActiveConnection` and `requireUsableConnection` re-derive tenant,
  membership, chart link, connection state and unit category on every call.
- `com.aurevia.smartclinic.messaging.units.MessagingInputs` — total validation of
  identifiers, context and reference before any domain or store call.
- `com.aurevia.smartclinic.messaging.units.MessagingActions` — bounded action
  matrix separating patient self-service from staff actions.
- `com.aurevia.smartclinic.messaging.units.MessagingContract` — the verified
  relationship, connection and server-derived verified-connection records.
- `com.aurevia.smartclinic.messaging.conversations.PatientConnectionVerifier`
  and `ConversationUnitResolver` — the conversation-side port and the bounded
  unit-category vocabulary; every identity returned to a protected command is
  resolved from the tenant directory, never accepted from the client.
- `com.aurevia.smartclinic.tenant.TenantContext` — the request-derived tenant
  and subject; a missing context fails closed.
- Controllers that expose the guarded commands:
  `com.aurevia.smartclinic.messaging.units.CareUnitController`
  (`GET /api/v1/care-units`, `POST` and `GET /api/v1/patient-unit-connections`,
  `POST /api/v1/patient-unit-connections/{id}/revoke`) and
  `com.aurevia.smartclinic.messaging.conversations.ConversationController`
  (protected conversation commands that reach the verifier through
  `ConversationService`).

Where the clause is implemented today (source survey result):

- `db/migrations/V60__care_units.sql` defines the durable care-unit directory,
  eligible relationships, patient-unit connections and non-sensitive messaging
  facts. Every table is tenant-owned with `organization_id` in its keys and has
  `ENABLE ROW LEVEL SECURITY` plus `FORCE ROW LEVEL SECURITY`; the connection
  business key is unique per tenant, patient, unit and reference so a retry
  cannot create a parallel conversation.
- `db/migrations/V91__staff_messaging.sql` adds the staff-to-staff messaging
  tables under the same forced row-level-security policy.
- `apps/api/src/main/java/com/aurevia/smartclinic/messaging/units/` holds the
  directory service, store interface, JDBC implementation, inputs, actions and
  contracts; the conversation-side verifier and resolver live under
  `messaging/conversations/`.
- Contract note: the care-unit and patient-unit-connection routes above are
  currently recorded in `contracts/openapi/missing-routes.txt`, not yet declared
  in `contracts/openapi.yaml`. This is pre-existing contract-parity debt tracked
  separately and is not part of this clause's validation rule.

## Gap

None.

The clause's validation rule is implemented and proven at the service boundary.
The `missing-routes.txt` entry for the care-unit routes is separately tracked
contract-parity work, not a gap in patient, destination or context validation;
adding those OpenAPI entries and regenerating the client would be a different
task and was outside this proof-only scope. This task changed no production
code, existing test, migration or contract.
