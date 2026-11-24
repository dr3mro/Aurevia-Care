# Aurevia V1 delivery plan

This is the canonical index of all planned stories, grouped by delivery gate. `TODO.md` owns the single WIP-one story; `PROGRESS.md` owns chronological evidence; `BUGS.md` owns observed defects; `PROJECT_V1_SPEC.md` is authoritative for requirements.

## How to maintain this file

- Every story has a checkbox: `- [ ]` is planned or in progress, `- [x]` is done.
- Only change a checkbox to `[x]` when the story's acceptance evidence is recorded in `PROGRESS.md` and all applicable gates pass. Add the evidence reference on the same line.
- Keep WIP one: at most one story may be `in progress` in `TODO.md`, regardless of how many checkboxes are filled.
- When a story is split, added or blocked, update this file and `TODO.md` in the same change.

## Gate G0 — platform (IAM, tenant model, catalog, audit, infrastructure)

Gate evidence: two tenants with identical local IDs remain isolated across API, search, files, workers and exports; revocation applies on the next access; concurrent connection and transaction tests pass.

- [x] S-G0-01 — Tenant isolation foundation: membership-derived tenant context, forced RLS, non-owner runtime role, append-only audit (IAM-01, IAM-02, ARC-01, ARC-02, OPS-02). Evidence: `PROGRESS.md` G0-01 rows; 44 tests, JaCoCo 100%; green CI https://github.com/dr3mro/Aurevia-Care/actions/runs/44442227258 on SHA `f9bc461`; merged in `e1dc7cd`.
- [x] S-G0-02 — Staff lifecycle: invite and deactivate staff, immutable membership and role history, permission change on next protected request (IAM-02, IAM-01, OPS-02). Evidence: `PROGRESS.md` S-G0-02 rows; 81 tests, JaCoCo 100%; V4 append-only history and folded current membership; local security and DB gates green, CI pending.
- [x] S-G0-03 — Tenant configuration, catalogs, broader audit and infrastructure foundations (ADM-01, OPS-02, OPS-06). Configuration, catalog and audit-review slices done and CI-green (V5/V6, 158 tests, JaCoCo 100%); OPS-06 environment/rollout work remains as a separate infrastructure follow-on.
- [ ] S-G0-04 — Isolation across search, files, workers and exports plus concurrent-connection and transaction tests (ARC-02). Concurrent-connection test slice merged by swarm as CW-003; other surfaces remain.
- [x] S-G0-05 — Patient registration, identity confirmation and likely-duplicate warnings (IAM-03). V7 append-only patient records, issuer-scoped MRN, normalized name search, non-linking duplicate warnings, `/api/v1/patients`; 184 tests, JaCoCo 100%, local gates green, code on `main`.
- [ ] S-G0-06 — Same-tenant duplicate review and all-or-nothing chart consolidation (IAM-04).
- [ ] S-G0-07 — Patient identity proofing, verified chart linking and unlinking (IAM-05).
- [ ] S-G0-08 — Emergency access and platform support access controls (IAM-06).

## Gate G1 — clinic

Gate evidence: competing bookings enforce one protected slot; stale drafts cannot overwrite signed records; corrections preserve the signed original; English and Arabic PDFs validated; consolidation is all-or-nothing.

- [ ] S-G1-01 — Facility, clinician and room/resource availability, recurrence, holds and transactional booking with history (CLN-01).
- [ ] S-G1-02 — Identity verification, check-in, organization/facility worklist, assignment and handoff (CLN-02).
- [ ] S-G1-03 — Longitudinal chart: problems, allergies, medications, vitals, source and verification states (CLN-03).
- [ ] S-G1-04 — Versioned SOAP, operative, OB/GYN, perinatal and pediatric development templates (CLN-04).
- [ ] S-G1-05 — Draft to signed, amendments, signed addenda and entered-in-error with preserved originals (CLN-05).
- [ ] S-G1-06 — Follow-up suggestions with rule explanation and care-team tasks (CLN-06).
- [ ] S-G1-07 — Original PDF/image associations and immutable, localized, versioned PDF rendering (CLN-07).
- [ ] S-G1-08 — Clinical dictionaries, diagnosis/procedure catalogs and RTL mixed-script operation (PRD-05, UX-01).
- [ ] S-G1-09 — G1 gate evidence and Product Owner acceptance (G1).

## Gate G2 — labs and exchange

Gate evidence: rejected or recollected specimens trace to the right order; duplicate and reordered events do not duplicate or overwrite final reports; unmatched results wait for review; critical results escalate on deadline with human acknowledgment, and corrections reopen review.

- [ ] S-G2-01 — Order creation with authorization, snapshot, disclosure record and destination work item (XCH-01).
- [ ] S-G2-02 — Separate workflow, transmission and clinical review states with human acknowledgment (XCH-02).
- [ ] S-G2-03 — Accept, reject with reason, clarify, linked changes, cancellations and revocation (XCH-03).
- [ ] S-G2-04 — Result routing, identifier matching, reconciliation queue and correction versions (XCH-04).
- [ ] S-G2-05 — Critical-result verification, owner, due time, escalation attempts and read-back (XCH-05).
- [ ] S-G2-06 — Patient release authorization, timed/hold policies and withdrawn releases (XCH-06).
- [ ] S-G2-07 — Trending only comparable observations with units, ranges, time and qualifiers visible (XCH-07).
- [ ] S-G2-08 — Requisition accession, specimen identification, rejection and linked recollection (LAB-01).
- [ ] S-G2-09 — Numeric, qualified, text and coded observations, verification states and issued revisions (LAB-02).
- [ ] S-G2-10 — Synthetic partner conformance fixtures including duplicates, corrections and partial outages (XCH-01–07, LAB-01, LAB-02).
- [ ] S-G2-11 — G2 gate evidence and Product Owner acceptance (G2).

## Gate G3 — patient and operations

Gate evidence: confirmed patients see only authorized revisions; generic notifications reveal no clinical detail; withdrawn releases stop future access; messaging works on both mobile platforms including wrong-unit denial, uploads, instructions, revision-specific acknowledgment, failed uploads, notification retry and revoked connections.

- [ ] S-G3-01 — Patient appointment list, confirmation, generic push notifications and device revocation (PAT-01).
- [ ] S-G3-02 — Released reports with revision and correction context, authorized downloads and protected local credentials (PAT-02).
- [ ] S-G3-03 — Privacy-safe enrollment, recovery, lockout and identity-dispute support channel (PAT-03).
- [ ] S-G3-04 — Care unit directory and verified, connection-scoped patient-unit relationships (MSG-01, ARC-07).
- [ ] S-G3-05 — Conversation lifecycle, unread counts, staff work queue, assignment and reopen states (MSG-02).
- [ ] S-G3-06 — PDF/JPEG/PNG attachments, sender binding, quarantine scans and clean-verdict access (MSG-03, ARC-06).
- [ ] S-G3-07 — Typed patient instructions with templates, revisions, withdrawal and units (MSG-04).
- [ ] S-G3-08 — Publication, dispatch, first view and revision-bound acknowledgment as separate facts (MSG-05).
- [ ] S-G3-09 — Service hours, response targets, named owners, backup coverage and after-hours guidance (MSG-06).
- [ ] S-G3-10 — Messaging audit, retention, legal hold, provenance and revocation effects (MSG-07).
- [ ] S-G3-11 — iOS and Android patient flows with accessibility and RTL operation (PAT-01–03, MSG-01–07, UX-01).
- [ ] S-G3-12 — Restore, downtime, load, accessibility and audit evidence (G3).
- [ ] S-G3-13 — G3 gate evidence and Product Owner acceptance (G3).

## Gate G4 — specialty and finance

Gate evidence: wrong-patient study association refused; radiology addendum reaches referrer; PT episode produces signed discharge and referral closure; ambiguous prescription transmission reconciles without duplication; payment and refund replay reproduces balances.

- [ ] S-G4-01 — Imaging order intake, urgency, accession, study association, signed interpretation and addenda (RAD-01).
- [ ] S-G4-02 — Therapy episode, evaluation, goals, interventions, outcomes, signed progress and discharge (PT-01).
- [ ] S-G4-03 — Prescription draft, versioned drug catalog, alternatives and verified allergy checks (RX-01).
- [ ] S-G4-04 — Prescriber attestation, approved routing channel, transmission events and ambiguity reconciliation (RX-02).
- [ ] S-G4-05 — Itemized self-pay invoices, payments, refunds, receipts and daily reconciliation (FIN-01).
- [ ] S-G4-06 — Specialty role permissions and end-to-end flow evidence (PRD-06).
- [ ] S-G4-07 — G4 gate evidence and Product Owner acceptance (G4).

## Gate G5 — launch and operations

Gate evidence: each site approves workflow, release policy, market constraints, integrations, identity procedures, staffing and downtime drills; messaging units verify eligibility, ownership, after-hours guidance and backup coverage; no unresolved isolation, record-integrity or critical-escalation defects.

- [ ] S-G5-01 — Market policy pack, hosting region, jurisdiction and data handling map (OPS-01, PRD-05).
- [ ] S-G5-02 — Clinical templates, code sets, interpreting privileges and result escalation approvals (ADM-01).
- [ ] S-G5-03 — Result release rules, sensitive information handling and identity recovery approvals (OPS-01).
- [ ] S-G5-04 — Laboratory, PACS and pharmacy interface contracts, credentials and outage drills (OPS-01).
- [ ] S-G5-05 — Capacity targets, pilot load profile and measured performance evidence (OPS-03).
- [ ] S-G5-06 — Recovery targets, restore into clean environment and operational runbooks (OPS-04, OPS-05).
- [ ] S-G5-07 — Separate environments, synthetic fixtures, signed builds, migration checks and progressive rollout (OPS-06).
- [ ] S-G5-08 — Tenant export manifest, retention, legal hold, deletion and offboarding (OPS-07).
- [ ] S-G5-09 — Onboarding dataset manifest, dry run, controlled batches and authorized sign-off (OPS-08).
- [ ] S-G5-10 — English and Arabic, RTL, time zones, WCAG 2.2 AA and native screen-reader flows (UX-01).
- [ ] S-G5-11 — Operational queues for unsigned work, unmatched reports, critical findings, failures and revoked accounts (ADM-02).
- [ ] S-G5-12 — Per-site launch approvals and final G5 acceptance (G5).

## Cross-cutting contracts

These are delivered inside the stories above, not separately scheduled.

- [ ] API versioning, idempotency, cursor pagination, correlation IDs and machine-readable errors (ARC-03, ARC-04).
- [ ] Durable events, outbox, deduplication, replay and dead-letter review (ARC-05).
- [ ] Upload validation, quarantine, mediated downloads and PDF binding to immutable revisions (ARC-06).
- [ ] Generated OpenAPI clients and server-side authorization for every command (PRD-06).

## Done ledger

| Story | Verified evidence | Merge / CI |
|---|---|---|
| S-G0-01 | `PROGRESS.md` G0-01 rows; 44 tests, JaCoCo 100% on instruction/line/branch/method/class; `db/check.sh` policy assertions | CI https://github.com/dr3mro/Aurevia-Care/actions/runs/44442227258 on `f9bc461`; merged `e1dc7cd` |
| S-G0-02 | `PROGRESS.md` S-G0-02 rows; 81 tests, JaCoCo 100%; `db/check.sh` append-only and fold assertions; `scripts/check.sh` and `scripts/docs.sh` green | Green CI https://github.com/dr3mro/Aurevia-Care/actions/runs/44447432253 on `ea15d6c`; merged in `8d015b5`, main run 44448021668 attempt 2 green |

Simulated review findings are evidence of review activity, not clinical or human pilot acceptance. Real-patient approval remains a G5 gate.

## Current position

S-G0-01 and S-G0-02 are done; S-G0-03 configuration, catalog and audit-review slices are green on `main` (only OPS-06 infrastructure remains). S-G0-05 patient registration is implemented locally with CI pending. The staff-web and patient-mobile scaffolds were delivered by the swarm (CW-004/CW-005) and are enforced by the frontend standards gate.
