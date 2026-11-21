import { describe, expect, it } from 'vitest';
import { routes } from './routes';

/**
 * Printing adds no screens of its own: the buttons are mounted on the
 * prescription, order and chart screens owned by other features. Registering an
 * empty route module keeps the feature discoverable by the shared route glob
 * without claiming a path or shadowing another screen.
 */
describe('print feature routes', () => {
  it('contributes no standalone screens', () => {
    expect(routes).toEqual([]);
  });
});
