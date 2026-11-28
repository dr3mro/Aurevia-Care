# START-52 · API: negative-path and tenant-isolation tests for the package `events`

PR #1818, tested branch tip a8250b6fb05a18d011c9b02a6d784ef6464c1e86.

Task: START-52

Swarm worker 3. Checks: passed. Local checks: failed. Agent result: ok.

# START-52 — API: negative-path and tenant-isolation tests for the package `events`

Tested revision: 7e3b1e1699c71dad73987dca46aaf929f88c4f9d

Branch `swarm/START-52-1747`; `git merge --no-edit origin/main` -> `Already up to date.` at the start and again after coding; working tree clean at the start. The declared scope file is absent in this snapshot (`tasks/00940000-START-52.md:10`); the senior-developer brief confirms it is the planned new file, so no maintainer blocker is claimed.

## Summary

One new test-only class at `apps/api/src/test/java/com/aurevia/events/EventsNegativePathsTest.java` (package `com.aurevia.events`) documents and locks the fail-closed failure signals of the `events` package. It reuses the `DurableEventServiceTest` fixture (`InMemoryDeliveryStore`, fixed `Clock`, `as(subject, org)`, `TenantContext.clear()`) and asserts only behaviour the production code has today. Six tests cover failure permutations that were not already asserted at the service entry point with no-write and no-audit assertions: every `publish` field validator, validation before context and role checks, oversized reasons on three delivery commands, malformed ids on four transition commands, role denial before a tenant-scoped lookup, and a cross-tenant `deadLetter` concealment that leaks no record detail. A few of these inputs also appear in HTTP clause suites, but the service-level ordering and absence of durable side effects are not asserted there. Stale-version 412, same-key replay 200/DUPLICATE and operator-publish 403 are already asserted by neighbouring tests and are deliberately not duplicated. No production code, existing test, contract, migration, dependency or tracker was changed. All fixtures are synthetic UUIDs, fixed instants and synthetic digests; no patient data or credential appears anywhere.

## Affected requirements

- ARC-02 (isolation): derive tenant from authentication and current membership, enforce service-side, fail closed when context or permission is missing. The new tests pin validation before context/role, role denial before a tenant-scoped lookup, and a peer-tenant concealment with no record detail.
- OPS-05 (operational error signals): the story explicitly assigns this ID to the auditable failure signals of this package. The tests support OPS-05 error visibility by pinning the `Outcome` stamped for 403/DENIED and 404/NOT_FOUND, and the absence of an audit fact for a 422 that fails before a tenant exists.

Requirement-to-evidence map:
- ARC-02 "input missing/malformed/too long" -> `publishRejectsInvalidFieldsWithoutWritingAnything`, `invalidPublishInputIsRejectedBeforeContextAndAuthorization`, `deliveryCommandsRejectOversizedReasonsBeforeAnyWrite`, `transitionCommandsRejectMalformedIdsBeforeAnyWrite`.
- ARC-02 "wrong active role" -> `roleDenialPrecedesLookupSoAnAuditorCannotProbeEventExistence` (403 not 404, DENIED audit).
- ARC-02 "another tenant's id looks like not found, never reveals the record" -> `crossTenantDeadLetterIsConcealedWithoutRecordDetail`.
- OPS-05 "auditable failure signal" -> every test above asserts audit absence or the exact `Outcome`.
- Already-covered and mapped, not duplicated: cross-tenant `markSent` concealment -> `DurableEventServiceTest.java:398-406` and `WorkersUseImmutableEventIdsAggregateVersionTest.java:274-288`; stale 412/STALE -> `WorkersUseImmutableEventIdsAggregateVersionTest.java:233-249`, `UseSyntheticContractFixturesIncludingDuplicatesCorrectionsTest.java:496-508`, `SendFailuresRestrictedDeadLetterQueueReviewTest.java:301-312`, `DurableEventServiceTest.java:163-176`; repeated key -> `UseIdempotencyKeysScopedTenantCallerCommandTest.java:100-118`, `DurableEventServiceTest.java:116-124`; active operator publish 403 -> `UseIdempotencyKeysScopedTenantCallerCommandTest.java:183-198`; tenant-scoped same key -> `UseIdempotencyKeysScopedTenantCallerCommandTest.java:137-153`, `DeliveryDomainTest.java:180-181`.

## Risk class

Cross-boundary evidence only (tenant and authorization boundaries), but the change itself is routine/test-only: no migration, no route, no production line. No clinical sign-off gate is opened.

## Review owner

scrum-master / product-owner accept the evidence; primary agent owns the single scoped test file.

## Data changes

None. No migration, backfill, RLS change, lock or volume impact, rollback, OpenAPI edit or generated-client regeneration.

## Authorization boundaries and audit events

No boundary or audit event is added or changed. The tests assert the existing `DurableEventService.authorize` order (active membership, then role) and `deny` audit outcomes (`DENIED`, `NOT_FOUND`) under synthetic subjects in two synthetic organizations, and that a pre-context 422 writes no audit fact.

## Migration and rollout considerations

None.

## Skills loaded

- `aurevia-delivery` — every story (delivery loop and swarm hand-off).
- `aurevia-context-optimizer` — locate the nearest existing negative/tenant tests.
- `aurevia-senior-backend` — which candidate gaps are real; reuse of the `InMemoryDeliveryStore` fixture.
- `aurevia-senior-java-build` — targeted Maven test command and the compile-first SpotBugs command.
- `aurevia-test-engineer` — design the red/green negative-path tests.
- `aurevia-plan-reviewer` — pre-coding plan review; first pass NO-GO (four duplicate tests), corrected plan re-reviewed GO.
- `aurevia-code-reviewer`, `aurevia-coverage-auditor`, `aurevia-devsecops` — post-code reviews.
- `aurevia-qa-reviewer` — post-code QA review required by AGENTS.md and `scripts/quality-gate.sh` but omitted from the brief; invoked as an agent for that reason.
- No additional skill matched the story scope.

## Brief discrepancies (carried forward, not instructions)

- The story names `docs/pr/notes/<task-id>.md`; the worker prompt names this file, and AGENTS.md says the prompt path wins. This file is the hand-off and pull-request description.
- The senior-developer brief asked for new tests for stale version and repeated idempotency key. The plan reviewer proved both are already asserted at service and HTTP entry points, so writing them would violate the story's "do not duplicate" rule. They are mapped to existing evidence above instead of being repeated. The brief is guidance, not a change to that rule.
- No maintainer channel is available in this worker. The entry point is taken from code: `DeliveryController` `/api/v1/delivery` -> `DurableEventService` -> `DeliveryStore`, with existing HTTP-level suites already proving the real wiring and forced-RLS side.
- The brief omits `qa-reviewer` from its reviewer list; AGENTS.md and `scripts/quality-gate.sh` require it, so it is invoked and recorded.
- The brief says four known Python tooling failures; this worker observed five identical pre-existing failures on the clean baseline and records the observed count.
- `scripts/worker_gate.py` still resolves notes under `docs/pr/notes/` (a known stale reference recorded in the 2026-11-26 daily report); no protected file is edited to change it.

## Entry point to test trace

`DeliveryController` `/api/v1/delivery` endpoints -> `DurableEventService` -> `DeliveryStore` interface -> `JdbcDeliveryStore` in production and `InMemoryDeliveryStore` in this unit fixture. The new class drives the real `DurableEventService` directly through its public commands (`publish`, `markSent`, `markDelivered`, `markFailed`, `retry`, `deadLetter`, `reviewDeadLetter`), which is where all validation, authorization, tenant scoping and audit decisions live. The HTTP binding and forced-RLS chain are already exercised by `WorkersUseImmutableEventIdsAggregateVersionTest`, `CommitStateAuditOutboxRecordAtomicallyApplicableTest`, `UseIdempotencyKeysScopedTenantCallerCommandTest` and `ConfirmOrderPatientIdentifiersBeforeLinkingExternalTest`; no new route or service was added, so no new HTTP integration test is required.

## Test evidence

All co
