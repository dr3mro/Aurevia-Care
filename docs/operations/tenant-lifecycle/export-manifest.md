# Tenant export manifest (OPS-07)

An export artifact describes what was exported for one tenant under an approved
policy. The evaluator requires a manifest and a non-empty record list whose
declared count matches the list exactly.

## Manifest envelope

| Field | Rule | Check |
|---|---|---|
| `schema_version` | non-blank string naming the export schema | `export.manifest.schema_version` |
| `policy_version` | non-blank string naming the authorizing policy | `export.manifest.policy_version` |
| `organization` | non-blank and equal to the artifact's active organization | `export.manifest.organization`, `.organization_scope` |
| `exported_at` | non-blank string (UTC instant) | `export.manifest.exported_at` |
| `record_count` | positive integer equal to `len(records)` | `export.manifest.record_count`, `export.record_count` |

The artifact-level `schema_version` is separately pinned to `1.0`; it is the
evidence-envelope version, not the chart schema version in the manifest.

## Records

Every record must carry:

- a unique, non-blank `id` (`export.record_ids`, `.record_ids_unique`);
- a lowercase 64-hex **sha256 checksum** (`export.record_checksums`);
- **provenance**: `source_system`, `source_id` and a positive integer `version`
  (`export.record_provenance`);
- a non-empty `documents` list, each with a non-blank id, a lowercase sha256
  checksum and a `policy_version` equal to the manifest's
  (`export.documents`). Document ids must be unique within the record: a
  duplicate id with different bytes leaves the manifest ambiguous about which
  authorized copy was exported and fails `export.documents`;
- a non-empty `audit` history of entries with a positive integer `seq`, an
  `action` and an `outcome`, whose `seq` values are strictly increasing and
  unique (`export.audit_history`). A duplicate or out-of-order sequence is
  rejected because it defeats the append-only tamper evidence. All identifiers
  are canonical: surrounding whitespace is rejected.

A document's `authorization` must be one of `release_policy`,
`record_access_request` or `approved_instruction`:
`export.document_authorizations`. Any other value, including a blank one, fails.
This tuple is a fixed, versioned-in-code stand-in for a market policy pack, not
a live policy binding; confirming a document was actually allowed by the
approved policy is a named human G5 review item.

## Isolation and honesty

The manifest organization must equal the artifact organization, and the
offboarding organization must match too, so a single artifact cannot claim two
active tenants at its envelope (supporting ARC-02). **This is an envelope check
only**: records carry no per-record tenant field, so a record from another
tenant with an identical local id cannot be detected here. Cross-tenant record
isolation is proved by the database and API gates, not by this evaluator.

Every artifact must name a non-blank `source` (`evidence.source`); a
`measured_operation` may not name a synthetic source (`evidence_conflict`).
Both fields are self-declared and are a best-effort heuristics check, not proof
that the run occurred. Every artifact is scanned for email- and SSN-shaped
strings; a hit fails `phi.free`. The scan covers those two shapes only — names,
phones, MRNs, addresses and credentials are out of scope for this gate and are
handled by the repository's separate secret scanner and G5 record review.

## What this check cannot prove

The evaluator sees only declared checksums and policy labels. It does not fetch
the object, recompute the digest against stored bytes, confirm the policy
approval signature, or verify that a withdrawn release was excluded. Those stay
in the human-gated G5 record-request and release reviews (PAT-02, OPS-02).
