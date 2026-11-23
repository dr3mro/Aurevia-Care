# MSG-05 clause 5 — clinical consent, task completion and critical-result acknowledgment

## Clause

Clinical consent, task completion and critical-result acknowledgment use their respective workflows.

## Status

Partly proven

## Evidence

Test class `com.aurevia.smartclinic.messaging.clauses.ClinicalConsentTaskCompletionCriticalResultAcknowledgmentTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/messaging/clauses/ClinicalConsentTaskCompletionCriticalResultAcknowledgmentTest.java`).
It drives the real controller, service and tenant-scoped facts through the Spring
MVC pipeline (tenant interceptor and API error handler) with the same synthetic
fixtures as `messaging/acknowledgment/InstructionFactControllerHttpTest`. All
subjects, charts and identifiers are synthetic. It adds no skipped test: skipped
tests fail the repository quality gate, so the absent half is recorded under
`## Gap` and as a task request in the worker notes.

Enabled tests that prove the separation of the built workflows:

- `receiptAcknowledgmentNeverBecomesAClinicalWorkflowAcknowledgment` — a patient
  acknowledges a published revision and the response and stored timeline fact
  are both `RECEIPT` (200, bound to revision 1). The foreign scope keys
  `CLINICAL_CONSENT`, `TASK_COMPLETION` and `CRITICAL_RESULT_ACKNOWLEDGMENT` are
  all rejected by `AcknowledgmentScope.from`, so a receipt cannot be relabelled
  as a clinical workflow acknowledgment. The other two built workflows keep
  their own vocabularies: `CriticalContract.State.from("acknowledged")` and
  `CriticalContract.EventType.ACKNOWLEDGED` are the critical-result
  acknowledgment state and event, both reject the `RECEIPT` key, and
  `CareTeamTask.complete` carries task completion through `OPEN` → `COMPLETED`
  with a recorded outcome while the original open task is unchanged.
- `staffCannotRecordAReceiptAcknowledgment` (negative) — an owning-unit
  `lab_collector` acting as the patient instruction's publisher receives 403
  `FORBIDDEN` from `POST /api/v1/instructions/acknowledge`, and the readable
  timeline holds only the publication and dispatch facts. Receipt is a
  patient-only action, not a staff clinical action.
- `crossTenantAcknowledgmentCannotAttachAReceiptFact` (negative) — a patient in
  another tenant receives 404 `NOT_FOUND` for the same instruction id, and no
  receipt fact appears in the owning tenant's timeline.
- `staleRevisionCannotBeRecordedAsAReceipt` (negative) — acknowledging a
  superseded revision receives 409 `STALE_REVISION`; the current revision can
  then be acknowledged as `RECEIPT`, and exactly one receipt fact is stored.

Production classes exercised by the live request path:

- `com.aurevia.smartclinic.messaging.acknowledgment.InstructionFactController`
  (`POST /api/v1/instructions/publish`, `POST /api/v1/instructions/acknowledge`,
  `GET /api/v1/instructions/{id}/facts`);
- `com.aurevia.smartclinic.messaging.acknowledgment.InstructionFactService`
  (`acknowledge`, `facts`), which hard-codes `AcknowledgmentScope.RECEIPT` and
  binds the fact to the exact revision;
- `com.aurevia.smartclinic.messaging.acknowledgment.InstructionFacts`
  (`AcknowledgmentScope.from`, `Acknowledgment`, `AcknowledgeCommand`,
  `PublishCommand`) and `InstructionFactActions.allows`, which grants
  `ACKNOWLEDGE` only to the patient role;
- `com.aurevia.smartclinic.web.TenantContextInterceptor` and
  `com.aurevia.smartclinic.api.ApiErrorHandler` for tenant binding and error
  mapping, with the synthetic `InMemoryInstructionFactStore` behind the service.

Built workflows surveyed and asserted at their own domain boundary (not driven
over HTTP here, because their controllers and routes belong to other clauses):
`com.aurevia.smartclinic.scheduling.followup.FollowUpService.completeTask` and
`com.aurevia.smartclinic.scheduling.followup.CareTeamTask` (task completion), and
`com.aurevia.smartclinic.exchange.critical.CriticalResultService.acknowledge`,
`com.aurevia.smartclinic.exchange.critical.CriticalContract` and
`db/migrations/V23__critical_results.sql` (critical-result acknowledgment, with
its own `AWAITING_ACK` → `ACKNOWLEDGED` state machine and a separate
`EventType.ACKNOWLEDGED` fact bound to the exact revision). Neither references
`AcknowledgmentScope`.

## Gap

Three of the four workflows exist; clinical consent does not. `consents` is only
an opaque JSON string carried on patient registration and carried forward on
consolidation (`web/PatientController`, `patient/PatientService`,
`patient/Patient`, `patient/PatientRepository`, `patient/consolidation/*`,
`db/migrations/V7__patient_registration.sql` and
`db/migrations/V9__duplicate_review_consolidation.sql`). There is no consent
controller route, service, state machine, table or contract entry, so there is
nothing for a test to call and no behavior that can be proven today. A later
task would add a bounded clinical-consent command and record (a consented
subject, purpose, version, actor, instant and revocation), enforce it in a
tenant-guarded transaction with forced row level security, expose it through a
thin controller, and publish the route in the OpenAPI contract with a regenerated
client. Files that task would change (none changed here): a new
`apps/api/src/main/java/com/aurevia/smartclinic/patient/consent/` package and its
tests under `apps/api/src/test`, a new Flyway migration under `db/migrations`,
`contracts/openapi.yaml` and the generated client. Closing this gap needs
**one** bounded task; the would-prove cases (wrong role → 403, foreign tenant →
404 with no leak, missing consent → 422 fail-closed, and a recorded consent with
its audit event) are preserved in the notes' `task-requests` block.
