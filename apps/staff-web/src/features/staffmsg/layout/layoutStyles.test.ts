import { describe, expect, it } from 'vitest';
import { paneLayout } from './layoutStyles';

describe('paneLayout breakpoints', () => {
  it('uses three columns on desktop', () => {
    const layout = paneLayout('expanded');
    expect(layout.shape).toBe('triple');
    expect(layout.root.gridTemplateColumns).toBe('minmax(0, 1fr) minmax(0, 2fr) minmax(0, 1fr)');
    expect(layout.root.gridTemplateRows).toBe('auto');
    expect(layout.placement.list.gridColumn).toBe('1');
    expect(layout.placement.detail.gridColumn).toBe('2');
    expect(layout.placement.list.gridRow).toBe('1');
    expect(layout.placement.detail.gridRow).toBe('1');
    expect(layout.placement.composer.gridColumn).toBe('3');
  });

  it('uses two columns at 768 px and wraps long values instead of clipping', () => {
    const layout = paneLayout('medium');
    expect(layout.shape).toBe('paired');
    expect(layout.root.gridTemplateColumns).toBe('minmax(0, 1fr) minmax(0, 2fr)');
    expect(layout.root.inlineSize).toBe('100%');
    expect(layout.root.maxInlineSize).toBe('100%');
    expect(layout.root.overflowX).toBeUndefined();
    expect(layout.pane.minInlineSize).toBe(0);
    expect(layout.pane.maxInlineSize).toBe('100%');
    expect(layout.pane.overflowWrap).toBe('anywhere');
    expect(layout.root.gridTemplateRows).toBe('auto auto');
    expect(layout.placement.list.gridColumn).toBe('1');
    expect(layout.placement.detail.gridColumn).toBe('2');
    expect(layout.placement.list.gridRow).toBe('1 / span 2');
    expect(layout.placement.detail.gridRow).toBe('1');
    expect(layout.placement.composer.gridColumn).toBe('2');
    expect(layout.placement.composer.gridRow).toBe('2');
  });

  it('uses a single column on narrow screens', () => {
    const layout = paneLayout('compact');
    expect(layout.shape).toBe('single');
    expect(layout.root.gridTemplateColumns).toBe('minmax(0, 1fr)');
    expect(layout.placement.list.gridRow).toBe('1 / span 2');
    expect(layout.placement.composer.gridColumn).toBe('1');
  });

  it('reserves keyboard space on the scroll container and tokens on the wrapper', () => {
    const { root } = paneLayout('medium');
    expect(root.scrollPaddingBlockEnd).toBe('var(--sc-space-8, 32px)');
    expect(root.display).toBe('grid');
    expect(root.gap).toBe('var(--sc-space-4, 16px)');
  });

  it('docks the composer above the on-screen keyboard', () => {
    const { composer } = paneLayout('compact');
    expect(composer.position).toBe('sticky');
    expect(composer.insetBlockEnd).toBe(0);
    expect(composer.paddingBlock).toBe('var(--sc-space-3, 12px)');
    expect(composer.paddingInline).toBe('var(--sc-space-4, 16px)');
    expect(composer.paddingBlockEnd).toBe(
      'max(var(--sc-space-3, 12px), var(--safe-block-end, 0px))',
    );
    expect(composer.background).toBe('var(--sc-color-surface-default, #ffffff)');
  });
});
