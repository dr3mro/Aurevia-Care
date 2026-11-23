# PT-01 clause 3 — sign notes, send the approved summary and document return acknowledgment

## Clause

Sign session, progress and discharge notes, send approved summaries to the referrer and document return acknowledgment.

## Status

Partly proven

## Evidence

Service-level integration proof against a fresh PostgreSQL database applied from the
production Flyway migrations (`synthetic-auth` profile, two synthetic tenants with
identical local identifiers, synthetic clinician/coordinator/referrer and patient
fixtures only). The test class is
`com.aurevia.smartclinic.therapy.clauses.SignSessionProgressDischargeNotesSendApprovedTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/therapy/clauses/SignSessionProgressDischargeNotesSendApprovedTest.java`).
It drives the durable domain entry point through a bound tenant context because the
three closure commands have no HTTP controller route today (see `## Gap`). Each
state is read back through `JdbcTherapyStore.find` and `TherapyService.get` under
forced RLS, so the assertions prove committed state, not just in-memory results.

Active tests:

- `signsSessionProgressAndDischargeNotesThenSendsApprovedSummaryWithReturnAcknowledgment`
  — positive proof of the whole chain: a SESSION, a PROGRESS and a DISCHARGE note
  are each drafted and signed with an attestation and checksum; the discharge
  closes the episode; the approved summary goes to the verified referrer from the
  episode; the referrer's acknowledgment binds the exact summary revision; the
  final notes, summary, acknowledgment and `DISCHARGED` state survive a fresh
  read. A direct `UPDATE` of a signed note as `smartclinic_app` is refused with
  SQLSTATE `42501`, proving immutability at the database boundary.
- `coordinatorCannotSignANote` — a `pt_coordinator` may send and acknowledge but
  is refused signing with 403 `FORBIDDEN`; the note stays unsigned.
- `peerTenantCannotSignAnotherTenantsNote` — a peer tenant's `pt_clinician` gets
  404 `NOT_FOUND` and ORG_A's note stays unsigned under forced RLS.
- `staleVersionCannotSignANote` — a stale `expectedVersion` is refused with 412
  `STALE_VERSION`.
- `aSignedNoteCannotBeSignedAgain` — re-signing a signed note is refused with
  409 `CONFLICT`.
- `dischargeWithoutASignedSessionNoteIsRefused` — a discharge note cannot be
  signed before a session or progress note is signed (409 `CONFLICT`).
- `summaryToAnUnverifiedReferrerIsRefused` — a summary to the wrong organization
  or subject is refused with 409 `CONFLICT` and no summary is persisted.
- `acknowledgmentMustBindTheExactSummaryVersion` — an acknowledgment naming a
  different summary revision is refused with 409 `CONFLICT`.
- `aSummaryCannotBeAcknowledgedTwice` — a second acknowledgment of the same
  revision is refused with 409 `CONFLICT`.
- `nonPtStaffCannotSendTheApprovedSummaryOrRecordTheReturnAcknowledgment` —
  a `finance` actor and an actor with no membership are refused summary send and
  acknowledgment with 403 `FORBIDDEN`; nothing is persisted.
- `summaryAndAcknowledgmentRejectStaleVersions` — both closure commands require
  the current episode version (412 `STALE_VERSION`).
- `closureCommandsConcealPeerTenantEpisodes` — summary send and acknowledgment
  conceal another tenant's episode (404 `NOT_FOUND`), leave ORG_A's state
  unchanged, and write a `NOT_FOUND` audit fact in the peer tenant.

Production classes exercised:

- `com.aurevia.smartclinic.therapy.TherapyService` — `signNote`, `sendSummary`,
  `acknowledgeReturn`, `get`, `auditTrail` and the guarded `authorize`, `load`,
  `requireVersion`, `requireState` helpers together with the declarative
  `ACTION_ROLES` policy (`NOTE_SIGN` is clinician-only; summary send and return
  acknowledgment allow clinician and coordinator).
- `com.aurevia.smartclinic.therapy.TherapyContract` — `NoteKind`, `ClinicalNote`,
  `Summary`, `ReturnAcknowledgment`, `TherapyAggregate` and the `NoteRequest`,
  `SummaryRequest`, `AckRequest` commands.
- `com.aurevia.smartclinic.therapy.JdbcTherapyStore` — tenant-guarded persistence
  of notes, the summary, the acknowledgment and the append-only audit trail.
- `db/migrations/V77__build_12.sql` — `therapy_clinical_note`, `therapy_summary`
  and `therapy_return_acknowledgment` with RLS, FORCE RLS, referrer keys and the
  `therapy_clinical_note_signed_guard` immutability trigger;
  `db/migrations/V85__therapy_store.sql` completes the store schema.

Discovery found no HTTP route for these commands: `TherapyController` publishes
only `POST /api/v1/therapy/episodes` and `GET /api/v1/therapy/episodes/{id}`, and
`contracts/openapi.yaml` lists those two routes only. The existing
suites `TherapyCoverageTest`, `TherapySafetyTest`, `TherapyEpisodeFlowTest`,
`TherapyDischargeAcceptanceTest` and `JdbcTherapyStoreIntegrationTest` already
exercise the domain branches; this class adds per-command protection of the
`NOTE_SIGN` role split and of `requireVersion`/`load` at the summary and
acknowledgment boundary, backed by durable state and a fresh database.

Verified locally with the focused integration run and SpotBugs recorded in
`.agent/pr-notes/SPEC-PT-01-C03-20261122-175531.md`.

## Gap

The domain rules and durable schema for clause 3 are built, but the clause cannot
be claimed as fully proven because no HTTP entry point, published contract or
regenerated client exists for the three closure commands:

1. **Missing controller routes and published contract.** `TherapyController`
   (`apps/api/src/main/java/com/aurevia/smartclinic/therapy/TherapyController.java`)
   exposes only referral accept and episode read. It needs routes to sign a note
   (`POST /api/v1/therapy/episodes/{id}/notes/{kind}/sign`), send the approved
   summary (`POST /api/v1/therapy/episodes/{id}/summary`) and record the return
   acknowledgment (`POST /api/v1/therapy/episodes/{id}/acknowledgment`), each
   resolving the actor from current membership, taking an `If-Match` version and
   declaring an `Idempotency-Key` on the POSTs. `contracts/openapi.yaml` needs
   those operations with a tag, request/response schemas, `Idempotency-Key` and
   `nextCursor`-free command semantics, the generated client must be regenerated,
   the routes removed from `contracts/openapi.yaml`, and an
   HTTP-level integration test added so the chain is proven through the real REST
   boundary. This class is the service-level test to extend to that boundary.
2. **Staff workflow surface.** Once the routes exist, the physical-therapy staff
   view needs actions to draft/sign session, progress and discharge notes, send
   the approved summary and capture the referrer's acknowledgment, with the
   clinician-only signing rule reflected in the UI.

That is **two separate tasks**: one cross-boundary backend/contract task (routes,
OpenAPI, client, route-parity and HTTP tests) and one staff-web task. No clinical
sign-off is recorded here; any live enablement of signing or disclosure follows
the specification's real-user approval gates.

This evidence task changed only the two in-scope files (the new test class and
this document) and its hand-off notes. It changed no production code, no existing
test, no migration and no contract. Story step 3 suggested an `@Disabled`
complete test for a partly built clause, but AGENTS.md's coverage rules and
`scripts/check-constraints.py` reject every added `@Disabled` in Java (it fails
the quality gate); the conflict is resolved by keeping this class green at the
service layer and recording the transport gap here instead.
