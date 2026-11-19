# OPS-08 provenance, no new attestation and authorized sign-off

## Preserved provenance

The `provenance` section records where the dataset came from:

| Field | Rule |
|---|---|
| `source_system` | non-blank name of the system or dataset |
| `source_version` | non-blank version of that source |
| `preserve_historical_signatures` | `true` |
| `creates_new_attestation` | `false` |

Every record must also carry `historical_signature` evidence: a non-blank
`issuer`, a non-blank `signed_at` time and a 64-hex-digit lowercase SHA-256
`checksum`. The evaluator checks the evidence on each record, not only the
declaration, so a manifest cannot claim preservation while dropping a record's
original signature.

OPS-08 requires import provenance and historical signature evidence to be
preserved **without creating a new attestation**. The evaluator therefore
requires `creates_new_attestation: false` and reports
`new_attestations_created: 0` in every verdict. It has no operation that mints a
signature; an import that did so would be a different, unapproved act of
clinical attestation and is rejected by the manifest rule.

## Authorized sign-off

Changing the source of truth requires a separate, named approval:

| Field | Rule | Why |
|---|---|---|
| `approver` | non-blank string | Approval must be attributable to a person or named role holder |
| `role` | one of `tenant_admin`, `privacy_auditor`, `clinical_safety_lead` | Only authorized roles may approve an onboarding import |
| `decision` | `approved` | A pending or rejected decision does not authorize anything |
| `content_sha256` | equals the digest of the manifest version and the ordered batch digests | Recomputing batch hashes after changing a record must not carry the old approval forward |
| `manifest_version` | equals the manifest's `manifest_version` | Approval of another version must not unlock this one |
| `batches` | list of integers equal to the set of all batch sequences | Approval of a subset must not unlock the whole plan |
| `approved_at` | non-blank timestamp | The approval needs a time |

The content digest is derived from values that the importer recomputes from the
dataset, so changing a field inside an approved record breaks
`sign_off.content` even when the batch digests are recalculated. A sign-off for
different content, a different version, only some batches, without a named
approver or with an unauthorized role fails `sign_off.content`, `sign_off.scope`
or the related named check. Withholding approval leaves `commit_allowed` false and
`source_of_truth_action` set to `unchanged`.

## The commit gate

`evaluate` returns a verdict with these gate fields:

| Field | Meaning |
|---|---|
| `verdict` | `pass` only when every named check passes |
| `failed` | the names of every failed check, so breaches can be fixed in one pass |
| `commit_allowed` | true only for a clean manifest with authorized sign-off |
| `source_of_truth_action` | `commit_in_batches` when allowed, otherwise `unchanged` |
| `new_attestations_created` | `0` when the manifest forbids a new attestation; `null` when provenance is non-conforming |
| `dry_run` | the five findings and their counts |
| `batch_plan` | the ordered, reconciled batch commit units |

A real importer must treat `commit_allowed` as a mandatory precondition and
apply only the returned `batch_plan`, in sequence, verifying each digest. The
evaluator cannot prove a human signed off or that the destination preserved the
source hashes; those are G5 evidence collected from the authorized import run.
