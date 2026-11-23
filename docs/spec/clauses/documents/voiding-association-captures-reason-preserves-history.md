# CLN-07 clause 4 — voiding preserves reason and history

## Clause

Voiding an association captures a reason and preserves history.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.documents.clauses.VoidingAssociationCapturesReasonPreservesHistoryTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/documents/clauses/VoidingAssociationCapturesReasonPreservesHistoryTest.java`),
an HTTP-level integration test on real PostgreSQL with two synthetic tenants.
The entry point is `POST /api/v1/documents/associations/{id}/void` with an
`If-Match` version precondition; read-back uses
`GET /api/v1/documents/associations/{id}` and `GET .../content`.

- `voidingCapturesReasonAndPreservesOriginalAndHistory` — a clinician voids an
  active association with `If-Match: W/"1"` and `{"reason":"wrong chart"}`. The
  response is `200` with `status=voided`, `voidReason` equal to the recorded
  reason and `version=2`. The append-only history keeps the original `link`
  event and gains a `void` event carrying the same reason; its `actor` is the
  authenticated clinician's `user_identity.id`, not a spoofed `actorId` in the
  body. The retained original bytes, `sha256`, `byteSize` and `createdBy` are
  unchanged and `GET .../content` still returns the original bytes.
- `invalidAssociationIdVoidIsRefused` — a malformed id is refused with
  `422 INVALID_ID` before any write.
- `receptionCannotVoidAnAssociation` — reception holds `DOCUMENT_LINK` and
  `DOCUMENT_READ` but not `DOCUMENT_VOID`, so the void is refused with
  `403 FORBIDDEN` and the association stays active with a single `link` event.
- `peerTenantCannotVoidAnotherTenantsAssociation` — a clinician of the peer
  tenant is refused with `404` under forced RLS, so no reason or actor crosses
  the tenant boundary.
- `staleVersionVoidIsRefused` — after a fixture-only advance of an active row
  from version 1 to version 2, reusing `If-Match: W/"1"` is refused with
  `412 STALE_VERSION`; the status stays active and no void reason or event is
  written.
- `blankOrMissingReasonIsRefusedBeforeAnyWrite` — both a whitespace-only and
  an absent reason are refused at the boundary with `422 REASON_REQUIRED`,
  leaving the association, its single history event and its bytes untouched.
- `voidingAnAlreadyVoidedAssociationIsRefused` — a second void with the current
  `If-Match: W/"2"` is refused with `409 INVALID_TRANSITION`, and the original
  reason, two-event history and original bytes remain intact.

Production classes exercised through the HTTP entry point:

- `com.aurevia.smartclinic.documents.DocumentController` — thin adapter;
  `voidAssociation` parses the `If-Match` header and the nested
  `DocumentController.VoidRequest` reason, and never binds tenant or actor from
  the body.
- `com.aurevia.smartclinic.documents.DocumentService` — `voidAssociation`
  validates the id and reason, authorizes `DOCUMENT_VOID`, conceals a missing
  association, refuses an already-voided or stale row, forwards the reason to
  the append-only event and audits; `authorize` and `staleVersion` own the
  denial paths.
- `com.aurevia.smartclinic.documents.DocumentInputs` — `uuid` and `reason`
  normalization and the `422 REASON_REQUIRED` boundary.
- `com.aurevia.smartclinic.documents.DocumentPolicy` — `DOCUMENT_VOID` is
  clinician-only; reception cannot void.
- `com.aurevia.smartclinic.documents.DocumentRepository` — `findAssociation`,
  the version-guarded `voidAssociation` compare-and-set, `appendEvent`,
  `associationHistory` and `verifiedAssociationContent`.
- `com.aurevia.smartclinic.documents.DocumentModel` — `DocumentAssoc`,
  `AssociationEvent` (including its `actor` field) and `DocumentAssocView`.
- `com.aurevia.smartclinic.api.IfMatch` — parses the weak `W/"1"` and `W/"2"`
  preconditions.
- `com.aurevia.smartclinic.audit.AuditAppender` and
  `com.aurevia.smartclinic.tenant.TenantContext` — tenant and actor come from
  the `X-Active-Organization` / `X-Synthetic-Subject` headers; audit outcomes
  are recorded for the success and denial paths.
- `db/migrations/V17__document_associations.sql` — forced tenant RLS, the
  `guard_document_association_update` trigger that pins identity, original
  bytes and checksum while requiring a reason and one version advance, the
  actor trigger that derives the history actor from the authenticated subject,
  and the append-only event table.

Verified locally with `SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=VoidingAssociationCapturesReasonPreservesHistoryTest test`
— `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 10.17 s -- in com.aurevia.smartclinic.documents.clauses.VoidingAssociationCapturesReasonPreservesHistoryTest`
(the first green run was 11.17 s; the final run after review hardening was
10.17 s. Surefire XML `tests="7" errors="0" skipped="0" failures="0"`; tool evidence).

## Gap

None for the clause: the reason capture, version guard, preserved bytes and
append-only history are implemented and proven end to end. One related
publication debt remains outside this test-only story: the void route is listed
in `contracts/openapi/missing-routes.txt` (line 199), not in
`contracts/openapi.yaml`. Publishing it would change `contracts/openapi.yaml`,
regenerate `apps/staff-web/src/api/generated/openapi.d.ts` and remove the
baseline line; that is the existing task `FIX-OPENAPI-04` (1 separate task).
