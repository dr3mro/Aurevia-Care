# OPS-08 versioned manifest and deterministic mapping contract

The [evaluator](../../../infra/s_g5_09/onboarding.py) is the authority for this
contract. This document explains the fields it checks and why. The
[conforming fixture](../../../infra/s_g5_09/fixtures/synthetic_onboarding_manifest.json)
is a synthetic, runnable example.

## Manifest envelope

A manifest is a JSON object. The envelope fixes which contract and which version
of that contract are being used:

| Field | Rule | Why |
|---|---|---|
| `schema_version` | equals `1.0` | An unknown shape must not be interpreted with old rules |
| `requirement` | equals `OPS-08` | A manifest for another contract is not an onboarding manifest |
| `manifest_version` | positive integer | OPS-08 requires a versioned manifest; the sign-off must name this version |
| `source` | non-blank string | Import provenance needs a named source system or dataset |
| `synthetic_only` | `true` | This repository and the rehearsal use synthetic fixtures only |

The envelope is checked before any content is read. A non-object manifest fails
closed; it is never treated as an empty but valid import.

## Dataset and record shape

`dataset.records` is a non-empty list. Each record must have:

- a non-blank `source_id` that is unique within the dataset;
- a `fields` object of source-field names to values;
- an `attachments` list;
- `historical_signature` evidence (see
  [sign-off and provenance](sign-off-and-provenance.md)).

A record that fails this shape is excluded from the deterministic dry run and
its dataset check fails. The dry run still reports it as an error rather than
silently ignoring it. The `dataset.source_ids` check enforces that every
`source_id` is a non-blank string unique within the dataset: a duplicate or
malformed id fails closed, because controlled batches refer to records by this
id and a repeated id would let the coverage fold silently drop a record.
Separately, every record must carry a non-blank value for each required target
field (the source name is derived from the mapping); a blank MRN is a dry-run
error even when the field is present in the mapping. Duplicate target values in
`source_to_target` are rejected because the reverse map used for identity would
otherwise depend on JSON key order.

## Deterministic mapping

`mapping` declares how source fields become target fields:

| Field | Rule | Why |
|---|---|---|
| `deterministic` | `true` | OPS-08 requires deterministic mapping; the same source must map the same way on retry |
| `strategy` | `canonical_sha256` | The only accepted strategy: canonical JSON bytes (sorted keys, compact separators) hashed with SHA-256 |
| `source_to_target` | object of string target names, containing `mrn`, `mrnIssuer`, `givenName`, `familyName`, `birthDate` exactly once | Every required target must be produced once; a missing or duplicated target cannot be reconciled deterministically |
| `identity_fields` | non-empty list of mapped target names that includes `mrn` and `mrnIssuer` | Duplicate risk is evaluated on the issuer-scoped MRN (IAM-03); a narrower identity would hide duplicates |
| `ignored_fields` | list (may be empty) | Source fields the operator explicitly chose to drop; anything else is unsupported |

The reverse of `source_to_target` is used to read identity values from each
record. A mapping that is non-deterministic, uses an unknown strategy, omits a
required target or names an unmapped identity field is rejected before the dry
run. A mapping whose `ignored_fields` is not a list is also rejected; the dry run
then treats every extra field as unsupported.

## Why canonical hashing matters

Record identity keys and batch digests use the same encoding: JSON with sorted
keys, no insignificant whitespace and no ASCII escaping, encoded as UTF-8 and
hashed with SHA-256. Two manifests that differ only in key order or whitespace
produce the same digest, so a validation replay is stable. A manifest whose
bytes change in a meaningful way produces a different digest, so a tampered
batch cannot reconcile. The evaluator recomputes digests from the parsed JSON;
it does not trust a declared digest on its own.

## Fail-closed behavior

Every absent or malformed section produces a named failed check rather than an
exception. The report lists all breaches together so an operator can correct
the manifest in one pass. No partial manifest is ever promoted to a commit plan:
`commit_allowed` is true only when every check passes, including sign-off.
