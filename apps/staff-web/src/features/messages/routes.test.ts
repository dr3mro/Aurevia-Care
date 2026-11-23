import { describe, expect, it } from 'vitest';
import { createRoutes } from '../../app/routes';

describe('unit inbox routes', () => {
  it('registers the messages path before the catch-all', () => {
    const patterns = createRoutes(false).map((route) => route.pattern);
    expect(patterns).toContain('/messages');
    expect(patterns.indexOf('/messages')).toBeLessThan(patterns.indexOf('*'));
  });
});
