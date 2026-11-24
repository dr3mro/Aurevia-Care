# CLN-07 clause 4 — voiding an association captures a reason and preserves history

## Clause

Voiding an association captures a reason and preserves history.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.documents.clauses.VoidingAssociationCapturesReasonPreservesHistoryTest`
(`apps/api/src/test/java/com/aurevia/documents/clauses/VoidingAssociationCapturesReasonPreservesHistoryTest.java`),
an HTTP-level integration test on real PostgreSQL with two synthetic tenants
sharing the same local codes. Each fixture registers a fresh synthetic chart and
links a synthetic PDF before the void, so tests are independent:

- `voidCapturesReasonAndPreservesHistory` — `POST /api/v1/documents/associations/{id}/void`
  with a reason and `If-Match: "1"` returns `200` with `status=voided`, the
  recorded `voidReason` and `version=2`. `GET /api/v1/documents/associations/{id}`
  returns exactly two history events: the original `link` and the new `void`
  carrying the same reason and the authenticated clinician's identity id as
  actor. `GET /api/v1/documents/associations/{id}/content` still returns the
  original bytes unchanged.
- `receptionCannotVoidAnAssociation` — a reception actor (which may link and
  read but not void) is refused with `403 FORBIDDEN`; the owner still sees the
  association active at version 1 with only its `link` event and intact bytes.
- `peerTenantCannotVoidAnotherTenantsAssociation` — a clinician of a peer tenant
  is concealed with `404 NOT_FOUND` on another tenant's association; the owner
  still sees it active and unmodified, and the peer cannot read it either.
- `staleVersionCannotVoidAnAssociation` — a version other than the one read is
  refused with `412 STALE_VERSION` and leaves no `void` event.
- `alreadyVoidedCannotBeVoidedAgain` — retrying with the advanced version is
  refused with `409 INVALID_TRANSITION`; the first reason survives unchanged and
  no second `void` event is appended.
- `missingAndBlankReasonAreRefused` — both `{}` and a whitespace-only reason are
  refused with `422 REASON_REQUIRED`, each leaving the association active with no
  additional history event.
- `missingIfMatchIsRefused` — a void request with no `If-Match` header is refused
  with `428 PRECONDITION_REQUIRED` before any state change.

Production classes exercised through the real HTTP entry points
`POST /api/v1/documents/associations`, `POST /api/v1/documents/associations/{id}/void`
and `GET /api/v1/documents/associations/{id}[/content]`:

- `com.aurevia.documents.DocumentController` — thin adapter for the
  link, history, verified-content and void routes; parses `If-Match` and the
  `VoidRequest` reason.
- `com.aurevia.documents.DocumentService` — requires and forwards the
  reason, re-derives `DOCUMENT_VOID` authorization, conceals other tenants,
  rejects an already-voided or stale association, performs the guarded void and
  appends the `void` event.
- `com.aurevia.documents.DocumentInputs` and
  `com.aurevia.api.IfMatch` — bound and trim the reason and parse the
  version precondition at the boundary.
- `com.aurevia.documents.DocumentPolicy` — grants `DOCUMENT_VOID` to
  the clinician role only.
- `com.aurevia.documents.DocumentRepository` — tenant-bounded JDBC
  void, event append and ordered history read, with verified original content.
- `com.aurevia.documents.DocumentModel` — `DocumentAssoc`,
  `DocumentAssocView` and `AssociationEvent` projections returned as JSON.
- `db/migrations/V17__document_associations.sql` — `document_association` and its
  append-only `document_association_event` table with RLS + FORCE RLS, the
  `active → voided` guard requiring a reason and exactly one version advance,
  the event-actor trigger deriving the actor from the authenticated subject, and
  the no-delete/no-truncate triggers that pin the original bytes and history.

The void route is not yet published in `contracts/openapi.yaml`; it is baselined
in `contracts/openapi.yaml:199` as separate, already-tracked
contract debt. The tests drive the real controller route directly.

## Gap

None.
