# CLN-05 clause 3 — API test evidence

## Clause

Save signer, effective time, signed time, content checksum and template version.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.chart.clauses.SaveSignerEffectiveTimeSignedTimeContentTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/chart/clauses/SaveSignerEffectiveTimeSignedTimeContentTest.java`).
It is an HTTP-level integration test against a real PostgreSQL ledger with the same Flyway
and synthetic fixtures as the neighbouring `clauses` tests. All three tests are active
(none are disabled) and pass with synthetic data only.

- `signingSavesSignerEffectiveTimeSignedTimeChecksumAndTemplateVersion` — positive proof.
  A clinician creates a draft with an explicit effective time, template version 3 and known
  content, then signs it. The returned revision is asserted to carry `signerSubject=clinician-a`,
  a non-empty `signedAt`, the preserved `effectiveAt` from the draft, `contentChecksum` equal to
  the server SHA-256 of the content and `templateVersion=3`. The same five facts are asserted
  directly on the persisted `public.clinical_note_revision` row, and the append-only history
  keeps both the draft and the signed revision.
- `signingIsRefusedForRoleWithoutSignPermission` — negative proof. A `reception` caller with
  active membership is refused with HTTP 403 `FORBIDDEN`; the note stays in `draft`, so no sign
  metadata is saved for an unauthorized signer.
- `signingWithStaleVersionIsRefused` — negative proof. A sign carrying a version older than the
  current draft is refused with HTTP 412 `STALE_VERSION`; the note stays in `draft`, so no sign
  metadata is saved for a stale write.

Verified locally: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=SaveSignerEffectiveTimeSignedTimeContentTest test`
→ `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`.

Production classes exercised through the normal HTTP entry point
(`POST /api/v1/clinical-notes/{id}/sign`):

- `com.aurevia.smartclinic.clinical.notes.ClinicalNoteController` — thin HTTP adapter that binds
  the `If-Match` precondition and the attestation request.
- `com.aurevia.smartclinic.clinical.notes.ClinicalNoteService` — `sign(...)` re-checks
  authorization, version, state, required fields and patient context, then appends an immutable
  `SIGNED` revision carrying the signer context, the clock's signed instant, the preserved
  effective instant, the draft checksum and the template version.
- `com.aurevia.smartclinic.clinical.notes.NoteRepository` — the single SQL boundary; its `insert`
  writes the five facts and its reads return the persisted revision.
- `com.aurevia.smartclinic.clinical.notes.ClinicalNoteRevision` and
  `com.aurevia.smartclinic.clinical.notes.NoteChecksum` — the record that carries the sign
  metadata and the server-side SHA-256 computation.
- `db/migrations/V15__clinical_note_signing.sql` — the `clinical_note_revision` ledger with the
  `signed_at`, `signer_subject`, `effective_at`, `content_checksum` and `template_version`
  columns, forced row-level security, append-only guards and triggers that own the version and
  actor.

Neighbouring implementations observed in the source survey (not changed by this task): the
chart package `com.aurevia.smartclinic.chart.record` implements CLN-03 recording and verification
but has no signing endpoint; the sign capability for chart notes lives in the `clinical.notes`
package above. The signing operation is also covered by
`com.aurevia.smartclinic.it.clinical.ClinicalNoteRevisionIntegrationTest` and the fast unit tier
`com.aurevia.smartclinic.it.clinical.ClinicalNoteServiceTest`.

## Gap

None.

The clause is built and proven by active tests. This task added proof only; it did not change
production code, an existing test, the OpenAPI contract or a migration.
