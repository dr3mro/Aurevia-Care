/**
 * Wire types for the MSG-02 conversation lifecycle. Field names are taken from
 * `ConversationContract.java` and are never invented here; every value is
 * synthetic in tests and no message body is ever written to browser storage.
 */

/** Stable lifecycle state of one conversation work item (MSG-02). */
export type ConversationState = 'awaiting_staff' | 'awaiting_patient' | 'resolved' | 'closed';

/** Bounded recipient care-unit keys from `ConversationContract.UnitKey`. */
export type UnitKey = 'clinic' | 'lab' | 'radiology' | 'pt' | 'pharmacy';

/** Kind of message author from `ConversationContract.SenderKind`. */
export type SenderKind = 'patient' | 'staff';

/** Append-only work-item fact vocabulary from `ConversationContract`. */
export type WorkEventType =
  | 'created'
  | 'reopened'
  | 'assigned'
  | 'transferred'
  | 'resolved'
  | 'closed';

/** The two role-scoped tabs of the unit inbox. */
export type MessageTab = 'inbox' | 'work-queue';

/** Current, mutable projection of one conversation. */
export interface Conversation {
  id: string;
  version: number;
  patientId: string;
  patientSubject: string;
  unitKey: UnitKey;
  connectionId: string;
  topic: string | null;
  state: ConversationState;
  assigneeSubject: string | null;
  patientReadSequence: number;
  staffReadSequence: number;
  createdAt: string;
}

/** One immutable, sequenced message in a conversation. */
export interface Message {
  id: string;
  conversationId: string;
  sequence: number;
  senderKind: SenderKind;
  senderSubject: string;
  senderRole: string;
  body: string;
  createdAt: string;
}

/** One immutable work-item fact. */
export interface WorkItemEvent {
  id: string;
  conversationId: string;
  conversationVersion: number;
  eventType: WorkEventType;
  actorSubject: string;
  fromState: string;
  toState: string;
  reason: string;
  occurredAt: string;
}

/** One conversation with its complete message and work-item trails. */
export interface ConversationView {
  conversation: Conversation;
  messages: readonly Message[];
  events: readonly WorkItemEvent[];
}

/** One inbox row with the server-derived unread count. */
export interface InboxEntry {
  conversation: Conversation;
  unreadCount: number;
}

/** The caller's inbox. */
export interface Inbox {
  entries: readonly InboxEntry[];
}

/** The role-scoped staff work queue. */
export interface WorkQueue {
  entries: readonly Conversation[];
}

/** A fully typed command that changes a conversation. */
export type ConversationCommand =
  | { action: 'assign'; subject: string; reason: string }
  | { action: 'transfer'; subject: string; reason: string }
  | { action: 'close'; reason: string }
  | { action: 'resolve'; reason: string };

/** The command a confirmation dialog is collecting. */
export type ConfirmableAction = ConversationCommand['action'];
