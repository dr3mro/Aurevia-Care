import { describe, expect, it } from 'vitest';
import {
  advanceConnection,
  civilDayKey,
  deliveryWordKey,
  groupMessagesByDay,
  hasComposableText,
  isIdentifierEvent,
} from './domain';
import { MESSENGER_VIEW_TIME_ZONE } from './types';
import type { MessageView } from './types';

/** Synthetic message with a chosen instant and identifier. */
function message(id: string, sentAt: string): MessageView {
  return {
    id,
    sequence: 1,
    senderSubject: 'synthetic-peer',
    body: 'Synthetic body',
    sentAt,
    timeZone: MESSENGER_VIEW_TIME_ZONE,
    lang: 'en',
    outgoing: false,
    delivery: 'delivered',
  };
}

describe('messenger domain', () => {
  it('maps every delivery state to its bilingual word', () => {
    expect(deliveryWordKey('sending')).toBe('deliverySending');
    expect(deliveryWordKey('delivered')).toBe('deliveryDelivered');
    expect(deliveryWordKey('failed')).toBe('deliveryFailed');
  });

  it('derives a civil day in the named zone across a midnight boundary', () => {
    expect(civilDayKey('2026-03-09T23:30:00Z', MESSENGER_VIEW_TIME_ZONE)).toBe('2026-03-09');
    expect(civilDayKey('2026-03-10T00:30:00Z', MESSENGER_VIEW_TIME_ZONE)).toBe('2026-03-10');
    expect(civilDayKey('2026-03-10T02:30:00Z', 'America/New_York')).toBe('2026-03-09');
  });

  it('groups messages by day with an exact rendered separator in both locales', () => {
    const messages = [
      message('first', '2026-03-09T08:00:00Z'),
      message('same-day', '2026-03-09T23:30:00Z'),
      message('next-day', '2026-03-10T00:30:00Z'),
    ];
    const english = groupMessagesByDay(messages, MESSENGER_VIEW_TIME_ZONE, 'en');
    expect(english.map((group) => group.day)).toEqual(['2026-03-09', '2026-03-10']);
    expect(english.map((group) => group.label)).toEqual(['March 9, 2026', 'March 10, 2026']);
    expect(english[0]?.messages.map((entry) => entry.id)).toEqual(['first', 'same-day']);
    expect(english[1]?.messages.map((entry) => entry.id)).toEqual(['next-day']);
    const arabic = groupMessagesByDay(messages, MESSENGER_VIEW_TIME_ZONE, 'ar');
    expect(arabic[0]?.label).toBe('9 مارس 2026');
  });

  it('produces no day groups for an empty thread', () => {
    expect(groupMessagesByDay([], MESSENGER_VIEW_TIME_ZONE, 'en')).toEqual([]);
  });

  it('advances the connection through stream, loss, polling and resync', () => {
    expect(advanceConnection('reconnecting', 'stream-open')).toBe('streaming');
    expect(advanceConnection('streaming', 'stream-lost')).toBe('reconnecting');
    expect(advanceConnection('reconnecting', 'poll-succeeded')).toBe('polling');
    expect(advanceConnection('polling', 'poll-succeeded')).toBe('polling');
    expect(advanceConnection('polling', 'resynced')).toBe('streaming');
  });

  it('guards an empty composer without stripping typed work', () => {
    expect(hasComposableText('   ')).toBe(false);
    expect(hasComposableText('Synthetic draft')).toBe(true);
  });

  it('accepts only published identifier events with a usable cursor', () => {
    const frame = (type: string, sequence: number) => ({ type, sequence });
    expect(isIdentifierEvent(frame('message', 21))).toBe(true);
    expect(isIdentifierEvent(frame('read', 0))).toBe(true);
    expect(isIdentifierEvent(frame('bogus', 21))).toBe(false);
    expect(isIdentifierEvent(frame('message', Number.MAX_SAFE_INTEGER + 1))).toBe(false);
    expect(isIdentifierEvent(frame('message', -1))).toBe(false);
  });
});
