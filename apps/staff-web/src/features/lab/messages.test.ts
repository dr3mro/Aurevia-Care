import { describe, expect, it } from 'vitest';
import {
  actionMessageKey,
  labKeys,
  labText,
  stateMessageKey,
} from './messages';
import { ACCESSION_STATES } from './domain';
import type { AccessionAction } from './types';

describe('lab message bundles', () => {
  it('provides a non-empty label in both bundles for every key', () => {
    for (const locale of ['en', 'ar'] as const) {
      for (const key of labKeys) {
        expect(labText(locale, key).length).toBeGreaterThan(0);
      }
    }
    expect(labText('en', 'collect')).not.toBe(labText('ar', 'collect'));
    expect(labText('ar', 'collect')).toMatch(/[\u0600-\u06FF]/);
  });

  it('maps every state and action to a defined message key', () => {
    for (const state of ACCESSION_STATES) {
      expect(labKeys).toContain(stateMessageKey[state]);
    }
    for (const action of ['collect', 'receive', 'reject', 'recollect'] as AccessionAction[]) {
      expect(labKeys).toContain(actionMessageKey[action]);
    }
  });
});
