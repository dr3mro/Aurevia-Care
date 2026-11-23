import { describe, expect, it } from 'vitest';
import type { Locale } from '../../../i18n/messages';
import type { TrackedOrderStatus } from './domain';
import {
  deliveryModeMessageKey,
  statusMessageKey,
  trackingKeys,
  trackingMessages,
  trackingText,
} from './labels';
import type { TrackingMessageKey } from './labels';

const ARABIC_LETTER =
  /[\u0621-\u063A\u0641-\u064A\u066E-\u066F\u0671-\u06D3\u06D5\u06FA-\u06FC\u06FF]/;

describe('tracking message bundles', () => {
  it('keeps identical English and Arabic keys with real translations', () => {
    expect(Object.keys(trackingMessages.en).sort()).toEqual([...trackingKeys].sort());
    expect(Object.keys(trackingMessages.ar).sort()).toEqual([...trackingKeys].sort());
    for (const key of trackingKeys) {
      expect(trackingMessages.en[key].trim().length).toBeGreaterThan(0);
      expect(ARABIC_LETTER.test(trackingMessages.ar[key])).toBe(true);
    }
  });

  it('resolves a label through the locale lookup', () => {
    expect(trackingText('en', 'ordersTab')).toBe(trackingMessages.en.ordersTab);
    expect(trackingText('ar', 'ordersTab')).toBe(trackingMessages.ar.ordersTab);
  });

  it('gives every order status its own translated wording key', () => {
    const expected: Record<TrackedOrderStatus, TrackingMessageKey> = {
      draft: 'statusDraft',
      placed: 'statusPlaced',
      delivered: 'statusDelivered',
      accepted: 'statusAccepted',
      collected_or_scheduled: 'statusCollectedOrScheduled',
      resulted: 'statusResulted',
      cancelled: 'statusCancelled',
    };
    const locales: readonly Locale[] = ['en', 'ar'];
    for (const status of Object.keys(expected) as TrackedOrderStatus[]) {
      const key = statusMessageKey(status);
      expect(key).toBe(expected[status]);
      for (const locale of locales) {
        expect(trackingText(locale, key).trim().length).toBeGreaterThan(0);
      }
    }
  });

  it('reuses the order-entry delivery mode wording', () => {
    expect(deliveryModeMessageKey('electronic')).toBe('electronic');
    expect(deliveryModeMessageKey('print')).toBe('print');
    expect(deliveryModeMessageKey('both')).toBe('both');
  });
});
