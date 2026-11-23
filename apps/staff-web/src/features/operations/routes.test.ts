import { describe, expect, it } from 'vitest';
import { createRoutes } from '../../app/routes';

describe('operations routes', () => {
  it('registers the queue path before the catch-all', () => {
    const patterns = createRoutes(false).map((route) => route.pattern);
    expect(patterns).toContain('/operations/queues');
    expect(patterns.indexOf('/operations/queues')).toBeLessThan(patterns.indexOf('*'));
  });
});
