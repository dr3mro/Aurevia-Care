# Maintainer guide

| Change | Start here | Verify through |
|---|---|---|
| API endpoint or worker | apps/api and contracts | Public command, HTTP contract, database integration |
| Schema or tenant policy | db/migrations and the affected service | Migration on realistic synthetic data, constraints, tenant isolation and concurrency |
| Migration change-safety contract | db/migrations and `scripts/check-migrations.py` | Offline version/expand-contract gate plus the runtime `db/check.sh` apply-and-assert gate |
| Staff workflow | apps/staff-web and generated API client | View behavior, authorization outcomes, keyboard and accessibility |
| Patient workflow | apps/patient-mobile | iOS and Android behavior, stale states, interruptions and retries |
| Deployment | infra and the release workflow | Image smoke, artifact digest, readiness and rollback evidence |

| Agent roles or permissions | .opencode/agents, scripts/gen-opencode.py | scripts/check-pack.py and unit tests in scripts/tests |

## Follow a feature

Start with its story and requirement ID in tasks/. Find the public entry point. Trace validation and authorization into the domain operation, persistence and any outbox event. Follow the test around that entry point before editing. Update the contract and tests alongside implementation. New modules add a short map here or link to a focused module guide.

Document transaction boundaries, tenant scope, event ownership, retries and expected errors at the symbol or module that owns them. Describe how to add an endpoint, schema change or workflow when the first real example exists. Use synthetic examples and links to source rather than copying implementation into docs.

Build the reference with scripts/docs.sh. A module's TypeScript documentation hook generates HTML using TypeDoc and must fail on documentation errors. Java builds use Javadoc-style comments consumed by Doxygen.

The module guides keep source links relative to the checkout so the Markdown checker can resolve them. Doxygen copies those links verbatim into flat pages at the site root, where they would resolve outside the published site. After generation, `scripts/link_developer_reference.py` rewrites root-level HTML links to GitHub `blob` URLs for files and `tree` URLs for directories, pinned to the commit that built the reference; it leaves Doxygen's own links and the TypeDoc sub-sites untouched. The builder takes the repository and commit from `GITHUB_REPOSITORY`/`GITHUB_SHA` in CI, or from the origin remote and `HEAD` locally, and fails closed when neither yields a valid identity. Run its `--check` mode to find any source link that was not rewritten. Pull-request builds pin to the transient merge commit, so only links in the main-push Pages deployment are permanent.

## G0 facility slice trace

`POST /api/v1/facilities` → `FacilityController.create` → `TenantContextInterceptor` (subject + explicit active organization) → `FacilityService.create` → `TransactionTemplate` bound by `TenantGuardTransactionManager.doBegin` (`set_config(..., true)`) → `FacilityRepository.insert` (forced RLS + `WITH CHECK`) → `appendAudit` (append-only hash chain) → 201. `GET /api/v1/facilities/{code}` follows the same guarded path and returns 404 when the row is outside the active tenant. `FacilityIsolationTest` exercises all of it against a real PostgreSQL with two synthetic tenants; decision record: `docs/decisions/0001-g0-tenant-isolation.md`.

## G0 staff lifecycle trace

`POST /api/v1/staff` → `StaffController.invite` → `TenantContextInterceptor` → `StaffService.invite` → guarded `TransactionTemplate` → `StaffRepository.insertIdentity` / `insertMembership(active)` / `insertRoleGrant` → `appendAudit(STAFF_INVITE, SUCCESS)` → 201. V4 makes those three tables append-only for every role and forces the grantor to come from `app.actor`; the role grant records `granted_by` and effective dates. `POST /api/v1/staff/{subject}/deactivate` appends a `revoked` membership event — it never updates a row — and the V4 `current_membership()` fold makes the next protected `FacilityService` or `StaffService` request fail closed with 403. `GET /api/v1/staff/{subject}` and `/history` read the same fold and append-only tables; outside the active tenant the subject is concealed with 404. `StaffLifecycleIsolationTest` exercises every route against real PostgreSQL with two synthetic tenants; `db/check.sh` asserts the immutability triggers, grants and fold directly.

## G0 tenant configuration trace

`POST /api/v1/configuration` → `ConfigurationController.create` → guarded `TransactionTemplate` → `ConfigurationRepository.nextVersion` (V5 advisory lock, per-org/key) → `insertEntry` (V5 forces `created_by` from `app.actor`) → `appendAudit(CONFIG_CREATE, SUCCESS)` → 201 pending. `POST /api/v1/configuration/{key}/versions/{version}/approve` appends one `config_approval` row (approver forced from the actor, `UNIQUE(org, entry_id)` rejects a duplicate) and audits `CONFIG_APPROVE`; a draft is never effective on its own. `GET /api/v1/configuration/{key}` reads the V5 `effective_config()` fold: only approved entries with `effective_from <= now()`, latest window first, so a future-dated approval does not become effective early. `GET .../{key}/history` returns the immutable version list. Cross-tenant keys are concealed with 404 after actor authorization. `ConfigurationIsolationTest` exercises all four routes against real PostgreSQL; `db/check.sh` asserts forced RLS, actor-derived fields, version sequencing, approval uniqueness, the fold and append-only triggers.

## S-G0-03b audit review trace

`GET /api/v1/audit-events` → `AuditController.query` → guarded `TransactionTemplate` → `AuditService.query` → actor authorization (tenant_admin or privacy_auditor, active membership re-derived per request) → `AuditRepository.query` (forced RLS, bounded filters, `seq DESC` with an exclusive seq cursor and a one-row look-ahead for `nextCursor`) → `appendAudit(AUDIT_READ, SUCCESS)` in the same transaction → 200. Denials are committed as `AUDIT_READ/DENIED` rows then thrown. `AuditQueryTest` proves two-tenant concealment, stable paging and audit-of-read against real PostgreSQL.

## OPS-06 migration change-safety trace

`python3 -I scripts/check-migrations.py --require-baseline` → parse `db/migrations/V*.sql` filenames and leading `-- Key: value` headers → single-pass blanking of comments, strings, identifiers and dollar bodies, then statement splitting and an anchored destructive-statement scan → assert contiguous versions and that every contract has a format-valid `Forward-fix:` plus a valid earlier `Change: expand` reference. The baseline flag fails on an empty or missing migration directory. The same command runs in `scripts/check.sh`, `scripts/quick-check.sh` and the Delivery `policy` job; `db/check.sh` remains the runtime apply-and-assert gate. `scripts/tests/test_check_migrations.py` (34 synthetic cases) covers every branch and the string/statement interaction regressions. See the decision record `docs/decisions/0002-ops06-migration-change-safety.md`.

## S-G0-03 catalog trace

`POST /api/v1/catalogs/{family}/entries` → `CatalogController.define` → `CatalogService.define` (bounded family/code) → `ConfigurationService.create` with key `catalog.<family>.<code>` → append-only V5 entry + `CONFIG_CREATE` audit. `POST .../{code}/versions/{version}/approve` delegates to `ConfigurationService.approve`, appending the named approval that makes the version effective. `GET /api/v1/catalogs/{family}[/entries/{code}]` → guarded transaction → actor authorization → V6 `effective_catalog_family()` / V5 `effective_config()` fold (approved entries within their effective window) → `CATALOG_LIST`/`CATALOG_READ` audit. Cross-tenant codes are concealed; a peer tenant with the same code is invisible under forced RLS. `CatalogIsolationTest` exercises the routes against real PostgreSQL.
