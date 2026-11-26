import { useState } from 'react';
import type { ReactElement } from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react';
import type { RenderResult } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { BREAKPOINT_MEDIA_QUERIES } from '../../../theme/breakpoints';
import { ThemeProvider } from '../../../theme/ThemeProvider';
import { StaffMessageLayout } from './StaffMessageLayout';
import type { StaffMessageLayoutProps } from './StaffMessageLayout';

type Band = 'compact' | 'medium' | 'expanded';
type ChangeListener = (event: MediaQueryListEvent) => void;

interface QueryState {
  matches: boolean;
  listeners: Set<ChangeListener>;
}

/**
 * Install one deterministic media-query state per query string. The hook's
 * snapshot, subscription and change events all read the same object, so a
 * resize can be fired without a real viewport. All content is synthetic.
 */
function installMatchMedia(band: Band) {
  const answers: Record<string, boolean> = {
    [BREAKPOINT_MEDIA_QUERIES.medium]: band !== 'compact',
    [BREAKPOINT_MEDIA_QUERIES.expanded]: band === 'expanded',
  };
  const states = new Map<string, QueryState>();
  const stateOf = (query: string): QueryState => {
    let current = states.get(query);
    if (!current) {
      current = { matches: answers[query] ?? false, listeners: new Set() };
      states.set(query, current);
    }
    return current;
  };
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => {
      const current = stateOf(query);
      return {
        get matches() {
          return current.matches;
        },
        media: query,
        onchange: null,
        addEventListener: (_type: string, listener: ChangeListener) => {
          current.listeners.add(listener);
        },
        removeEventListener: (_type: string, listener: ChangeListener) => {
          current.listeners.delete(listener);
        },
        addListener: () => undefined,
        removeListener: () => undefined,
        dispatchEvent: () => false,
      };
    }),
  );
  return (query: string, matches: boolean) => {
    const current = stateOf(query);
    current.matches = matches;
    for (const listener of current.listeners) {
      listener({ matches } as MediaQueryListEvent);
    }
  };
}

/** A synthetic conversation list, thread and composer for one facility. */
function fixturePanes(): Pick<StaffMessageLayoutProps, 'list' | 'detail' | 'composer'> {
  return {
    list: <p>Synthetic conversation list</p>,
    detail: <p>Synthetic thread body</p>,
    composer: <textarea aria-label="Synthetic draft" />,
  };
}

/** Props with deterministic bilingual labels and a spyable back action. */
function propsFor(overrides: Partial<StaffMessageLayoutProps> = {}): StaffMessageLayoutProps {
  return {
    label: 'Synthetic messages',
    ...fixturePanes(),
    detailOpen: false,
    backLabel: 'Back to list',
    onBack: () => undefined,
    ...overrides,
  };
}

function renderLayout(props: StaffMessageLayoutProps = propsFor()): RenderResult {
  return render(
    <ThemeProvider>
      <StaffMessageLayout {...props} />
    </ThemeProvider>,
  );
}

function paneGrid(): HTMLElement {
  return screen.getByTestId('staff-message-panes');
}

function tracksOf(grid: HTMLElement): string[] {
  return grid.style.gridTemplateColumns.match(/minmax\([^)]*\)/g) ?? [];
}

/** Compact detail navigation with an observable caller-side state change. */
function StatefulLayout({ onBack }: { onBack: () => void }): ReactElement {
  const [detailOpen, setDetailOpen] = useState(true);
  const onBackTo = (): void => {
    onBack();
    setDetailOpen(false);
  };
  return (
    <ThemeProvider>
      <StaffMessageLayout {...propsFor({ detailOpen, onBack: onBackTo })} />
    </ThemeProvider>
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
  document.documentElement.removeAttribute('dir');
  document.documentElement.removeAttribute('lang');
});

describe('responsive staff message layout (WEB-07, UX-01, WEB-09 R9)', () => {
  it('expanded renders three panes with a static composer', () => {
    installMatchMedia('expanded');
    renderLayout();

    const grid = paneGrid();
    expect(screen.getByTestId('staff-message-layout').getAttribute('data-breakpoint')).toBe(
      'expanded',
    );
    expect(screen.getByTestId('staff-message-pane-list')).toBeTruthy();
    expect(screen.getByTestId('staff-message-pane-detail')).toBeTruthy();
    expect(screen.getByTestId('staff-message-pane-composer')).toBeTruthy();
    expect(grid.children).toHaveLength(3);
    expect(tracksOf(grid)).toHaveLength(3);
    expect(screen.getByTestId('staff-message-pane-composer').style.position).toBe('');
  });

  it('medium renders two panes with a docked composer at 768 px', () => {
    installMatchMedia('medium');
    renderLayout();

    const grid = paneGrid();
    const composer = screen.getByTestId('staff-message-pane-composer');
    expect(screen.getByTestId('staff-message-layout').getAttribute('data-breakpoint')).toBe(
      'medium',
    );
    expect(screen.getByTestId('staff-message-pane-list')).toBeTruthy();
    expect(screen.getByTestId('staff-message-pane-detail')).toBeTruthy();
    expect(grid.children).toHaveLength(3);
    expect(tracksOf(grid)).toHaveLength(2);
    expect(composer.style.gridColumn).toBe('1 / -1');
    expect(composer.style.position).toBe('sticky');
  });

  it('compact shows only the list when no detail is open', () => {
    installMatchMedia('compact');
    renderLayout();

    expect(screen.getByTestId('staff-message-layout').getAttribute('data-breakpoint')).toBe(
      'compact',
    );
    expect(screen.getByTestId('staff-message-pane-list')).toBeTruthy();
    expect(screen.getByTestId('staff-message-pane-composer')).toBeTruthy();
    expect(screen.queryByTestId('staff-message-pane-detail')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Back to list' })).toBeNull();
    expect(tracksOf(paneGrid())).toHaveLength(1);
  });

  it('does not show a back action on tablet or desktop when a detail is open', () => {
    installMatchMedia('medium');
    const first = renderLayout(propsFor({ detailOpen: true }));
    expect(screen.queryByRole('button', { name: 'Back to list' })).toBeNull();
    expect(screen.getByTestId('staff-message-pane-list')).toBeTruthy();
    expect(screen.getByTestId('staff-message-pane-detail')).toBeTruthy();
    first.unmount();

    installMatchMedia('expanded');
    renderLayout(propsFor({ detailOpen: true }));
    expect(screen.queryByRole('button', { name: 'Back to list' })).toBeNull();
    expect(screen.getByTestId('staff-message-pane-list')).toBeTruthy();
    expect(screen.getByTestId('staff-message-pane-detail')).toBeTruthy();
  });

  it('compact shows the detail and a back action when a detail is open', () => {
    installMatchMedia('compact');
    const onBack = vi.fn();
    renderLayout(propsFor({ detailOpen: true, onBack }));

    expect(screen.queryByTestId('staff-message-pane-list')).toBeNull();
    expect(screen.getByTestId('staff-message-pane-detail')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Back to list' }));
    // The wrapper is controlled: it reports the command, the caller clears its
    // selection. The stateful harness below proves the list then returns.
    expect(onBack).toHaveBeenCalledTimes(1);
  });

  it('back action invokes the caller callback and restores the list', () => {
    installMatchMedia('compact');
    const onBack = vi.fn();
    render(<StatefulLayout onBack={onBack} />);

    fireEvent.click(screen.getByRole('button', { name: 'Back to list' }));
    expect(onBack).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('staff-message-pane-list')).toBeTruthy();
    expect(screen.queryByTestId('staff-message-pane-detail')).toBeNull();
  });
});

describe('no horizontal scroll and keyboard-reachable composer', () => {
  it('uses shrinkable tracks and logical properties at 768 px', () => {
    installMatchMedia('medium');
    renderLayout();

    const root = screen.getByTestId('staff-message-layout');
    const grid = paneGrid();
    // A clipping overflow would make this a scroll container and defeat the
    // sticky composer, so the tracks and wrapping must carry the no-scroll
    // guarantee instead. The painted measurement belongs to Playwright.
    expect(root.style.overflowX).toBe('');
    expect(root.style.overflow).toBe('');
    expect(grid.style.overflowX).toBe('');
    expect(grid.style.overflow).toBe('');
    expect(root.style.scrollPaddingBlockEnd).toBe('var(--sc-space-8, 32px)');
    for (const track of tracksOf(grid)) {
      expect(track.startsWith('minmax(0,')).toBe(true);
    }
    for (const pane of [
      screen.getByTestId('staff-message-pane-list'),
      screen.getByTestId('staff-message-pane-detail'),
      screen.getByTestId('staff-message-pane-composer'),
    ]) {
      expect(pane.style.minInlineSize).toBe('0');
      expect(pane.style.overflowWrap).toBe('anywhere');
    }
  });

  it('docks the composer with safe-area padding below the expanded band', () => {
    installMatchMedia('compact');
    renderLayout(propsFor({ detailOpen: true }));

    const composer = screen.getByTestId('staff-message-pane-composer');
    // DOM/stylesheet check only; painted keyboard behaviour is a Playwright proof.
    expect(composer.style.position).toBe('sticky');
    expect(composer.style.insetBlockEnd).toBe('0');
    expect(composer.style.paddingBlockEnd).toBe('max(8px, var(--safe-block-end, 0px))');
    expect(composer.style.background).toBe('var(--sc-color-surface-default, #ffffff)');
  });

  it('uses logical properties only under right-to-left', () => {
    document.documentElement.setAttribute('dir', 'rtl');
    document.documentElement.setAttribute('lang', 'ar');
    installMatchMedia('medium');
    renderLayout();

    const root = screen.getByTestId('staff-message-layout');
    for (const element of [root, ...Array.from(root.querySelectorAll('*'))] as HTMLElement[]) {
      expect(element.getAttribute('style') ?? '').not.toMatch(
        /(?:margin|padding|border)-(?:left|right)\s*:|text-align\s*:\s*(?:left|right)/,
      );
      expect(element.style.direction).toBe('');
    }
    expect(root.getAttribute('data-breakpoint')).toBe('medium');
    expect(screen.getByText('Synthetic conversation list')).toBeTruthy();
  });
});

describe('rotation preserves work (WEB-09 R9)', () => {
  it('keeps the typed composer text, open detail and focused field across resizes', () => {
    const fire = installMatchMedia('compact');
    renderLayout(propsFor({ detailOpen: true }));

    const field = screen.getByRole('textbox', { name: 'Synthetic draft' });
    fireEvent.change(field, { target: { value: 'Synthetic unsent draft' } });
    field.focus();
    expect(document.activeElement).toBe(field);

    act(() => fire(BREAKPOINT_MEDIA_QUERIES.medium, true));
    expect(screen.getByTestId('staff-message-layout').getAttribute('data-breakpoint')).toBe(
      'medium',
    );
    act(() => fire(BREAKPOINT_MEDIA_QUERIES.expanded, true));
    expect(screen.getByTestId('staff-message-layout').getAttribute('data-breakpoint')).toBe(
      'expanded',
    );
    // A real resize to a phone clears both min-width queries; the expanded
    // band wins while it still matches, so both answers must change together.
    act(() => {
      fire(BREAKPOINT_MEDIA_QUERIES.expanded, false);
      fire(BREAKPOINT_MEDIA_QUERIES.medium, false);
    });
    expect(screen.getByTestId('staff-message-layout').getAttribute('data-breakpoint')).toBe(
      'compact',
    );

    expect(screen.getByTestId('staff-message-pane-detail')).toBeTruthy();
    expect((field as HTMLTextAreaElement).value).toBe('Synthetic unsent draft');
    expect(document.activeElement).toBe(field);
  });
});
