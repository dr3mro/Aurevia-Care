import type { Page, Route } from '@playwright/test';

/**
 * Deterministic in-browser stand-in for the clinician order boundary
 * (LAB-01, RAD-01, XCH-02, WEB-09).
 *
 * The harness mounts the real order-entry parts and the real transport, but a
 * browser journey must not contact a patient record, a partner or a credential.
 * This stub intercepts only `/api/v1/orders/**` requests from one page, mints
 * opaque synthetic order ids, drives a controllable partner failure and records
 * every command so the journey can assert what the browser actually sent.
 */

/** Mutable state shared with the page's route handlers; safe to assert in the test. */
export interface SyntheticOrderApi {
  /** `METHOD /path` for every intercepted request, in arrival order. */
  calls: string[];
  /** When true the next delivery attempt is refused once with a correlation id. */
  failNextSend: boolean;
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
 * Body for the order commands that are accepted with a fixed answer:
 * reconciliation resolves an ambiguous send without a blind resend, and
 * placing, cancelling and amending carry an opaque version.
 */
function commandResponse(method: string, path: string): Record<string, unknown> | undefined {
  if (method !== 'POST') {
    return undefined;
  }
  if (path.endsWith('/reconcile')) {
    return { timeline: ['placed', 'transmitted', 'delivered'] };
  }
  if (path.endsWith('/place')) {
    return { status: 'placed', version: 2 };
  }
  if (path.endsWith('/cancel')) {
    return { status: 'cancelled', version: 3 };
  }
  if (path.endsWith('/amend')) {
    return { version: 3 };
  }
  return undefined;
}

/**
 * Install the synthetic order API on one page and supply the write CSRF cookie
 * the real transport requires. Call before navigating to the harness.
 *
 * @param page page that owns the browser context to mock
 * @param baseURL configured browser base URL, used to scope the CSRF cookie
 * @returns mutable state the test inspects to prove commands reached the API
 */
export async function installSyntheticOrderApi(
  page: Page,
  baseURL: string | undefined,
): Promise<SyntheticOrderApi> {
  const api: SyntheticOrderApi = { calls: [], failNextSend: false };
  let draftSequence = 0;

  await page.context().addCookies([
    {
      name: 'XSRF-TOKEN',
      value: 'synthetic-csrf-token',
      url: baseURL ?? 'http://127.0.0.1:5173',
    },
  ]);

  await page.route('**/api/v1/orders/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const method = request.method();
    api.calls.push(`${method} ${path}`);

    // A saved draft gets a fresh opaque id and a version the next write matches.
    if (method === 'POST' && path === '/api/v1/orders/drafts') {
      draftSequence += 1;
      await json(route, { id: `ORD-SYN-${draftSequence}`, version: 1 });
      return;
    }

    // A reload returns the same draft the harness holds; nothing is persisted
    // beyond this browser journey.
    const reload = /^\/api\/v1\/orders\/drafts\/([^/]+)$/.exec(path);
    if (method === 'GET' && reload !== null) {
      await json(route, { id: reload[1], version: 1 });
      return;
    }

    const deliver = /^\/api\/v1\/orders\/([^/]+)\/deliver$/.exec(path);
    if (method === 'POST' && deliver !== null) {
      // The partner refusal is a policy decision carried on the response: one
      // known failure with a copyable reference and a safe-to-retry flag. It is
      // consumed once so the print fallback is not silently retried.
      if (api.failNextSend) {
        api.failNextSend = false;
        await json(
          route,
          { code: 'PARTNER_UNAVAILABLE', retrySafe: true, correlationId: 'SYN-CORR-42' },
          502,
        );
        return;
      }
      await json(route, { timeline: ['placed', 'transmitted', 'delivered'] });
      return;
    }

    const command = commandResponse(method, path);
    if (command !== undefined) {
      await json(route, command);
      return;
    }

    await json(route, { code: 'NOT_FOUND' }, 404);
  });

  return api;
}
