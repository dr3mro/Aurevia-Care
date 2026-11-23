# MSG-04 clause 1 — API test evidence

## Clause

Let authorized staff send typed patient instructions with title, text, language, author, unit, issue time, optional action due date, and optional secure attachments.

## Status

Proven by tests

## Evidence

Scope: this status covers the typed-instruction domain model and its thin HTTP adapter. Publishing these routes in the OpenAPI contract and persisting instructions durably are separate tracked concerns (see `## Gap`) and are not claimed here. Clause 3 (replace/withdraw) is likewise out of scope. The `SENT` and `DISPATCHED` entries asserted below are send-path side effects that show publication and dispatch were requested; the MSG-05 fact-tracking workflow itself is not claimed. The bound order context type and identifier are asserted as typed-instruction metadata; the clause-2 linking rule is not claimed.

The clause test is [LetAuthorizedStaffSendTypedPatientInstructionsTest.java](../../../../apps/api/src/test/java/com/aurevia/smartclinic/messaging/clauses/LetAuthorizedStaffSendTypedPatientInstructionsTest.java). It uses the same synthetic fixtures and tenant context as the neighbouring `messaging.instructions` unit tests: a fixed clock at `2026-11-19T10:00:00Z`, a tenant-keyed `InstructionStore.InMemory`, seed helpers for members, unit assignments and one verified patient-unit connection, and synthetic UUID/key identifiers. All eight tests are active (none are `@Disabled`) and drive the normal command entry point, `InstructionController.send` (`POST /api/v1/patient-instructions`).

- `authorizedStaffSendsTypedInstructionWithAllClauseOneFields` (rule met) — a clinician in the sender's organization sends free text. The adapter answers HTTP 201 with a Location pointing at the new thread, and the single published revision carries every clause field: revision 1 in `SENT` state, no template (free-text branch), kind, title, text, language, author subject and author role, unit, connection and patient, issue time equal to the fixed clock, the parsed future action due date, the bound order context type and identifier, the secure attachment identifier, and no supersession, replacement or withdrawal fields. The `SENT` and `DISPATCHED` facts are recorded for the patient.
- `authorizedStaffSendsFreeTextWithoutOptionalDueDateOrAttachments` (rule met, optional fields absent) — the same authorized sender may omit both optional fields: the adapter answers HTTP 201 with a `SENT` revision whose `actionDueAt` is null and whose attachment list is empty, proving the qualifiers in the clause are genuinely optional.
- `sendRefusesRoleWithoutInstructionSendPermission` (negative, role) — a `reception` member with active membership lacks `INSTRUCTION_SEND`, so the guarded command answers `403 FORBIDDEN` with `missing INSTRUCTION_SEND permission`; no revision is stored for the patient.
- `sendRefusesAnotherTenantsConnection` (negative, tenant) — a clinician in a second organization holds the same local unit key and a unit assignment but is seeded with no connection, so the ORG_A connection with that local key is invisible; the connection guard answers `403 FORBIDDEN`, and no revision is stored in either tenant.
- `sendRefusesASubjectNotAssignedToTheUnit` (negative, unit scope) — a clinician with the right role and a valid connection but no unit assignment is refused by the unit half of the guard with `403 FORBIDDEN` and `the sender is not assigned to the care unit`, and no revision is stored.
- `freeTextSendRefusesClinicalAssistantWithoutClinicalSend` (negative, clinical scope) — a `clinical_assistant` may send from an approved template but free text requires `INSTRUCTION_CLINICAL_SEND`; the free-text branch answers `403 FORBIDDEN` with `free text requires clinician authorization`, and no revision is stored.
- `sendRefusesMissingTitle` (negative, input boundary) — a blank free-text title is rejected by the input validator with `422 INVALID_TITLE`, and no revision is stored.
- `sendRefusesADueDateInThePast` (negative, input boundary) — an action due date at or before the issue time is rejected with `422 INVALID_ACTIONDUEAT`, and no revision is stored.

Production classes exercised through that entry point:

- [InstructionController.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/messaging/instructions/InstructionController.java) — the thin HTTP adapter that exposes `POST /api/v1/patient-instructions` and returns 201 with the published revision; its delegation is covered by the existing `InstructionControllerTest`.
- [InstructionService.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/messaging/instructions/InstructionService.java) — `send` owns the typed-instruction command: it re-derives membership and roles, chooses free text or an approved template, requires clinician authorization for free text, checks the patient-unit connection and unit assignment, stamps the author and issue time and appends the patient-notification facts.
- [InstructionContract.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/messaging/instructions/InstructionContract.java) — `SendRequest` and the immutable `Revision` carrying title, text, language, author, unit, issue time, optional due date and optional attachments, plus the `Kind` and `ContextType` vocabularies and `State.SENT`.
- [InstructionActions.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/messaging/instructions/InstructionActions.java) — the bounded action matrix: `reception` has no send permission and free text requires the clinician-only `INSTRUCTION_CLINICAL_SEND` action.
- [InstructionInputs.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/messaging/instructions/InstructionInputs.java) — the trust boundary that validates bounded text, language, keys, identifiers, attachment count and a future action due date with machine-readable 422 errors.
- [InstructionStore.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/messaging/instructions/InstructionStore.java) — the tenant-keyed persistence port and its `InMemory` test adapter; one tenant's connection and unit assignment cannot be read by another even with identical local keys.

The neighbouring [V63__instruction_facts.sql](../../../../db/migrations/V63__instruction_facts.sql) was surveyed only as context: it creates MSG-05 publication, dispatch, first-view, acknowledgment and follow-up facts, not the instruction thread itself.

## Gap

Two follow-up tasks are outside this test-only story; both are already tracked, and no production file, contract or migration was changed here.

1. Contract publication: `contracts/openapi.yaml` has no `/api/v1/patient-instructions` path, so all six controller routes are listed in `contracts/openapi.yaml` (lines 70-71 and 257-260). They need tagged operations, `Idempotency-Key` on the POSTs, pagination on the list GET and regenerated staff-web client types. This belongs to the `FIX-OPENAPI-04` chain (WEB-05); files that would change are `contracts/openapi.yaml`, `contracts/openapi.yaml` and the generated client.
2. Durability: `JdbcInstructionStore` does not exist in `src/main` and there is no Flyway migration for MSG-04 instruction threads and revisions. `V63__instruction_facts.sql` holds only MSG-05 facts. The open `READY-40` task covers a `V64__patient_instructions.sql` with forced row-level security and a real-PostgreSQL store plus an HTTP integration test; files that would change are the `messaging/instructions` store, a new migration under `db/migrations` and an integration test under `apps/api/src/test/java/com/aurevia/smartclinic/it`.
