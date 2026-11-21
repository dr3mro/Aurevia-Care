import { describe, expect, it } from 'vitest';
import * as printButtons from './index';

/**
 * The print feature's public surface is deliberately tiny: the three reusable
 * buttons the owning screens mount. Nothing else — no helpers, no types, no
 * routes — is part of this barrel, so a new export is a deliberate decision.
 */
describe('print feature public API', () => {
  it('exports the three print buttons and nothing else', () => {
    expect(Object.keys(printButtons).sort()).toEqual([
      'PrintChartButton',
      'PrintOrderButton',
      'PrintPrescriptionButton',
    ]);
    for (const button of Object.values(printButtons)) {
      expect(typeof button).toBe('function');
    }
  });
});
