# WEB-11 clause 3 — roll out progressively and roll back automatically on error rate or latency

## Clause

Roll out progressively (5%, 25%, 100%) and roll back automatically when error rate or latency breaches its threshold.

## Status

Not built

## Evidence

- Requirement and clause text: [SMARTCLINIC_V1_SPEC.md](../../../../SMARTCLINIC_V1_SPEC.md), WEB-11 row (line 159). Line 163 states that, before G5, evidence for WEB-01, WEB-04, WEB-11 and WEB-12 is produced with the infrastructure owner; the rest is produced by CI and the end-to-end suite.
- The nearest artifact in the repository is a synthetic manifest evaluator: [delivery_safety_evidence.py](../../../../infra/delivery-safety/delivery_safety_evidence.py), driven by the synthetic fixture [synthetic_evidence.json](../../../../infra/delivery-safety/fixtures/synthetic_evidence.json) and invoked by [check.sh](../../../../infra/delivery-safety/check.sh). Its declared contract is documented in [rollout-and-production-access.md](../../../operations/delivery-safety/rollout-and-production-access.md).
- The evaluator checks only the *shape* of a declared rollout: `strategy` equal to `progressive`; `stages` as at least two distinct non-blank names whose last member is `full`; and literal `true` for `health_gates`, `monitoring` and `rollback_tested`. In the synthetic fixture `rollout.stages = [internal, canary, partial, full]` and `rollback_tested: true`.
- That is **not** the clause. The evaluator does not check the 5%/25%/100% percentages, does not evaluate any error-rate or latency threshold, and does not trigger or observe an automatic rollback. The fixture declares `kind: synthetic_rehearsal` and `synthetic_only: true`, and the operations document states that the evaluator "cannot observe live health signals, confirm that a rollback was rehearsed, verify the approver's identity or authority, or check that an access grant actually expired. It judges the declared contract only."
- No deployment workflow, infrastructure-as-code, cloud account or rollout controller in this repository implements the clause, and there is no workflow run or production evidence to cite. The related decision [0002-ops06-migration-change-safety.md](../../../decisions/0002-ops06-migration-change-safety.md) records that environment-account separation, signed builds, monitored progressive rollout and audited production access cannot be enforced offline, because there is no deployment workflow or cloud account to bind them to, and assigns them to cloud/human-gated work.

## Gap

S-G5-07 delivered the OPS-06 synthetic manifest evaluator; it is recorded as a completed effort in the archived tracker and in this repository's commit history. No PR number is asserted here: the archived tracker records one number and the only matching merge commit in this repository names another, so the provenance is disputed and this note does not rely on either. That effort proves synthetic manifest validation only. Real signed builds with an SBOM, a monitored 5%/25%/100% traffic shift and automatic rollback on an error-rate or latency breach remain cloud/human-gated (decision 0002; specification line 163).

The infrastructure owner must supply the real deployment evidence, together with a named signed release approval before G5; CI cannot attest to it offline. Closing the gap needs at least two separate efforts, neither of which is this note: (1) deployment tooling — infrastructure-as-code under `infra/` plus a deployment workflow under `.github/workflows/`, neither of which holds such a file today — that performs the actual staged traffic shift and threshold-triggered automatic rollback; and (2) a site G5 evidence task producing the signed artifact, SBOM, canary records, an executed rollback rehearsal and named approvers. No new task ID is claimed here.

## How to verify

Reviewer commands available in this repository (both prove only the synthetic contract, not the clause):

1. `python3 -I scripts/check-docs.py` — checks Markdown links, requirement IDs and document structure; exits 0 and is silent on success.
2. `bash infra/delivery-safety/check.sh` — runs the OPS-06 evaluator's unit tests and fixture checks; final line `S-G5-07: OPS-06 evaluator unit tests and fixture checks passed`.

Human evidence a site must produce before G5 and present to the reviewer, none of which is in this repository today:

- the signed release artifact and its SBOM, promoted unchanged through the environments;
- canary records showing actual 5%, 25% and 100% traffic splits and the health signals observed at each stage;
- defined error-rate and latency thresholds and a rehearsed automatic rollback whose logs show the breach triggering the rollback;
- the named approvers and the signed release approval.
