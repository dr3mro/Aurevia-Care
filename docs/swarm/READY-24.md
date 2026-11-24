# READY-24 · Admin pages: /admin/config and /admin/audit on the real API

PR #278, tested branch tip 244fc95293c5e6f51ad00e92edfbbc1f4a25836e.

**Draft: the worker did not finish cleanly** (agent result: ok; local checks: failed). Its work is saved here so nothing is lost; a fix worker or a person continues from this branch.

**Why it failed** (TypeScript/frontend and gate lines):

```text
FAIL unreferenced-service: apps/api/src/main/java/com/x/NewService.java
FAIL unreferenced-service: apps/api/src/main/java/com/x/OrphanService.java
```

Task: READY-24

Swarm worker 2. Local checks: failed. Agent result: ok.

# READY-24 — Admin pages: /admin/config and /admin/audit on the real API

## Summary

Wired the two remaining administration screens to the real API. `ConfigPage.tsx`
looks a configuration key up, renders its immutable version and approval history
through the reused `ConfigEditor`, defines a version, approves a pending version
only after an explicit confirmation, and lists/defines catalog-family entries.
`AuditPage.tsx` submits bounded filters, pages the audit trail with an accumulating
"Load more", renders a distinct no-events state, and shows each instant in the
facility zone with the UTC instant on hover or focus. Both routes self-register in
`features/admin/routes.ts`. All data in every test is synthetic; no dependency,
migration, credential or patient data was added.

Acceptance mapping:

| Story step | Implementation | Test |
|---|---|---|
| 1. Read the three controllers and use the listed endpoints | `api.ts`: `getConfiguration`, `getConfigurationHistory`, `defineConfiguration`, `approveConfiguration`, `listCatalogFamily`, `defineCatalogEntry`, `queryAuditEvents` plus pure `auditQueryString` | `api.test.ts` descriptor URL/body/header assertions (paths exactly match `ConfigurationController`, `CatalogController`, `AuditController`) |
| 2. `ConfigPage` lookup/versions/approval/define/approve; `AuditPage` filters + cursor paging + empty state + zone/UTC | `ConfigPage.tsx`, `AuditPage.tsx`, minimal optional props on `ConfigEditor.tsx` and `AuditEventList.tsx` | `ConfigPage.test.tsx`, `AuditPage.test.tsx`, `admin-components.test.tsx` |
| 3. Register `/admin/config` and `/admin/audit`; audit only for privacy auditor and tenant admin | `features/admin/routes.ts`; `app/navigation.ts` already scopes audit to those two roles and config to tenant admin; a 403 renders `ForbiddenState` | `ConfigPage.test.tsx > registers the config path before the catch-all`, `AuditPage.test.tsx > shows the audit item only to the privacy auditor and the tenant admin`, both lazy-route render tests and 403 tests |
| 4. Tests for each state, query string, Load more, approval confirmation | 7 WEB-09 states on both pages; exact query-string assertion; append and filter-reset tests; cancel/confirm approval tests | See `## Coverage` |
| Route registration (READY-51) | `export const routes: AppRoute[]` extended in `features/admin/routes.ts`; `app/routes.tsx` and `app/navigation.ts` untouched | `ConfigPage.test.tsx`, `AuditPage.test.tsx`, `app/routes.test.tsx`, `AppShell.test.tsx` |

## Affected requirements

- **ADM-01** — tenant configuration and catalogs with immutable versions, named
  approval, effective dates and version history; define and approve commands carry
  a fresh idempotency key and a server-derived author/approver (no body tenant).
- **OPS-02** — append-only audit review with actor, purpose, scope, correlation,
  action, resource, outcome and instant; no client-side tamper claim is fabricated
  from API rows; outcomes are shown with word + shape-distinct glyph.
- WEB-09 (resilient screens) and UX-01 (bilingual, RTL, facility zone) are
  supporting requirements for the seven states, the Arabic bundle and time display.

## Risk class

Cross-boundary (staff web ↔ real configuration/catalog/audit API). Approval is an
irreversible administration command, so it is confirmed and assigned a fresh
idempotency key; the API remains the authorization authority. No signed clinical
data, patient association, prescribing or critical-result surface is changed.
Negative, forbidden, conflict and failure paths are covered with synthetic data.

## Review owner

Senior React and Vite + Senior TypeScript (advisory bounded review dispatched to
the `orevia-senior-react` specialist; its read-only findings are recorded
under Findings). The primary worker ran every command listed below.

## Data changes

None. No Flyway migration, backfill, lock/volume impact, rollback or forward-fix.
The only reserved migration version in this story is unused.

## Authorization boundaries and audit events

- Tenant comes only from `useActiveOrganization()` and is sent as
  `X-Active-Organization` by `buildRequestDescriptor`; no request body or URL
  carries an organization. Path segments are `encodeURIComponent`-encoded.
- All four POST commands send a fresh `crypto.randomUUID()` `Idempotency-Key`.
  The approve request body contains only the optional note; approver identity is
  derived server-side.
- Audit is restricted in `app/navigation.ts` to `privacy_auditor` and
  `tenant_admin`; config to `tenant_admin`. A direct visit still fails closed via
  `StateView.forTransportError` mapping 403 → `ForbiddenState`.
- Audit filters stay in component state; nothing is written to local/session
  storage, the URL, a page title or a log. No audit event is added or changed by
  this client slice; server-side `AUDIT_READ`/`CONFIG_ADMIN` auditing remains the
  API's responsibility.

## Migration and rollout considerations

None. Both pages are lazy route chunks: `AuditPage` 2.14 kB gzip and `ConfigPage`
3.48 kB gzip, each far below the WEB-08 250 kB budget (`check-bundle-size.mjs`
reported `ok` for every chunk).

## Test evidence

Evidence source: executed tool commands (Vitest, tsc, ESLint, Vite, bundle-size
and repository policy checks) plus one read-only advisory specialist review.
No simulated-clinical user, human participant or CI run was available or claimed.

- TDD red: `npx vitest run src/features/admin/api.test.ts` → 15 failures
  (`TypeError: <function> is not a function`) for the new API client.
- TDD red: `npx vitest run src/features/admin/ConfigPage.test.tsx src/features/admin/AuditPage.test.tsx`
  → `Error: Failed to resolve import "./ConfigPage"` / `"./AuditPage"`.
- Green (changed area): `npx vitest run --exclude 'e2e/**' src/features/admin/ConfigPage.test.tsx src/features/admin/AuditPage.test.tsx src/features/admin/admin-components.test.tsx src/features/admin/api.test.ts src/features/admin/admin-core.test.ts`
  → `Test Files 5 passed (5)`, `Tests 132 passed (132)`.
- Canonical gate: `bash apps/staff-web/check.sh` → `EXIT=0`; final lines:
  `Test Files 51 passed (51)`, `Tests 617 passed (617)`,
  `No files with missing coverage.`, `76 files fully covered.`,
  `Statements : 100% ( 1624/1624 )`, `Branches : 100% ( 894/894 )`,
  `Functions : 100% ( 535/535 )`, `Lines : 100% ( 1546/1546 )`.
- `npm run gen:api:check` → `gen:api: generated client matches the contract`.
- Typecheck final lines (from `check.sh`, `npm run typecheck` /
  `tsc --noEmit -p tsconfig.json`, and the `tsc` inside `npm run build`):

  ```
  > orevia-staff-web@0.1.0 typecheck
  > tsc --noEmit -p tsconfig.json

  > orevia-staff-web@0.1.0 lint
  > eslint . --max-warnings 0

  > orevia-staff-web@0.1.0 build
  > tsc --noEmit -p tsconfig.json && vite build
  ✓ built in 94ms
  ```

  No `error TS…` was reported.
- `npm run lint` (`eslint . --max-warnings 0`) → exit 0, no output.
- `node scripts/check-bundle-size.mjs` → `AuditPage ... 2130 ok`,
  `ConfigPage ... 3474 ok`, every other chunk `ok`.
- `bash scripts/check.sh` → exit 1 at `scripts/check-pack.py`:
  `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. This is the
  documented cloud-worker CI-profile artifact (MEMORY, OPS-CW-003-01):
  `git ls-files -v opencode.json` prints `S` (skip-worktree), so the worker sees
  the CI profile while `git show HEAD:opencode.json` is the committed local
  profile. `python3 -I scripts/gen-opencode.py --check` likewise reports stale in
  the worktree. O
