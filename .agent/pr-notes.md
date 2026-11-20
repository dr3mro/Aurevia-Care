# AUD-20261120-07 — Inventory every in-memory store and write the persistence decision record

## Scope and requirement IDs

- Scope: `docs/decisions` only. Created `docs/decisions/0003-persistence-of-in-memory-stores.md` from `docs/decisions/template.md`.
- Requirement IDs: ARC-01, ARC-02, OPS-02 (as directed by the story).
- Branch: `swarm/AUD-20261120-07-242`, base HEAD `57f449c`. No Java, SQL, migration, script or shared-tracker file was changed. No push, tag or `gh` call.

## Acceptance mapping

| Story acceptance | Evidence |
|---|---|
| File exists, created from the template with every heading kept; Status `proposed`, Date `2026-11-20`, Requirement IDs `ARC-01, ARC-02, OPS-02` | File read-back: headings are `# 0003. Persistence of in-memory stores`, `## Context`, `## Decision`, `## Alternatives considered`, `## Consequences and evidence` (see heading check below). |
| Context table, one row per listed class, five required columns | 15 data rows; programmatic check reported `data rows: 15` and `rows with wrong column count: 0`. |
| Each cell from real `grep` output, never guessed | Inventory commands and outputs below; every interface, annotation, injecting field and table name was read from the code. |
| Short Decision ordering: beans-with-tables → new migrations V59+ → nobody-injects | Decision section: tier 1 `InMemoryDeliveryStore`/V55; tier 2 V59–V71 one per store; tier 3 `IdempotencyLedger.InMemoryIdempotencyLedger` orphan for AUD-20261119-03. |
| Consequences: restart loses data for every `@Repository`; `InMemoryDecisionSink` and `IdempotencyLedger` security-relevant | Both lines present in `## Consequences and evidence`. |
| `python3 -I scripts/check-docs.py` passes | exit 0 (before and after creating the document). |
| `bash scripts/worker-check.sh` passes for the classes touched | No classes were touched. The scoped gates pass; the full gate is blocked by pre-existing red suites and a pre-existing DB-script defect (details below). |

## Test-first note

This is a documentation-only story, so there is no production behavior to test with a red Java test. The discovery "red" was the absent required artifact: `test -e docs/decisions/0003-persistence-of-in-memory-stores.md` returned exit 1 before the write, with `python3 -I scripts/check-docs.py` already exit 0. The document was then created and re-verified. No test was weakened, skipped or deleted.

## Inventory evidence (executed tool commands, synthetic code survey only)

Interface and Spring-bean detection — `grep -nE` over the 15 files under `apps/api/src/main/java/com/aurevia/smartclinic`:

- `@Repository` present on: `api/x_02/InMemoryDeliveryStore` (implements `DeliveryStore`), `messaging/service/InMemoryServiceProfileStore` (`ServiceProfileStore`), `messaging/units/InMemoryMessagingStore` (`MessagingStore`), `messaging/flows/InMemoryFlowPolicyStore` (`FlowPolicyStore`), `messaging/governance/InMemoryMessagingGovernanceStore` (`MessagingGovernanceStore`), `messaging/acknowledgment/InMemoryInstructionFactStore` (`InstructionFactStore`), and `messaging/instructions/InstructionStore.java:70` (`InstructionStore.InMemory implements InstructionStore`). **Notable finding:** the story listed `InstructionStore.InMemory` among "other in-memory classes", but it is in fact a `@Repository` Spring bean; the record reports it as such.
- No Spring annotation on: `InMemoryTemplateStore`, `InMemoryDecisionSink`, `IdempotencyLedger.InMemoryIdempotencyLedger` (nested `final class` at line 126), `InMemoryDocumentStore`, `InMemoryAttachmentStore`, `InMemoryPatientAccessStore`, `InMemoryReportReleaseStore`, `InMemoryTherapyStore`.
- `InMemoryReportReleaseStore` implements no interface (`public final class ... {`); it is injected by concrete type.

Injection detection — `grep -rn "private final <Interface>" apps/api/src/main/java`:

- `DeliveryStore` → `api/x_02/DurableEventService.java:61`
- `ServiceProfileStore` → `messaging/service/ServiceProfileService.java:46`
- `MessagingStore` → `messaging/units/CareUnitDirectoryService.java:52`
- `FlowPolicyStore` → `messaging/flows/PatientFlowPresentationService.java:34`
- `MessagingGovernanceStore` → `messaging/governance/MessagingGovernanceService.java:62`
- `InstructionFactStore` → `messaging/acknowledgment/InstructionFactService.java:58`
- `TemplateStore` → `clinical/templates/TemplateService.java:44`
- `DecisionSink` → `api/authorization/CommandAuthorization.java:167`
- `DocumentStore` → `api/x_03/PdfBindingService.java:33`, `DocumentGuard.java:21`, `QuarantineService.java:35`
- `AttachmentStore` → `messaging/attachments/AttachmentService.java:43`
- `PatientAccessStore` → `messaging/enrollment/PatientAccessService.java:81`
- `InstructionStore` → `messaging/instructions/InstructionService.java:39`
- `TherapyStore` → `therapy/TherapyService.java:99`
- `InMemoryReportReleaseStore` (concrete) → `messaging/reports/ReleasedReportService.java:37`
- `IdempotencyLedger` → none (no `private final IdempotencyLedger`; `grep -rn "IdempotencyLedger" ... | grep -v own file` returned nothing).

Durable-table detection — `grep -rhoiE 'CREATE TABLE...' db/migrations/*.sql` plus per-keyword greps:

- V55 `__durable_event_outbox.sql`: `delivery_outbox`, `delivery_receipt`, `delivery_dead_letter`, `delivery_replay`, `delivery_link`, `delivery_audit`.
- V10 `__patient_identity_proofing.sql`: `patient_account`, `account_invitation`, `account_chart_link` (partial coverage of `PatientAccessStore`).
- V24 `__patient_release.sql`: `patient_release`, `release_event`, `release_notification`; V33 `__conversation_lifecycle.sql`: `patient_link` (partial coverage of the release store).
- No `service_profile`, `care_unit`, `patient_unit_connection`, `flow_policy`, `instruction_fact`, `template_version` (table), `artifact`, `pdf_binding`, `attachment` (table), `access_token`, `support_case`, `therapy` or dedicated `decision`/`idempotency` ledger table exists. Keyword hits for `template_version` (V15), `decision` (V21/V24/V25/V46/V55), `idempoten` (V55), `attachment` (V58), `instruction` (V43) are columns/comments, not backing tables — verified by reading the matching lines.

Orphan cross-check: `grep -rn "new InMemory" apps/api/src/main/java` → none; several injecting services carry no `@Service`/`@Component` (e.g. `TemplateService`, `PdfBindingService`, `AttachmentService`, `PatientAccessService`, `TherapyService`, `ReleasedReportService`). Recorded in the Decision as an AUD-20261119-03 risk, not resolved here.

## Checks run and results

| Command | Result |
|---|---|
| `test -e docs/decisions/0003-persistence-of-in-memory-stores.md` | exit 1 before creation (discovery red), exit 0 after |
| `python3 -I scripts/check-docs.py` (before and after) | exit 0 both times; no broken links or unknown requirement IDs |
| `python3 -I scripts/check-pack.py` | exit 0 — "Delivery tooling validated: 33 agents, 33 skills" |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 (no migration created) |
| `python3 -I -m unittest discover -s scripts/tests -q` (sanitized env) | exit 0 |
| `bash scripts/quick-check.sh` (sanitized env) | exit 0 — "quick check: policy, compile and unit tests passed" (shellcheck, pack, docs, secrets, migrations, Python suite, Java compile + unit tests) |
| table verification via Python (read-only) | `data rows: 15`; `rows with wrong column count: 0`; headings match the template exactly |
| `bash scripts/worker-check.sh` (sanitized env) | quick-check passed; integration tier and `db/check.sh` failed for pre-existing reasons (below); exit 3 |

Environment repair needed to reach the tooling tests (worker-environment only, no repo file changed): the cloud worker sets `CHECK_SCRIPT=scripts/worker-check.sh` and `BRANCH_PREFIX=swarm`, which leak into `test_agent_run.py`'s temp repos and make 5 tests fail + 2 error; with `env -u CHECK_SCRIPT -u BRANCH_PREFIX` all 23 `test_agent_run` tests pass. PyYAML was absent and neither `pip` nor an apt package was available; it was bootstrapped from a bundled pip wheel into `/usr/local/lib/python3.11/dist-packages` so isolated (`python3 -I`) tests can import `yaml`. Both were pre-existing environment gaps, not caused by this change.

## Defects found (none introduced by this change; not edited here)

- `worker-check.sh` integration tier: 44 failures in `AccessIntegrationTest`, `ClinicalNoteRevisionIntegrationTest`, `DocumentIsolationTest`, `ResultRoutingIntegrationTest`, `RxTransmissionFlowTest`, `SpecimenAccessionIsolationTest`, `TrendingTest`. `scripts/worker_gate.py` explicitly reported "integration failures not related to this change (already red on main)" for exactly these classes; TODO.md tracks them under DLV-02/DLV-03/DLV-04. Out of scope (no `apps/api`, `db/` or script file touched).
- `bash db/check.sh` fails with `psql:db/migrations/V10__patient_identity_proofing.sql:17: ERROR: relation "public.organization" does not exist` because the offline runner applies `V*.sql` in lexicographic glob order (V10 before V1). This is the known tooling defect recorded in swarm PR notes; it is not a migration I created and is outside `docs/decisions`.
- No Python test reads `docs/decisions`, so the new document cannot influence these failures.

## Decisions

- Kept the template's exact heading style (`# 0003. Title` with a period) and all five headings; no extra heading added. No relative Markdown links were introduced, so `check-docs.py` link validation is unaffected.
- Reserved V59–V71 (13 versions, one per store needing a table) as a *proposal*; no migration file was created and no version was consumed.
- Classified `InstructionStore.InMemory` as a Spring bean despite the story's grouping, because the code carries `@Repository` — the record follows the code, as instructed.
- Classified stores with partial table coverage (V10 patient access; V24/V33 release) as needing forward expansion rather than a rewrite of applied migrations.
- Left the orphan decision for `IdempotencyLedger` to AUD-20261119-03 / the owning story, while flagging replay protection as security-relevant before production.

## Evidence provenance

All evidence came from executed `grep`/`find`/`read`/shell tool commands and executed checks on synthetic source code. No reviewer, simulated user, human participant, patient data or credential contributed. No real clinical or pilot evidence is claimed.
