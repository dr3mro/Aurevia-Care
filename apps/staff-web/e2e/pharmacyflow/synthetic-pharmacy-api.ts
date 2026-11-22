import type { Page, Route } from '@playwright/test';
import type {
  InboxItemResponse,
  InboxPageResponse,
  StatusEventResponse,
  StatusViewResponse,
} from '../../src/features/pharmacyinbox/types';
import type {
  CatalogEntryView,
  ProposalViewResponse,
  ThreadViewResponse,
} from '../../src/features/prescriberstatus/types';
import { syntheticFacility } from '../../src/fixtures/synthetic';

/**
 * Deterministic in-browser stand-in for the pharmacy boundary (RX-02, WEB-09).
 *
 * The two roles drive real staff-web screens and the real typed API clients. Only
 * `/api/v1/pharmacy/**` is intercepted, and both browser contexts share one
 * in-memory store so a command from the prescriber is visible to the pharmacy and
 * vice versa. Every status fact is append-only and acceptance, dispensing and
 * clarification stay separate facts, mirroring the domain invariant. No patient
 * record, partner, credential or real identifier is contacted or stored here.
 */

/** The synthetic tenant shared by every fixture in this harness. */
const organizationId = syntheticFacility.organizationId;

/** Opaque identifiers used by the synthetic clinical objects. */
const prescriptionId = 'rx-synthetic-001';
const threadId = 'thread-synthetic-001';
const proposalId = 'proposal-synthetic-001';

/** One original catalog entry in the synthetic substitution proposal. */
const originalEntry: CatalogEntryView = {
  entryId: 'catalog-original',
  version: 2,
  genericName: 'Synthetic Amoxicillin',
  tradeName: 'Syntheticmox',
  formulation: 'tablet',
  strengthValue: '500',
  strengthUnit: 'mg',
};

/** One tagged alternative proposed by the synthetic pharmacy. */
const proposedEntry: CatalogEntryView = {
  ...originalEntry,
  entryId: 'catalog-proposed',
  version: 1,
  tradeName: 'Syntheticmox Alternative',
  formulation: 'capsule',
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
  /** `METHOD /path` for every intercepted pharmacy request, in arrival order. */
  calls: string[];
}

/** Handle used by the journey to install routes and inspect shared state. */
export interface SyntheticPharmacyApi {
  /** Shared mutable store behind both roles. */
  readonly store: SyntheticPharmacyStore;
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
 * clarification and dispensing are never collapsed into one another.
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
    id: `event-synthetic-${version}`,
    organizationId,
    prescriptionId: prescription.id,
    version,
    type,
    actorId: type === 'submitted' ? 'synthetic.prescriber' : 'synthetic.pharmacist',
    occurredAt: instantFor(version),
    detail,
    dispensedBy: evidence?.dispensedBy ?? null,
    dispensedAt: evidence?.dispensedAt ?? null,
    handedOver: evidence?.handedOver ?? null,
  };
  prescription.version = version;
  store.events.push(event);
  return event;
}

/** Create the prescription and its two delivery facts when the prescriber routes. */
function routePrescription(store: SyntheticPharmacyStore): InboxItemResponse {
  const prescription: InboxItemResponse = {
    id: prescriptionId,
    version: 1,
    patientId: 'SYN-PATIENT-001',
    medication: 'Synthetic Amoxicillin',
    strength: '500 mg',
    dose: '500 mg',
    route: 'oral',
    frequency: 'twice daily',
    quantity: 20,
    state: 'submitted',
    submissionId: 'submission-synthetic-001',
    prescriberSubject: 'synthetic.prescriber',
    destinationPharmacy: 'synthetic-pharmacy',
    createdAt: instantFor(1),
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
    thread: {
      id: threadId,
      organizationId,
      prescriptionId,
      requesterSubject: 'synthetic.pharmacist',
      prescriberSubject: 'synthetic.prescriber',
      state: 'open',
      openedAt: instantFor(store.events.length + 1),
      version: 1,
      answerBody: null,
      amendedRevisionId: null,
      answeredAt: null,
      answeredBy: null,
    },
    entries: [
      {
        id: 'clarification-entry-question',
        threadId,
        sequence: 1,
        authorParty: 'pharmacy',
        authorSubject: 'synthetic.pharmacist',
        body: question,
        amendedRevisionId: null,
        occurredAt: instantFor(store.events.length + 1),
      },
    ],
    overdue: false,
  };
  store.thread = thread;
  return thread;
}

/** Create the pharmacy's pending, review-only substitution proposal. */
function openSubstitution(store: SyntheticPharmacyStore): ProposalViewResponse {
  const proposal: ProposalViewResponse = {
    proposal: {
      id: proposalId,
      prescriptionId,
      patientId: 'SYN-PATIENT-001',
      prescriberSubject: 'synthetic.prescriber',
      proposedBy: 'synthetic.pharmacist',
      original: originalEntry,
      proposed: proposedEntry,
      reason: 'stock',
      note: 'Synthetic stock note',
      state: 'pending',
      version: 1,
      proposedAt: instantFor(store.events.length + 1),
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
 * substitution-creation command in the journey does not and is not checked.
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

  await page.route(/\/api\/v1\/pharmacy(?:\/|\?|$)/, async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const method = request.method();
    store.calls.push(`${method} ${path}`);

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
      await json(route, routePrescription(store));
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
    if (statusRead !== null && method === 'GET' && store.prescription !== null) {
      if (decodeURIComponent(statusRead[1] ?? '') !== store.prescription.id) {
        await json(route, { code: 'NOT_FOUND' }, 404);
        return;
      }
      await json(route, statusView(store));
      return;
    }

    const statusCommand = /^\/api\/v1\/pharmacy\/status\/([^/]+)\/([a-z-]+)$/.exec(path);
    if (statusCommand !== null && method === 'POST' && store.prescription !== null) {
      const action = statusCommand[2];
      const body = bodyOf(request);
      if (decodeURIComponent(statusCommand[1] ?? '') !== store.prescription.id) {
        await json(route, { code: 'NOT_FOUND' }, 404);
        return;
      }
      if (isStale(request, store.prescription.version)) {
        await refuseStale(route);
        return;
      }
      if (action === 'clarification') {
        const event = appendEvent(store, 'clarification', String(body['question'] ?? ''));
        store.prescription.state = 'clarification';
        openClarification(store, String(body['question'] ?? ''));
        await json(route, event);
        return;
      }
      if (action === 'accept') {
        const event = appendEvent(store, 'accepted', null);
        store.prescription.state = 'accepted';
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
        // accompanies it, so derive its instant from the fact trail, not the
        // number of entries already on the thread.
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
            id: 'clarification-entry-answer',
            threadId,
            sequence: thread.entries.length + 1,
            authorParty: 'prescriber',
            authorSubject: 'synthetic.prescriber',
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
          proposal.appliedRevision = {
            id: 'revision-synthetic-001',
            revisionNumber: 4,
            replacement: { ...proposedEntry, version: 2 },
            approvedBy: 'synthetic.prescriber',
            signedAt: instantFor(store.events.length + 2),
          };
        } else {
          proposal.proposal = { ...proposal.proposal, state: 'declined', note: null };
          proposal.appliedRevision = null;
        }
        await json(route, proposal);
        return;
      }
    }

    await json(route, { code: 'NOT_FOUND' }, 404);
  });
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
    installOn: (page, baseURL) => install(page, baseURL, store),
  };
}
