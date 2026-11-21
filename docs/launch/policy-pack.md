# Market policy pack — decision for approval (OPS-01, PRD-05)

Status: Not approved

This document prepares a decision for a person. It does not choose an option, name a
jurisdiction, hosting region, partner or date, or record an approval. It gathers the
questions, options, existing evidence and gaps so that the accountable roles in the
[Approval record](#approval-record) can decide and sign. The repository holds synthetic
engineering evidence only; nothing here is legal advice or a live deployment claim.

## Decision needed

Before a live pilot, the accountable roles must approve, for each production
deployment, a signed market policy pack and data handling map that fixes:

- the launch jurisdiction, the hosting region and the scope of the pilot;
- the applicable law for that market;
- retention periods and legal hold rules, including who may place and lift a hold;
- patient release rules, including permitted release modes and withdrawal handling;
- incident notification duties (internal roles, timeframes and external parties);
- supplier obligations (processors, partners, contracts, credentials and audit rights);
- prescribing constraints, including market entitlement and the permitted prescribing
  channel; and
- for a U.S. launch where HIPAA applies, the security risk analysis, the required
  agreements and the vendor scope before any ePHI is handled.

V1 permits one market per production deployment with a market-specific policy pack
(PRD-05). No market has been selected in this repository. All fields above remain for
the owner to complete.

## Why it matters

- **OPS-01** requires the hosting region, applicable law, retention and legal hold
  rules, patient release rules, incident notification, supplier obligations and
  prescribing constraints to be defined in the approved policy pack. For a U.S. launch
  where HIPAA applies, it requires verification of the security risk analysis, the
  required agreements and vendor scope before handling ePHI, and it requires
  jurisdiction-specific requirements and owners to be recorded before live pilot.
- **PRD-05** scopes the platform to one market per production deployment with a
  market-specific policy pack; the jurisdiction and data handling decisions are
  therefore a per-deployment gate, not a global default.
- Section 9 (G5 row) of the [specification](../../SMARTCLINIC_V1_SPEC.md) requires each
  site to approve its workflow, release policy, market constraints, integrations,
  identity procedures, staffing and downtime drills before launch.
- Section 10 assigns the decision "Launch jurisdiction, hosting region and scope of
  pilot" to the product owner and privacy lead, with a signed market policy pack and
  data handling map as its evidence. Neighbouring section 10 rows assign related
  approvals to the clinical, privacy, integration, engineering and operations roles.
- The engineering controls that support these topics are real but bounded: OPS-02
  (encryption, audit), OPS-04 (recovery), OPS-05 (monitoring and on-call), OPS-06
  (environment separation, signed builds, audited production access) and OPS-07
  (retention, export and offboarding) can be encoded and tested in this repository.
  A legal determination, a named region, a signed agreement or a person's approval
  cannot: those are human G5 gates.
- Prescribing and release are clinically sensitive. PRD-06 excludes controlled
  electronic prescribing and leaves clinical attestation to users; RX-02 requires
  prescriber attestation and market entitlement and an approved channel or locally
  approved printable prescription; XCH-06 makes patient release a separate
  authorization tied to an immutable revision and policy version. The market rules
  that activate these controls must be approved by a person.

## Options

The rows below are neutral archetypes for the owner to consider or reject. None is
chosen or recommended, and none names a real jurisdiction, region, partner or date.
Each row states what it would commit the platform to, its principal risks and the
evidence that would be required if it were selected.

| Option | What it commits the platform to | Risks | Evidence each needs |
|---|---|---|---|
| One named jurisdiction, one hosting region inside it, and a bounded pilot cohort | A single legal regime and one data-residency boundary for the pilot; all clinical and audit data stays in the named region; pilot membership is fixed and reviewed. | Legal advice may be incomplete; the chosen region may not satisfy a market's residency rule; a later expansion becomes a new decision. | Signed market policy pack and data handling map naming the jurisdiction and region; jurisdictional legal review; region configuration and private-network evidence; per-site pilot scope approval. |
| Several jurisdictions with per-site data residency and region pinning | Each tenant or site is bound to its own approved region; cross-region access is denied by policy; retention and release rules can vary by market. | Higher configuration and operational burden; risk of cross-region leakage or a wrong-region binding; divergent rule sets are harder to audit. | Per-market signed policy packs; data handling map binding tenant to region and law; isolation and fail-closed tests; per-site approval and periodic access review. |
| One named jurisdiction with a primary region and a disaster-recovery region inside the same jurisdiction | Primary and recovery locations both stay inside the named jurisdiction; backups and restore data never cross the boundary. | Correlated regional failure; higher cost; replication and identity-provider dependencies must be checked for spill-over. | Region-bound backup and point-in-time-recovery configuration; a data-flow map showing replicas and backups stay in jurisdiction; a restore drill with measured RPO and RTO. |
| Decision-pending posture with no live launch (synthetic adapters and mock partners only) | No production patient data is handled until the signed pack exists, as section 10 of the specification directs. | Schedule and partner idle time; pressure to admit real data before the decision is signed. | Signed pack before enablement; synthetic evidence only; the section 10 decision table closed. This is a posture, not an approval of any market. |
| A pilot-only deployment with a defined exit and tenant offboarding | A limited, time-bounded cohort with a documented end state: export, deletion of active data, backup expiry and audit, under an approved retention policy. | The pilot may drift into production use; offboarding evidence may be incomplete; legal hold may conflict with planned expiry. | Signed pilot scope and exit plan; retention and legal hold schedule; tenant export manifest and offboarding evidence; G5 per-site sign-off. |
| A retention and legal hold schedule approved per market | Named retention periods by record class, a named authority to place and lift holds, and binding hold scope that blocks deletion and backup expiry. | Periods that are too short or long for the market; an unauthorized hold lift; a hold that cannot be reconciled with backup expiry. | Approved retention schedule; named hold authority; hold-lift authorization record; synthetic retention, hold and offboarding evidence. |
| Patient release rules approved per market | An allowed set of release modes (for example immediate, timed or held) tied to an immutable revision and policy version, with withdrawal and correction handling. | Releasing to the wrong patient or revision; a withdrawn release remaining visible; market rules that differ from the clinical approval. | Approved release rules; XCH-06 policy-version binding evidence; withdrawal and correction tests; clinical and privacy sign-off (S-G5-03). |
| An incident notification and supplier obligations schedule | Named internal roles, response and notification timeframes, approved contact channels, and named processors and partners with contract, credential and audit obligations. | Missed statutory or contractual notification; an unnamed or unreviewed supplier; support access used without an approved incident record. | Incident runbook with named owners; supplier register and contract/agreement evidence; production access approval, audit and expiry record; on-call rota. |
| Prescribing under an approved market channel or locally approved printable prescription | Market entitlement checked, prescriber attestation recorded, the actual channel recorded, and no controlled electronic prescribing. | An unentitled prescriber or unapproved channel; ambiguous transmission retried into a duplicate; printable prescription used outside the approved market. | Approved prescribing channel decision; market entitlement and attestation evidence; RX-01/RX-02 tests; clinical safety lead and site medical director sign-off. |
| A U.S. launch where HIPAA applies, with a completed security risk analysis and required agreements | A documented risk analysis, required agreements (including business-associate agreements where applicable), and a defined vendor scope before ePHI is handled. | Risk analysis or agreements incomplete or stale; vendor scope that omits a processor; ePHI handled before the gate is met. | Signed risk analysis; executed agreements; vendor scope inventory; privacy lead review; evidence that no ePHI was handled before approval. |
| A non-U.S. launch under another applicable regime | The market's own law, regulator and cross-border transfer rules determine residency, retention, release and notification duties. | Assuming a U.S. framework applies; unaddressed local residency or transfer restrictions; an unnamed regulator or notification duty. | Local legal review; jurisdiction-specific requirements and named owners; data handling map; signed market policy pack. |

## What the repository already supports

The items below exist today. Each states what it actually proves; none supplies a
legal determination, a named region, a signed agreement or an approval. There is no
approved market policy pack in the repository: the only `policy` manifest is the
synthetic OPS-06 declaration at
[infra/delivery-safety/delivery-safety-policy.json](../../infra/delivery-safety/delivery-safety-policy.json),
which is not a market pack.

- [SmartClinic V1 specification](../../SMARTCLINIC_V1_SPEC.md) — section 8 (OPS-01),
  section 9 (G5 row) and section 10 (decision owners and evidence). Proves what must be
  decided; decides nothing.
- [Production readiness evidence register](../../docs/operations/production-readiness/README.md)
  — WEB-01 to WEB-13 evidence rows with unassigned owners and `not started` status.
  Proves the evidence register exists; not that TLS, WAF, headers, sessions or scans
  are configured.
- [Launch checklist — items that need a person](../../docs/operations/production-readiness/launch-checklist.md)
  — cloud and human items (TLS, WAF, OIDC MFA, restore drill, signed builds, rollout,
  on-call rota, privacy notice, per-site sign-off), all `open`. Proves the handoff is
  identified; not that any row is closed.
- [Retention, legal hold, deletion, offboarding and disputed access](../../docs/operations/tenant-lifecycle/retention-and-offboarding.md)
  — OPS-07 contract: a hold is binding, scope is bidirectional, lifts need named
  authority, and offboarding has five governed steps. Proves the enforcement form;
  explicitly cannot prove that a hold was placed by an authorized privacy owner.
- [Tenant export manifest](../../docs/operations/tenant-lifecycle/export-manifest.md)
  — OPS-07 export envelope, checksums, provenance, document authorizations and audit
  history. The document authorization labels are a fixed stand-in for a market policy
  pack, not a live policy binding; approval remains a human G5 item.
- [Operational monitoring and runbooks](../../docs/operations/recovery/operational-runbooks.md)
  — OPS-05 monitoring signals, named-owner fields and four downtime runbook topics.
  Proves a declared envelope; not that telemetry is live or a person is on call. It
  covers outage handling, not statutory breach-notification deadlines, and it states
  that the rota and escalation contacts live in the organization's approved policy
  pack.
- [Recovery runbook](../../docs/operations/recovery/runbook.md) and
  [restore procedure](../../docs/operations/recovery/restore-procedure.md) — recovery
  and restore steps used in drills; they do not record a real drill or measured RPO/RTO.
- [Delivery safety handoff](../../docs/operations/delivery-safety/README.md),
  [environment and fixture separation](../../docs/operations/delivery-safety/environment-and-fixtures.md)
  and [rollout and production access](../../docs/operations/delivery-safety/rollout-and-production-access.md)
  — OPS-06 contract for four isolated environments, synthetic-only fixtures, CI gates,
  signed builds, progressive rollout and separated, audited, expiring production
  access. The evaluator proves declared form; it cannot prove accounts, signatures,
  rollout or an approval are real.
- [Deployment design handoff](../../infra/DEPLOYMENT.md) — topology expectations
  (managed TLS ingress, private data services, same-digest promotion, restore drill).
  A design handoff, not a deployed region or a signed hosting decision. No
  infrastructure-as-code or configuration in the repository binds a hosting region,
  jurisdiction or data-residency rule; that is a genuine gap, not a hidden file.
- [Decision 0001 — tenant isolation](../../docs/decisions/0001-g0-tenant-isolation.md),
  [0002 — migration change safety](../../docs/decisions/0002-ops06-migration-change-safety.md)
  and [0003 — persistence of in-memory stores](../../docs/decisions/0003-persistence-of-in-memory-stores.md)
  — enforced tenant isolation, the migration change-safety gate, and the inventory of
  in-memory adapters. Engineering decisions; none fixes a market or region.
- [Prescription service](../../apps/api/src/main/java/com/aurevia/smartclinic/prescription/PrescriptionService.java)
  and [prescribing channel service](../../apps/api/src/main/java/com/aurevia/smartclinic/prescription/rx/RxService.java)
  — draft, catalog, allergy and channel support. Market entitlement and the choice of
  approved channel or printable prescription are configuration plus human decisions.
- [Patient release service](../../apps/api/src/main/java/com/aurevia/smartclinic/exchange/release/ReleaseService.java)
  — release authorization tied to an immutable revision and policy version (XCH-06).
  The market's release rules are not encoded here.

## What is missing

| Gap | Task ID or NO TASK |
|---|---|
| Signed market policy pack and data handling map: launch jurisdiction, hosting region, pilot scope and applicable law | S-G5-01 (human decision; open) |
| Evidence notes for the three OPS-01 clauses (policy definition, U.S./HIPAA, jurisdiction-specific owners) | SPEC-OPS-01-C01, SPEC-OPS-01-C02, SPEC-OPS-01-C03 |
| Evidence notes for the two PRD-05 clauses (onboarding/export/recovery/languages; one market per deployment) | SPEC-PRD-05-C01, SPEC-PRD-05-C02 |
| Approved retention schedule, legal hold authority and per-market retention rules | BUILD-42, BUILD-16 (code support); NO TASK for the signed schedule and named authority |
| Encryption of transport, data, queues, backups and object storage in the chosen region | SPEC-OPS-02-C01, BUILD-40 (object-storage adapter, open); NO TASK for a signed encryption and key-management topology |
| Approved patient release rules, sensitive information handling and identity recovery | S-G5-03 |
| Incident notification matrix: roles, statutory/contractual timeframes and external parties | START-04 (runbooks); NO TASK for the statutory notification matrix |
| Supplier and partner obligations, contracts, credentials and outage drills | S-G5-04; NO TASK for the signed supplier/subprocessor register and agreements |
| Prescribing market entitlement, approved channel and prescribing constraints | BUILD-10, SPEC-RX-02-C01, SPEC-RX-02-C02; NO TASK for the signed market entitlement decision |
| U.S. launch: signed security risk analysis, required agreements and vendor scope | SPEC-OPS-01-C02; NO TASK for the signed risk analysis and agreements themselves |
| Named hosting region and data residency cloud evidence | NO TASK (requires a cloud account and an owner decision) |
| Approved capacity and recovery targets and the per-site downtime drill | READY-54 provides the drill template; NO TASK for a conducted drill with measured RPO/RTO and for the sign-off |
| Personal data inventory for privacy review | START-15, START-91 |
| Per-site launch approvals and final G5 acceptance | S-G5-12, GATE-PACK-G5 |
| Recipient retention applied under law and policy | SPEC-XCH-03-C05 |

The clause notes under `docs/spec/clauses` referenced by several of the tasks above are
not present in the repository at the time of writing; those tasks are open.

## Questions for the owner

1. What launch jurisdiction and hosting region are approved for each deployment, and
   what is the exact scope of the pilot cohort? Where are backups, replicas, object
   stores, logs and metrics physically held, who may access them from outside the
   authorized region, and who approves a later change of region or pilot scope?
2. Which law, regulator and cross-border transfer rules apply in that jurisdiction
   and to each enabled site's service lines, and who is the named owner for recording
   jurisdiction-specific requirements, effective dates and review cycle?
3. What retention periods apply by record class (chart, signed revision, audit,
   messages, documents, metrics/logs and backups), what clock starts and stops each
   period, who may authorize an exception, who may place and lift a legal hold, and
   how does a hold interact with backup expiry, export, disputed access and tenant
   offboarding? What log and metric retention period is configured under WEB-06?
4. Which patient release modes are permitted, who approves a release and its policy
   version, and how are withdrawal, correction and a record access request handled?
5. What constitutes a notifiable privacy or security incident under the applicable
   law, who classifies severity, what are the deadlines to regulators, patients and
   partner organizations, who submits each notice, what content is permitted in a
   generic patient notification, and under what approved incident record may platform
   support access clinical data? How is this distinguished from a critical-result
   escalation failure, and who owns the weekly safety review?
6. Which processors, partners and suppliers handle clinical data or ePHI and in what
   location, what confidentiality, security, breach-notice, subcontracting, audit and
   return-or-deletion terms are mandatory, which interface contracts must be signed
   before enablement, and who maintains the supplier register and approves changes?
7. What prescribing constraints apply: is market entitlement required, which
   market-approved channel is used, which catalog and code-set versions and role
   entitlements apply, under what conditions may a locally approved printable
   prescription be produced, and who performs the clinical and architecture review
   required before rollout? How are PRD-06's exclusions (including controlled
   electronic prescribing) preserved?
8. If the launch is in the U.S. and HIPAA applies, who signs the security risk
   analysis, which agreements are required, what is the vendor scope, and how is it
   evidenced that no ePHI was handled before approval?
9. Which accountable roles sign this pack and the data handling map, and what is the
   effective date and review cycle?

## Evidence checklist

Artifacts a site must hold. Every box is unchecked; nothing below is held in this
repository as a signed or live artifact.

- [ ] Signed market policy pack naming the launch jurisdiction
- [ ] Signed data handling map naming the hosting region and data residency boundary
- [ ] Cloud account and resource inventory with region attestation and private-data-service configuration
- [ ] Approved pilot scope and per-site launch approval
- [ ] Jurisdictional legal review recording applicable law, regulator and transfer rules
- [ ] Record of jurisdiction-specific requirements and their named owners
- [ ] Approved retention schedule by record class and configured log/metric retention period
- [ ] Named legal hold authority and hold-placement/lift authorization record
- [ ] Policy-version binding evidence for document authorizations decided "as allowed by policy"
- [ ] Approved patient release rules with policy version and withdrawal handling
- [ ] Record access request and export procedure evidence
- [ ] Incident response and notification runbook with named owners, timeframes and external parties
- [ ] Supplier register with executed agreements, audit rights and credential inventory
- [ ] Approved prescribing channel decision and market entitlement record
- [ ] Signed security risk analysis (if a U.S. launch where HIPAA applies)
- [ ] Executed required agreements and vendor scope inventory (if a U.S. launch where HIPAA applies)
- [ ] Workforce training and sanctions evidence, and applicable state-law register (if a U.S. launch where HIPAA applies)
- [ ] Personal data inventory reviewed by the privacy lead
- [ ] On-call rota covering operational and site clinical owners
- [ ] Recorded downtime drill with measured RPO and RTO
- [ ] Independent penetration-test report with finding closure, external TLS scan and WAF rule test
- [ ] SBOM, signature and digest verification, progressive-rollout record, rehearsed rollback and approved expiring production-access records
- [ ] Published privacy notice and accessibility statement on the separate support page
- [ ] Per-site product owner and clinical sign-off, with no unresolved patient-safety, tenant-isolation, record-integrity or critical-escalation defect

## Approval record

Accountable roles from section 10 of the
[specification](../../SMARTCLINIC_V1_SPEC.md). This table is completed by the people
who decide; no field is filled here.

| Section 10 decision area | Accountable role | Name | Date | Decision |
|---|---|---|---|---|
| Launch jurisdiction, hosting region and scope of pilot | Product owner and privacy lead |  |  |  |
| Clinical templates, code sets, interpreting privileges, results escalation and coverage | Clinical safety lead and site medical director |  |  |  |
| Result release, sensitive information handling and patient identity recovery | Privacy lead and clinical safety lead |  |  |  |
| Lab, PACS and pharmacy interface contracts | Integration lead and partner owner |  |  |  |
| Service capacity and recovery targets | Engineering and operations |  |  |  |
| Implementation priorities and release approval | Product, clinical, engineering and privacy owners |  |  |  |
