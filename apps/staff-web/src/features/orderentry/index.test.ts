import { describe, expect, it } from 'vitest';
import * as orderEntry from './index';

/**
 * The order entry feature's public surface is deliberately tiny: the three
 * parts the chart screen mounts. Nothing else — no helpers, no prop types, no
 * routes — is part of this barrel, so a new export is a deliberate decision.
 */
describe('order entry feature public API', () => {
  it('exports the three order entry parts and nothing else', () => {
    expect(Object.keys(orderEntry).sort()).toEqual([
      'DeliveryChoice',
      'OrderEntryPanel',
      'OrdersTab',
    ]);
    for (const part of Object.values(orderEntry)) {
      expect(typeof part).toBe('function');
    }
  });
});
