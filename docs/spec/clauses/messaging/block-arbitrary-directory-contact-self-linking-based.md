# MSG-01 clause 4 — block arbitrary directory contact, demographic self-linking and cross-unit sharing

## Clause

Block arbitrary directory contact, self-linking based on demographics, and sharing a conversation across units.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.messaging.clauses.BlockArbitraryDirectoryContactSelfLinkingBasedTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/messaging/clauses/BlockArbitraryDirectoryContactSelfLinkingBasedTest.java`),
eight active tests with synthetic fixtures only (no `@Disabled`, no real patient data):

- `patientOpensOnlyThroughVerifiedActiveConnection` — a patient open command persists the
  patient, unit key and connection identifier returned by the verified connection resolver,
  not the values merely named in the request (a resolver returning different identifiers is
  asserted to win); no client value can choose the stored identities.
- `directoryListingAndConnectRefuseContactWithoutActiveVerifiedRelationship` — a subject with
  a verified chart but no relationship sees an empty directory and is refused a connection
  (422 `NO_ELIGIBLE_RELATIONSHIP`); inactive, consumed and expired relationships stay
  ineligible. No connection is inserted.
- `matchingDemographicsOrAnotherPatientsConnectionCannotSelfLink` — a connection owned by
  another verified chart is concealed as 404 `NOT_FOUND` when used as one's own, and the
  enrollment relationship reference exposes only an identifier and a site-verified flag,
  with no demographic fields. No connection or message is written.
- `otherTenantCannotResolveTheConnection` — binding a different organization makes the
  tenant-scoped connection lookup return nothing (404 `NOT_FOUND`); the original connection
  remains in its own tenant and is never disclosed across the boundary.
- `replyDoesNotShareAConversationAcrossUnits` — a directory unit category that no longer
  matches the expected key denies the reply (403 `FORBIDDEN`), and a staff member whose role
  is not eligible for the conversation's unit is denied by the unit matrix (403 `FORBIDDEN`);
  neither path appends a message or updates the thread.
- `wrongRoleStaleVersionClosedThreadAndMissingInputAreRefused` — a role without the command
  permission (403 `FORBIDDEN`), a stale version (412 `STALE_VERSION`), a closed thread
  (409 `CLOSED_THREAD`) and an unapproved unit key (422 `INVALID_UNITKEY`) are each refused
  with an explicit status and code and without a write.
- `verifiedRelationshipAllowsItsOwnVerifiedConnectionAndUnit` — positive proof for the directory
  ban: only a verified, active, unconsumed relationship lists its unit and grants a connection,
  and `requireActive` returns identities derived from that exact connection and unit.
- `conversationAcceptsOnlyItsAuthorizedUnitAndConnection` — positive proof for the sharing ban:
  an in-unit staff reply appends one message and advances the same conversation, so the refusal
  rules above do not disable legitimate unit-scoped messaging.

Production classes exercised through their normal server-side command entry points
(the same ones the controllers call), with deterministic test fixtures at the store boundary:

- `com.aurevia.smartclinic.messaging.units.CareUnitDirectoryService` —
  `eligibleUnits` lists only active, unconsumed, unexpired verified relationships; `connect`
  requires an exact unit, context and reference match (422 `NO_ELIGIBLE_RELATIONSHIP` when
  absent); `requireActive`, `requireActiveConnectionById`, `resolveConnection` and `isUsable`
  re-resolve the connection on every protected action and conceal foreign charts and tenants.
- `com.aurevia.smartclinic.messaging.conversations.ConversationService` — `open` persists only
  the identities derived from the verified connection; `reply`, `requireAccess`, `requireVersion`
  and `requireWritable` re-authorize and re-resolve on every action, refuse wrong-unit staff
  and closed or stale threads, and bind a conversation to one connection and one unit.
- `com.aurevia.smartclinic.messaging.conversations.ConversationUnitResolver.fromDirectory`,
  `PatientConnectionVerifier` and `ConversationActions.allowsUnit` — derive the bounded unit
  key from tenant directory data, deny an unknown category and bind each staff role to a
  single care unit; no route or field moves a conversation to another unit.
- `com.aurevia.smartclinic.messaging.enrollment.EnrollmentContract.RelationshipRef` — a verified
  care relationship with no demographic fields, so a shared phone, name or date of birth can
  never establish a connection on its own.

Source survey (unchanged by this task): the relevant production packages are
`apps/api/src/main/java/com/aurevia/smartclinic/messaging/units`,
`apps/api/src/main/java/com/aurevia/smartclinic/messaging/conversations` and
`apps/api/src/main/java/com/aurevia/smartclinic/messaging/enrollment`; the web controllers are
`CareUnitController` and `ConversationController`; the forced-RLS tables are `care_unit`,
`eligible_relationship`, `patient_unit_connection` and `messaging_audit` from
`db/migrations/V60__care_units.sql`, and `patient_link`, `conversation`, `conversation_message`
and `work_item_event` from `db/migrations/V33__conversation_lifecycle.sql`. The published
OpenAPI contract does **not** currently carry the care-unit or conversation routes: they are
baselined as missing in `contracts/openapi.yaml`. No OpenAPI contract proof is
claimed here; the rule is proven at the domain command boundary. This is simulated engineering
evidence, not real-patient approval or a substitute for the database isolation suites that
the integrator runs.

Verified locally: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=BlockArbitraryDirectoryContactSelfLinkingBasedTest test`
— `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`.

## Gap

None for the three bans: each is implemented in production and proven by an active test above.
The care-unit and conversation routes are absent from the published OpenAPI contract
(`contracts/openapi.yaml`); publishing them is a separate, already-tracked
contract gap, not missing behaviour of this clause. Closing it would change
`contracts/openapi.yaml`, the generated client and `contracts/openapi.yaml`, and
needs one separate contract-publication task. This task changed only the test and this document
— no production code, existing test, contract or migration.
