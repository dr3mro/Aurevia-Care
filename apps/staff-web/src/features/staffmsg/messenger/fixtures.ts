/**
 * Clearly synthetic instant-messenger data (MSG-02, MSG-03).
 *
 * Two tenants reuse the same local identifier shapes, one facility holds two
 * conversations with different unread counts, and one body is Arabic so the
 * right-to-left bubble path can be exercised. No real person, staff identity,
 * file or credential appears in this file.
 */

import { TransportError } from '../../../api/transport';
import { defined } from '../../../components/invariant';
import type {
  ComposerDraft,
  ConversationView,
  FailedSend,
  MessengerView,
  MessageView,
  PendingAttachment,
  WireConversation,
  WireEvent,
  WireEventPage,
  WireMessage,
  WireUnread,
} from './types';
import { MESSENGER_VIEW_TIME_ZONE } from './types';

/** Synthetic alpha tenant, owner of the primary fixture set. */
export const alphaOrganizationId = 'org-synthetic-alpha';

/** Synthetic beta tenant whose local ids deliberately resemble alpha's. */
export const betaOrganizationId = 'org-synthetic-beta';

/** The active facility. */
export const facilityId = 'facility-synthetic-main';

/** The acting staff subject. */
export const selfSubject = 'synthetic-staff-self';

/** A peer staff subject in the same facility. */
export const peerSubject = 'synthetic-staff-peer';

/** A complete wire conversation with a positive unread count. */
export const wireUnreadConversation: WireConversation = {
  id: 'synthetic-conversation-alpha',
  facilityId,
  kind: 'direct',
  topic: 'Synthetic triage peer',
  lastSequence: 12,
  unreadCount: 3,
};

/** A complete wire conversation with zero unread and no topic. */
export const wireReadConversation: WireConversation = {
  id: 'synthetic-conversation-beta',
  facilityId,
  kind: 'group',
  topic: null,
  lastSequence: 7,
  unreadCount: 0,
};

/** A wire conversation whose optional fields are all absent. */
export const wireSparseConversation: WireConversation = {};

/** A complete wire message from a peer. */
export const wireIncomingMessage: WireMessage = {
  id: 'synthetic-message-001',
  sequence: 9,
  senderSubject: peerSubject,
  senderRole: 'staff',
  body: 'Synthetic incoming body',
  sentAt: '2026-03-09T23:30:00Z',
};

/** A complete wire message from the active subject. */
export const wireOutgoingMessage: WireMessage = {
  id: 'synthetic-message-002',
  sequence: 10,
  senderSubject: selfSubject,
  senderRole: 'staff',
  body: 'Synthetic outgoing body',
  sentAt: '2026-03-10T00:30:00Z',
};

/** A wire message whose optional fields are all absent. */
export const wireSparseMessage: WireMessage = {};

/** Tenant-local conversation id used by the primary fixture set. */
export const alphaConversationId = defined(wireUnreadConversation.id, 'alpha conversation id');

/** A complete wire unread count. */
export const wireUnread: WireUnread = {
  conversationId: alphaConversationId,
  unreadCount: 3,
};

/** A wire unread count with both optional fields absent. */
export const wireSparseUnread: WireUnread = {};

/** A complete identifier-only message event. */
export const wireMessageEvent: WireEvent = {
  id: 'synthetic-event-001',
  sequence: 13,
  type: 'message',
  conversationId: alphaConversationId,
  subject: peerSubject,
};

/** A complete identifier-only read event. */
export const wireReadEvent: WireEvent = {
  id: 'synthetic-event-002',
  sequence: 14,
  type: 'read',
  conversationId: alphaConversationId,
  subject: selfSubject,
};

/** A wire event whose optional fields are all absent. */
export const wireSparseEvent: WireEvent = {};

/** A complete polling page with a high-water cursor. */
export const wireEventPage: WireEventPage = {
  events: [wireMessageEvent, wireReadEvent],
  nextCursor: 14,
};

/** A polling page with no events and a cursor that still advances. */
export const wireEmptyEventPage: WireEventPage = {
  events: [],
  nextCursor: 15,
};

/** A polling page whose optional fields are all absent. */
export const wireSparseEventPage: WireEventPage = {};

/** A staged file; only its name and local id are held by the view. */
export const stagedAttachment: PendingAttachment = {
  id: 'synthetic-document-001',
  name: 'synthetic-notes.pdf',
};

/** A second staged file for the multi-file tray test. */
export const secondAttachment: PendingAttachment = {
  id: 'synthetic-document-002',
  name: 'synthetic-scan.png',
};

/** An empty composer. */
export const emptyDraft: ComposerDraft = { text: '', attachments: [] };

/** A composer with typed text and one staged file. */
export const contentDraft: ComposerDraft = {
  text: 'Synthetic typed reply',
  attachments: [stagedAttachment],
};

/** An Arabic outgoing message for the right-to-left bubble test. */
export const arabicMessage: MessageView = {
  id: 'synthetic-message-arabic',
  sequence: 11,
  senderSubject: peerSubject,
  body: 'رسالة тوضيحية',
  sentAt: '2026-03-10T01:00:00Z',
  timeZone: MESSENGER_VIEW_TIME_ZONE,
  lang: 'ar',
  outgoing: false,
  delivery: 'delivered',
};

/** One view conversation with unread messages. */
export const unreadConversation: ConversationView = {
  id: alphaConversationId,
  facilityId,
  kind: 'direct',
  topic: 'Synthetic triage peer',
  lastSequence: 12,
  unreadCount: 3,
};

/** One view conversation with no unread messages. */
export const readConversation: ConversationView = {
  id: defined(wireReadConversation.id, 'beta conversation id'),
  facilityId,
  kind: 'group',
  topic: '',
  lastSequence: 7,
  unreadCount: 0,
};

/** A thread spanning a UTC midnight, with an outgoing and an incoming bubble. */
export const threadMessages: readonly MessageView[] = [
  {
    id: defined(wireIncomingMessage.id, 'incoming message id'),
    sequence: 9,
    senderSubject: peerSubject,
    body: 'Synthetic incoming body',
    sentAt: '2026-03-09T23:30:00Z',
    timeZone: MESSENGER_VIEW_TIME_ZONE,
    lang: 'en',
    outgoing: false,
    delivery: 'delivered',
  },
  {
    id: defined(wireOutgoingMessage.id, 'outgoing message id'),
    sequence: 10,
    senderSubject: selfSubject,
    body: 'Synthetic outgoing body',
    sentAt: '2026-03-10T00:30:00Z',
    timeZone: MESSENGER_VIEW_TIME_ZONE,
    lang: 'en',
    outgoing: true,
    delivery: 'delivered',
  },
];

/** A complete messenger view for the primary tenant. */
export const messengerView: MessengerView = {
  facilityId,
  conversations: [unreadConversation, readConversation],
  messages: threadMessages,
  selectedConversationId: unreadConversation.id,
};

/** A view with no conversations and no messages. */
export const emptyMessengerView: MessengerView = {
  facilityId,
  conversations: [],
  messages: [],
  selectedConversationId: '',
};

/** A failed send that keeps its text, files and idempotency key for retry. */
export const failedSend: FailedSend = {
  error: new TransportError('offline', {
    correlationId: 'synthetic-correlation-001',
    code: 'SYNTHETIC_OFFLINE',
    retryAfterSeconds: undefined,
  }),
  text: 'Synthetic unsent text',
  files: [stagedAttachment],
  idempotencyKey: 'synthetic-idempotency-001',
};
