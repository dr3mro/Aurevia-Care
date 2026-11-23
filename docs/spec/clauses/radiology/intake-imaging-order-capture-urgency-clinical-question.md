# RAD-01 clause 1 — intake, schedule, accession and verified study link

## Clause

Intake an imaging order, capture urgency and clinical question, schedule, record accession and modality, link the external Study Instance UID and verify patient/accession before association.

## Status

Partly proven

## Evidence

Test class `com.aurevia.smartclinic.radiology.clauses.IntakeImagingOrderCaptureUrgencyClinicalQuestionTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/radiology/clauses/IntakeImagingOrderCaptureUrgencyClinicalQuestionTest.java`)
proves the rule at the imaging domain service entry point with synthetic facts and a fixed clock.
It does not claim HTTP, persistence or partner-system proof.

- `intakeSchedulesAccessionsAndAssociatesOnlyWithMatchingLabels` — intake with `ROUTINE` urgency and
  a clinical question and no pre-selected modality; scheduling records the time and approved `CT`
  modality; accession records issuer-scoped `ACC-3001`; association links the external Study
  Instance UID only when patient and accession match. The four revisions advance
  `INTAKE → SCHEDULED → ACCESSIONED → STUDY_ASSOCIATED` at versions 1–4, urgency and the clinical
  question are preserved on every revision, the associated revision carries the UID, partner
  workstation, verified patient and verified accession, and the four `ORDER_INTAKED`,
  `ORDER_SCHEDULED`, `ACCESSION_RECORDED` and `STUDY_ASSOCIATED` facts are emitted in order.
- `intakeRefusesMissingClinicalQuestionOrUrgency` — a blank clinical question is refused with
  `422 NO_CLINICAL_QUESTION` and a missing urgency with `422 INVALID_URGENCY`, appending no order or
  event.
- `statIntakeWithoutNamedOwnerIsRefused` — `STAT` urgency without a named responsible clinician is
  refused with `422 OWNER_REQUIRED`, while a routine intake with a named owner succeeds: the guard
  takes both sides.
- `associationRefusesMismatchedPatientAndAccession` — a wrong patient label and a wrong accession
  label are each refused with `422 LABEL_MISMATCH`; the order stays `ACCESSIONED` at version 3 with
  no UID or verified labels, no `STUDY_ASSOCIATED` fact is emitted, and one link-review item opens.
- `wrongRoleAndStaleVersionCannotAdvanceTheOrder` — a `finance` role is refused with
  `403 FORBIDDEN` at intake and a stale version precondition is refused with `412 STALE_VERSION` at
  scheduling, leaving the order at `INTAKE` version 1.
- `otherTenantCannotReadTheOrder` — a second tenant cannot read the order by its known local
  identifier; the service conceals it with `404 NOT_FOUND`.

Production classes exercised: `com.aurevia.smartclinic.radiology.ImagingService` (`intake`,
`schedule`, `accession`, `associateStudy`, `read`, `history`, `events`, `studyLinkReviews`,
`matches` and the version/transition guards), `RadCommands` (`IntakeCommand`, `ScheduleCommand`,
`AccessionCommand`, `AssociateStudyCommand`), `RadRules` (role checks), `RadPolicyCatalog`
(approved-modality check), `RadTypes` (`Urgency`, `Modality`, `OrderState`, `RadEventType`),
`ImagingOrder` (append-only revision) and `RadEvent` (domain fact). All six tests pass with
`mvn -f apps/api/pom.xml -Pno-security -Dtest=IntakeImagingOrderCaptureUrgencyClinicalQuestionTest test`
— `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`.

No `@Disabled` test is added for the missing HTTP chain: the rule itself is built and proven, and a
test driving schedule/accession routes would not compile because those routes do not exist and the
`ImagingController(ImagingService, …)` fixture constructor is package-private. Task step 3's
disabled-test escape hatch is therefore not applicable; the missing behaviour is recorded below.

## Gap

The rule is implemented in the in-memory `ImagingService` kernel, but the clause is not reachable
and durable end to end. `ImagingController` publishes only intake, read and study-link routes; it
has no schedule or accession route, so a caller cannot complete clause 1 over HTTP. State lives in
`HashMap`s in `ImagingService` with no Flyway table, RLS/FORCE RLS or `Jdbc*` store, so it does not
survive a restart. There is no imaging entry in `contracts/openapi.yaml`: all five imaging routes
are still listed as parity debt in `contracts/openapi/missing-routes.txt:49-50,220-222`.

One bounded follow-up task closes the gap: add a radiology migration with tenant RLS and FORCE RLS,
an `ImagingStore` interface with a `JdbcImagingStore` implementation behind `ImagingService`, an
injected thin controller with schedule and accession POST routes (including `Idempotency-Key`), an
OpenAPI entry with a regenerated client, and an HTTP-level integration test through those routes.
The existing `AUD-20261121-23` task covers `WorklistService`, not `ImagingService`, so it does not
cover this gap. This test-only story does not change those files; the task request is recorded in
the worker notes.
