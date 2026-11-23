import type { Page, Request, Route } from '@playwright/test';
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
const originalEntry: CatalogEntryView = syntheticProposal.original;

/** The tagged alternative proposed by the synthetic pharmacy. */
const proposedEntry: CatalogEntryView = syntheticProposal.proposed;

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

/** The synthetic actor that records each kind of fact. */
function actorFor(type: string): string {
  switch (type) {
    case 'submitted':
    case 'substitution_approved':
      return 'synthetic.prescriber';
    case 'delivery_confirmed':
      return 'synthetic.partner';
    default:
      return 'synthetic.pharmacist';
  }
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
    actorId: actorFor(type),
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
function isStale(request: { headers(): Record<string, string> }, expectedVersion: number): boolean {
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

/** Everything one intercepted request needs to be answered. */
interface RequestContext {
  route: Route;
  request: Request;
  url: URL;
  path: string;
  method: string;
  store: SyntheticPharmacyStore;
}

/** A route handler answers the request and returns true, or declines with false. */
type RouteHandler = (context: RequestContext) => Promise<boolean>;

/** Render one JSON body field as text, treating an absent field as empty. */
function textOf(value: unknown): string {
  switch (typeof value) {
    case 'string':
      return value;
    case 'number':
    case 'boolean':
    case 'bigint':
      return String(value);
    case 'symbol':
    case 'undefined':
    case 'object':
    case 'function':
    default:
      return '';
  }
}

/** Answer a request for an object that is not the one held in the store. */
async function notFound({ route }: RequestContext): Promise<true> {
  await json(route, { code: 'NOT_FOUND' }, 404);
  return true;
}

/** True when the request is `method` and `path` matches; returns the match. */
function matchPath(
  { method, path }: RequestContext,
  verb: string,
  pattern: RegExp,
): RegExpExecArray | null {
  return method === verb ? pattern.exec(path) : null;
}

/** Decode the first captured path segment. */
function idFrom(match: RegExpExecArray): string {
  return decodeURIComponent(match[1] ?? '');
}

/** transmit creates the shared prescription and delivery facts. */
const handleTransmit: RouteHandler = async (context) => {
  const match = matchPath(context, 'POST', /^\/api\/v1\/prescriptions\/([^/]+)\/transmit$/);
  if (match === null) {
    return false;
  }
  if (idFrom(match) !== prescriptionId) {
    return notFound(context);
  }
  const item = routePrescription(context.store);
  context.store.calls.push(`POST /api/v1/prescriptions/${prescriptionId}/confirm-delivery`);
  await json(context.route, { id: item.id, version: item.version, state: item.state });
  return true;
};

/** confirm-delivery acknowledges the facts transmit created. */
const handleConfirmDelivery: RouteHandler = async (context) => {
  const match = matchPath(context, 'POST', /^\/api\/v1\/prescriptions\/([^/]+)\/confirm-delivery$/);
  if (match === null) {
    return false;
  }
  if (idFrom(match) !== prescriptionId) {
    return notFound(context);
  }
  await json(context.route, {
    id: prescriptionId,
    version: context.store.prescription?.version ?? 2,
    state: 'delivery_confirmed',
  });
  return true;
};

/** The pharmacy inbox, filtered by the optional status query. */
const handleInboxList: RouteHandler = async ({ route, url, path, method, store }) => {
  if (path !== '/api/v1/pharmacy/inbox' || method !== 'GET') {
    return false;
  }
  const status = url.searchParams.get('status');
  const prescription = store.prescription;
  const visible =
    prescription !== null && (status === null || status === 'all' || status === prescription.state);
  const inboxPage: InboxPageResponse = {
    items: visible ? [prescription] : [],
    nextCursor: null,
  };
  await json(route, inboxPage);
  return true;
};

/** The single pharmacy route command. */
const handleInboxRoute: RouteHandler = async ({ route, path, method, store }) => {
  if (path !== '/api/v1/pharmacy/inbox/route' || method !== 'POST') {
    return false;
  }
  const item = routePrescription(store);
  recordRoutingCommands(store);
  await json(route, item);
  return true;
};

/** One inbox item by id. */
const handleInboxItem: RouteHandler = async (context) => {
  const match = matchPath(context, 'GET', /^\/api\/v1\/pharmacy\/inbox\/([^/]+)$/);
  if (match === null) {
    return false;
  }
  const item = context.store.prescription;
  if (idFrom(match) !== item?.id) {
    return notFound(context);
  }
  await json(context.route, item);
  return true;
};

/** The status projection for one prescription. */
const handleStatusRead: RouteHandler = async (context) => {
  const match = matchPath(context, 'GET', /^\/api\/v1\/pharmacy\/status\/([^/]+)$/);
  if (match === null) {
    return false;
  }
  const { store } = context;
  if (idFrom(match) !== store.prescription?.id) {
    return notFound(context);
  }
  await json(context.route, statusView(store));
  return true;
};

/** Append the status fact for one pharmacy command; undefined when unknown. */
function applyStatusAction(
  store: SyntheticPharmacyStore,
  prescription: InboxItemResponse,
  action: string | undefined,
  body: Record<string, unknown>,
): StatusEventResponse | undefined {
  switch (action) {
    case undefined:
      return undefined;
    case 'clarification': {
      const question = textOf(body.question);
      const event = appendEvent(store, 'clarification', question);
      prescription.state = 'clarification';
      openClarification(store, question);
      return event;
    }
    case 'accept': {
      const event = appendEvent(store, 'accepted', null);
      prescription.state = 'accepted';
      return event;
    }
    case 'reject': {
      const event = appendEvent(store, 'rejected', textOf(body.reason));
      prescription.state = 'rejected';
      return event;
    }
    case 'dispensing-evidence': {
      const event = appendEvent(store, 'dispensed', null, {
        dispensedBy: textOf(body.dispensedBy),
        dispensedAt: textOf(body.dispensedAt),
        handedOver: textOf(body.handedOver),
      });
      prescription.state = 'dispensed';
      return event;
    }
    default:
      return undefined;
  }
}

/** Pharmacy status commands: clarification, accept, reject, dispensing evidence. */
const handleStatusCommand: RouteHandler = async (context) => {
  const match = matchPath(context, 'POST', /^\/api\/v1\/pharmacy\/status\/([^/]+)\/([a-z-]+)$/);
  const { store, request, route } = context;
  const prescription = store.prescription;
  if (match === null || prescription === null) {
    return false;
  }
  if (idFrom(match) !== prescription.id) {
    return notFound(context);
  }
  if (isStale(request, prescription.version)) {
    await refuseStale(route);
    return true;
  }
  const event = applyStatusAction(store, prescription, match[2], bodyOf(request));
  if (event === undefined) {
    return false;
  }
  await json(route, event);
  return true;
};

/** A clarification thread by id; other ids fall through to the 404. */
const handleThreadRead: RouteHandler = async (context) => {
  const match = matchPath(context, 'GET', /^\/api\/v1\/pharmacy\/clarifications\/([^/]+)$/);
  const { thread } = context.store;
  if (match === null || idFrom(match) !== thread?.thread.id) {
    return false;
  }
  await json(context.route, thread);
  return true;
};

/** The prescriber's answer, which follows the question on the thread. */
const handleThreadAnswer: RouteHandler = async (context) => {
  const match = matchPath(
    context,
    'POST',
    /^\/api\/v1\/pharmacy\/clarifications\/([^/]+)\/answer$/,
  );
  const { store, request, route } = context;
  const thread = store.thread;
  if (match === null || idFrom(match) !== thread?.thread.id) {
    return false;
  }
  if (isStale(request, thread.thread.version)) {
    await refuseStale(route);
    return true;
  }
  const body = bodyOf(request);
  const answerBody = textOf(body.answerBody);
  const amendedRevisionId = textOf(body.amendedRevisionId);
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
  return true;
};

/** The short proposal-creation command. */
const handleProposalShortCreate: RouteHandler = async ({ route, path, method, store }) => {
  if (path !== '/api/v1/pharmacy/substitutions' || method !== 'POST') {
    return false;
  }
  recordProposalCommand(store, true);
  await json(route, openSubstitution(store));
  return true;
};

/** The long proposal-creation command, scoped to the prescription. */
const handleProposalCreate: RouteHandler = async (context) => {
  const match = matchPath(
    context,
    'POST',
    /^\/api\/v1\/pharmacy\/substitutions\/([^/]+)\/proposals$/,
  );
  if (match === null) {
    return false;
  }
  if (idFrom(match) !== prescriptionId) {
    return notFound(context);
  }
  recordProposalCommand(context.store, false);
  await json(context.route, openSubstitution(context.store));
  return true;
};

/** A substitution proposal by id; other ids fall through to the 404. */
const handleProposalRead: RouteHandler = async (context) => {
  const match = matchPath(context, 'GET', /^\/api\/v1\/pharmacy\/substitutions\/([^/]+)$/);
  const { proposal } = context.store;
  if (match === null || idFrom(match) !== proposal?.proposal.id) {
    return false;
  }
  await json(context.route, proposal);
  return true;
};

/** Apply the prescriber's decision to the proposal and the prescription. */
function decideProposal(
  store: SyntheticPharmacyStore,
  proposal: ProposalViewResponse,
  decision: string | undefined,
): void {
  if (decision === 'approve') {
    proposal.proposal = { ...proposal.proposal, state: 'approved' };
    proposal.appliedRevision = syntheticSignedRevision;
    if (store.prescription !== null) {
      store.prescription.medication = syntheticSignedRevision.replacement.tradeName;
    }
    appendEvent(store, 'substitution_approved', `Signed revision ${syntheticSignedRevision.id}`);
  } else {
    proposal.proposal = { ...proposal.proposal, state: 'declined', note: null };
    proposal.appliedRevision = null;
  }
}

/** Approve or decline a substitution proposal. */
const handleProposalCommand: RouteHandler = async (context) => {
  const match = matchPath(
    context,
    'POST',
    /^\/api\/v1\/pharmacy\/substitutions\/([^/]+)\/(approve|decline)$/,
  );
  const { store, request, route } = context;
  const proposal = store.proposal;
  if (match === null || idFrom(match) !== proposal?.proposal.id) {
    return false;
  }
  if (isStale(request, proposal.proposal.version)) {
    await refuseStale(route);
    return true;
  }
  decideProposal(store, proposal, match[2]);
  await json(route, proposal);
  return true;
};

/** Handlers in match order; the first one that answers wins. */
const handlers: readonly RouteHandler[] = [
  handleTransmit,
  handleConfirmDelivery,
  handleInboxList,
  handleInboxRoute,
  handleInboxItem,
  handleStatusRead,
  handleStatusCommand,
  handleThreadRead,
  handleThreadAnswer,
  handleProposalShortCreate,
  handleProposalCreate,
  handleProposalRead,
  handleProposalCommand,
];

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
    const method = request.method();
    store.calls.push(`${method} ${url.pathname}`);
    const context: RequestContext = { route, request, url, path: url.pathname, method, store };
    for (const candidate of handlers) {
      if (await candidate(context)) {
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
