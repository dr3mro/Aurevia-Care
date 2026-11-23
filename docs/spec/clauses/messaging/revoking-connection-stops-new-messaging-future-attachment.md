# MSG-07 clause 4 — API test evidence

## Clause

Revoking a connection stops new messaging and future attachment access through that connection.

## Status

Partly proven

## Evidence

Test class: `com.aurevia.smartclinic.messaging.clauses.RevokingConnectionStopsNewMessagingFutureAttachmentTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/messaging/clauses/RevokingConnectionStopsNewMessagingFutureAttachmentTest.java`).
All eight tests are active; none are skipped. Fixtures are synthetic tenants,
subjects, identifiers and a fixed clock over `InMemoryMessagingGovernanceStore`.

Service-level rules (direct calls into `MessagingGovernanceService`):

- `revokingAConnectionBlocksNewMessagingThroughTheGovernanceGuard` — the same
  connection is first allowed (`SEND`/`SUCCESS`, `unitId = UNIT`) and then
  re-seeded as revoked; the next `requireMessagingAllowed` throws HTTP 403
  `MESSAGING_REVOKED` and appends `SEND`/`DENIED` with `unitId = SYSTEM`.
- `revokingAConnectionBlocksFutureAttachmentAccessThroughTheGovernanceGuard` —
  the same pattern for `requireAttachmentAccess`: allowed (`DOWNLOAD`/`SUCCESS`)
  then refused with 403 `ATTACHMENT_ACCESS_REVOKED` and `DOWNLOAD`/`DENIED`.
- `aPatientCannotRecordRevocationEffectsForAConnection` — wrong role: a
  `patient` subject cannot perform the `tenant_admin`-only `RESTRICT` command
  (403 `FORBIDDEN`, `RESTRICT`/`DENIED`, no effect stored).
- `anotherTenantCannotSeeOrRevokeThisConnection` — other tenant: `admin` in the
  foreign organization gets 404 `NOT_FOUND` on `recordRevocation` (no effect
  stored, no cross-tenant audit bleed); `other-clinician` there gets 404 for
  `requireMessagingAllowed` and `requireAttachmentAccess` on the organization's
  connection and on a missing identifier.
- `aPrivacyAuditorCannotUseTheMessagingOrAttachmentGuards` and
  `anInactiveSubjectCannotUseTheRevocationGuards` — wrong role and inactive
  membership on the `SEND`/`DOWNLOAD` guards (403 `FORBIDDEN`, with denial facts).
- `missingTenantContextFailsClosedForBothGuards` — no ambient context yields 401.

HTTP-level proof (the wired revocation route through `MockMvc` with
`TenantContextInterceptor` and `ApiErrorHandler`):

- `revocationEffectsAreRecordedThroughTheGovernanceHttpRoute` —
  `POST /api/v1/messaging-governance/connections/{id}/revocation-effects`
  returns `messagingBlocked = true`, `attachmentAccessBlocked = true`,
  `priorConversationsReadable = true`, `recordAccessBasis = policy-v1` and a
  `restrict`/`success` audit fact; an active connection returns 409
  `CONNECTION_ACTIVE`; the foreign tenant returns 404 `NOT_FOUND`. This is the
  entry-point-to-test trace: `MessagingGovernanceController.recordRevocation`
  (`:141-144`) → `MessagingGovernanceService.recordRevocation` (`:324-342`) →
  appended audit and `messaging_revocation_effect` fact.

Production classes exercised by the tests: `MessagingGovernanceService` (guards
at `:352-365` and `:378-391`, `authorize` at `:478-487`, `deny` at `:489-495`,
`recordRevocation` at `:324-342`), `MessagingGovernanceActions` (role matrix
`:26-43`), `MessagingGovernanceController` (`:141-144`) and the request pipeline
`TenantContextInterceptor`/`ApiErrorHandler`.

Supporting production code identified as evidence but **not** called by this
test class: the revoke write is `CareUnitDirectoryService.revoke` (`:186-207`) →
`JdbcMessagingStore.revokeConnection` (`:211-218`) over
`db/migrations/V60__care_units.sql:42-60,76-96` (`state`, `revoked_at`, RLS and
FORCE RLS); the recorded effect is
`db/migrations/V62__messaging_governance.sql:146-159,161-192`. New messaging is
also enforced on the conversation path by `ConversationService.reply`
(`:158-167`) via `PatientConnectionVerifier.requireActive` (`:43`), which
re-resolves the connection on every patient reply. The governance
`requireMessagingAllowed` guard currently has no production caller.

## Gap

"Future attachment access" is recorded but not yet enforced on the actual
download path: `AttachmentService.download` (`:218-230`) authorizes, checks
version and publication state and verifies participation, but never loads the
connection and never calls `MessagingGovernanceService.requireAttachmentAccess`
(`:352-365`). That guard has no production caller and
`MessagingGovernanceController` (`:57,68,79,93,104,115,128,141,152`) exposes no
route that would invoke it. `recordRevocation` (`:324-342`) persists
`messagingBlocked` and `attachmentAccessBlocked` as audit facts; a fact alone
does not block a request. Both governance routes are already baselined as
missing from `contracts/openapi.yaml` in `contracts/openapi/missing-routes.txt`
(`:244-250`), which this task cites and does not edit.

Closing the gap would change `AttachmentService.download` (bind the guard to
the connection stored on the attachment and propagate the 403), add a mediated
attachment-access route to `MessagingGovernanceController`, then publish that
route in `contracts/openapi.yaml` and regenerate the client. That is two
separate tasks: (1) enforce and prove the download refusal through HTTP, and
(2) publish and regenerate the contract. No production code, existing test,
contract or migration was changed for this evidence-only story.

The enable-ready HTTP acceptance for task (1), written here rather than as an
annotated `@Disabled` test because `scripts/check-constraints.py:56` rejects any
added `@Disabled` line in a change (see the worker notes under Findings), is:

```java
@Test
void revocationStopsAttachmentDownloadThroughHttp() throws Exception {
    // Seed a published attachment bound to REVOKED_CONNECTION (a synthetic UUID
    // constant) and a clinician grant; ATTACHMENT_ID is its synthetic resource id.
    // headers(...) binds X-Synthetic-Subject "clinician" and X-Active-Organization ORG.
    mvc.perform(headers(get("/api/v1/messaging-governance/connections/"
            + REVOKED_CONNECTION + "/attachments/" + ATTACHMENT_ID)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ATTACHMENT_ACCESS_REVOKED"));
    mvc.perform(headers(get("/api/v1/messaging-governance/audit")))
        .andExpect(jsonPath("$[?(@.action == 'download' && @.outcome == 'denied')]").exists());
}
```

The probe route above is illustrative; a later task should fix the exact path
when it adds the controller operation, and must add the OpenAPI entry with a
tag, an `Idempotency-Key` header if the verb is a POST, and regenerated client
types in the same change.
