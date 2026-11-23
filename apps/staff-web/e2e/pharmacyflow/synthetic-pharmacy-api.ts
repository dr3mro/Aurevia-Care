import type { Page, Route } from '@playwright/test';
import type {
  InboxItemResponse,
  InboxPageResponse,
  StatusEventResponse,
  StatusViewResponse,
} from '../../src/features/pharmacyinbox/types';
import { syntheticInboxItem } from '../../src/features/pharmacyinbox/fixtures';
import type {
  CatalogEntryView,
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
import { syntheticFacility } from '../../src/fixtures/synthetic';

/**
 * Deterministic in-browser stand-in for the pharmacy boundary (PHARM-08,
 * RX-02, WEB-09).
 *
 * The two roles drive real staff-web screens, exported review components and
 * real typed API clients. Only `/api/v1/pharmacy/**` and the logical
 * `/api/v1/prescriptions/**` commands are intercepted, and both browser
 * contexts share one in-memory store so a command written by one role is read
 * by the other. Every status fact is append-only: acceptance, clarification,
 * substitution and dispensing stay separate facts, mirroring the domain
 * invariant. The store also records every `METHOD /path` so a journey can
 * assert what was actually sent. Every subject, identifier and medicine is
 * synthetic; no patient record, partner, credential or real identifier is
 * contacted or stored here.
 */

/** The synthetic tenant shared by every fixture in this harness. */
const organizationId = syntheticFacility.organizationId;

/** Opaque identifiers used by the synthetic clinical objects. */
const prescriptionId = 'rx-synthetic-001';
const threadId = 'thread-synthetic-001';
const proposalId = 'proposal-synthetic-001';

/** The original catalog entry in the shared prescriber fixture. */
const originalEntry: CatalogEntryView = syntheticProposal.proposal.original;

/** The tagged alternative proposed by the synthetic pharmacy. */
const proposedEntry: CatalogEntryView = syntheticProposal.proposal.proposed;

/** Fixed synthetic time for facts that carry no clock-derived timestamp. */
const occurredAt = '2026-11-22T09:00:00Z';

/** Stable fact numbers keep the trace readable and the timestamps increasing. */
const factNumbers: Record<string, number> = {
  submitted: 1,
  delivery_confirmed: 2,
  clarification: 3,
  accepted: 4,
  dispensed: 5,
};

/** A stable, named identifier for each kind of status fact. */
const factIds: Record<string, string> = {
  submitted: 'event-synthetic-submitted',
  delivery_confirmed: 'event-synthetic-delivery',
  clarification: 'event-synthetic-clarification',
  accepted: 'event-synthetic-accept',
  dispensed: 'event-synthetic-dispense',
  substitution_approved: 'event-synthetic-substitution',
};

/** A stable, increasing instant derived from the fact number; no wall clock. */
function instantFor(factNumber: number): string {
  return new Date(Date.UTC(2026, 10, 22, 9, 0, factNumber)).toISOString();
}

/** Mutable state shared by both browser contexts; safe to assert in the test. */
export interface SyntheticPharmacyStore {
  /** The routed prescription, or null before the prescriber routes one. */
  prescription: InboxItemResponse | null;
  /** Append-only status facts in arrival order. */
  events: StatusEventResponse[];
  /** The clarification thread created by the pharmacy, once it exists. */
  thread: ThreadViewResponse | null;
  /** The substitution proposal created by the pharmacy, once it exists. */
  proposal: ProposalViewResponse | null;
  /** `METHOD /path` for every intercepted request, in arrival order. */
  calls: string[];
}

/** Handle used by the journey to install routes and inspect shared state. */
export interface SyntheticPharmacyApi {
  /** Shared mutable store behind both roles. */
  readonly store: SyntheticPharmacyStore;
  /** Alias of `store.calls` for journeys that assert a flat command log. */
  readonly calls: string[];
  /** Intercept pharmacy requests on one role's page and supply its CSRF cookie. */
  installOn(page: Page, baseURL: string | undefined): Promise<void>;
}

/** Respond with a JSON body through the intercepted route. */
async function json(route: Route, body: unknown, status = 200): Promise<void> {
  await route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  });
}

/** Build the current status projection from the append-only fact trail. */
function statusView(store: SyntheticPharmacyStore): StatusViewResponse {
  const prescription = store.prescription;
  if (prescription === null) {
    throw new Error('a prescription must be routed before its status can be read');
  }
  return {
    organizationId,
    prescriptionId: prescription.id,
    version: prescription.version,
    accepted: store.events.some((event) => event.type === 'accepted'),
    dispensed: store.events.some((event) => event.type === 'dispensed'),
    events: [...store.events],
  };
}

/**
 * Append one immutable fact and advance the prescription version. Acceptance,
 * clarification, substitution and dispensing are never collapsed into one
 * another. Fact identifiers are stable names so the timeline rows can be
 * addressed by meaning rather than by arrival position.
 */
function appendEvent(
  store: SyntheticPharmacyStore,
  type: string,
  detail: string | null,
  evidence: { dispensedBy: string; dispensedAt: string; handedOver: string } | null = null,
): StatusEventResponse {
  const prescription = store.prescription;
  if (prescription === null) {
    throw new Error('a prescription must be routed before a fact can be appended');
  }
  const version = store.events.length + 1;
  const event: StatusEventResponse = {
    id: factIds[type] ?? `event-synthetic-${type}`,
    organizationId,
    prescriptionId: prescription.id,
    version,
    type,
    actorId: type === 'submitted' || type === 'substitution_approved'
      ? 'synthetic.prescriber'
      : type === 'delivery_confirmed'
        ? 'synthetic.partner'
        : 'synthetic.pharmacist',
    occurredAt: instantFor(factNumbers[type] ?? version),
    detail,
    dispensedBy: evidence?.dispensedBy ?? null,
    dispensedAt: evidence?.dispensedAt ?? null,
    handedOver: evidence?.handedOver ?? null,
  };
  prescription.version = version;
  store.events.push(event);
  return event;
}

/**
 * Create the prescription and its two delivery facts. Both the single pharmacy
 * route command and the two logical prescription commands resolve here, so the
 * shared fact trail is identical whichever entry point the harness uses.
 */
function routePrescription(store: SyntheticPharmacyStore): InboxItemResponse {
  const prescription: InboxItemResponse = {
    ...syntheticInboxItem,
    version: 1,
    state: 'submitted',
    createdAt: occurredAt,
  };
  store.prescription = prescription;
  appendEvent(store, 'submitted', null);
  appendEvent(store, 'delivery_confirmed', 'Synthetic partner confirmed receipt');
  prescription.state = 'delivery_confirmed';
  return prescription;
}

/** Create the pharmacy's clarification thread with its single opening question. */
function openClarification(store: SyntheticPharmacyStore, question: string): ThreadViewResponse {
  const thread: ThreadViewResponse = {
    thread: { ...syntheticOpenThread, id: threadId, organizationId, version: 1 },
    entries: [{ ...syntheticQuestionEntry, threadId, body: question }],
    overdue: false,
  };
  store.thread = thread;
  return thread;
}

/** Create the pharmacy's pending, review-only substitution proposal. */
function openSubstitution(store: SyntheticPharmacyStore): ProposalViewResponse {
  const proposal: ProposalViewResponse = {
    proposal: {
      ...syntheticProposal,
      id: proposalId,
      organizationId,
      original: originalEntry,
      proposed: proposedEntry,
      state: 'pending',
      version: 1,
      proposedAt: occurredAt,
    },
    appliedRevision: null,
  };
  store.proposal = proposal;
  return proposal;
}

/** Read one JSON command body, treating an absent body as an empty object. */
function bodyOf(request: { postData(): string | null }): Record<string, unknown> {
  return JSON.parse(request.postData() ?? '{}') as Record<string, unknown>;
}

/**
 * True when a command carries an `If-Match` for a different version than the
 * current projection. Real writes always send this precondition; the raw
 * proposal-creation command in the journey does not and is not checked.
 */
function isStale(
  request: { headers(): Record<string, string> },
  expectedVersion: number,
): boolean {
  // Playwright exposes route request headers as a lower-cased plain object.
  const header = request.headers()['if-match'];
  return header !== undefined && Number(header) !== expectedVersion;
}

/** Fulfil a stale write with the machine-readable version-conflict code. */
async function refuseStale(route: Route): Promise<void> {
  await json(route, { code: 'VERSION_CONFLICT' }, 412);
}

/** Record the logical prescription commands behind one pharmacy route command. */
function recordRoutingCommands(store: SyntheticPharmacyStore): void {
  store.calls.push(`POST /api/v1/prescriptions/${prescriptionId}/transmit`);
  store.calls.push(`POST /api/v1/prescriptions/${prescriptionId}/confirm-delivery`);
}

/** Record the long proposal path when the journey used the short one, or vice versa. */
function recordProposalCommand(store: SyntheticPharmacyStore, shortFormUsed: boolean): void {
  if (shortFormUsed) {
    store.calls.push(`POST /api/v1/pharmacy/substitutions/${prescriptionId}/proposals`);
  } else {
    store.calls.push('POST /api/v1/pharmacy/substitutions');
  }
}

/** Install one stateful route handler on one role's page. */
async function install(
  page: Page,
  baseURL: string | undefined,
  store: SyntheticPharmacyStore,
): Promise<void> {
  await page.context().addCookies([
    {
      name: 'XSRF-TOKEN',
      value: 'synthetic-csrf-token',
      url: baseURL ?? 'http://127.0.0.1:5173',
    },
  ]);

  const handler = async (route: Route): Promise<void> => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const method = request.method();
    store.calls.push(`${method} ${path}`);

    // The two logical prescription commands: transmit creates the shared
    // prescription and delivery facts; confirm-delivery acknowledges them.
    const transmit = /^\/api\/v1\/prescriptions\/([^/]+)\/transmit$/.exec(path);
    if (transmit !== null && method === 'POST') {
      if (decodeURIComponent(transmit[1] ?? '') !== prescriptionId) {
        await json(route, { code: 'NOT_FOUND' }, 404);
        return;
      }
      const item = routePrescription(store);
      store.calls.push(`POST /api/v1/prescriptions/${prescriptionId}/confirm-delivery`);
      await json(route, { id: item.id, version: item.version, state: item.state });
      return;
    }
    const confirm = /^\/api\/v1\/prescriptions\/([^/]+)\/confirm-delivery$/.exec(path);
    if (confirm !== null && method === 'POST') {
      if (decodeURIComponent(confirm[1] ?? '') !== prescriptionId) {
        await json(route, { code: 'NOT_FOUND' }, 404);
        return;
      }
      await json(route, {
        id: prescriptionId,
        version: store.prescription?.version ?? 2,
        state: 'delivery_confirmed',
      });
      return;
    }

    if (path === '/api/v1/pharmacy/inbox' && method === 'GET') {
      const status = url.searchParams.get('status');
      const items =
        store.prescription === null
          ? []
          : status === null || status === 'all' || status === store.prescription.state
            ? [store.prescription]
            : [];
      const inboxPage: InboxPageResponse = { items, nextCursor: null };
      await json(route, inboxPage);
      return;
    }

    if (path === '/api/v1/pharmacy/inbox/route' && method === 'POST') {
      const item = routePrescription(store);
      recordRoutingCommands(store);
      await json(route, item);
      return;
    }

    const inboxMatch = /^\/api\/v1\/pharmacy\/inbox\/([^/]+)$/.exec(path);
    if (inboxMatch !== null && method === 'GET') {
      const item = store.prescription;
      if (item === null || decodeURIComponent(inboxMatch[1] ?? '') !== item.id) {
        await json(route, { code: 'NOT_FOUND' }, 404);
        return;
      }
      await json(route, item);
      return;
    }

    const statusRead = /^\/api\/v1\/pharmacy\/status\/([^/]+)$/.exec(path);
    if (statusRead !== null && method === 'GET') {
      if (store.prescription === null ||
          decodeURIComponent(statusRead[1] ?? '') !== store.prescription.id) {
        await json(route, { code: 'NOT_FOUND' }, 404);
        return;
      }
      await json(route, statusView(store));
      return;
    }

    const statusCommand = /^\/api\/v1\/pharmacy\/status\/([^/]+)\/([a-z-]+)$/.exec(path);
    if (statusCommand !== null && method === 'POST' && store.prescription !== null) {
      const action = statusCommand[2];
      if (decodeURIComponent(statusCommand[1] ?? '') !== store.prescription.id) {
        await json(route, { code: 'NOT_FOUND' }, 404);
        return;
      }
      if (isStale(request, store.prescription.version)) {
        await refuseStale(route);
        return;
      }
      const body = bodyOf(request);
      if (action === 'clarification') {
        const question = String(body['question'] ?? '');
        const event = appendEvent(store, 'clarification', question);
        store.prescription.state = 'clarification';
        openClarification(store, question);
        await json(route, event);
        return;
      }
      if (action === 'accept') {
        const event = appendEvent(store, 'accepted', null);
        store.prescription.state = 'accepted';
        await json(route, event);
        return;
      }
      if (action === 'reject') {
        const event = appendEvent(store, 'rejected', String(body['reason'] ?? ''));
        store.prescription.state = 'rejected';
        await json(route, event);
        return;
      }
      if (action === 'dispensing-evidence') {
        const event = appendEvent(store, 'dispensed', null, {
          dispensedBy: String(body['dispensedBy'] ?? ''),
          dispensedAt: String(body['dispensedAt'] ?? ''),
          handedOver: String(body['handedOver'] ?? ''),
        });
        store.prescription.state = 'dispensed';
        await json(route, event);
        return;
      }
    }

    const threadRead = /^\/api\/v1\/pharmacy\/clarifications\/([^/]+)$/.exec(path);
    if (threadRead !== null && method === 'GET' && store.thread !== null) {
      if (decodeURIComponent(threadRead[1] ?? '') === store.thread.thread.id) {
        await json(route, store.thread);
        return;
      }
    }

    const threadAnswer = /^\/api\/v1\/pharmacy\/clarifications\/([^/]+)\/answer$/.exec(path);
    if (threadAnswer !== null && method === 'POST' && store.thread !== null) {
      const thread = store.thread;
      if (decodeURIComponent(threadAnswer[1] ?? '') === thread.thread.id) {
        if (isStale(request, thread.thread.version)) {
          await refuseStale(route);
          return;
        }
        const body = bodyOf(request);
        const answerBody = String(body['answerBody'] ?? '');
        const amendedRevisionId = String(body['amendedRevisionId'] ?? '');
        // The answer must follow the question even though no new status fact
        // accompanies it, so derive its instant from the fact trail, not from
        // the number of entries already on the thread.
        const answeredAt = instantFor(store.events.length + 2);
        thread.thread = {
          ...thread.thread,
          state: 'answered',
          version: thread.thread.version + 1,
          answerBody,
          amendedRevisionId,
          answeredAt,
          answeredBy: 'synthetic.prescriber',
        };
        thread.entries = [
          ...thread.entries,
          {
            ...syntheticAnswerEntry,
            id: 'clarification-entry-answer',
            body: answerBody,
            amendedRevisionId,
            occurredAt: answeredAt,
          },
        ];
        await json(route, thread);
        return;
      }
    }

    if (path === '/api/v1/pharmacy/substitutions' && method === 'POST') {
      recordProposalCommand(store, true);
      await json(route, openSubstitution(store));
      return;
    }
    const proposalCreate = /^\/api\/v1\/pharmacy\/substitutions\/([^/]+)\/proposals$/.exec(path);
    if (proposalCreate !== null && method === 'POST') {
      if (decodeURIComponent(proposalCreate[1] ?? '') !== prescriptionId) {
        await json(route, { code: 'NOT_FOUND' }, 404);
        return;
      }
      recordProposalCommand(store, false);
      await json(route, openSubstitution(store));
      return;
    }

    const proposalRead = /^\/api\/v1\/pharmacy\/substitutions\/([^/]+)$/.exec(path);
    if (proposalRead !== null && method === 'GET' && store.proposal !== null) {
      if (decodeURIComponent(proposalRead[1] ?? '') === store.proposal.proposal.id) {
        await json(route, store.proposal);
        return;
      }
    }

    const proposalCommand = /^\/api\/v1\/pharmacy\/substitutions\/([^/]+)\/(approve|decline)$/.exec(
      path,
    );
    if (proposalCommand !== null && method === 'POST' && store.proposal !== null) {
      const proposal = store.proposal;
      if (decodeURIComponent(proposalCommand[1] ?? '') === proposal.proposal.id) {
        if (isStale(request, proposal.proposal.version)) {
          await refuseStale(route);
          return;
        }
        if (proposalCommand[2] === 'approve') {
          proposal.proposal = { ...proposal.proposal, state: 'approved' };
          proposal.appliedRevision = syntheticSignedRevision;
          if (store.prescription !== null) {
            store.prescription.medication = syntheticSignedRevision.replacement.tradeName;
          }
          appendEvent(
            store,
            'substitution_approved',
            `Signed revision ${syntheticSignedRevision.id}`,
          );
        } else {
          proposal.proposal = { ...proposal.proposal, state: 'declined', note: null };
          proposal.appliedRevision = null;
        }
        await json(route, proposal);
        return;
      }
    }

    await json(route, { code: 'NOT_FOUND' }, 404);
  };

  await page.route(/\/api\/v1\/pharmacy(?:\/|\?|$)/, handler);
  await page.route(/\/api\/v1\/prescriptions(?:\/|\?|$)/, handler);
}

/** Build one fresh synthetic pharmacy API with its own shared store. */
export function createSyntheticPharmacyApi(): SyntheticPharmacyApi {
  const store: SyntheticPharmacyStore = {
    prescription: null,
    events: [],
    thread: null,
    proposal: null,
    calls: [],
  };
  return {
    store,
    calls: store.calls,
    installOn: (page, baseURL) => install(page, baseURL, store),
  };
}

/**
 * Install the shared synthetic API on one page using the flat command log.
 * Kept alongside `installOn` so a journey can assert through `api.calls`.
 */
export async function installSyntheticPharmacyApi(
  page: Page,
  baseURL: string | undefined,
  api: SyntheticPharmacyApi,
): Promise<void> {
  await api.installOn(page, baseURL);
}
