# XCH-05 clause 2 — attempts, recipient, required read-back, acknowledgment and follow-up are separate events

## Clause

Track attempts, recipient, read-back where required, acknowledgment and follow-up as separate events.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.exchange.clauses.TrackAttemptsRecipientReadBackRequiredAcknowledgmentTest`
([TrackAttemptsRecipientReadBackRequiredAcknowledgmentTest.java](../../../../apps/api/src/test/java/com/aurevia/smartclinic/exchange/clauses/TrackAttemptsRecipientReadBackRequiredAcknowledgmentTest.java)).
It is a synthetic, database-free clause test in the `clauses` style of
[TransferHandoffRequiresActiveAssigneeTest.java](../scheduling/transfer-handoff-requires-active-assignee.md):
mocked repository and audit appender, a transaction template that runs the real
guarded callback, `Clock.fixed` and a synthetic tenant context. Every case enters
through a command on `CriticalResultService`, the domain service the
critical-result controller injects. Each fact is asserted on its own append call,
with the recipient, channel and outcome matched exactly, so a merged, duplicated
or misdirected event cannot satisfy the clause.

- `eachCriticalFactIsAppendedAsItsOwnEvent` — a fully verified result records two
  escalation attempts (one delivered, one failed, both with recipient `BACKUP` and
  channel `ONCALL_PHONE`), a verbatim read-back, a clinician acknowledgment bound
  to revision 3 and a follow-up bound to revision 5. Eight distinct `appendEvent`
  calls are asserted: `REGISTERED`, `OWNER_ASSIGNED`, `VERIFIED`, two
  `ESCALATION_ATTEMPT`, `READ_BACK`, `ACKNOWLEDGED` and `FOLLOW_UP`. The states
  advance `UNVERIFIED` to `AWAITING_ACK` to `ACKNOWLEDGED` to `CLOSED`, while each
  attempt leaves the state unchanged (an attempt is a fact, not a state change).
- `acknowledgeIsRefusedUntilTheRequiredReadBackIsRecorded` — even after a
  delivered attempt, acknowledgment is refused with 409 `READ_BACK_REQUIRED` and
  no `ACKNOWLEDGED` event is written.
- `readBackIsRefusedWhenItWasNotRequired` — a result registered without the
  read-back requirement refuses one with 409 `READ_BACK_NOT_REQUIRED`.
- `readBackIsRefusedWithoutADeliveredAttempt` — a read-back with no delivered
  attempt is refused with 409 `NO_DELIVERED_ATTEMPT`.
- `readBackIsRefusedWhenRecordedTwice` — after one successful read-back, a second
  is refused with 409 `ALREADY_READ_BACK` and the ledger still holds exactly one
  `READ_BACK` event.
- `releaseNeverSatisfiesClinicianAcknowledgment` — with no delivered attempt,
  acknowledgment is refused with 409 `NO_DELIVERED_ATTEMPT` both before and after
  a patient release; the release appends only `PATIENT_RELEASED` and leaves the
  state `AWAITING_ACK`. Once a delivery exists, acknowledgment still owes the
  required read-back.
- `acknowledgeIsRefusedForARoleWithoutPermission` — a caller holding only
  `reception` is refused with 403 `FORBIDDEN` and writes no acknowledgment.
- `staleVersionIsRefused` — an acknowledgment against a stale revision is refused
  with 412 `STALE_VERSION`.
- `followUpIsRefusedBeforeAcknowledgment` — follow-up before acknowledgment is
  refused with 409 `INVALID_TRANSITION` and writes no `FOLLOW_UP` event.

Production classes and files surveyed and exercised by the entry point:

- [CriticalResultService.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/exchange/critical/CriticalResultService.java)
  — `escalate`, `readBack`, `acknowledge`, `followUp` and `release` each append one
  dedicated immutable event inside the tenant-guarded transaction; delivery and
  patient release never advance the acknowledgment state.
- [CriticalContract.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/exchange/critical/CriticalContract.java)
  — `EventType` defines `ESCALATION_ATTEMPT`, `READ_BACK`, `ACKNOWLEDGED`,
  `FOLLOW_UP` and `PATIENT_RELEASED` as distinct facts; `Result` carries the
  recipient and channel fields and the `readBackRequired` policy.
- [CriticalActions.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/exchange/critical/CriticalActions.java)
  — the bounded action matrix that reserves acknowledgment and read-back for an
  active clinician and release for a tenant administrator.
- [CriticalResultController.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/exchange/critical/CriticalResultController.java)
  — `POST /api/v1/critical-results/{id}/{escalate,read-back,acknowledge,follow-up,release}`
  are the HTTP entry points; the controller injects the service.
- [V23__critical_results.sql](../../../../db/migrations/V23__critical_results.sql)
  — creates `public.critical_result` and the append-only `public.critical_result_event`
  ledger with FORCE ROW LEVEL SECURITY, an append-only grant and triggers that
  block update, delete and truncate, so the separate facts survive a restart.

Broader coverage already present (not repeated here): the HTTP-plus-PostgreSQL
path in [CriticalResultFlowTest.java](../../../../apps/api/src/test/java/com/aurevia/smartclinic/it/exchangeresult/CriticalResultFlowTest.java)
`fullWorkflowKeepsEveryCriticalFactSeparate` asserts the seven ordered events and
their actor and revision binding; `deliveryAndPatientReleaseNeverAcknowledge`
covers the same separation at the transport boundary.
[CriticalResultControllerHttpTest.java](../../../../apps/api/src/test/java/com/aurevia/smartclinic/it/exchangeresult/CriticalResultControllerHttpTest.java)
reaches each route over HTTP.

Verified locally: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=TrackAttemptsRecipientReadBackRequiredAcknowledgmentTest test`
— `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`.

## Gap

None.
