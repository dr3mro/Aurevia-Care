# MSG-04 clause 2 — tie instructions to the relevant care relationship

## Clause

Tie instructions to the relevant appointment, order, referral, prescription or encounter when available.

## Status

Partly proven

## Evidence

Test class `com.aurevia.messaging.clauses.TieInstructionsRelevantAppointmentOrderReferralPrescriptionTest`
(`apps/api/src/test/java/com/aurevia/messaging/clauses/TieInstructionsRelevantAppointmentOrderReferralPrescriptionTest.java`),
a synthetic, tenant-keyed service-level suite:

- `sendBindsEachRelevantContextTypeAndIdToThePublishedRevision` — sends one free-text
  instruction for each of the five `ContextType` values with a distinct valid key and
  asserts the type and key round-trip on the returned revision, the stored revision and
  the read model, each in `State.SENT`.
- `sendWithTemplateRequiringADifferentContextIsRejected` — an `ORDER` template refuses a
  send carrying `APPOINTMENT` with 422 / `CONTEXT_REQUIRED` and leaves no revision; a
  matching `ORDER` send then succeeds with the context bound.
- `sendWithMalformedContextIdIsRejected` — a key containing spaces/punctuation and a
  null key each fail with 422 / `INVALID_CONTEXTID` and leave no revision.
- `sendWithoutContextIsRejectedWhenTemplateRequiresOne` — a context-requiring template
  refuses a context-free send with 422 / `CONTEXT_REQUIRED` (the "when available" negative).
- `replacementKeepsTheOriginalContextBinding` — a linked replacement revision copies the
  original `contextType` and `contextId`.
- `contextBindingIsTenantScopedForIdenticalLocalConnections` — two tenants with identical
  local connection, patient and unit identifiers keep distinct context bindings; each
  tenant sees only its own revision (the other thread is 404).

Production classes exercised by these tests:
`com.aurevia.messaging.instructions.InstructionContract` (`ContextType`,
`Template`, `Revision`, `SendRequest`), `InstructionService.send` and `InstructionService.replace`,
`InstructionInputs.key`, `InstructionActions`, `InstructionStore.InMemory` and
`com.aurevia.tenant.TenantContext`. The HTTP entry point
`InstructionController.send` is not called by this class; its route is exercised by
`InstructionControllerTest.everyRoutePublishesReadsReplacesAndWithdraws` in the existing
instruction controller suite, which is not re-run here.

Verified locally: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=TieInstructionsRelevantAppointmentOrderReferralPrescriptionTest test`
— Surefire report `tests="6" errors="0" skipped="0" failures="0"`.

## Gap

The binding itself is proven, but the clause is only partly built. `InstructionService.send`
shape-validates `contextId` through `InstructionInputs.key` and stamps it on the sent (and
later) revisions; it never checks that the referenced appointment, order, referral,
prescription or encounter exists, belongs to the same patient and unit, or is actually
relevant to the instruction. Any syntactically valid key is accepted, so "to the relevant
... when available" is not enforced. The binding is also not durable: `InstructionStore.InMemory`
is the only adapter (listed under `store-without-durable` in `scripts/wiring-baseline.json`),
with no Flyway migration carrying RLS and FORCE RLS for instruction revisions, facts and
templates, so a binding does not survive a restart. The API routes are tracked but not
published: `contracts/openapi.yaml` lists `GET`/`POST /api/v1/patient-instructions`
and the template/replace/withdraw routes, while `contracts/openapi.yaml` has no such paths and
the generated client has no corresponding types.

Files that would change: a new migration under `db/migrations` (version reserved by the owner)
with RLS/FORCE RLS; `apps/api/src/main/java/com/aurevia/messaging/instructions/InstructionStore.java`
plus a new `JdbcInstructionStore`; a new cross-reference port and its five domain adapters
plus `InstructionService.java` and its tests; an HTTP-level integration test through
`InstructionController`; `contracts/openapi.yaml` and the regenerated files under
`contracts/openapi/generated/`. No new task or production file is added by this change.

Number of separate tasks: at least three — (1) durable store and migration with an HTTP
integration test, (2) cross-reference existence/relevance validation for the five context
types with negative tests, (3) OpenAPI route entries and regenerated client. No `@Disabled`
GAP test was added because `scripts/check-constraints.py` rejects any newly added `@Disabled`;
this prose records the missing behaviour for a later task to enable.
