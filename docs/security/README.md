# Security documentation

Index of the security pages in this folder. Pages here describe controls that are present in the code and gaps that still need work; they are not production assurance or a substitute for site approval.

| Page | Purpose | Requirements |
|---|---|---|
| [Threat model](threat-model.md) | Diagram-free STRIDE tables for the staff web, patient app, API, database, background workers and partner exchange. Every cited control names a real file or test; every gap is a `GAP:` line with a suggested task title. | IAM-01, ARC-02, OPS-02, WEB-04 |

## Related pages outside this folder

These are indexes and evidence, not additional threat-model pages; they are listed here so a reader can find them from one place.

- [Tenant isolation foundation decision](../decisions/0001-g0-tenant-isolation.md)
- [Migration change-safety decision](../decisions/0002-ops06-migration-change-safety.md)
- [Persistence of in-memory stores decision](../decisions/0003-persistence-of-in-memory-stores.md)
- [Recovery runbook](../operations/recovery/runbook.md)
- [Production readiness launch checklist](../operations/production-readiness/launch-checklist.md)

## Conventions

- Paths in the threat model are repository-relative and written as code so a reader can open them from any viewer.
- A `GAP:` line is a proposal for the Product Owner. It is not accepted work and does not describe a control that exists.
- No real patient data, credential or domain name belongs in these pages. Use synthetic fixtures only.
- `docs/reference/index.md` is owned by a separate documentation task; this index does not edit it.
