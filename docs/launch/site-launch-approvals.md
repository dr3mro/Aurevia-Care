# Site launch approvals and final G5 acceptance

Status: **Not approved**

This is a decision pack. It assembles everything a person needs to decide per-site
launch approval and final G5 acceptance. It presents options, evidence and open
questions; it does not choose an option, name a jurisdiction, region, partner,
person or date, or record any approval. The [approval record](#approval-record)
is deliberately blank. A passing synthetic rehearsal in this repository is not
evidence that a site is ready.

## Decision needed

A person must decide, for each enabled site, whether the platform may be launched
there and whether final G5 acceptance is granted. The decision has three parts:

1. **Per-site launch** — confirm the site's approved workflow, release policy,
   market constraints, integrations, identity procedures, staffing and downtime
   drills.
2. **Messaging enablement per participating unit** — confirm each unit's patient
   eligibility rules, inbox ownership, after-hours guidance and backup coverage
   before messaging is enabled for that unit.
3. **Final G5 acceptance** — confirm there is no unresolved patient-safety,
   tenant-isolation, record-integrity or critical-escalation defect in the enabled
   scope.

Each part is a human decision in [tasks/](../../tasks/) task `S-G5-12`; this
pack supplies the material for it and decides none of it.

## Why it matters

- **OPS-01** requires the approved policy pack to define hosting region,
  applicable law, retention and legal hold, patient release, incident
  notification, supplier obligations and prescribing constraints; a U.S. launch
  where HIPAA applies additionally requires the security risk analysis, required
  agreements and vendor scope before handling ePHI, and jurisdiction-specific
  requirements and owners must be recorded before live pilot. A site cannot be
  approved without those policy decisions and their owners.
- **MSG-06** requires each enabled unit to publish service hours, response
  targets, appropriate topics and urgent contact instructions before sending, to
  require a named queue owner, backup coverage and overdue escalation, to show
  when the unit next handles messages outside hours, to surface backlog and
  breaches to supervisors, and to use the site's approved urgent and downtime
  channels. Enabling messaging without verifying patient eligibility, inbox
  ownership, after-hours guidance and backup coverage would leave messages with
  no accountable owner.
- The [G5 gate row](../../SMARTCLINIC_V1_SPEC.md) and [section 10](../../SMARTCLINIC_V1_SPEC.md)
  of the specification make these approvals and the six accountable-role
  decisions a precondition for production enablement; provider or partner traffic
  is enabled only after the corresponding evidence exists.

## Options

The owner chooses one option per site and per messaging unit. No option is
recommended or selected here.

| Option | What it commits the platform to | Risks | Evidence each needs |
|---|---|---|---|
| Approve the site as a whole | All listed site artifacts are signed and messaging units in the site are enabled with it | One missing artifact blocks the site; any later unit-level change needs re-approval | Full site evidence checklist below, a signed acceptance matrix, and the six section-10 decisions |
| Approve the site but enable messaging per unit | Site artifacts are signed; a unit goes live only after its own messaging evidence is signed | A not-yet-enabled unit has no in-app queue, so messages there need an agreed interim channel before enablement | Site artifacts plus, per unit, verified eligibility rules, named inbox owner, after-hours guidance and named backup coverage |
| Defer launch or decline approval | No production enablement; only synthetic adapters, mock partners and rehearsals remain active | Capability is delayed and the gap list stays open | A recorded decision to defer, the gap list with owners, and the unresolved-defect list for the enabled scope |

## What the repository already supports

These files exist today. They provide the contract, evaluators and rehearsal
evidence the owner will use; none of them records a site approval.

**G5 and site-readiness material**

- [G5 gate row and section 10 of the specification](../../SMARTCLINIC_V1_SPEC.md)
  — the required evidence and the accountable roles.
- [Production readiness evidence register](../operations/production-readiness/README.md)
  — the WEB-01 to WEB-13 evidence register; every row is `not started` and owner
  `unassigned`.
- [Launch checklist](../operations/production-readiness/launch-checklist.md)
  — the named-role items, including per-site Product Owner and clinical sign-off
  and the final G5 acceptance record; every row is `open`.
- [Deployment design handoff](../../infra/DEPLOYMENT.md) — topology and
  operational expectations, not evidence for any approval row.

**Messaging unit evidence (MSG-06)**

- [ServiceProfileContract.java](../../apps/api/src/main/java/com/aurevia/smartclinic/messaging/service/ServiceProfileContract.java)
  — models service hours, response target, topics, urgent instructions, named
  queue owner, backup coverage, escalation/urgent/downtime channels and the
  supervisor snapshot (backlog, unassigned, failed notifications, overdue
  responses).
- [ServiceProfileController.java](../../apps/api/src/main/java/com/aurevia/smartclinic/messaging/service/ServiceProfileController.java)
  and [ServiceHoursCalculator.java](../../apps/api/src/main/java/com/aurevia/smartclinic/messaging/service/ServiceHoursCalculator.java)
  — publish/approval entry point and zone-aware "next handling" calculation.
- [CareUnitController.java](../../apps/api/src/main/java/com/aurevia/smartclinic/messaging/units/CareUnitController.java)
  and [ConversationController.java](../../apps/api/src/main/java/com/aurevia/smartclinic/messaging/conversations/ConversationController.java)
  — unit directory and conversation/inbox entry points.
- [PatientAccessService.java](../../apps/api/src/main/java/com/aurevia/smartclinic/messaging/enrollment/PatientAccessService.java)
  — verified patient connection, the basis for patient eligibility.
- [MessagingGovernanceController.java](../../apps/api/src/main/java/com/aurevia/smartclinic/messaging/governance/MessagingGovernanceController.java)
  and [PatientFlowPresentationService.java](../../apps/api/src/main/java/com/aurevia/smartclinic/messaging/flows/PatientFlowPresentationService.java)
  — governance and patient-facing presentation of approved profiles.
- [GateEvidenceController.java](../../apps/api/src/main/java/com/aurevia/smartclinic/messaging/evidence/GateEvidenceController.java)
  — the gate-evidence entry point; it judges synthetic declarations, not a real
  site.

**Operations evidence and evaluators**

- [Operational queues handoff](../operations/ops-queues/README.md),
  [queue catalog](../operations/ops-queues/queue-catalog.md) and
  [evidence contract](../operations/ops-queues/evidence-contract.md) — the seven
  queues, named owners, backup/escalation actions and the honesty boundary. The
  catalog states that staffing levels, response targets, escalation deadlines and
  site role mapping are configuration decisions for the accountable owners.
- [Operational monitoring and runbooks](../operations/recovery/operational-runbooks.md),
  [recovery evidence](../operations/recovery/recovery-evidence.md),
  [drill evidence](../operations/recovery/drill-evidence.md),
  [restore procedure](../operations/recovery/restore-procedure.md) and
  [recovery targets](../operations/recovery/recovery-targets.md) — the 13
  signals, named owners, four runbook topics and the downtime-drill obligation.
- [Progressive rollout and production access](../operations/delivery-safety/rollout-and-production-access.md),
  [build and migration gates](../operations/delivery-safety/build-and-migration-gates.md)
  and the [delivery-safety evidence contract](../operations/delivery-safety/evidence-contract.md)
  — the declared rollout, rollback and audited, expiring production-access rules.
- [Capacity targets](../operations/capacity/capacity-targets.md) and
  [performance evidence](../operations/capacity/performance-evidence.md) — the
  load and availability evidence producers.
- [Onboarding sign-off and provenance](../operations/onboarding/sign-off-and-provenance.md)
  and [tenant retention and offboarding](../operations/tenant-lifecycle/retention-and-offboarding.md)
  — the controlled-batch sign-off and retention/legal-hold boundaries.
- [Decision records](../decisions/README.md),
  [0001 tenant isolation](../decisions/0001-g0-tenant-isolation.md),
  [0002 migration change safety](../decisions/0002-ops06-migration-change-safety.md)
  and [0003 persistence of in-memory stores](../decisions/0003-persistence-of-in-memory-stores.md)
  — the architecture and process constraints that a site's integration,
  isolation and durability evidence must respect.

## What is missing

- `S-G5-01` — market policy pack, hosting region, jurisdiction and data handling
  map (OPS-01, PRD-05): still open.
- `S-G5-02` — clinical templates, code sets, interpreting privileges and result
  escalation approvals: still open.
- `S-G5-03` — result release rules, sensitive information handling and patient
  identity recovery approvals (OPS-01): still open.
- `S-G5-04` — laboratory, PACS and pharmacy interface contracts, credentials and
  outage drills (OPS-01): still open.
- `S-G5-12` — the per-site approval and final G5 acceptance decision itself: still
  open; it is a human decision and cannot be satisfied by a worker.
- `SPEC-G5-01`, `SPEC-G5-02`, `SPEC-G5-03` — clause notes under
  `docs/spec/clauses/launch-gates/` for the three G5 evidence clauses: still open,
  and the `docs/spec/clauses/` directory does not exist in this checkout.
- `SPEC-OPS-01-C01`, `SPEC-OPS-01-C02`, `SPEC-OPS-01-C03` — clause notes under
  `docs/spec/clauses/policy/` for the OPS-01 policy clauses: still open.
- `SPEC-MSG-06-C01` to `SPEC-MSG-06-C06` — clause notes and tests under
  `docs/spec/clauses/messaging/` for the MSG-06 clauses: still open.
- `READY-11` — staff web OIDC login with session cookie and CSRF: not yet done
  (currently taken by a worker); its note records that listing and revoking a
  user's other sessions remains a follow-up for a person to approve, which bears
  on identity procedures.
- The E2E browser CI job listed as open in the
  [launch checklist](../operations/production-readiness/launch-checklist.md): no
  task in this checkout owns adding that workflow job.
- NO TASK — the actual signed site configuration, real partner contracts,
  selected jurisdiction/region, named individuals, dates and the final human
  acceptance. These are owner decisions; no worker task produces them.

## Questions for the owner

1. Which accountable role signs the per-site launch approval and the final G5
   acceptance record on behalf of each site?
2. Are all messaging units in a site enabled together, or is enablement decided
   unit by unit with interim routing for units not yet live?
3. Which sites and which participating units are in scope for this decision?
4. Have the six section-10 decisions been made, and where are the signed
   artifacts that evidence each one?
5. Are there any unresolved patient-safety, tenant-isolation, record-integrity or
   critical-escalation defects in the enabled scope?
6. Do the site role names map to the declared operator allowlist, and is the
   `retry` restriction to the two declared queues accepted for the site?
7. Is the jurisdiction-specific requirement set, including whether HIPAA applies,
   recorded with an owner before live pilot?

## Evidence checklist

One unchecked item per artifact a site must hold. Nothing is marked complete.

**Per site**

- [ ] Signed workflow approval for the site
- [ ] Signed release policy approval for the site
- [ ] Signed market constraints approval for the site
- [ ] Signed integrations approval for the site
- [ ] Signed identity procedures approval for the site
- [ ] Signed staffing approval for the site
- [ ] Downtime drill evidence for the site
- [ ] Signed market policy pack and data handling map
- [ ] Recorded hosting region, applicable law, retention and legal hold, patient release, incident notification, supplier obligations and prescribing constraints
- [ ] Where HIPAA applies: security risk analysis, required agreements and vendor scope verified before handling ePHI
- [ ] Approved clinical templates, code sets, interpreting privileges, results escalation and coverage, with timed workflow drills
- [ ] Approved result release, sensitive information handling and patient identity recovery rules, with enrollment rehearsal and access review
- [ ] Integration conformance results, approved credentials, reconciliation and outage drill
- [ ] Load report, restore evidence and on-call readiness
- [ ] Signed acceptance matrix and roll-forward/rollback criteria
- [ ] Progressive rollout, tested rollback and audited, expiring production-access record
- [ ] Named operational on-call and site clinical coverage owners with the four runbook topics
- [ ] Operational-queue snapshot and operator roster with the site role-name mapping

**Per messaging unit**

- [ ] Verified patient eligibility rules for the unit
- [ ] Named inbox owner for the unit
- [ ] Published after-hours guidance for the unit
- [ ] Named backup coverage for the unit
- [ ] Published service hours, response targets, appropriate topics and urgent contact instructions, shown before sending
- [ ] Overdue escalation configuration for the unit
- [ ] Supervisor visibility of backlog, unassigned work, failed notifications and overdue responses
- [ ] Approved urgent, escalation and downtime channels for time-sensitive care
- [ ] Outside-hours next-handling time published to patients

**Final G5 acceptance**

- [ ] No unresolved patient-safety defect in enabled scope
- [ ] No unresolved tenant-isolation defect in enabled scope
- [ ] No unresolved record-integrity defect in enabled scope
- [ ] No unresolved critical-escalation defect in enabled scope

## Approval record

Accountable roles are taken from section 10 of the
[specification](../../SMARTCLINIC_V1_SPEC.md). The Name, Date and Decision columns
are intentionally empty and must be completed only by the accountable people at
the G5 gate.

| Decision (section 10) | Accountable role (section 10) | Name | Date | Decision |
|---|---|---|---|---|
| Launch jurisdiction, hosting region and scope of pilot | Product owner and privacy lead |  |  |  |
| Clinical templates, code sets, interpreting privileges, results escalation and coverage | Clinical safety lead and site medical director |  |  |  |
| Result release, sensitive information handling and patient identity recovery | Privacy lead and clinical safety lead |  |  |  |
| Lab, PACS and pharmacy interface contracts | Integration lead and partner owner |  |  |  |
| Service capacity and recovery targets | Engineering and operations |  |  |  |
| Implementation priorities and release approval | Product, clinical, engineering and privacy owners |  |  |  |
