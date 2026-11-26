# OPS-05 clause 3 — evidence note: have downtime and recovery runbooks

## Clause

Have downtime and recovery runbooks for paper/manual capture, patient identification, critical contacts and supervised back-entry.

## Status

Partly built

The repository builds and synthetically verifies the *declared runbook envelope* for all four topics and a fail-closed duplicate-recovery check, and it holds a partial manual-capture code path for partner outages. It does not hold a published rota, a conducted downtime drill or a site's signed acceptance of those artifacts. Under the task rule the human/partner portion is **Not built** in this repository and must be supplied by named site owners before G5; the documentation and synthetic-verification portion is built and checked, hence the overall Partly built status. This follows the same distinction as [the manual-workflow evidence note](../product/manual-workflows-clearly-identify-delivery-verification-evidence.md).

## Evidence

This note records only files, tests and command results that exist in this repository today. The commands below were executed from the repository root; their output came from the executing tool. Everything else comes from reading the linked files. No simulated-user, partner, cloud-account, signed-decision or human-participant evidence is held here.

### Authoritative requirement

- [PROJECT_V1_SPEC.md](../../../../.opencode/config/PROJECT_V1_SPEC.md) line 138 defines OPS-05, including this clause and "Prevent duplicates when recovering." Its section 9 G5 row (line 174) requires each site to approve staffing and downtime drills; section 10 (lines 183-187) assigns timed workflow drills to the clinical safety lead and site medical director, the outage drill to the integration lead and partner owner, restore evidence and on-call readiness to engineering and operations, and the signed acceptance matrix to product, clinical, engineering and privacy owners.

### Declared runbook procedures (present, but explicitly not operational evidence)

- [operational-runbooks.md](../../../operations/recovery/operational-runbooks.md) is titled "Operational monitoring and runbooks (OPS-05)". Lines 43-81 define all four required topics by name: `paper_manual_capture` (capture on approved paper or local forms, preserve original timestamps and entering actor, reconcile against committed entries), `patient_identification` (two identifiers at high-risk transitions, issuer-scoped MRN, approved alternate when identifiers are unavailable), `critical_contacts` (site-approved backup channel, each attempt/recipient/deadline/read-back/receipt recorded separately, route to a staffed backup queue) and `supervised_back_entry` (qualified supervisor, original timestamps and actor, duplicate reconciliation before the record becomes authoritative). Lines 102-107 state this is "a declared envelope, not operational telemetry" and that no sign-off is implied.
- [restore-procedure.md](../../../operations/recovery/restore-procedure.md) section 5 (lines 70-84) states the duplicate-prevention steps: tenant/caller/command idempotency keys with a request digest, durable business uniqueness after a retry key expires, immutable event-ID deduplication and per-aggregate version, and reconciliation of supervised back-entry against entries committed before the outage.
- [recovery-evidence.md](../../../operations/recovery/recovery-evidence.md) lines 3-6 state that the evaluator judges a JSON artifact, never a running system, and that real acceptance is a human G5 gate. Lines 20-23 fix the four runbook topic names and `duplicate_prevention: true` in the artifact contract.
- [runbook.md](../../../operations/recovery/runbook.md) and [drill-evidence.md](../../../operations/recovery/drill-evidence.md) are the timed restore-drill procedure and evidence template; [index.md](../../../operations/runbooks/index.md) lines 7-12 say a runbook is a procedure, not evidence, and lines 24-27 say the named rota and contact list are `NOT YET IN PLACE`.
- [launch-checklist.md](../../../operations/production-readiness/launch-checklist.md) line 31 carries the OPS-05 row (status `open`, Infrastructure owner) requiring a published rota with named operational and clinical coverage owners, the four topics and an escalation record; its G5 row at line 33 is also `open`. [site-launch-approvals.md](../../../launch/site-launch-approvals.md) holds the corresponding unchecked downtime-drill and named-owner evidence checkboxes.

### Machine-checkable synthetic verification (present)

- [recovery_evidence.py](../../../../infra/recovery/recovery_evidence.py) lines 49-51 declare the four required runbook names; lines 200-203 require `duplicate_prevention` to be literal `true`; lines 237-239 evaluate both. [recovery-contract.json](../../../../infra/recovery/recovery-contract.json) lines 43-49 and [synthetic_recovery_evidence.json](../../../../infra/recovery/fixtures/synthetic_recovery_evidence.json) lines 3-4 and 52-58 hold the synthetic rehearsal. [test_recovery_evidence.py](../../../../infra/recovery/tests/test_recovery_evidence.py) lines 411-456 test that all four topics are required, that `downtime_drills` cannot substitute for `supervised_back_entry`, and that duplicate prevention must be a literal boolean.
- [GateEvidenceEvaluator.java](../../../../apps/api/src/main/java/com/aurevia/messaging/evidence/GateEvidenceEvaluator.java) lines 116-166 evaluates `evaluateDowntime`: each of the four missing runbook elements produces a named failure, and replay effects are checked for a conflicting digest, a duplicate effect, an unknown replay and an incomplete recovery. The four-element contract is [GateEvidenceContract.java](../../../../apps/api/src/main/java/com/aurevia/messaging/evidence/GateEvidenceContract.java) lines 153-168. [GateEvidenceTest.java](../../../../apps/api/src/test/java/com/aurevia/messaging/evidence/GateEvidenceTest.java) lines 254-295 name the tests `downtimePassesWithFullRunbookAndSingleRecoveryEffects`, `downtimeFailsForEveryMissingRunbookElement` and `downtimeFailsOnConflictingDigestDuplicateEffectAndUnknownOrIncompleteReplay`; [GateEvidenceControllerHttpTest.java](../../../../apps/api/src/test/java/com/aurevia/messaging/evidence/GateEvidenceControllerHttpTest.java) lines 62-75 build the evidence input and lines 83-93 exercise it through the real controller at HTTP level.
- [labflow/outage](../../../../apps/api/src/main/java/com/aurevia/labflow/outage/OutageService.java) holds the adjacent real manual-capture path: `OutageService`, `OutageController`, `OutageModel` and `OutageDecision` capture manual results when a lab partner is unavailable (LAB-02, XCH-03, PRD-02), tested by [OutageIntegrationTest.java](../../../../apps/api/src/test/java/com/aurevia/labflow/outage/OutageIntegrationTest.java). This is partner-outage manual capture only, not a general supervised chart back-entry workflow.

### Commands executed for this note

- `test -f docs/spec/clauses/monitoring/have-downtime-recovery-runbooks-paper-manual-capture.md; echo "exit=$?"` → `exit=1` before the note existed (the acceptance gap), `exit=0` after it was written.
- `python3 -I scripts/check-docs.py; echo "check-docs exit=$?"` → `check-docs exit=0` with no `check-docs:` diagnostics, both before and after this note was added.
- `python3 -I infra/recovery/recovery_evidence.py infra/recovery/fixtures/synthetic_recovery_evidence.json` → exit 0, ending with `[PASS] runbooks: all required members present exactly once` and `[PASS] duplicate_prevention: recovery must declare duplicate prevention`.
- `python3 -I infra/recovery/recovery_evidence.py infra/recovery/fixtures/violation_recovery_evidence.json` → exit 1, including `[FAIL] runbooks: missing: supervised_back_entry` and `[FAIL] duplicate_prevention: recovery must declare duplicate prevention`.
- `python3 -I -m unittest discover -s infra/recovery/tests -q` → exit 0.

## Gap

Missing before G5, with the accountable role for each item:

1. A **published named rota and escalation record**: operational on-call and site clinical coverage owners by name, with a recorded escalation trail for critical alerts. The OPS-05 row of [launch-checklist.md](../../../operations/production-readiness/launch-checklist.md) is `open`; [index.md](../../../operations/runbooks/index.md) marks the rota `NOT YET IN PLACE`. Supplied by engineering and operations with the site clinical lead (section 10); names belong in the approved policy pack, not this repository.
2. A **conducted downtime drill** rehearsing all four topics and producing an artifact with `kind: conducted_drill`, observed RPO/RTO, reconciliation results, named owners and failed-step dispositions, as required by [recovery-evidence.md](../../../operations/recovery/recovery-evidence.md) and [drill-evidence.md](../../../operations/recovery/drill-evidence.md). Only `synthetic_rehearsal` fixtures exist today. Supplied by engineering and operations with the integration lead and each partner owner for the outage scenario, the privacy lead and clinical safety lead for patient identity recovery, and the site clinical lead for critical contacts (section 10).
3. **Per-site G5 acceptance**: the signed site acceptance matrix and final G5 record closing the downtime-drill and staffing rows in [site-launch-approvals.md](../../../launch/site-launch-approvals.md) and [launch-checklist.md](../../../operations/production-readiness/launch-checklist.md). Supplied by the per-site Product Owner and clinical lead.
4. A **real supervised back-entry write path**: a search of `apps/api/src/main` found no route that re-enters paper-captured care into the chart with preserved original timestamps, entering actor and idempotent duplicate reconciliation. The `GateEvidenceEvaluator` checks declared facts about such a replay, and `labflow/outage` captures manual lab results, but neither performs the supervised chart back-entry the runbook describes. Supplying it would change application service/controller code and its tests, and, if a route is added, `contracts/openapi.yaml` and the generated client. This is a separate code story; the clause note does not change those files.

Files that would change for the human items: `docs/launch/policy-pack.md` (named rota and contacts, approved), `docs/launch/site-launch-approvals.md` and `docs/operations/production-readiness/launch-checklist.md` (closed rows with attached evidence), and a new conducted-drill artifact under `docs/operations/recovery/` produced from the existing template. For item 4: the appropriate `apps/api` domain package, its tests and the API contract files. This note lists the work and counts it; it changes none of those files.

Number of separate tasks: **at least four** — the named rota and escalation record (1), the conducted drill and its artifact (2), the per-site signed G5 acceptance (3), and the supervised back-entry implementation with duplicate reconciliation (4). Items 1-3 are human/partner/signed decisions; item 4 is a code task. That last item is adjacent to, and not a substitute for, the sibling OPS-05 "Prevent duplicates when recovering" work.

## How to verify

Repository checks a reviewer can run now (these prove the declared envelope and the synthetic harness, never a conducted drill or a live rota):

- `test -f docs/spec/clauses/monitoring/have-downtime-recovery-runbooks-paper-manual-capture.md; echo "exit=$?"` → `exit=0`; the note exists at the planned path.
- `python3 -I scripts/check-docs.py; echo "check-docs exit=$?"` → `check-docs exit=0` with no diagnostics; this validates this note's links, requirement IDs and Markdown structure.
- `grep -n 'paper_manual_capture\|patient_identification\|critical_contacts\|supervised_back_entry' docs/operations/recovery/operational-runbooks.md` → the four topics are declared.
- `python3 -I infra/recovery/recovery_evidence.py infra/recovery/fixtures/synthetic_recovery_evidence.json` → exit 0; the conforming rehearsal declares all four topics and duplicate prevention.
- `python3 -I infra/recovery/recovery_evidence.py infra/recovery/fixtures/violation_recovery_evidence.json` → exit 1 with `[FAIL]` for `runbooks` and `duplicate_prevention`; the harness fails closed.
- `python3 -I -m unittest discover -s infra/recovery/tests -q` → exit 0; the evaluator tests pass, including the requirement that `downtime_drills` cannot replace `supervised_back_entry`.
- `bash infra/recovery/check.sh` and `python3 -I infra/recovery/tests/coverage_gate.py` are the canonical recovery gate and coverage commands listed in [recovery-evidence.md](../../../operations/recovery/recovery-evidence.md) lines 30-39; they were not run in this documentation-only task and no result is claimed here.
- A Java reviewer may run `mvn -q -B -f apps/api/pom.xml test -Dtest=GateEvidenceTest,GateEvidenceControllerHttpTest` for the four-topic and duplicate-replay assertions; those tests were not run here and no result is claimed.

Human evidence a site must produce before G5 — no repository check can substitute for it:

- A **published on-call rota and escalation record** with named operational and clinical coverage owners and a recorded critical-alert escalation trail, supplied by engineering and operations with the site clinical lead (section 10; OPS-05 row of the launch checklist).
- A **conducted downtime drill artifact** (`kind: conducted_drill`) rehearsing paper/manual capture, patient identification, critical contacts and supervised back-entry, with observed RPO/RTO, reconciliation before writes reopened, named owners and corrective actions, supplied by engineering and operations with the integration lead, partner owners, privacy lead and site clinical lead (section 10; [recovery-evidence.md](../../../operations/recovery/recovery-evidence.md)).
- **Approved site policy** naming the backup, urgent and downtime channels and the people who may record verification evidence, supplied by the site clinical lead and operations owner under ADM-01; and per-partner outage-drill evidence, supplied by the integration lead and partner owner (section 10).
- A **signed site acceptance matrix and final G5 record** closing the staffing, downtime-drill and workflow rows, supplied by the per-site Product Owner and clinical lead (section 9 G5 row).
