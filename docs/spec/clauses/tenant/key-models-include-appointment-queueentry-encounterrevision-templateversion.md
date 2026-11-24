# ARC-01 clause 5 — key models

## Clause

Key models include Appointment, QueueEntry, EncounterRevision, TemplateVersion, Order, Specimen, Observation, ReportRevision, Signature, PatientRelease, ReviewTask, ExchangeGrant, DeliveryEvent, Attachment, TherapyEpisode, Prescription, Invoice, PaymentEntry, CareUnit, PatientUnitConnection, Conversation, Message, MessageAttachment, InstructionRevision, InstructionAcknowledgment and AuditEvent.

## Status

Partly proven

## Evidence

Test class: `com.aurevia.tenant.clauses.KeyModelsIncludeAppointmentQueueentryEncounterrevisionTemplateversionTest`
(`apps/api/src/test/java/com/aurevia/tenant/clauses/KeyModelsIncludeAppointmentQueueentryEncounterrevisionTemplateversionTest.java`).
It is a real-PostgreSQL acceptance test tagged `integration` using the synthetic-auth
profile and two synthetic tenants that reuse one opaque id, following the Flyway
and database harness of `IsolationAcrossSurfacesTest`. A dedicated database
(`aurevia_arc01_keymodels_it`) is migrated from `db/migrations`; all proof
reads use read-only `information_schema` / `pg_class` metadata and forced-RLS
row reads. No production file, migration, existing test or contract is changed.

Enabled test methods:

- `everyKeyModelRelationCarriesOrganizationIdAndOpaqueId` (rule met) — for each
  concrete relation that implements a named key model, asserts an
  `organization_id` of type `uuid`, an opaque `id` of type `uuid`, and that both
  `relrowsecurity` and `relforcerowsecurity` are on. One assertion group per
  relation, so a missing key or missing forced RLS names the candidate that broke.
- `naturalKeyAndColumnCarriedKeyModelsStayTenantOwned` (rule met with a recorded
  gap) — TemplateVersion is tenant-owned but keyed by `(organization_id, kind,
  version)`; the test proves its organization id and forced RLS and explicitly
  records the absent opaque `id`. It also proves that Specimen, Signature,
  MessageAttachment and InstructionRevision are carried as typed columns of
  tenant-owned opaque-id relations rather than as invented tables.
- `peerTenantCannotReadAKeyModelRowWithTheSameLocalId` (negative) — both tenants
  insert `care_unit` rows with the same opaque id; reading as the non-owner
  runtime role under forced RLS returns exactly the caller's own organization,
  and a call with no tenant context returns no row (fails closed).
- `keyModelRelationMissingOrganizationIdFailsTheProof` (negative) — the real
  global catalogue `clinical_template_baseline` has no `organization_id`, and the
  proof helper must reject it. This shows the positive proof can fail.
- `encounterRevisionRelationIsNotPresentSoTheProofRemainsPartial` (gap
  characterisation) — asserts that no `encounter_revision` relation exists, so
  the claim stays honest and the assertion flips when the gap is closed.

Survey of all 26 names in the clause (tool evidence from the migrated schema;
the task brief says 28, but the specification and story list 26):

| Clause model | Relation in the migrated schema | Definition | Tenant keys |
|---|---|---|---|
| Appointment | `public.appointment` | `V8__scheduling.sql` | organization_id + opaque id, forced RLS |
| QueueEntry | `public.worklist_entry` | `V12__checkin_worklist.sql` | organization_id + opaque id, forced RLS |
| EncounterRevision | none | `V15__clinical_note_signing.sql:26` keeps `encounter_id` as a plain uuid and defers the FK | **gap — no relation** |
| TemplateVersion | `public.template_version` | `V80__template_store.sql` | organization_id + forced RLS, **no opaque id** (key `(organization_id, kind, version)`) |
| Order | `public.exchange_order` | `V19__exchange_orders.sql` | organization_id + opaque id, forced RLS |
| Specimen | columns `specimen_type`, `site_code` on `public.lab_accession` | `V26__laboratory_accession.sql` | tenant-owned opaque-id relation, no dedicated specimen table |
| Observation | `public.trend_observation` | `V25__observation_trending.sql` | organization_id + opaque id, forced RLS |
| ReportRevision | `public.xch04_report_revision` | `V22__result_routing.sql` | organization_id + opaque id, forced RLS |
| Signature | columns `signed_at`, `signer_subject`, `attestation` on `public.clinical_note_revision` | `V15__clinical_note_signing.sql` | tenant-owned opaque-id relation, no dedicated signature table |
| PatientRelease | `public.patient_release` | `V24__patient_release.sql` | organization_id + opaque id, forced RLS |
| ReviewTask | `public.xch04_review` | `V22__result_routing.sql` | organization_id + opaque id, forced RLS |
| ExchangeGrant | `public.exchange_authorization` | `V19__exchange_orders.sql` | organization_id + opaque id, forced RLS |
| DeliveryEvent | `public.delivery_outbox`, `public.delivery_receipt` | `V55__durable_event_outbox.sql` | organization_id + opaque id, forced RLS |
| Attachment | `public.attachment` | `V82__attachment_store.sql` | organization_id + opaque id, forced RLS |
| TherapyEpisode | `public.therapy_episode` | `V77__build_12.sql` | organization_id + opaque id, forced RLS |
| Prescription | `public.prescription_draft` | `V43__prescription_drafts.sql` | organization_id + opaque id, forced RLS |
| Invoice | `public.finance_invoice` | `V45__finance.sql` | organization_id + opaque id, forced RLS |
| PaymentEntry | `public.finance_ledger_entry` | `V45__finance.sql` | organization_id + opaque id, forced RLS |
| CareUnit | `public.care_unit` | `V60__care_units.sql` | organization_id + opaque id, forced RLS |
| PatientUnitConnection | `public.patient_unit_connection` | `V60__care_units.sql` | organization_id + opaque id, forced RLS |
| Conversation | `public.conversation` | `V33__conversation_lifecycle.sql` | organization_id + opaque id, forced RLS |
| Message | `public.conversation_message` | `V33__conversation_lifecycle.sql` | organization_id + opaque id, forced RLS |
| MessageAttachment | column `message_id` on `public.attachment` | `V82__attachment_store.sql` | tenant-owned opaque-id relation, no dedicated message-attachment table |
| InstructionRevision | `public.instruction_fact_instruction` (`revision` column) | `V63__instruction_facts.sql` | organization_id + opaque id, forced RLS |
| InstructionAcknowledgment | `public.instruction_fact_acknowledgment` | `V63__instruction_facts.sql` | organization_id + opaque id, forced RLS |
| AuditEvent | `public.audit_event` | `V1__tenant_schema.sql` | organization_id + opaque id, forced RLS |

Production elements the test exercises: the Flyway migrations listed above and
the database policies and `app_org()` function they install. This test proves the
database half of tenant isolation only: it sets `app.current_org` over raw JDBC
and reads as the non-owner runtime role, which is provisioned with `NOBYPASSRLS`
by `infra/app/start.sh` (and recreated with the same option in the test harness).
`V2__rls_roles_policies.sql` defines `app_org()` and grants privileges to that
pre-existing role; it does not create the role. The in-application half —
`com.aurevia.tenant.TenantContext` and
`com.aurevia.tenant.TenantGuardTransactionManager`, which bind the
same GUC per transaction and fail closed when no context exists — is exercised
by the neighbouring HTTP isolation acceptance tests, not directly by this
schema-evidence test.

## Gap

Two parts of the clause are not proven and would change these files:

1. **EncounterRevision has no relation.** `V15__clinical_note_signing.sql` stores
   `encounter_id` as a plain uuid and explicitly defers the composite
   `(organization_id, patient_id, encounter_id)` foreign key until an encounter
   table lands. Completing it needs a new Flyway migration (with RLS and FORCE
   RLS), a Java model/store, and an enabled tenant-key proof: one separate story.
2. **TemplateVersion has no opaque `id`.** `V80__template_store.sql` keys it by
   `(organization_id, kind, version)`. Giving it an opaque id would change the
   migration, the template store and its callers, and the proof assertion: one
   separate story.

Specimen, Signature, MessageAttachment, ReviewTask and InstructionRevision are
implemented under different names or as columns of tenant-owned relations; they
are counted as proven above, not as gaps. This is a test-and-document-only story:
no migration, production class, contract or existing test is changed. The story
asked for unmet proofs to be left as `@Disabled("GAP: ...")` tests, but AGENTS.md
forbids a new disabled test and `scripts/check-constraints.py` rejects an added
`@Disabled` line; the gap is therefore kept green with an enabled characterising
assertion and recorded here instead, so no suppression or skipped test is added.
