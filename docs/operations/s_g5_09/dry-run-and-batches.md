# OPS-08 dry run and controlled batches

Before any source of truth changes, a manifest must produce a clean dry run and
a batch plan whose counts and hashes reconcile. Both are computed by the
[evaluator](../../../infra/s_g5_09/onboarding.py) from the declared records; no
write occurs.

## The five dry-run findings

OPS-08 names five things a dry run must surface. The evaluator reports each as a
named check and a value in `dry_run`:

| Finding | `dry_run` key | Fail condition |
|---|---|---|
| Counts | `counts` | No records, or the number of records and batches does not match the declared plan |
| Unsupported fields | `unsupported_fields` | A source field is neither mapped nor declared in `ignored_fields` |
| Duplicate risk | `duplicate_risk` | Two or more records share the same deterministic identity key (mapped identity fields) |
| Missing attachments | `missing_attachments` | An attachment declares `present: false` |
| Errors | `errors` | A record has an invalid source id, a non-object `fields`, a missing or blank required field, a non-list `attachments`, a malformed attachment or an unusable attachment digest |

`counts` includes `total_records`, `batch_count` and the number of findings in
each other category. The fixture rehearsal is clean: three records, two batches
and zero findings. The violation fixture deliberately carries several breaches
at once and is rejected.

Unsupported field names and duplicate labels are sorted and deduplicated, so the
report is deterministic and reviewable. Duplicate risk reports every member of a
duplicate identity group, matching the same-tenant review expectation that both
sides of a suspected duplicate be visible.

## Attachment checks

An attachment is acceptable only when it has a non-blank `key`, a 64-hex-digit
lowercase SHA-256 `sha256`, a `type` of `pdf`, `jpeg` or `png`, and a boolean
`present`. A clean verdict is represented by `present: true`; a missing or
quarantined attachment is `present: false` and becomes a dry-run finding that
fails the check. The evaluator does not read file bytes — it validates the
declared evidence and the authorization gate around it.

## Controlled batches

`batches` is a non-empty ordered list. Each batch declares `sequence`,
`record_ids`, `expected_count` and `sha256`. The envelope rules are:

- sequences are contiguous from 1;
- `record_ids` are non-blank strings, sorted and unique within the batch;
- across all batches, `record_ids` cover every source id exactly once;
- `expected_count` equals the length of `record_ids`;
- `0 < expected_count <= 100` (the controlled limit in the evaluator);
- `sha256` equals the digest of the canonical bytes of the batch's records in
  declared id order. Batch digests cover the full record, not only identity
  fields, so any changed value changes the digest. The ordered batch digests
  and the manifest version feed the sign-off content binding described in
  [sign-off and provenance](sign-off-and-provenance.md).

A sequence gap, an unordered or repeated id, an uncovered or unknown record, an
oversized batch, a count mismatch or a hash mismatch fails the respective named
check. When `record_ids` are unusable, hash reconciliation is skipped and the
hash check fails closed rather than comparing against an empty batch.

## Reconciliation and the commit plan

When a batch plan is otherwise valid, the evaluator returns an ordered
`batch_plan` with each batch's sequence, count and declared digest. The sum of
the plan counts equals the dry-run `total_records`. This is the unit of a
controlled commit: batches are applied in sequence, each one verified against
its own reconciled digest, and no batch is applied if any check failed.

The evaluator does not apply the plan itself. `commit_allowed` is the gate that
an authorized importer must respect; it is false whenever any dry-run, mapping,
provenance, batch or sign-off check fails.
