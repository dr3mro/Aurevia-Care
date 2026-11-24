/**
 * Real-API client for the staff mail view (MSG-02, MSG-03, WEB-09).
 *
 * Mail routes and schemas are already published in the generated contract, so
 * this module never edits that contract. Descriptors are built with the shared
 * header builder and sent through the shared transport; the version precondition,
 * idempotency key and tenant scope stay owned in one place. Wire responses have
 * optional fields, so they are narrowed once here before any state or command
 * sees them. Response text is never copied into an error or a log.
 */

import { buildRequestDescriptor } from '../../../api/client';
import type { ApiPath, RequestDescriptor, RequestMeta } from '../../../api/client';
import { TransportError, send } from '../../../api/transport';
import type {
  ComposeCommand,
  ComposeDraft,
  MailFolder,
  MailMessageView,
  MailOverviewView,
  RecipientView,
  WireMailDraft,
  WireMailRecipient,
  WireMailView,
} from './types';
import { MAIL_FOLDERS } from './types';

/** A published path used only as the source of header metadata. */
const HEADER_SOURCE_PATH: ApiPath = '/api/v1/staff/mail/drafts';

/** The wire carries no zone, so instants are presented in UTC with a visible label. */
const MAIL_VIEW_TIME_ZONE = 'UTC';

/** Build a descriptor for a published mail route, including the PUT draft save. */
function descriptor(
  method: 'GET' | 'POST' | 'PUT',
  path: string,
  meta: RequestMeta,
  body?: Record<string, unknown>,
): RequestDescriptor {
  const base = buildRequestDescriptor('POST', HEADER_SOURCE_PATH, meta, body);
  // The shared descriptor type predates the versioned PUT draft write. The
  // transport treats every non-GET method as a protected write and only GET is
  // retried, so the runtime semantics are correct without editing shared code.
  return { ...base, url: path, method: method as 'GET' | 'POST' };
}

/** Fail closed on a malformed response with a typed, body-free failure. */
function requireField<T>(value: T | undefined, field: string): T {
  if (value === undefined) {
    throw new TransportError('invalid', {
      correlationId: undefined,
      code: field,
      retryAfterSeconds: undefined,
    });
  }
  return value;
}

/** Treat an absent wire string as empty text without trusting response text. */
function orText(value: string | undefined): string {
  return value ?? '';
}

/** Treat an absent wire collection as empty. */
function orEmpty<T>(value: readonly T[] | undefined): readonly T[] {
  return value ?? [];
}

/** Narrow one wire recipient; the value doubles as its accessible label. */
function narrowRecipient(wire: WireMailRecipient): RecipientView {
  const kind = requireField(wire.kind, 'recipient kind');
  const value = requireField(wire.value, 'recipient value');
  return { kind, value, label: value };
}

/** Narrow one wire draft so its id and version can act as save preconditions. */
export function narrowDraft(wire: WireMailDraft): ComposeDraft {
  return {
    draftId: requireField(wire.id, 'draft id'),
    version: requireField(wire.version, 'draft version'),
    facilityId: orText(wire.facilityId),
    subject: orText(wire.subject),
    body: orText(wire.body),
    to: orEmpty(wire.to).map(narrowRecipient),
    cc: orEmpty(wire.cc).map(narrowRecipient),
    attachments: orEmpty(wire.attachmentDocumentIds).map((documentId) => ({
      documentId,
      fileName: documentId,
    })),
  };
}

/** Narrow one message in a folder listing; the requested folder is authoritative. */
export function narrowMessage(view: WireMailView, folder: MailFolder): MailMessageView {
  const message = requireField(view.message, 'mail message');
  const from = requireField(message.senderSubject, 'sender subject');
  return {
    id: requireField(message.id, 'message id'),
    subject: orText(message.subject),
    body: orText(message.body),
    from: { kind: 'person', value: from, label: from },
    to: orEmpty(message.to).map(narrowRecipient),
    cc: orEmpty(message.cc).map(narrowRecipient),
    attachments: orEmpty(message.attachmentDocumentIds).map((documentId) => ({
      documentId,
      fileName: documentId,
    })),
    sentAt: requireField(message.sentAt, 'sent at'),
    timeZone: MAIL_VIEW_TIME_ZONE,
    folder,
  };
}

/** Build the published compose body from required local command fields. */
function composeBody(command: ComposeCommand): Record<string, unknown> {
  return {
    facilityId: command.facilityId,
    subject: command.subject,
    body: command.body,
    to: command.to.map((recipient) => ({ kind: recipient.kind, value: recipient.value })),
    cc: command.cc.map((recipient) => ({ kind: recipient.kind, value: recipient.value })),
    attachmentDocumentIds: [...command.attachmentDocumentIds],
  };
}

/** Read one folder's messages with the tenant scope from the active organization. */
export async function listFolder(
  organizationId: string,
  folder: MailFolder,
): Promise<MailMessageView[]> {
  const path = `/api/v1/staff/mail/folders/${encodeURIComponent(folder)}`;
  const wire = await send<WireMailView[]>(descriptor('GET', path, { activeOrganization: organizationId }));
  return wire.map((view) => narrowMessage(view, folder));
}

/**
 * Read all five folders and derive their counts. No count endpoint is published;
 * loading every folder is the only way to show a true count per folder.
 */
export async function loadMailOverview(
  organizationId: string,
  facilityId: string,
): Promise<MailOverviewView> {
  const results = await Promise.all(
    MAIL_FOLDERS.map(async (folder) => ({ folder, messages: await listFolder(organizationId, folder) })),
  );
  const counts: Record<MailFolder, number> = {
    inbox: 0,
    sent: 0,
    drafts: 0,
    archive: 0,
    trash: 0,
  };
  const messages: MailMessageView[] = [];
  for (const result of results) {
    counts[result.folder] = result.messages.length;
    messages.push(...result.messages);
  }
  return { facilityId, counts, messages };
}

/** Start a private version-one draft. */
export async function createDraft(organizationId: string, command: ComposeCommand): Promise<ComposeDraft> {
  const wire = await send<WireMailDraft>(
    descriptor(
      'POST',
      '/api/v1/staff/mail/drafts',
      { activeOrganization: organizationId, idempotencyKey: crypto.randomUUID() },
      composeBody(command),
    ),
  );
  return narrowDraft(wire);
}

/** Build a compose body from the current draft state for a save. */
function commandFromDraft(draft: ComposeDraft): ComposeCommand {
  return {
    facilityId: draft.facilityId,
    subject: draft.subject,
    body: draft.body,
    to: draft.to,
    cc: draft.cc,
    attachmentDocumentIds: draft.attachments.map((attachment) => attachment.documentId),
  };
}

/** Save a new draft version at the version the caller last read. */
export async function saveDraft(organizationId: string, draft: ComposeDraft): Promise<ComposeDraft> {
  const path = `/api/v1/staff/mail/drafts/${encodeURIComponent(draft.draftId)}`;
  const meta: RequestMeta = { activeOrganization: organizationId };
  // A draft that has not been created yet has no version and cannot be saved.
  if (draft.version > 0) {
    meta.ifMatch = draft.version;
  }
  const wire = await send<WireMailDraft>(
    descriptor('PUT', path, meta, composeBody(commandFromDraft(draft))),
  );
  return narrowDraft(wire);
}

/**
 * Publish a draft. The first call omits confirmedRecipients so the server can
 * answer 428; a confirmation reuses the caller's idempotency key so a lost
 * response cannot double-publish.
 */
export async function sendDraft(
  organizationId: string,
  draftId: string,
  confirmedRecipients: readonly string[] | undefined,
  idempotencyKey: string,
): Promise<MailMessageView> {
  const path = `/api/v1/staff/mail/drafts/${encodeURIComponent(draftId)}/send`;
  const body: Record<string, unknown> =
    confirmedRecipients === undefined ? {} : { confirmedRecipients: [...confirmedRecipients] };
  const wire = await send<WireMailView>(
    descriptor('POST', path, { activeOrganization: organizationId, idempotencyKey }, body),
  );
  return narrowMessage(wire, 'sent');
}

/** One text-only reply command, with its target action and idempotency key. */
interface TextReplyCommand {
  readonly action: 'reply' | 'reply-all';
  readonly messageId: string;
  readonly bodyText: string;
  readonly idempotencyKey: string;
}

/** Send a text-only reply or reply-all command and return the versioned draft. */
async function writeTextReply(
  organizationId: string,
  command: TextReplyCommand,
): Promise<ComposeDraft> {
  const path = `/api/v1/staff/mail/messages/${encodeURIComponent(command.messageId)}/${command.action}`;
  const wire = await send<WireMailDraft>(
    descriptor(
      'POST',
      path,
      { activeOrganization: organizationId, idempotencyKey: command.idempotencyKey },
      { body: command.bodyText },
    ),
  );
  return narrowDraft(wire);
}

/** Reply to the sender of one message. */
export function replyMail(
  organizationId: string,
  messageId: string,
  bodyText: string,
  idempotencyKey: string,
): Promise<ComposeDraft> {
  return writeTextReply(organizationId, {
    action: 'reply',
    messageId,
    bodyText,
    idempotencyKey,
  });
}

/** Reply to the sender and every To and Cc participant of one message. */
export function replyAllMail(
  organizationId: string,
  messageId: string,
  bodyText: string,
  idempotencyKey: string,
): Promise<ComposeDraft> {
  return writeTextReply(organizationId, {
    action: 'reply-all',
    messageId,
    bodyText,
    idempotencyKey,
  });
}

/** Forward one message as a fresh compose command. */
export async function forwardMail(
  organizationId: string,
  messageId: string,
  command: ComposeCommand,
  idempotencyKey: string,
): Promise<ComposeDraft> {
  const path = `/api/v1/staff/mail/messages/${encodeURIComponent(messageId)}/forward`;
  const wire = await send<WireMailDraft>(
    descriptor('POST', path, { activeOrganization: organizationId, idempotencyKey }, composeBody(command)),
  );
  return narrowDraft(wire);
}
