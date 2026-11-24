# PRD-05 clause 2 — One market per production deployment, with a market-specific policy pack

## Clause

One market per production deployment, with a market-specific policy pack.

## Status

Not built

## Evidence

What the specification requires (authoritative text):

- The PRD-05 boundary row of [PROJECT_V1_SPEC.md](../../../../.opencode/config/PROJECT_V1_SPEC.md) (section 1) names "One market per production deployment, with a market-specific policy pack" as a boundary, not a delivered artifact.
- The G5 row of section 9 of the same specification requires each site to approve its workflow, release policy, **market constraints**, integrations, identity procedures, staffing and downtime drills.
- Section 10 of the same specification assigns "Launch jurisdiction, hosting region and scope of pilot" to the Product owner and privacy lead, with evidence "**Signed market policy pack and data handling map**".
- OPS-01 in the same specification defines what the approved policy pack must contain (hosting region, applicable law, retention and legal hold, patient release rules, incident notification, supplier obligations, prescribing constraints, and a HIPAA risk analysis for a U.S. launch). ADM-01 requires named approval, effective dates, audit and version history for clinical/market policies.

What the repository actually holds today (all links resolve to files that exist):

- Tenant-scoped **approved configuration** is implemented: the fold `public.effective_config(:key)` is read by `approvedSetting` in [RxRepository.java](../../../../apps/api/src/main/java/com/aurevia/prescription/rx/RxRepository.java).
- [RxService.java](../../../../apps/api/src/main/java/com/aurevia/prescription/rx/RxService.java) reads the keys `rx02.market`, `rx02.approved_channels` and `rx02.entitlement.<subject>` and fails closed with `NO_MARKET_POLICY`, `NO_CHANNEL_POLICY` or `NO_ENTITLEMENT`. [RxTransmissionFlowTest.java](../../../../apps/api/src/test/java/com/aurevia/it/prescription/RxTransmissionFlowTest.java) asserts the `NO_MARKET_POLICY` path. This is a per-tenant prescription entitlement check (RX-02), **not** a deployment-wide market policy pack, and it does not bind a deployment to one market.
- [PrescriptionRepository.java](../../../../apps/api/src/main/java/com/aurevia/prescription/PrescriptionRepository.java) reads a market column on a published catalog version (RX-01); again this is per-tenant catalog data, not the signed policy pack.
- The [export manifest](../../../operations/tenant-lifecycle/export-manifest.md) states explicitly that its authorization tuple is "a fixed, versioned-in-code stand-in for a market policy pack, not a live policy binding" and that confirming actual policy permission is a named human G5 review item.
- The [launch checklist](../../../operations/production-readiness/launch-checklist.md) carries the per-site sign-off row for market constraints, with status `open` and link `none yet`; the document states a passing synthetic rehearsal is not evidence for any row.
- The [design README](../../../design/README.md) records the PRD-05 language/market scope and lists the Arabic-Indic digit policy as a site clinical-and-market decision to be made with the market policy pack.
- [infra/DEPLOYMENT.md](../../../../infra/DEPLOYMENT.md) describes topology, capacity and promotion, but binds no deployment to a single market or hosting region.

What was searched and **not found**: no `docs/launch/` directory exists, so neither `docs/launch/policy-pack.md` ([GATE-DECISION-POLICY-PACK](../../../../tasks/00299000-GATE-DECISION-POLICY-PACK.md), task still `[-]`) nor `docs/launch/site-launch-approvals.md` ([GATE-DECISION-SITE-LAUNCH-APPROVALS](../../../../tasks/00303000-GATE-DECISION-SITE-LAUNCH-APPROVALS.md), task still `[-]`) is present. No signed market policy pack or data handling map, no jurisdiction/hosting-region selection, no workflow or infrastructure file that enforces one market per deployment, and no workflow run or command output holds that evidence. The owning human decision task [S-G5-01](../../../../tasks/00184000-S-G5-01.md) is still `[ ]` in [TODO.md](../../../../TODO.md). The partial code hooks above do not constitute the clause, so the status is Not built (rule 3: a signed decision the repository does not hold).

## Gap

Missing: (1) the signed market policy pack and data handling map, including jurisdiction, hosting region, pilot scope, retention/legal hold, release rules, incident notification, supplier obligations, prescribing constraints and a HIPAA risk analysis if the launch is in the U.S.; (2) a deployment-level binding that permits only one market per production deployment (no code or infrastructure enforces this today); (3) per-site G5 sign-off evidence for market constraints.

Files that would change: `docs/launch/policy-pack.md` (new — GATE-DECISION-POLICY-PACK), `docs/launch/site-launch-approvals.md` (new — GATE-DECISION-SITE-LAUNCH-APPROVALS), a deployment/infrastructure configuration binding the market and region (NO TASK exists; [infra/DEPLOYMENT.md](../../../../infra/DEPLOYMENT.md) would need to record it), and the G5 evidence pack when it is assembled.

Number of separate tasks: at least three — the human decision and sign-off (S-G5-01 / GATE-DECISION-POLICY-PACK), the one-market-per-deployment infrastructure binding (NO TASK), and the per-site launch approvals (S-G5-12 / GATE-DECISION-SITE-LAUNCH-APPROVALS). None is complete, and two are still open/taken rather than delivered.

## How to verify

Repository checks a reviewer can run now (they prove only the partial code hooks, not the clause):

- `python3 -I scripts/check-docs.py` — this note's links and requirement IDs are valid.
- `grep -rn "rx02.market\|NO_MARKET_POLICY" apps/api/src/main apps/api/src/test` — shows the tenant-level market hooks and their test.
- `test -f docs/launch/policy-pack.md` — currently fails, confirming the signed decision pack is absent.

Human evidence a site must produce before G5 (no command or workflow run can substitute for it):

- A **signed market policy pack and data handling map** for the launch jurisdiction, hosting region and pilot scope, supplied by the Product owner and privacy lead (specification section 10).
- Approved policy-pack contents for applicable law, retention and legal hold, patient release rules, incident notification, supplier obligations and prescribing constraints (OPS-01), plus a HIPAA security risk analysis and required agreements if the launch is in the U.S.
- A **signed site acceptance matrix** and G5 acceptance record with market constraints approved per enabled site, supplied by the per-site Product Owner and clinical lead (specification section 9 G5 row and [launch checklist](../../../operations/production-readiness/launch-checklist.md)).
- Evidence that the production deployment is bound to that single market and region (NO TASK exists to produce it today).
