# Clinical configuration approvals — decision pack

Status: Not approved

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
  dictionary before a coded field and preserves field meanings per version, and
  audit roles for define and approve:
  [BaselineTemplates](../../apps/api/src/main/java/com/aurevia/smartclinic/clinical/templates/BaselineTemplates.java),
  [FieldDictionary](../../apps/api/src/main/java/com/aurevia/smartclinic/clinical/templates/FieldDictionary.java),
  [FieldDefinition](../../apps/api/src/main/java/com/aurevia/smartclinic/clinical/templates/FieldDefinition.java),
  [FieldType](../../apps/api/src/main/java/com/aurevia/smartclinic/clinical/templates/FieldType.java),
  [TemplateVersion](../../apps/api/src/main/java/com/aurevia/smartclinic/clinical/templates/TemplateVersion.java),
  [TemplateService](../../apps/api/src/main/java/com/aurevia/smartclinic/clinical/templates/TemplateService.java),
  [V15 clinical note signing](../../db/migrations/V15__clinical_note_signing.sql).
- Versioned diagnosis and procedure catalogues with a named approval and an
  effective-version fold, and the durable production version store they use:
  [DiagnosisCatalogService](../../apps/api/src/main/java/com/aurevia/smartclinic/catalog/dictionary/DiagnosisCatalogService.java),
  [DiagnosisCode](../../apps/api/src/main/java/com/aurevia/smartclinic/catalog/dictionary/DiagnosisCode.java),
  [Approval](../../apps/api/src/main/java/com/aurevia/smartclinic/catalog/dictionary/Approval.java),
  [DurableVersionStore](../../apps/api/src/main/java/com/aurevia/smartclinic/catalog/dictionary/DurableVersionStore.java).
  The [VersionStore](../../apps/api/src/main/java/com/aurevia/smartclinic/catalog/dictionary/VersionStore.java)
  is the deterministic in-memory kernel behind the no-arg unit-test
  constructors, whose validation helpers are shared with the production
  `DurableVersionStore`.
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

The corrected entries below record engineering work that is now done; the gaps
that remain are human approvals or sibling decisions. The owner/evidence/status
table in this pack marks every capability `open` for that reason.

- `S-G5-02` — the named human approval decision this pack prepares; open and
  owned by a person, not satisfiable by a repository check.
- `S-G5-12` — per-site launch approvals and final G5 acceptance, where these
  artifacts are attached to the signed acceptance matrix.
- `BUILD-26` — done in code: the JDBC template store and migrations V80 and V87
  persist field dictionaries and template versions across a restart. What is
  still missing is human: the approved dictionary version and effective dates for
  each enabled family.
- `BUILD-17` — done in code: operational queues for unsigned encounters and
  missing coverage, including assign, resolve and reason. The site still supplies
  the named roster owners and backups those queues need.
- `BUILD-14` — done in code: unit service hours, response targets and named backup
  coverage, including escalation of overdue conversations. The site still supplies
  its own hours and named backup.
- Durable code-set storage — no longer an engineering gap: the versioned catalogue
  persists through `DurableVersionStore` and the configuration service. What is
  still missing is human: the approved version, mapping and effective dates for
  each code set.
- NO TASK — a dedicated, durable imaging-privilege registry. Privileges are
  read from approved configuration; the named interpreters, modalities and
  effective windows are site evidence a person must supply, not code this pack
  adds.
- `SPEC-DECISIONS` — the evidence note for section 10 (decisions required before
  production enablement), the section that assigns this decision. Related
  clause notes for the requirements cited here come from `SPEC-ADM-01-C01`
  (ADM-01 tenant configuration) and `SPEC-CLN-04-C02` (CLN-04 operative note
  fields). These `SPEC-*` evidence-note tasks write under `docs/spec/clauses/`,
  which is absent from this checkout, so their notes are not yet available as
  evidence. The authoritative clause text is in sections 9 and 10 of
  [SMARTCLINIC_V1_SPEC.md](../../SMARTCLINIC_V1_SPEC.md). Nothing here depends
  on those notes being present.

## Questions for the owner

A person must answer the questions below for the `open` rows of the
owner/evidence/status table in this pack. No answer is recorded in this
repository; simulated review and synthetic fixtures are not a substitute.

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

A site must hold each artifact below before enabling the related capability.
Every unchecked artifact belongs to an `open` row of the owner/evidence/status
table in this pack; a tick in this repository would be synthetic, and only the
accountable roles in section 10 can mark it complete.

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

## Approvals — owner, evidence and status

Every ADM-01 clinical approval item below carries an accountable owner role, an
evidence location and an honest status. No row is `approved`: this repository
holds synthetic engineering evidence only and records no human sign-off. `open`
means the decision or its signed evidence is not yet complete. No row is
`blocked`: the engineering gaps this pack previously recorded as outstanding are
closed in code (see the corrected entries in "What is missing"), so each item
waits only on a person's approval, effective dates and site-supplied artifacts.
Owner roles are the accountable role of section 10 of
[SMARTCLINIC_V1_SPEC.md](../../SMARTCLINIC_V1_SPEC.md), which assigns clinical
templates, code sets, interpreting privileges, result escalation and coverage to
the clinical safety lead and the site medical director. "Evidence location" is
where the signed artifact belongs; a repository path is given only where this
checkout can hold the evidence today. The still open on-call and site-coverage
evidence notes and the missed-coverage scenario evidence are G5 acceptance items
tracked by `S-G5-12`, not prerequisites that block a person's decision on these
rows.

| Item | Spec basis | Accountable owner role | Evidence location | Status |
|---|---|---|---|---|
| Clinical note templates and field dictionaries | ADM-01, CLN-04 | Clinical safety lead and site medical director | Approved version identifiers for each enabled CLN-04 family with field definitions, requiredness, the approved dictionary behind every coded field, permitted free-text languages and effective-from and effective-to dates; the versioned store is [TemplateService](../../apps/api/src/main/java/com/aurevia/smartclinic/clinical/templates/TemplateService.java) and [V15 clinical note signing](../../db/migrations/V15__clinical_note_signing.sql) | open — named approval and effective dates not yet supplied |
| Diagnosis code sets | ADM-01 | Clinical safety lead and site medical director | Approved diagnosis code-set version and code-to-display mapping with effective dates and evidence that notes saved against an earlier version keep their original meanings; [DiagnosisCatalogService](../../apps/api/src/main/java/com/aurevia/smartclinic/catalog/dictionary/DiagnosisCatalogService.java) now persists versions through `DurableVersionStore` and the configuration service | open — named approval, version, mapping and effective dates not yet supplied |
| Procedure code sets | ADM-01 | Clinical safety lead and site medical director | Approved procedure code-set version and code-to-display mapping with effective dates and evidence that notes saved against an earlier version keep their original meanings; the same [DiagnosisCatalogService](../../apps/api/src/main/java/com/aurevia/smartclinic/catalog/dictionary/DiagnosisCatalogService.java) holds versioned catalogues through `DurableVersionStore` | open — named approval, version, mapping and effective dates not yet supplied |
| Imaging interpretation privileges | ADM-01, RAD-01 | Clinical safety lead and site medical director | Approved modality list with the named interpreter and effective window for each modality and verification of the privilege; [RadPolicyCatalog](../../apps/api/src/main/java/com/aurevia/smartclinic/radiology/RadPolicyCatalog.java) reads approved configuration, while the named interpreters, modalities and windows are site evidence a person must supply | open — named interpreters, modalities and effective windows not yet supplied |
| Critical-result escalation rules | ADM-01, XCH-05 | Clinical safety lead and site medical director | Approved rule set with owner, deadline, primary and backup channels and recipients, read-back and human acknowledgment requirements and effective dates, plus a timed escalation drill with recorded human acknowledgment; [CriticalResultService](../../apps/api/src/main/java/com/aurevia/smartclinic/exchange/critical/CriticalResultService.java) and [V23 critical results](../../db/migrations/V23__critical_results.sql) | open — named approval, rule set and timed drill acknowledgment not yet supplied |
| Coverage rosters | ADM-01 | Clinical safety lead and site medical director | Named owner and backup per queue or unit with effective dates and the hand-off rule when the owner is unavailable; [ServiceProfileContract](../../apps/api/src/main/java/com/aurevia/smartclinic/messaging/service/ServiceProfileContract.java) and [V22 result routing](../../db/migrations/V22__result_routing.sql) | open — named owner, backup and effective dates not yet supplied |

## Approval record

Accountable roles from section 10 of [SMARTCLINIC_V1_SPEC.md](../../SMARTCLINIC_V1_SPEC.md).
The Name, Date and Decision columns are intentionally empty: the approver
completes them when the decision is made. This pack records no approval.

| Role | Name | Date | Decision |
|---|---|---|---|
| Clinical safety lead |  |  |  |
| Site medical director |  |  |  |
