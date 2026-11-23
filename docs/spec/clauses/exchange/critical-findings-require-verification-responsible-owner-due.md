# XCH-05 clause 1 — critical findings require verification, a responsible owner, a due time and an escalation policy

## Clause

For critical findings, require verification, responsible owner, due time and escalation policy.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.exchange.clauses.CriticalFindingsRequireVerificationResponsibleOwnerDueTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/exchange/clauses/CriticalFindingsRequireVerificationResponsibleOwnerDueTest.java`).
It is a synthetic, database-free clause test in the same `clauses` style as
`scheduling/clauses/TransferHandoffRequiresActiveAssigneeTest`: a mocked
`CriticalResultRepository` and `AuditAppender`, a transaction template that runs
the real guarded callback, `Clock.fixed` and a synthetic tenant context. Every
case enters through `CriticalResultService`, the domain service the HTTP
controller injects; no patient data or credential is used and no production code
is changed.

- `criticalFindingIsVerifiedOwnedDueAndEscalatedUnderApprovedPolicy` — register,
  assign owner and future due time, verify, then escalate. Asserts the owner,
  due time and stamped primary/backup channels on the result, that verification
  binds the verifier and instant, and that `OWNER_ASSIGNED`, `VERIFIED` and
  `ESCALATION_ATTEMPT` are three separate append-only events (rule met).
- `verificationIsRefusedWithoutOwnerAndDueTime` — an unassigned finding is
  refused with 409 `OWNER_REQUIRED` and no `VERIFIED` event is written
  (negative: missing responsible owner and due time).
- `escalationIsRefusedBeforeVerification` — an owned but unverified finding is
  refused with 409 `INVALID_TRANSITION` and no `ESCALATION_ATTEMPT` event is
  written (negative: verification is mandatory before alerting).
- `assignmentIsRefusedWithoutApprovedEscalationPolicy` — a missing approved
  primary channel is refused with 422 `NO_ESCALATION_POLICY`; nothing is assigned
  and no `OWNER_ASSIGNED` event is written (negative: missing escalation policy).
- `assignmentIsRefusedWithoutApprovedBackupRecipient` — a missing active backup
  clinician is refused with 422 `BACKUP_ROSTER_GAP` and an immutable `ROSTER_GAP`
  event is appended; nothing is assigned (negative: roster gap in the policy).
- `acknowledgeIsRefusedForARoleWithoutClinicalPermission` — after driving the
  finding to `AWAITING_ACK`, a verifier-only role is denied with 403 `FORBIDDEN`
  and no acknowledgment is written (negative: only a clinician may acknowledge).
- `patientReleaseDoesNotAcknowledgeCriticalFinding` — a patient release appends
  `PATIENT_RELEASED` but leaves the state `AWAITING_ACK` and writes no
  `ACKNOWLEDGED` fact (negative: release is not acknowledgment).

Production classes and files surveyed and exercised by the entry point:

- `com.aurevia.smartclinic.exchange.critical.CriticalResultService` — owns the
  clause. `register` creates an unverified finding; `assign` validates the owner
  and a future due time, snapshots the approved escalation policy and appends
  `OWNER_ASSIGNED`; `verify` refuses without owner/due and appends `VERIFIED`;
  `escalate` enforces the stamped primary channel before the deadline and the
  approved backup channel when overdue or down, appending each attempt as its
  own fact; `acknowledge` and `release` are separate commands. `authorize`,
  `isActiveClinician`, `requiredPolicy`, `requireVersion` and `requireState`
  re-derive permissions and invariants inside the guarded transaction.
- `com.aurevia.smartclinic.exchange.critical.CriticalContract` — `Result` carries
  the owner, due time and stamped channels; `State` and `EventType` keep
  verification, assignment, escalation and acknowledgment as distinct facts.
  `CriticalActions` is the role/action matrix and `CriticalInputs` validates the
  subject, channel, note and future deadline. `CriticalResultRepository` is the
  single SQL boundary and appends to the immutable event ledger.
- `com.aurevia.smartclinic.exchange.critical.CriticalResultController` —
  `POST /api/v1/critical-results` and the `POST /api/v1/critical-results/{id}/assign`,
  `/verify`, `/escalate`, `/acknowledge` and `/release` sub-routes are the HTTP
  entry points; the controller injects the service and parses `If-Match`.
- `apps/api/src/test/java/com/aurevia/smartclinic/it/exchangeresult/CriticalResultControllerHttpTest`
  — the broader HTTP-plus-PostgreSQL proof of every critical-result route,
  including authorization denials and the chained success workflow.
- `db/migrations/V23__critical_results.sql` — creates `public.critical_result`
  and the append-only `public.critical_result_event` ledger with the owner, due
  time, primary/backup channel and backup-recipient columns. Forced row-level
  security and FORCE RLS from V2 confine both tables to the active organization;
  triggers re-check ownership, version and transition invariants in the database.
- Contract survey: all ten `/api/v1/critical-results/*` routes are baselined in
  `contracts/openapi.yaml` (GET report and GET `{}` at lines 29-30;
  POST base and the seven sub-routes at lines 170-177). They are not described as
  operations in `contracts/openapi.yaml`. That is separate contract-documentation
  and regenerated-client debt; it does not affect whether the clause is
  implemented and proven by the tests above and is unchanged by this story.

Verified locally: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=CriticalFindingsRequireVerificationResponsibleOwnerDueTest test`
— `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`.

## Gap

None.
