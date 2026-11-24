# OPS-08 onboarding dataset import handoff

- Requirement: OPS-08
- Status: the versioned manifest contract, deterministic mapping rules, dry-run
  findings, controlled-batch reconciliation and authorized sign-off gate are
  delivered as a fail-closed evaluator. A real approved onboarding import is
  **not** performed here and remains a G5, human-gated step.
- Audience: engineering, operations, privacy and G5 acceptance reviewers.

This directory is the engineering handoff for OPS-08. It states what the
manifest must contain, how the dry run and batches are judged, what sign-off is
required before the source of truth changes, and — deliberately — what this
repository cannot prove.

## Requirement mapping

| OPS-08 clause | Enforced by |
|---|---|
| Import approved onboarding datasets through a versioned manifest | [Manifest and mapping contract](onboarding-manifest.md) — `manifest_version`, `schema_version`, `source`, `synthetic_only` |
| Deterministic mapping | [Manifest and mapping contract](onboarding-manifest.md) — declared `canonical_sha256` strategy, required target fields, identity fields |
| Dry-run counts, unsupported fields, duplicate risk, missing attachments, errors | [Dry run and controlled batches](dry-run-and-batches.md) — the five `dry_run` findings and their checks |
| Preserve import provenance and historical signature evidence without creating a new attestation | [Sign-off, provenance and commit gate](sign-off-and-provenance.md) — provenance section, per-record signature evidence, `creates_new_attestation: false` |
| Commit in controlled batches; reconcile counts and hashes | [Dry run and controlled batches](dry-run-and-batches.md) — bounded, ordered, non-overlapping batches with recomputed digests |
| Authorized sign-off before changing the source of truth | [Sign-off, provenance and commit gate](sign-off-and-provenance.md) — named approval bound to a content digest, gating `commit_allowed` |

The approved clause itself is in the [product specification](../../../.opencode/config/PROJECT_V1_SPEC.md).
No new threshold or requirement ID is introduced by this handoff.

## Three evidence tiers

| Tier | What it is | What it proves | What it does not prove |
|---|---|---|---|
| Contract | The OPS-08 clauses encoded in the [evaluator](../../../infra/onboarding/onboarding.py) | Fidelity of the encoding and fail-closed validation | That any dataset was imported |
| Synthetic rehearsal | The seeded manifests run by [`check.sh`](../../../infra/onboarding/check.sh) | The evaluator accepts a conforming manifest and rejects breaches of several clauses at once | That an import or approval really happened; no data was written |
| Approved import | A manifest, dry run and named sign-off produced by authorized operators outside this repository | A real import followed the declared gate | Clinical or operational acceptance; that remains a G5 human gate |

A passing synthetic manifest is a rehearsal, not an import record. The evaluator
never writes to a source of truth and never creates an attestation.

## Document set

| Document | Covers |
|---|---|
| [Manifest and mapping contract](onboarding-manifest.md) | The versioned envelope, dataset shape, deterministic mapping and fail-closed rules |
| [Dry run and controlled batches](dry-run-and-batches.md) | The five dry-run findings, the batch envelope and count/hash reconciliation |
| [Sign-off, provenance and commit gate](sign-off-and-provenance.md) | Provenance preservation, no new attestation, authorized sign-off and the commit gate |

Executable material lives in `infra/onboarding`: the
[evaluator](../../../infra/onboarding/onboarding.py), the
[conforming](../../../infra/onboarding/fixtures/synthetic_onboarding_manifest.json)
and [violation](../../../infra/onboarding/fixtures/violation_onboarding_manifest.json)
fixtures, and the unit tests.

## Verification

| Check | Command | Proves |
|---|---|---|
| Unit and fixture gate | `bash infra/onboarding/check.sh` | 68 synthetic unit tests pass; the conforming fixture passes and the violation fixture fails |
| Unit tests alone | `python3 -I -m unittest discover -s infra/onboarding/tests -q` | The evaluator's enumerated branches behave as specified |
| Line coverage | `python3 -m trace --count --coverdir /tmp/opencode/onboarding_cov infra/onboarding/tests/run_coverage.py` | Every executable line of the evaluator runs; stdlib `trace` is used because `coverage.py` is not installed |
| Links and requirement IDs | `python3 -I scripts/check-docs.py` | Relative links resolve and every requirement ID is defined in the specification |
| Credential and PHI shapes | `python3 -I scripts/check-secrets.py` | No credential or patient-identifier pattern was introduced |

## Decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | The evaluator judges a manifest artifact, not a running importer | A script cannot prove an import, a scan or a human approval happened; claiming otherwise would fabricate evidence |
| D2 | Mapping must declare `canonical_sha256` and `deterministic: true` | OPS-08 requires deterministic mapping; an undeclared or non-deterministic strategy could map the same source differently on retry |
| D3 | Identity duplicates and missing attachments fail the dry run | A clean dry run is the precondition for a controlled import; reporting a risk without gating on it would let it through |
| D4 | Ignored source fields must be declared explicitly; every record must carry all required field values | An unlisted field is unsupported and a blank required field would quietly evade duplicate detection |
| D5 | Batches must be ordered, unique, bounded and hash-reconciled | OPS-08 requires controlled batches and reconciled counts and hashes; an overlapping or tampered batch would import twice or lose records |
| D6 | Sign-off must name an approver in an authorized role, cover this manifest version and every batch, and bind to a content digest | Approval of different content — even with recomputed batch hashes — must not unlock the import |
| D7 | Historical signature evidence must be carried per record and no new attestation is created | OPS-08 preserves import provenance and historical signatures without minting a new attestation |
| D8 | Reserved migration version V51 is not used | No schema change is needed; version gaps are allowed by the repository's change-safety gate |
| D9 | Duplicate identity must use the issuer-scoped MRN; mapping targets must be unique strings | IAM-03 makes MRN uniqueness issuer scoped; ambiguous reverse mapping would make duplicate keys depend on JSON key order |

## Boundaries

- Synthetic data only. No fixture contains a real person, identifier or credential.
- No clinical or privacy approval is implied. Approval of the onboarding
  dataset, mapping and sign-off belongs to the accountable owners in the
  specification's decision table.
- No application code, schema, API contract, CI or tracker file belongs in this
  story's scope. The two scope directories are the only files changed.

## Open items

| Item | Why it is open | Who can close it |
|---|---|---|
| Real approved onboarding dataset and versioned manifest | No source dataset or deployment exists in this repository | Operations with the privacy lead, in an authorized environment |
| Authorized sign-off event with a named human approver | A script cannot attest that a person approved the import | The accountable approver in the specification's decision table |
| Verification that imported records preserved source hashes in the real importer | The evaluator checks the declared manifest, not the destination system | Engineering and operations, with evidence from the import run |
| Human acceptance of the import evidence | A passing rehearsal is not pilot acceptance | G5 acceptance owners |

Evidence source for the delivered checks: executed tool commands in this
repository and a simulated specialist review. There is no human, clinical or
real-import evidence in this story.
