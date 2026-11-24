# MSG-02 clause 1 — let the patient or an authorized unit staff member open a conversation

## Clause

Let the patient or an authorized unit staff member open a conversation.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.messaging.clauses.LetPatientAuthorizedUnitStaffMemberOpenTest`
(`apps/api/src/test/java/com/aurevia/messaging/clauses/LetPatientAuthorizedUnitStaffMemberOpenTest.java`).
It is a synthetic, database-free clause test in the `clauses` style used by
`docs/spec/clauses/scheduling/transfer-handoff-requires-active-assignee.md`: a
MockMvc `standaloneSetup` over the real controller and service, mocked
`ConversationRepository`, `TransactionTemplate` (synchronous callback),
`AuditAppender` and `PatientConnectionVerifier`, and an explicit `TenantContext`.
Every case enters through the clause's HTTP command,
`POST /api/v1/conversations`, and asserts a status, an error code or the persisted
identities, state, work-item fact and audit outcome.

- `patientOpensConversationThroughVerifiedConnection` — a verified patient opens
  a thread: HTTP 201 and `awaiting_staff`; the patient, unit and connection
  persisted are the ones the mocked verifier returned, deliberately different
  from the values the request named; one `created` work-item fact and one
  `SUCCESS` audit event (rule met).
- `authorizedUnitStaffOpensConversation` — a reception staff member eligible for
  the clinic unit opens a thread: HTTP 201 and `awaiting_patient`, with the
  request's identities persisted, no connection verification call, one `created`
  fact and one `SUCCESS` audit event (rule met, staff side).
- `openIsRefusedForRoleWithoutMessagingPermission` — `tenant_admin` is refused
  with 403 and `missing CONVERSATION_OPEN permission`, nothing is persisted and
  a `DENIED` audit event is written (negative: wrong role).
- `openIsRefusedWhenStaffRoleIsNotEligibleForTheUnit` — `lab_collector` against
  the clinic unit is refused with 403 and `the caller is not eligible for this
  care unit`, with no lookup of the connection (negative: forbidden unit).
- `openIsRefusedWhenPatientNamesAnotherPatientsSubject` — a patient naming a
  different subject is refused with a concealment 404; `patientExists`,
  `patientLinkActive` and connection verification are never called
  (negative: mismatched patient, no existence probe).
- `openIsRefusedWhenConnectionIsNotVerifiedOrRevoked` — a verifier denial
  propagates as 404 with no conversation insert (negative: revoked or
  unverified connection).
- `openIsRefusedForInactiveMembership` — a caller without active membership is
  refused with 403 and a `DENIED` audit event (negative: revoked membership
  fails closed on the next action).
- `openIsRefusedForMalformedInput` — an invalid patient id, connection id and
  unit key each yield 422 with `INVALID_PATIENTID`, `INVALID_CONNECTIONID` and
  `INVALID_UNITKEY`, before any repository or connection call
  (negative: missing or malformed input).

There is no duplicate-idempotency, cursor or stale-version case: `POST open`
takes no `Idempotency-Key`, cursor/limit or `If-Match` precondition, so none is
invented here.

Production classes and files surveyed and exercised by the entry point:

- `com.aurevia.messaging.conversations.ConversationController.open`
  — `POST /api/v1/conversations` is the HTTP entry point; the controller
  injects the service and only binds the JSON command.
- `com.aurevia.messaging.conversations.ConversationService.open`
  (lines 92-134) — owns the rule: it validates every field, checks domain
  authorization before any existence lookup, derives the persisted patient,
  unit and connection from the verified connection for a patient caller,
  checks the role/unit matrix for a staff caller, sets `awaiting_staff` versus
  `awaiting_patient`, and commits the insert, the `created` fact and the audit
  event in one transaction. Its `authorize` (lines 373-388) reads
  `repository.activeRoles`, not the tenant context's purpose field, and writes
  the `DENIED` audit outcome before returning a failure.
- `com.aurevia.messaging.conversations.ConversationActions` — the
  bounded action/unit matrix that gives exactly the asserted denials for
  `tenant_admin` and `lab_collector`.
- `com.aurevia.messaging.conversations.ConversationInputs`,
  `ConversationContract.OpenRequest`, `Conversation`, `State` and `UnitKey`,
  `PatientConnectionVerifier.VerifiedConnection` and `ConversationRepository`
  — the input guards, command, projection, lifecycle states, verified
  identities and single SQL boundary exercised by the entry point.
- `com.aurevia.api.ApiErrorHandler` and `ApiException` — render the
  201, 403, 404 and 422 outcomes as the uniform error body asserted in the
  negative cases.
- `db/migrations/V33__conversation_lifecycle.sql` (lines 143-146, 198-210) —
  creates `public.conversation`, `conversation_message` and `work_item_event`
  with `ENABLE ROW LEVEL SECURITY` and `FORCE ROW LEVEL SECURITY` on each, and
  the `guard_conversation_insert` trigger that re-checks the verified patient
  link at write time. The migration is already in place and was not changed.
- Contract survey: the route is baselined as
  `POST /api/v1/conversations` in `contracts/openapi.yaml` line
  163, as are the other conversation routes. It is not described as an
  operation in `contracts/openapi.yaml`. That is separate
  contract-documentation debt, not a behaviour gap in this clause, and no
  contract file was changed here.

Broader coverage already present (not repeated here): the HTTP-plus-PostgreSQL
path, the verified-link and forced-RLS folds, and the database guards are
proven in `apps/api/src/test/java/com/aurevia/it/messagingconversations/ConversationFlowTest.java`
(`patientOpenStaffReplyUnreadMarkReadReopenAndClose`,
`preconditionsValidationAndTenantIsolation`,
`revokedLinkAndRevokedMembershipFailOnNextRequest`,
`databaseGuardsRejectIllegalWrites` and
`directFoldAssertionsConfirmVerifiedLinkAndForcedRls`). In particular the
`patientLinkActive == false` branch of `open` is already exercised by
`revokedLinkAndRevokedMembershipFailOnNextRequest`; this clause test does not
duplicate it.

Verified locally with
`mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=LetPatientAuthorizedUnitStaffMemberOpenTest test`
— `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`.

## Gap

None.
