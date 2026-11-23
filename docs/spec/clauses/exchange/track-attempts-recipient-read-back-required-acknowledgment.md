# XCH-05 clause 2 — track attempts, recipient, read-back, acknowledgment and follow-up

## Clause

Track attempts, recipient, read-back where required, acknowledgment and follow-up as separate events.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.exchange.clauses.TrackAttemptsRecipientReadBackRequiredAcknowledgmentTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/exchange/clauses/TrackAttemptsRecipientReadBackRequiredAcknowledgmentTest.java`),
a real-PostgreSQL integration test under the `synthetic-auth` profile. It uses its own
synthetic database (`smartclinic_xch05_tracking_it`, dropped after the suite), two
synthetic organizations with local identifiers for isolation, a `lab_verifier`, a
`clinician`, an active backup `clinician` and a `tenant_admin` in each, and only the
approved escalation policy entries required by the flow. It drives the real HTTP
entry points under `/api/v1/critical-results` and applies only the production
Flyway migrations from `db/migrations`. All fixtures and narratives are synthetic.

The targeted command and its final Surefire line (tool evidence):

- `SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=TrackAttemptsRecipientReadBackRequiredAcknowledgmentTest test`
  → `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 9.181 s -- in com.aurevia.smartclinic.exchange.clauses.TrackAttemptsRecipientReadBackRequiredAcknowledgmentTest`

- `attemptsRecipientReadBackAcknowledgmentAndFollowUpAreSeparateEvents` (rule met) —
  registers with read-back required, assigns an owner and future deadline, verifies,
  records a delivered escalation attempt, a read-back, an acknowledgment and follow-up.
  A fresh `GET` asserts seven ordered, distinct event types; that the
  `escalation_attempt` carries the notified recipient and approved channel; that
  `read_back` carries the exact repeated value; that `acknowledged` is bound to
  `criticalResultVersion=3` with the acknowledging clinician's database actor id;
  and that `follow_up` is last.
- `acknowledgmentWithoutADeliveredAttemptIsRefused` (negative) — with a patient
  release recorded, a clinician acknowledgment without a delivered attempt is refused
  `409 NO_DELIVERED_ATTEMPT`; state stays `awaiting_ack`, the release is its own fact
  and no acknowledgment event is created.
- `readBackIsRequiredBeforeAcknowledgmentIsRefused` (negative) — a delivered attempt
  without the required read-back is refused `409 READ_BACK_REQUIRED`.
- `readBackIsRefusedWhenNotRequired` (negative) — a read-back when none was required
  is refused `409 READ_BACK_NOT_REQUIRED`.
- `aVerifierCannotAcknowledgeAndAPeerTenantCannotReadTheTrail` (negative) — a
  `lab_verifier` is refused `403` when acknowledging, a peer tenant is refused `404`
  when reading the trail, and the same command succeeds for the authorized clinician.
- `staleVersionCannotAdvanceTheTrail` (negative) — a stale `If-Match` version is
  refused `412` and leaves the state and trail unchanged.

Production classes and files exercised by this request path:

- `com.aurevia.smartclinic.exchange.critical.CriticalResultController` — the thin HTTP
  adapter for the register, assign, verify, escalate, read-back, acknowledge,
  follow-up, release and get routes under `/api/v1/critical-results`.
- `com.aurevia.smartclinic.exchange.critical.CriticalResultService` — the ordered
  state machine and command invariants: a separate `appendEvent` per fact, recipient
  and channel snapshot on the attempt, read-back bound to a delivered attempt,
  acknowledgment bound to the exact revision, and the delivery/release separation.
- `com.aurevia.smartclinic.exchange.critical.CriticalContract` — `EventType` wire keys
  (`escalation_attempt`, `read_back`, `acknowledged`, `follow_up`, `patient_released`)
  and the immutable `Event`/`View` records carrying recipient, channel, outcome,
  detail, actor and revision.
- `com.aurevia.smartclinic.exchange.critical.CriticalActions` — the role matrix that
  reserves acknowledgment and read-back for `clinician` and denies a verifier.
- `com.aurevia.smartclinic.exchange.critical.CriticalResultRepository` — the RLS-bounded
  SQL boundary that appends the facts and reads the ordered trail.
- `com.aurevia.smartclinic.exchange.critical.CriticalInputs` — boundary validation for
  command fields.
- `com.aurevia.smartclinic.api.IfMatch` — parses the `If-Match` version precondition.
- `db/migrations/V23__critical_results.sql` — the append-only `critical_result_event`
  ledger with forced row-level security, the actor trigger and the immutability guards.

The existing `com.aurevia.smartclinic.it.exchangeresult.CriticalResultFlowTest`
corroborates these behaviors in the full workflow suite; this class proves the clause
in isolation. No production line or branch was added or changed.

## Gap

None.
