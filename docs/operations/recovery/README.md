# OPS-04/OPS-05 recovery targets, clean-environment restore and operational runbooks

- Requirements: **OPS-04** (recovery targets, restore into a clean environment,
  reconciliation before reopening writes, verification cadence) and **OPS-05**
  (monitoring signals, operational and clinical owners, downtime and recovery
  runbooks, duplicate prevention).
- Status: the target contract, the fail-closed evidence evaluator, synthetic
  fixtures and the operational runbooks are delivered. A real restore drill and
  real measured RPO/RTO evidence are **not** produced here and remain an open G5
  gate.
- Audience: engineering, operations, the product owner, privacy and G5
  acceptance reviewers.

This directory is the engineering handoff for OPS-04 and OPS-05. It states the
exact target values, the restore and reconciliation procedure, the monitoring
and ownership envelope, how an evidence artifact is judged, and — deliberately —
what this repository cannot prove. The approved clause itself is in the
[product specification](../../../.opencode/config/PROJECT_V1_SPEC.md).

## Requirement mapping

| Requirement | Specification clause | Handled by |
|---|---|---|
| OPS-04 | RPO ≤ 15 minutes and RTO ≤ 4 hours for core chart, order data, documents and their associations; restore database, objects and IdP dependencies into a clean environment; reconcile hashes, source versions, in-flight deliveries and audit coverage before reopening writes; verify before pilot and quarterly thereafter | [Recovery targets](recovery-targets.md), [Restore procedure](restore-procedure.md) |
| OPS-05 | Monitor error rates, saturation, queue age, partner delivery, unmatched reports, critical acknowledgment time, missing coverage, unassigned patient messages, response breaches, failed instruction notifications, overdue instruction acknowledgments, rendering failures and backup status; define operational on-call and site clinical coverage owners; downtime and recovery runbooks for paper/manual capture, patient identification, critical contacts and supervised back-entry; prevent duplicates when recovering | [Operational runbooks](operational-runbooks.md) |

No new threshold or requirement ID is introduced by this handoff. Cross-references
to other requirement IDs (ARC-03 in the duplicate-prevention procedure, ARC-05 in
the event-deduplication rules, plus IAM-02, IAM-05 and XCH-05) point at the
specification's existing clauses and do not create new acceptance values.

## Three evidence tiers

| Tier | What it is | What it proves | What it does not prove |
|---|---|---|---|
| Target | The spec literals encoded in the [evaluator](../../../infra/recovery/recovery_evidence.py) | Fidelity of the encoding and fail-closed validation | That any target was achieved |
| Synthetic rehearsal | A seeded, synthetic artifact run by [`check.sh`](../../../infra/recovery/check.sh) in this repository | That the evaluator accepts a conforming artifact and rejects breaches | That a restore ran, that RPO/RTO were measured, or that anyone is on call |
| Conducted drill | An authorized restore drill artifact produced outside this repository | The drill's own recorded facts when the artifact conforms | Human or clinical sign-off; the flag records a *claim*, not an attestation |

A passing synthetic artifact is explicitly labelled `final_evidence: false`. The
evaluator never promotes a rehearsal to drill evidence, and a drill artifact that
names a synthetic source is rejected as an `evidence_conflict`.

## Document set

| Document | Covers |
|---|---|
| [Recovery targets](recovery-targets.md) | The enforced numbers and units, restore and reconciliation scope, verification cadence, and fail-closed rules |
| [Restore procedure](restore-procedure.md) | Clean-environment restore, the reconciliation gate, reopening writes and duplicate prevention |
| [Operational runbooks](operational-runbooks.md) | The 13 monitoring signals, named owners, the four required runbook topics and escalation |
| [Recovery evidence](recovery-evidence.md) | Artifact schema, commands, the observed rehearsal result, the honesty boundary and the remaining gate |

Executable material lives in `infra/recovery`: the
[evaluator](../../../infra/recovery/recovery_evidence.py), the
[recovery contract](../../../infra/recovery/recovery-contract.json), the
[conforming](../../../infra/recovery/fixtures/synthetic_recovery_evidence.json) and
[violation](../../../infra/recovery/fixtures/violation_recovery_evidence.json)
fixtures, and the unit tests.

## Verification

| Check | Command | Proves |
|---|---|---|
| Unit and fixture gate | `bash infra/recovery/check.sh` | 97 synthetic unit tests pass; the build-failing coverage gate passes; the conforming fixture passes and the violation fixture fails |
| Unit tests alone | `python3 -I -m unittest discover -s infra/recovery/tests -q` | The evaluator's enumerated branches behave as specified |
| Build-failing coverage gate | `python3 -I infra/recovery/tests/coverage_gate.py` | Exits 1 when any executable line of the evaluator is uncovered; wired into `check.sh` so incomplete coverage fails the build |
| Line coverage | `python3 -I -m trace --count --missing --coverdir /tmp/opencode/recovery_cov infra/recovery/tests/run_coverage.py` | Every executable line of the evaluator runs; stdlib `trace` is used because `coverage.py` is not installed. `--missing` is required: without it `trace` never annotates unexecuted lines and the measurement is vacuous |
| Links and requirement IDs | `python3 -I scripts/check-docs.py` | Relative links resolve and every requirement ID is defined in the specification |
| Credential and PHI shapes | `python3 -I scripts/check-secrets.py` | No credential or patient-identifier pattern was introduced |

The CLI guard line (`sys.exit(main())`) is marked `#pragma NO COVER`: it runs
only when the file is executed as a script, while the unit tests exercise the
same `main()` entry point by calling it directly. Branch coverage has no
mechanical measure in the standard library; the tests enumerate every
control-flow branch, including a case that asserts an empty artifact fails
every section check so no checker can be silently skipped. The coverage gate's
own tests include a real `trace` run on a deliberately under-covered probe
module, proving the gate detects uncovered lines rather than always passing.

## Decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | Only the literals and named sets in OPS-04/OPS-05 are enforced | The specification fixes the targets and the signal/scope vocabulary; inventing extra thresholds would overstate the contract |
| D2 | The field names `rpo_minutes` and `rto_hours` pin the units | A single number without a unit could be reinterpreted as seconds or hours; exact field names prevent a unit fail-open |
| D3 | RPO = 0 is accepted | Zero data loss satisfies "≤ 15 minutes"; only RTO must be strictly positive |
| D4 | Scope, restore targets, signals and runbooks are "must include, no duplicates", extras allowed | Recording more recovery scope or more monitoring signals cannot hide a required one; exact-set equality would reject a legitimate broader drill |
| D5 | Boolean gates must be the literal `true`, never a truthy value | `bool("false")` is `True`; `is True` fails closed on strings and integers |
| D6 | `kind` is the evidence-class authority; a synthetic-labelled source on a conducted drill is a conflict | Free-text source alone cannot promote a rehearsal, and it cannot be used to smuggle one in either |
| D7 | Revocation and withdrawal reapplication is a mandatory **runbook** gate, not a new evaluator threshold | It follows from IAM-02, IAM-05 and §11, but OPS-04 does not list it among the reconciliation items; encoding it as a spec literal would be fabrication |
| D8 | Reserved migration version V48 is not used | No schema change is needed; version gaps are allowed by the repository's change-safety gate |

## Boundaries

- Synthetic data only. No fixture contains a real person, identifier or credential.
- No clinical, privacy or operations approval is implied. Restore, availability
  and operations sign-off belong to the accountable owners in the specification's
  decision table.
- No application code, schema, API contract, CI or tracker file belongs in this
  story's scope. The two scope directories are the only files changed.

## Open items

| Item | Why it is open | Who can close it |
|---|---|---|
| A real restore drill with measured RPO/RTO | No deployment, backup or restore target exists in this repository; Docker is unavailable locally | Engineering with the operations owner, in an authorized environment |
| Reconciliation of real hashes, source versions, in-flight deliveries and audit coverage | Requires restored production-like artifacts and the append-only chain verifier | Engineering and privacy, under an authorized drill |
| Actual quarterly drill execution | One artifact cannot prove a recurring schedule was followed | Operations, from successive dated drill records |
| Human acceptance of the conducted-drill evidence | A script cannot attest that a drill really happened | G5 acceptance owners |
