- [-] CONVERSATION-INBOX-ACTION · Wire the patient conversation inbox open action
  - agent: orca
  - attempts: 0
  - requirements: MSG-02, MSG-03, PAT-02
  - scope: apps/patient-mobile/src/api/transport.ts, apps/patient-mobile/src/api/transport.test.ts, apps/patient-mobile/src/app/patientPages.tsx, apps/patient-mobile/src/app/providers.ts, apps/patient-mobile/src/app/patientPages.test.tsx, apps/patient-mobile/src/app/providers.test.ts, tasks/01358000-CONVERSATION-INBOX-ACTION.md
  - details:
    AUD-20261122-01: `ConversationInboxPage` passes `onOpen={placeholderAction}`
    (patientPages.tsx:529) while `loadConversations` already works. Deployed API
    has GET `/api/v1/conversations/{id}` (ConversationDetail) and POST
    `/api/v1/conversations/{id}/read` with IfMatch/RetryKey.

    Required plan-review corrections (adopted):
    - Extend the shared `Transport.post(path, body?, headers?)` seam (and its
      tests) so mark-read can send exact `If-Match: "<detail version>"` and
      `Idempotency-Key`; a body-only POST gets 428 from the deployed controller.
      Land this seam and its failing contract test FIRST.
    - Version comes only from the detail `ConversationRecord.version`; the badge
      is cleared from the read reply's authoritative `patientReadSequence`, with
      a test that it drops to zero on success and is unchanged on 412/404/403.
    - Named negative tests: double press issues one GET and one POST via a
      synchronous claim guard; 401 on GET and POST; 412 stale; 404/403;
      malformed detail and read payloads; foreign patient id; second selection
      while pending; no POST from the unwired reply composer.
    - Phone detail view needs a host-rendered accessible back control using
      existing `screenText(locale,'back')`, with a test returning to the inbox.
    - Reply wiring remains explicitly out of scope: pass the existing
      placeholder action and assert no POST on send.
    - Synthetic detail/read fixtures use invented message text; no patient data.
    - Targeted inner loop: the two Jest files, then `npm run typecheck`, then
      `bash apps/patient-mobile/check.sh` with 100% changed-source coverage.

    Bounded plan: add typed `loadConversationDetail` and `markConversationRead`
    provider functions with runtime validation, then wire page state so pressing
    a row selects that conversation, loads its thread once, marks it read at the
    detail version and updates the unread badge. Compose the existing
    `ConversationInbox`/`ConversationThread` on phone and large windows. No new
    dependency, no free-text error, no PHI in fixtures. Review verdicts: code-reviewer GO, coverage-auditor GO, devsecops GO.
    Reviewers and exact-SHA CI before done.
