# MSG-05 clause 2 — API test evidence

## Clause

Bind acknowledgment to the exact instruction revision.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.messaging.clauses.BindAcknowledgmentExactInstructionRevisionTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/messaging/clauses/BindAcknowledgmentExactInstructionRevisionTest.java`).
All six tests are active (none are disabled) and use synthetic data. They drive the real HTTP entry
point with the synthetic-auth profile, the same wire-up, fixtures and style as the neighbouring
`com.aurevia.smartclinic.messaging.acknowledgment.InstructionFactControllerHttpTest`.

- `acknowledgingTheLatestRevisionRecordsOneReceiptBoundToThatRevision` — positive proof. Revisions
  1 and 2 are published with acknowledgment requested; the patient acknowledges revision 2 through
  `POST /api/v1/instructions/acknowledge` and receives 200 with `revision=2`, `scope=RECEIPT` and
  `actor=PATIENT_USER`. The fact timeline holds exactly one receipt, bound to revision 2, with no
  receipt for revision 1.
- `acknowledgingASupersededRevisionIsRefusedAsStale` — negative proof (stale revision). With
  revisions 1 and 2 published, acknowledging revision 1 with a fresh client key is refused with
  HTTP 409 `STALE_REVISION`; the timeline gains no receipt for either revision.
- `aStaffRoleCannotAcknowledgeOnBehalfOfThePatient` — negative proof (wrong role). A unit staff
  member with active membership posts an acknowledgment and is refused with HTTP 403 `FORBIDDEN`;
  the timeline gains no receipt.
- `aPatientCannotAcknowledgeAnotherTenantsInstruction` — negative proof (another tenant). A patient
  with active membership in a peer organization uses the same local instruction identifier; the
  request is concealed with HTTP 404 `NOT_FOUND`. Acknowledging under the owning organization with
  a fresh client key then succeeds, proving the refusal came from tenant isolation and not from a
  malformed fixture.
- `acknowledgingARevisionThatDidNotRequestItIsRefused` — negative proof (forbidden state). A
  revision published with `requiresAcknowledgment=false` cannot be acknowledged: HTTP 422
  `ACKNOWLEDGMENT_NOT_REQUESTED`, with no receipt in the timeline.
- `reusingAnAcknowledgmentClientKeyForAnotherRevisionConflicts` — negative proof (idempotency
  boundary). The same client key first acknowledges revision 1, then revision 2; the second call is
  refused with HTTP 409 `IDEMPOTENCY_CONFLICT`, and the only receipt recorded is the one bound to
  revision 1.

Classes and contracts traced from the normal HTTP entry point (this proof wires the test-only
`InMemoryInstructionFactStore`; persistence and forced row-level security are proven by the existing
JDBC integration tests, not by this document's test class):

- `com.aurevia.smartclinic.messaging.acknowledgment.InstructionFactController` — thin adapter;
  `POST /api/v1/instructions/acknowledge` binds the command and delegates to the service.
- `com.aurevia.smartclinic.messaging.acknowledgment.InstructionFactService` — `acknowledge` resolves
  the exact revision through `publishedInstruction`, re-checks patient membership, verified chart
  and active connection through `requirePatientAccess`, enforces the `requiresAcknowledgment`
  guard, refuses a newer latest revision with `STALE_REVISION`, and keys the idempotency digest on
  `id + "|" + revision`.
- `com.aurevia.smartclinic.messaging.acknowledgment.InstructionFactActions` — `ACKNOWLEDGE` is a
  patient-only action.
- `com.aurevia.smartclinic.messaging.acknowledgment.InstructionFacts` — `AcknowledgeCommand`,
  `Instruction` and the receipt-only `Acknowledgment` carrying the bound revision and `RECEIPT`
  scope.
- `com.aurevia.smartclinic.messaging.acknowledgment.InstructionFactStore` — the organization-keyed
  store contract exercised here through the test-only `InMemoryInstructionFactStore`: revision
  lookup, latest-revision lookup, publication check, per-subject idempotency digest and append-only
  acknowledgment. Its production implementation `JdbcInstructionFactStore` is exercised by the
  existing integration and acceptance tests.
- `com.aurevia.smartclinic.api.ApiErrorHandler` and `com.aurevia.smartclinic.web.TenantContextInterceptor`
  — the uniform error contract (`$.code`) and the tenant context derived from the authenticated
  request.
- `db/migrations/V63__instruction_facts.sql` — the production tenant-owned fact tables with forced
  row-level security and keys on `(organization_id, id, revision)`. Persistence and isolation are
  proven by the existing JDBC integration tests; this proof does not touch a database.

Existing proof observed in the source survey and not changed by this task: the unit-level
`InstructionFactServiceTest`, the HTTP-level `InstructionFactControllerHttpTest` and the patient
acceptance test `PatientInstructionAcceptanceTest` already exercise stale and bound-revision
acknowledgments. This task adds a clause-focused HTTP proof of the exact-revision binding and its
tenant, role, state and idempotency refusal boundaries.

The route `POST /api/v1/instructions/acknowledge` is currently listed in
`contracts/openapi/missing-routes.txt`. That is pre-existing contract-documentation debt, out of
scope for this proof-only change, and does not affect the implementation status of the clause.

## Gap

None.

The MSG-05 clause is built and proven by active tests. This task added proof only; it did not change
production code, an existing test, the OpenAPI contract or a migration.
