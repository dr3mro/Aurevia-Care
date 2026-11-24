# WEB-11 clause 5 — rehearse a rollback before pilot and keep a release checklist with named approvers

## Clause

Rehearse a rollback before pilot and keep a release checklist with named approvers.

## Status

Not built

The clause asks for two human artifacts: a rollback actually rehearsed before pilot, and a release checklist naming actual approvers. This repository holds an evaluator, a synthetic rehearsal fixture and open handoff rows, but no rehearsal record, no run provenance for a performed rollback and no named individual approver. Under the task rule, a clause that needs a person, a cloud account or a signed decision is `Not built` unless the evidence is already in the repository, so the status is `Not built`. The presence of `rollback_tested: true` in a synthetic fixture is a declared literal, not a performed drill, and the launch checklist states explicitly that a passing synthetic rehearsal is evidence for none of its rows.

## Evidence

Authoritative text and ownership:

- [SMARTCLINIC_V1_SPEC.md](../../../../SMARTCLINIC_V1_SPEC.md) line 159 defines WEB-11, whose last sentence is this clause. Line 163 requires evidence for WEB-01, WEB-04, WEB-11 and WEB-12 to be produced with the **infrastructure owner**; the remaining evidence comes from CI and the end-to-end suite. The section 9 G5 row requires each site to approve its workflow and release policy, and the section 10 decision "Implementation priorities and release approval" assigns the signed acceptance matrix and roll-forward/rollback criteria to the Product, clinical, engineering and privacy owners.

Executable material — real, but it proves only the evaluator and its synthetic input:

- [delivery_safety_evidence.py](../../../../infra/delivery-safety/delivery_safety_evidence.py) lines 201–231 (`_check_rollout`) checks that a JSON contract declares `strategy: progressive`, at least two distinct stage names ending in `full`, and the literal booleans `health_gates`, `monitoring` and `rollback_tested`. `_check_production_access` checks a requester distinct from an approver and audited, expiring approval. Both judge declared form only.
- [rollout-and-production-access.md](../../../../docs/operations/delivery-safety/rollout-and-production-access.md), "What this does not prove": the evaluator cannot confirm that a rollback was rehearsed, verify the approver's identity or authority, or observe live health signals; real monitoring dashboards, rollback drill records and audit-log extracts must be reviewed by operations and the privacy owner. The same document requires `rollback_tested: true` as a contract literal, which is precisely the gap between a declaration and a rehearsal.
- [synthetic_evidence.json](../../../../infra/delivery-safety/fixtures/synthetic_evidence.json) lines 33–46 sets `rollback_tested: true`, four rollout stages and `requester: deployment-bot` / `approver: release-manager` inside `kind: synthetic_rehearsal` with `fixtures.synthetic_only: true`. These are placeholder tokens in a seeded fixture, not named people and not a drill record.
- [check.sh](../../../../infra/delivery-safety/check.sh) lines 20–31 asserts that a relabelled synthetic rehearsal — one changing only `kind` and `source` — is still rejected with exit 1. [test_delivery_safety_evidence.py](../../../../infra/delivery-safety/tests/test_delivery_safety_evidence.py) exercises the evaluator's branches. Neither command performs a rollback; they only test the gate.
- [delivery-safety/README.md](../../../../docs/operations/delivery-safety/README.md) separates three evidence tiers (contract, synthetic rehearsal, measured deployment). Decision D6 states that a synthetic rehearsal is never final evidence, and its Open items table records "Monitored progressive rollout with a rehearsed rollback — No deployment exists to observe — Operations with the system designer" and "Human G5 acceptance of the evidence — G5 acceptance owners".
- [recovery-contract.json](../../../../infra/recovery/recovery-contract.json) line 56: "A synthetic rehearsal artifact is never final evidence; human acceptance remains a G5 gate."
- [decision 0002](../../../../docs/decisions/0002-ops06-migration-change-safety.md) line 28 rejected enforcing a declared environment manifest: with no deployment workflow or cloud account to bind to, such a manifest asserts values it cannot verify and would pass vacuously.
- [DEPLOYMENT.md](../../../../infra/DEPLOYMENT.md) line 7 says to verify staging smoke, load capacity, failover and rollback before authorized deployment. This is a design expectation, not a record of a rehearsal.

Handoff rows — all open, none closed by a repository check:

- [launch-checklist.md](../../../../docs/operations/production-readiness/launch-checklist.md) line 27 is the WEB-11 rollout row: owner **Infrastructure**, status `open`, evidence required "Monitored rollout record showing all three stages, health gates, a rehearsed rollback and the release checklist with named approvers", with [rollout-and-production-access.md](../../../../docs/operations/delivery-safety/rollout-and-production-access.md) as the handoff, not the evidence. Line 26 is the companion WEB-11 signed-artifact/SBOM row, also `open`. Lines 5–10 state that "Owner" is a role, not a person's name — the accountable individual is on the G5 acceptance record — and lines 12–15 state that every row is `open` and a passing synthetic rehearsal is evidence for none of them.
- [production-readiness/README.md](../../../../docs/operations/production-readiness/README.md) and [evidence.md](../../../../docs/operations/production-readiness/evidence.md) are the production-readiness register and evidence register; they do not hold a performed rollback drill or a signed release checklist.

Evidence source: executed tool commands and direct file reads in this checkout for the survey; no reviewer, simulated user, human participant, operations team or partner supplied a rehearsal or approval for this story.

## Gap

Missing before G5:

1. A real pre-pilot rollback rehearsal record: UTC date, target environment, artifact digest rolled back from and to, the actor, each step, the observed health signals across the 5%/25%/100% stages, the breach trigger that decided the rollback, the measured recovery time and reconciliation of committed records. The `rollback_tested: true` literal in the synthetic fixture does not supply any of this. Supplier: the **infrastructure owner**, with operations and the system designer (WEB-11 line 163, launch-checklist WEB-11 row, delivery-safety README Open items).
2. A release checklist with named individual approvers, each approval carrying scope, timestamp and signature, plus the final G5 acceptance record that associates those accountable individuals. The launch checklist's "Owner" column holds roles, not names, and its bot/`release-manager` fixture tokens are placeholders. Supplier: the **G5 acceptance owners**, the per-site Product Owner and clinical lead, with the privacy owner reviewing drill records and audit-log extracts.
3. A signed acceptance matrix with roll-forward/rollback criteria for release approval. Supplier: the Product, clinical, engineering and privacy owners (section 10 decision table).
4. A real deployment and cloud binding to observe. No deployment workflow or cloud account exists in this repository, so the repository cannot produce a measured rollout artifact (decision 0002, delivery-safety README Open items).

Files that would change to close the human portion: `docs/operations/production-readiness/launch-checklist.md` (WEB-11 rows 26–27 closed with attached evidence), `docs/operations/delivery-safety/rollout-and-production-access.md` (handoff pointing at the real artifacts), the G5 evidence pack under `docs/launch/` (signed release checklist and rollback rehearsal record), and `infra/DEPLOYMENT.md` once a real rollback procedure is bound. If the real record must be machine-checked, the artifact schema in `infra/delivery-safety/delivery_safety_evidence.py` would gain provenance validation; that is a separate coded task and is not assumed here. This note changes none of those files.

Separate tasks needed: **at least three**. (1) A human/cloud task for the infrastructure owner, operations and the system designer to perform and record the pre-pilot rollback rehearsal with run provenance. (2) A human task for the named approvers and the per-site Product Owner/clinical lead to sign the release checklist and the G5 acceptance record, with the privacy owner's review. (3) A deployment/infrastructure task to bind a real progressive-rollout workflow and environments (no such task exists today). A fourth, tooling task is needed only if the artifact contract is extended to validate the real record.

## How to verify

Repository checks a reviewer can run now — these prove only the evaluator and its synthetic fixture, never a rehearsal or an approval:

- `python3 -I scripts/check-docs.py` — this note's relative links resolve and every requirement ID is defined (exit 0).
- `bash infra/delivery-safety/check.sh` — evaluator unit tests pass, the conforming synthetic fixture passes, both negative fixtures exit exactly 1 and the real migration gate is green; final line `S-G5-07: OPS-06 evaluator unit tests and fixture checks passed`.
- `python3 -I -m unittest discover -s infra/delivery-safety/tests -q` — evaluator branches only.
- `grep -n 'rollback_tested' infra/delivery-safety/fixtures/synthetic_evidence.json` — shows the literal in a `synthetic_rehearsal` fixture, not a drill record.
- `grep -n 'WEB-11' docs/operations/production-readiness/launch-checklist.md` — both WEB-11 rows remain `open`.

Human evidence a site must produce before G5 — no command or synthetic artifact can substitute for it:

- The **infrastructure owner**, with operations and the system designer: a rollback rehearsed before pilot, recorded with date, environment, artifact digest, actor, steps, trigger, observed health signals at 5%/25%/100%, measured recovery time and record reconciliation, together with the monitored rollout record showing all three stages and health gates (WEB-11, launch-checklist WEB-11 rollout row).
- **Named release approvers**: a release checklist naming individuals rather than roles or bot tokens, each approval carrying scope, timestamp and signature; the accountable individuals are recorded on the G5 acceptance record (launch-checklist.md lines 5–10).
- The **Product, clinical, engineering and privacy owners**: a signed acceptance matrix with roll-forward/rollback criteria (section 10 decision table).
- The **privacy owner** with operations: a review of the real monitoring dashboards, rollback drill records and audit-log extracts that the evaluator cannot verify (rollout-and-production-access.md, "What this does not prove").
- The **per-site Product Owner and clinical lead**: a signed G5 site acceptance matrix with no unresolved patient-safety, tenant-isolation, record-integrity or critical-escalation defect in enabled scope (section 9 G5 row).
