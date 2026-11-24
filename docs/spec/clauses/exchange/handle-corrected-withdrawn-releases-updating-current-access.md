# XCH-06 clause 3 — API test evidence

## Clause

Handle corrected or withdrawn releases by updating current access, retaining history and notifying affected recipients per policy.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.exchange.clauses.HandleCorrectedWithdrawnReleasesUpdatingCurrentAccessTest`
(path: `apps/api/src/test/java/com/aurevia/exchange/clauses/HandleCorrectedWithdrawnReleasesUpdatingCurrentAccessTest.java`).
All seven tests are active (none are disabled) and use synthetic data on a dedicated PostgreSQL
database migrated from `db/migrations` with forced row-level security. They drive the real HTTP
entry points with the synthetic-auth profile, the same DB-bootstrap and `RestClient` pattern as
`com.aurevia.patient.clauses.UnlinkingImmediatelyEndsFutureAccountAccessAffectedTest`.

- `withdrawalUpdatesAccessRetainsHistoryAndNotifiesPriorRecipients` — positive proof. An
  immediate release is authorized, then withdrawn through `POST /api/v1/releases/{id}/withdraw`
  under `If-Match`. `GET /api/v1/patients/{patientId}/released-revisions` contains the revision
  before and omits it immediately after. `GET /api/v1/releases/{id}/history` retains both
  `RELEASE_AUTHORIZE` and `RELEASE_WITHDRAW`, the release row still exists, and a direct query of
  `release_notification` proves `RELEASE_WITHDRAWN` is published with `recipient_scope =
  'prior-recipients'`.
- `correctionMovesAccessToNewRevisionRetainingHistoryAndNotifyingPriorRecipients` — positive
  proof. `POST /api/v1/releases/{id}/correct` replaces the revision; current access switches to the
  new revision, the replacement carries `supersedesId` and inherits the original policy version and
  effective instant, the original history retains `RELEASE_AUTHORIZE` and `RELEASE_CORRECT`, and the
  outbox records `RELEASE_CORRECTED` / `prior-recipients` on the old row and `RELEASE_AUTHORIZED` /
  `linked-patient` on the replacement. This recipient-scope assertion is the clause-specific gap
  the neighbouring suites leave open.
- `nonDecidersCannotWithdrawOrCorrectAndAccessIsUnchanged` — negative proof (wrong role). A
  receptionist, a privacy auditor and a member with active membership but no role are refused with
  403 on both commands; access and history remain unchanged.
- `peerTenantCannotRetractTheReleaseAndStateIsUnchanged` — negative proof (another tenant). A
  clinician of a second tenant with the same local patient identifier gets 404 on both commands
  under forced RLS; the release stays authorized in its own tenant.
- `staleIfMatchRefusesWithdrawalAndLeavesAccessUnchanged` — negative proof (stale version). A
  stale `If-Match` is refused with 412 `STALE_VERSION` and changes no access or history. The service
  uses 412 `STALE_VERSION` for a version mismatch; 409 is reserved for state conflicts.
- `forbiddenStatesAndRepeatedRevisionsAreRefused` — negative proof (forbidden state and duplicate
  revisions). Withdrawing an already withdrawn release, correcting onto the same revision and
  correcting onto a revision that was already released all return 409 and leave access unchanged.
- `missingOrInvalidDecisionInputIsRefused` — negative proof (missing input). A blank withdrawal or
  correction reason, a malformed replacement revision id and a non-positive replacement version are
  refused with 422 and the documented error codes; the original release stays authorized.

Production classes and database objects exercised through the normal HTTP entry point:

- `com.aurevia.exchange.release.PatientReleaseController` — thin adapter for
  `POST /api/v1/releases/{id}/withdraw`, `POST /api/v1/releases/{id}/correct` and
  `GET /api/v1/releases/{id}[/history]`; parses `If-Match` and delegates.
- `com.aurevia.exchange.release.PatientReleasedRevisionsController` —
  `GET /api/v1/patients/{patientId}/released-revisions`, the current-access query.
- `com.aurevia.exchange.release.ReleaseService` — `withdraw`, `correct`, `transition`
  and `visibleRevisions` run authorization, version precondition, state transition and outbox
  append inside one tenant-guarded transaction.
- `com.aurevia.exchange.release.ReleaseActionPolicy` and `ReleaseState` — decider roles
  for withdraw/correct and the terminal-state transition rules.
- `com.aurevia.exchange.release.ReleaseRepository` — compare-and-set updates,
  append-only event and notification inserts, and forced-RLS reads.
- `com.aurevia.api.IfMatch` and `com.aurevia.api.ApiErrorHandler` — version
  precondition parsing and the uniform error body.
- `db/migrations/V24__patient_release.sql` — the append-only `release_event` and
  `release_notification` ledgers, forced RLS and the database state-machine trigger.

Neighbouring implementations observed but not duplicated here: `it.exchangerelease.PatientReleaseIsolationTest`
already proves the withdraw/correct access and history transitions, stale-version, terminal-state,
self-correction, duplicate-revision, tenant-isolation and role refusals at HTTP level;
`it.exchangerelease.ReleasePolicyTest` unit-tests every role and state-machine branch including the
null-role case; `acceptance.patient.PatientRevisionAccessAcceptanceTest` proves the patient-facing
download refusal. This task adds the clause-focused end-to-end proof of the recipient-scope policy.

## Gap

The clause is implemented and proven at the API boundary; no production gap remains. "Notifying"
is materialized as an append-only `release_notification` outbox row carrying the policy-defined
`recipient_scope`; this package writes that row and this test proves its persisted value through
HTTP and SQL, but no dispatcher consumes the outbox inside `src/main`. Actual delivery to a human
recipient is a separate operational concern. Two further observations are outside this task's
scope:

- The routes `POST /api/v1/releases`, `POST /api/v1/releases/{}/withdraw`,
  `POST /api/v1/releases/{}/correct` and `GET /api/v1/patients/{}/released-revisions` are currently
  baselined in `contracts/openapi.yaml` rather than published in `contracts/openapi.yaml`.
  Documenting them and regenerating the staff-web client is tracked by the existing `FIX-OPENAPI-*`
  stories; no contract file was edited here.
- The `ReleaseActionPolicy.allows(action, null)` branch cannot be produced through HTTP because
  `role_assignment.role_key` is `NOT NULL`; it is covered by `ReleasePolicyTest.nullRoleAndUnknownActionAreDenied`
  instead of by a synthetic HTTP request.
