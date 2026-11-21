/**
 * Process-local store for tracker items (PAT-02).
 *
 * Tracker projections may be held for the lifetime of one app process so a
 * screen can render them, but they are never written to a disk cache,
 * AsyncStorage or the OS secure store. The sign-out and idle-timeout handlers
 * call {@link TrackerCache.clear} so no item outlives the session.
 */
import type { TrackerItem } from './types';

/** In-memory cache of the items currently shown in the patient tracker. */
export class TrackerCache {
  private readonly items = new Map<string, TrackerItem>();

  /**
   * Hold one item for the current app process.
   *
   * @param item tracker projection keyed by its item identifier
   */
  put(item: TrackerItem): void {
    this.items.set(item.itemId, item);
  }

  /**
   * Read a held item.
   *
   * @param itemId identifier of the item
   * @returns the item, or `undefined` when absent or after a clear
   */
  get(itemId: string): TrackerItem | undefined {
    return this.items.get(itemId);
  }

  /** Discard every held item (sign-out / idle timeout). */
  clear(): void {
    this.items.clear();
  }

  /**
   * Number of items currently held.
   *
   * @returns the count of cached items
   */
  size(): number {
    return this.items.size;
  }
}
