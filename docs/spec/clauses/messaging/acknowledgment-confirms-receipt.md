# MSG-05 clause 4 — the acknowledgment confirms receipt

## Clause

The acknowledgment confirms receipt.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.messaging.clauses.AcknowledgmentConfirmsReceiptTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/messaging/clauses/AcknowledgmentConfirmsReceiptTest.java`).
It follows the cross-module `clauses` style of
`scheduling/clauses/TransferHandoffRequiresActiveAssigneeTest` and the HTTP harness of
`messaging/acknowledgment/InstructionFactControllerHttpTest`: the real
`InstructionFactController`, `InstructionFactService`, `InstructionFactActions` and
`InMemoryInstructionFactStore` are driven through `MockMvcBuilders.standaloneSetup` with
`ApiErrorHandler`, `TenantContextInterceptor` under the `synthetic-auth` profile, a fixed
clock, an `ObjectMapper` and synthetic identifiers. Every case enters through
`POST /api/v1/instructions/acknowledge`; the fact timeline is read through
`GET /api/v1/instructions/{id}/facts`.

- `acknowledgmentRecordsASeparateReceiptScopedFactBoundToTheExactRevision` — after publishing a
  revision that requests acknowledgment, the patient's acknowledgment returns 200 with
  `$.scope=RECEIPT`, `$.revision=1` and the patient actor. The timeline holds three distinct
  facts (publication, dispatch, acknowledgment); the receipt is its own final fact, bound to
  revision 1. The single-valued `AcknowledgmentScope` and the rejection of `consent` and
  `critical_result` reinforce that this acknowledgment can never mean clinical consent, task
  completion or a critical-result acknowledgment (rule met).
- `staffCannotAcknowledgeOnBehalfOfThePatient` — a unit staff member acknowledging an otherwise
  valid, published revision is refused with 403 `FORBIDDEN` (negative: wrong role; only the
  patient role is allowed to acknowledge).
- `acknowledgmentOfAnotherTenantsInstructionIsConcealed` — a caller who is a fully authorized
  patient in a second tenant is refused with 404 `NOT_FOUND` because the instruction lives only
  in the first tenant (negative: tenant isolation fails closed).
- `acknowledgmentOfASupersededRevisionIsRefused` — after revisions 1 and 2 are published,
  acknowledging revision 1 is refused with 409 `STALE_REVISION` (negative: stale revision).
- `acknowledgmentIsRefusedWhenNotRequested` — an instruction published without a requested
  acknowledgment refuses a receipt with 422 `ACKNOWLEDGMENT_NOT_REQUESTED` (negative: forbidden
  state).

Production classes and files surveyed and exercised by the entry point:

- `com.aurevia.smartclinic.messaging.acknowledgment.InstructionFactService.acknowledge` — owns
  the rule: it re-derives tenant and subject from the request context, checks the patient-only
  action permission, requires the instruction to be published in the active tenant, checks the
  verified chart and active unit connection, requires that a receipt was requested, rejects a
  superseded revision, and appends a `RECEIPT` acknowledgment attributed to the authenticated
  account. A view never acknowledges and a generic dispatch never carries clinical content.
- `InstructionFacts.Acknowledgment` and `InstructionFacts.AcknowledgmentScope.RECEIPT` — the
  receipt-only fact and its single wire scope. `InstructionFactActions.ACKNOWLEDGE` allows only
  the `patient` role. `InstructionFactController` is a thin adapter that receives the service
  by injection and delegates `POST /api/v1/instructions/acknowledge`;
  `GET /api/v1/instructions/{id}/facts` exposes the timeline.
- `InMemoryInstructionFactStore` (test) and `JdbcInstructionFactStore` (main) behind the
  `InstructionFactStore` interface — tenant-keyed, append-only fact stores; the in-memory
  adapter exists only under `src/test`. `db/migrations/V63__instruction_facts.sql` creates the
  `instruction_fact_*` tables with tenant keys, `FORCE ROW LEVEL SECURITY`, a tenant isolation
  policy and unique constraints, so the receipt survives a restart and stays inside its tenant.
- Contract survey: `POST /api/v1/instructions/acknowledge` is baselined at
  `contracts/openapi/missing-routes.txt:223` (the first-view route at :225). There is no
  operation in `contracts/openapi.yaml` and therefore no regenerated client entry. That is
  contract-documentation debt handled separately; it does not mean the behaviour is absent.

Broader coverage already present (not repeated here): the service-level branches live in
`messaging/acknowledgment/InstructionFactServiceTest`, the receipt-only scope in
`messaging/acknowledgment/InstructionFactsTest`, the separate-facts and stale-revision HTTP
path in `messaging/acknowledgment/InstructionFactControllerHttpTest`, and the persisted,
RLS-guarded path in
`messaging/acknowledgment/it/JdbcInstructionFactStoreIntegrationTest`.

Verified locally:
`mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=AcknowledgmentConfirmsReceiptTest test`
— `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`.

## Gap

None.
