import { describe, expect, it } from 'vitest';
import { columnsFor, paneVisibility } from './layoutModel';

/** Every combination of breakpoint and optional auxiliary pane. */
const combinations = [
  ['compact', true],
  ['compact', false],
  ['medium', true],
  ['medium', false],
  ['expanded', true],
  ['expanded', false],
] as const;

describe('columnsFor', () => {
  it('uses one wrapping track at compact', () => {
    expect(columnsFor('compact', true)).toBe('minmax(0, 1fr)');
    expect(columnsFor('compact', false)).toBe('minmax(0, 1fr)');
  });

  it('uses two wrapping tracks at medium even when an auxiliary pane exists', () => {
    expect(columnsFor('medium', true)).toBe('minmax(0, 1fr) minmax(0, 1fr)');
    expect(columnsFor('medium', false)).toBe('minmax(0, 1fr) minmax(0, 1fr)');
  });

  it('uses three wrapping tracks at expanded with an auxiliary pane', () => {
    expect(columnsFor('expanded', true)).toBe(
      'minmax(0, 1fr) minmax(0, 1fr) minmax(0, 1fr)',
    );
  });

  it('uses two wrapping tracks at expanded without an auxiliary pane', () => {
    expect(columnsFor('expanded', false)).toBe('minmax(0, 1fr) minmax(0, 1fr)');
  });

  it('never emits a fixed-width or fractional-minimum track', () => {
    for (const [breakpoint, hasAuxiliary] of combinations) {
      const columns = columnsFor(breakpoint, hasAuxiliary);
      const tracks = columns.match(/minmax\(0, 1fr\)/g) ?? [];
      expect(tracks.length).toBeGreaterThan(0);
      expect(tracks.join(' ')).toBe(columns);
      expect(columns).not.toContain('px');
    }
  });
});

describe('paneVisibility', () => {
  it('shows all three panes at expanded in either compact stage', () => {
    expect(paneVisibility('expanded', 'list')).toEqual({ list: true, detail: true, auxiliary: true });
    expect(paneVisibility('expanded', 'detail')).toEqual({ list: true, detail: true, auxiliary: true });
  });

  it('shows list and detail only at medium in either compact stage', () => {
    expect(paneVisibility('medium', 'list')).toEqual({ list: true, detail: true, auxiliary: false });
    expect(paneVisibility('medium', 'detail')).toEqual({ list: true, detail: true, auxiliary: false });
  });

  it('shows only the list at compact in the list stage', () => {
    expect(paneVisibility('compact', 'list')).toEqual({ list: true, detail: false, auxiliary: false });
  });

  it('shows only the detail at compact in the detail stage', () => {
    expect(paneVisibility('compact', 'detail')).toEqual({ list: false, detail: true, auxiliary: false });
  });
});
