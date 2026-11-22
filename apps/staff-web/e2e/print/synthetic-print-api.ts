import type { Page, Route } from '@playwright/test';

/**
 * Deterministic in-browser stand-in for the protected printing boundary
 * (WEB-09, RX-02, LAB-01, OPS-02).
 *
 * The harness mounts the real print buttons and the real transport, but a
 * browser journey must not need a live chart, a printer or a credential. This
 * harness intercepts only `/api/v1/**` requests from one page, accepts the
 * sign and place commands that commit the synthetic sources, and records every
 * print audit entry so the journey can read it back. No real patient datum,
 * endpoint or credential appears here.
 */

/** One sanitized audit row: identifiers and reasons only, never handout text. */
export interface PrintAuditEntry {
  kind: string;
  reason: string | null;
  language: string;
  [field: string]: unknown;
}

/** Mutable state shared with the page's route handlers; safe to assert in the test. */
export interface SyntheticPrintApi {
  /** One entry per accepted `/api/v1/printing/audit` POST, in arrival order. */
  audit: PrintAuditEntry[];
  /** `METHOD /path` for every intercepted request, in arrival order. */
  calls: string[];
}

/** Respond with a JSON body through the intercepted route. */
async function json(route: Route, body: unknown, status = 200): Promise<void> {
  await route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  });
}

/**
 * Install the synthetic print API on one page and supply the write CSRF cookie
 * the real transport requires. Call before navigating to the print harness.
 *
 * @param page page that owns the browser context to mock
 * @param baseURL configured browser base URL, used to scope the CSRF cookie
 * @returns mutable state the test inspects to prove commands reached the API
 */
export async function installSyntheticPrintApi(
  page: Page,
  baseURL: string | undefined,
): Promise<SyntheticPrintApi> {
  const api: SyntheticPrintApi = { audit: [], calls: [] };

  await page.context().addCookies([
    {
      name: 'XSRF-TOKEN',
      value: 'synthetic-csrf-token',
      url: baseURL ?? 'http://127.0.0.1:5173',
    },
  ]);

  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const method = request.method();
    api.calls.push(`${method} ${path}`);

    if (method === 'POST' && path === '/api/v1/printing/audit') {
      const entry = JSON.parse(request.postData() ?? '{}') as PrintAuditEntry;
      api.audit.push(entry);
      await json(route, { id: `audit-${api.audit.length}` });
      return;
    }

    if (method === 'POST' && path === '/api/v1/prescriptions/RX-SYN-1/sign') {
      await json(route, { id: 'RX-SYN-1', state: 'attested', version: 2 });
      return;
    }

    if (method === 'POST' && path === '/api/v1/orders/ORD-SYN-1/place') {
      await json(route, { id: 'ORD-SYN-1', status: 'placed', version: 2 });
      return;
    }

    await json(route, { code: 'NOT_FOUND' }, 404);
  });

  return api;
}
