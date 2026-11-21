import { describe, expect, it } from 'vitest';
import { dosingInstructions, filterQuery, hasReason } from './domain';
import { syntheticInboxItem } from './fixtures';

describe('pharmacy inbox domain helpers', () => {
  it('composes dosing instructions from the routed prescription snapshot', () => {
    expect(dosingInstructions(syntheticInboxItem)).toBe('10 mg; oral; twice daily');
  });

  it('omits the status query for the all filter and sends a concrete state otherwise', () => {
    expect(filterQuery('all')).toBeUndefined();
    expect(filterQuery('accepted')).toBe('accepted');
    expect(filterQuery('dispensed')).toBe('dispensed');
  });

  it('requires a non-blank reason or question after trimming', () => {
    expect(hasReason('')).toBe(false);
    expect(hasReason('   ')).toBe(false);
    expect(hasReason('  out of stock  ')).toBe(true);
  });
});
