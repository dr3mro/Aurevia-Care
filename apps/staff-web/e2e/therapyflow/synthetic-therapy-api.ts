import type { Page, Route } from '@playwright/test';
import { syntheticFacility } from '../../src/fixtures/synthetic';

/**
 * Synthetic physical-therapy boundary for the browser journey (WEB-10,
 * PT-01). There is no production therapy screen yet, so the harness drives a
 * small dev-only workstation through this stand-in. The store is append-only:
 * a refused episode never appears, and every request's tenant, correlation and
 * CSRF headers are recorded so the journey can prove the real boundary shape.
 * All subjects and identifiers are synthetic.
 */

/** Opaque patient alias of the one referrable synthetic episode. */
export const expectedPatientId = 'SYN-PT-PATIENT-001';

/** Opaque identifier of the accepted episode. */
const episodeId = 'episode-synthetic-001';

/** One recorded request, reduced to its boundary metadata. */
export interface TherapyRequest {
  method: string;
  path: string;
  organizationId: string;
  correlationId: string;
  csrf: string;
}

/** One accepted therapy episode. */
export interface TherapyEpisode {
  id: string;
  patientId: string;
  plan: string;
  state: 'accepted';
  version: number;
}

/** Mutable state behind the harness; safe to assert from the spec. */
export interface SyntheticTherapyStore {
  calls: string[];
  requests: TherapyRequest[];
  episodes: TherapyEpisode[];
}

/** Handle used by the journey to install the routes and inspect the store. */
export interface SyntheticTherapyApi {
  readonly store: SyntheticTherapyStore;
  installOn(page: Page, baseURL: string | undefined): Promise<void>;
}

/** Read one string field of an untrusted JSON body without a type assertion. */
function fieldOf(body: unknown, key: string): string {
  if (typeof body === 'object' && body !== null && key in body) {
    const value: unknown = Reflect.get(body, key);
    return typeof value === 'string' ? value : '';
  }
  return '';
}

/** Fulfil one response with a machine-readable JSON body. */
async function json(route: Route, body: unknown, status = 200): Promise<void> {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
}

/** Build one fresh synthetic therapy API with its own store. */
export function createSyntheticTherapyApi(): SyntheticTherapyApi {
  const store: SyntheticTherapyStore = { calls: [], requests: [], episodes: [] };

  async function install(page: Page, baseURL: string | undefined): Promise<void> {
    await page.context().addCookies([
      {
        name: 'XSRF-TOKEN',
        value: 'synthetic-csrf-token',
        url: baseURL ?? 'http://127.0.0.1:5173',
      },
    ]);
    await page.route('**/api/v1/therapy/**', async (route) => {
      const request = route.request();
      const url = new URL(request.url());
      const headers = request.headers();
      store.calls.push(`${request.method()} ${url.pathname}`);
      const requestRecord: TherapyRequest = {
        method: request.method(),
        path: url.pathname,
        organizationId: headers['x-active-organization'] ?? '',
        correlationId: headers['x-correlation-id'] ?? '',
        csrf: headers['x-xsrf-token'] ?? '',
      };
      store.requests.push(requestRecord);

      if (url.pathname !== '/api/v1/therapy/episodes' || request.method() !== 'POST') {
        await json(route, { code: 'NOT_FOUND' }, 404);
        return;
      }
      // A command without the tenant, correlation and CSRF metadata fails
      // closed before any episode can be created.
      if (
        requestRecord.organizationId !== syntheticFacility.organizationId ||
        requestRecord.correlationId === '' ||
        requestRecord.csrf !== 'synthetic-csrf-token'
      ) {
        await json(route, { code: 'FORBIDDEN' }, 403);
        return;
      }
      const body: unknown = JSON.parse(request.postData() ?? '{}');
      const patientId = fieldOf(body, 'patientId');
      if (patientId !== expectedPatientId) {
        await json(route, { code: 'FORBIDDEN' }, 403);
        return;
      }
      const episode: TherapyEpisode = {
        id: episodeId,
        patientId,
        plan: fieldOf(body, 'plan'),
        state: 'accepted',
        version: 1,
      };
      store.episodes.push(episode);
      await json(route, { id: episode.id, state: episode.state, version: episode.version }, 201);
    });
  }

  return { store, installOn: install };
}
