import type { Page, Route } from '@playwright/test';
import type { ClinicalNoteRevisionResponse } from '../../src/features/chart/api';

/**
 * Deterministic in-browser stand-in for the clinical-note boundary (WEB-10,
 * CLN-05). The harness mounts the real note editor and real transport, but a
 * browser journey must not contact a patient record or a note service. This
 * stub intercepts only the patient, chart and clinical-note reads and writes,
 * holds one mutable draft revision so reloads observe saved state, and records
 * every command so the journey can assert what the browser actually sent.
 */

/** Opaque identifiers used only by this journey. */
const noteId = 'note-syn-1';
const patientId = 'patient-100';

/** Mutable state shared with the page's route handlers; safe to assert in the test. */
export interface SyntheticNoteApi {
  /** `METHOD /path` for every intercepted request, in arrival order. */
  calls: string[];
}

/** Build the first draft revision the note page loads. */
function freshRevision(): ClinicalNoteRevisionResponse {
  return {
    id: noteId,
    version: 1,
    state: 'draft',
    patientId,
    encounterId: 'enc-001',
    title: 'Synthetic clinical note',
    content: 'Synthetic chief concern and history for review.',
    language: 'en',
    templateCode: 'SOAP',
    templateVersion: 2,
    clinicalAuthorSubject: 'synthetic.clinician',
    dataEntrySubject: 'synthetic.assistant',
    effectiveAt: null,
    signedAt: null,
    signerSubject: null,
    attestation: null,
    contentChecksum: null,
    supersedesVersion: null,
    enteredErrorReason: null,
    enteredErrorActor: null,
    affectedRecipients: null,
    createdAt: '2026-03-09T10:00:00Z',
  };
}

/** Respond with a JSON body through the intercepted route. */
async function json(route: Route, body: unknown, status = 200): Promise<void> {
  await route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  });
}

/** Read one string field from a posted body without trusting its runtime type. */
function text(body: Record<string, unknown>, key: string, fallback: string): string {
  const value = body[key];
  return typeof value === 'string' ? value : fallback;
}

/**
 * Install the synthetic note API on one page and supply the write CSRF cookie
 * the real transport requires. Call before navigating to the harness.
 *
 * @param page page that owns the browser context to mock
 * @param baseURL configured browser base URL, used to scope the CSRF cookie
 * @returns mutable state the test inspects to prove commands reached the API
 */
export async function installSyntheticNoteApi(
  page: Page,
  baseURL: string | undefined,
): Promise<SyntheticNoteApi> {
  const api: SyntheticNoteApi = { calls: [] };
  const revision = freshRevision();

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

    if (method === 'GET' && path === `/api/v1/patients/${patientId}`) {
      await json(route, {
        patient: { id: patientId, displayName: 'Synthetic Layla' },
        duplicates: [],
      });
      return;
    }

    if (method === 'GET' && path === `/api/v1/chart/records/patients/${patientId}/entries`) {
      await json(route, { entries: [], nextCursor: null });
      return;
    }

    if (method === 'GET' && path === `/api/v1/clinical-notes/${noteId}`) {
      await json(route, { current: revision, history: [] });
      return;
    }

    if (method === 'PUT' && path === `/api/v1/clinical-notes/${noteId}`) {
      const body = request.postDataJSON() as Record<string, unknown>;
      revision.content = text(body, 'content', revision.content);
      revision.version += 1;
      await json(route, revision);
      return;
    }

    if (method === 'POST' && path === `/api/v1/clinical-notes/${noteId}/sign`) {
      const body = request.postDataJSON() as Record<string, unknown>;
      revision.state = 'signed';
      revision.version += 1;
      revision.signedAt = '2026-03-09T11:00:00Z';
      revision.signerSubject = 'synthetic.clinician';
      revision.attestation = text(body, 'attestation', 'Synthetic attestation');
      await json(route, revision);
      return;
    }

    await json(route, { code: 'NOT_FOUND' }, 404);
  });

  return api;
}
