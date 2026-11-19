# Maintainer guide

| Change | Start here | Verify through |
|---|---|---|
| API endpoint or worker | apps/api and contracts | Public command, HTTP contract, database integration |
| Schema or tenant policy | db/migrations and the affected service | Migration on realistic synthetic data, constraints, tenant isolation and concurrency |
| Staff workflow | apps/staff-web and generated API client | View behavior, authorization outcomes, keyboard and accessibility |
| Patient workflow | apps/patient-mobile | iOS and Android behavior, stale states, interruptions and retries |
| Deployment | infra and the release workflow | Image smoke, artifact digest, readiness and rollback evidence |

| Agent roles or permissions | .opencode/agents, scripts/gen-opencode.py | scripts/check-pack.py and unit tests in scripts/tests |

## Follow a feature

Start with its story and requirement ID in TODO.md. Find the public entry point. Trace validation and authorization into the domain operation, persistence and any outbox event. Follow the test around that entry point before editing. Update the contract and tests alongside implementation. New modules add a short map here or link to a focused module guide.

Document transaction boundaries, tenant scope, event ownership, retries and expected errors at the symbol or module that owns them. Describe how to add an endpoint, schema change or workflow when the first real example exists. Use synthetic examples and links to source rather than copying implementation into docs.

Build the reference with scripts/docs.sh. A module's TypeScript documentation hook generates HTML using TypeDoc and must fail on documentation errors. Java builds use Javadoc-style comments consumed by Doxygen.

## G0 facility slice trace

`POST /api/v1/facilities` → `FacilityController.create` → `TenantContextInterceptor` (subject + explicit active organization) → `FacilityService.create` → `TransactionTemplate` bound by `TenantGuardTransactionManager.doBegin` (`set_config(..., true)`) → `FacilityRepository.insert` (forced RLS + `WITH CHECK`) → `appendAudit` (append-only hash chain) → 201. `GET /api/v1/facilities/{code}` follows the same guarded path and returns 404 when the row is outside the active tenant. `FacilityIsolationTest` exercises all of it against a real PostgreSQL with two synthetic tenants; decision record: `docs/decisions/0001-g0-tenant-isolation.md`.
