# LAB-02 clause 1 — capture numeric, qualified numeric, text and coded observations

## Clause

Capture numeric, qualified numeric, text and coded observations, with units, range, method when supplied, abnormal flags, instrument/source and verifier.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.lab.clauses.CaptureNumericQualifiedNumericTextCodedObservationsTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/lab/clauses/CaptureNumericQualifiedNumericTextCodedObservationsTest.java`).
It is a synthetic, database-free clause test in the same `clauses` style as
`scheduling/clauses/TransferHandoffRequiresActiveAssigneeTest`: a real
`ResultEntryService` with mocked `LabAccessionRepository`, `CriticalResultService`,
`TransactionTemplate` (the guarded callback runs synchronously), `AuditAppender`,
a `Clock.fixed` and a synthetic COLLECTED accession at version one. Every case
enters through `com.aurevia.smartclinic.labflow.results.ResultEntryService.enter`,
the command called by `POST /api/v1/lab/results/{id}`; the verifier is proven
through `ResultEntryService.verify`. The verifier is a report-level projection
field (`ReportView.verifier()`) derived from the authenticated tenant context,
not a client-supplied observation attribute.

- `capturesAllFourObservationKindsWithUnitsRangeMethodFlagsInstrumentAndVerifier` —
  captures six observations in one preliminary report: an exact numeric (GLU),
  two ranged numerics (NA below range, CA above range), a qualified numeric (AST
  with `<`), a text observation (URINE) and a coded observation (HIV). For each
  it asserts the wire `kind`, `presence`, value-or-text-or-codedValue, `unit`,
  `qualifier`, `method`, `instrument`, derived `abnormalFlag` and the derived
  `display()` rendering (lowercase wire flags, e.g. `5.5 mmol/L (none)` and
  `<120 U/L`). It then verifies as a different subject and asserts the final
  state, the preserved flags, qualifier, text and code, and that the report
  records that verifier; two non-critical ranged results register no critical
  workflow. (`method` and `instrument` are asserted on the captured view; the
  issued view re-asserts the value, flags, qualifier, text and code.)
- `captureIsRefusedForWrongRole` — a caller without the collector role is refused
  with 403 `FORBIDDEN`, the denial is audited, and no accession lookup or success
  audit occurs (negative: wrong role).
- `captureIsRefusedForMismatchedPatient` — observations cannot be filed against
  another patient's specimen: 422 `PATIENT_ACCESSION_MISMATCH` with a `MISMATCH`
  audit outcome (negative: patient/accession match).
- `captureIsRefusedForStaleVersion` — a specimen version changed since it was read
  is refused with 412 `STALE_VERSION` (negative: stale precondition).
- `captureIsRefusedForNonCollectedSpecimen` — only a COLLECTED specimen accepts
  results: 409 `INVALID_STATE` (negative: forbidden state).
- `captureIsRefusedForMissingOrMalformedObservation` — empty observations,
  an unsupported kind, a malformed code, a non-decimal value and a present numeric
  with no value are refused with 422 `NO_OBSERVATIONS`, `INVALID_OBSERVATION_KIND`,
  `INVALID_OBSERVATION_CODE`, `INVALID_VALUE` and `INVALID_OBSERVATION`; none
  produces a success audit (negative: missing and malformed input).

Production classes and files surveyed and exercised by the entry point:

- `com.aurevia.smartclinic.labflow.results.ResultEntryService` — `enter` validates
  the boundary, repeats the patient/accession match inside the tenant-guarded
  transaction, parses and flags observations, and delegates to the kernel;
  `verify` records the distinct verifier and the final revision. `parse`, `flag`,
  `authorize`, `matches`, `dueAfter` and `view` are covered through these cases.
- `com.aurevia.smartclinic.lab.result.LabResultService` — `capture` and `verify`
  append immutable tenant-keyed revisions; `evaluate` stamps the verifier on each
  observation via `LabObservation.verified`. `LabObservation`, `LabTypes`
  (`ObservationKind`, `Qualifier`, `AbnormalFlag`, `VerificationState`),
  `LabRules`, `LabPolicyCatalog.ReferenceRange` and `LabPolicyCatalog.CriticalPolicy`
  carry the four kinds, qualifiers, flags and approved evaluation bounds.
- `com.aurevia.smartclinic.labflow.results.ObservationInput`, `ResultEntryRequest`,
  `ReportView.ObservationView`, `ResultDecisionRequest` — the bounded wire shapes;
  `ObservationView.display()` is a derived rendering asserted alongside the record
  components, not a record component itself.
- `com.aurevia.smartclinic.lab.accession.LabAccessionRepository` (`hasActiveMembership`,
  `activeRoles`, `findById`), `com.aurevia.smartclinic.audit.AuditAppender`,
  `com.aurevia.smartclinic.exchange.critical.CriticalResultService`,
  `com.aurevia.smartclinic.tenant.TenantContext` and `com.aurevia.smartclinic.api.ApiException`
  — the authorization, provenance and audit boundaries used by every case.
- `com.aurevia.smartclinic.labflow.results.LabResultEntryController` —
  `POST /api/v1/lab/results/{id}` binds this command and injects `ResultEntryService`;
  `POST /api/v1/lab/results/{id}/verify` binds the verifier command.

Clause-noun coverage: numeric, qualified numeric, text and coded are carried by
`ObservationKind` and the value/text/code fields of `LabObservation`; `unit`,
`method` and `instrument` are captured and projected; `abnormalFlag` is derived
from approved bounds; `verifier` is recorded on issue. "Range" is an input and
derivation concern for this clause: approved `referenceLow/High` and
`criticalLow/High` bounds travel with each `ObservationInput`
(`ResultEntryService.flag`, lines 402-428) and are consumed to set the abnormal
flag and critical policy version stamped on the revision
(`LabResultService.evaluate`, lines 269-297). The raw bounds are intentionally
not emitted on `ObservationView`; source-unit, reference-range and interpretation
display belong to XCH-07 (`V25__observation_trending.sql`).

Broader coverage already present (not repeated here): service-branch and failure
tests in `labflow/results/ResultEntryServiceTest`, the thin-adapter proof in
`labflow/results/ResultControllerTest`, the real-PostgreSQL HTTP journey in
`labflow/results/ResultIntegrationTest`, and kernel tests in `it/lab/LabResultServiceTest`.

Contract and migration survey (recorded, not changed): `POST /api/v1/lab/results/{}`
is baselined in `contracts/openapi/missing-routes.txt` (line 237) and the capture
operation has no entry in `contracts/openapi/generated/openapi.yaml`, which
documents only `/api/v1/lab/results/{id}/verify` (lines 288-310). This is
contract-documentation debt tracked by other backlog items and is out of scope
for a clause test. `V26__laboratory_accession.sql` provides the LAB-01 specimen;
`V25__observation_trending.sql` and `V22__result_routing.sql` provide XCH-07
display and XCH-04 routing. This clause's revision ledger is the in-memory
`LabResultService`; durable observation persistence is a separate story.

Verified locally: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=CaptureNumericQualifiedNumericTextCodedObservationsTest test`
— `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`.

## Gap

None. The clause's observable capture and verifier behavior is implemented and
proven above. The contract-route documentation, raw-range wire display and
durable observation persistence noted under Evidence belong to separate,
already-tracked stories and are outside this task's scope; none requires a
`@Disabled` test here because the clause behavior itself is built and green.
