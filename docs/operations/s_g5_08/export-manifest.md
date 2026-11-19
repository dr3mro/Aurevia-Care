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
  (`export.documents`);
- a non-empty `audit` history of entries with a positive integer `seq`, an
  `action` and an `outcome` (`export.audit_history`).

A document's `authorization` must be one of `release_policy`,
`record_access_request` or `approved_instruction` ("as allowed by policy"):
`export.document_authorizations`. Any other value, including a blank one, fails.

## Isolation and honesty

The manifest organization must equal the artifact organization, and the
offboarding organization must match too, so two tenants sharing local record
identifiers cannot be merged in one artifact (supporting ARC-02). A `measured`
or executed export may not name a synthetic source. Every artifact is scanned
for email- and SSN-shaped strings; a hit fails `phi.free`.

## What this check cannot prove

The evaluator sees only declared checksums and policy labels. It does not fetch
the object, recompute the digest against stored bytes, confirm the policy
approval signature, or verify that a withdrawn release was excluded. Those stay
in the human-gated G5 record-request and release reviews (PAT-02, OPS-02).
