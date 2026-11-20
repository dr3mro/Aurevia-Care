# AUD-20261119-02 — Verify the patient-unit connection when opening a conversation

Story source: daily audit. Worker: swarm slot for `AUD-20261119-02`. Branch `swarm/AUD-20261119-02-216`, base `34a1bd8`. Synthetic fixtures only. No shared tracker edited.

## Requirement IDs

Primary **MSG-01**, **MSG-02**, **MSG-07**, **ARC-07**. Supporting: IAM-05, ARC-02, ARC-04, OPS-02. Specification: `SMARTCLINIC_V1_SPEC.md` §§2, 6.1, 7, 11.

## Defect reproduced from the story (root cause)

`ConversationService.open` accepted `connectionId` from the JSON body, shape-validated it with `ConversationInputs.opaqueId`, and persisted it verbatim. For a patient caller the only relationship check was `ConversationRepository.patientLinkActive` (the IAM-05 patient↔chart fold); no check proved that an active `PatientUnitConnection` bound that chart to the named `unitKey`. `CareUnitDirectoryService.requireActiveConnection` already implemented such a check but had no caller in `main`. A verified patient could open a thread with any of the five units by naming the unit key, and a forged/revoked/foreign connection id was stored unchallenged. `reply()` likewise never re-checked connection state, so a revoked connection still permitted new messages.

## Fix (smallest reviewable slice)

1. `CareUnit` now declares an optional tenant-scoped `unitKey` (with a backward-compatible 6-arg constructor). The conversation unit is derived from the connection's recipient care unit **through the tenant directory**, never from a global id map or the client.
2. `CareUnitDirectoryService` gains `requireActiveConnectionById` (patient self-service: reuses `requireActiveConnection` for membership/role/verified-chart/active-connection) and `requireUsableConnection` (staff action: same ownership, state and unit checks without the patient-only role gate). Both resolve inside `TenantContext.organizationId()`, conceal a missing or foreign connection as 404, deny a revoked connection as 403, and require an active care unit with a conversation category. The reused `deny()` path records a non-sensitive `CONNECTION_USE` audit fact.
3. `ConversationService.open` resolves the connection **after** subject/patient/link checks and staff unit eligibility, derives the unit from the tenant directory, rejects a client unit that differs from the bound unit (403), and persists the server-derived unit and authoritative connection id. `reply` re-resolves the stored connection on every message from either side, so revocation or a stale route stops new messaging.
4. New `ConversationUnitResolver.fromDirectory` converts the tenant directory value into the bounded `UnitKey` vocabulary and rejects anything else (403).

Ordering choices: subject mismatch → 404 (no existence probe) → patient 404 → verified-link 404 → staff unit 403 → connection 404 (concealed) / 403 (state) → derived-unit mismatch 403 → write. Zero writes on every denial.

## Acceptance mapping

| # | Acceptance | Evidence (test) |
|---|---|---|
| A1 | Patient cannot open a unit without an active verified connection | `ConversationServiceTest.patientOpenWithoutAnyVerifiedConnectionIsDenied` → 404, no insert |
| A2 | ConnectionId not bound to caller is rejected | `patientOpenWithAConnectionBoundToAnotherPatientIsDenied` → 404; `CareUnitDirectoryServiceTest.requireActiveConnectionByIdConcealsAnotherPatientsConnection` |
| A3 | Wrong-unit connection cannot open a thread | `patientOpenWithAConnectionToAnotherUnitIsDenied` → 403, no insert |
| A4 | Revoked connection cannot open | `patientOpenWithARevokedConnectionIsDenied` → 403 |
| A5 | Malformed connection id fails before any write | `patientOpenWithAMalformedConnectionIdIsRejectedBeforeAnyWrite` → 422 |
| A6 | Server derives and persists the connection's unit and id | `patientOpenCreatesAwaitingStaffConversationAndWorkItem` (verifies `"lab"` and the connection UUID in `insertConversation`) |
| A7 | Every reply re-verifies the connection (both sides) | `patientReplyAfterTheConnectionWasRevokedIsDenied`, `patientReplyRejectsAConnectionBoundToAnotherUnit`, `CareUnitDirectoryServiceTest.requireUsableConnectionAllowsAStaffActionButDeniesInactiveMembership` |
| A8 | Tenant-scoped resolution: missing/foreign connection and missing unit concealed | `requireActiveConnectionByIdConcealsAnUnknownConnection`, `...ConcealsAMissingRecipientCareUnit`, `...DeniesAUnitWithoutAConversationCategoryOrAnInactiveUnit` |
| A9 | Patient-side membership, verified chart and link still enforced | `requireActiveConnectionByIdDeniesInactiveMembershipAndUnlinkedChart` |
| A10 | Unit key vocabulary stays bounded | `ConversationUnitResolverTest.everyDirectoryUnitKeyIsAccepted`, `anUnknownOrMissingDirectoryUnitKeyIsRejected` |

## Entry-point-to-test trace

`POST /api/v1/conversations` → `ConversationController.open` → `ConversationService.open` (subject/link checks, then `CareUnitDirectoryService.requireActiveConnectionById` / `requireUsableConnection` → `resolveConnection` tenant lookup → `requireActiveConnection` for patients → `ConversationUnitResolver.fromDirectory`) → guarded `TransactionTemplate` → `ConversationRepository.insertConversation` with server-derived unit and connection id → 201. Fast path: `ConversationServiceTest` (real `InMemoryMessagingStore` + `CareUnitDirectoryService`, mocked repository). DB/HTTP path: `ConversationFlowTest` (integrator-owned).

`POST /api/v1/conversations/{id}/reply` → `ConversationService.reply` → existing access/version/state/link checks → connection re-resolution above → sequenced insert → 200. Trace covered by `ConversationServiceTest` reply cases.

## Exact commands and results (tool evidence, executed locally)

Baseline (before edits): `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=ConversationServiceTest,ConversationContractTest,CareUnitDirectoryServiceTest,MessagingDomainTest,CareUnitControllerTest test` → exit 0.

Red: the same targeted command after adding tests → exit 1, `COMPILATION ERROR`: `ConversationUnitResolver` cannot be found, `ConversationService` constructor requires 5 args (found 4), and `requireActiveConnectionById` does not exist. This is the expected missing-behaviour red for a new API.

Green:
- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → exit 0. Surefire reports: **1797 tests, 0 failures, 0 errors**.
- Targeted: `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=ConversationServiceTest,ConversationUnitResolverTest,CareUnitDirectoryServiceTest,ConversationContractTest,MessagingDomainTest,CareUnitControllerTest test` → exit 0. Per class: ConversationServiceTest 47, CareUnitDirectoryServiceTest 44, ConversationUnitResolverTest 2, ConversationContractTest 17, MessagingDomainTest 13, CareUnitControllerTest 4.
- Coverage: `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test jacoco:report` then JaCoCo XML inspection → `ConversationService` and `CareUnitDirectoryService` both **0 missed** instructions/branches/lines/methods/classes. (Whole-bundle misses are from integration-tagged tests excluded by `-Pfast`; they are unchanged by this slice.)
- Static/security: `mvn -q -B -f apps/api/pom.xml -Psecurity -DskipTests spotbugs:check` → exit 0.
- Conventions: `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` → 2 tests OK.
- Migration gate: `python3 -I scripts/check-migrations.py --require-baseline` → exit 0 (no migration added or changed).
- Hygiene: `git diff --check` → exit 0.
- Full fast policy: `bash scripts/quick-check.sh` → exit 1 **only** at `check-pack.py` ("opencode.json is stale"). Cause is the known worker artifact, not this change: `git ls-files -v opencode.json` → flag `S` (skip-worktree), the worktree file is the 1837-line CI profile while `git show HEAD:opencode.json` is the 1777-line committed local profile (`cmp` → 1), and no agent, skill or `opencode.json` file was edited. Every other quick-check step passed when run directly: `bash -n`/`shellcheck` on the shell scripts, `project-name.py`, `check-docs.py`, `check-secrets.py`, `check-migrations.py --require-baseline`, all Python tooling tests ("ok"), and the Maven fast tier. The committed local profile is what CI/integrator checks; no regeneration was performed.

## Scope note (recorded, not silently changed)

The declared scope names the two `messaging/conversations` and `messaging/units` **main** source paths. To satisfy "failing tests first" and "add a no-connection/wrong-unit negative test", I also changed the corresponding co-located test packages (`it/messagingconversations`, `messaging/units`) and the existing `ConversationFlowTest` seeding helper so the DB/HTTP suite can still open threads with a real connection. No file outside the API messaging domain, generators, workflows, scripts or shared trackers was touched. No migration was added (no reserved version was supplied for this story).

## Decisions

- Connections are patient↔unit relationships; staff have no patient connection. Staff callers are still authorized by the existing role/unit matrix and now additionally require the thread's connection to be active and bound to that unit, so a revoked connection also stops staff messaging (MSG-07) and staff-opened threads remain repliable.
- Wrong-unit is 403 (not 404): the five unit keys are public, and a random connection id still gets a uniform 404, so no existence oracle is introduced.
- The public `CareUnit` contract gains an optional `unitKey`; the 6-arg constructor is preserved so existing directory-only callers and fixtures are unchanged. The maintainer guide contract entry for the care-unit directory is an integrator follow-up (docs are outside this scope).

## Deferred findings (owners; not blocking this synthetic slice)

1. **In-memory connection store is not authoritative (HIGH, owner: durable connection table).** There is no `care_unit` / `patient_unit_connection` table or reserved migration (V32 absent); `MessagingStore` is `InMemoryMessagingStore`. The new checks inherit that synthetic adapter limitation. A forced-RLS durable store and reconciliation with the IAM-05 DB fold must precede real-patient enablement. The DB second line of defence still stores `connection_id` as plain text with no connection FK (owner: same durable-table story).
2. **Durable denial audit (MEDIUM, owner: MSG-07/S-G3-10 hardening).** Mid-operation denials in `ConversationService` (including the new unit mismatch) throw inside the guarded transaction, so the transactional `AuditAppender` row rolls back; `CareUnitDirectoryService.deny()` writes a non-sensitive `CONNECTION_USE` fact to the in-memory store instead. This is the pre-existing systemic pattern recorded in `S-G3-05` deferred findings, not introduced here.
3. **Other patient commands (MEDIUM, owner: MSG-03/04/05 governance).** Attachment send/download and instruction acknowledgement use separate stores and do not re-resolve this connection; a single revocation is not yet proven to deny every patient command.
4. **Two verified-link sources (MEDIUM, owner: S-G0-07/S-G3-10).** `patient_link_active` (DB) and in-memory `verifiedPatientCharts` can diverge; the new path checks both but does not reconcile them.
5. **Developer reference build (environment).** `bash scripts/docs.sh` could not run locally ("Install Doxygen to build the developer reference"); the Javadoc-link convention test passes. Doxygen is integrator/CI evidence.

## Evidence sources

- **Tool (executed by this worker):** every command and result above, plus `git status`/`git diff` inspection and JaCoCo XML counters. No database, integration or full `scripts/check.sh` run locally.
- **Reviewer (simulated, read-only):** `smartclinic-software-architect` and `smartclinic-senior-spring-security` bounded design reviews. They found the global-unit-id-map and staff-delegation problems; both were fixed before the green run (unit now resolved through the tenant directory; staff use a separate safe method; connection ownership, ordering and concealment verified). Reviewers did not execute tests.
- **Simulated user / human participant:** none. No clinical or real-patient evidence; G5 sign-off remains required.

## Integrator-owned gates (not run locally, per instruction)

`bash db/check.sh`, the `@Tag("integration")` `ConversationFlowTest` (now seeds care units, connections and in-memory links/members), Flyway V1–V55, full `mvn verify` JaCoCo/SpotBugs/OWASP, `bash scripts/check.sh`, `bash scripts/integrator-gate.sh`, and `bash scripts/docs.sh` with Doxygen. `-Pfast` excludes the integration group, so **no integration result is claimed in this note**.
