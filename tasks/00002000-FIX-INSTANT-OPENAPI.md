- [x] FIX-INSTANT-OPENAPI · Document the instant-messaging API in the OpenAPI contract (HIGH PRIORITY: Delivery fails on PR #1497, SMSG-03)
  - pr: #1297
  - attempts: 1
  - priority: high
  - requirements: MSG-02, ARC-07, WEB-05
  - scope: contracts/openapi.yaml, contracts/openapi, apps/staff-web/src/api/generated/openapi.d.ts, apps/api/src/main/java/com/aurevia/smartclinic/staffmsg/instant, apps/api/src/test/java/com/aurevia/smartclinic/staffmsg/instant
  - details:
    Historical brief. Use the recorded evidence and PR to locate the delivered change before starting a follow-up.
    Current reference points to verify against that delivery are `apps/api/src/main/java/com/aurevia/smartclinic/staffmsg/instant/InstantMessagingContract.java`, `apps/api/src/main/java/com/aurevia/smartclinic/staffmsg/instant/InstantMessagingService.java`.

    Problem: Delivery "Java and database verification" on SMSG-03 (PR #1497) ran 4108 tests and 4 failed, all OpenAPI contract tests, because the `/api/v1/staff/instant/*` routes are not correctly in `contracts/openapi.yaml`. Work on the SMSG-03 branch of PR #1497 when it is still open; otherwise on top of main.
    1. `OpenApiContractRulesTest.committedStaffApiContractSatisfiesEveryRule`: give every instant operation a tag, and declare the `Idempotency-Key` header on every POST and DELETE (`/staff/instant/direct`, `/groups`, `/participants`, `/{conversationId}/participants/{subject}`, `/read`). The two GETs `/{conversationId}/messages` and `/conversations`, and `/unread`, only need the tag.
    2. `OpenApiPaginationRulesTest` (two tests): `listInstantMessages`, `listInstantUnreadCounts` and `listInstantConversations` are list GETs. Give each the query parameters `cursor` and `limit` and a `nextCursor` field in the response, and implement that in the controller, or change the response so it is not a list. Do not exempt them; the only allowed exemption is `listCatalogFamily`.
    3. `OpenApiRouteParityTest.everyControllerRouteIsDocumentedOrBaselined`: document every instant route from its controller so none is missing from the contract. Do not add instant routes to `contracts/openapi/missing-routes.txt`.
    4. Regenerate staff web types with `npm --prefix apps/staff-web run gen:api` and confirm `npm --prefix apps/staff-web run gen:api:check` passes.
    Acceptance: `OpenApiContractRulesTest`, `OpenApiPaginationRulesTest` and `OpenApiRouteParityTest` pass, and the existing instant-messaging tests still pass (retried send makes one message, per-person unread counts).
    Before you start read the "Instructions for every agent" at the top of TODO.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.
