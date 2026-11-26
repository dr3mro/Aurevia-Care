# OPS-06 clause 1 — Use separate development, test, staging and production accounts/resources

## Clause

Use separate development, test, staging and production accounts/resources.

## Status

Not built

Rule 3 forces this status. The clause needs real cloud accounts, verified IAM isolation, pipeline records and a signed approval, and the repository holds none of them. The [delivery-safety handoff](../../../operations/delivery-safety/README.md) line 104 says "No cloud account or infrastructure as code exists in this repository"; [Environment and fixture separation](../../../operations/delivery-safety/environment-and-fixtures.md) lines 61-68 say a conforming declaration cannot prove that the accounts exist or that IAM policies keep them separate. Four distinct *declared* identifier strings are not four provisioned accounts, so `Partly built` would overclaim. No real account identifier, resource ARN, IAM policy, workflow run or approval is asserted in this note.

## Evidence

What the specification requires (authoritative text):

- OPS-06 in [PROJECT_V1_SPEC.md](../../../../.opencode/config/PROJECT_V1_SPEC.md) line 139 contains the clause verbatim. The G5 evidence rule at line 163 assigns evidence for WEB-01, WEB-04, WEB-11 and WEB-12 to be produced with the infrastructure owner, and the G5 launch row at line 174 requires per-site approval. A script cannot manufacture either.

What actually runs in the repository — a fail-closed checker, not the clause:

- [delivery_safety_evidence.py](../../../../infra/delivery-safety/delivery_safety_evidence.py) lines 89-122 enforce the exact four environment names and four distinct, non-blank *declared* account and resource strings. [check.sh](../../../../infra/delivery-safety/check.sh) runs the evaluator's unit tests, asserts the conforming fixture passes and both negative fixtures exit exactly 1, then runs the real migration gate; it ends `S-G5-07: OPS-06 evaluator unit tests and fixture checks passed`. This is a **false green** for clause 1: the S-G5-07 evaluator checks declared metadata only. It proves the checker works, never that an account exists or is isolated.

Declared-only input, deliberately synthetic:

- [delivery-safety-policy.json](../../../../infra/delivery-safety/delivery-safety-policy.json) declares `"source": "synthetic-policy-declaration"` (line 4), the four environment names (line 5) and `"synthetic_only": true` (line 13).
- [synthetic_evidence.json](../../../../infra/delivery-safety/fixtures/synthetic_evidence.json) is `kind: synthetic_rehearsal` (line 3); its four accounts and resources at lines 5-9 are placeholder strings and `synthetic_only` at line 12 is a literal. Running the evaluator on it prints `evidence class: rehearsal (final: False)`.
- The evaluator's own header at lines 13-15 admits it "cannot prove that cloud accounts are isolated". [environment-and-fixtures.md](../../../operations/delivery-safety/environment-and-fixtures.md) lines 61-68 repeat that boundary; the [delivery-safety README](../../../operations/delivery-safety/README.md) lines 27-45 define three evidence tiers with a rehearsal explicitly labelled `final_evidence: false`, and lines 100-109 list the open items with named suppliers.
- [decision 0002](../../../decisions/0002-ops06-migration-change-safety.md) lines 9 and 28 record that no infrastructure as code or deployment workflow exists, that a declared environment manifest would be vacuous, and that account separation was deferred to the cloud/human-gated S-G5-07.

Absence, checked on this checkout rather than assumed: no Terraform, CloudFormation or other IaC file exists under `infra/`; no account-separation or environment-promotion step exists in `.github/workflows`; [DEPLOYMENT.md](../../../../infra/DEPLOYMENT.md) is a design handoff for a disposable container stack, not a bound deployment. The sibling clause notes [build-once-promote-same-signed-artifact-through.md](../release/build-once-promote-same-signed-artifact-through.md) and [release-rollback.md](../release/release-rollback.md) record the same `Not built` boundary for adjacent WEB-11 evidence; they are templates for this note, not evidence of separation. No workflow run, account identifier, digest, signer or approval is quoted here.

## Gap

Missing before G5:

1. A real four-account topology with verified IAM isolation, network boundaries, per-account encryption and audit aggregation. The repository holds only declared strings. Files that would change: new business-named IaC under `infra/cloud/` (for example `accounts/`, `network/`, `data/`, `compute/` and `security/`, none of which exists today) plus a revised [DEPLOYMENT.md](../../../../infra/DEPLOYMENT.md). Cloud/IaC work: **NO TASK** — no task in `tasks/` claims it.
2. Pipeline records proving that dependency and secret scans ran clean and that account-scoped deployment actually used the separated accounts. The CI checks are only declared booleans in the synthetic fixture. Files that would change: `.github/workflows/` deployment and promotion workflows, and the [delivery-safety README](../../../operations/delivery-safety/README.md) open-items table.
3. Approved, audited, expiring production-access records with a requester distinct from the approver. Only declared placeholders exist today. Files that would change: the delivery-safety README open-items table, [site-launch-approvals.md](../../../launch/site-launch-approvals.md) and the readiness trackers under `docs/operations/production-readiness/`.
4. Human G5 acceptance of the four-account evidence. A script cannot attest that a deployment occurred. Files that would change: the G5 evidence pack and the production-readiness trackers.

Number of separate tasks needed: at least **four** beyond this note — cloud/IaC topology with verified isolation (NO TASK); pipeline scan and deployment records (NO TASK); named production-access approval records (NO TASK); and G5 acceptance (owner: G5 acceptance owners; no task created by this note). This note counts the work and changes none of those files.

Suppliers named by the [delivery-safety README](../../../operations/delivery-safety/README.md) lines 104-109: real four-account topology with verified isolation — **Cloud infrastructure owner with DevSecOps**; clean scan pipeline evidence — **DevSecOps with the CI owner**; approved, audited, expiring production access — **named approver and privacy reviewer**; human G5 acceptance — **G5 acceptance owners**.

## How to verify

Repository checks a reviewer can run now. Every one proves absence, machinery or declarations — none proves separate real accounts:

- `python3 -I scripts/check-docs.py` — exit 0 and no output: this note's relative links, requirement IDs and Markdown structure are valid. This is the acceptance gate; it passed before the note existed, which is the gap.
- `bash infra/delivery-safety/check.sh` — exit 0; final line `S-G5-07: OPS-06 evaluator unit tests and fixture checks passed`. Read it as a false green against the next two commands.
- `grep -n 'synthetic-policy-declaration\|synthetic_only' infra/delivery-safety/delivery-safety-policy.json infra/delivery-safety/fixtures/synthetic_evidence.json` — shows the policy's `source` at line 4, `synthetic_only` in the policy at line 13 and in the fixture at line 12.
- `grep -rnEi 'SBOM|cosign|sigstore|promote|signed artifact' .github/workflows` — no matches, exit 1: no signing, SBOM or promotion step in any workflow. Widening the pattern with `attestation|provenance` adds one hit, the comment at `delivery.yml:277` about reusing an already-published image tag; that comment is not a signing, promotion or attestation step.
- `find infra -type f \( -name '*.tf' -o -name '*.tfvars' -o -name '*.hcl' \)` — no output: no infrastructure as code to bind the four accounts to.
- `grep -n '"kind": "measured_deployment"' infra/delivery-safety/fixtures/*.json` — only the rejected `relabelled_violation_evidence.json` at line 3; no accepted final-evidence fixture exists.
- `grep -n 'prove that cloud accounts are isolated' infra/delivery-safety/delivery_safety_evidence.py` — line 14, the evaluator's own shape-only boundary stated across lines 13-15.
- `grep -n 'Real four-account cloud topology with verified isolation' docs/operations/delivery-safety/README.md` — line 104, status open, supplier named.

Human evidence a site must produce before G5 — no command or synthetic fixture can substitute for it:

- Four provisioned, isolated accounts with IAM policies, network boundaries and audit records demonstrating that development, test, staging and production cannot reach one another's resources, supplied by the **Cloud infrastructure owner with DevSecOps**.
- Pipeline records showing clean dependency and secret scans and an account-scoped deployment using those separated accounts, supplied by **DevSecOps with the CI owner**.
- Approved, audited, expiring production-access records with a requester distinct from the approver, supplied by the **named approver and privacy reviewer**.
- A signed G5 acceptance record attaching the topology and pipeline evidence, supplied by the **G5 acceptance owners** under the specification's G5 rule.
