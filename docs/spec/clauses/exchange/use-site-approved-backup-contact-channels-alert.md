# XCH-05 clause 3 — use site-approved backup contact channels for overdue or offline alerts

## Clause

Use site-approved backup contact channels if an alert is overdue or the system is down.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.exchange.clauses.UseSiteApprovedBackupContactChannelsAlertTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/exchange/clauses/UseSiteApprovedBackupContactChannelsAlertTest.java`).
It is a synthetic, database-free clause test in the same `clauses` style as
`scheduling/clauses/TransferHandoffRequiresActiveAssigneeTest`: mocked
`CriticalResultRepository` and `AuditAppender`, a transaction template that runs
the real guarded callback, two `Clock.fixed` instances (before and after the
deadline) and a synthetic tenant context. Every case enters through
`CriticalResultService.escalate`, the command called by `CriticalResultController`
at `POST /api/v1/critical-results/{id}/escalate`.

- `overdueAlertUsesApprovedBackupChannelAndRecordsAttempt` — with the clock past
  the deadline, the approved backup channel and recipient are accepted; the alert
  stays `AWAITING_ACK` at version 3, one `ESCALATION_ATTEMPT` event records the
  recipient, channel and delivered outcome, and the audit row is `SUCCESS` (rule met).
- `systemDownAlertUsesApprovedBackupChannelBeforeDeadline` — with the primary
  system down but the clock still before the deadline, the same backup channel and
  recipient are accepted with the same event and audit row (rule met; left side
  of the `||` that decides the branch).
- `overdueAlertRefusesPrimaryChannelAndOwner` — an overdue alert presented with
  the primary channel and owner, or with the backup channel but the owner as
  recipient, is refused with 422 `BACKUP_CHANNEL_REQUIRED` and no
  `ESCALATION_ATTEMPT` event (negative: wrong channel and wrong recipient).
- `inHoursAlertRefusesBackupChannel` — before the deadline, the backup channel and
  recipient are refused with 422 `PRIMARY_CHANNEL_REQUIRED` and no attempt event
  (negative: forbidden channel for the current state).
- `escalationIsRefusedWhenBackupRecipientIsNotActive` — a revoked backup
  recipient yields 409 `ROSTER_GAP`, a `ROSTER_GAP` event and a `CONFLICT` audit
  row, with no attempt event (negative: revoked membership fails closed on the
  next action).
- `escalationByUnauthorizedRoleIsForbidden` — an actor without an escalation
  permission is refused with 403 `FORBIDDEN` and a `DENIED` audit row (negative:
  wrong role).
- `staleVersionEscalationIsRefused` — an out-of-date `If-Match` version is refused
  with 412 `STALE_VERSION` and no attempt event (negative: stale version).
- `escalationOfAlertNotAwaitingAckIsRefused` — an alert outside `AWAITING_ACK` is
  refused with 409 `INVALID_TRANSITION` and no attempt event (negative: forbidden
  state).

Production classes and files surveyed and exercised by the entry point:

- `com.aurevia.smartclinic.exchange.critical.CriticalResultService.escalate` —
  owns the rule: it re-derives authorization, checks the version and state,
  computes `overdueOrDown` from the fixed clock and the `systemDown` flag, refuses
  an inactive backup recipient with a durable `ROSTER_GAP` fact, requires the
  approved backup channel and recipient when overdue or down, requires the
  approved primary channel and owner in hours, then appends one
  `ESCALATION_ATTEMPT` fact and a `SUCCESS` audit row in the same transaction.
- `CriticalResultService.assign` — reads the three approved policy keys and
  snapshots the primary channel, backup channel and backup recipient onto the
  alert at assignment, so a later policy change cannot silently rewrite which
  channel an overdue alert must use.
- `CriticalActions` (`ESCALATE` is allowed for `clinician` and `tenant_admin`),
  `CriticalInputs.channel/subject/outcome/note/flag` and
  `CriticalContract.EscalateRequest/EventType/Result` — the role matrix, input
  guards, command and append-only event vocabulary.
- `CriticalResultRepository.appendEvent` and `hasActiveMembership` — the fact
  append path and the live membership source for the fail-closed check.
- `CriticalResultController` — `POST /api/v1/critical-results/{id}/escalate` is
  the HTTP entry point; it injects `CriticalResultService` and parses the
  `If-Match` version.
- `db/migrations/V23__critical_results.sql` — creates `critical_result` and
  `critical_result_event` with the backup policy columns and the `roster_gap`
  event type, enables and forces row level security with tenant policies on both
  tables, and adds append-only triggers that keep the fact ledger immutable.

Broader coverage already present (not repeated here): the HTTP-plus-PostgreSQL
path, including the approved backup channel, the roster-gap fact and the
append-only ledger, lives in `it/exchangeresult/CriticalResultFlowTest`
(`overdueAndSystemDownUseApprovedBackupChannel`,
`reportSurfacesOverdueAlertsAndRosterGaps`); the HTTP entry point is also proven
by `it/exchangeresult/CriticalResultControllerHttpTest.allowedSubjectReachesSuccessStatusOnEveryRoute`.

Contract survey: the escalate route is baselined in
`contracts/openapi/missing-routes.txt` as
`POST /api/v1/critical-results/{}/escalate` and is not described as an operation
in `contracts/openapi.yaml`. That is contract-documentation debt recorded
elsewhere and does not affect whether the behaviour is implemented and proven.

Verified locally: the targeted Maven command and its final Surefire counts are
recorded in `.agent/pr-notes/SPEC-XCH-05-C03-20261122-162409.md`.

## Gap

None.
