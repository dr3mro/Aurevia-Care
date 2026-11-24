## Clause

Role Clinician: allowed workflow: Document and sign, order, prescribe where authorized, reconcile and release; scope constraint: Care relationship, privileges and facility.

## Status

Partly proven

## Evidence

Test class `RoleClinicianTest`
(`apps/api/src/test/java/com/aurevia/authz/clauses/RoleClinicianTest.java`),
17 active tests, 0 skipped. It changes no production file, migration, contract or
existing test. All fixtures are synthetic; tenant and subject arrive through
`X-Active-Organization` and `X-Synthetic-Subject` headers on the prescription
MockMvc path, never from a request body.

Allowed workflow — built and proven at the real decision:

- `clinicianMayDocumentAndSign` — `ClinicalNoteService.sign` with fresh `clinician`
  roles appends a SIGNED revision at version 2 with the signer attestation and a
  `SUCCESS` audit. `receptionCannotSign` proves the same entry point refuses a
  wrong role with 403 and a `DENIED` audit before any note row is locked or
  written.
- `clinicianMayPlaceAnOrder` — `OrderPlacementService.saveDraft` then `place`
  succeed with the ordering privilege, recording the acting subject and tenant.
  `tenantAdminCannotPlaceOrder` proves `requireOrderingPrivilege` refuses an
  active tenant admin with 403; `clinicianOfAnotherTenantIsDenied` proves the
  tenant-key isolation with 404.
- `clinicianMayPrescribeWithPrivilege` — `RxActions` and `PrescriptionActions`
  permit the clinician prescribing verbs and withhold pharmacy evidence.
  `clinicianMayAttestAPrescriptionWithAnEnabledEntitlement` drives the real
  `RxController` to `attested` with an `ATTEST/SUCCESS` audit.
  `receptionCannotPrescribe` is the wrong-role 403 at the role gate (before any
  row read); `careRelationshipLimitsPrescribingToThePrescriber` is the distinct
  ownership 403 after the row is read;
  `prescribingRequiresAnEnabledMarketEntitlement` is the 422 privilege refusal.
- `clinicianMayAuthorizeARelease` — `ReleaseService.authorize` with `clinician`
  roles emits an AUTHORIZED release, an authorization event, a patient
  notification and a `SUCCESS` audit; `lab_verifier` is withheld from release
  authorization (it verifies lab results under LAB-02).
  `receptionCannotAuthorizeARelease` proves the 403 and `DENIED` audit.

Failure paths asserted: `staleDraftVersionOnSignIsRefused` (412 `STALE_VERSION`),
`signRefusesAWrongPatientContext` (422 `INVALID_PATIENT_CONTEXT`), and
`noteWriteDeniesInactiveMembership` (403 "no active membership") cover CLN-05
preconditions and IAM-01's membership branch. Existing coverage is cross-referenced
rather than duplicated here: `ClinicalNoteServiceTest`, `OrderPlacementServiceTest`,
`RequirePrescriberAttestationMarketEntitlementTest`, `ReleasePolicyTest`,
`RxContractTest`, `ResultActionPolicyReconciliationTest`,
`RxTransmissionFlowTest` (real forced-RLS concealment) and
`FacilityScopeFurtherNarrowAccessTest` (worklist/intake facility narrowing).
Active-membership denials in the release and prescription paths are proven in
`ReleaseServiceTest` and `RequirePrescriberAttestationMarketEntitlementTest`.

Production classes exercised: `clinical.notes.ClinicalNoteService`,
`orderentry.place.OrderPlacementService`, `prescription.rx.RxController` and
`RxService`, `prescription.rx.RxActions`, `prescription.PrescriptionActions`,
`exchange.release.ReleaseService` and `ReleaseActionPolicy`, and
`exchange.result.ResultActionPolicy`.

Scope bounding: the two ACTIVE absence-pinning tests
`reconcileIsCurrentlyDeniedToClinicianGap` and
`facilityScopeIsNotConsultedByClinicalOrderingGap` are gap markers, not proofs of
the clause. They assert today's absence so the pull request stays green and will
fail on purpose when the missing behaviour is built. Reconciliation resolution
and facility narrowing of clinical verbs are outside the proven set.

## Gap

Three parts of this clause are not built. Fixing them requires production code,
which this test-only task is not allowed to change.

1. **Reconcile.** `exchange.result.ResultActionPolicy` excludes `clinician` from
   `RECONCILIATION_READ` and `RECONCILIATION_RESOLVE`; only result read and review
   acknowledgement are permitted. Files to change:
   `apps/api/src/main/java/com/aurevia/exchange/result/ResultActionPolicy.java`
   (grant the role), the owning `ResultRoutingService`/`ReconciliationService`
   tests and the OpenAPI entry for the resolution route. The complete would-prove
   test for the enabling task is:

   ```java
   assertTrue(policy.allows(ResultActionPolicy.RECONCILIATION_READ, "clinician"));
   assertTrue(policy.allows(ResultActionPolicy.RECONCILIATION_RESOLVE, "clinician"));
   ```

   and the enabling task must flip the two `assertFalse` assertions in
   `reconcileIsCurrentlyDeniedToClinicianGap` and rename it as the positive proof.

2. **Facility and care relationship on clinical verbs.** Facility narrowing is
   enforced for check-in, intake, emergency access and messaging through the
   `is_facility_out_of_scope` fold, but `ClinicalNoteService`,
   `OrderPlacementService`, `RxService` and the lab report release path never
   consult it. `ClinicalNoteService.requirePatient` only checks that the patient
   exists in the active organization; it does not check an assigned encounter or
   care relationship. `OrderPlacementService` stores the caller-supplied
   `facilityId` verbatim. Files to change: a Flyway migration associating the
   membership with facilities and an encounter care relationship (tenant-owned,
   RLS and FORCE RLS), the four clinical services and their stores, the staff
   invitation acceptance path, the action-policy contract and OpenAPI. The
   complete would-prove test for the enabling task is: a clinician assigned to
   `MAIN` sends an order for facility `NORTH` or an unassigned encounter and is
   refused 403 with no draft persisted; flip
   `facilityScopeIsNotConsultedByClinicalOrderingGap` accordingly. This is two
   implementation tasks (persistence, enforcement) plus one test-enablement task.

3. **Lab report release actor check.** `labflow.release.LabReportReleaseService`
   has no actor role decision of its own; the `CLINICIAN` constant is a
   notification recipient, not a gate. Clinician release authority is proven in
   `ReleaseService` (XCH-06) instead. Closing this caveat changes
   `LabReportReleaseService` and its controller, its unit/integration tests and
   the OpenAPI entry: one implementation task.

Estimate: four implementation tasks plus two test-enablement tasks. No task file
is edited here; a later task owns each gap.
