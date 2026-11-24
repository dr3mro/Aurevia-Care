/**
 * View, wire and command types for the staff instant-messenger panel
 * (MSG-02, MSG-03, WEB-09).
 *
 * Wire shapes come from the published OpenAPI contract, whose fields are all
 * optional. State and commands use the required-field local types below; the
 * API module narrows each wire response once at the boundary so an absent id,
 * sequence or count can never reach the panel. All identifiers are synthetic
 * and tenant-local. The realtime vocabulary is identifier-only: an event names
 * a change but never carries message text.
 */

import type { TransportError } from '../../../api/transport';
import type { components } from '../../../api/generated/openapi';

/** Time zone used for every displayed instant and day separator in this view. */
export const MESSENGER_VIEW_TIME_ZONE = 'UTC';

/** Delivery states a composer message can show to its author. */
export const DELIVERY_STATES = ['sending', 'delivered', 'failed'] as const;

/** One delivery state shown beside an outgoing message. */
export type DeliveryState = (typeof DELIVERY_STATES)[number];

/** Observable states of the realtime connection. */
export const CONNECTION_STATUSES = ['streaming', 'reconnecting', 'polling'] as const;

/** One realtime connection state. */
export type ConnectionStatus = (typeof CONNECTION_STATUSES)[number];

/** Stable wire kinds of an identifier-only realtime event. */
export const REALTIME_EVENT_TYPES = ['message', 'read'] as const;

/** One identifier-only realtime event kind. */
export type RealtimeEventType = (typeof REALTIME_EVENT_TYPES)[number];

/** One conversation in the active facility, narrowed from the wire. */
export interface ConversationView {
  readonly id: string;
  readonly facilityId: string;
  readonly kind: string;
  readonly topic: string;
  readonly lastSequence: number;
  readonly unreadCount: number;
}

/** One message in a conversation thread, narrowed from the wire or staged locally. */
export interface MessageView {
  readonly id: string;
  readonly sequence: number;
  readonly senderSubject: string;
  readonly body: string;
  readonly sentAt: string;
  readonly timeZone: string;
  /** Language of the message body run, used for isolated RTL rendering. */
  readonly lang: string;
  /** True when the active staff subject sent this message. */
  readonly outgoing: boolean;
  readonly delivery: DeliveryState;
}

/** A staged file in the attachment tray. No bytes are held by this view. */
export interface PendingAttachment {
  readonly id: string;
  readonly name: string;
}

/** The composer's local, unsent work. */
export interface ComposerDraft {
  readonly text: string;
  readonly attachments: readonly PendingAttachment[];
}

/** Durable context for a send that failed, so retry can reuse the same key. */
export interface FailedSend {
  readonly error: TransportError;
  readonly text: string;
  readonly files: readonly PendingAttachment[];
  readonly idempotencyKey: string;
}

/** One facility's conversation list and the selected thread. */
export interface MessengerView {
  readonly facilityId: string;
  readonly conversations: readonly ConversationView[];
  readonly messages: readonly MessageView[];
  readonly selectedConversationId: string;
}

/** A day separator and the messages that belong to that civil day. */
export interface MessageDayGroup {
  readonly day: string;
  readonly label: string;
  readonly messages: readonly MessageView[];
}

/** A narrowed identifier-only notification safe for the realtime controller. */
export interface RealtimeNotification {
  readonly id: string;
  readonly sequence: number;
  readonly type: RealtimeEventType;
  readonly conversationId: string;
  readonly subject: string;
}

/** A narrowed polling page with an explicit cursor when the wire supplies one. */
export interface RealtimePage {
  readonly events: readonly RealtimeNotification[];
  readonly nextCursor: number | undefined;
}

/** A conversation as published on the wire. */
export type WireConversation = components['schemas']['InstantConversation'];

/** A message as published on the wire. */
export type WireMessage = components['schemas']['InstantMessage'];

/** One unread count as published on the wire. */
export type WireUnread = components['schemas']['InstantUnread'];

/** An identifier-only event as published on the wire. */
export type WireEvent = components['schemas']['RealtimeEvent'];

/** A bounded page of identifier-only events as published on the wire. */
export type WireEventPage = components['schemas']['RealtimeEventPage'];

/** The published send request body; extra fields are forbidden by the contract. */
export type WireSendRequest = components['schemas']['InstantSendRequest'];

/** The published read-cursor request body. */
export type WireReadRequest = components['schemas']['InstantMarkReadRequest'];
