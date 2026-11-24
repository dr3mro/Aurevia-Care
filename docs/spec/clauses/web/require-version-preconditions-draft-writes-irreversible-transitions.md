# ARC-03 clause 4 — require version preconditions for draft writes and irreversible transitions

## Clause

Require version preconditions for draft writes and irreversible transitions.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.web.clauses.RequireVersionPreconditionsDraftWritesIrreversibleTransitionsTest`
(`apps/api/src/test/java/com/aurevia/web/clauses/RequireVersionPreconditionsDraftWritesIrreversibleTransitionsTest.java`),
an HTTP-level integration test on real PostgreSQL with two synthetic tenants.
The draft is a temporary hold created through the real `POST /api/v1/appointments/holds`
route; the irreversible transition is the real `POST /api/v1/appointments/{id}/confirm`
route. Every command goes through production Spring wiring and the stored snapshot
is read back through the same RLS-bounded repository inside a tenant-guarded
transaction.

- `irreversibleCommandWithMatchingIfMatchAdvancesVersionAndPersists` — a
  receptionist holds a slot (201, state `held`, version 1) and confirms it with a
  matching `If-Match: "1"` (200, state `booked`, version 2). A second confirm with
  `If-Match: "2"` is refused (409 `INVALID_TRANSITION`), so the transition cannot
  be walked backwards. The stored snapshot is read back as `booked` at version 2.
- `irreversibleCommandWithoutIfMatchIsRejected` — a confirm without an
  `If-Match` header is refused (428 `PRECONDITION_REQUIRED`) and the stored draft
  stays `held` at version 1.
- `malformedIfMatchIsRejectedBeforeAnyWrite` — an unparseable `If-Match` is
  refused (422 `INVALID_IF_MATCH`) before any state change.
- `staleIfMatchIsRejectedWithoutChangingState` — a confirm with a version that
  does not match the stored one is refused (412 `STALE_VERSION`) and both stored
  version and state are unchanged.
- `wrongRoleOrOtherTenantIsDenied` — a clinician without the confirm permission
  is refused (403 `FORBIDDEN`); a receptionist of the other tenant is concealed
  (404 `NOT_FOUND`) and sees no row through the RLS-bounded store; the owner's
  draft remains `held` at version 1. A valid precondition is sent on both denied
  calls so the refusal comes from the domain authorization check rather than the
  earlier header parse.

Production classes exercised by the entry point:
`com.aurevia.web.AppointmentController` (the thin HTTP adapter that
binds `If-Match` and parses it before entering the domain),
`com.aurevia.api.IfMatch` (missing 428, malformed 422),
`com.aurevia.scheduling.AppointmentService` (`authorize` raises 403,
`loadForUpdate` conceals another tenant's row with 404, `requireVersion` raises
412 `STALE_VERSION`, the state machine refuses a second confirm with 409, and
`updateState` bumps the version by exactly one),
`com.aurevia.scheduling.AppointmentState` and
`com.aurevia.authz.ActionPolicy` (the role matrix),
`com.aurevia.scheduling.AppointmentRepository` (the RLS-bounded
stored snapshot) and `com.aurevia.tenant.TenantContext`, with tenant
RLS/FORCE RLS supplied by the Flyway migrations under `db/migrations`.

The draft-write half of the clause is also covered at its own API entry points by
`com.aurevia.finance.clauses.SupportDraftIssuedPaidPartiallyPaidVoidTest`
(a self-pay invoice draft refuses a write without `If-Match`: 428
`PRECONDITION_REQUIRED`, and advances its version on issue) and
`com.aurevia.chart.clauses.DraftSignedSeparateAmendmentDraftsSignedAddendaTest`
(a chart draft advances its version on sign, amendment and addendum). Those
suites are not modified here; this story proves the clause through the
appointment-confirm HTTP entry point and cites them as corroborating evidence.

The claim is limited to these tested entry points; it is not a claim that every
resource family in ARC-03 has been exhaustively re-tested here.

Verified locally:
`mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=RequireVersionPreconditionsDraftWritesIrreversibleTransitionsTest test`
— `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`.

## Gap

None within the tested entry points. Configuration and catalog drafts are
append-only version creation: a caller supplies the next version in the path, so
there is no editable draft to protect with `If-Match`; they are outside the
draft-write meaning exercised here. The clause is implemented at the web boundary
(`IfMatch`) and enforced by the owning domain service's compare-and-set, so no
production change is pending for this story.
