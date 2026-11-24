# FIX-INSTANT-SEND-CONTRACT — Bind instant message sends to the conversation in the URL

## Summary

`InstantMessagingController.send` received the `@PathVariable conversationId` but forwarded only the request body, so `InstantMessagingService.send` authorized and wrote using the body's `conversationId`. A caller could send into a different conversation they were also allowed to access. The service now takes the route ID explicitly, rejects a body/URL mismatch with `422 INVALID_INPUT` before any store interaction, and drives access control, deduplication and the durable write from the route ID. The controller forwards the path ID; the client body shape and `Idempotency-Key` header are unchanged. The OpenAPI contract documents the binding and the generated staff types were regenerated.

## Affected requirements

MSG-02, ARC-07. The story also tags `WEB-05`, but specification `WEB-05` governs health and lifecycle routes; no health route was touched. The staff-web contract change follows `docs/WORKFLOW.md` and `tasks/_head.md` 6a.

## Risk class

Cross-boundary / safety-critical: an authorization and durable-write boundary for staff-to-patient messages.

## Review owner

Senior backend, Spring security, test engineering, code review, coverage audit, DevSecOps.

## Data changes

None. No migration, table, RLS policy, cursor or limit change. The only data-side effect is that a mismatched request now writes zero rows instead of writing into the body-named conversation.

## Authorization boundaries and audit events

The URL `conversationId` is now the sole resource selector for `requireAccess` (active staff, facility scope, active participation) and for `messageByClientId`, `sendMessage` and attachment publication, all keyed on `access.conversation().id()`. Tenant and actor remain derived from `TenantContext`; a mismatch is refused before any store call. No audit event was added or changed; this route's pre-existing lack of a publication audit/outbox fact is not claimed as closed by this fix.

## Migration and rollout considerations

None. The route, request body shape, `Idempotency-Key` header and error schema are unchanged. Existing clients that send a matching `conversationId` keep working; clients that send a different ID now receive the documented 422 instead of writing to the wrong thread.

## Test evidence

Tool evidence. Branch and base: `swarm/FIX-INSTANT-SEND-CONTRACT-1361`, HEAD `29467f47`; `git merge --no-edit origin/main` -> `Already up to date.` before work and before finishing.

Entry point to test trace: HTTP POST `/api/v1/staff/instant/{conversationId}/messages` -> `InstantMessagingController.send` -> `InstantMessagingService.send` -> `requireAccess` -> `JdbcInstantConversationStore.messageByClientId`/`sendMessage`. The HTTP test `InstantMessagingIntegrationTest#anHttpBodyConversationMismatchWritesToNeitherConversation` mounts the real controller, real service and real JDBC store on PostgreSQL and asserts both histories and both client-id keys are unchanged.

RED, before the production change (genuine behavioral failure, test compiled against the old two-argument service):

- `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security -Dtest='InstantMessagingIntegrationTest#anHttpBodyConversationMismatchWritesToNeitherConversation' -Dgroups=integration test`
- Final lines: `Tests run: 1, Failures: 1, Errors: 0, Skipped: 0`; `Status expected:<422> but was:<200>` at `InstantMessagingIntegrationTest.java:252`.

GREEN after the production change:

- `mvn -B -f apps/api/pom.xml -Pno-security -Dtest='InstantMessagingServiceTest,InstantMessagingControllerTest' test` -> `Tests run: 42, Failures: 0, Errors: 0, Skipped: 0`; `BUILD SUCCESS`.
- `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security -Dtest='InstantMessagingIntegrationTest' -Dgroups=integration test` -> `Tests run: 20, Failures: 0, Errors: 0, Skipped: 0`; `BUILD SUCCESS`.
- `mvn -B -f apps/api/pom.xml -Pno-security -Dtest='InstantAttachmentPublicationHttpTest,ConversationObjectAccessTest,TenantFacilityIsolationTest,StaffAttachmentPublicationWiringTest' test` -> `Tests run: 21, Failures: 0, Errors: 0, Skipped: 0`; `BUILD SUCCESS` (out-of-scope regression surface that sends over HTTP with matching IDs).
- `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security -Dgroups=integration -Dtest='OpenApiRouteParityTest,OpenApiRouteCoverageTest' test` -> `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`; `BUILD SUCCESS`.
- `mvn -B -f apps/api/pom.xml -Pno-security -Dtest='OpenApiContractDriftTest,OpenApiContractControllerTest,OpenApiContractHttpTest,OpenApiContractRulesTest,OpenApiErrorRulesTest,OpenApiPaginationRulesTest' test` -> `Tests run: 53, Failures: 0, Errors: 0, Skipped: 0`; `BUILD SUCCESS`.
- Fresh coverage batch (also the coverage command): `rm -f apps/api/target/jacoco.exec && SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security -Dtest='InstantMessagingServiceTest,InstantMessagingControllerTest,InstantMessagingIntegrationTest,InstantAttachmentPublicationHttpTest' test jacoco:report` -> `Tests run: 66, Failures: 0, Errors: 0, Skipped: 0`; `BUILD SUCCESS`.
- `python3 -I scripts/check-wiring.py` -> no output, exit 0 (no resolved entries, baseline unchanged).
- `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py'` -> `Ran 2 tests ... OK`.
- `python3 -I scripts/check-docs.py` and `python3 -I scripts/check-names.py` -> exit 0, no output.
- `mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check` -> exit 0, zero findings (tool evidence; no suppression added).
- `(cd apps/staff-web && npm run gen:api)` -> `gen:api: wrote src/api/generated/openapi.d.ts`; `(cd apps/staff-web && npm run gen:api:check)` -> `gen:api: generated client matches the contract`.
- `(cd apps/staff-web && npm run typecheck)` -> `tsc --noEmit -p tsconfig.json`, exit 0, no `error TS…`.
- `(cd apps/staff-web && npm run lint)` -> `eslint . --max-warnings 0`, exit 0.
- `bash apps/staff-web/check.sh` -> `gen:api:check`, `typecheck`, `lint`, `build` and the bundle-size gate all passed; the test stage reported `Test Files 1 failed | 267 passed (268)`, `Tests 1 failed | 3044 passed (3045)`. The single failure is the pre-existing WEB-10 tripwire `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:166` (`expected [ …(14) ] to deeply equal [ …(9) ]`), proved to fail identically on clean `origin/main` in a detached worktree with the same `node_modules`. No file in that test's dependency chain was changed by this story.

Known environment false positive (confirmed once, not re-diagnosed): `python3 -I -m unittest discover -s scripts/tests` -> `Ran 1432 tests`, `FAILED (failures=6)`, all six in `test_check_pack.py` (OPS-CW-003-01). Evidence: `git ls-files -v opencode.json` -> `S opencode.json`; `sha256sum opencode.json .github/opencode.ci.json` are identical; `git show HEAD:opencode.json` equals `gen_opencode.render(ci=False)` and the CI profile equals `gen_opencode.render(ci=True)`. The committed profile is correct; the worktree file is the CI runtime profile installed under `skip-worktree`. No protected file was regenerated or committed.

## Coverage

Tool evidence from `apps/api/target/site/jacoco/jacoco.xml` after the fresh batch above (no `jacoco.exec` carried in):

| Class | missed / covered |
|---|---|
| `InstantMessagingService` | INSTRUCTION 0/936, BRANCH 0/54, LINE 0/175, METHOD 0/34, CLASS 0/1 |
| `InstantMessagingController` | INSTRUCTION 0/62, LINE 0/12, METHOD 0/10, CLASS 0/1 |
| every `InstantMessagingContract*` nested record | missed 0 on all counters |

Branch-to-test map:

| New or changed branch | Test that takes it |
|---|---|
| `requireSubject(conversationId, ...)` valid side | all matching-ID sends, e.g. `InstantMessagingServiceTest#sendPublishesAnImmutableMessageWithTheRecordedRole` and `InstantMessagingIntegrationTest#aSameKeyRetryThroughTheMatchingUrlPublishesOneMessage` |
| `requireSubject(conversationId, ...)` blank/null side | `InstantMessagingServiceTest#sendRejectsMissingIdsFromEitherThePathOrTheBody` (first two assertions: null and blank) |
| `requireSubject(request.conversationId(), ...)` valid side | same matching-ID sends |
| `requireSubject(request.conversationId(), ...)` null and blank sides | `InstantMessagingServiceTest#sendRejectsMissingIdsFromEitherThePathOrTheBody` (third and fourth assertions) |
| `if (!routeId.equals(bodyId))` TRUE | `InstantMessagingServiceTest#sendRejectsAMismatchedBodyIdBeforeTouchingTheStore` (422, `INVALID_INPUT`, `verifyNoInteractions(store)`) and HTTP `InstantMessagingIntegrationTest#anHttpBodyConversationMismatchWritesToNeitherConversation` in both directions |
| `if (!routeId.equals(bodyId))` FALSE | `InstantMessagingServiceTest#aRetriedSendReturnsTheFirstMessageWithoutPublishingAgain`, `#sendPublishesAnImmutableMessageWithTheRecordedRole`, HTTP `#aSameKeyRetryThroughTheMatchingUrlPublishesOneMessage` |
| `requireAccess`, `messageByClientId`, `sendMessage` now driven by the route ID | `InstantMessagingIntegrationTest#aSameKeyRetryThroughTheMatchingUrlPublishesOneMessage`, `#oneKeyInTwoMatchingUrlsPublishesOneMessage`, `#aRetriedSendPublishesExactlyOneMessage`, plus the 20-test integration suite |
| Controller forwards the `@PathVariable` into `send` | `InstantMessagingControllerTest#sendBindsTheServiceToTheConversationInThePath` (`eq(CONVERSATION)`, `eq(request)`) and `#sendsWithTheIdempotencyHeader` |
| HTTP 422 -> `INVALID_INPUT` on this route | `InstantMessagingIntegrationTest#anHttpBodyConversationMismatchWritesToNeitherConversation` (`$.code`) |

No threshold, exclusion, skip, suppression or generated-code marker was added or lowered. The frontend generated file was regenerated by `npm run gen:api`, never hand-edited; its diff contains only the two new description comments.

## Clinical and architecture review

No clinical sign-off is required: no prescribing, critical result, release of information or live patient data is involved. All fixtures are synthetic (`nurse-a`, `nurse-b`, two UUID-shaped facilities/conversations). Senior backend and Spring security reviewers confirmed the route ID is the correct authorization subject and that all store and attachment side effects use the canonical loaded conversation ID. The narrow change does not add audit or notification outbox facts; that pre-existing ARC-07 gap is not claimed as closed.

## Brief corrections

The advisor brief names `contracts/openapi.yaml` and the generated staff types but omits the byte-identical packaged copy at `apps/api/src/main/resources/contract/openapi.yaml`. `OpenApiContractDriftTest` fails the build when that copy differs by a byte, and every recent contract change in `git log` updates all three files together. The scope line predates BUILD-23's drift gate. The copy was refreshed with `cp` and verified byte-identical with `cmp`; it is a derived mirror with no logic of its own. The plan reviewer and senior backend reviewer both required this for a green build.

## Review verdicts

- smartclinic-plan-reviewer: GO
- smartclinic-code-reviewer: GO
- smartclinic-coverage-auditor: GO
- smartclinic-devsecops: GO
- smartclinic-senior-backend (plan): design confirmed; Required findings folded in
- smartclinic-senior-spring-security: GO (authorization subject confirmed; sender-agnostic replay tracked as a task-request)
- smartclinic-test-engineer: red cases and branch map adopted

## Findings

1. **Scope deviation (resolved in code, recorded here):** the packaged contract mirror was updated although it is not in the declared scope. Rationale and evidence are under `## Brief corrections`; `OpenApiContractDriftTest` passes.
2. **Pre-existing red on `main`:** `apps/staff-web/tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:166` expects a frozen list of 9 e2e specs, but `main` has 14 after commit `4698687f` added bilingual role journeys. The identical failure was reproduced on clean `origin/main` in a detached worktree. Not caused by and not fixable inside this story's scope.
3. **Environment false positive:** six `test_check_pack.py` failures from the CI `opencode.json` profile under `skip-worktree` (OPS-CW-003-01); confirmed with `git ls-files -v`, sha256 comparison and generator round-trip. No protected file changed.
4. **Replay key is not sender-checked (out of scope):** `JdbcInstantConversationStore.messageByClientId` returns the first message for a client key regardless of sender, so a second participant reusing a key in the same conversation can receive the first participant's message. Adding a 409 would change the error contract and needs its own story; a `task-requests` block is included below.
5. **No new wiring debt:** `check-wiring.py` reported no resolved entries, so no baseline regeneration is needed from this worker.

## Lessons for workers

- Read `OpenApiContractDriftTest` before editing `contracts/openapi.yaml`: the packaged mirror at `apps/api/src/main/resources/contract/openapi.yaml` must be refreshed byte-for-byte in the same change, even when an old task scope omits it.
- An arity change makes new unit tests compile-red; capture a genuine behavioral RED first with an HTTP-level test written against the current signature, then change production and migrate callers.
- `mvn -Dgroups=integration -Dtest=...` filters out non-tagged tests; run contract unit tests in a separate invocation without that flag and confirm the Surefire count.
- Reproduce a failing frontend gate on clean `origin/main` in a detached worktree before treating it as your own defect; one stale tripwire and one environment profile accounted for every failure in this run.

## task-requests

```task-requests
[
  {
    "title": "Reject instant-message replay keys reused by a different sender",
    "details": "JdbcInstantConversationStore.messageByClientId(org, conversationId, clientId) returns the first message for a key irrespective of sender. If another active participant reuses a key already committed in the same conversation, InstantMessagingService.send returns the first sender's message and silently drops the second sender's message. Add a stable documented conflict (409) when the replayed message's senderSubject differs from TenantContext.subject(), with a service unit test and an HTTP test using two synthetic participants. No migration required. Surveyed during FIX-INSTANT-SEND-CONTRACT and left out of that narrow URL-binding fix.",
    "scope": ["apps/api/src/main/java/com/aurevia/smartclinic/staffmsg/instant", "apps/api/src/test/java/com/aurevia/smartclinic/staffmsg/instant", "contracts/openapi.yaml", "apps/api/src/main/resources/contract/openapi.yaml"],
    "depends": ["FIX-INSTANT-SEND-CONTRACT"]
  }
]
```
