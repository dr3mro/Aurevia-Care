import { describe, expect, it } from 'vitest';
import type { DeliveryMode } from '../entry/domain';
import {
  canRetry,
  confirmKey,
  deliveryModes,
  milestoneKey,
  modeLabelKey,
  needsRecovery,
  partnerLang,
  receivesKey,
} from './domain';
import type { DeliveryMilestone, SendResult } from './domain';
import { deliveryKeys, deliveryText } from './labels';
import type { DeliveryMessageKey } from './labels';

const modes: readonly DeliveryMode[] = ['electronic', 'print', 'both'];

const retryCases: readonly (readonly [SendResult, boolean])[] = [
  [{ state: 'failed', retrySafe: true }, true],
  [{ state: 'failed', retrySafe: false }, false],
  [{ state: 'idle' }, false],
  [{ state: 'sending' }, false],
  [{ state: 'placed', timeline: ['placed', 'printed'] }, false],
  [{ state: 'ambiguous', correlationId: 'SYN-CORR-9' }, false],
];

const recoveryCases: readonly (readonly [SendResult, boolean])[] = [
  [{ state: 'failed', retrySafe: true }, true],
  [{ state: 'failed', retrySafe: false }, true],
  [{ state: 'ambiguous' }, true],
  [{ state: 'idle' }, false],
  [{ state: 'sending' }, false],
  [{ state: 'placed', timeline: ['placed'] }, false],
];

const modeMappings: readonly (readonly [string, Record<DeliveryMode, DeliveryMessageKey>])[] = [
  ['mode label', modeLabelKey],
  ['patient-receive description', receivesKey],
  ['worded confirm', confirmKey],
];

describe('delivery choice domain', () => {
  it('offers the three order delivery modes in a stable order', () => {
    expect([...deliveryModes]).toEqual([...modes]);
  });

  it.each(retryCases)('allows a retry only after a known safe failure: %#', (result, expected) => {
    expect(canRetry(result)).toBe(expected);
  });

  it.each(recoveryCases)('needs a recovery decision for %#', (result, expected) => {
    expect(needsRecovery(result)).toBe(expected);
  });

  it.each(modeMappings)('maps every mode to a distinct %s message key', (_label, mapping) => {
    const keys = modes.map((mode) => mapping[mode]);
    expect(new Set(keys).size).toBe(modes.length);
    for (const key of keys) {
      expect(deliveryKeys).toContain(key);
      expect(deliveryText('en', key).length).toBeGreaterThan(0);
    }
  });

  it('maps every delivery milestone to a distinct worded label', () => {
    const milestones: readonly DeliveryMilestone[] = [
      'placed',
      'transmitted',
      'delivered',
      'printed',
    ];
    const keys = milestones.map((milestone) => milestoneKey[milestone]);
    expect(new Set(keys).size).toBe(milestones.length);
    for (const key of keys) {
      expect(deliveryKeys).toContain(key);
      expect(deliveryText('en', key).length).toBeGreaterThan(0);
    }
  });

  it('derives the partner name language from its script for screen readers', () => {
    expect(partnerLang('شريك المختبر التجريبي', 'en')).toBe('ar');
    expect(partnerLang('Synthetic Lab Partner', 'en')).toBe('en');
    expect(partnerLang('Synthetic Lab Partner', 'ar')).toBe('ar');
  });
});
