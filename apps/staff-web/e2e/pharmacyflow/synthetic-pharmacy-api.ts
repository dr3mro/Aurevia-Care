import type { Page, Route } from '@playwright/test';
import type {
  InboxItemResponse,
  StatusEventResponse,
  StatusViewResponse,
} from '../../src/features/pharmacyinbox/types';
import { alphaOrganizationId, syntheticInboxItem } from '../../src/features/pharmacyinbox/fixtures';
import type {
  ProposalViewResponse,
  ThreadViewResponse,
} from '../../src/features/prescriberstatus/types';
import {
  syntheticAnswerEntry,
  syntheticOpenThread,
  syntheticProposal,
  syntheticQuestionEntry,
  syntheticSignedRevision,
} from '../../src/features/prescriberstatus/fixtures';

/**
 * A deterministic, in-browser stand-in for the pharmacy workflow API
 * (RX-02, WEB-09).
 *
 * The staff-web pharmacy inbox and the prescriber harness use the real routes,
 * components and transport. This harness intercepts only the pharmacy and
 * prescription endpoints, keeps one shared fact trail for both browser
 * contexts, and records every command so the journey can assert what was sent.
 * Every value is synthetic; no patient record, partner or credential is used.
 */

/** Opaque prescription identifier shared by every synthetic fixture. */
const prescriptionId = syntheticInboxItem.id;

/** Fixed synthetic time so a rendered status never depends on the clock. */
const occurredAt = '2026-11-22T09:00:00Z';

/** Mutable state shared by the two browser contexts; safe to assert in a test. */
export interface SyntheticPharmacyApi {
  /** `METHOD /path` for every intercepted request, in arrival order. */
  calls: string[];
  /** Prescription rows currently routed to the pharmacy. */
  items: InboxItemResponse[];
  /** Append-only status projection for the one synthetic prescription. */
  status: StatusViewResponse | undefined;
  /** Clarification thread visible to both parties, once one is requested. */
  thread: ThreadViewResponse | undefined;
  /** Substitution proposal visible to both parties, once one is proposed. */
  proposal: ProposalViewResponse | undefined;
}

/** Create an empty shared pharmacy API state for one journey. */
export function createSyntheticPharmacyApi(): SyntheticPharmacyApi {
  return { calls: [], items: [], status: undefined, thread: undefined, proposal: undefined };
}

/** Build one immutable status fact with a stable identifier for the timeline. */
function statusEvent(
  id: string,
  type: string,
  version: number,
  actorId: string,
  detail: string | null = null,
  dispensedBy: string | null = null,
  dispensedAt: string | null = null,
  handedOver: string | null = null,
): StatusEventResponse {
  return {
    id,
    organizationId: alphaOrganizationId,
    prescriptionId,
    version,
    type,
    actorId,
    occurredAt,
    detail,
    dispensedBy,
    dispensedAt,
    handedOver,
  };
}

/** Append one fact and advance the version of the shared status projection. */
function appendFact(api: SyntheticPharmacyApi, event: StatusEventResponse): void {
  if (api.status === undefined) {
    return;
  }
  api.status = {
    ...api.status,
    version: event.version,
    events: [...api.status.events, event],
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

/** Read a command body without trusting any field the browser sent. */
function commandBody(route: Route): Record<string, unknown> {
  try {
    const parsed: unknown = JSON.parse(route.request().postData() ?? '{}');
    return typeof parsed === 'object' && parsed !== null
      ? (parsed as Record<string, unknown>)
      : {};
  } catch {
    return {};
  }
}

/** Read one bounded text field from a command body. */
function textField(body: Record<string, unknown>, key: string): string {
  const value = body[key];
  return typeof value === 'string' ? value : '';
}

/** Record the first path segment after a known prefix, or return nothing. */
function tail(path: string, prefix: string): string | undefined {
  return path.startsWith(prefix) ? path.slice(prefix.length) : undefined;
}

/** Handle one intercepted pharmacy or prescription request. */
async function handle(route: Route, api: SyntheticPharmacyApi): Promise<void> {
  const request = route.request();
  const path = new URL(request.url()).pathname;
  const method = request.method();
  api.calls.push(`${method} ${path}`);

  if (method === 'GET' && path === '/api/v1/pharmacy/inbox') {
    await json(route, { items: api.items, nextCursor: null });
    return;
  }

  const inboxId = tail(path, '/api/v1/pharmacy/inbox/');
  if (method === 'GET' && inboxId !== undefined) {
    const item = api.items.find((row) => row.id === decodeURIComponent(inboxId));
    if (item === undefined) {
      await json(route, { code: 'NOT_FOUND' }, 404);
    } else {
      await json(route, item);
    }
    return;
  }

  const statusTail = tail(path, '/api/v1/pharmacy/status/');
  const statusId = statusTail === undefined ? undefined : statusTail.split('/')[0];
  if (statusId !== undefined && decodeURIComponent(statusId) !== prescriptionId) {
    await json(route, { code: 'NOT_FOUND' }, 404);
    return;
  }
  if (method === 'GET' && statusId !== undefined) {
    if (api.status === undefined) {
      await json(route, { code: 'NOT_FOUND' }, 404);
    } else {
      await json(route, api.status);
    }
    return;
  }

  if (method === 'POST' && statusId !== undefined && api.status !== undefined) {
    const body = commandBody(route);
    const action = path.slice(path.lastIndexOf('/') + 1);
    const version = api.status.version + 1;
    if (action === 'accept') {
      const event = statusEvent(
        'event-synthetic-accept', 'accepted', version, 'synthetic.pharmacist',
      );
      appendFact(api, event);
      api.status = { ...api.status, accepted: true };
      api.items = api.items.map((item) => ({ ...item, state: 'accepted', version }));
      await json(route, event);
      return;
    }
    if (action === 'clarification') {
      const question = textField(body, 'question');
      const event = statusEvent(
        'event-synthetic-clarification', 'clarification', version,
        'synthetic.pharmacist', question,
      );
      appendFact(api, event);
      api.items = api.items.map((item) => ({ ...item, state: 'clarification', version }));
      api.thread = {
        thread: { ...syntheticOpenThread, version: 1 },
        entries: [{ ...syntheticQuestionEntry, body: question }],
        overdue: false,
      };
      await json(route, event);
      return;
    }
    if (action === 'dispensing-evidence') {
      const event = statusEvent(
        'event-synthetic-dispense', 'dispensed', version, 'synthetic.pharmacist',
        null,
        textField(body, 'dispensedBy'),
        textField(body, 'dispensedAt'),
        textField(body, 'handedOver'),
      );
      appendFact(api, event);
      api.status = { ...api.status, dispensed: true };
      api.items = api.items.map((item) => ({ ...item, state: 'dispensed', version }));
      await json(route, event);
      return;
    }
  }

  const threadId = tail(path, '/api/v1/pharmacy/clarifications/');
  if (threadId !== undefined) {
    if (method === 'GET') {
      if (api.thread === undefined) {
        await json(route, { code: 'NOT_FOUND' }, 404);
      } else {
        await json(route, api.thread);
      }
      return;
    }
    if (method === 'POST' && path.endsWith('/answer') && api.thread !== undefined) {
      const body = commandBody(route);
      const answerBody = textField(body, 'answerBody');
      const amendedRevisionId = textField(body, 'amendedRevisionId');
      const current = api.thread;
      api.thread = {
        thread: {
          ...current.thread,
          state: 'answered',
          version: current.thread.version + 1,
          answerBody,
          amendedRevisionId,
          answeredAt: occurredAt,
          answeredBy: 'synthetic.prescriber',
        },
        entries: [
          ...current.entries,
          { ...syntheticAnswerEntry, body: answerBody, amendedRevisionId },
        ],
        overdue: false,
      };
      await json(route, api.thread);
      return;
    }
  }

  const substitutionPath = tail(path, '/api/v1/pharmacy/substitutions/');
  if (substitutionPath !== undefined) {
    if (method === 'POST' && substitutionPath === `${prescriptionId}/proposals`) {
      api.proposal = {
        proposal: { ...syntheticProposal, state: 'pending', version: 1 },
        appliedRevision: null,
      };
      await json(route, api.proposal);
      return;
    }
    if (method === 'GET') {
      if (api.proposal === undefined) {
        await json(route, { code: 'NOT_FOUND' }, 404);
      } else {
        await json(route, api.proposal);
      }
      return;
    }
    if (method === 'POST' && path.endsWith('/approve') && api.proposal !== undefined) {
      const revision = api.proposal.proposal.version + 1;
      api.proposal = {
        proposal: { ...api.proposal.proposal, state: 'approved', version: revision },
        appliedRevision: syntheticSignedRevision,
      };
      api.items = api.items.map((item) => ({
        ...item,
        medication: syntheticSignedRevision.replacement.tradeName,
        version: revision,
      }));
      if (api.status !== undefined) {
        appendFact(api, statusEvent(
          'event-synthetic-substitution', 'substitution_approved', revision,
          'synthetic.prescriber', `Signed revision ${syntheticSignedRevision.id}`,
        ));
      }
      await json(route, api.proposal);
      return;
    }
  }

  const prescriptionPath = tail(path, '/api/v1/prescriptions/');
  if (method === 'POST' && prescriptionPath !== undefined) {
    if (prescriptionPath.endsWith('/transmit')) {
      api.items = [
        {
          ...syntheticInboxItem,
          state: 'submitted',
          version: 1,
        },
      ];
      api.status = {
        organizationId: alphaOrganizationId,
        prescriptionId,
        version: 1,
        accepted: false,
        dispensed: false,
        events: [statusEvent('event-synthetic-submitted', 'submitted', 1, 'synthetic.prescriber')],
      };
      await json(route, { id: prescriptionId, version: 1, state: 'submitted' });
      return;
    }
    if (prescriptionPath.endsWith('/confirm-delivery') && api.status !== undefined) {
      appendFact(api, statusEvent(
        'event-synthetic-delivery', 'delivery_confirmed', 2, 'synthetic.partner',
        'Synthetic partner confirmed receipt',
      ));
      api.items = api.items.map((item) => ({ ...item, state: 'delivery_confirmed', version: 2 }));
      await json(route, { id: prescriptionId, version: 2, state: 'delivery_confirmed' });
      return;
    }
  }

  await json(route, { code: 'NOT_FOUND' }, 404);
}

/**
 * Install the shared synthetic API on one page and supply the CSRF cookie the
 * real transport requires for writes. Call before navigating to a route.
 *
 * @param page page that owns the browser context to mock
 * @param baseURL configured browser base URL, used to scope the CSRF cookie
 * @param api shared state object created by `createSyntheticPharmacyApi`
 */
export async function installSyntheticPharmacyApi(
  page: Page,
  baseURL: string | undefined,
  api: SyntheticPharmacyApi,
): Promise<void> {
  await page.context().addCookies([
    {
      name: 'XSRF-TOKEN',
      value: 'synthetic-csrf-token',
      url: baseURL ?? 'http://127.0.0.1:5173',
    },
  ]);
  const handler = async (route: Route): Promise<void> => handle(route, api);
  await page.route('**/api/v1/pharmacy/**', handler);
  await page.route('**/api/v1/prescriptions/**', handler);
}
