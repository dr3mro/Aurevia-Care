# AUD-20261119-02 — Verify the patient-unit connection when opening a conversation

Status: complete, ready for integrator merge. Production scope confined to
`apps/api/src/main/java/com/aurevia/smartclinic/messaging/conversations` and
`.../messaging/units`; co-located unit tests and the existing integration test
were edited to deliver failing-first evidence and 100% coverage (same precedent
as `.agent/pr-notes/AUD-20261119-01.md`).

## Requirement IDs

MSG-01 (validate patient, destination and context on the server at connection
time and every protected action; block arbitrary directory contact), MSG-02
(conversation lifecycle), MSG-07 (revocation stops messaging), ARC-07 (one
conversation per verified connection and unit), ARC-02 (tenant scoping), OPS-02
(audit of denials). Classification: safety-critical authorization, so negative
and failure tests were written before production code.

## Defect confirmed red first

`ConversationService.open` validated only `subject == patientSubject` and the DB
`patient_link_active` fold for a patient caller. `connectionId` was format-checked
and persisted verbatim; `unitKey` was free to name any of the five units.
`CareUnitDirectoryService.requireActiveConnection` (the authoritative
patient+unit active-connection check) had zero callers in `main`.

True HTTP-level red against unchanged production code, on local PostgreSQL with
synthetic two-tenant fixtures:

- `SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest='ConversationFlowTest#patientCannotOpenWithAConnectionNotBoundToThem+patientCannotOpenWithAUnitTheyHaveNoConnectionTo' -DfailIfNoSpecifiedTests=false test`
  → **exit 1, 2 failures**: both returned `201 CREATED` with a persisted
  conversation (`connectionId: "forged-conn"` and `unitKey: "pharmacy"`) where
  `403 FORBIDDEN` was expected. Evidence source: executed tool command.

## Acceptance mapping

| ID | Acceptance | Evidence (tool unless stated) |
|---|---|---|
| A1 | A patient cannot open with a connection id that is not bound to them → 403, no row | `ConversationFlowTest.patientCannotOpenWithAConnectionNotBoundToThem` (red→green); `ConversationServiceTest.patientOpenIsDeniedWhenNoActiveConnectionBindsThePatientToTheUnit` |
| A2 | A patient cannot open a unit they have no connection to, even with a valid connection for another unit → 403 | `ConversationFlowTest.patientCannotOpenWithAUnitTheyHaveNoConnectionTo` (red→green); `CareUnitDirectoryServiceTest.requireActiveDeniesAConnectionUsedForTheWrongUnitKind` |
| A3 | The exact named connection must be ACTIVE, owned by the verified chart and mapped to the expected unit; unknown, other-chart, revoked, missing-unit and inactive-unit connections all deny uniformly (403 `FORBIDDEN`, one message) | `CareUnitDirectoryServiceTest.requireActiveDeniesAnUnknownConnection`, `...AConnectionOwnedByAnotherChart`, `...ARevokedConnection`, `...ConnectionsWhoseUnitIsMissingOrInactive`, `...AConnectionUsedForTheWrongUnitKind` |
| A4 | The conversation persists server-derived patient/unit/connection identities, not the client's named values | `ConversationServiceTest.patientOpenPersistsTheServerDerivedConnectionIdentities` (verifier returns `server-conn`; insertConversation asserted with it) |
| A5 | A revoked connection stops the next patient reply, including reopen of a resolved thread → 403, no message/state write | `ConversationServiceTest.patientReplyIsDeniedWhenTheConnectionWasRevoked`; `CareUnitDirectoryService.requireActive` called from `ConversationService.reply` |
| A6 | Staff open/reply are unchanged: unit role eligibility still applies and the patient connection verifier is never consulted | `ConversationServiceTest.staffOpenNeverConsultsThePatientConnectionVerifier` (`verifyNoInteractions`); existing staff open/reply/assign tests pass; `CONNECTION_USE` remains patient-only |
| A7 | Concealment order preserved: a foreign subject is denied before any existence or connection lookup | `ConversationServiceTest.openConcealsAnotherSubjectWithoutProbingPatientExistence` extended with `verifyNoInteractions(connections)` |
| A8 | Connection verification reuses the existing authoritative rule instead of duplicating it; denials are audited | `CareUnitDirectoryService.requireActive` delegates to existing `requireActiveConnection`; `requireActiveDeniesAnUnknownConnection` asserts `CONNECTION_USE/DENIED` in the messaging audit |
| A9 | Bounded inputs and fail-closed context: invalid unit key → 422, missing context → 401 | `CareUnitDirectoryServiceTest.requireActiveRejectsAnUnknownUnitKeyBeforeAnyLookup`, `requireActiveFailsClosedWithoutTenantContext`; `MessagingDomainTest.careUnitKindIsBoundedToTheApprovedUnitKeys` |
| A10 | Tenant isolation: identical local patient/connection ids across two orgs stay bound to their own org | `ConversationFlowTest` seeds both orgs with the same connection ids; existing `preconditionsValidationAndTenantIsolation`, `directFoldAssertionsConfirmVerifiedLinkAndForcedRls` unchanged; port reads all identities from `context.organizationId()` |

## Implementation and entry-point-to-test trace

Production edits (all inside the two declared scope directories):

- `messaging/conversations/PatientConnectionVerifier.java` (new) — consumer-owned
  port and `VerifiedConnection(patientId, connectionId, unitKey)` record. Defined
  in conversations so the dependency is one-way `units → conversations` and no
  package cycle is introduced.
- `messaging/conversations/ConversationService.java` — 5th constructor
  dependency; `open()` resolves the connection for patient callers after the
  existing subject/link checks and persists the returned identities; `reply()`
  re-resolves it from the stored `patientId/unitKey/connectionId` for patient
  callers before any write.
- `messaging/conversations/ConversationContract.java` — Javadoc only: the open
  request's `unitKey` and `connectionId` are now documented as server-checked.
- `messaging/units/MessagingContract.java` — `CareUnit` gains a bounded `kind`
  discriminator validated with the authoritative `ConversationContract.UnitKey.from`,
  so the five keys are declared once.
- `messaging/units/CareUnitDirectoryService.java` — implements the port;
  `requireActive` looks up the exact connection by id, requires ACTIVE +
  chart ownership, resolves its unit and requires active + matching `kind`,
  then delegates to the pre-existing `requireActiveConnection` for membership,
  verified-chart and active-binding checks. Every failure uses one uniform
  403 `FORBIDDEN` message so link/connection state cannot be probed.

Trace:
`POST /api/v1/conversations` → `ConversationController.open` →
`ConversationService.open` (guarded `TransactionTemplate`, membership/role from
DB folds) → `PatientConnectionVerifier.requireActive` →
`CareUnitDirectoryService.requireActive` (`MessagingStore.findConnection` /
`findUnit` / verified charts, all tenant-keyed, `CONNECTION_USE` authorization +
DENIED audit) → existing `requireActiveConnection` → `ConversationRepository.insertConversation`
(forced RLS) → append-only event + DB audit → 201.
`POST /api/v1/conversations/{id}/reply` → `ConversationService.reply` →
`requireAccess` → `patientLinkActive` → the same port call for patients →
message append + state update. Unit trace: `ConversationServiceTest`
(Mockito port, fixed inputs) and `CareUnitDirectoryServiceTest` (synthetic
in-memory store). HTTP trace: `ConversationFlowTest` on real PostgreSQL with
two synthetic tenants.

## Exact commands and observed results

Evidence source: executed tool commands unless stated otherwise. All from the
current checkout on branch `swarm/AUD-20261119-02-220`, HEAD `9bd064d`.

1. Baseline fast tier before changes — `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → exit 0; 1825 tests, 0 failures/errors.
2. Red (above) — 2 HTTP integration tests, 2 failures (`201 CREATED` instead of `403`).
3. Green fast tier after implementation — clean `surefire-reports`, then `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → **exit 0; 1838 tests, 0 failures, 0 errors, 0 skipped** (13 net new tests).
4. Targeted integration green — same two new tests with `SMARTCLINIC_PG_ADMIN=postgres` and no `fast` profile → **exit 0; 2 tests, 0 failures/errors**.
5. Full `ConversationFlowTest` — 10 tests, 2 failures / 3 errors; every failure is a pre-existing DLV-02 defect unrelated to this story (`HttpStatus.UNPROCESSABLE_ENTITY` vs `UNPROCESSABLE_CONTENT` at two assertions, a `JsonNode.get(...)` null on a missing response field, and `uuid = character varying` in a direct SQL assertion). The same 5 failures were reproduced on the unchanged baseline before any edit; my two new tests pass inside this class and no new failure was introduced.
6. JaCoCo from the fast-tier exec (`mvn -q -B -f apps/api/pom.xml -Pno-security jacoco:report`, XML inspected): `ConversationService` 1004/1004 instructions, 62/62 branches, 172/172 lines, 39/39 methods; `CareUnitDirectoryService` 813/813, 64/64, 135/135, 28/28; `PatientConnectionVerifier` 12/12, 1/1 line, 1/1 method; `MessagingContract` 288/288, 8/8 branches, 39/39 lines, 15/15 methods; `ConversationContract` 496/496, 20/20 branches, 74/74 lines, 30/30 methods. All 100%.
7. Static analysis — `mvn -B -f apps/api/pom.xml compile spotbugs:check` → exit 0, `BugInstance size is 0`. OWASP was not re-run: dependencies and `pom.xml` are unchanged, so the CVE disposition is unchanged.
8. Policy — `python3 -I scripts/check-docs.py`, `python3 -I scripts/check-secrets.py`, `python3 -I scripts/check-migrations.py --require-baseline` → each exit 0. No migration was added or changed (no reserved version in this story).
9. Java conventions — `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` → 2 tests OK.

## Decisions and rationale

- Kept the port in `conversations` and the implementation in `units` (one-way
  dependency), following the bounded Senior Backend review (simulated reviewer;
  code verified by this agent). Returning a conversations-owned record avoids a
  `conversations ↔ units` package cycle.
- Verify the **exact connection id**, not merely "some active connection of the
  right kind", following the Senior Spring Security review's HIGH finding:
  connections are keyed per (patient, unit, reference), so a same-kind second
  connection must not keep a revoked thread alive. The service persists the
  port's returned identities.
- Applied the check **only to patient callers**. `CONNECTION_USE` is
  patient-only in `MessagingActions`; a staff path would 403. MSG-02 still
  permits staff to open, and staff eligibility stays in `ConversationActions`.
  Both specialists flagged a staff-reply-after-revocation gap; it is recorded
  as residual below rather than broadening `CONNECTION_USE` outside this story.
- Collapsed every connection-verification failure into one 403 `FORBIDDEN` with
  one message, and checked it after the existing subject/link checks, so the new
  check adds no new distinguishability for a foreign subject.
- Reused `requireActiveConnection` rather than duplicating the active-binding
  rule; `kind` is validated through `UnitKey.from` so the five keys are not
  re-declared.
- No migration or schema change: the MSG-01 connection directory is the
  repository's existing synthetic in-memory adapter. This story makes the
  check real but does not claim production-grade durability (see residual).

## Defects and environment notes

- The reported authorization defect is confirmed fixed; regression tests added.
- `ConversationFlowTest` is already red on this branch for pre-existing DLV-02
  reasons; no attempt was made to weaken, skip, delete or re-tag those tests.
- `bash scripts/docs.sh` → exit 1, "Install Doxygen to build the developer
  reference." Doxygen is absent from this worker (`command -v doxygen` →
  MISSING), an environment limitation, not a code failure. New public Javadoc
  was written with complete `@param`/`@return`/`@throws`; Java conventions
  guard and SpotBugs are green. The integrator's image installs Doxygen.
- `python3 -I scripts/gen-opencode.py --check` and `scripts/check-pack.py` exit 1
  here: `git ls-files -v opencode.json` → `S` (skip-worktree), worktree hash
  `279aa1e7…` equals `.github/opencode.ci.json`, `git show HEAD:opencode.json`
  is `14bba0da…` and `git diff opencode.json` is empty. This is the documented
  OPS-CW-003-01 CI-profile measurement artifact, not a stale committed file;
  no config was regenerated or committed.

## Scope and evidence disclosure

Production edits are confined to the two declared scope directories. Test
edits are limited to the conversation and units test packages. No tracker,
`.github/`, `scripts/`, migration, `contracts/` or OpenAPI file was changed.

Evidence provenance: executed tool commands (Maven, JaCoCo XML, SpotBugs, git,
policy scripts) for all results; two bounded simulated specialist reviewers for
the design critique (Spring Security and Backend), with their code suggestions
verified by this agent; no simulated-user or human/clinical participant
evidence. All fixtures are synthetic; no patient data or credentials were used.

## Residual / not verified

- **Authoritative connection store**: the MSG-01 directory remains the
  repository's in-memory `InMemoryMessagingStore`; it is still not the durable,
  RLS-protected authoritative store (its own Javadoc says so). This fix makes
  the check real for synthetic flows but a DB-backed connection store with a
  reserved migration is required before real-patient enablement. Marking this
  story synthetic-only is honest; it does not by itself satisfy G3/G5.
- **Staff reply after revocation**: `reply()` verifies the connection only for
  patient callers, so a staff reply on a thread whose patient connection was
  revoked is still permitted (pre-existing behaviour, not worsened). Closing
  this needs a staff-permitted verification path and a product/clinical
  decision on MSG-07 semantics; out of this story's scope.
- **Durable audit of a connection denial**: the port's DENIED fact is appended
  to the in-memory messaging audit. A denial thrown inside the guarded
  transaction rolls back, so no `AuditAppender` DB row is committed for that
  specific denial — this is the pre-existing pattern for all in-work
  `notFound()/forbidden()` throws in `ConversationService`, not introduced here.
  Restructuring `execute`'s `Sink` to cover it is a follow-up.
- **Cross-store TOCTOU**: the in-memory check and the DB write are not atomic;
  a revocation racing the commit is accepted for synthetic operation.
- `get()/inbox()/markRead()` do not re-check connection state; the patient
  linkage-dispute scenario remains a separate follow-on.
- Doxygen/TypeDoc reference and the full OWASP scan were not run locally
  (environment); the integrator runs the full `scripts/check.sh` gate.
