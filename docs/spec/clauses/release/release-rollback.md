# WEB-11 clause 1 — Release and rollback (evidence note)

## Clause

Release and rollback.

## Status

Not built

The clause needs a cloud account, a signed decision and human evidence that the
repository does not hold, so none of it can be claimed as delivered. WEB-11 (section
8 of [PROJECT_V1_SPEC.md](../../../../.opencode/config/PROJECT_V1_SPEC.md)) requires one
signed artifact promoted with an SBOM, a progressive rollout with automatic
rollback on error-rate or latency breach, a rehearsed rollback and a release
checklist with named approvers. The
[delivery-safety handoff](../../../operations/delivery-safety/README.md) records
that real deployment evidence and per-site launch approval are open G5 gates;
both WEB-11 rows of the
[launch checklist](../../../operations/production-readiness/launch-checklist.md)
are `open`; and
[decision 0002](../../../../docs/decisions/0002-ops06-migration-change-safety.md)
records that no infrastructure as code or deployment workflow exists, deferring
environment separation, signed builds, rollout and production access to the
cloud/human-gated S-G5-07.

The repository does hold adjacent machinery, but none of it proves the clause:

- [release-app-image.yml](../../../../.github/workflows/release-app-image.yml) and
  [delivery.yml](../../../../.github/workflows/delivery.yml) build, smoke-test on
  native amd64 and arm64 and publish a **disposable test image** with a recorded
  digest and an immutable tag. They sign nothing, emit no SBOM, deploy to no
  environment and contain no rollout or rollback stage. A passing workflow file
  is not a workflow run, and no run output is captured here.
- [delivery_safety_evidence.py](../../../../infra/delivery-safety/delivery_safety_evidence.py)
  is a fail-closed OPS-06 evaluator over a declarative artifact: it judges the
  *shape* of signed-build, rollout, automatic-rollback and production-access
  claims, not reality. Its conforming fixture,
  [synthetic_evidence.json](../../../../infra/delivery-safety/fixtures/synthetic_evidence.json),
  is a `synthetic_rehearsal` whose signature, stages, health gates and
  `rollback_tested` values are declared literals, explicitly labelled
  `final_evidence: false`. The
  [rollout and production access contract](../../../operations/delivery-safety/rollout-and-production-access.md)
  states in "What this does not prove" that live signals, a rehearsed rollback
  and the approver's authority cannot be observed.
- [check.sh](../../../../infra/delivery-safety/check.sh) is a real gate that
  runs the evaluator's tests, asserts the conforming fixture passes and both
  negative fixtures exit exactly 1, and runs the authoritative migration checker
  for real. That proves the check works, not that a release or rollback occurred.
- [check-migrations.py](../../../../scripts/check-migrations.py) enforces
  expand/contract change safety offline, but that is clause 4's subject, not a
  release or rollback.

No signed artifact, SBOM, deployment or infrastructure as code, progressive
5%/25%/100% rollout with automatic rollback, rehearsed rollback record, release
checklist with named approvers or feature-flag configuration exists in the
repository, and no workflow run, signature, SBOM, rollout record or approval is
captured here. Treating any of the adjacent machinery as built evidence would
overclaim. The roles who must supply the missing evidence are named in the Gap
section below.

The absence claims were checked on this checkout, not assumed: `grep -rniE
'cosign|sigstore|sbom|syft|cyclonedx|attest|provenance' .github/workflows/` and
`grep -rniE 'progressive|rollout|rollback|error.?rate|latency' .github/workflows/`
returned no matches at all; `grep -rniE 'sign|signature' .github/workflows/`
matched only unrelated words such as "assigned" and "Assign workers" in the
swarm issue monitor, not a signing step; `find infra` for Terraform, ECS/EKS,
CloudFormation or deployment manifests found none; and a project-wide `find` for
feature-flag configuration excluding vendored `node_modules` found none. The
OPS-06 gate was executed, not merely read: `bash infra/delivery-safety/check.sh`
passed 71 tests and ended
with `S-G5-07: OPS-06 evaluator unit tests and fixture checks passed`. That
result proves the evaluator and fixture discipline work, not that a release or
rollback happened.

## Evidence

What the specification requires (authoritative text):

- WEB-11 in [PROJECT_V1_SPEC.md](../../../../.opencode/config/PROJECT_V1_SPEC.md) (section
  8) names "Release and rollback." and requires one signed artifact promoted
  with an SBOM, progressive rollout with automatic rollback on error-rate or
  latency breach, expand/contract migrations with default-off feature flags, a
  rehearsed rollback and a release checklist with named approvers. This note
  covers only the clause-1 sentence; clauses 2 to 5 cover the other sentences.
- Section 8 of the same specification says evidence for WEB-11 is produced with
  the infrastructure owner; sections 9 and 10 require per-site release-policy
  approval and a signed roll-forward/rollback criterion.
- [The G5 open-items table](../../../operations/delivery-safety/README.md)
  records that real four-account topology, clean scan evidence, a verified
  signature, a monitored rollout with rehearsed rollback, expiring production
  access and G5 acceptance are not produced in this repository.

What the repository actually holds today (all links resolve to files that exist):

- [release-app-image.yml](../../../../.github/workflows/release-app-image.yml) —
  build-once, native amd64/arm64 smoke, green-default-branch requirement for a
  tag, immutable tag check, multi-arch manifest and recorded digest. No signer,
  no SBOM, no deployment stage and no GitHub Release step.
- [delivery.yml](../../../../.github/workflows/delivery.yml) — policy, tooling,
  Java and image verification plus tagged release publication and a GitHub
  Release record. No rollout or rollback stage.
- [delivery_safety_evidence.py](../../../../infra/delivery-safety/delivery_safety_evidence.py) —
  fail-closed OPS-06 evaluator: exact env allowlist, distinct accounts and
  resources, literal-true booleans, a `sha256:` digest pattern, allowed signing
  methods, distinct rollout stages ending in `full`, monitored health gates, a
  declared `rollback_tested` control and separated, audited, expiring production
  access. A `measured_deployment` needs six provenance fields before it can be
  labelled final; shape and presence only, never truth.
- [synthetic_evidence.json](../../../../infra/delivery-safety/fixtures/synthetic_evidence.json) —
  the conforming rehearsal with placeholder identifiers and
  `kind: synthetic_rehearsal`; `final_evidence: false`.
- [check.sh](../../../../infra/delivery-safety/check.sh) — runs the evaluator's
  unit tests, asserts the conforming fixture passes and both negative fixtures
  exit exactly 1, then runs `python3 -I scripts/check-migrations.py
  --require-baseline` for real.
- [README.md](../../../operations/delivery-safety/README.md) — three evidence
  tiers (contract, synthetic rehearsal, measured deployment), decisions D1–D12,
  the explicit `final_evidence: false` rule and the named-owner open-items table.
- [rollout-and-production-access.md](../../../operations/delivery-safety/rollout-and-production-access.md) —
  the declared rollout and production-access field contract and a "What this
  does not prove" section.
- [0002-ops06-migration-change-safety.md](../../../../docs/decisions/0002-ops06-migration-change-safety.md) —
  records that environment-account separation, signed builds, monitored
  progressive rollout and audited production access were deferred to the
  cloud/human-gated S-G5-07, and that the offline migration gate is the part
  delivered.
- [check-migrations.py](../../../../scripts/check-migrations.py) — the real,
  enforced expand/contract and forward-fix change-safety gate.
- [launch-checklist.md](../../../operations/production-readiness/launch-checklist.md) —
  the two WEB-11 rows (SBOM plus one signed artifact; progressive rollout with
  automatic rollback and a named-approver checklist) both have status `open`.

## Gap

The workflows and evaluator cited under Evidence are shared CI and OPS-06
machinery: they build and publish a disposable image and check the *shape* of a
declaration, so they cover only the publication and expand/contract edges of
WEB-11. They are not WEB-11 implementation or G5 final evidence, and closing the
items below needs the named owners and tasks rather than another copy of the
evaluator.

Missing, with the files that would have to change and the owning tasks:

1. **Signed build and SBOM.** No workflow signs the image or emits a bill of
   materials, and no promotion binds one digest from staging to production
   without a rebuild. Would change
   `.github/workflows/release-app-image.yml` and `.github/workflows/delivery.yml`.
   Covered by clause 2 (SPEC-WEB-11-C02, taken).
2. **Real deployment, separated environments and progressive rollout.** No
   infrastructure as code, cloud account topology or deployment workflow exists;
   5%/25%/100% stages, health gates and automatic rollback on error-rate or
   latency breach exist only as declared literals in the evaluator contract.
   Would change new `infra/` deployment code and `infra/DEPLOYMENT.md`. Covered
   by clause 3 (SPEC-WEB-11-C03, taken); the cloud/IaC work has **NO TASK**.
3. **Rollback rehearsal and release checklist.** No rehearsal record and no
   checklist with named approvers exist; the
   [launch checklist](../../../operations/production-readiness/launch-checklist.md)
   rows are `open` and the approval record in
   [site-launch-approvals.md](../../../launch/site-launch-approvals.md) is blank.
   Would change those two documents and a new release checklist. Covered by
   clause 5 (SPEC-WEB-11-C05, open) and by G5 human approval.
4. **Expand/contract migrations and default-off feature flags.** The migration
   half is gated by `check-migrations.py`; no feature-flag configuration exists.
   Covered by clause 4 (SPEC-WEB-11-C04, open).
5. **Human and cloud evidence.** No four-account topology with verified
   isolation, no pipeline artifact proving clean dependency and secret scans,
   no verified signing identity, no real rollout or rollback drill, no approved
   audited expiring production-access records and no G5 acceptance. Would change
   the [delivery-safety open-items table](../../../operations/delivery-safety/README.md)
   and the site evidence pack; **NO TASK** exists for several of these items.

Number of separate tasks: at least **five** beyond this note — clauses 2, 3, 4
and 5, plus a cloud/IaC and named-approver task for which no task exists in
`tasks/` today. Clause 2 and 3 are taken, clause 4 and 5 are open.

Human owners from the [delivery-safety open-items table](../../../operations/delivery-safety/README.md):

- Real four-account cloud topology with verified isolation — **Cloud
  infrastructure owner with DevSecOps**.
- Pipeline evidence that dependency and secret scans ran clean — **DevSecOps
  with the CI owner**.
- Cryptographically verified signed build and trusted identity — **Release
  engineering with the signing authority**.
- Monitored progressive rollout with a rehearsed rollback — **Operations with
  the system designer**.
- Approved, audited, expiring production access records — **Named approver and
  privacy reviewer**.
- Human G5 acceptance of the evidence — **G5 acceptance owners**.

The [launch checklist](../../../operations/production-readiness/launch-checklist.md)
additionally names **Engineering** for the SBOM/signed-artifact row and
**Infrastructure** for the progressive-rollout row.

## How to verify

Repository checks a reviewer can run now (they prove the machinery and the
honesty boundary, not that a release or rollback occurred):

- `python3 -I scripts/check-docs.py` — this note's relative links, requirement
  IDs and Markdown structure are valid.
- `bash infra/delivery-safety/check.sh` — the evaluator's unit tests pass, the
  conforming synthetic fixture passes, both negative fixtures exit exactly 1 and
  the real migration gate is green.
- `python3 -I infra/delivery-safety/delivery_safety_evidence.py infra/delivery-safety/fixtures/synthetic_evidence.json`
  — the report prints `evidence class: rehearsal (final: False)`.
- `grep -nE "cosign|sigstore|sbom|syft|cyclonedx|progressive|rollout|rollback" .github/workflows/release-app-image.yml .github/workflows/delivery.yml`
  — shows no signing, SBOM, rollout or rollback step in the release workflows.
- The repo-wide absence checks are repeatable: `grep -rniE
  'cosign|sigstore|sbom|syft|cyclonedx|attest|provenance' .github/workflows/` and
  `grep -rniE 'progressive|rollout|rollback|error.?rate|latency' .github/workflows/`
  return nothing; `grep -rniE 'sign|signature' .github/workflows/` matches only
  unrelated "assigned" words; a project-wide feature-flag `find` excluding
  vendored `node_modules` returns nothing.

Human evidence a site must produce before G5 (no command or synthetic fixture
can substitute for it):

- A **signed build and SBOM** with a verified signature and a matching digest
  for the single artifact promoted from staging to production without rebuild,
  supplied by Release engineering with the signing authority and the
  Infrastructure owner.
- A **monitored progressive rollout record** showing the specification's exact
  stages "5%, 25%, 100%", with health gates and automatic rollback when "error
  rate or latency" breaches its threshold, supplied by Operations with the
  system designer.
- The six **measured-deployment provenance fields** the evaluator requires before
  it can label an artifact final — tool, tool version, command, UTC time,
  environment and source SHA. A bare declaration, or a rehearsal relabelled as a
  measured deployment with only `kind` and `source` changed, must not pass G5.
- A **rollback rehearsal record** executed before pilot, with date, named roles
  and outcome.
- A **release checklist with named approvers** and a signed roll-forward or
  rollback criterion in the site acceptance matrix, supplied by the named
  approver, the privacy reviewer and the G5 acceptance owners.
- Verified four-account cloud isolation, clean scan pipeline evidence and
  approved, audited, expiring production-access records for the open items in
  the [delivery-safety handoff](../../../operations/delivery-safety/README.md).
