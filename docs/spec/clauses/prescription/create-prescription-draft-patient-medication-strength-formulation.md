# RX-01 clause 1 — Create a prescription draft with patient, medication, strength, formulation, dose, route, frequency, quantity, refills where supported, instructions, prescriber and destination

## Clause

Create a prescription draft with patient, medication, strength, formulation, dose, route, frequency, quantity, refills where supported, instructions, prescriber and destination.

## Status

Partly proven

## Evidence

Test class: `com.aurevia.prescription.clauses.CreatePrescriptionDraftPatientMedicationStrengthFormulationTest`
(`apps/api/src/test/java/com/aurevia/prescription/clauses/CreatePrescriptionDraftPatientMedicationStrengthFormulationTest.java`),
a real-PostgreSQL integration test under the `synthetic-auth` profile that drives the HTTP entry
points `POST /api/v1/prescription-drafts`, `POST /api/v1/prescription-drafts/{id}/select-medication`,
`GET /api/v1/prescription-drafts/{id}` and `GET /api/v1/drug-catalog/search`. Two synthetic
organizations with forced row-level security, a clinician and a reception member in organization A,
a clinician in organization B, one synthetic patient and one approved effective catalog entry are
seeded in a class-private database. All inputs are synthetic.

- `createDraftCapturesPatientDestinationPrescriberThenSelectMedicationCapturesOrderDetails` —
  creation captures the patient, the destination and the database-derived prescriber at version 1;
  selection then captures the medication (catalog entry key and generic name), dose and unit, route,
  frequency, quantity and unit, refills and instructions at version 2. A later read through
  `GET /{id}` proves the order details were persisted, not merely echoed by the command.
- `selectedMedicationStrengthAndFormulationAreOnCatalogEntryButNotOnDraft` — the enabled gap-evidence
  test: the draft response exposes none of `strengthValue`, `strengthUnit` or `formulation`, while the
  catalog entry selected by the draft exposes `500`, `mg` and `tablet`. It asserts the precise
  boundary of the clause today and does not skip a test (an added `@Disabled` fails the repository
  constraint gate, so no skipped placeholder was used).
- `receptionRoleCannotCreateDraft` (negative) — a synthetic reception subject is refused with 403
  because draft creation is reserved to the clinician role.
- `draftIsNotVisibleFromAnotherTenant` (negative) — a clinician of organization B receives 404 both
  when reading an organization A draft and when trying to create a draft for an organization A
  patient; forced RLS and the patient visibility check fail closed.
- `staleVersionIsRejectedWhenSelectingMedication` (negative) — selecting with a positive but wrong
  version precondition (2 against version 1) is refused with 412 by the version guard.
- `missingOrMalformedPatientIdIsRejected` (negative) — a malformed and an absent patient identifier
  are both refused with 422 at the input boundary.
- `nonPositiveIfMatchVersionIsRejected` (negative) — a zero version precondition is refused with 422
  by the `If-Match` parser before the draft is read.

Production classes and files exercised by the request path:

- `com.aurevia.web.TenantContextInterceptor` — binds the synthetic subject and active
  organization from validated headers to the transaction-local tenant context.
- `com.aurevia.prescription.PrescriptionController` — thin HTTP adapter binding the
  create and select commands and the `If-Match` precondition.
- `com.aurevia.prescription.PrescriptionService` — `createDraft` validates the patient and
  writes the draft; `selectMedication` checks the version, resolves the approved catalog entry and
  records the order details; `searchCatalog` exposes the catalog values.
- `com.aurevia.prescription.PrescriptionActions` — reserves `CREATE_DRAFT` and
  `SELECT_MEDICATION` to the clinician role.
- `com.aurevia.prescription.PrescriptionInputs` and `com.aurevia.api.IfMatch`
  — reject malformed identifiers, order fields and version preconditions with 422.
- `com.aurevia.prescription.PrescriptionRepository` — the single SQL boundary; maps the
  `Draft` projection and the `DrugEntry` catalog projection.
- `com.aurevia.prescription.PrescriptionContract` — `CreateDraftRequest`,
  `SelectMedicationRequest`, `Draft` and `DrugEntry`; the last two show that strength and
  formulation are catalog fields, not draft fields.
- `db/migrations/V43__prescription_drafts.sql` — `prescription_draft` and `drug_catalog_entry` with
  forced row-level security, tenant policies and the `set_rx_draft_author_from_actor` trigger that
  derives the prescriber from `app.actor` and never from a request body.
- `contracts/openapi.yaml` — `POST /api/v1/prescription-drafts` and
  `POST /api/v1/prescription-drafts/{}/select-medication` are baselined here; the routes are
  reachable over HTTP but not yet described in `contracts/openapi.yaml` (see Findings in the worker
  notes). This test cites that baseline and edits no contract file.

## Gap

Strength, strength unit and formulation are carried by the approved catalog entry
(`drug_catalog_entry.formulation`, `.strength_value`, `.strength_unit` in V43; mapped to
`PrescriptionContract.DrugEntry`) but are not copied onto the prescription draft. The `Draft` record,
`PrescriptionRepository.DRAFT_SELECT` and `mapDraft` have no such fields, and
`prescription_draft` has no matching columns, so an HTTP caller cannot read them from the created
draft even though the prescriber can read them from the catalog. The clause is therefore only
partly provable at the draft boundary: patient, medication, dose, route, frequency, quantity,
refills, instructions, prescriber and destination are captured; strength and formulation are not.

Closing the gap is production work in a separate task, not a change to this test: add strength and
formulation columns to `prescription_draft` in a new Flyway migration (with tenant policy, forced
RLS and a backfill for existing drafts), add them to the `Draft` record, `insertDraft` /
`selectMedication` writes, `DRAFT_SELECT` and `mapDraft`, add the fields to the OpenAPI schema and
regenerate the client, then update the staff-web draft view. That is two separate tasks: a
backend/database/contract task and a staff-web task. When that work lands, rewrite
`selectedMedicationStrengthAndFormulationAreOnCatalogEntryButNotOnDraft` with an explanation to
assert the projected values on the draft instead of their absence — do not simply delete it, and
change this status to `Proven by tests` only after the new assertions pass.
