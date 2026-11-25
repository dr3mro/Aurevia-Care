import { describe, expect, it } from 'vitest';
import { layoutFor } from './panes';

describe('layoutFor', () => {
  it('uses one list pane and a drawer at compact width', () => {
    const layout = layoutFor(767, false);
    expect(layout.breakpoint).toBe('compact');
    expect(layout.visiblePanes).toEqual(['list']);
    expect(layout.drawerPanes).toEqual(['auxiliary']);
    expect(layout.showBack).toBe(false);
    expect(layout.showDrawerToggle).toBe(true);
    expect(layout.gridTemplateColumns).toBe('minmax(0, 1fr)');
  });

  it('keeps the list and detail panes and draws in the auxiliary pane at 768 px', () => {
    const layout = layoutFor(768, false);
    expect(layout.breakpoint).toBe('medium');
    expect(layout.visiblePanes).toEqual(['list', 'detail']);
    expect(layout.drawerPanes).toEqual(['auxiliary']);
    expect(layout.showBack).toBe(false);
    expect(layout.showDrawerToggle).toBe(true);
    expect(layout.gridTemplateColumns).toBe('minmax(0, 1fr) minmax(0, 1fr)');
    expect(layout.gridTemplateColumns).not.toMatch(/\d+px/);
  });

  it('treats 1279 px as the last tablet width', () => {
    expect(layoutFor(1279, false).breakpoint).toBe('medium');
    expect(layoutFor(1279, false).visiblePanes).toEqual(['list', 'detail']);
  });

  it('shows all three panes and no drawer at 1280 px', () => {
    const layout = layoutFor(1280, false);
    expect(layout.breakpoint).toBe('expanded');
    expect(layout.visiblePanes).toEqual(['auxiliary', 'list', 'detail']);
    expect(layout.drawerPanes).toEqual([]);
    expect(layout.showBack).toBe(false);
    expect(layout.showDrawerToggle).toBe(false);
    expect(layout.gridTemplateColumns).toMatch(/^minmax\(0, /);
  });

  it('replaces the list with the detail pane and a Back action at compact width', () => {
    const layout = layoutFor(320, true);
    expect(layout.visiblePanes).toEqual(['detail']);
    expect(layout.drawerPanes).toEqual(['auxiliary', 'list']);
    expect(layout.showBack).toBe(true);
  });

  it('never shows Back without an open detail', () => {
    expect(layoutFor(320, false).showBack).toBe(false);
    expect(layoutFor(768, true).showBack).toBe(false);
    expect(layoutFor(1280, true).showBack).toBe(false);
  });
});
