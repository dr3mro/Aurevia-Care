import type { Page, Route } from '@playwright/test';
import { syntheticFacility } from '../../src/fixtures/synthetic';

/**
 * Synthetic daily-reconciliation boundary for the browser journey (WEB-10,
 * FIN-01). Finance roles read only totals in integer minor units; no clinical
 * narrative crosses this boundary. A non-finance role is denied with the
 * contract's 403 `FORBIDDEN` code and no read is recorded. All amounts,
 * identifiers and dates here are synthetic.
 */

/** Synthetic civil date reported by the reconciliation. */
export const reconciliationDate = '2026-11-22';

/** Fixed integer minor-unit total for the synthetic day. */
export const totalMinorUnits = 450000;

/** Fixed number of posted entries behind the synthetic total. */
export const postedEntries = 7;

/** One recorded request, reduced to its boundary metadata. */
export interface FinanceRequest {
  method: string;
  path: string;
  organizationId: string;
  correlationId: string;
  csrf: string;
  role: string;
}

/** Mutable state behind the harness; safe to assert from the spec. */
export interface SyntheticFinanceStore {
  calls: string[];
  requests: FinanceRequest[];
  reads: string[];
}

/** Handle used by the journey to install the routes and inspect the store. */
export interface SyntheticFinanceApi {
  readonly store: SyntheticFinanceStore;
  installOn(page: Page, baseURL: string | undefined): Promise<void>;
}

/** Fulfil one response with a machine-readable JSON body. */
async function json(route: Route, body: unknown, status = 200): Promise<void> {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
}

/** Build one fresh synthetic finance API with its own store. */
export function createSyntheticFinanceApi(): SyntheticFinanceApi {
  const store: SyntheticFinanceStore = { calls: [], requests: [], reads: [] };

  async function install(page: Page, baseURL: string | undefined): Promise<void> {
    await page.context().addCookies([
      {
        name: 'XSRF-TOKEN',
        value: 'synthetic-csrf-token',
        url: baseURL ?? 'http://127.0.0.1:5173',
      },
    ]);
    await page.route('**/api/v1/finance/**', async (route) => {
      const request = route.request();
      const url = new URL(request.url());
      const headers = request.headers();
      store.calls.push(`${request.method()} ${url.pathname}`);
      const requestRecord: FinanceRequest = {
        method: request.method(),
        path: url.pathname,
        organizationId: headers['x-active-organization'] ?? '',
        correlationId: headers['x-correlation-id'] ?? '',
        csrf: headers['x-xsrf-token'] ?? '',
        role: headers['x-active-role'] ?? '',
      };
      store.requests.push(requestRecord);

      if (url.pathname !== '/api/v1/finance/reconciliation' || request.method() !== 'GET') {
        await json(route, { code: 'NOT_FOUND' }, 404);
        return;
      }
      // Deny before any aggregate is read: neither a missing tenant/correlation
      // nor a role outside finance may see the daily totals.
      if (
        requestRecord.organizationId !== syntheticFacility.organizationId ||
        requestRecord.correlationId === '' ||
        requestRecord.csrf !== 'synthetic-csrf-token' ||
        requestRecord.role !== 'finance'
      ) {
        await json(route, { code: 'FORBIDDEN' }, 403);
        return;
      }
      store.reads.push(url.searchParams.get('date') ?? reconciliationDate);
      await json(
        route,
        {
          date: reconciliationDate,
          currency: 'USD',
          totalMinorUnits,
          postedEntries,
        },
        200,
      );
    });
  }

  return { store, installOn: install };
}
