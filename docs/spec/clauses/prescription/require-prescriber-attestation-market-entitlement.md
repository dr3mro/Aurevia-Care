# RX-02 clause 1 — API test evidence

## Clause

Require prescriber attestation and market entitlement.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.prescription.clauses.RequirePrescriberAttestationMarketEntitlementTest`
(path: `apps/api/src/test/java/com/aurevia/prescription/clauses/RequirePrescriberAttestationMarketEntitlementTest.java`).
All twelve tests are active (none are disabled) and pass with synthetic data only. The real
`RxController` and `RxService` are driven through MockMvc over a mocked `RxRepository` and a
synchronous transaction template, so the HTTP entry point and the domain rule are both
exercised without a new database. This class deliberately proves clause 1 alone; the
printable-channel and recorded-channel clauses have their own evidence in C02 and in
`LocalPrescriptionPrintTest`, `RxPrintableServiceTest` and `RxPrintableHttpTest`.

- `entitlesThePrescriberAndRecordsTheAttestation` — positive proof. The owning clinician
  attests a draft at version 1 with an enabled entitlement; the route returns HTTP 200 with
  state `attested` and version 2, `RxRepository.attest(ID, 1)` is called, and the appended
  event is `ATTESTED` at version 2 with outcome `market=US` and the synthetic note.
- `refusesAttestationFromAnotherPrescriber` — an active clinician with the clinician role
  but who is not the recorded prescriber is refused with HTTP 403 `FORBIDDEN`. The row is
  loaded, so the denial can only come from the ownership check, and no event is written.
- `refusesAttestationByPharmacyStaff` — an active member with the `pharmacy_staff` role is
  refused with HTTP 403 `FORBIDDEN` by the action gate before any row is read; a DENIED
  audit fact is appended.
- `refusesAttestationWithoutActiveMembership` — a caller without an active membership is
  denied with HTTP 403 `FORBIDDEN` before any row is read and records a DENIED audit fact.
- `concealsAnInvisiblePrescriptionFromAnotherTenant` — a row invisible in the active
  organization is concealed as HTTP 404 `NOT_FOUND`; no attestation or event is written.
  With a mocked repository the empty `loadForUpdate` result models the effect of forced
  RLS; the real-database isolation proof is
  `RxTransmissionFlowTest.tenantIsolationConcealsIdenticalLocalIdentifiers`.
- `refusesStaleVersion` — a version of 9 against a row at version 1 is refused with HTTP
  412 `STALE_VERSION`; the compare-and-set is not attempted.
- `refusesAMissingIfMatch` — a request without `If-Match` is refused in the controller with
  HTTP 428 `PRECONDITION_REQUIRED` before the service or repository runs.
- `refusesAttestationOfAnAlreadyAttestedPrescription` — a second attestation of an
  `attested` row is refused with HTTP 409 `INVALID_TRANSITION`, so attestation cannot be
  applied outside the draft state.
- `refusesAMalformedIfMatch` — a non-numeric version is refused with HTTP 422
  `INVALID_IF_MATCH` at the boundary, with no repository interaction.
- `refusesWhenNoMarketPolicyApproved` — an absent `rx02.market` policy yields HTTP 422
  `NO_MARKET_POLICY` and no attestation event.
- `refusesWhenNoEntitlementSetting` — an absent `rx02.entitlement.<subject>` setting yields
  HTTP 422 `NO_ENTITLEMENT` and no attestation event.
- `refusesWhenEntitlementIsDisabled` — an entitlement setting of `disabled` yields HTTP 422
  `NO_ENTITLEMENT` and no attestation event, so a revocation fails closed.

Entry-point-to-test trace:

`POST /api/v1/prescriptions/{id}/attest` → `RxController.attest` → `IfMatch.parse` →
`RxService.attest` → `lockedByPrescriber` → `requirePrescriber` / `requireVersion` /
`requireState` → `requireEntitlement` → `RxRepository.attest` and `appendEvent(ATTESTED)`.

Production classes exercised (all pre-existing, none changed by this task):

- `apps/api/src/main/java/com/aurevia/prescription/rx/RxController.java` — thin
  HTTP adapter; parses `If-Match` at the boundary and delegates every decision to the service.
- `apps/api/src/main/java/com/aurevia/prescription/rx/RxService.java` — `attest`,
  `lockedByPrescriber`, `requirePrescriber`, `requireVersion`, `requireState`,
  `requireEntitlement`, `authorize` and `execute`; entitlement is re-derived inside the
  tenant-guarded transaction so a revocation fails closed.
- `apps/api/src/main/java/com/aurevia/prescription/rx/RxActions.java` — binds
  `RX_ATTEST` to the clinician role.
- `apps/api/src/main/java/com/aurevia/prescription/rx/RxContract.java` —
  `State`, `EventType.ATTESTED` and `AttestRequest`.
- `apps/api/src/main/java/com/aurevia/prescription/rx/RxRepository.java` —
  `loadForUpdate`, `approvedSetting`, `attest`, `appendEvent` and `find`.
- `apps/api/src/main/java/com/aurevia/api/IfMatch.java` and
  `apps/api/src/main/java/com/aurevia/api/ApiErrorHandler.java` — precondition
  parsing and the uniform error body.
- `apps/api/src/main/java/com/aurevia/web/TenantContextInterceptor.java` — derives
  tenant identity from the synthetic headers; the request body cannot select a tenant.
- `db/migrations/V44__prescription_transmission_transmission.sql` — the durable tables, RLS with
  FORCE RLS and the immutable-event/state guards behind the mocked repository.

## Gap

None.

The clause is built and proven by active tests at the HTTP entry point and in the domain
service. This task added proof only; it did not change production code, an existing test,
the OpenAPI contract or a migration. Publishing the RX-02 routes in
`contracts/openapi.yaml` is separate, baselined route-parity debt and is not a gap in this
clause's rule.
