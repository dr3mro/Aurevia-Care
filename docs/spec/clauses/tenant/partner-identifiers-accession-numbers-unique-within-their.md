# ARC-01 clause 4 — Partner identifiers and accession numbers unique within issuer and type

## Clause

Partner identifiers and accession numbers are unique within their defined issuer and type.

## Status

Partly proven

## Evidence

Active HTTP-level proof (`synthetic-auth` profile, a fresh PostgreSQL database migrated from
`db/migrations`, synthetic fixtures only):

- Test class: `com.aurevia.smartclinic.tenant.clauses.PartnerIdentifiersAccessionNumbersUniqueWithinTheirTest`
  (`apps/api/src/test/java/com/aurevia/smartclinic/tenant/clauses/PartnerIdentifiersAccessionNumbersUniqueWithinTheirTest.java`).
  It follows the fixtures and database setup of `patient/clauses/RecordPatientrecordIssuerScopedMrnOrganizationTest`
  and `it/lab/SpecimenAccessionIsolationTest`, with a dedicated database (`smartclinic_arc01c04_it`) so
  durable rows are read back under forced row-level security. Two synthetic organizations and
  synthetic subjects (`reception-a`, `lab-a`, `auditor-a`, `reception-b`, `lab-b`) are used.

Test methods (one positive rule proof and three negative refusals):

- `accessionNumberIsUniqueWithinItsIssuer` — a `lab_collector` posts the same accession number and
  issuer twice through `POST /api/v1/lab/accessions`: the first returns 201 and the duplicate returns
  409 with code `CONFLICT`. The same number under a different issuer returns 201. SQL read-back
  under forced RLS finds one durable `lab_accession` row per issuer and no row in the peer
  organization, and `GET /api/v1/lab/accessions/{id}` returns 200 for the owning tenant.
- `peerOrganizationMayReuseTheSameIssuerAccessionNumberButCannotReadTheFirst` — a peer organization
  reuses the same issuer accession number (201), both organizations hold one durable row, and the
  first organization's accession is concealed from the peer (404).
- `roleWithoutAccessionPermissionCannotAccession` — a `privacy_auditor` is refused (403); the
  laboratory action policy grants `LAB_ACCESSION_CREATE` only to `lab_collector`.
- `malformedIssuerOrMissingNumberIsRejected` — a malformed issuer (`!`) and a request with no
  `accessionNo` are refused (422) at the API trust boundary before a transaction opens.

The missing type-qualified half is not proven here. Its intended acceptance contract is specified
under `## Gap` and handed to the two follow-up tasks below; no skipped test is kept in the suite. A
later implementation must add HTTP-level proofs for the partner-identifier issuer+type key and the
type-qualified accession key when those surfaces exist.

Production classes exercised through the normal HTTP entry point:

- HTTP entry point: `com.aurevia.smartclinic.lab.accession.LabAccessionController`
  (`POST /api/v1/lab/accessions`, `GET /api/v1/lab/accessions/{id}`).
- Domain service and boundary: `com.aurevia.smartclinic.lab.accession.LabAccessionService`
  (`accession`), `LabAccessionRepository`, `AccessionCommands.AccessionRequest`, `LabAccession`,
  `LabInputs`, `LabActionPolicy`.
- Schema: `db/migrations/V26__laboratory_accession.sql` defines `lab_accession` with
  `UNIQUE (organization_id, accession_issuer, accession_no)`, enabled and forced row-level security,
  tenant policies, immutable history and database-owned author/version columns.
- Tenant scope: `com.aurevia.smartclinic.tenant.TenantContext` and the synthetic-auth fixtures
  (`X-Synthetic-Subject`, `X-Active-Organization`).

Survey result — where the clause is implemented today:

- Issuer-scoped accession numbers are implemented in `apps/api/src/main/java/com/aurevia/smartclinic/lab/accession`
  (`LabAccessionService.accession`, `LabAccessionRepository.accessionExists`) and in
  `db/migrations/V26__laboratory_accession.sql`. The service refuses a duplicate with HTTP 409 both
  on the pre-check and on the `DuplicateKeyException` race path.
- `apps/api/src/main/java/com/aurevia/smartclinic/radiology/ImagingService.accession` keeps the
  issuer-scoped rule for imaging orders with a per-tenant in-memory key of `issuer|accession`.
- `db/migrations/V22__result_routing.sql` keys inbound result revisions on order reference, source,
  accession and version; it explicitly allows the same accession on two orders. It is result
  routing, not an identifier-uniqueness key.
- `db/migrations/V7__patient_registration.sql` keys MRN on `(organization_id, mrn_issuer, mrn,
  version)`; MRN is a different concept from a partner identifier.
- No partner-identifier issuer+type key or API was found in `apps/api/src/main/java`,
  `db/migrations` or `contracts`. Nearby candidates are ARC-05 external-event deduplication
  (`db/migrations/V55__durable_event_outbox.sql`) and FHIR identifier helpers
  (`exchange/fhir/FhirSupport.java`); neither enforces partner-identifier uniqueness by issuer and
  type.

## Gap

The issuer-scoped accession half is built and proven above. The literal clause is not fully built:

1. **Type-qualified accession key.** `specimen_type` exists in `V26__laboratory_accession.sql` but is
   nullable and outside `UNIQUE (organization_id, accession_issuer, accession_no)`, and it is only
   populated later by `LabAccessionService.collect`. To make an accession number unique within its
   issuer *and type*, the type must become a required field of `AccessionCommands.AccessionRequest`
   and part of a new uniqueness key, with `LabInputs` validation, `LabAccessionService.accession`
   and `recollect`, `LabAccessionRepository.accessionExists`/`insert`, a new Flyway migration with
   `ENABLE` and `FORCE ROW LEVEL SECURITY` plus tenant policies on the changed table, and the
   `POST /api/v1/lab/accessions` contract in `contracts/openapi.yaml`. One cross-boundary backend,
   database and contract task.
2. **Partner identifiers.** No partner-identifier record, command or endpoint exists. It needs a
   new tenant-owned table keyed by `(organization_id, issuer, type, identifier)` with forced RLS and
   tenant policies, a `Jdbc*` store behind a store interface, a domain service with the issuer+type
   conflict rule, an injected thin controller, an HTTP integration test and an OpenAPI entry
   (plus regenerated client types). One cross-boundary backend, database and contract task.

Follow-up: add HTTP-level acceptance proofs for the partner-identifier issuer+type key and the
type-qualified accession key to `PartnerIdentifiersAccessionNumbersUniqueWithinTheirTest` when those
two tasks land. This evidence task changed no production code, existing test, migration or contract;
status stays `Partly proven` until both missing halves are implemented and proven.
