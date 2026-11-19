/**
 * Pure MSG-02 conversation domain for the patient app (PAT-01–03, MSG-02, UX-01).
 *
 * This module is deterministic and has no React, storage or network
 * dependencies: every function returns a new value and is exhaustively
 * testable. Server authorization, work-item assignment and audit belong to the
 * API slice of S-G3-05; the app only renders the projection and derives unread
 * state from it.
 */

/** Lifecycle state of a conversation and its staff work item. */
export type ConversationState =
  | 'awaiting_staff'
  | 'awaiting_patient'
  | 'resolved'
  | 'closed';

/** Author of a message in a patient conversation. */
export type SenderRole = 'patient' | 'unit';

/** Active layout direction for RTL-aware rendering (UX-01). */
export type LayoutDirection = 'ltr' | 'rtl';

/** One immutable message in a conversation (ARC-07). */
export interface Message {
  /** Durable client message identifier used to deduplicate retries. */
  readonly id: string;
  /** Owning conversation identifier. */
  readonly conversationId: string;
  /** Authenticated author subject. */
  readonly senderId: string;
  /** Whether the patient or the care unit authored the message. */
  readonly senderRole: SenderRole;
  /** Non-sensitive message text. */
  readonly body: string;
  /** Server UTC instant in ISO-8601 form (UX-01). */
  readonly sentAt: string;
  /** Monotonic per-conversation server sequence. */
  readonly sequence: number;
}

/** One verified patient-to-unit conversation (MSG-01, ARC-07). */
export interface Conversation {
  /** Conversation identifier. */
  readonly id: string;
  /** Owning organization identifier (ARC-01, ARC-02). */
  readonly organizationId: string;
  /** Recipient care-unit identifier; a conversation has exactly one unit. */
  readonly unitId: string;
  /** Localized care-unit display name. */
  readonly unitName: string;
  /** Conversation topic. */
  readonly topic: string;
  /** Current lifecycle state. */
  readonly state: ConversationState;
  /** Whether the verified relationship still permits messaging. */
  readonly messagingPermitted: boolean;
  /** Messages in sequence order. */
  readonly messages: readonly Message[];
  /** Highest sequence the patient has viewed. */
  readonly lastReadSequence: number;
}

/** Minimal input required to append a patient reply. */
export interface ReplyInput {
  /** Durable client message identifier. */
  readonly messageId: string;
  /** Authenticated patient subject. */
  readonly senderId: string;
  /** Client UTC instant in ISO-8601 form. */
  readonly sentAt: string;
  /** Non-sensitive reply text. */
  readonly body: string;
}

/** User-facing reason shown when a closed thread cannot accept a reply. */
export const CLOSED_THREAD_MESSAGE = 'Conversation is closed and read only.';

/** User-facing reason shown when a revoked connection cannot accept a reply. */
export const MESSAGING_NOT_PERMITTED_MESSAGE =
  'Messaging is no longer permitted for this connection.';

/**
 * Return the highest message sequence, or zero for an empty thread.
 *
 * @param messages messages to inspect
 * @return highest sequence present
 */
function highestSequence(messages: readonly Message[]): number {
  return messages.reduce((max, message) => Math.max(max, message.sequence), 0);
}

/**
 * Count unread unit messages in a conversation.
 *
 * Only unit-authored messages beyond the patient's read watermark count;
 * the patient's own messages never contribute (MSG-02).
 *
 * @param conversation conversation to inspect
 * @return number of unread unit messages
 */
export function unreadCount(conversation: Conversation): number {
  return conversation.messages.filter(
    message => message.senderRole === 'unit' && message.sequence > conversation.lastReadSequence,
  ).length;
}

/**
 * Sum unread counts across a patient's conversations.
 *
 * @param conversations conversations to inspect
 * @return total number of unread unit messages
 */
export function unreadTotal(conversations: readonly Conversation[]): number {
  return conversations.reduce((sum, conversation) => sum + unreadCount(conversation), 0);
}

/**
 * Produce a conversation with its read watermark advanced to the newest message.
 *
 * The watermark never moves backwards. The input value is not mutated.
 *
 * @param conversation conversation to mark as read
 * @return a new conversation with the advanced watermark
 */
export function markRead(conversation: Conversation): Conversation {
  return {
    ...conversation,
    lastReadSequence: Math.max(conversation.lastReadSequence, highestSequence(conversation.messages)),
  };
}

/**
 * Decide whether a patient may reply to a conversation.
 *
 * A closed thread is read-only and a revoked relationship permits no further
 * messaging, even when the thread is otherwise open (MSG-02, MSG-07).
 *
 * @param conversation conversation to inspect
 * @return {@code true} when replies are permitted
 */
export function canReply(conversation: Conversation): boolean {
  return conversation.state !== 'closed' && conversation.messagingPermitted;
}

/**
 * Append a patient reply, returning a new conversation.
 *
 * A patient reply always means the conversation is awaiting staff, which also
 * reopens a resolved thread when the relationship still permits messaging.
 *
 * @param conversation conversation to reply to
 * @param input reply content and durable identifiers
 * @return a new conversation containing the appended message
 * @throws Error when the thread is closed or the relationship was revoked
 */
export function applyReply(conversation: Conversation, input: ReplyInput): Conversation {
  if (conversation.state === 'closed') {
    throw new Error(CLOSED_THREAD_MESSAGE);
  }
  if (!conversation.messagingPermitted) {
    throw new Error(MESSAGING_NOT_PERMITTED_MESSAGE);
  }

  const message: Message = {
    id: input.messageId,
    conversationId: conversation.id,
    senderId: input.senderId,
    senderRole: 'patient',
    body: input.body,
    sentAt: input.sentAt,
    sequence: highestSequence(conversation.messages) + 1,
  };
  return {
    ...conversation,
    state: 'awaiting_staff',
    messages: [...conversation.messages, message],
  };
}
