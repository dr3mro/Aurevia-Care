import { TrackerCache } from './cache';
import type { TrackerItem } from './types';

const item: TrackerItem = {
  itemId: 'item-1',
  kind: 'prescription',
  steps: [{ key: 'placed', releasedToPatient: true }],
};

describe('TrackerCache sign-out clearing (PAT-02)', () => {
  it('stores and reads an item by id', () => {
    const cache = new TrackerCache();
    cache.put(item);
    expect(cache.get('item-1')).toEqual(item);
    expect(cache.size()).toBe(1);
  });

  it('returns undefined for an id that was never stored', () => {
    const cache = new TrackerCache();
    expect(cache.get('missing')).toBeUndefined();
    expect(cache.size()).toBe(0);
  });

  it('clears every tracked item so nothing survives sign-out', () => {
    const cache = new TrackerCache();
    cache.put(item);
    cache.put({ ...item, itemId: 'item-2' });
    expect(cache.size()).toBe(2);
    cache.clear();
    expect(cache.size()).toBe(0);
    expect(cache.get('item-1')).toBeUndefined();
    expect(cache.get('item-2')).toBeUndefined();
  });

  it('can be used again after a clear', () => {
    const cache = new TrackerCache();
    cache.put(item);
    cache.clear();
    cache.put(item);
    expect(cache.get('item-1')).toEqual(item);
    expect(cache.size()).toBe(1);
  });
});
