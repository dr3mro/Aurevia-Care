# S-G5-08 — tenant lifecycle evidence (OPS-07)

The evaluator lives in `infra/s_g5_08/tenant_lifecycle_evidence.py`. It judges a
**synthetic evidence artifact**, not a running system: it proves that a claimed
export, retention, offboarding and disputed-access envelope is complete and
internally consistent, and that no clause of OPS-07 is contradicted. It cannot
prove that a real export, deletion, restore, review or revocation happened.

- Requirement: **OPS-07** (tenant export manifest, retention, legal hold,
  deletion, backup expiration, offboarding, representative scenarios).
- Supporting IDs: OPS-02 (audit), ARC-02 (tenant isolation), ARC-06 (documents),
  PAT-02 (patient downloads), IAM-05 (linkage dispute), ARC-05 (durable events).

## Entry point to test trace

```
infra/s_g5_08/check.sh
  -> python3 -I -m unittest discover -s infra/s_g5_08/tests -q
       -> infra/s_g5_08/tests/test_tenant_lifecycle_evidence.py
            -> evaluate(artifact) -> _check_export / _check_retention /
               _check_offboarding / _check_disputed_access /
               _check_scenarios / _check_phi -> named checks -> verdict
  -> python3 -I infra/s_g5_08/tenant_lifecycle_evidence.py <fixture.json>
       -> fixtures/synthetic_lifecycle_evidence.json   (must pass)
       -> fixtures/measured_lifecycle_evidence.json    (must pass, final)
       -> fixtures/violation_lifecycle_evidence.json   (must fail)
```

`infra/s_g5_08/tests/run_coverage.py` runs the same suite plus the CLI entry
point in one stdlib `trace` session for line-coverage evidence.

## Requirement-to-check map

| OPS-07 clause | Enforced by |
|---|---|
| manifest and schema version | `export.manifest.*`, `schema_version` |
| checksums | `export.record_checksums`, `export.documents` |
| authorized documents | `export.document_authorizations`, `.policy_version` |
| provenance | `export.record_provenance` |
| audit history | `export.audit_history` |
| retention respects legal holds | `retention.legal_hold_respected`, `.backup_hold_respected` |
| tracked deletion of active data | `retention.deletion_entries`, `.deletion_approval` |
| approved backup expiration | `retention.backup_expiration_entries` |
| representative scenarios tested | `scenarios`, `scenarios.no_duplicates` |
| tenant offboarding | `offboarding.*` |
| disputed access (supporting IAM-05) | `disputed_access.*` |
| policy-bounded, no PHI in fixtures | `phi.free`, `evidence_conflict`, `kind` |

Detail in [export manifest](export-manifest.md) and
[retention and offboarding](retention-and-offboarding.md).

## Honesty rule

| Class | Meaning | `final_evidence` |
|---|---|---|
| target | Requirement from the specification | n/a — not an artifact |
| `synthetic_rehearsal` | Exercises the check with synthetic fixtures | always `false` |
| `measured_operation` | Operation claimed against a non-synthetic source | `true` only if every check passes |

A `measured_operation` artifact that names a synthetic source is rejected by
`evidence_conflict`. A rehearsal can never be promoted by a self-declared flag.

## Limitations — human-gated

- Checksums, authorizations and policy versions are **format and metadata
  checks**, not verification of the underlying bytes or of a real disclosure
  decision. The evaluator does not read object storage or policy packs.
- Legal-hold authority, release-policy approval, dispute re-review authority and
  offboarding sign-off belong to qualified site, privacy and clinical owners.
- Real pilot acceptance, restoration proof and record-request handling stay in
  G5 site approval; no real patient data is used here.
- A malformed or missing ledger fails closed; an empty but well-formed ledger is
  accepted as "no events" only because the artifact explicitly declares it.

## Reserved migration

V50 is reserved for this story but unused: the scope is `infra/` and `docs/`
only, and no Flyway migration is introduced.
