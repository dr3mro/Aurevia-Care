import { useState } from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '../../../i18n/I18nProvider';
import { ThemeProvider } from '../../../theme/ThemeProvider';
import { layoutText } from './layoutText';
import { MessagingLayout, useMessagingNavigation } from './MessagingLayout';
import type { MessagingLayoutProps } from './MessagingLayout';

/** The small part of MediaQueryList that the breakpoint hook consumes. */
interface StubQueryList {
  matches: boolean;
  addEventListener: (type: string, listener: (event: { matches: boolean }) => void) => void;
  removeEventListener: (type: string, listener: (event: { matches: boolean }) => void) => void;
}

/** A viewport whose media query lists are stable objects with change events. */
interface Viewport {
  setWidth: (width: number) => void;
}

/** Minimum width named by a media query; unrelated queries never match. */
function thresholdFor(query: string): number {
  if (query.includes('1280')) {
    return 1280;
  }
  if (query.includes('768')) {
    return 768;
  }
  return Number.POSITIVE_INFINITY;
}

/**
 * Install a query-aware matchMedia stub. `useBreakpoint` caches a snapshot until
 * a change event fires on the object captured at subscribe time, so the same
 * stub objects must be returned on every call and reused when the width moves.
 */
function installViewport(width: number): Viewport {
  const queries = new Map<
    string,
    { list: StubQueryList; listeners: ((event: { matches: boolean }) => void)[] }
  >();
  vi.stubGlobal('matchMedia', (query: string) => {
    const existing = queries.get(query);
    if (existing !== undefined) {
      return existing.list;
    }
    const listeners: ((event: { matches: boolean }) => void)[] = [];
    const list: StubQueryList = {
      matches: width >= thresholdFor(query),
      addEventListener: (_type, listener) => {
        listeners.push(listener);
      },
      removeEventListener: (_type, listener) => {
        const index = listeners.indexOf(listener);
        if (index >= 0) {
          listeners.splice(index, 1);
        }
      },
    };
    queries.set(query, { list, listeners });
    return list;
  });
  return {
    setWidth: (next) => {
      for (const [query, entry] of queries) {
        const matches = next >= thresholdFor(query);
        if (entry.list.matches === matches) {
          continue;
        }
        entry.list.matches = matches;
        for (const listener of entry.listeners) {
          listener({ matches });
        }
      }
    },
  };
}

/** A stateful list child that can open the detail pane through the hook. */
function ListWithNavigation({ openLabel = layoutText('en', 'detailPane') }: { readonly openLabel?: string }) {
  const navigation = useMessagingNavigation();
  const [value, setValue] = useState('synthetic draft');
  return (
    <div>
      <input aria-label="Synthetic draft" value={value} onChange={(event) => setValue(event.target.value)} />
      <button type="button" onClick={navigation.openDetail}>
        {openLabel}
      </button>
      <span data-stage-child={navigation.stage} />
    </div>
  );
}

/** Probe for the hook's fail-closed path. */
function NavigationProbe() {
  useMessagingNavigation();
  return <span>probe</span>;
}

/** Render the layout under the shared providers at a chosen locale. */
function wrap(overrides: Partial<MessagingLayoutProps> = {}, locale: 'en' | 'ar' = 'en') {
  const props: MessagingLayoutProps = {
    title: 'Synthetic messages',
    locale,
    list: { label: layoutText(locale, 'listPane'), content: <ListWithNavigation /> },
    detail: { label: layoutText(locale, 'detailPane'), content: <p>Synthetic thread</p> },
    auxiliary: { label: layoutText(locale, 'auxiliaryPane'), content: <p>Synthetic folders</p> },
    composer: <p>Synthetic composer</p>,
    ...overrides,
  };
  const view = render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <MessagingLayout {...props} />
      </I18nProvider>
    </ThemeProvider>,
  );
  return { ...view, props };
}

/** Pane regions that are not hidden from the accessibility tree. */
function visiblePanes(container: HTMLElement): HTMLElement[] {
  return Array.from(container.querySelectorAll<HTMLElement>('[data-pane]')).filter(
    (pane) => !pane.hasAttribute('hidden'),
  );
}

/** Dispatch a simulated resize inside React's act scope. */
function resizeViewport(viewport: Viewport, width: number): void {
  act(() => {
    viewport.setWidth(width);
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('MessagingLayout pane counts', () => {
  it('renders three labelled panes at the expanded breakpoint', () => {
    installViewport(1280);
    const { container } = wrap();
    const root = container.querySelector<HTMLElement>('[data-layout-scroll]');
    expect(root?.getAttribute('data-breakpoint')).toBe('expanded');
    expect(root?.getAttribute('data-stage')).toBe('list');
    expect(visiblePanes(container).map((pane) => pane.getAttribute('data-pane'))).toEqual([
      'list',
      'detail',
      'auxiliary',
    ]);
    expect(screen.queryByTestId('synthetic-notice')).toBeNull();
    for (const id of ['list', 'detail', 'auxiliary']) {
      expect(screen.getByRole('region', { name: layoutText('en', `${id}Pane` as 'listPane') })).toBeTruthy();
    }
  });

  it('renders two panes at the medium breakpoint and omits the auxiliary pane', () => {
    installViewport(768);
    const { container } = wrap();
    const root = container.querySelector<HTMLElement>('[data-layout-scroll]');
    expect(root?.getAttribute('data-breakpoint')).toBe('medium');
    expect(visiblePanes(container).map((pane) => pane.getAttribute('data-pane'))).toEqual([
      'list',
      'detail',
    ]);
    expect(container.querySelector('[data-pane="auxiliary"]')).toBeNull();
    expect(screen.queryByRole('button', { name: layoutText('en', 'back') })).toBeNull();
  });

  it('renders one pane at the compact breakpoint and opens the detail pane from the list', () => {
    installViewport(320);
    const { container } = wrap();
    const root = container.querySelector<HTMLElement>('[data-layout-scroll]');
    expect(root?.getAttribute('data-breakpoint')).toBe('compact');
    expect(visiblePanes(container).map((pane) => pane.getAttribute('data-pane'))).toEqual(['list']);
    expect(container.querySelector('[data-pane="detail"]')?.hasAttribute('hidden')).toBe(true);

    fireEvent.click(screen.getByRole('button', { name: layoutText('en', 'detailPane') }));
    expect(root?.getAttribute('data-stage')).toBe('detail');
    expect(visiblePanes(container).map((pane) => pane.getAttribute('data-pane'))).toEqual(['detail']);
    expect(screen.getByRole('button', { name: layoutText('en', 'back') })).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: layoutText('en', 'back') }));
    expect(root?.getAttribute('data-stage')).toBe('list');
    expect(visiblePanes(container).map((pane) => pane.getAttribute('data-pane'))).toEqual(['list']);
    expect(screen.queryByRole('button', { name: layoutText('en', 'back') })).toBeNull();
  });

  it('does not offer a back action at expanded', () => {
    installViewport(1280);
    wrap();
    expect(screen.queryByRole('button', { name: layoutText('en', 'back') })).toBeNull();
  });

  it('renders two panes without an auxiliary pane at expanded', () => {
    installViewport(1280);
    const { container } = wrap({ auxiliary: undefined });
    expect(visiblePanes(container).map((pane) => pane.getAttribute('data-pane'))).toEqual([
      'list',
      'detail',
    ]);
    expect(container.querySelector('[data-pane="auxiliary"]')).toBeNull();
    expect(container.querySelector('[data-panes]')?.getAttribute('style')).toContain(
      'grid-template-columns: minmax(0, 1fr) minmax(0, 1fr)',
    );
  });
});

describe('MessagingLayout reflow and keyboard behaviour', () => {
  it('uses only wrapping grid tracks and shrinkable panes at 768 px', () => {
    installViewport(768);
    const { container } = wrap();
    const grid = container.querySelector<HTMLElement>('[data-panes]');
    const style = grid?.getAttribute('style') ?? '';
    expect(style).toContain('grid-template-columns: minmax(0, 1fr) minmax(0, 1fr)');
    expect(style).not.toContain('220px');
    for (const pane of visiblePanes(container)) {
      const style = pane.getAttribute('style') ?? '';
      expect(style).toContain('min-inline-size: 0');
      expect(style).toContain('overflow-wrap: anywhere');
    }
    const root = container.querySelector<HTMLElement>('[data-layout-scroll]');
    expect(root?.getAttribute('style')).toContain('overflow-inline: clip');
    expect(root?.getAttribute('style')).toContain('overflow-block: auto');
  });

  it('keeps a sticky composer above the keyboard in each compact stage and at medium', () => {
    const viewport = installViewport(320);
    const { container } = wrap();
    const composer = container.querySelector<HTMLElement>('[data-sticky-composer]');
    const root = container.querySelector<HTMLElement>('[data-layout-scroll]');
    const assertSticky = (): void => {
      const style = composer?.getAttribute('style') ?? '';
      expect(style).toContain('position: sticky');
      expect(style).toContain('inset-block-end: 0');
      expect(root?.getAttribute('style')).toContain('calc(16rem + 4px)');
      expect(root?.getAttribute('style')).toContain('scroll-padding-block-end');
      expect(root?.getAttribute('style')).toContain('display: block');
    };
    assertSticky();
    fireEvent.click(screen.getByRole('button', { name: layoutText('en', 'detailPane') }));
    assertSticky();
    resizeViewport(viewport, 768);
    assertSticky();
  });

  it('renders notices between the title and the pane grid', () => {
    installViewport(1280);
    const { container } = wrap({
      notices: <p data-testid="synthetic-notice">Synthetic emergency notice</p>,
    });
    const grid = container.querySelector<HTMLElement>('[data-panes]');
    expect(screen.getByTestId('synthetic-notice').textContent).toBe('Synthetic emergency notice');
    expect(grid?.previousElementSibling).toBe(screen.getByTestId('synthetic-notice'));
  });
});

describe('MessagingLayout state and localization', () => {
  it('preserves stage and typed work when the breakpoint changes and returns', () => {
    const viewport = installViewport(320);
    const { container } = wrap();
    const root = container.querySelector<HTMLElement>('[data-layout-scroll]');
    const input = screen.getByLabelText<HTMLInputElement>('Synthetic draft');
    fireEvent.change(input, { target: { value: 'typed work' } });
    fireEvent.click(screen.getByRole('button', { name: layoutText('en', 'detailPane') }));
    expect(root?.getAttribute('data-stage')).toBe('detail');
    expect(screen.getByLabelText<HTMLInputElement>('Synthetic draft').value).toBe('typed work');

    resizeViewport(viewport, 768);
    expect(root?.getAttribute('data-breakpoint')).toBe('medium');
    expect(root?.getAttribute('data-stage')).toBe('detail');
    expect(screen.queryByRole('button', { name: layoutText('en', 'back') })).toBeNull();
    expect(screen.getByLabelText<HTMLInputElement>('Synthetic draft').value).toBe('typed work');

    resizeViewport(viewport, 320);
    expect(root?.getAttribute('data-breakpoint')).toBe('compact');
    expect(root?.getAttribute('data-stage')).toBe('detail');
    expect(screen.getByRole('button', { name: layoutText('en', 'back') })).toBeTruthy();
    expect(screen.getByLabelText<HTMLInputElement>('Synthetic draft').value).toBe('typed work');
  });

  it('uses the Arabic back label on a narrow screen', () => {
    installViewport(320);
    wrap(
      {
        list: {
          label: layoutText('ar', 'listPane'),
          content: <ListWithNavigation openLabel={layoutText('ar', 'detailPane')} />,
        },
      },
      'ar',
    );
    fireEvent.click(screen.getByRole('button', { name: layoutText('ar', 'detailPane') }));
    expect(screen.getByRole('button', { name: layoutText('ar', 'back') })).toBeTruthy();
  });

  it('fails closed when the navigation hook has no layout provider', () => {
    expect(() => render(<NavigationProbe />)).toThrow(
      'useMessagingNavigation must be used inside MessagingLayout',
    );
  });
});
