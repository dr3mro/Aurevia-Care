# WEB-11 clause 5 — Rehearse a rollback before pilot and keep a release checklist with named approvers

## Clause

Rehearse a rollback before pilot and keep a release checklist with named approvers.

## Status

Not built

## Evidence

What the specification requires (authoritative text):

- The WEB-11 row of [SMARTCLINIC_V1_SPEC.md](../../../../SMARTCLINIC_V1_SPEC.md) (section 8.1) names the clause verbatim as the last sentence of "Release and rollback": build once and promote the same signed artifact through environments with an SBOM, roll out progressively at 5%, 25% and 100% with automatic rollback on error-rate or latency breach, use expand and contract migrations and feature flags that default off, then this clause.
- Section 8.1 of the same specification states that evidence for WEB-11 is produced with the infrastructure owner before G5.
- The G5 row of section 9 requires each site to approve its workflow, release policy, market constraints, integrations, identity procedures, staffing and downtime drills.
- Section 10 assigns "Implementation priorities and release approval" to the Product, clinical, engineering and privacy owners, with "Signed acceptance matrix and roll-forward/rollback criteria" as evidence.

What the repository actually holds today (every link resolves to a file that exists; nothing below is a rehearsal result or a signed approval):

- The "Who may act" table of [bad-deploy.md](../../../operations/runbooks/bad-deploy.md) (lines 25 to 33) lists roles: operational on-call owner, Infrastructure owner (decides and performs rollback), Engineering owner, Site clinical coverage owner and "Named release approvers". The same section immediately states `NOT YET IN PLACE:` the production deployment command, the named release approvers, the health-gated deploy and the rollback trigger, and that this repository publishes images and records digests but contains no deploy, rollback or progressive-rollout workflow. "How to roll back" repeats `NOT YET IN PLACE:` a deploy or rollback command and requires the environment operator to perform and record the step; "How to roll forward" states `NOT YET IN PLACE:` the 5% / 25% / 100% progressive rollout and automatic rollback on error-rate or latency breach. "What to record" requires who approved and who performed each action and says "Mark a rehearsal as a rehearsal."
- [launch-checklist.md](../../../operations/production-readiness/launch-checklist.md) (lines 8 to 15) states that "Owner" is a role, not a person's name, that every row is `open`, and that a passing synthetic rehearsal is not evidence for any row. Row 27 is the WEB-11 rollout row, whose required evidence is a "Monitored rollout record showing all three stages, health gates, a rehearsed rollback and the release checklist with named approvers" — status `open`. Row 26, the WEB-11 signed-artifact and SBOM row, is `open`; row 33, the G5 per-site Product Owner and clinical sign-off, is `open` with link `none yet`.
- [evidence.md](../../../operations/production-readiness/evidence.md) (line 31) marks WEB-11 `needs infrastructure owner` and points only at the delivery-safety contract documents and the launch checklist rows; line 46 says the site must still attach the 5/25/100% rollout record with a rehearsed automatic rollback and a release checklist naming its approvers; line 59 records that no rolling-deploy rehearsal artifact was found. [README.md](../../../operations/production-readiness/README.md) (line 19) marks WEB-11 `not started` with the Infrastructure owner.
- [infra/DEPLOYMENT.md](../../../../infra/DEPLOYMENT.md) (line 7) asks the operator to verify staging smoke, load capacity, failover and rollback before authorized deployment — an expectation in a design handoff, not a performed rehearsal.
- [release-app-image.yml](../../../../.github/workflows/release-app-image.yml) builds one image, smoke-tests it on native amd64 and arm64 runners and publishes it by digest. It requires a green default-branch run for the tagged commit (lines 79 to 90) and treats tags as immutable (lines 99 to 103). It contains no SBOM generation, no signature, no staged 5/25/100 promotion, no automatic rollback and no rehearsal record.
- [decision 0002](../../../decisions/0002-ops06-migration-change-safety.md) (lines 9 and 28) records that there is no infrastructure-as-code or deployment workflow in the repository and that monitored progressive rollout and audited production access are deferred to the human and cloud-gated S-G5-07 decision.
- [rollout-and-production-access.md](../../../operations/delivery-safety/rollout-and-production-access.md) (lines 48 to 55) describes a synthetic OPS-06 evaluator and states that it cannot observe live health signals, confirm that a rollback was rehearsed, verify the approver's identity or authority, or check that an access grant actually expired. [evidence-contract.md](../../../operations/delivery-safety/evidence-contract.md) (lines 40 and 51 to 52) gives `synthetic_rehearsal` `final_evidence: false` and says that verifying a rollout really happened, and G5 acceptance, remain human and cloud gated.
- [site-launch-approvals.md](../../../launch/site-launch-approvals.md) (lines 3 to 10) is a decision pack with "Status: Not approved" whose approval record is deliberately blank and which names no person or date; lines 200 and 201 leave "Signed acceptance matrix and roll-forward/rollback criteria" and "Progressive rollout, tested rollback and audited, expiring production-access record" unchecked. [policy-pack.md](../../../launch/policy-pack.md) (line 299) leaves the SBOM, signature, progressive-rollout record and rehearsed rollback unchecked.

What was searched and not found: a case-insensitive search for rehearsal, named approver, signed checklist and 5%/25%/100% across `docs/operations`, `.github/workflows` and `infra` returned only the prose above and other synthetic-rehearsal documentation. There is no rollback rehearsal record, no signed release checklist naming an individual, no deploy or rollback command, no staged-promotion workflow and no workflow run holding this evidence, and no separate rollback rehearsal record, result or report file exists. The `[x]` on the GATE-DECISION-SITE-LAUNCH-APPROVALS and GATE-DECISION-RELEASE-AND-RECOVERY-RULES decision-pack tasks means the packs were delivered, not that anyone approved or rehearsed anything, and their scope is other requirements. The clause needs a human, cloud-account and signed decision that the repository does not hold, so the status is Not built.

## Gap

Missing: (1) a rollback rehearsal conducted before pilot and labelled as a rehearsal, with UTC instants, the tag and digest rolled back from and to, probe and log results, who performed it and who approved it; (2) a release checklist naming individual approvers, distinct from the requester and recorded with an approval and audit trail; (3) a deploy or rollback command and a monitored 5%/25%/100% progressive rollout with health gates, monitoring and automatic rollback on error-rate or latency breach; (4) an SBOM and a verified signature for the single artifact promoted unchanged by digest; (5) a signed acceptance matrix with roll-forward and rollback criteria, plus per-site G5 sign-off.

Files that would change, all outside this task's scope: a new rehearsal record and signed release checklist under `docs/operations/` with the bad-deploy runbook pointing to them; a new deploy, rollback and progressive-rollout workflow under `.github/workflows/` or `infra/`; the WEB-11 rows of the launch checklist closed only when the owner attaches the evidence; and the G5 acceptance record in `docs/launch/site-launch-approvals.md`. This note changes none of them.

Number of separate tasks: at least three — an infrastructure-owned rehearsal record, deploy/rollback command and staged rollout with automatic rollback; an engineering-owned signed artifact with SBOM and a release checklist with named approvers; and a G5 signed acceptance matrix with roll-forward/rollback criteria and per-site sign-off. The synthetic OPS-06 evaluator proves only that a declared artifact has the required shape, so it cannot close any of the three.

## How to verify

Repository checks a reviewer can run now. They prove the note's structure and the persistence of the gap, not the clause:

- `test -f docs/spec/clauses/release/rehearse-rollback-before-pilot-keep-release-checklist.md` — exit 0.
- `grep -nE '^## (Clause|Status|Evidence|Gap|How to verify)$' docs/spec/clauses/release/rehearse-rollback-before-pilot-keep-release-checklist.md` — exactly those five headings, in order.
- `grep -Fn 'Rehearse a rollback before pilot and keep a release checklist with named approvers.' docs/spec/clauses/release/rehearse-rollback-before-pilot-keep-release-checklist.md` — the clause is verbatim; compare it with the WEB-11 row of the specification.
- `grep -rniE 'rehears(al|e)|named approver|signed checklist|5%|25%|100%' docs/operations .github/workflows infra` — returns the `NOT YET IN PLACE` and `open` prose plus synthetic-rehearsal documentation and fixtures, and no rehearsal record or signed checklist. A real one would appear here if it existed.
- `! grep -rniE 'rollback rehearsal (record|result|report)|conducted rollback rehearsal|signed (release )?checklist|approver: [A-Z][a-z]+ [A-Z][a-z]+' docs/operations .github/workflows infra docs/launch` — exit 0 today, confirming no named-approver checklist or rollback rehearsal record exists.
- `python3 -I scripts/check-docs.py` — exit 0 with no output: links, requirement IDs and Markdown structure are valid. This checker does not assert the clause text, the heading set or the status; the greps above and inspection do that.

Human evidence a site must produce before G5 (no command or synthetic rehearsal can substitute):

- The **Infrastructure owner** supplies the recorded rollback rehearsal, explicitly labelled as a rehearsal, and the monitored 5%/25%/100% rollout with health gates and rollback on error-rate or latency breach (specification section 8.1 and launch checklist row 27).
- The **named release approvers**, distinct from the requester, sign the release checklist and the production-access record; the **engineering owner** supplies the SBOM, signature and digest promotion record; the **site clinical coverage owner** confirms clinical integrity before affected workflows resume (bad-deploy runbook "Who may act" and "Confirm clinical integrity").
- The **Product, clinical, engineering and privacy owners** sign the acceptance matrix and the roll-forward/rollback criteria (specification section 10), and the per-site **Product Owner and clinical lead** sign the G5 site acceptance record (launch checklist row 33). The checklist's "Owner" column is a role, not a person's name: the accountable individual appears only on that signed record.
