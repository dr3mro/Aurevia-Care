import type { Page, Request, Route } from '@playwright/test';

/**
 * Deterministic, in-browser stand-in for the staff-messaging boundary
 * (MSG-02, MSG-03, WEB-09).
 *
 * Three synthetic staff views — two in the home facility and one in a second
 * facility of the same organization — share one append-only store, so a
 * command written in one browser context is visible to the others. Only the
 * published `/api/v1/staff/**` paths are intercepted. Every subject, file name,
 * identifier and reference is synthetic; no real record, credential or partner
 * endpoint is contacted. The store also records `METHOD /path` so a journey can
 * prove what was actually sent rather than only what a screen rendered.
 */

/** The single synthetic tenant shared by all three views. */
export const organizationId = 'org-synthetic-staffmsg';

/** Home facility: the instant conversation and the mail author live here. */
export const homeFacilityId = 'facility-synthetic-main';

/** Second facility of the same tenant: the Cc recipient lives here. */
export const otherFacilityId = 'facility-synthetic-north';

/** Synthetic author in the home facility. */
export const authorSubject = 'synthetic-staff-nora';

/** Synthetic instant-chat peer in the home facility. */
export const peerSubject = 'synthetic-staff-omar';

/** Synthetic mail recipient in the second facility. */
export const remoteSubject = 'synthetic-staff-layla';

/** The one home-facility instant conversation. */
export const instantConversationId = 'synthetic-instant-main';

/** Opaque reference handle shared by every view in a journey. */
export const referenceId = 'synthetic-reference-001';

/** Opaque patient handle. It is hidden from viewers without access. */
export const syntheticPatientId = 'synthetic-patient-target';

/** Non-identifying label returned by the label-only reference view. */
export const referenceLabel = 'Synthetic chart reference';

/** A clean staged document and a quarantined one. */
export const cleanDocumentId = 'synthetic-document-clean';
export const quarantinedDocumentId = 'synthetic-document-quarantined';
export const cleanFileName = 'synthetic-visit-summary.pdf';
export const quarantinedFileName = 'synthetic-untrusted.png';

/** Stable file names for the two opaque document handles. */
const documentNames: Record<string, string> = {
  [cleanDocumentId]: cleanFileName,
  [quarantinedDocumentId]: quarantinedFileName,
};

/** Fixed synthetic instants; the clock is never read by the stub. */
const originalSentAt = '2026-03-10T09:00:00Z';
const replySentAt = '2026-03-10T11:00:00Z';

/** One published instant message, with the clean file names it carried. */
export interface InstantRecord {
  readonly id: string;
  readonly conversationId: string;
  readonly senderSubject: string;
  readonly body: string;
  readonly sentAt: string;
  readonly attachmentNames: readonly string[];
}

/** One published mail message and the viewers it is visible to. */
export interface MailRecord {
  readonly id: string;
  readonly subject: string;
  readonly body: string;
  readonly senderSubject: string;
  readonly to: readonly string[];
  readonly cc: readonly string[];
  readonly attachmentNames: readonly string[];
  readonly sentAt: string;
  readonly folder: 'inbox' | 'sent';
  readonly owner: string;
}

/** A private versioned draft created by the real mail page. */
interface DraftRecord {
  readonly id: string;
  readonly owner: string;
  version: number;
  subject: string;
  body: string;
  to: string[];
  cc: string[];
  attachments: string[];
}

/** The tenant and write headers captured for one intercepted request. */
export interface CapturedRequest {
  readonly method: string;
  readonly path: string;
  readonly tenant: string | undefined;
  readonly csrf: string | undefined;
  readonly idempotencyKey: string | undefined;
}

/** Mutable state shared by every browser view in one journey. */
export interface StaffMessageStore {
  /** Published instant messages in arrival order. */
  readonly messages: InstantRecord[];
  /** Published mail in arrival order. */
  readonly mails: MailRecord[];
  /** Private drafts keyed by draft id. */
  readonly drafts: Map<string, DraftRecord>;
  /** Append-only facts: send, publish, quarantine and access decisions. */
  readonly facts: string[];
  /** `METHOD /path` for every intercepted request, in arrival order. */
  readonly calls: string[];
  /** Tenant and write headers for every request, aligned with `calls`. */
  readonly requests: CapturedRequest[];
  /** Number of drafts and messages created, used for stable synthetic ids. */
  serial: number;
}

/** Handle used by a journey to install routes and read the shared store. */
export interface StaffMessageApi {
  /** Shared store behind all views. */
  readonly store: StaffMessageStore;
  /** Alias of `store.calls` for a flat command log. */
  readonly calls: string[];
  /** Intercept staff-messaging requests for one view and bind its identity. */
  installOn(page: Page, baseURL: string | undefined, viewer: string): Promise<void>;
}

/** Fulfil a request with a JSON body. */
async function json(route: Route, body: unknown, status = 200): Promise<void> {
  await route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  });
}

/** Read one command body, treating an absent body as an empty object. */
function bodyOf(request: Request): Record<string, unknown> {
  return JSON.parse(request.postData() ?? '{}') as Record<string, unknown>;
}

/** Render one body field as text without trusting its runtime type. */
function textOf(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

/** Render one body field list as tenant-local string values. */
function valuesOf(value: unknown): string[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.flatMap((entry) => {
    if (typeof entry === 'string') {
      return [entry];
    }
    if (typeof entry !== 'object' || entry === null) {
      return [];
    }
    const candidate = (entry as Record<string, unknown>).value;
    return typeof candidate === 'string' ? [candidate] : [];
  });
}

/** Everything one intercepted request needs to be answered. */
interface RequestContext {
  readonly route: Route;
  readonly request: Request;
  readonly path: string;
  readonly method: string;
  readonly viewer: string;
  readonly store: StaffMessageStore;
}

/** A route group answers a request and returns true, or declines with false. */
type RouteGroup = (context: RequestContext) => Promise<boolean>;

/** True when a request carries the owning tenant, otherwise 403. */
function isOwningTenant(request: Request): boolean {
  return request.headers()['x-active-organization'] === organizationId;
}

/** Decline an unscoped or foreign-tenant request without revealing anything. */
async function refuse(context: RequestContext): Promise<boolean> {
  await json(context.route, { code: 'FORBIDDEN' }, 403);
  return true;
}

/** Append one immutable fact to the shared trail. */
function record(store: StaffMessageStore, fact: string): void {
  store.facts.push(fact);
}

/**
 * Names from the most recent successful attachment publication. The harness
 * publishes named files before it sends the instant message, so the message
 * carries exactly the files that passed the scan gate.
 */
function publishedFileNames(store: StaffMessageStore): string[] {
  for (let index = store.facts.length - 1; index >= 0; index -= 1) {
    const fact = store.facts[index] ?? '';
    const prefix = 'attachment.send:';
    if (fact.startsWith(prefix)) {
      return fact
        .slice(prefix.length)
        .split(',')
        .filter((id) => id !== '')
        .map((id) => documentNames[id] ?? id);
    }
  }
  return [];
}

/** Wire shape of one instant message, with file names visible in the body. */
function instantWire(message: InstantRecord, sequence: number) {
  const suffix = message.attachmentNames.map((name) => `[file:${name}]`).join(' ');
  return {
    id: message.id,
    sequence,
    senderSubject: message.senderSubject,
    senderRole: 'staff',
    body: suffix === '' ? message.body : `${message.body} ${suffix}`,
    sentAt: message.sentAt,
  };
}

/** Unread messages for one viewer in the home-facility conversation. */
function unreadFor(store: StaffMessageStore, viewer: string): number {
  return store.messages.filter((message) => message.senderSubject !== viewer).length;
}

/** Read the instant conversation list for the requested facility. */
async function instantConversations(context: RequestContext): Promise<boolean> {
  if (
    context.method !== 'GET' ||
    !context.path.startsWith('/api/v1/staff/instant/conversations')
  ) {
    return false;
  }
  const facility = new URL(context.request.url()).searchParams.get('facilityId');
  const conversations =
    facility === homeFacilityId
      ? [
          {
            id: instantConversationId,
            facilityId: homeFacilityId,
            kind: 'direct',
            topic: 'Synthetic triage peer',
            lastSequence: context.store.messages.length,
            unreadCount: unreadFor(context.store, context.viewer),
          },
        ]
      : [];
  await json(context.route, conversations);
  return true;
}

/** Read the one thread; a peer sees the author's message and vice versa. */
async function instantMessages(context: RequestContext): Promise<boolean> {
  const match = /^\/api\/v1\/staff\/instant\/([^/]+)\/messages$/.exec(context.path);
  if (context.method !== 'GET' || match === null) {
    return false;
  }
  if (decodeURIComponent(match[1] ?? '') !== instantConversationId) {
    await json(context.route, { code: 'NOT_FOUND' }, 404);
    return true;
  }
  const wire = context.store.messages.map((message, index) => instantWire(message, index + 1));
  await json(context.route, wire);
  return true;
}

/** Read unread counts and an identifier-only event page. */
async function instantAuxiliary(context: RequestContext): Promise<boolean> {
  if (context.method === 'GET' && context.path.startsWith('/api/v1/staff/instant/unread')) {
    await json(context.route, [
      {
        conversationId: instantConversationId,
        unreadCount: unreadFor(context.store, context.viewer),
      },
    ]);
    return true;
  }
  if (context.method === 'GET' && context.path.startsWith('/api/v1/staff/instant/events')) {
    await json(context.route, { events: [], nextCursor: undefined });
    return true;
  }
  if (context.method === 'POST' && context.path === '/api/v1/staff/instant/read') {
    await json(context.route, { conversationId: instantConversationId, unreadCount: 0 });
    return true;
  }
  return false;
}

/** Publish one instant message after its attachment set passed the scan gate. */
async function instantSend(context: RequestContext): Promise<boolean> {
  const match = /^\/api\/v1\/staff\/instant\/([^/]+)\/messages$/.exec(context.path);
  if (context.method !== 'POST' || match === null) {
    return false;
  }
  if (decodeURIComponent(match[1] ?? '') !== instantConversationId) {
    await json(context.route, { code: 'NOT_FOUND' }, 404);
    return true;
  }
  const body = bodyOf(context.request);
  const id = `synthetic-instant-${++context.store.serial}`;
  const recordMessage: InstantRecord = {
    id,
    conversationId: instantConversationId,
    senderSubject: context.viewer,
    body: textOf(body.body),
    sentAt: originalSentAt,
    attachmentNames: publishedFileNames(context.store),
  };
  context.store.messages.push(recordMessage);
  record(context.store, `instant.send:${context.viewer}:${id}`);
  await json(context.route, instantWire(recordMessage, context.store.messages.length), 201);
  return true;
}

/** Wire shape of one mail listing entry for the requesting viewer. */
function mailWire(message: MailRecord, viewer: string) {
  const folder = message.folder === 'sent' && message.owner === viewer ? 'sent' : 'inbox';
  const recipients = [...message.to, ...message.cc].map((value) => ({ kind: 'person', value }));
  return {
    message: {
      id: message.id,
      subject: message.subject,
      body: message.body,
      senderSubject: message.senderSubject,
      sentAt: message.sentAt,
      to: recipients,
      cc: [],
      attachmentDocumentIds: message.attachmentNames,
    },
    viewerFolder: folder,
  };
}

/** True when the viewer is a participant in the published mail. */
function seesMail(message: MailRecord, viewer: string): boolean {
  return message.owner === viewer || message.to.includes(viewer) || message.cc.includes(viewer);
}

/** List one mail folder, filtered by viewer participation. */
async function mailFolders(context: RequestContext): Promise<boolean> {
  const match = /^\/api\/v1\/staff\/mail\/folders\/([^/]+)$/.exec(context.path);
  if (context.method !== 'GET' || match === null) {
    return false;
  }
  const folder = decodeURIComponent(match[1] ?? '');
  const visible = context.store.mails.filter(
    (message) => seesMail(message, context.viewer) && mailWire(message, context.viewer).viewerFolder === folder,
  );
  await json(context.route, visible.map((message) => mailWire(message, context.viewer)));
  return true;
}

/** Wire shape of one private draft, including its version precondition. */
function draftWire(draft: DraftRecord) {
  return {
    id: draft.id,
    version: draft.version,
    facilityId: homeFacilityId,
    subject: draft.subject,
    body: draft.body,
    to: draft.to.map((value) => ({ kind: 'person', value })),
    cc: draft.cc.map((value) => ({ kind: 'person', value })),
    attachmentDocumentIds: [...draft.attachments],
  };
}

/** Create a private version-one draft from the real page's compose body. */
async function mailCreateDraft(context: RequestContext): Promise<boolean> {
  if (context.method !== 'POST' || context.path !== '/api/v1/staff/mail/drafts') {
    return false;
  }
  const body = bodyOf(context.request);
  const id = `synthetic-draft-${++context.store.serial}`;
  const draft: DraftRecord = {
    id,
    owner: context.viewer,
    version: 1,
    subject: textOf(body.subject),
    body: textOf(body.body),
    to: valuesOf(body.to),
    cc: valuesOf(body.cc),
    attachments: valuesOf(body.attachmentDocumentIds),
  };
  context.store.drafts.set(id, draft);
  record(context.store, `mail.draft.create:${context.viewer}:${id}`);
  await json(context.route, draftWire(draft), 201);
  return true;
}

/** Save a new draft version only when If-Match names the last read version. */
async function mailSaveDraft(context: RequestContext): Promise<boolean> {
  const match = /^\/api\/v1\/staff\/mail\/drafts\/([^/]+)$/.exec(context.path);
  if (context.method !== 'PUT' || match === null) {
    return false;
  }
  const draft = context.store.drafts.get(decodeURIComponent(match[1] ?? ''));
  if (draft === undefined) {
    await json(context.route, { code: 'NOT_FOUND' }, 404);
    return true;
  }
  const expected = Number(context.request.headers()['if-match'] ?? '0');
  if (expected !== draft.version) {
    await json(context.route, { code: 'VERSION_CONFLICT' }, 412);
    return true;
  }
  const body = bodyOf(context.request);
  draft.version += 1;
  draft.subject = textOf(body.subject);
  draft.body = textOf(body.body);
  draft.to = valuesOf(body.to);
  draft.cc = valuesOf(body.cc);
  draft.attachments = valuesOf(body.attachmentDocumentIds);
  record(context.store, `mail.draft.save:${context.viewer}:${draft.version}`);
  await json(context.route, draftWire(draft));
  return true;
}

/** Publish a confirmed draft, or ask for an explicit recipient confirmation. */
async function mailSendDraft(context: RequestContext): Promise<boolean> {
  const match = /^\/api\/v1\/staff\/mail\/drafts\/([^/]+)\/send$/.exec(context.path);
  if (context.method !== 'POST' || match === null) {
    return false;
  }
  const draft = context.store.drafts.get(decodeURIComponent(match[1] ?? ''));
  if (draft === undefined) {
    await json(context.route, { code: 'NOT_FOUND' }, 404);
    return true;
  }
  const body = bodyOf(context.request);
  const confirmed = body.confirmedRecipients;
  if (!Array.isArray(confirmed) || confirmed.length === 0) {
    await json(context.route, { code: 'CONFIRM_REQUIRED' }, 428);
    return true;
  }
  if (draft.attachments.includes(quarantinedDocumentId)) {
    await json(context.route, { code: 'QUARANTINED_FILE' }, 409);
    return true;
  }
  const id = `synthetic-mail-${++context.store.serial}`;
  const published: MailRecord = {
    id,
    subject: draft.subject,
    body: draft.body,
    senderSubject: context.viewer,
    to: draft.to,
    cc: draft.cc,
    attachmentNames: [...draft.attachments],
    sentAt: replySentAt,
    folder: 'sent',
    owner: context.viewer,
  };
  context.store.mails.push(published);
  context.store.drafts.delete(draft.id);
  record(context.store, `mail.publish:${context.viewer}:${id}`);
  await json(context.route, mailWire(published, context.viewer));
  return true;
}

/** The mail group: folders, private drafts and publication. */
const mailGroup: RouteGroup = async (context) => {
  if (!context.path.startsWith('/api/v1/staff/mail')) {
    return false;
  }
  if (await mailFolders(context)) {
    return true;
  }
  if (await mailCreateDraft(context)) {
    return true;
  }
  if (await mailSaveDraft(context)) {
    return true;
  }
  return mailSendDraft(context);
};

/** Attach an opaque reference and return a fixed-reason subject warning. */
async function referenceAttach(context: RequestContext): Promise<boolean> {
  if (context.method !== 'POST' || context.path !== '/api/v1/staff/references/attach') {
    return false;
  }
  const body = bodyOf(context.request);
  const subject = textOf(body.subject);
  const reason = /\d{7,}/.test(subject) ? 'DIGIT_RUN' : 'NONE';
  record(context.store, `reference.attach:${context.viewer}:${reason}`);
  await json(context.route, { id: referenceId, reason }, 201);
  return true;
}

/** Return only the non-identifying label; no target data is ever projected. */
async function referenceView(context: RequestContext): Promise<boolean> {
  if (!context.path.startsWith('/api/v1/staff/references/view')) {
    return false;
  }
  // Defense in depth: even the label-only projection omits the label for a
  // viewer without the relationship, before any chip is rendered.
  const label = context.viewer === remoteSubject ? undefined : referenceLabel;
  await json(context.route, label === undefined ? { id: referenceId } : { id: referenceId, label });
  return true;
}

/**
 * Re-run access on open. A viewer in the home facility has the care
 * relationship; a viewer in the second facility does not. The blocked response
 * carries no label, patient handle or reason beyond the fixed outcome.
 */
async function referenceOpen(context: RequestContext): Promise<boolean> {
  if (context.method !== 'POST' || context.path !== '/api/v1/staff/references/open') {
    return false;
  }
  const facility = context.viewer === remoteSubject ? otherFacilityId : homeFacilityId;
  const outcome = facility === homeFacilityId ? 'ALLOWED' : 'NO_RELATIONSHIP';
  record(context.store, `reference.open:${context.viewer}:${outcome}`);
  await json(
    context.route,
    outcome === 'ALLOWED' ? { outcome, label: referenceLabel, targetId: syntheticPatientId } : { outcome },
  );
  return true;
}

/** The reference group: attach, label-only view and fresh access check. */
const referenceGroup: RouteGroup = async (context) => {
  if (!context.path.startsWith('/api/v1/staff/references')) {
    return false;
  }
  if (await referenceAttach(context)) {
    return true;
  }
  if (await referenceView(context)) {
    return true;
  }
  return referenceOpen(context);
};

/** List pending files with their independent scan verdicts. */
async function attachmentPending(context: RequestContext): Promise<boolean> {
  if (context.method !== 'GET' || !context.path.startsWith('/api/v1/staff/attachments/pending')) {
    return false;
  }
  await json(context.route, [
    { id: cleanDocumentId, fileName: cleanFileName, verdict: 'clean' },
    { id: quarantinedDocumentId, fileName: quarantinedFileName, verdict: 'quarantined' },
  ]);
  return true;
}

/** Record a scan verdict for one private file. */
async function attachmentScan(context: RequestContext): Promise<boolean> {
  const match = /^\/api\/v1\/staff\/attachments\/([^/]+)\/scan$/.exec(context.path);
  if (context.method !== 'POST' || match === null) {
    return false;
  }
  const id = decodeURIComponent(match[1] ?? '');
  const verdict = id === quarantinedDocumentId ? 'quarantined' : 'clean';
  record(context.store, `attachment.scan:${id}:${verdict}`);
  await json(context.route, { id, state: verdict });
  return true;
}

/** Refuse to publish any named file whose scan verdict is not clean. */
async function attachmentSend(context: RequestContext): Promise<boolean> {
  if (context.method !== 'POST' || context.path !== '/api/v1/staff/attachments/send') {
    return false;
  }
  const ids = valuesOf(bodyOf(context.request).documentIds);
  if (ids.some((id) => id === quarantinedDocumentId)) {
    record(context.store, 'attachment.refuse:QUARANTINED_FILE');
    await json(context.route, { code: 'QUARANTINED_FILE' }, 409);
    return true;
  }
  record(context.store, `attachment.send:${ids.join(',')}`);
  await json(context.route, ids.map((id) => ({ id, state: 'sent' })));
  return true;
}

/** The attachment group: pending review, scan verdict and guarded publish. */
const attachmentGroup: RouteGroup = async (context) => {
  if (!context.path.startsWith('/api/v1/staff/attachments')) {
    return false;
  }
  if (await attachmentPending(context)) {
    return true;
  }
  if (await attachmentScan(context)) {
    return true;
  }
  return attachmentSend(context);
};

/** Answer one request from the first group that matches it. */
async function answer(route: Route, viewer: string, store: StaffMessageStore): Promise<void> {
  const request = route.request();
  const path = new URL(request.url()).pathname;
  const context: RequestContext = {
    route,
    request,
    path,
    method: request.method(),
    viewer,
    store,
  };
  const headers = request.headers();
  store.calls.push(`${context.method} ${path}`);
  store.requests.push({
    method: context.method,
    path,
    tenant: headers['x-active-organization'],
    csrf: headers['x-xsrf-token'],
    idempotencyKey: headers['idempotency-key'],
  });
  if (!isOwningTenant(request)) {
    await refuse(context);
    return;
  }
  // Every protected write must carry the CSRF cookie header supplied by the
  // real transport. Refusing a header-less write proves the harness exercises
  // the production client rather than bypassing its safety checks.
  if (context.method !== 'GET' && headers['x-xsrf-token'] !== 'synthetic-csrf-token') {
    await json(route, { code: 'CSRF_MISSING' }, 403);
    return;
  }
  const groups: readonly RouteGroup[] = [
    attachmentGroup,
    referenceGroup,
    mailGroup,
    instantConversations,
    instantMessages,
    instantAuxiliary,
    instantSend,
  ];
  for (const group of groups) {
    if (await group(context)) {
      return;
    }
  }
  await json(route, { code: 'NOT_FOUND' }, 404);
}

/** Install one stateful handler for a view with a fixed synthetic identity. */
async function install(
  page: Page,
  baseURL: string | undefined,
  viewer: string,
  store: StaffMessageStore,
): Promise<void> {
  await page.context().addCookies([
    {
      name: 'XSRF-TOKEN',
      value: 'synthetic-csrf-token',
      url: baseURL ?? 'http://127.0.0.1:5173',
    },
  ]);
  await page.route(/\/api\/v1\/staff\/(instant|mail|references|attachments)(?:\/|\?|$)/, (route) =>
    answer(route, viewer, store),
  );
}

/** Build one fresh shared store with no published messages, mail or facts. */
export function createStaffMessageApi(): StaffMessageApi {
  const store: StaffMessageStore = {
    messages: [],
    mails: [],
    drafts: new Map<string, DraftRecord>(),
    facts: [],
    calls: [],
    requests: [],
    serial: 0,
  };
  return {
    store,
    calls: store.calls,
    installOn: (page, baseURL, viewer) => install(page, baseURL, viewer, store),
  };
}
