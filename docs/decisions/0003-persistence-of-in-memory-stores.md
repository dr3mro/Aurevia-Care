# 0003. Persistence of in-memory stores

- Status: proposed
- Date: 2026-11-20
- Requirement IDs: ARC-01, ARC-02, OPS-02

## Context

Production code contains in-memory store implementations whose data disappears on
restart. This record inventories them so each persistence rewrite can be ordered and
resourced separately. Every cell below was read from the source with `grep` on
`apps/api/src/main/java` and `db/migrations`; nothing is inferred from the story text.
No Java, SQL or other document was changed by this record.

| Class | Interface it implements | Is it a Spring bean (`@Repository`/`@Component`)? | Who injects the interface (`private final <Interface>`) | Durable table that already exists in `db/migrations` or "none" |
|---|---|---|---|---|
| `api/x_02/InMemoryDeliveryStore` | `DeliveryStore` | Yes — `@Repository` | `api/x_02/DurableEventService` (`private final DeliveryStore store`) | V55: `delivery_outbox`, `delivery_receipt`, `delivery_dead_letter`, `delivery_replay`, `delivery_link`, `delivery_audit` |
| `messaging/service/InMemoryServiceProfileStore` | `ServiceProfileStore` | Yes — `@Repository` | `messaging/service/ServiceProfileService` (`private final ServiceProfileStore store`) | none |
| `messaging/units/InMemoryMessagingStore` | `MessagingStore` | Yes — `@Repository` | `messaging/units/CareUnitDirectoryService` (`private final MessagingStore store`) | none |
| `messaging/flows/InMemoryFlowPolicyStore` | `FlowPolicyStore` | Yes — `@Repository` | `messaging/flows/PatientFlowPresentationService` (`private final FlowPolicyStore store`) | none |
| `messaging/governance/InMemoryMessagingGovernanceStore` | `MessagingGovernanceStore` | Yes — `@Repository` | `messaging/governance/MessagingGovernanceService` (`private final MessagingGovernanceStore store`) | none |
| `messaging/acknowledgment/InMemoryInstructionFactStore` | `InstructionFactStore` | Yes — `@Repository` | `messaging/acknowledgment/InstructionFactService` (`private final InstructionFactStore store`) | none |
| `clinical/templates/InMemoryTemplateStore` | `TemplateStore` | No (plain `final` class, no annotation) | `clinical/templates/TemplateService` (`private final TemplateStore store`) | none (V15 has a `template_version` column on `clinical_note_revision`, no template table) |
| `api/authorization/InMemoryDecisionSink` | `CommandAuthorization.DecisionSink` | No (plain `final` class, no annotation) | `api/authorization/CommandAuthorization` (`private final DecisionSink sink`) | none |
| `api/x_01/IdempotencyLedger.InMemoryIdempotencyLedger` | `IdempotencyLedger` (enclosing interface) | No (package-private nested `final class`, no annotation) | none — no `private final IdempotencyLedger` and no reference outside `IdempotencyLedger.java` | none |
| `api/x_03/InMemoryDocumentStore` | `DocumentStore` | No (plain `final` class, no annotation) | `api/x_03/PdfBindingService`, `api/x_03/DocumentGuard`, `api/x_03/QuarantineService` (`private final DocumentStore store`) | none (V17 `document_association`/`rendered_document` are CLN-07 associations, not the X-03 artifact store) |
| `messaging/attachments/InMemoryAttachmentStore` | `AttachmentStore` | No (plain `final` class, no annotation) | `messaging/attachments/AttachmentService` (`private final AttachmentStore store`) | none |
| `messaging/enrollment/InMemoryPatientAccessStore` | `PatientAccessStore` | No (plain `final` class, no annotation) | `messaging/enrollment/PatientAccessService` (`private final PatientAccessStore store`) | V10: `patient_account`, `account_invitation`, `account_chart_link` (partial — no token, support-case or notification table) |
| `messaging/reports/InMemoryReportReleaseStore` | none — concrete class used directly | No (plain `final` class, no annotation) | `messaging/reports/ReleasedReportService` (`private final InMemoryReportReleaseStore store`, concrete type) | V24: `patient_release`, `release_event`, `release_notification`; V33: `patient_link` (partial) |
| `messaging/instructions/InstructionStore.InMemory` | `InstructionStore` (enclosing interface) | Yes — `@Repository` on the nested class | `messaging/instructions/InstructionService` (`private final InstructionStore store`) | none |
| `therapy/InMemoryTherapyStore` | `TherapyStore` | No (plain `final` class, no annotation) | `therapy/TherapyService` (`private final TherapyStore store`) | none |

## Decision

Order the persistence work in three tiers, one reserved Flyway version per store that
needs a table. Versions V59 upward are reserved for this programme; they are not
created by this record.

1. **Spring beans that already have tables.** `InMemoryDeliveryStore` is the only
   row in both categories: the V55 tables already exist, so no migration is needed.
   Replace the bean with a JDBC `DeliveryStore`; the code change is owned by
   AUD-20261120-06. This establishes the repository pattern for the rest.
2. **Stores that need a new migration.** Reserve one version each, in this order:
   - V59 `InMemoryServiceProfileStore` (`ServiceProfileStore`)
   - V60 `InMemoryMessagingStore` (`MessagingStore`)
   - V61 `InMemoryFlowPolicyStore` (`FlowPolicyStore`)
   - V62 `InMemoryMessagingGovernanceStore` (`MessagingGovernanceStore`)
   - V63 `InMemoryInstructionFactStore` (`InstructionFactStore`)
   - V64 `InstructionStore.InMemory` (`InstructionStore`)
   - V65 `InMemoryTemplateStore` (`TemplateStore`)
   - V66 `InMemoryDecisionSink` (`DecisionSink`) — security-relevant
   - V67 `InMemoryDocumentStore` (`DocumentStore`)
   - V68 `InMemoryAttachmentStore` (`AttachmentStore`)
   - V69 `InMemoryPatientAccessStore` (`PatientAccessStore`) — complete the gaps left by V10
   - V70 `InMemoryReportReleaseStore` — complement V24 `patient_release` and V33 `patient_link`
   - V71 `InMemoryTherapyStore` (`TherapyStore`)

   Each rewrite keeps the interface contract, derives tenant context from the
   authenticated subject and active membership, uses forced row-level security with
   the organization on every key, and commits state with its audit facts in one
   transaction. A store whose table only partially covers its state (V10, V24/V33)
   needs a forward expansion, not a rewrite of the applied migration.
3. **Stores nobody injects.** `IdempotencyLedger.InMemoryIdempotencyLedger` has no
   injecting field and no reference outside its own file. Remove it or mark it an
   orphan for AUD-20261119-03 rather than building a table for dead code. Because
   replay protection is security-relevant, if the ledger is retained a durable
   implementation still has to be introduced before production; that decision is
   left to the owning story.

A related orphan risk is recorded but not resolved here: the concrete classes that
are not Spring annotated are never constructed in `main` (`grep -rn "new InMemory"
apps/api/src/main/java` returns nothing), and several of their injecting services
carry no `@Service`/`@Component` annotation. Adding a table alone does not make such a
store reachable at runtime, so AUD-20261119-03 must decide whether to wire or delete
each of those services.

## Alternatives considered

- Keep the in-memory stores and add periodic snapshots: rejected. A snapshot still
  loses in-flight commands and breaks the replay, deduplication and append-only
  guarantees that ARC-01 and ARC-02 require.
- One generic key-value table for every store: rejected. It discards per-domain
  constraints, event identity and tenant policy, and moves invariants out of the
  owning module.
- Convert only the annotated `@Repository` beans: rejected. Non-bean stores also
  hold clinical and authorization state (templates, decisions, documents) and are
  injected by services; restart would still lose that state.

## Consequences and evidence

- Restart loses data for **every** `@Repository` in the table:
  `InMemoryDeliveryStore`, `InMemoryServiceProfileStore`, `InMemoryMessagingStore`,
  `InMemoryFlowPolicyStore`, `InMemoryMessagingGovernanceStore`,
  `InMemoryInstructionFactStore` and `InstructionStore.InMemory`.
- `InMemoryDecisionSink` and `IdempotencyLedger` are security-relevant: authorization
  decision audit and idempotent replay protection must be durable before production.
- Evidence source: executed `grep`/`find` tool commands on this branch, not a
  reviewer, simulated user or human participant. Exact commands and outputs are
  recorded in `.agent/pr-notes/AUD-20261120-07.md`. This is a proposed sequencing
  record only: no Java, SQL or migration was created, and each rewrite still needs
  its own implementation story, failing tests and 100% coverage gate.
