# CLN-05 clause 3 — save signature metadata on a signed revision

## Clause

Save signer, effective time, signed time, content checksum and template version.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.chart.clauses.SaveSignerEffectiveTimeSignedTimeContentTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/chart/clauses/SaveSignerEffectiveTimeSignedTimeContentTest.java`),
an HTTP-level integration test on a fresh real-PostgreSQL database created from
the production Flyway migrations, with synthetic subjects in two synthetic
organizations and one synthetic patient. Each test reads the response and then
re-reads the ledger over a tenant-scoped JDBC connection, so an HTTP status alone
cannot stand in for persisted metadata.

- `signingPersistsSignerEffectiveTimeSignedTimeChecksumAndTemplateVersion` — a
  draft authored by `clinician-b` with a past `effectiveAt` and `templateVersion`
  3 is signed by `clinician-a`. The response and the stored version-2 row carry
  `signerSubject = clinician-a` (the authenticated signer, not the author),
  a non-null `signedAt` that PostgreSQL confirms is at or after both the
  effective instant and an instant captured just before the command, the
  unchanged effective instant, `contentChecksum = SHA-256(content)` and
  `templateVersion = 3`. The version-1 draft stays in the append-only history
  with a null signer and null signed time.
- `signingByUnauthorisedRoleIsRefused` — `clinical_assistant` and `reception`
  sign attempts return 403 `FORBIDDEN`; the note stays a single unsigned draft.
- `crossTenantCannotSeeOrSignTheDraft` — a clinician of another tenant gets 404
  on both GET and sign; the owning tenant still reads the note and no signed row
  is written.
- `staleVersionCannotSignTheDraft` — `If-Match: "2"` against the version-1 draft
  returns 412 `STALE_VERSION`; the row stays a draft.
- `missingAttestationCannotSignTheDraft` — an empty sign body returns 422
  `INVALID_ATTESTATION`; the row stays a draft.
- `signedRevisionCannotBeSignedAgainOrEdited` — re-signing returns 409
  `INVALID_TRANSITION` and editing the signed version returns 409
  `IMMUTABLE_REVISION`; the ledger still holds exactly two revisions.
- `clientSuppliedSignatureMetadataIsIgnored` — extra JSON keys for signer,
  signed time, checksum and template version are dropped by the HTTP boundary;
  the response and the row keep the server-derived values (attester, signing
  instant, recomputed digest and the draft's template version).

Production classes exercised through `POST /api/v1/clinical-notes`,
`POST /api/v1/clinical-notes/{id}/sign`, `PUT /api/v1/clinical-notes/{id}` and
`GET /api/v1/clinical-notes/{id}`: `com.aurevia.smartclinic.clinical.notes.ClinicalNoteController`
(thin HTTP adapter), `com.aurevia.smartclinic.clinical.notes.ClinicalNoteService`
(draft-to-sign invariants, version precondition and attestation validation),
`com.aurevia.smartclinic.clinical.notes.NoteInputs` (trust-boundary validation),
`com.aurevia.smartclinic.clinical.notes.RevisionInput`,
`com.aurevia.smartclinic.clinical.notes.ClinicalNoteRevision`,
`com.aurevia.smartclinic.clinical.notes.NoteRepository` (tenant-guarded SQL
boundary and revision fold), `com.aurevia.smartclinic.clinical.notes.NoteChecksum`
(server-side SHA-256) and `db/migrations/V15__clinical_note_signing.sql` (actor
and checksum stamp trigger, signed-state CHECK, `signed_at >= effective_at`
CHECK, succession guard, RLS and FORCE RLS). The reading code is corroborated by
the existing `com.aurevia.smartclinic.it.clinical.ClinicalNoteRevisionIntegrationTest`,
which is not re-tested here.

Verified locally with the targeted suite:
`SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=SaveSignerEffectiveTimeSignedTimeContentTest test`
→ `tests="7" errors="0" skipped="0" failures="0"` in the Surefire XML report.
"Proven by tests" is limited to the clause behaviour above; it does not claim
that these routes are published in the committed OpenAPI contract.

## Gap

None for the clause behaviour. Out of scope and pre-existing: the committed
OpenAPI contract has no `/api/v1/clinical-notes` paths
(`contracts/openapi/missing-routes.txt` already lists them). Publishing the
routes, their tags, headers and generated client is a separate ARC-04/WEB-05
contract task; no contract, migration or production file was changed here.
