# OPS-06 separate environments, signed builds and progressive rollout

- Requirement: OPS-06
- Status: the enforced contract, the synthetic evidence evaluator and this
  handoff are delivered. Real deployment evidence and per-site launch approval
  are **not** produced here and remain open G5 gates.
- Audience: engineering, operations, DevSecOps, the product owner and G5
  acceptance reviewers.

This directory is the engineering handoff for the part of OPS-06 that decision
record `docs/decisions/0002-ops06-migration-change-safety.md` deferred from
S-G0-03d: environment separation, synthetic fixtures, CI policy and scanning
gates, signed builds, migration checks, monitored progressive rollout and
audited production access. The change-safety checker itself already exists at
[`scripts/check-migrations.py`](../../../scripts/check-migrations.py); this story
does not duplicate it, it requires it by name.

## Requirement mapping

| Requirement | Specification clause | Handled by |
|---|---|---|
| OPS-06 | Separate development/test/staging/production accounts and resources; synthetic fixtures; CI policy checks; dependency and secret scanning; signed builds; migration checks; monitored progressive rollout; reversible deployments and expand/contract schema changes; audited production access | [Environment and fixture separation](environment-and-fixtures.md), [Build and migration gates](build-and-migration-gates.md), [Rollout and production access](rollout-and-production-access.md), [Evidence contract](evidence-contract.md) |

The approved clause is in the [product specification](../../../.opencode/config/PROJECT_V1_SPEC.md).
No new threshold or requirement ID is introduced by this handoff.

## Three evidence tiers

| Tier | What it is | What it proves | What it does not prove |
|---|---|---|---|
| Contract | The literals encoded in the [evaluator](../../../infra/delivery-safety/delivery_safety_evidence.py) and the [policy manifest](../../../infra/delivery-safety/delivery-safety-policy.json) | Fidelity of the encoding and fail-closed validation | That any environment, build or rollout exists |
| Synthetic rehearsal | A seeded artifact run by [`check.sh`](../../../infra/delivery-safety/check.sh) in this repository | That the evaluator accepts a conforming artifact and rejects breaches | Isolation of real accounts, a real signature, a real rollout or real approval |
| Measured deployment | An authorized artifact produced outside this repository from real pipeline and cloud evidence, carrying run provenance | What the artifact *declares* about a real rollout | Independent verification of those claims or G5 clinical/operational acceptance |

A passing synthetic artifact is explicitly labelled `final_evidence: false`. A
`measured_deployment` artifact is not granted final evidence on a bare
declaration: it must carry run provenance (tool, version, command, UTC time,
environment and source SHA), matching the sibling OPS-03 evaluator. Even then a
passing measured artifact is a structured claim: the evaluator checks the
presence and shape of that provenance, not its truth — it cannot verify a
signature's trust root, that scans actually ran, or that access expired.
Relabelling a rehearsal alone cannot yield final evidence: the canonical gate
runs a dedicated negative fixture that changes only `kind` and `source` on the
conforming rehearsal and asserts the artifact is still rejected (exit 1) because
it carries no run provenance.

## Document set

| Document | Covers |
|---|---|
| [Environment and fixture separation](environment-and-fixtures.md) | The four-environment allowlist, distinct identifiers, synthetic-only fixtures and the three CI gates |
| [Build and migration gates](build-and-migration-gates.md) | Immutable digest, signature verification, promotion gating and the change-safety gate |
| [Rollout and production access](rollout-and-production-access.md) | Progressive stages, health gates, monitoring, rollback and separated, audited, expiring approval |
| [Evidence contract](evidence-contract.md) | Artifact schema, every named check, branch enumeration, commands and the honesty boundary |

Executable material lives in `infra/delivery-safety`: the
[evaluator](../../../infra/delivery-safety/delivery_safety_evidence.py), the
[policy manifest](../../../infra/delivery-safety/delivery-safety-policy.json), the
[conforming](../../../infra/delivery-safety/fixtures/synthetic_evidence.json),
[violation](../../../infra/delivery-safety/fixtures/violation_evidence.json) and
[relabelled-rehearsal](../../../infra/delivery-safety/fixtures/relabelled_violation_evidence.json)
fixtures, the tests and the canonical gate.

## Verification

| Check | Command | Proves |
|---|---|---|
| Unit and fixture gate | `bash infra/delivery-safety/check.sh` | 71 synthetic unit tests pass; the conforming fixture passes, both negative fixtures exit exactly 1, and the real migration gate is green |
| Unit tests alone | `python3 -I -m unittest discover -s infra/delivery-safety/tests -q` | The evaluator's enumerated branches behave as specified |
| Line coverage | `python3 -m trace --count --coverdir /tmp/opencode/delivery-safety_cov infra/delivery-safety/tests/run_coverage.py` | All 248 executable lines of the evaluator run; stdlib `trace` is used because `coverage.py` is not installed |
| Links and requirement IDs | `python3 -I scripts/check-docs.py` | Relative links resolve and every requirement ID is defined in the specification |
| Credential and PHI shapes | `python3 -I scripts/check-secrets.py` | No credential or patient-identifier pattern was introduced |

## Decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | The evaluator judges structure, not reality | Decision 0002 rejected a self-attesting environment manifest as vacuous; the checks are fail-closed on form, and real verification stays a cloud/human gate |
| D2 | Environment names and identifiers use exact allowlists and distinctness | Aliases (`prod`, `qa`) or a shared account would defeat separation; exact string equality prevents case/whitespace conflation |
| D3 | Booleans must be the literal `true` | `1`, `"true"` and other truthy values are rejected, so a weakened control cannot pass by type confusion |
| D4 | The migration gate must name the existing checker | The artifact carries the label token `check-migrations --require-baseline`; the authoritative offline command `python3 -I scripts/check-migrations.py --require-baseline` is executed by the canonical gate, not inferred from the artifact |
| D5 | Production access requires a requester distinct from the approver | Separation of duties is the minimum control behind "audited approval" |
| D6 | A synthetic rehearsal is never final evidence | A rehearsal proves the check works, not that a deployment met it |
| D7 | Reserved migration version V49 is unused | No schema change is needed; version gaps are allowed by the repository's change-safety gate |
| D8 | No CI/workflow or shared script was wired | Those files are outside this story's scope; the gate is self-contained and the integrator runs the repository-wide checks |
| D9 | A `measured_deployment` claim must carry six provenance fields | Without them, changing only `kind` and `source` would grant `final_evidence` to a synthetic rehearsal; this matches the sibling OPS-03 gate (decision made after a defensive review finding) |
| D10 | All distinctness checks compare stripped values | Account/resource identifiers, rollout stages and production-access requester/approver are normalized before comparison, so whitespace padding cannot alias two values or self-approve access |
| D11 | The violation fixture must exit exactly 1 | Any other non-zero exit is a parse/IO regression, not a rejected artifact; the canonical gate now asserts the exact status |
| D12 | A relabelled-rehearsal negative fixture is enforced by the canonical gate | A rehearsal that changes only `kind` and `source` must still be rejected for missing provenance; proving this at gate level (not just in unit tests) guards the honesty rule against future weakening |

## Boundaries

- Synthetic data only. No fixture contains a real person, identifier or credential.
- No clinical or launch approval is implied. Site configuration, release policy,
  partner contracts and G5 acceptance belong to the accountable owners in the
  specification's decision table.
- No application code, schema, API contract, CI or tracker file belongs in this
  story's scope. The two scope directories are the only files changed.

## Open items

| Item | Why it is open | Who can close it |
|---|---|---|
| Real four-account cloud topology with verified isolation | No cloud account or infrastructure as code exists in this repository | Cloud infrastructure owner with DevSecOps |
| Pipeline evidence that dependency and secret scans ran clean | No deployment pipeline artifact is available offline | DevSecOps with the CI owner |
| Cryptographically verified signed build and trusted identity | The evaluator checks declared metadata, not a signature | Release engineering with the signing authority |
| Monitored progressive rollout with a rehearsed rollback | No deployment exists to observe | Operations with the system designer |
| Approved, audited, expiring production access records | Approvals are human decisions | Named approver and privacy reviewer |
| Human G5 acceptance of the evidence | A script cannot attest that a deployment really occurred | G5 acceptance owners |

Evidence source for the delivered checks: executed tool commands in this
repository plus bounded simulated specialist design reviews. There is no human,
clinical or real-deployment evidence in this story.
