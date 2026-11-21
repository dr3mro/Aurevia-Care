/** Synthetic-only fixture builders for messages screens; no real identifiers. */

import type {
  Conversation,
  ConversationView,
  Inbox,
  InboxEntry,
  Message,
  WorkItemEvent,
  WorkQueue,
} from './types';

/** Synthetic organization used by the alpha tenant. */
export const alphaOrganizationId = 'org-alpha';

/** Synthetic organization used by the beta tenant. */
export const betaOrganizationId = 'org-beta';

/** Synthetic opaque conversation identifier. */
export const conversationId = '11111111-1111-4111-8111-111111111111';

/** Synthetic opaque patient identifier. */
export const patientId = 'SYN-PAT-0001';

/** Build one synthetic conversation projection. */
export function syntheticConversation(overrides: Partial<Conversation> = {}): Conversation {
  return {
    id: conversationId,
    version: 3,
    patientId,
    patientSubject: 'patient-alpha',
    unitKey: 'clinic',
    connectionId: 'conn-alpha',
    topic: 'Synthetic follow-up',
    state: 'awaiting_staff',
    assigneeSubject: null,
    patientReadSequence: 2,
    staffReadSequence: 1,
    createdAt: '2026-01-01T00:00:00.000Z',
    ...overrides,
  };
}

/** Build one synthetic message in a conversation. */
export function syntheticMessage(overrides: Partial<Message> = {}): Message {
  return {
    id: 'msg-1',
    conversationId,
    sequence: 1,
    senderKind: 'patient',
    senderSubject: 'patient-alpha',
    senderRole: 'patient',
    body: 'Synthetic patient question',
    createdAt: '2026-01-01T00:00:00.000Z',
    ...overrides,
  };
}

/** Build one synthetic work-item event. */
export function syntheticWorkItemEvent(overrides: Partial<WorkItemEvent> = {}): WorkItemEvent {
  return {
    id: 'event-1',
    conversationId,
    conversationVersion: 1,
    eventType: 'created',
    actorSubject: 'patient-alpha',
    fromState: '',
    toState: 'awaiting_staff',
    reason: '',
    occurredAt: '2026-01-01T00:00:00.000Z',
    ...overrides,
  };
}

/** Build one synthetic conversation view with two messages in server order. */
export function syntheticView(overrides: Partial<ConversationView> = {}): ConversationView {
  return {
    conversation: syntheticConversation(),
    messages: [
      syntheticMessage(),
      syntheticMessage({
        id: 'msg-2',
        sequence: 2,
        senderKind: 'staff',
        senderRole: 'unit_staff',
        body: 'Synthetic staff reply',
        createdAt: '2026-01-01T01:00:00.000Z',
      }),
    ],
    events: [syntheticWorkItemEvent()],
    ...overrides,
  };
}

/** Build one synthetic inbox entry with an explicit unread count. */
export function syntheticInboxEntry(unreadCount = 2, conversation = syntheticConversation()): InboxEntry {
  return { conversation, unreadCount };
}

/** Build a synthetic inbox from its entries. */
export function syntheticInbox(entries: readonly InboxEntry[] = [syntheticInboxEntry()]): Inbox {
  return { entries };
}

/** Build a synthetic staff work queue from its conversations. */
export function syntheticWorkQueue(
  entries: readonly Conversation[] = [syntheticConversation()],
): WorkQueue {
  return { entries };
}
