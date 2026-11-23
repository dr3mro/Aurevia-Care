# Clinical configuration approvals — decision pack

Status: **Not approved** — all five items are open pending a named human
approval; none is enabled by a repository check. No value in this pack is an
approval, a contract or a credential.

This pack prepares the material a person needs for TODO task `S-G5-02`. It records
no decision, recommends no option, and names no jurisdiction, region, partner,
person or date. The approval record at the end is intentionally empty. The
accountable roles and the evidence they must attach come from section 10 and the
G5 row of section 9 of [SMARTCLINIC_V1_SPEC.md](../../SMARTCLINIC_V1_SPEC.md).

Each item below needs a **named clinical approver** and **effective dates**
(ADM-01). Until those are supplied and signed, the related capability stays
configured as pending rather than enabled for a pilot site.

## Decision needed

For each enabled pilot site, a person must approve:

1. **Clinical note templates and field dictionaries** — which version of each
   CLN-04 family (SOAP, operative, OB/GYN, perinatal and pediatric development)
   is in scope, the field definitions and requiredness approved for it, the
   approved dictionary behind every coded field, the permitted free-text
   languages, and the effective window of each approval.
2. **Diagnosis and procedure code sets** — which approved version of each code
   set is in force, how codes map to displays, and how historical field and code
   meanings are retained for notes saved against an earlier version.
3. **Imaging interpretation privileges** — which modalities are enabled, which
   named clinicians may interpret and sign each modality, and the approved
   effective window of each privilege (a future-dated or unapproved privilege
   must not apply early, and no blanket permission is implied).
4. **Critical-result escalation rules** — the responsible owner, the deadline,
   the approved primary and backup channels and recipients, read-back and human
   acknowledgment requirements, and the effective window of each rule.
5. **Coverage rosters** — the named owner and backup for each queue or unit that
   receives routed results or critical findings, with effective dates and the
   hand-off rule when the owner is unavailable.

Each approval names the person accountable and the dates from which, and if
applicable until which, it applies. The decision itself is recorded by the
roles in the approval record, not by this document.

## Approval register

The five item groups above are the single decision that section 10 of
[SMARTCLINIC_V1_SPEC.md](../../SMARTCLINIC_V1_SPEC.md) assigns to the clinical
safety lead and the site medical director. Each row names the accountable owner
role, where the repository evidence lives, the human artifact that must be
attached, and the current status. Status values are:

- `approved` — a named person has signed and effective dates are recorded;
- `open` — human approval is not yet supplied, and the repository can hold the
  data once it is;
- `blocked` — human approval is not yet supplied and a named engineering gap
  prevents a durable, versioned approval record.

All five rows are `open` in this checkout: the durable stores and HTTP entry
points exist, so the only thing outstanding is the named human decision. No row
is `blocked` by a persistence or reachability gap. A passing repository check or
a synthetic review cannot move a row to `approved`.

| Item | Spec clause | Owner role | Evidence location (repository) | Human evidence to attach (open item) | Status |
|---|---|---|---|---|---|
| 1. Clinical note templates and field dictionaries | ADM-01 C01/C02; CLN-04 | Clinical safety lead and site medical director | `apps/api/src/main/java/com/aurevia/smartclinic/clinical/templates/` (`BaselineTemplates`, `FieldDictionary`, `TemplateService`, `JdbcTemplateStore`), `db/migrations/V15__clinical_note_signing.sql`, `db/migrations/V80__template_store.sql` | Approved version identifier per family, field definitions and requiredness, approved dictionaries, permitted free-text languages, named approver, effective-from/to dates, retained audit and version history | `open` |
| 2. Diagnosis and procedure code sets | ADM-01 C01/C02 | Clinical safety lead and site medical director | `apps/api/src/main/java/com/aurevia/smartclinic/catalog/dictionary/` (`ClinicalCatalogController`, `DurableVersionStore`, `VersionPersistence`, `DiagnosisCode`, `Approval`), `db/migrations/V6__catalog_fold.sql` | Approved code-set version and mapping, named approver, effective dates, evidence that notes saved against an earlier version keep their original meanings | `open` |
| 3. Imaging interpretation privileges | ADM-01 C01/C02; RAD-01 | Clinical safety lead and site medical director | `apps/api/src/main/java/com/aurevia/smartclinic/radiology/` (`RadPolicyCatalog`, `ImagingService`, `ImagingController`), `apps/api/src/main/java/com/aurevia/smartclinic/authz/specialty/SpecialtyRoles.java` | Approved modality list, named interpreter per modality, effective window per privilege, verification of privilege, timed workflow drill | `open` |
| 4. Critical-result escalation rules | ADM-01 C01/C02; XCH-05 | Clinical safety lead and site medical director | `apps/api/src/main/java/com/aurevia/smartclinic/exchange/critical/` (`CriticalResultService`, `CriticalResultRepository`, `CriticalContract`, `CriticalResultController`), `db/migrations/V23__critical_results.sql` | Approved owner, deadline, primary and backup channels and recipients, read-back and human acknowledgment, named approver, effective dates, timed escalation drill with recorded acknowledgment | `open` |
| 5. Coverage rosters | ADM-01 C01/C02 | Clinical safety lead and site medical director | `apps/api/src/main/java/com/aurevia/smartclinic/exchange/result/ResultRules.java`, `apps/api/src/main/java/com/aurevia/smartclinic/messaging/service/ServiceProfileContract.java`, `db/migrations/V22__result_routing.sql` | Named owner and backup per queue or unit, effective dates, hand-off notice, drill showing a critical result reaching a staffed backup queue | `open` |

The owner role is the same for all five rows because they are one section-10
decision, "Clinical templates, code sets, interpreting privileges, results
escalation and coverage", whose required evidence is "approved dictionaries and
timed workflow drills". The G5 row of section 9 additionally requires the site
to approve its workflow, staffing and downtime drills and to have no unresolved
critical-escalation defect. The evidence locations are code facts, not
approvals; they do not change who is accountable.

## Synthetic example (illustrative shape only — not an approval)

The row below shows only the *shape* a completed approval takes. Every value is
a placeholder token, not a person, date, jurisdiction, partner or credential.
It records no decision and must not be copied into an approval record.

| Item | Version (synthetic) | Approver (synthetic) | Effective from (synthetic) | Effective to (synthetic) | Status |
|---|---|---|---|---|---|
| 1. Templates / dictionaries | `SYNTHETIC-TEMPLATE-VERSION` | `SYNTHETIC-APPROVER-CLINICAL-SAFETY` | `SYNTHETIC-DATE-FROM` | `SYNTHETIC-DATE-TO` | `open` |

A real row replaces each token with the approved value and attaches the evidence
named in the register. Until then the item stays `open`.

## Requirement mapping

| Requirement / clause | What it requires | Where this pack addresses it | Status |
|---|---|---|---|
| ADM-01 C01 — tenant admins configure facilities, referral partner directory, scheduling policies, templates, catalogs, coverage rosters, critical-result escalation rules and patient release policies | Configuration capability exists and is tenant-admin scoped | [What the repository already supports](#what-the-repository-already-supports), register items 1–5 | `open` — human configuration approval pending |
| ADM-01 C02 — clinical/market policies require named approval, effective dates, audit and version history | Named approval, effective dates, audit and version history on each policy | [Evidence checklist](#evidence-checklist), [Approval register](#approval-register), [Approval record](#approval-record) | `open` — no named approval recorded |
| ADM-01 C03 — pilot site onboarding tests each configured channel | Each configured channel is tested at onboarding | [Evidence checklist](#evidence-checklist) drill items, [Questions for the owner](#questions-for-the-owner) | `open` — drill evidence to be attached by the site |
| Section 9 G5 row | Site approves workflow, staffing and downtime drills; no unresolved critical-escalation defect | register item 4, [What is missing](#what-is-missing) | `open` |
| Section 10 decision row | Clinical safety lead and site medical director accountable; approved dictionaries and timed workflow drills as evidence | [Approval register](#approval-register), [Approval record](#approval-record) | `open` |

## Why it matters

- **ADM-01** requires tenant admins to configure templates, catalogs, coverage
  rosters and critical-result escalation rules, and requires clinical policies
  to carry named approval, effective dates, audit and version history. Without
  those, an enabled site has no authorized configuration to point at.
- **CLN-04** requires clinical governance to approve field dictionaries and
  requiredness, and requires the historical meaning of a field to be preserved
  with each saved version. Approving a template version without its dictionary
  and effective dates would let a coded value be captured against an
  unapproved meaning.
- The G5 row of section 9 requires each site to approve its workflow, staffing
  and downtime drills and to have no unresolved critical-escalation defect.
  Section 10 assigns this decision to the clinical safety lead and the site
  medical director, with approved dictionaries and timed workflow drills as
  evidence. Related rules are **RAD-01** (imaging privileges) and **XCH-05**
  (critical-result escalation and human acknowledgment).

Simulated engineering review and synthetic fixtures are not a substitute for
these signatures; they only show that the repository can hold the approved data
once a person supplies it.

## Options

The rows are options for the owner to consider. None is selected or recommended
here; declining a row leaves that capability unapproved for the site rather
than substituting a default.

| Option | What it commits the platform to | Risks | Evidence each needs |
|---|---|---|---|
| Approve versioned clinical templates and field dictionaries for the pilot families | Serve only approved versions; bind every coded field to an approved dictionary; retain each saved note's field meanings | A coded field whose dictionary is not approved, a requiredness change that invalidates saved notes, or a future-dated version taking effect early | Approved version identifiers, field definitions and requiredness, approved dictionaries, named approver, effective-from and effective-to dates, retained audit and version history |
| Approve diagnosis and procedure code sets | Restrict coded entry to approved code sets and fold only approved versions effective at the evaluation instant | Wrong or retired codes in clinical records; loss of the meaning a historical note was saved with | Approved code-set version and mapping, named approver, effective dates, evidence that old notes keep their original meanings |
| Approve imaging interpretation privileges by modality | Permit a signature only while the named interpreter holds an approved, effective privilege for that modality | An unqualified or unapproved signer, a blanket permission, or a future-dated privilege applied early | Approved modality list, named interpreter per modality, effective windows, verification of privilege, timed workflow drill |
| Approve critical-result escalation rules | Record owner, deadline, primary and backup channel/recipient, read-back and human acknowledgment on each critical finding; escalate on deadline | A result silently reassigned to an unstaffed queue, a missed deadline, or delivery acknowledged as clinical receipt | Approved rule set with owner, deadline, channels and recipients, named approver, effective dates, timed escalation drill with recorded acknowledgment |
| Approve coverage rosters per unit or queue | Route results and critical findings to a named owner with a named backup; expose roster gaps instead of concealing them | A queue with no owner or backup, or an out-of-hours gap with no notice | Named owner and backup, effective dates, hand-off notice, drill showing a critical result reaching a staffed backup queue when the owner is unavailable |

## What the repository already supports

These are code and migration facts in this checkout, not approvals:

- Versioned, immutable configuration with a named approver, an effective date,
  audit and version history, plus an effective-version fold that does not apply
  a future-dated entry early: [V5 tenant configuration](../../db/migrations/V5__tenant_configuration.sql),
  [ConfigurationController](../../apps/api/src/main/java/com/aurevia/smartclinic/web/ConfigurationController.java),
  [AccessRepository](../../apps/api/src/main/java/com/aurevia/smartclinic/authz/access/AccessRepository.java).
- Catalog entries folded from the same approved configuration store:
  [V6 catalog fold](../../db/migrations/V6__catalog_fold.sql),
  [CatalogController](../../apps/api/src/main/java/com/aurevia/smartclinic/web/CatalogController.java).
- Synthetic baseline field sets for the five CLN-04 template families, an
  approved-flag field dictionary, a versioned template service that approves a
  dictionary before a coded field and preserves field meanings per version,
  audit roles for define and approve, and a tenant-guarded JDBC store so an
  approved dictionary and version survive a restart:
  [BaselineTemplates](../../apps/api/src/main/java/com/aurevia/smartclinic/clinical/templates/BaselineTemplates.java),
  [FieldDictionary](../../apps/api/src/main/java/com/aurevia/smartclinic/clinical/templates/FieldDictionary.java),
  [FieldDefinition](../../apps/api/src/main/java/com/aurevia/smartclinic/clinical/templates/FieldDefinition.java),
  [FieldType](../../apps/api/src/main/java/com/aurevia/smartclinic/clinical/templates/FieldType.java),
  [TemplateVersion](../../apps/api/src/main/java/com/aurevia/smartclinic/clinical/templates/TemplateVersion.java),
  [TemplateService](../../apps/api/src/main/java/com/aurevia/smartclinic/clinical/templates/TemplateService.java),
  [JdbcTemplateStore](../../apps/api/src/main/java/com/aurevia/smartclinic/clinical/templates/JdbcTemplateStore.java),
  [V15 clinical note signing](../../db/migrations/V15__clinical_note_signing.sql),
  [V80 template store](../../db/migrations/V80__template_store.sql).
- Versioned diagnosis and procedure catalogues with a named approval and an
  effective-version fold, durable configuration-backed version persistence and
  an HTTP administration controller, all using the shared version contract:
  [DiagnosisCatalogService](../../apps/api/src/main/java/com/aurevia/smartclinic/catalog/dictionary/DiagnosisCatalogService.java),
  [DiagnosisCode](../../apps/api/src/main/java/com/aurevia/smartclinic/catalog/dictionary/DiagnosisCode.java),
  [Approval](../../apps/api/src/main/java/com/aurevia/smartclinic/catalog/dictionary/Approval.java),
  [VersionStore](../../apps/api/src/main/java/com/aurevia/smartclinic/catalog/dictionary/VersionStore.java),
  [DurableVersionStore](../../apps/api/src/main/java/com/aurevia/smartclinic/catalog/dictionary/DurableVersionStore.java),
  [VersionPersistence](../../apps/api/src/main/java/com/aurevia/smartclinic/catalog/dictionary/VersionPersistence.java),
  [ClinicalCatalogController](../../apps/api/src/main/java/com/aurevia/smartclinic/catalog/dictionary/ClinicalCatalogController.java).
- Approved, effective-dated imaging policies where a signature needs an
  approved privilege for that specific modality and future dates do not apply
  early: [RadPolicyCatalog](../../apps/api/src/main/java/com/aurevia/smartclinic/radiology/RadPolicyCatalog.java),
  [ImagingService](../../apps/api/src/main/java/com/aurevia/smartclinic/radiology/ImagingService.java),
  [ImagingController](../../apps/api/src/main/java/com/aurevia/smartclinic/radiology/ImagingController.java),
  [SpecialtyRoles](../../apps/api/src/main/java/com/aurevia/smartclinic/authz/specialty/SpecialtyRoles.java).
- Critical-result registration, assignment, escalation attempts, read-back,
  human acknowledgment, roster-gap facts and an operational report of overdue
  alerts, roster gaps and failed notifications:
  [CriticalResultService](../../apps/api/src/main/java/com/aurevia/smartclinic/exchange/critical/CriticalResultService.java),
  [CriticalResultRepository](../../apps/api/src/main/java/com/aurevia/smartclinic/exchange/critical/CriticalResultRepository.java),
  [CriticalContract](../../apps/api/src/main/java/com/aurevia/smartclinic/exchange/critical/CriticalContract.java),
  [CriticalResultController](../../apps/api/src/main/java/com/aurevia/smartclinic/exchange/critical/CriticalResultController.java),
  [V23 critical results](../../db/migrations/V23__critical_results.sql).
- Named owner and coverage queue on routed results and on messaging unit
  profiles, including backup coverage and escalation channels:
  [ResultRules](../../apps/api/src/main/java/com/aurevia/smartclinic/exchange/result/ResultRules.java),
  [ServiceProfileContract](../../apps/api/src/main/java/com/aurevia/smartclinic/messaging/service/ServiceProfileContract.java),
  [V22 result routing](../../db/migrations/V22__result_routing.sql).
- The evidence register and the person-owned launch checklist that a site owner
  completes: [production readiness register](../operations/production-readiness/README.md),
  [launch checklist](../operations/production-readiness/launch-checklist.md).

## What is missing

- `S-G5-02` — the named human approval decision this pack prepares; open and
  owned by a person, not satisfiable by a repository check.
- `S-G5-12` — per-site launch approvals and final G5 acceptance, where these
  artifacts are attached to the signed acceptance matrix.
- `BUILD-17` — operational queues for unsigned encounters and missing coverage,
  including assign, resolve and reason.
- `BUILD-14` — unit service hours, response targets and named backup coverage,
  including escalation of overdue conversations.
- NO TASK — a dedicated, durable imaging-privilege registry. Privileges are
  read from approved configuration; the named interpreters, modalities and
  effective windows are site evidence a person must supply, not code this pack
  adds.
- `SPEC-DECISIONS` — the evidence note for section 10 (decisions required before
  production enablement), the section that assigns this decision. Related
  clause notes for the requirements cited here come from `SPEC-ADM-01-C01`
  (ADM-01 tenant configuration) and `SPEC-CLN-04-C02` (CLN-04 operative note
  fields). These `SPEC-*` evidence-note tasks write under `docs/spec/clauses/`.
  ADM-01 and CLN-04 clause notes are present in that directory, including the
  [ADM-01 tenant-configuration note](../spec/clauses/configuration/tenant-admins-configure-facilities-referral-partner-directory.md)
  and the [CLN-04 field-dictionary note](../spec/clauses/chart/clinical-governance-approves-field-dictionaries-requiredness.md).
  A note being present does not close the task that produced it, and none of these
  notes records a site approval. The authoritative clause text is in sections 9
  and 10 of [SMARTCLINIC_V1_SPEC.md](../../SMARTCLINIC_V1_SPEC.md). The decision
  here depends on a named human approval, not on those notes.

## Questions for the owner

- Which CLN-04 template families are enabled for the pilot, and which approved
  version, field dictionary and requiredness set applies to each?
- Who is the named clinical approver for each of the five item groups above, and
  what are the effective-from and effective-to dates of each approval?
- Which diagnosis and procedure code-set versions are in force, and how must a
  note saved against an earlier version keep its original meanings?
- Which imaging modalities are enabled, and which named clinicians hold an
  effective privilege for each?
- What are the critical-result owner, deadline, primary and backup channels and
  recipients, and what read-back and human acknowledgment are required?
- Who is the named owner and backup on each coverage roster, and what hand-off
  rule applies when the owner is unavailable?
- What drill evidence counts as a timed workflow drill for the G5 row of
  section 9, who records it, and where is it retained?
- Should any capability remain unapproved for the first pilot site and be
  enabled only at a later site?

## Evidence checklist

A site must hold each artifact below before enabling the related capability:

- [ ] Approved clinical template versions for each enabled family, with field definitions, requiredness and permitted free-text languages
- [ ] Approved field dictionary for every coded field, with named clinical approver and effective dates
- [ ] Approved diagnosis code-set version, with named approver and effective dates
- [ ] Approved procedure code-set version, with named approver and effective dates
- [ ] Approved imaging modality list with a named interpreter privilege and effective window per modality
- [ ] Approved critical-result escalation rules: owner, deadline, primary and backup channels and recipients, read-back and human acknowledgment, named approver and effective dates
- [ ] Approved coverage rosters with a named owner and backup per queue or unit, with effective dates
- [ ] Immutable audit and version history for every approval above
- [ ] Timed workflow drill record for critical-result escalation, including recorded human acknowledgment
- [ ] Timed drill record showing a critical result reaching a staffed backup queue when the owner is unavailable
- [ ] Evidence that a future-dated approval does not take effect early and that unapproved configuration denies rather than defaults
- [ ] Per-site acceptance matrix linking each artifact to the accountable roles in section 10

## Approval record

Accountable roles from section 10 of [SMARTCLINIC_V1_SPEC.md](../../SMARTCLINIC_V1_SPEC.md).
The Name, Date and Decision columns are intentionally empty: the approver
completes them when the decision is made. Status stays `open` until a person
signs. This pack records no approval.

| Role | Name | Date | Decision | Status |
|---|---|---|---|---|
| Clinical safety lead |  |  |  | `open` |
| Site medical director |  |  |  | `open` |
