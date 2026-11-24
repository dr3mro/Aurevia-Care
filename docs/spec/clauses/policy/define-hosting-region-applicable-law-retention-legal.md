# OPS-01 clause 1 — Define hosting region, applicable law, retention and legal hold rules

## Clause

Define hosting region, applicable law, retention and legal hold rules, patient release rules, incident notification, supplier obligations and prescribing constraints in the approved policy pack.

## Status

Not built

## Evidence

The clause needs a signed human decision that the repository does not hold. It holds a decision draft and bounded code hooks, but no signed pack. Under rule 3 a clause that needs a human, a partner, a cloud account or a signed decision gets status Not built unless the signed evidence is in the repository, so the status here is Not built.

What the specification requires (authoritative text):

- OPS-01 in [PROJECT_V1_SPEC.md](../../../../.opencode/config/PROJECT_V1_SPEC.md) defines the required contents of the approved policy pack and, for a U.S. launch where HIPAA applies, requires a security risk analysis, the required agreements and vendor scope before ePHI is handled, and jurisdiction-specific requirements and owners before live pilot.
- The G5 row of section 9 of the same specification requires each site to approve its workflow, release policy, market constraints, integrations, identity procedures, staffing and downtime drills.
- Section 10 of the same specification assigns "Launch jurisdiction, hosting region and scope of pilot" to the Product owner and privacy lead, with "Signed market policy pack and data handling map" as its evidence, and assigns the neighbouring clinical, privacy, integration and engineering/operations decisions to their own accountable roles.

Signed decision — not present in the repository:

- [policy-pack.md](../../../../docs/launch/policy-pack.md) is a decision draft, not a signed decision: its status is `Not approved`, it names no jurisdiction, hosting region, partner or date, all of its topic rows are `open` or `blocked`, its approval record is blank, and its data handling map is a blank "owner to enter" template.
- [site-launch-approvals.md](../../../../docs/launch/site-launch-approvals.md) is likewise `Not approved`, with a deliberately blank approval record.
- The per-site Product Owner and clinical sign-off row covering market constraints in the [launch checklist](../../../../docs/operations/production-readiness/launch-checklist.md) is `open`; its evidence link is `none yet`.
- [infra/DEPLOYMENT.md](../../../../infra/DEPLOYMENT.md) is a topology design handoff: no infrastructure-as-code or configuration in the repository binds a hosting region, jurisdiction or data-residency rule.
- Searched for and not found: a signed market policy pack or data handling map, a jurisdiction or hosting-region determination, an approved retention schedule or named legal-hold authority, an incident-notification matrix, a supplier register with executed agreements, an approved prescribing-channel decision, named jurisdiction-specific owners, and any workflow run or command output holding that evidence.

Code hooks — real, but each proves only a mechanism, never the signed decision:

- [RxService.java](../../../../apps/api/src/main/java/com/aurevia/prescription/rx/RxService.java) reads `rx02.market`, `rx02.approved_channels` and `rx02.entitlement.<subject>` and fails closed with `NO_MARKET_POLICY`, `NO_CHANNEL_POLICY` or `NO_ENTITLEMENT`; [RxRepository.java](../../../../apps/api/src/main/java/com/aurevia/prescription/rx/RxRepository.java) reads them through `approvedSetting` from `public.effective_config(:key)`. [PrescriptionSubstitutionService.java](../../../../apps/api/src/main/java/com/aurevia/prescription/PrescriptionSubstitutionService.java) likewise reads `rx02.market` and fails closed with `NO_MARKET_POLICY` for substitutions. These are per-tenant prescription checks (RX-02), not a deployment-wide policy pack.
- [ReleaseService.java](../../../../apps/api/src/main/java/com/aurevia/exchange/release/ReleaseService.java) binds a release to an immutable revision version and a `policyVersion` (XCH-06) with an optimistic version precondition; it does not encode the market's patient release rules.
- [V21__exchange_response_order_response.sql](../../../../db/migrations/V21__exchange_response_order_response.sql) defines a `retention_policy` column (`STANDARD`, `EXTENDED`, `LEGAL_HOLD`) and a `legal_hold` column; [V62__messaging_governance.sql](../../../../db/migrations/V62__messaging_governance.sql) defines the `messaging_retention_policy`, `messaging_retention_duty` and `messaging_legal_hold` tables. These are synthetic enforcement forms, not an approved schedule or a named hold authority.
- [retention-and-offboarding.md](../../../../docs/operations/tenant-lifecycle/retention-and-offboarding.md) states the OPS-07 enforcement contract — a hold is binding, its scope is bidirectional and a lift needs named authority — and that the evaluator cannot confirm a hold was placed by an authorized privacy owner. [export-manifest.md](../../../../docs/operations/tenant-lifecycle/export-manifest.md) states that its document authorization tuple is "a fixed, versioned-in-code stand-in for a market policy pack, not a live policy binding" and that confirming approved policy permission is a named human G5 review item.

## Gap

Missing before G5:

1. The signed market policy pack and data handling map: launch jurisdiction, hosting region, applicable law and pilot scope (OPS-01, PRD-05; section 10). Accountable: Product owner and privacy lead.
2. A deployment configuration binding the hosting region and data residency; none exists today (OPS-01, PRD-05).
3. An approved retention schedule by record class with a named legal-hold authority and per-market periods (OPS-01, OPS-07; the log/metric period falls under WEB-06). Accountable: Privacy lead with engineering.
4. Approved patient release rules with a policy version and withdrawal handling (OPS-01, XCH-06). Accountable: Privacy lead and clinical safety lead.
5. An incident-notification matrix: named internal roles, statutory or contractual timeframes and external parties (OPS-01). Accountable: Privacy lead and operations.
6. A signed supplier/subprocessor register with executed agreements, credentials and audit rights (OPS-01). Accountable: Integration lead and partner owner with privacy lead.
7. An approved prescribing-channel and market-entitlement decision with the permitted constraints (OPS-01, RX-02). Accountable: Clinical safety lead and site medical director.
8. A named jurisdiction-specific requirements-and-owners register with effective dates and a review cycle (OPS-01). Accountable: Product owner and privacy lead.
9. Per-site G5 sign-off covering market constraints, and the final G5 acceptance record (section 9). Accountable: per-site Product Owner and clinical lead.
10. Conditional for a U.S. launch where HIPAA applies: a signed security risk analysis, the required agreements and a vendor scope inventory before ePHI is handled (OPS-01). Accountable: Privacy lead.

Files that would change: `docs/launch/policy-pack.md` (completed and signed), `docs/launch/site-launch-approvals.md` (completed and signed), `docs/operations/production-readiness/launch-checklist.md` (rows closed with attached evidence), `infra/DEPLOYMENT.md` (record the region and market binding), and the G5 evidence pack when it is assembled. This note lists those files and counts the work; it creates no task file and changes none of them.

Number of separate tasks: at least nine — the signed policy pack and data handling map (the S-G5-01 drafting task is done, but the human signing decision it prepares is still open); the region and data-residency deployment binding (NO TASK); the signed retention schedule and named hold authority (NO TASK for the signed artifact; BUILD-42 and BUILD-16 supply code support); the patient release rules (S-G5-03); the incident-notification matrix (NO TASK; START-04 supplies runbooks only); the supplier register and agreements (S-G5-04; NO TASK for the register); the prescribing-channel and entitlement decision (NO TASK; BUILD-10 and RX-02 supply code support); the per-site G5 approvals (S-G5-12 / GATE-PACK-G5); and the jurisdiction-specific owners register (NO TASK). A tenth is needed for the conditional U.S./HIPAA risk analysis and agreements if the launch is in the U.S.

## How to verify

Repository checks a reviewer can run now — these prove only the draft and the code hooks, never the signed decision:

- `python3 -I scripts/check-docs.py` — this note's relative links and requirement IDs are valid.
- `grep -n "Not approved" docs/launch/policy-pack.md docs/launch/site-launch-approvals.md` — both decision packs are drafts, not signed decisions.
- `grep -n "none yet" docs/operations/production-readiness/launch-checklist.md` — the per-site sign-off and the other owner rows have no attached evidence file.
- `grep -rn "rx02.market\|NO_MARKET_POLICY" apps/api/src/main apps/api/src/test` — shows only the per-tenant RX-02 entitlement hooks and their test.

Human evidence a site must produce before G5 — no command or workflow run can substitute for it:

- A **signed market policy pack and data handling map** naming the launch jurisdiction, hosting region, applicable law and pilot scope, supplied by the Product owner and privacy lead (section 10).
- An **approved retention schedule** by record class and a **named legal-hold authority** with a hold placement and lift authorization record, supplied by the privacy lead with engineering (OPS-01, OPS-07).
- **Approved patient release rules** with a policy version and withdrawal handling, supplied by the privacy lead and clinical safety lead (OPS-01, XCH-06).
- An **incident-notification matrix** with named owners, timeframes and external parties, supplied by the privacy lead and operations (OPS-01).
- A **signed supplier/subprocessor register** with executed agreements, credentials and audit rights, supplied by the integration lead and partner owner with the privacy lead (OPS-01).
- An **approved prescribing-channel and market-entitlement decision** with the permitted constraints, supplied by the clinical safety lead and site medical director (OPS-01, RX-02).
- A **jurisdiction-specific requirements-and-owners register** with effective dates and a review cycle, supplied by the Product owner and privacy lead (OPS-01).
- A **signed site acceptance matrix and final G5 acceptance record** with market constraints approved per enabled site, supplied by the per-site Product Owner and clinical lead (section 9 G5 row).
- If the launch is in the U.S. where HIPAA applies: a **signed security risk analysis, the required agreements and a vendor scope inventory**, supplied by the privacy lead, with evidence that no ePHI was handled before approval (OPS-01).
