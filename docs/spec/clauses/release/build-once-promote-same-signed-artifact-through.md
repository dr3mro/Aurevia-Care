# WEB-11 clause 2 — Build once and promote the same signed artifact through environments with an SBOM

## Clause

Build once and promote the same signed artifact through environments with an SBOM.

## Status

Not built

Rule 3 forces this status. The clause needs a signed artifact, an SBOM, a trusted signer and a real cross-environment promotion record. The repository holds none of them: it holds a build-and-publish workflow that does not sign or produce an SBOM, and a synthetic evaluator that checks only declared metadata. A human, a cloud account and a signed decision are required before G5, and the repository does not hold that evidence. The synthetic promotion-gate checker is a declared-only rehearsal, not an implemented signed-artifact mechanism, so it does not justify `Partly built`.

## Evidence

What the specification requires (authoritative text):

- WEB-11 in [SMARTCLINIC_V1_SPEC.md](../../../../SMARTCLINIC_V1_SPEC.md) requires the same signed artifact with an SBOM to be promoted through environments, alongside progressive rollout, expand/contract migrations and a release checklist. The G5 evidence rule below the same requirement table assigns evidence for WEB-11 to be produced with the infrastructure owner; a script cannot manufacture it.
- The [WEB-11 evidence register row](../../../operations/production-readiness/evidence.md) marks this requirement `needs infrastructure owner` and states that the smallest closing task is to attach the signed-artifact SBOM and matching digest promoted unchanged. It is deliberately not `missing` because section 8.1 puts verification outside worker scope.

Real mechanisms in the repository — partial only; each proves a build, never a signature or a promotion:

- [release-app-image.yml](../../../../.github/workflows/release-app-image.yml) builds the image on native amd64 and arm64 runners, smoke-tests each, pushes per-architecture tags, and assembles a multi-architecture manifest with `docker buildx imagetools create`. It rejects a replaced immutable version tag with `imagetools inspect` (lines 99-103) and verifies that each per-architecture digest appears in the manifest (lines 107-113). It contains no SBOM generation, no cosign or sigstore signing, no attestation and no environment-promotion step. A repository-wide search of `.github/workflows` for `SBOM`, `cosign`, `sigstore`, `attestation`, `provenance`, `promote` and `signed artifact` returns no matches.
- [delivery_safety_evidence.py](../../../../infra/delivery-safety/delivery_safety_evidence.py) lines 159-180 check only the *declared* `signed_builds` fields: `method`, a `sha256:` digest shape, `verified: true`, a non-blank `signer_identity` and `promotion_gate: signature_verified`. [check.sh](../../../../infra/delivery-safety/check.sh) passes these against fixtures, but [delivery-safety-policy.json](../../../../infra/delivery-safety/delivery-safety-policy.json) declares `source: synthetic-policy-declaration` and `synthetic_only: true`, and [synthetic_evidence.json](../../../../infra/delivery-safety/fixtures/synthetic_evidence.json) carries `kind: synthetic_rehearsal`, a repeated fake digest and the placeholder signer `ci-signer-release`. No key, signature or real promotion record exists in these files.

Authoritative statements that the artifact is not proved (the repository's own boundaries):
[build-and-migration-gates.md](../../../operations/delivery-safety/build-and-migration-gates.md) lines 47-54 state that the evaluator cannot verify the cryptographic signature, the trust root behind `signer_identity`, or that promotion was truly blocked, and that real provenance must be supplied by release engineering and reviewed by DevSecOps.
[rollout-and-production-access.md](../../../operations/delivery-safety/rollout-and-production-access.md) lines 48-55 give the same boundary for rollout and access evidence.
[delivery-safety README.md](../../../operations/delivery-safety/README.md) lines 100-109 list the open items and their suppliers: a cryptographically verified signed build and trusted identity (release engineering with the signing authority), pipeline evidence that scans ran clean (DevSecOps with the CI owner), a real four-account cloud topology (cloud infrastructure owner with DevSecOps), a monitored rollout with rehearsed rollback (operations with the system designer), approved production-access records (a named approver and privacy reviewer) and human G5 acceptance (G5 acceptance owners).
The [launch checklist](../../../operations/production-readiness/launch-checklist.md) rows 26-27 for WEB-11 are both `open`, each linked to a supporting delivery-safety document but with no SBOM, signature or promotion evidence attached, and line 12 states that every row is open. The [production-readiness register](../../../operations/production-readiness/README.md) line 19 names the Infrastructure owner as `unassigned` and `not started`. The [bad-deploy runbook](../../../operations/runbooks/bad-deploy.md) lines 119-122 mark progressive rollout and automatic rollback `NOT YET IN PLACE`, and [policy-pack.md](../../../launch/policy-pack.md) line 299 leaves `SBOM, signature and digest verification` unchecked.
[infra/DEPLOYMENT.md](../../../../infra/DEPLOYMENT.md) line 7 is a design statement only ("Promote the same image digest"), with no infrastructure-as-code or deployment workflow binding it. [Decision 0002](../../../decisions/0002-ops06-migration-change-safety.md) line 9 assigns signed builds to the cloud/human-gated S-G5-07. The [first-release architecture-tag note](../../../operations/workflow-changes/first-release-architecture-tag.md) line 3 marks its workflow patch `proposed, not applied` (line 32 ties the overwrite risk to WEB-11), and the [GitHub verification checklist](../../../operations/workflow-changes/github-verification-checklist.md) line 153 likewise marks a proposed patch `not applied`.
The [OPS-01 clause 1 note](../policy/define-hosting-region-applicable-law-retention-legal.md) is the template for this note and gives the same `Not built` status under rule 3 for a signed decision the repository does not hold.

Searched for and not found in the repository: an SBOM file; a cosign, sigstore or in-toto signing step; a signature or attestation artifact; a trusted signer identity or trust root; a signed promotion record showing the same digest moved from staging to production; and any workflow run or command output holding that evidence. No digest, signer identity, token or workflow run has been invented for this note.

## Gap

Missing before G5:

1. A generated SBOM bound to the exact released artifact, supplied by release engineering with the signing authority. No SBOM file or build step exists today.
2. A cryptographic signature over that artifact with a named, trusted signer identity and a verification step, supplied by release engineering with the signing authority and reviewed by DevSecOps. The repository only declares these fields in a synthetic fixture.
3. Evidence that the same immutable digest is promoted from staging to production without rebuild or retag, supplied by the infrastructure owner with a cloud account. The build workflow publishes tags and a manifest but has no cross-environment promotion stage.
4. Pipeline records showing that promotion was actually gated on successful signature verification, supplied by DevSecOps with the CI owner. The synthetic checker validates declared metadata only and cannot observe a real gate.
5. A signed G5 acceptance record naming the approvers of the signed-artifact and SBOM evidence, supplied by the G5 acceptance owners under the WEB-11 evidence rule. No such record exists.

Files that would change when these are supplied: `.github/workflows/release-app-image.yml` (add SBOM generation, signing and an environment promotion stage), the `infra/delivery-safety` policy and fixtures (replace synthetic declarations with real provenance), `docs/operations/production-readiness/launch-checklist.md` rows 26-27 (close with attached evidence), `docs/operations/production-readiness/evidence.md` and `README.md` (update the WEB-11 rows), and the G5 evidence pack when assembled. This note lists those files and counts the work; it creates no task file and changes none of them.

Number of separate tasks needed: at least five — SBOM generation and artifact signing (NO TASK); a trusted signer identity and verification gate (NO TASK); immutable-digest cross-environment promotion with cloud-owner records (NO TASK); attaching real pipeline and gate evidence to the readiness trackers (NO TASK); and G5 acceptance of the signed-artifact evidence (owner: G5 acceptance owners; no task created by this note). Progressive rollout, expand/contract migrations and the rehearsed rollback with named approvers are other clauses of WEB-11 and belong to their own notes, not to this one.

## How to verify

Repository checks a reviewer can run now — these prove only the absence, the mechanisms and the declarations, never a signed artifact or a real promotion:

- `python3 -I scripts/check-docs.py` — no output, exit 0: this note's relative links, requirement IDs and structure are valid.
- `grep -rnEi 'SBOM|cosign|sigstore|attestation|provenance|promote|signed artifact' .github/workflows` — no matching lines, exit 1: the release workflow has no signing or SBOM step.
- `grep -n 'Build the image\|Push the per-architecture image\|Create the manifest' .github/workflows/release-app-image.yml` — only lines 36, 53 and 91: the workflow builds, pushes tags and assembles a manifest, nothing else.
- `bash infra/delivery-safety/check.sh` — ends `S-G5-07: OPS-06 evaluator unit tests and fixture checks passed`, exit 0. This is a *false green* for this clause: read it with its own boundary in the next commands.
- `grep -n 'synthetic-policy-declaration\|synthetic_only' infra/delivery-safety/delivery-safety-policy.json infra/delivery-safety/fixtures/synthetic_evidence.json` — shows `source: synthetic-policy-declaration` in the policy (line 4) and `synthetic_only: true` in both the policy (line 13) and the fixture (line 12).
- `sed -n '47,54p' docs/operations/delivery-safety/build-and-migration-gates.md` — the evaluator "cannot verify the cryptographic signature, the trust root behind `signer_identity`, or that promotion was truly blocked before verification".
- `grep -n 'checks declared metadata, not a signature' docs/operations/delivery-safety/README.md` — line 106 of the open-items table.
- `grep -n 'SBOM and one signed artifact promoted unchanged' docs/operations/production-readiness/launch-checklist.md` — line 26, status `open`, Link to Signed builds and migration checks.
- `grep -n 'needs infrastructure owner' docs/operations/production-readiness/evidence.md` — the WEB-11 rows including the signed-artifact closing task.
- `grep -n 'WEB-11' docs/operations/production-readiness/README.md` — line 19, owner `unassigned`, status `not started`.
- `grep -n 'NOT YET IN PLACE' docs/operations/runbooks/bad-deploy.md` — line 119.

Human evidence a site must produce before G5 — no command, synthetic fixture or workflow run can substitute for it:

- An **SBOM for the exact released artifact** and a **verified cryptographic signature with a named trusted signer**, supplied by release engineering with the signing authority and reviewed by DevSecOps.
- A **matching full sha256 digest and promotion record** showing that the same artifact, with no rebuild or retag, moved from staging to production, supplied by the infrastructure owner.
- **Pipeline records proving promotion was blocked until signature verification**, supplied by DevSecOps with the CI owner.
- A **signed G5 acceptance record naming the approvers** of the signed-artifact and SBOM evidence, supplied by the G5 acceptance owners.
