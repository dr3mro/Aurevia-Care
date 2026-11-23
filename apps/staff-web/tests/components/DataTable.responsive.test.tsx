import { act, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DataTable } from '../../src/components/DataTable';
import { BREAKPOINT_MEDIA_QUERIES } from '../../src/theme/breakpoints';
import { ThemeProvider } from '../../src/theme/ThemeProvider';

type ChangeListener = (event: MediaQueryListEvent) => void;

interface QueryState {
  matches: boolean;
  listeners: Set<ChangeListener>;
}

/**
 * Deterministic matchMedia stand-in keyed by media query string. One state
 * object is reused per query so the hook's initializer and effect see the same
 * listeners. All data below is synthetic.
 */
function installMatchMedia(initial: Record<string, boolean>) {
  const states = new Map<string, QueryState>();
  const state = (query: string): QueryState => {
    let current = states.get(query);
    if (!current) {
      current = { matches: initial[query] ?? false, listeners: new Set() };
      states.set(query, current);
    }
    return current;
  };
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => {
      const current = state(query);
      return {
        get matches() {
          return current.matches;
        },
        addEventListener: (_type: string, listener: ChangeListener) => {
          current.listeners.add(listener);
        },
        removeEventListener: (_type: string, listener: ChangeListener) => {
          current.listeners.delete(listener);
        },
      };
    }),
  );
  return {
    state,
    fire(query: string, matches: boolean) {
      const current = state(query);
      current.matches = matches;
      for (const listener of current.listeners) {
        listener({ matches } as MediaQueryListEvent);
      }
    },
  };
}

afterEach(() => {
  vi.unstubAllGlobals();
  document.documentElement.removeAttribute('dir');
  document.documentElement.removeAttribute('lang');
});

const columns = [
  { key: 'code', header: 'Code' },
  { key: 'name', header: 'Name' },
];

const LONG_ENGLISH =
  'A deliberately long synthetic facility name that must wrap without clipping or overlap';
const LONG_ARABIC = 'اسم منشأة تجريبي طويل جداً يجب أن يلتف دون قص أو تداخل';

function renderTable(rows: Record<string, string>[] = [{ code: 'SYN-0001', name: LONG_ENGLISH }]) {
  return render(
    <ThemeProvider>
      <DataTable caption="Facilities" columns={columns} rows={rows} emptyLabel="No facilities" />
    </ThemeProvider>,
  );
}

function compactQueries() {
  return {
    [BREAKPOINT_MEDIA_QUERIES.medium]: false,
    [BREAKPOINT_MEDIA_QUERIES.expanded]: false,
  };
}

describe('responsive DataTable (WEB-07, UX-01)', () => {
  it('wraps the table in a labelled, keyboard-scrollable region on compact phones', () => {
    installMatchMedia(compactQueries());
    renderTable();

    const region = screen.getByRole('region', { name: 'Facilities' });
    expect(region.className).toContain('table-region');
    expect(region.getAttribute('tabindex')).toBe('0');
    expect(region.tabIndex).toBe(0);

    const table = within(region).getByRole('table', { name: 'Facilities' });
    expect(within(table).getByRole('columnheader', { name: 'Code' }).getAttribute('scope')).toBe(
      'col',
    );
    expect(within(table).getByRole('columnheader', { name: 'Name' }).getAttribute('scope')).toBe(
      'col',
    );
    expect(within(table).getByRole('cell', { name: 'SYN-0001' })).toBeTruthy();
    expect(within(table).getByRole('cell', { name: LONG_ENGLISH })).toBeTruthy();

    // Keyboard order: the labelled region is the only tab stop; native cells
    // and headers keep their semantics and are not independently focusable.
    for (const cell of within(table).getAllByRole('cell')) {
      expect(cell.tabIndex).toBe(-1);
      expect(cell.style.overflowWrap).toBe('anywhere');
      expect(cell.style.whiteSpace).toBe('normal');
    }
    for (const header of within(table).getAllByRole('columnheader')) {
      expect(header.tabIndex).toBe(-1);
    }
  });

  it('keeps the defined empty state inside the labelled region on compact', () => {
    installMatchMedia(compactQueries());
    renderTable([]);

    const region = screen.getByRole('region', { name: 'Facilities' });
    const table = within(region).getByRole('table', { name: 'Facilities' });
    expect(within(table).getByRole('cell', { name: 'No facilities' }).getAttribute('colspan')).toBe(
      '2',
    );
  });

  it('keeps a full, unhidden table on medium tablets', () => {
    installMatchMedia({
      [BREAKPOINT_MEDIA_QUERIES.medium]: true,
      [BREAKPOINT_MEDIA_QUERIES.expanded]: false,
    });
    renderTable();

    expect(screen.queryByRole('region', { name: 'Facilities' })).toBeNull();
    const table = screen.getByRole('table', { name: 'Facilities' });
    expect(
      within(table)
        .getAllByRole('columnheader')
        .map((node) => node.textContent),
    ).toEqual(['Code', 'Name']);
    for (const cell of within(table).getAllByRole('cell')) {
      expect(cell.style.display).not.toBe('none');
    }
  });

  it('keeps a full table with no scroll region on expanded desktops', () => {
    installMatchMedia({
      [BREAKPOINT_MEDIA_QUERIES.medium]: true,
      [BREAKPOINT_MEDIA_QUERIES.expanded]: true,
    });
    renderTable();

    expect(screen.queryByRole('region', { name: 'Facilities' })).toBeNull();
    const table = screen.getByRole('table', { name: 'Facilities' });
    expect(within(table).getAllByRole('cell')).toHaveLength(2);
  });

  it('drops the scroll region when the viewport grows past compact', () => {
    const media = installMatchMedia(compactQueries());
    renderTable();
    expect(screen.getByRole('region', { name: 'Facilities' })).toBeTruthy();

    act(() => media.fire(BREAKPOINT_MEDIA_QUERIES.medium, true));
    expect(screen.queryByRole('region', { name: 'Facilities' })).toBeNull();
    expect(screen.getByRole('table', { name: 'Facilities' })).toBeTruthy();
  });

  it('keeps the labelled structure and logical-only styling under right-to-left', () => {
    document.documentElement.setAttribute('dir', 'rtl');
    document.documentElement.setAttribute('lang', 'ar');
    installMatchMedia(compactQueries());
    renderTable([{ code: 'SYN-0002', name: LONG_ARABIC }]);

    const region = screen.getByRole('region', { name: 'Facilities' });
    expect(region.className).toContain('table-region');
    expect(region.tabIndex).toBe(0);
    const table = within(region).getByRole('table', { name: 'Facilities' });
    expect(within(table).getByRole('columnheader', { name: 'Code' })).toBeTruthy();
    const arabicCell = within(table).getByRole('cell', { name: LONG_ARABIC });
    expect(arabicCell.style.overflowWrap).toBe('anywhere');

    // No component sets a physical side, a physical alignment or a direction
    // override: the document direction mirrors the logical CSS on its own.
    // Symmetric shorthands such as `padding: 4px` are direction-neutral.
    for (const element of [region, ...Array.from(region.querySelectorAll('*'))] as HTMLElement[]) {
      const style = element.getAttribute('style') ?? '';
      expect(style).not.toMatch(/(?:margin|padding|border)-(?:left|right)\s*:/);
      expect(style).not.toMatch(/text-align\s*:\s*(?:left|right)/);
      expect(element.style.direction).toBe('');
    }
    expect((table.querySelector('caption') as HTMLElement).style.textAlign).toBe('start');
  });
});
