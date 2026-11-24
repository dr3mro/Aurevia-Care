/**
 * Pure instant-messenger rules (MSG-02, MSG-03, WEB-09).
 *
 * The panel never derives a tenant boundary, a delivery state or a connection
 * state from presentation. Unread totals, day separators and the stream/poll
 * state machine live here so each is deterministic and independently testable.
 * Instants are rendered in the named view time zone; civil day keys are built
 * from the instant and then handed to the shared civil-date formatter, which
 * appends the UTC midnight itself.
 */

import { formatCivilDate } from '../../../i18n/format';
import type { Locale } from '../../../i18n/messages';
import type { MessengerMessageKey } from './labels';
import { REALTIME_EVENT_TYPES } from './types';
import type {
  ConnectionStatus,
  DeliveryState,
  MessageDayGroup,
  MessageView,
} from './types';

/** Bilingual word for each delivery state. */
const DELIVERY_WORD: Record<DeliveryState, MessengerMessageKey> = {
  sending: 'deliverySending',
  delivered: 'deliveryDelivered',
  failed: 'deliveryFailed',
};

/** Observable inputs to the realtime connection state machine. */
export type ConnectionEvent = 'stream-open' | 'stream-lost' | 'poll-succeeded' | 'resynced';

/** Resolve a delivery state to its bilingual label key. */
export function deliveryWordKey(state: DeliveryState): MessengerMessageKey {
  return DELIVERY_WORD[state];
}

/**
 * Build a `YYYY-MM-DD` civil key for an ISO instant in an IANA zone.
 *
 * Parts are assembled explicitly instead of relying on a locale-specific
 * formatted string, so the key is stable across ICU versions and matches the
 * `YYYY-MM-DD` input the shared civil-date formatter expects.
 */
export function civilDayKey(iso: string, timeZone: string): string {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(new Date(iso));
  const part = (type: string): string =>
    parts
      .filter((entry) => entry.type === type)
      .map((entry) => entry.value)
      .join('');
  return `${part('year')}-${part('month')}-${part('day')}`;
}

/** Mutable accumulator used only while grouping; callers receive read-only groups. */
interface DayAccumulator {
  day: string;
  label: string;
  messages: MessageView[];
}

/**
 * Group thread messages into civil-day sections for date separators. A new
 * group starts at the first message and whenever the named zone crosses into a
 * different civil day, so a late-night message and the next morning are split.
 */
export function groupMessagesByDay(
  messages: readonly MessageView[],
  timeZone: string,
  locale: Locale,
): MessageDayGroup[] {
  const groups: DayAccumulator[] = [];
  for (const entry of messages) {
    const day = civilDayKey(entry.sentAt, timeZone);
    const current = groups[groups.length - 1];
    if (current?.day === day) {
      current.messages.push(entry);
    } else {
      groups.push({ day, label: formatCivilDate(day, locale), messages: [entry] });
    }
  }
  return groups;
}

/**
 * Advance the realtime connection state. A stream opening always returns to
 * `streaming`; losing it enters `reconnecting`; a successful poll only becomes
 * `polling` when we were reconnecting, so an ordinary periodic poll never
 * mislabels a healthy stream. An explicit resync returns to `streaming`.
 */
export function advanceConnection(status: ConnectionStatus, event: ConnectionEvent): ConnectionStatus {
  switch (event) {
    case 'stream-open':
      return 'streaming';
    case 'stream-lost':
      return 'reconnecting';
    case 'poll-succeeded':
      return status === 'reconnecting' ? 'polling' : status;
    case 'resynced':
      return 'streaming';
  }
}

/** The two wire fields checked before a frame may drive a refetch or a resume. */
interface IdentifierCandidate {
  readonly type: string;
  readonly sequence: number;
}

/**
 * True only for a published identifier-only event with a usable cursor value.
 * A malformed SSE payload can carry an unknown kind or a non-integral sequence;
 * rejecting it here keeps a bad resume id out of the next stream and stops a
 * bogus notification from triggering a full thread refetch.
 */
export function isIdentifierEvent(event: IdentifierCandidate): boolean {
  return (
    REALTIME_EVENT_TYPES.some((kind) => kind === event.type) &&
    Number.isSafeInteger(event.sequence) &&
    event.sequence >= 0
  );
}

/** True only when the composer holds non-whitespace text worth sending. */
export function hasComposableText(text: string): boolean {
  return text.trim().length > 0;
}
