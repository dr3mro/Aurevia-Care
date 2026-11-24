# MSG-04 clause 4 — use unit-approved templates or approved free text within the sender’s professional scope

## Clause

Use unit-approved templates or approved free text within the sender’s professional scope.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.messaging.clauses.UseUnitApprovedTemplatesApprovedFreeTextTest`
(`apps/api/src/test/java/com/aurevia/messaging/clauses/UseUnitApprovedTemplatesApprovedFreeTextTest.java`),
driven through the `InstructionController` HTTP adapter methods with an injected
service, a synthetic tenant-keyed `InstructionStore.InMemory` and synthetic
members. It is an adapter/service-tier test: it uses direct construction rather
than a Spring context or PostgreSQL, so it proves rule enforcement, not
durability or contract publication (see `## Gap`). All 12 tests are enabled:

- `adminApprovedUnitTemplateIsSentByAClinicianWithItsApprovedContent` — an
  administrator approves a non-clinical unit template and a clinician sends from
  it; the response is 201 with `State.SENT`, the template id, kind, title, body
  and language are copied from the approved template, and the author is the
  clinician.
- `approvedNonClinicalTemplateIsSendableByAnInScopeAssistant` — an approved
  non-clinical template is within a `clinical_assistant`'s scope; the revision is
  sent with that authoring role.
- `clinicianMaySendAnApprovedClinicalTemplate` — an approved clinical template
  is within clinician scope; the revision is sent with the clinician authoring
  role.
- `clinicianSendsBoundedFreeTextWithinProfessionalScope` — a clinician sends
  free text; the bounded title, text, kind and language are carried into a sent
  revision with no template id.
- `clinicalAssistantCannotSendFreeTextOutsideProfessionalScope` — a
  `clinical_assistant`'s free text is refused (403 `FORBIDDEN`) and no revision
  is stored.
- `clinicalAssistantCannotSendAClinicalTemplate` — a clinical template approved
  for the unit is still refused to a `clinical_assistant` (403 `FORBIDDEN`).
- `unknownAndOtherUnitTemplatesAreRefused` — an unknown template key and a
  template approved for another unit are both refused (404 `NOT_FOUND`); the
  store lookup is filtered by the sending unit.
- `templateContextMismatchIsRefused` — a template bound to an `ORDER` context is
  refused for an `APPOINTMENT` context and for a missing context (422
  `CONTEXT_REQUIRED`).
- `freeTextWithoutAKindIsRefused` — clinician free text with no kind is refused
  (422 `INVALID_KIND`).
- `receptionCannotApproveATemplate` — template approval is reserved to
  administration; `reception` is refused (403 `FORBIDDEN`) and no template is
  stored.
- `senderNotAssignedToTheUnitCannotSend` — a clinician who is not assigned to
  the named care unit is refused (403 `FORBIDDEN`) even with an active patient
  connection.
- `templateApprovedInAnotherTenantIsRefused` — a template approved in another
  tenant is invisible to identical local unit and connection identifiers (404
  `NOT_FOUND`).

Production classes exercised through `POST /api/v1/patient-instructions/templates`
and `POST /api/v1/patient-instructions`:
`com.aurevia.messaging.instructions.InstructionController` (thin HTTP
adapter), `com.aurevia.messaging.instructions.InstructionService`
(approval gate, template-versus-free-text branch, clinical authorization,
context binding and unit assignment), `com.aurevia.messaging.instructions.InstructionActions`
(bounded role matrix), `com.aurevia.messaging.instructions.InstructionStore`
(`InMemory.approvedTemplate` tenant and unit filter), `com.aurevia.messaging.instructions.InstructionInputs`
(bounded input validation) and the records/enums in
`com.aurevia.messaging.instructions.InstructionContract`.

"Professional scope" is enforced here as the clinical-content authorization
boundary: clinical templates and all free text require the clinician role, while
non-clinical approved templates may be sent by other in-scope staff. The service
does not carry a separate per-`Kind` specialty allow-list.

Verified locally: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=UseUnitApprovedTemplatesApprovedFreeTextTest test`
— `tests="12" errors="0" skipped="0" failures="0"` in the Surefire XML report.
"Proven by tests" is limited to the clause behaviour above; it does not claim
that the instruction routes are published in the committed OpenAPI contract.

## Gap

No gap for the clause behaviour itself. Out of scope and pre-existing:

- `InstructionStore.InMemory` is a `@Repository` in `src/main`, so instruction
  state is lost on restart. There is no `JdbcInstructionStore` and no Flyway
  table for instruction templates or revisions (`V63__instruction_facts.sql`
  belongs to the separate MSG-05 facts slice). `READY-40` already owns the durable
  store, `db/migrations/V64__patient_instructions.sql` with RLS and FORCE RLS and
  its integration test; it depends on `READY-39`.
- The six `/api/v1/patient-instructions` routes are absent from the committed
  OpenAPI contract and are listed in `contracts/openapi.yaml`.
  `FIX-OPENAPI-04` owns documenting the remaining routes and emptying that
  baseline (through `FIX-OPENAPI-02` and `FIX-OPENAPI-03`); `FIX-OPENAPI-01`
  owns the route-parity gate.

These are at least two existing tracked efforts (durable persistence and contract
publication), not one new task; no `task-requests` entry is needed. This change
does not touch `apps/api/src/main`, `db/migrations`, `contracts` or `tasks`.
