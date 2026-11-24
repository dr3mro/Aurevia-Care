# Partner interface decision pack — laboratory, PACS and pharmacy

Status: **Not approved**.

This pack collects what a person needs in order to decide whether any laboratory,
PACS (imaging) or pharmacy partner interface may be enabled for a site. It does
not choose an option, does not name a partner, jurisdiction, region, person or
date, and records no approval. Section 10 of the specification requires partner
traffic to remain disabled until the corresponding evidence exists; a synthetic
adapter or simulated drill in this repository is not that evidence.

## Decision needed

For each site and each partner kind below, an accountable person must decide
whether to enable live traffic, keep the partner synthetic, use an approved
manual workflow, or defer. The decision is per site and per partner kind; this
pack only frames it.

| Partner kind | Interface contract a person must accept | Conformance result a person must accept | Credentials a person must approve | Reconciliation evidence a person must accept | Timed outage drill a person must accept |
|---|---|---|---|---|---|
| Laboratory partner | The site's laboratory order/result interface: the agreed profile and channel (FHIR R4 `ServiceRequest`, `Specimen`, `Observation` and `DiagnosticReport`, and HL7 v2 ORM/ORU only where the contract requires them), with the authorised payload, recipient, purpose, retained-copy terms and duration stated in the contract. | A recorded conformance run against the partner's agreed profile, covering identifier matching, a duplicate, a correction, an out-of-order event and a partial outage, with the acceptor and the result recorded. | Approved, scoped partner credentials with an owner, a rotation and revocation record; no credential value appears in the repository or in evidence links. | Evidence that final, corrected and critical results are routed to a named owner and coverage queue; that patient, source, accession, destination and order identifiers are matched before import; and that unmatched or conflicting reports wait in the reconciliation queue instead of being imported. | A timed drill in which laboratory results stop arriving and orders accumulate, recording detection, fallback, backlog handling, recovery and reconciliation before writes resume. |
| PACS / imaging partner | The site's imaging interface: the agreed study association and reporting channel, including `ImagingStudy`/study-instance-UID and accession rules, the partner diagnostic workstation boundary, and the contract's position on DICOMweb. The platform boundary is the partner workstation, not an in-house image viewer or PACS. | A recorded conformance run covering study association, a wrong-patient or mismatched-accession refusal, a signed interpretation and an addendum reaching the referrer, with the acceptor and result recorded. | Approved, scoped partner credentials with an owner, a rotation and revocation record; no credential value appears in the repository or in evidence links. | Evidence that a study is linked only after patient and accession match, that a mismatch goes to review, and that corrections or addenda preserve prior versions and reopen review where required. | A timed drill in which the imaging association/reporting channel is unavailable, recording refusal to link on mismatch, manual fallback, backlog handling and reconciliation after recovery. |
| Pharmacy partner | The site's prescription channel: the agreed routing for `MedicationRequest`/e-prescribing or a locally approved printable prescription, with the actual channel, submission identifiers, pharmacy acceptance/rejection and the boundary that the platform does not hold inventory or perform dispensing. | A recorded conformance run covering submission, delivery confirmation, pharmacy status, an ambiguous transmission and the absence of a duplicate on retry, with the acceptor and result recorded. | Approved, scoped partner credentials with an owner, a rotation and revocation record; no credential value appears in the repository or in evidence links. | Evidence that submitted, delivery-confirmed, pharmacy-accepted/rejected, clarification, renewal and cancellation events are tracked separately, that dispensing evidence is distinguished from acceptance, and that an ambiguous transmission is verified with the partner before any retry. | A timed drill in which the pharmacy channel is unavailable, recording how an ambiguous transmission is held, verified and reconciled without sending a duplicate, and how the site falls back under its approved policy. |

## Why it matters

- **ARC-05 (Delivery)** requires atomic commit of state, audit and outbox,
  immutable event IDs, consumer deduplication inside the sender/type namespace,
  holding out-of-order changes, bounded retry, a restricted dead-letter queue,
  authorised and reasoned replays, and confirmation of order and patient
  identifiers before external data is linked. It also requires synthetic
  contract fixtures with duplicates, corrections and partial partner outages.
  These are the platform guarantees a partner conformance result must exercise;
  they are not proof that a particular partner satisfies them.
- **XCH-04** requires every final, corrected and critical result to be routed to
  a named owner and coverage queue, identifier matching before import, a
  reconciliation queue for unmatched or conflicting reports, and corrections
  that preserve prior versions and reopen review. Without accepted
  reconciliation evidence, a partner feed can silently mis-attribute results.
- **XCH-01**, **XCH-02** and **XCH-03** require the sending organisation to
  authorise the specific payload, recipient, purpose and duration; keep
  workflow, transmission and clinical review states separate; and require a
  recipient response to changes and revocation. A contract decision fixes these
  boundaries for a site.
- **PRD-02** states that partner-specific interfaces require approval and that
  manual workflows must clearly identify delivery and verification evidence.
- **LAB-02**, **RAD-01** and **RX-02** add the laboratory, imaging and pharmacy
  integrity rules that conformance results and reconciliation evidence must
  cover, including versioned critical policies, patient/accession verification
  before a study is linked, and separate prescription status events.
- **OPS-01** and section 10 require the market policy pack, data handling map and
  named owners before live operation; the G5 row of section 9 requires each site
  to approve its integrations and downtime drills and to have no unresolved
  patient-safety, tenant-isolation, record-integrity or critical-escalation
  defect in enabled scope.
- **OPS-05** requires monitoring of partner delivery and unmatched reports and
  downtime runbooks for manual capture, patient identification, critical contacts
  and supervised back-entry. A timed outage drill is how those runbooks are
  tested rather than merely written.

## Options

These are the alternatives a person may decide between. None is selected here.

| Option | What it commits the platform to | Risks | Evidence each needs |
|---|---|---|---|
| Enable live partner traffic for a site, using the interface family its contract specifies (FHIR R4, HL7 v2 where required, DICOMweb or e-prescribing routing where required) | Operating an approved adapter for that partner, scoped credentials with rotation and revocation, monitored delivery, reconciliation ownership, and the site's downtime and escalation procedures | Wrong-patient or wrong-order association; duplicate, corrected or out-of-order reports overwriting a final revision; credential exposure; silent delivery failure; an unstaffed reconciliation or coverage queue | Per partner: signed interface contract, conformance results, approved credential record, reconciliation evidence and a timed outage drill, each accepted by the accountable roles |
| Keep the partner synthetic (mock partner against the repository's fixtures) | No live partner connection; all testing remains on synthetic identities and event sequences | Synthetic conformance does not prove the real partner's profile, transport, credentials or behaviour; a later switch to live traffic still needs the full evidence set | Recorded synthetic conformance results and an explicit site record that live partner traffic stays disabled; the section 10 decision still outstanding |
| Use an approved manual workflow with documented delivery and verification evidence | Staff follow the site's approved manual process; the platform records the delivery and verification evidence it can attest to | Delivery and verification evidence can be incomplete or ambiguous; higher risk of re-entry errors, duplicates and delayed critical results; no automated partner monitoring | Site-approved manual procedure naming who delivers and who verifies, the recorded delivery/verification evidence, and a drill of the manual fallback |
| Defer enablement for the site or partner kind | No partner traffic; orders, results and prescriptions are handled within the site's existing approved scope | Delayed specialist access or result turnaround; order and result backlog; unmet pilot metrics | A site record that the interface is out of scope for the current approval, the backlog owner, and the trigger for revisiting the decision |

## What the repository already supports

All items below exist today and are synthetic. They show what the platform can
exercise; none is partner evidence, a credential record or an approval.

- The [ARC-05 durable delivery contract](../../contracts/events/README.md) and
  its [delivery service](../../apps/api/src/main/java/com/aurevia/events/DurableEventContract.java)
  describe atomic commit, idempotency, deduplication, bounded retry, dead-letter
  review and external-link confirmation.
- The [synthetic partner event fixtures](../../contracts/events/fixtures/partner-events.json)
  carry duplicate, correction, out-of-order, partial-outage and
  unconfirmed-identifier scenarios with expected outcomes.
- The [conformance fixtures](../../apps/api/src/main/java/com/aurevia/exchange/conformance/ConformanceFixtures.java)
  and [result routing service](../../apps/api/src/main/java/com/aurevia/exchange/result/ResultRoutingService.java)
  are the current in-repository surface for partner event conformance and
  result routing.
- The [REST API contract](../../contracts/openapi.yaml) is the documented entry
  point for the commands a partner-facing integration would drive.
- The [queue catalog](../../docs/operations/ops-queues/queue-catalog.md) defines
  the `unreconciled_reports`, `unacknowledged_critical_findings`,
  `rejected_exchanges` and `missing_coverage` operator queues and their states.
- The [production readiness evidence register](../../docs/operations/production-readiness/README.md)
  and [launch checklist](../../docs/operations/production-readiness/launch-checklist.md)
  list the human-owned items, including secrets held in a secret manager and the
  named on-call and incident runbooks.
- The [recovery drill evidence template](../../docs/operations/recovery/drill-evidence.md)
  and [operational runbooks](../../docs/operations/recovery/operational-runbooks.md)
  define how a timed drill is recorded and which signals, owners and downtime
  topics must be covered; the drill-evidence file is an empty template and the
  runbook document is a declared envelope, neither a completed drill.
- The [tenant isolation decision](../../docs/decisions/0001-g0-tenant-isolation.md)
  and [migration change-safety decision](../../docs/decisions/0002-ops06-migration-change-safety.md)
  record the isolation and change discipline that any partner adapter must keep.
- The authoritative clauses are in [section 7 (Interoperability and Identity),
  section 9 (G5) and section 10](../../.opencode/config/PROJECT_V1_SPEC.md) of the
  specification.

## What is missing

Task IDs below refer to tasks/ and are recorded as open, taken or done at the
time of writing; a task is not evidence that its artifact exists at a site.
`NO TASK` means no tracked task supplies that artifact. A PRD-02 partner-interface
clause note is tracked as `SPEC-PRD-02-C02` on the delivery line; it was not
present in this worker's branch when the pack was prepared, so the authoritative
specification sections above were used instead. Re-check the states below against
tasks/ before the approval meeting: they are a snapshot, not evidence.

| Artifact a person needs | Laboratory | PACS / imaging | Pharmacy |
|---|---|---|---|
| Signed interface contract | NO TASK (partner and site artifact) | `SPEC-LAYER-INTEROPERABILITY-3`, `SPEC-PRD-06-C02` define the boundary; the signed contract itself is NO TASK | `BUILD-10` records the channel; the signed contract itself is NO TASK |
| Conformance results | `BUILD-35`, `BUILD-37`, `BUILD-38` (all open); `BUILD-38` adds duplicates, corrections, reordering and outages | `BUILD-36` (open); a wrong-patient study is covered by `BUILD-11` (done) | `BUILD-10` (taken) covers channel evidence; partner conformance is NO TASK |
| Approved credentials | `SPEC-LAYER-IDENTITY-4`, `SPEC-LAYER-IDENTITY-5` (open); the launch-checklist secret-manager row has NO TASK | same as laboratory | same as laboratory |
| Reconciliation evidence | `BUILD-08` (taken), `READY-27` (done), `SPEC-XCH-04-C01`, `SPEC-XCH-04-C02`, `SPEC-XCH-04-C03` (open) | `BUILD-11` (done) covers study-link refusal; partner addendum reconciliation is NO TASK | `BUILD-10` (taken) covers status events; pharmacy reconciliation is NO TASK |
| Timed outage drill | `START-16` (taken, includes a partner outage drill sheet); the drill evidence template is empty until a drill is run | `START-16` (taken); the imaging-specific drill is NO TASK | `START-16` (taken); the pharmacy-specific drill is NO TASK |
| Human acceptance | `S-G5-04` (open) is the decision this pack prepares; no approval is recorded here | `S-G5-04` (open) | `S-G5-04` (open) |

Related operator documentation still pending: `START-04` (taken) covers key
rotation and incident runbooks. `SPEC-PRD-02-C02` (taken) is the clause note for
"partner-specific interfaces require approval"; it records the governance
boundary, not an approval or a site artifact.

## Questions for the owner

1. Which interface family does each partner contract actually require — FHIR R4
   with which partner-specific profile, HL7 v2 ORM/ORU, DICOMweb, or e-prescribing
   routing — and where is the signed, versioned contract held?
2. Who accepts the conformance result for each partner, and what counts as a
   pass for identifier matching, duplicates, corrections, out-of-order events
   and a partial outage?
3. Which credentials are approved for each partner, who owns them, how are they
   rotated and revoked, and how is a revoked credential proven to stop access?
4. Who owns the reconciliation queue for each feed, what is the coverage roster
   and backup, and what evidence shows no unmatched report was silently
   imported?
5. What are the timed outage drill's scenario, duration, injects, pass criteria
   and stop condition for each partner, and who declares the result?
6. What is the approved manual fallback when a partner is unavailable, and how
   is supervised back-entry kept free of duplicates?
7. Which site-specific policy pack, market constraints and hosting region apply,
   and where are the corresponding owner signatures recorded? These are not
   stated in this pack.
8. Under which conditions would live traffic be withheld or withdrawn, and who
   has authority to stop a partner feed?

## Evidence checklist

A site must hold every artifact below before its partner interface is enabled.
All boxes are intentionally unchecked; this pack asserts none of them.

### Laboratory partner

- [ ] Signed, versioned interface contract naming the agreed profile, channel, payload, recipient, purpose and duration
- [ ] Conformance result for the agreed profile, including a duplicate, a correction, an out-of-order event and a partial outage, with the acceptor recorded
- [ ] Approved credential record with an owner, scope, rotation schedule and revocation test evidence, with no credential value stored in the repository or evidence links
- [ ] Reconciliation evidence showing named-owner and coverage-queue routing, identifier matching before import, and unmatched or conflicting reports held for review
- [ ] Timed outage drill record with scenario, duration, detection, fallback, backlog handling, recovery and reconciliation before writes resumed

### PACS / imaging partner

- [ ] Signed, versioned interface contract naming the study-association and reporting channel, study-instance-UID and accession rules, and the partner workstation boundary
- [ ] Conformance result covering a valid study association, a wrong-patient or mismatched-accession refusal, a signed interpretation and an addendum reaching the referrer
- [ ] Approved credential record with an owner, scope, rotation schedule and revocation test evidence, with no credential value stored in the repository or evidence links
- [ ] Reconciliation evidence showing that no study was linked on a mismatch and that corrections or addenda preserve prior versions and reopen review
- [ ] Timed outage drill record with scenario, duration, detection, fallback, backlog handling, recovery and reconciliation before writes resumed

### Pharmacy partner

- [ ] Signed, versioned interface contract naming the prescription channel, submission identifiers and the division of responsibility for acceptance versus dispensing
- [ ] Conformance result covering submission, delivery confirmation, pharmacy status, an ambiguous transmission and no duplicate on retry
- [ ] Approved credential record with an owner, scope, rotation schedule and revocation test evidence, with no credential value stored in the repository or evidence links
- [ ] Reconciliation evidence showing separate status events, dispensing evidence distinguished from acceptance, and partner verification before any retry on ambiguity
- [ ] Timed outage drill record with scenario, duration, detection, held transmissions, verification, reconciliation and no duplicate sent after recovery

### Shared

- [ ] Site record stating that live partner traffic stays disabled until these artifacts are accepted
- [ ] Site policy pack and data handling map, with the applicable market constraints and the accountable owners named
- [ ] Monitoring evidence that partner delivery and unmatched reports are observed and alerted, with a named on-call owner and escalation trail
- [ ] Downtime and recovery runbooks covering manual capture, patient identification, critical contacts and supervised back-entry, with a recorded drill

## Acceptance register

The partner artifacts in [Decision needed](#decision-needed) and the shared
market-policy artifacts named by OPS-01 and the G5 row of section 9 are collected
here with the accountable owner role, where the repository evidence lives, the
human artifact that must be attached, and the current status. Owner roles come
from section 10 of [PROJECT_V1_SPEC.md](../../.opencode/config/PROJECT_V1_SPEC.md): the
interface-contract decision belongs to the integration lead and partner owner;
the market-policy topics belong to the roles named on each row. The evidence
locations are code and document facts, not approvals; they do not change who is
accountable. Status values are:

- `approved` — a named person has signed and effective dates are recorded;
- `open` — human approval is not yet supplied, and the repository can hold the
  data once it is;
- `blocked` — human approval is not yet supplied and a named engineering gap
  prevents a durable, versioned approval record.

All rows are `open` in this checkout. The register is itself the durable record a
person completes and holds the attachable human artifact; the cited repository
locations hold only platform evidence. No row is `blocked` by a persistence or
reachability gap. A passing repository check, a synthetic fixture or a simulated
review cannot move a row to `approved`.

| Item | Spec clause | Owner role | Evidence location (repository) | Human evidence to attach (open item) | Status |
|---|---|---|---|---|---|
| Laboratory — signed interface contract | OPS-01; PRD-02; XCH-01, XCH-02, XCH-03; section 9 G5 row; section 10 | Integration lead and partner owner | [ARC-05 durable delivery contract](../../contracts/events/README.md), [REST API contract](../../contracts/openapi.yaml) | Signed, versioned contract naming the agreed profile and channel, authorised payload, recipient, purpose, retained-copy terms and duration, with a named site acceptor | `open` |
| Laboratory — conformance result | ARC-05; LAB-02; XCH-04 | Integration lead and partner owner | [synthetic partner event fixtures](../../contracts/events/fixtures/partner-events.json), [conformance fixtures](../../apps/api/src/main/java/com/aurevia/exchange/conformance/ConformanceFixtures.java) | Recorded conformance run for identifier matching, a duplicate, a correction, an out-of-order event and a partial outage, with acceptor and result | `open` |
| Laboratory — approved credential record | OPS-02; section 10 | Integration lead and partner owner | [production readiness evidence register](../../docs/operations/production-readiness/README.md), [launch checklist](../../docs/operations/production-readiness/launch-checklist.md) | Approved scoped credential ownership record with owner, scope, rotation schedule and revocation test; no credential value in the repository or evidence links | `open` |
| Laboratory — reconciliation evidence | XCH-04; LAB-02; ARC-05 | Integration lead and partner owner | [queue catalog](../../docs/operations/ops-queues/queue-catalog.md), [result routing service](../../apps/api/src/main/java/com/aurevia/exchange/result/ResultRoutingService.java) | Evidence of final, corrected and critical results routed to a named owner and coverage queue, identifier matching before import, and unmatched or conflicting reports held for review | `open` |
| Laboratory — timed outage drill | OPS-05; section 9 G5 row; section 10 | Integration lead and partner owner | [recovery drill evidence template](../../docs/operations/recovery/drill-evidence.md), [operational runbooks](../../docs/operations/recovery/operational-runbooks.md) | Completed timed drill record with scenario, duration, detection, fallback, backlog handling, recovery and reconciliation before writes resumed | `open` |
| PACS / imaging — signed interface contract | OPS-01; PRD-02; XCH-01, XCH-02, XCH-03; RAD-01; section 9 G5 row; section 10 | Integration lead and partner owner | [ARC-05 durable delivery contract](../../contracts/events/README.md), [REST API contract](../../contracts/openapi.yaml) | Signed, versioned contract naming the study-association and reporting channel, study-instance-UID and accession rules, the partner workstation boundary and the contract's DICOMweb position | `open` |
| PACS / imaging — conformance result | ARC-05; RAD-01; XCH-04 | Integration lead and partner owner | [synthetic partner event fixtures](../../contracts/events/fixtures/partner-events.json), [conformance fixtures](../../apps/api/src/main/java/com/aurevia/exchange/conformance/ConformanceFixtures.java) | Recorded conformance run covering study association, a wrong-patient or mismatched-accession refusal, a signed interpretation and an addendum reaching the referrer, with acceptor and result | `open` |
| PACS / imaging — approved credential record | OPS-02; section 10 | Integration lead and partner owner | [production readiness evidence register](../../docs/operations/production-readiness/README.md), [launch checklist](../../docs/operations/production-readiness/launch-checklist.md) | Approved scoped credential ownership record with owner, scope, rotation schedule and revocation test; no credential value in the repository or evidence links | `open` |
| PACS / imaging — reconciliation evidence | XCH-04; RAD-01; ARC-05 | Integration lead and partner owner | [queue catalog](../../docs/operations/ops-queues/queue-catalog.md), [result routing service](../../apps/api/src/main/java/com/aurevia/exchange/result/ResultRoutingService.java) | Evidence that a study is linked only after patient and accession match, a mismatch goes to review, and corrections or addenda preserve prior versions and reopen review | `open` |
| PACS / imaging — timed outage drill | OPS-05; section 9 G5 row; section 10 | Integration lead and partner owner | [recovery drill evidence template](../../docs/operations/recovery/drill-evidence.md), [operational runbooks](../../docs/operations/recovery/operational-runbooks.md) | Completed timed drill with the association and reporting channel unavailable, recording refusal to link on mismatch, manual fallback, backlog handling and reconciliation after recovery | `open` |
| Pharmacy — signed interface contract | OPS-01; PRD-02; XCH-01, XCH-02, XCH-03; RX-02; section 9 G5 row; section 10 | Integration lead and partner owner | [ARC-05 durable delivery contract](../../contracts/events/README.md), [REST API contract](../../contracts/openapi.yaml) | Signed, versioned contract naming the prescription channel, submission identifiers, pharmacy acceptance and rejection, and the boundary that the platform holds no inventory and does not dispense | `open` |
| Pharmacy — conformance result | ARC-05; RX-02; XCH-04 | Integration lead and partner owner | [synthetic partner event fixtures](../../contracts/events/fixtures/partner-events.json), [conformance fixtures](../../apps/api/src/main/java/com/aurevia/exchange/conformance/ConformanceFixtures.java) | Recorded conformance run covering submission, delivery confirmation, pharmacy status, an ambiguous transmission and the absence of a duplicate on retry, with acceptor and result | `open` |
| Pharmacy — approved credential record | OPS-02; section 10 | Integration lead and partner owner | [production readiness evidence register](../../docs/operations/production-readiness/README.md), [launch checklist](../../docs/operations/production-readiness/launch-checklist.md) | Approved scoped credential ownership record with owner, scope, rotation schedule and revocation test; no credential value in the repository or evidence links | `open` |
| Pharmacy — reconciliation evidence | XCH-04; RX-02; ARC-05 | Integration lead and partner owner | [queue catalog](../../docs/operations/ops-queues/queue-catalog.md), [result routing service](../../apps/api/src/main/java/com/aurevia/exchange/result/ResultRoutingService.java) | Evidence that submitted, delivery-confirmed, accepted or rejected, clarification, renewal and cancellation events are tracked separately, dispensing is distinguished from acceptance, and an ambiguous transmission is verified before any retry | `open` |
| Pharmacy — timed outage drill | OPS-05; section 9 G5 row; section 10 | Integration lead and partner owner | [recovery drill evidence template](../../docs/operations/recovery/drill-evidence.md), [operational runbooks](../../docs/operations/recovery/operational-runbooks.md) | Completed timed drill with the pharmacy channel unavailable, recording how an ambiguous transmission is held, verified and reconciled without a duplicate, and how the site falls back under its approved policy | `open` |
| Hosting region and data residency | OPS-01; section 9 G5 row; section 10 | Product owner and privacy lead | [policy-pack.md](policy-pack.md), [deployment design handoff](../../infra/DEPLOYMENT.md) | Signed data handling map and cloud region attestation naming the approved region; this pack names none | `open` |
| Applicable law and launch jurisdiction | OPS-01; section 10 | Product owner and privacy lead | [policy-pack.md](policy-pack.md) | Signed applicable-law record and jurisdictional legal review for the selected market; this pack names no jurisdiction | `open` |
| Retention and legal hold rules | OPS-01; OPS-07 | Privacy lead | [policy-pack.md](policy-pack.md), [retention and offboarding](../../docs/operations/tenant-lifecycle/retention-and-offboarding.md) | Approved retention schedule by record class, named hold authority and hold placement and lift authorizations | `open` |
| Patient release rules | OPS-01; section 10 | Privacy lead and clinical safety lead | [policy-pack.md](policy-pack.md), [release and recovery rules](release-and-recovery-rules.md) | Approved release rules with policy version, permitted modes, withdrawal handling, named approver and effective dates | `open` |
| Incident notification | OPS-01; OPS-05 | Privacy lead and operations | [policy-pack.md](policy-pack.md), [incident response runbook](../../docs/operations/runbooks/incident-response.md) | Approved notification matrix with named internal roles, timeframes, external parties and named owners | `open` |
| Supplier obligations | OPS-01; section 10 | Integration lead and partner owner with privacy lead | [policy-pack.md](policy-pack.md) | Signed supplier and subprocessor register with executed agreements, audit rights and credential inventory | `open` |
| Prescribing constraints | OPS-01; RX-02 | Clinical safety lead and site medical director | [policy-pack.md](policy-pack.md), [pharmacy workflow](../../docs/operations/pharmacy-workflow/README.md) | Approved prescribing channel decision and market entitlement record with named approver and effective dates | `open` |
| HIPAA security risk analysis (U.S. launch only) | OPS-01 | Privacy lead | [policy-pack.md](policy-pack.md), [personal data inventory](../../docs/security/data-inventory.md) | Signed security risk analysis before any ePHI is handled, with a named reviewer | `open` |
| HIPAA required agreements (U.S. launch only) | OPS-01 | Privacy lead | [policy-pack.md](policy-pack.md) | Executed required agreements, including business-associate agreements where applicable, with named owner | `open` |
| HIPAA vendor scope (U.S. launch only) | OPS-01 | Privacy lead | [policy-pack.md](policy-pack.md), [personal data inventory](../../docs/security/data-inventory.md) | Approved vendor scope inventory listing every processor that may handle ePHI, with named owner | `open` |
| Jurisdiction-specific requirements and owners | OPS-01; section 10 | Product owner and privacy lead | [policy-pack.md](policy-pack.md) | Signed requirements-and-owners register with effective dates and review cycle; no jurisdiction or owner is named here | `open` |

The partner rows are one section-10 decision, "Lab, PACS and pharmacy interface
contracts", whose required evidence is "conformance results, approved credentials,
reconciliation and outage drill"; the signed contract is the partner artifact that
fixes the payload, recipient, purpose and duration. The G5 row of section 9
additionally requires each site to approve its integrations and downtime drills and
to have no unresolved patient-safety, tenant-isolation, record-integrity or
critical-escalation defect in enabled scope; that last item is a human acceptance
recorded in [site-launch-approvals.md](site-launch-approvals.md), not a row this pack
can close.

The shared rows are owned by the sibling [policy-pack.md](policy-pack.md) decision
pack, which records the same OPS-01 topics with its own owner, evidence and status
columns; scope of pilot, one market per deployment and the data handling map stay
there and are not duplicated here. That pack labels the hosting-region row
`blocked` under its broader definition (a missing region or cloud account is a
named prerequisite); this register keeps the row `open` because the status legend
above reserves `blocked` for a named engineering gap. Neither label is an approval.
The supplier-obligations row is also the S-G5-04 hand-off in that pack's
"What is missing" section; a task pointer is not evidence of a signed register.

## Synthetic example (illustrative shape only — not an approval)

The row below shows only the *shape* a completed acceptance entry takes. Every
value is a placeholder token, not a person, date, jurisdiction, region, partner,
credential or contract. It records no decision and must not be copied into the
[Approval record](#approval-record).

| Item | Partner (synthetic) | Evidence link (synthetic) | Acceptor (synthetic) | Effective from (synthetic) | Effective to (synthetic) | Status |
|---|---|---|---|---|---|---|
| Laboratory — signed interface contract | `SYNTHETIC-PARTNER-LAB` | `SYNTHETIC-EVIDENCE-LOCATION` | `SYNTHETIC-ACCEPTOR-INTEGRATION-LEAD` | `SYNTHETIC-DATE-FROM` | `SYNTHETIC-DATE-TO` | `open` |

A real row replaces each token with the approved value and attaches the evidence
named in the [Acceptance register](#acceptance-register). Until then the item stays
`open`.

## Approval record

Accountable roles from section 10 of the specification. Every cell below is
left empty on purpose; this pack records no decision.

| Role (section 10) | Name | Date | Decision |
|---|---|---|---|
| Integration lead |  |  |  |
| Partner owner |  |  |  |
