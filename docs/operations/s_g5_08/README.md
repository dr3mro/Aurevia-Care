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
| policy-bounded, no email/SSN-shaped fixture strings | `phi.free`, `evidence.source`, `evidence_conflict`, `kind` |

Detail in [export manifest](export-manifest.md) and
[retention and offboarding](retention-and-offboarding.md).

## Honesty rule

| Class | Meaning | `final_evidence` |
|---|---|---|
| target | Requirement from the specification | n/a — not an artifact |
| `synthetic_rehearsal` | Exercises the check with synthetic fixtures | always `false` |
| `measured_operation` | Operation claimed against a non-synthetic source | `true` only if every check passes |

Every artifact must name a non-blank `source` (`evidence.source`), and a
`measured_operation` that names a synthetic source is rejected by
`evidence_conflict`. **The class is self-declared**: relabelling a rehearsal and
renaming its source would make it pass. `final_evidence: true` therefore means
only "a passing artifact that *claims* a measured run" — never verified
operational truth. Real acceptance stays with the named G5 owners.

## Limitations — human-gated

- Checksums, authorizations and policy versions are **format and metadata
  checks**, not verification of the underlying bytes or of a real disclosure
  decision. The evaluator does not read object storage or policy packs.
- Tenant isolation is checked at the **envelope** only; records do not carry a
  per-record tenant field, so a cross-tenant record with the same local id is
  not detectable here (database/API gates own that proof).
- `phi.free` detects only email- and SSN-shaped strings; it is not a general PHI
  or credential detector. The repository's secret scan and G5 record review are
  separate controls.
- Legal-hold authority, release-policy approval, dispute re-review authority and
  offboarding sign-off belong to qualified site, privacy and clinical owners.
- Real pilot acceptance, restoration proof and record-request handling stay in
  G5 site approval; no real patient data is used here.
- A malformed or missing ledger fails closed; an empty but well-formed ledger is
  accepted as "no events" only because the artifact explicitly declares it.

## Verification

| Check | Command | Result |
|---|---|---|
| Unit and fixture gate | `bash infra/s_g5_08/check.sh` | 92 synthetic unit tests pass; conforming and measured fixtures pass; violation fixture exits 1 with all eight named breaches |
| Unit tests alone | `python3 -I -m unittest discover -s infra/s_g5_08/tests -q` | 92 tests, OK |
| Line coverage | `python3 -m trace --count --coverdir /tmp/opencode/g5_08_cov2 infra/s_g5_08/tests/run_coverage.py` | No `>>>>>>` lines in `tenant_lifecycle_evidence.cover` (stdlib `trace`; `coverage.py` unavailable) |
| Shell gate guard | `python3 -I -m unittest discover -s infra/s_g5_08/tests -p test_check_gate.py -q` | 4 gate-alignment tests pass |
| Links and requirement IDs | `python3 -I scripts/check-docs.py` | exit 0 |
| Credential and PHI shapes | `python3 -I scripts/check-secrets.py` | exit 0 |

The violation control requires the **exact exit code 1** plus its named breach
list; an unreadable fixture (exit 2) or a wrong-reason failure cannot silently
pass it. The integration/database gates are run by the integrator, not here.

## Decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | Structure and metadata, not reality | The evaluator judges a declared artifact; real export, deletion, restore and re-review need named human G5 owners. |
| D2 | Holds cover bidirectionally and need an authorized lift | A narrow hold must block an org-wide backup expiry, and flipping `active` without a lift must not release evidence. |
| D3 | Audit `seq` strictly increases | Append-only tamper evidence; duplicate or out-of-order sequences are rejected. |
| D4 | Evidence class is declared, not proven | Documentation states plainly that `final_evidence` is a claim; sibling G5 evaluators use the same envelope convention. |
| D5 | Negative control pins exit code and breach names | An unreadable fixture (exit 2) or a stray failed check must not pass the negative control (S-G5-05 precedent). |

## Reserved migration

V50 is reserved for this story but unused: the scope is `infra/` and `docs/`
only, and no Flyway migration is introduced.
