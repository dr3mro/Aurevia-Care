import type { Page, Route } from '@playwright/test';
import { syntheticFacility } from '../../src/fixtures/synthetic';

/**
 * Synthetic tenant-export boundary for the browser journey (WEB-10, OPS-07).
 * The real screen and typed client are exercised; only `/api/v1/**` is stubbed.
 * A write must carry the tenant, correlation, CSRF and idempotency metadata,
 * and only a tenant administrator may start an export. A denial returns the
 * contract's 403 `FORBIDDEN` code and records no job. All identifiers,
 * checksums and counts here are synthetic.
 */

/** Opaque identifier of the one synthetic export job. */
export const jobId = 'export-synthetic-001';

/** Synthetic manifest checksum; never a digest of real data. */
export const checksum = 'sha256:synthetic-export';

/** Number of synthetic records in the job. */
export const recordCount = 3;

/** Retention instant after the harness's fixed clock. */
export const expiresAt = '2026-11-23T10:00:00Z';

/** One recorded request, reduced to its boundary metadata. */
export interface AdminRequest {
  method: string;
  path: string;
  organizationId: string;
  correlationId: string;
  csrf: string;
  idempotencyKey: string;
  role: string;
}

/** Mutable state behind the harness; safe to assert from the spec. */
export interface SyntheticAdminStore {
  calls: string[];
  requests: AdminRequest[];
  exports: string[];
}

/** Handle used by the journey to install the routes and inspect the store. */
export interface SyntheticAdminApi {
  readonly store: SyntheticAdminStore;
  installOn(page: Page, baseURL: string | undefined): Promise<void>;
}

/** Fulfil one response with a machine-readable JSON body. */
async function json(route: Route, body: unknown, status = 200): Promise<void> {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
}

/**
 * True when a command is scoped to the synthetic tenant and carries the
 * correlation, CSRF and idempotency metadata the real client always sends.
 * A declared role must be the tenant administrator; the real client does not
 * send a role header, so an absent header stays within the real shape.
 */
function authorizedWrite(record: AdminRequest): boolean {
  return (
    record.organizationId === syntheticFacility.organizationId &&
    record.correlationId !== '' &&
    record.csrf === 'synthetic-csrf-token' &&
    record.idempotencyKey !== '' &&
    (record.role === '' || record.role === 'tenant_admin')
  );
}

/** True when a manifest read belongs to the tenant admin and an existing job. */
function authorizedRead(record: AdminRequest, store: SyntheticAdminStore): boolean {
  // The real GET descriptor carries only the tenant header; correlation and
  // CSRF metadata belong to commands. A declared role must stay admin.
  return (
    record.organizationId === syntheticFacility.organizationId &&
    (record.role === '' || record.role === 'tenant_admin') &&
    store.exports.includes(jobId)
  );
}

/** Answer the export-start command; a denial appends no job to the store. */
async function startExport(route: Route, record: AdminRequest, store: SyntheticAdminStore) {
  if (!authorizedWrite(record)) {
    await json(route, { code: 'FORBIDDEN' }, 403);
    return;
  }
  store.exports.push(jobId);
  await json(
    route,
    { job_id: jobId, checksum, record_count: recordCount, expires_at: expiresAt },
    200,
  );
}

/** Answer one manifest read for the job this harness started. */
async function readManifest(route: Route, record: AdminRequest, store: SyntheticAdminStore) {
  if (!authorizedRead(record, store)) {
    await json(route, { code: 'FORBIDDEN' }, 403);
    return;
  }
  await json(route, { job_id: jobId, checksum }, 200);
}

/** Build one fresh synthetic admin API with its own store. */
export function createSyntheticAdminApi(): SyntheticAdminApi {
  const store: SyntheticAdminStore = { calls: [], requests: [], exports: [] };

  async function install(page: Page, baseURL: string | undefined): Promise<void> {
    await page.context().addCookies([
      {
        name: 'XSRF-TOKEN',
        value: 'synthetic-csrf-token',
        url: baseURL ?? 'http://127.0.0.1:5173',
      },
    ]);
    await page.route('**/api/v1/**', async (route) => {
      const request = route.request();
      const url = new URL(request.url());
      const headers = request.headers();
      store.calls.push(`${request.method()} ${url.pathname}`);
      const record: AdminRequest = {
        method: request.method(),
        path: url.pathname,
        organizationId: headers['x-active-organization'] ?? '',
        correlationId: headers['x-correlation-id'] ?? '',
        csrf: headers['x-xsrf-token'] ?? '',
        idempotencyKey: headers['idempotency-key'] ?? '',
        role: headers['x-active-role'] ?? '',
      };
      store.requests.push(record);

      if (url.pathname === '/api/v1/tenant/exports' && request.method() === 'POST') {
        await startExport(route, record, store);
        return;
      }
      if (url.pathname === `/api/v1/tenant/exports/${jobId}` && request.method() === 'GET') {
        await readManifest(route, record, store);
        return;
      }
      await json(route, { code: 'NOT_FOUND' }, 404);
    });
  }

  return { store, installOn: install };
}
