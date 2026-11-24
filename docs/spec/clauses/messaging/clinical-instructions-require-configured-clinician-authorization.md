# MSG-04 clause 5 — API test evidence

## Clause

Clinical instructions require the configured clinician authorization.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.messaging.clauses.ClinicalInstructionsRequireConfiguredClinicianAuthorizationTest`
(path: `apps/api/src/test/java/com/aurevia/messaging/clauses/ClinicalInstructionsRequireConfiguredClinicianAuthorizationTest.java`).
All six tests are active (none are disabled) and pass with synthetic data only. Every command enters
through the normal HTTP adapter `com.aurevia.messaging.instructions.InstructionController`
(`send`, `replace`, `withdraw`, `defineTemplate`, `listForPatient`) with an injected
`InstructionService`, a tenant-keyed synthetic role fold in `InstructionStore.InMemory` and a bound
`TenantContext`; the context is cleared after each test.

- `clinicianMaySendFreeTextAndRecordsClinicianAuthorRole` — positive proof. A clinician sends free
  text through `InstructionController.send`; the response is HTTP 201, the revision is `State.SENT`,
  its `authorRole` is `clinician` and its fact trail contains `SENT` and `DISPATCHED` facts.
- `clinicalAssistantIsRefusedFreeTextWithoutClinicianAuthorization` — negative proof of the role
  gate. A `clinical_assistant` holds `SEND` but not `CLINICAL_SEND`; the send is refused with HTTP 403
  `FORBIDDEN` ("free text requires clinician authorization") and no revision is persisted for the
  patient (facts are appended only after a revision is stored).
- `clinicalTemplateRequiresClinicianWhileAdminApprovesIt` — separation of approval from clinical
  sending. A `tenant_admin` approves a template with `clinical=true`; a clinician sends it, while a
  `clinical_assistant` is refused with HTTP 403 `FORBIDDEN` ("a clinical template requires clinician
  authorization"). The same assistant may send an approved `clinical=false` template and is recorded
  with `authorRole=clinical_assistant`, proving the gate is scoped to clinical content.
- `replacementFreeTextStillRequiresClinician` — negative proof on the change path. A clinical
  assistant may `withdraw`, but `replace` with new free text is refused with HTTP 403 `FORBIDDEN`
  ("replacement text requires clinician authorization"); the current revision stays `SENT` at
  revision 1, history stays at one revision and no `REPLACED`, `SUPERSEDED` or revision-2 `DISPATCHED`
  fact is appended before the legitimate withdrawal.
- `authorizationIsReDerivedFromCurrentTenantMembership` — negative proof that authority is not
  cached. After a successful clinician send, the three distinct `execute` denial branches are taken
  for the same subject: downgrade to `clinical_assistant` → "free text requires clinician
  authorization"; remove all roles → "no active membership"; clear the tenant context → "no active
  tenant context".
- `configuredClinicalAuthorizationIsTheInjectedActionMatrix` — proof of the word "configured". A
  test-only `InstructionActions` override that allows `CLINICAL_SEND` for `clinical_assistant` is
  injected into the same controller; the assistant's free-text send then succeeds with
  `authorRole=clinical_assistant`. The guard therefore consults the injected matrix, not a literal
  in the service.

Production classes exercised (unchanged by this task):

- `com.aurevia.messaging.instructions.InstructionActions` — `CLINICAL_SEND` maps only to
  the `clinician` key; `SEND` and `WITHDRAW` map to the wider staff-sender set.
- `com.aurevia.messaging.instructions.InstructionService` — guards clinical templates
  (`send`), free text (`send`) and replacement text (`replace`) with `CLINICAL_SEND`; `execute`
  re-derives the active tenant and membership roles on every command and records the author role
  with the same gate.
- `com.aurevia.messaging.instructions.InstructionController` — thin HTTP adapter that
  delegates each route to the injected service and parses the `If-Match` precondition.
- `com.aurevia.messaging.instructions.InstructionStore.InMemory` — tenant-keyed
  synthetic store providing `activeRoles`, unit assignment, connections, templates and facts.
- `com.aurevia.api.ApiException` and `com.aurevia.api.IfMatch` — the
  structured 403/FORBIDDEN denial and the version precondition parsed at the adapter boundary.
- `com.aurevia.tenant.TenantContext` — binds and clears the synthetic tenant identity.

"Configured clinician authorization" is the `clinician` role key in the `InstructionActions` matrix
evaluated against fresh tenant-configured active membership, together with the tenant-approved
template's `clinical` flag. It is not a separate config file, migration or OpenAPI security scheme.

## Gap

None.

The rule is built and proven by active tests at the HTTP adapter and service boundaries. This task
added proof only; it did not change production code, an existing test, the OpenAPI contract or a
migration.
