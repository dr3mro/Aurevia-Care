import { fireEvent, render, screen } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '../../../i18n/I18nProvider';
import type { Locale } from '../../../i18n/messages';
import { BREAKPOINT_MEDIA_QUERIES, BREAKPOINT_WIDTHS } from '../../../theme/breakpoints';
import { ThemeProvider } from '../../../theme/ThemeProvider';
import { layoutText } from './labels';
import { StaffMessageLayout } from './StaffMessageLayout';

/**
 * Install a deterministic matchMedia stand-in for one synthetic viewport width.
 * The answers are set before render: the breakpoint hook caches one snapshot per
 * query and does not re-read the environment between renders.
 */
function installWidth(width: number): void {
  const answers: Record<string, boolean> = {
    [BREAKPOINT_MEDIA_QUERIES.medium]: width >= BREAKPOINT_WIDTHS.medium,
    [BREAKPOINT_MEDIA_QUERIES.expanded]: width >= BREAKPOINT_WIDTHS.expanded,
  };
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({
      matches: answers[query] ?? false,
      media: query,
      onchange: null,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      addListener: () => undefined,
      removeListener: () => undefined,
      dispatchEvent: () => false,
    })),
  );
}

/** Mount the wrapper with synthetic panes under the shell providers. */
function layoutElement(
  width: number,
  detailOpen: boolean,
  onBack: () => void,
  locale: Locale = 'en',
): ReactElement {
  installWidth(width);
  return (
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <StaffMessageLayout
          list={<p>Synthetic list</p>}
          detail={<p>Synthetic detail</p>}
          composer={<textarea aria-label="Synthetic composer" />}
          detailOpen={detailOpen}
          onBack={onBack}
        />
      </I18nProvider>
    </ThemeProvider>
  );
}

/** The grid that holds the visible panes. */
function paneGrid(): HTMLElement {
  const grid = document.querySelector('[data-part="panes"]');
  if (!grid) {
    throw new Error('pane grid was not rendered');
  }
  return grid as HTMLElement;
}

/** The element carrying the active layout band. */
function layoutRoot(): HTMLElement {
  const root = document.querySelector('[data-layout]');
  if (!root) {
    throw new Error('layout root was not rendered');
  }
  return root as HTMLElement;
}

/** The composer region, including its sticky footer on tablet and phone. */
function composerRegion(): HTMLElement {
  const composer = document.querySelector('[data-pane="composer"]');
  if (!composer) {
    throw new Error('composer region was not rendered');
  }
  return composer as HTMLElement;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('staff-message panes per breakpoint', () => {
  it('renders three panes on desktop', () => {
    render(layoutElement(1280, false, vi.fn()));
    expect(layoutRoot().getAttribute('data-breakpoint')).toBe('expanded');
    expect(layoutRoot().getAttribute('data-layout')).toBe('three-pane');
    expect(paneGrid().querySelectorAll('[data-pane]')).toHaveLength(3);
    expect(screen.getByText('Synthetic list')).toBeTruthy();
    expect(screen.getByText('Synthetic detail')).toBeTruthy();
    expect(screen.getByLabelText('Synthetic composer')).toBeTruthy();
    expect(paneGrid().style.gridTemplateColumns).toBe('repeat(3, minmax(0, 1fr))');
    expect(paneGrid().style.gridTemplateColumns).not.toContain('px');
    expect(composerRegion().parentElement).toBe(paneGrid());
    expect(composerRegion().style.position).toBe('');
  });

  it('keeps the desktop layout and hides Back when a detail is already selected', () => {
    render(layoutElement(1280, true, vi.fn()));
    expect(screen.queryByRole('button', { name: layoutText('en', 'back') })).toBeNull();
    expect(paneGrid().querySelectorAll('[data-pane]')).toHaveLength(3);
    expect(composerRegion().parentElement).toBe(paneGrid());
    expect(composerRegion().style.position).toBe('');
  });

  it('renders two panes and a sticky composer footer at 768 px without fixed tracks', () => {
    render(layoutElement(768, false, vi.fn()));
    expect(layoutRoot().getAttribute('data-breakpoint')).toBe('medium');
    expect(layoutRoot().getAttribute('data-layout')).toBe('two-pane');
    const grid = paneGrid();
    expect(grid.querySelectorAll('[data-pane]')).toHaveLength(2);
    expect(grid.style.gridTemplateColumns).toBe('repeat(2, minmax(0, 1fr))');
    expect(grid.style.gridTemplateColumns).not.toContain('px');
    for (const pane of grid.querySelectorAll('[data-pane]')) {
      expect(['0', '0px']).toContain((pane as HTMLElement).style.minInlineSize);
    }
    expect(composerRegion().parentElement).not.toBe(grid);
    expect(composerRegion().style.position).toBe('sticky');
  });

  it('renders one list pane and no back action on a narrow screen without a selection', () => {
    render(layoutElement(320, false, vi.fn()));
    expect(layoutRoot().getAttribute('data-breakpoint')).toBe('compact');
    expect(layoutRoot().getAttribute('data-layout')).toBe('single-pane');
    expect(paneGrid().querySelectorAll('[data-pane]')).toHaveLength(1);
    expect(paneGrid().style.gridTemplateColumns).toBe('minmax(0, 1fr)');
    expect(paneGrid().style.gridTemplateColumns).not.toContain('px');
    expect(screen.getByText('Synthetic list')).toBeTruthy();
    expect(screen.queryByText('Synthetic detail')).toBeNull();
    expect(screen.queryByRole('button', { name: layoutText('en', 'back') })).toBeNull();
    expect(document.querySelector('[data-pane="composer"]')).toBeNull();
  });

  it('renders one detail pane with a back action and composer on a narrow screen with a selection', () => {
    render(layoutElement(320, true, vi.fn()));
    expect(layoutRoot().getAttribute('data-layout')).toBe('single-pane');
    expect(paneGrid().querySelectorAll('[data-pane]')).toHaveLength(1);
    expect(screen.getByText('Synthetic detail')).toBeTruthy();
    expect(screen.queryByText('Synthetic list')).toBeNull();
    expect(screen.getByRole('button', { name: layoutText('en', 'back') })).toBeTruthy();
    expect(composerRegion().style.position).toBe('sticky');
  });
});

describe('compact back action', () => {
  it('reports the back action and restores the list after the parent clears the selection', () => {
    const onBack = vi.fn();
    const view = render(layoutElement(320, true, onBack));
    fireEvent.click(screen.getByRole('button', { name: layoutText('en', 'back') }));
    expect(onBack).toHaveBeenCalledTimes(1);
    view.rerender(layoutElement(320, false, onBack));
    expect(screen.getByText('Synthetic list')).toBeTruthy();
    expect(screen.queryByText('Synthetic detail')).toBeNull();
    expect(screen.queryByRole('button', { name: layoutText('en', 'back') })).toBeNull();
  });
});

describe('keyboard clearance and bilingual names', () => {
  it('reserves scroll space and pins the composer above the keyboard on tablet', () => {
    render(layoutElement(768, false, vi.fn()));
    expect(layoutRoot().style.scrollPaddingBlockEnd).not.toBe('');
    expect(['0', '0px']).toContain(composerRegion().style.insetBlockEnd);
    expect(screen.getByRole('region', { name: layoutText('en', 'listPane') })).toBeTruthy();
    expect(screen.getByRole('region', { name: layoutText('en', 'detailPane') })).toBeTruthy();
    expect(screen.getByRole('region', { name: layoutText('en', 'composerPane') })).toBeTruthy();
  });

  it('uses Arabic accessible names and document direction under RTL', () => {
    render(layoutElement(320, true, vi.fn(), 'ar'));
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByRole('button', { name: layoutText('ar', 'back') })).toBeTruthy();
    expect(screen.getByRole('region', { name: layoutText('ar', 'detailPane') })).toBeTruthy();
    expect(screen.getByRole('region', { name: layoutText('ar', 'composerPane') })).toBeTruthy();
  });
});
