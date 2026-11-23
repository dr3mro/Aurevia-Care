import { act, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ReactNode } from 'react';
import { AdminTableRegion } from '../../../src/features/admin/AdminTableRegion';
import { BREAKPOINT_MEDIA_QUERIES } from '../../../src/theme/breakpoints';
import type { Breakpoint } from '../../../src/theme/breakpoints';

type MatchListener = (event: MediaQueryListEvent) => void;

interface ViewportState {
  medium: boolean;
  expanded: boolean;
}

/** Synthetic viewport states mirroring the responsive Playwright matrix. */
const VIEWPORTS: Record<Breakpoint, ViewportState> = {
  compact: { medium: false, expanded: false },
  medium: { medium: true, expanded: false },
  expanded: { medium: true, expanded: true },
};

/**
 * jsdom cannot evaluate media queries. Install a deterministic `matchMedia`
 * stand-in so the real breakpoint hook resolves for one layout band and can be
 * changed later to prove the region follows a resize. All data is synthetic.
 */
function installMatchMedia(state: ViewportState): (query: string, matches: boolean) => void {
  const answers: Record<string, boolean> = {
    [BREAKPOINT_MEDIA_QUERIES.medium]: state.medium,
    [BREAKPOINT_MEDIA_QUERIES.expanded]: state.expanded,
  };
  const listeners = new Map<string, Set<MatchListener>>();
  const matchMedia = (query: string): MediaQueryList => {
    const queryListeners = listeners.get(query) ?? new Set<MatchListener>();
    listeners.set(query, queryListeners);
    return {
      media: query,
      matches: answers[query] ?? false,
      onchange: null,
      addEventListener: (_type: string, listener: MatchListener) => {
        queryListeners.add(listener);
      },
      removeEventListener: (_type: string, listener: MatchListener) => {
        queryListeners.delete(listener);
      },
      addListener: (listener: MatchListener) => queryListeners.add(listener),
      removeListener: (listener: MatchListener) => queryListeners.delete(listener),
      dispatchEvent: () => true,
    } as unknown as MediaQueryList;
  };
  vi.stubGlobal('matchMedia', matchMedia);
  return (query, matches) => {
    answers[query] = matches;
    for (const listener of listeners.get(query) ?? []) {
      listener({ matches, media: query } as MediaQueryListEvent);
    }
  };
}

const LONG_ENGLISH =
  'A deliberately long synthetic facility name that must wrap without clipping or overlap';
const LONG_ARABIC = 'اسم منشأة تجريبي طويل جداً يجب أن يلتف دون قص أو تداخل';

/** A synthetic administration table with every clinical column present. */
function syntheticTable(actionLabel: string, name: string): ReactNode {
  return (
    <table style={{ borderCollapse: 'collapse', width: '100%' }}>
      <caption style={{ textAlign: 'start' }}>Synthetic facilities</caption>
      <thead>
        <tr>
          <th scope="col">Code</th>
          <th scope="col">Name</th>
          <th scope="col">Action</th>
        </tr>
      </thead>
      <tbody>
        <tr>
          <td>SYN-0001</td>
          <td>{name}</td>
          <td>
            <button type="button" className="touch">
              {actionLabel}
            </button>
          </td>
        </tr>
      </tbody>
    </table>
  );
}

function renderRegion(label: string, name = LONG_ENGLISH, actionLabel = 'Open SYN-0001') {
  return render(
    <AdminTableRegion label={label}>{syntheticTable(actionLabel, name)}</AdminTableRegion>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('responsive AdminTableRegion (WEB-07, UX-01)', () => {
  describe.each(['compact', 'medium', 'expanded'] as const)('%s width', (breakpoint) => {
    it('keeps one labelled, keyboard-scrollable region around the full table', () => {
      installMatchMedia(VIEWPORTS[breakpoint]);
      renderRegion('Synthetic facilities');

      const region = screen.getByRole('region', { name: 'Synthetic facilities' });
      // The stylesheet's .table-region owns the direction-neutral scroll and
      // focus ring; data-breakpoint exposes the resolved band to jsdom.
      expect(region.classList.contains('table-region')).toBe(true);
      expect(region.getAttribute('data-breakpoint')).toBe(breakpoint);
      expect(region.getAttribute('aria-label')).toBe('Synthetic facilities');
      expect(region.tabIndex).toBe(0);
      expect(region.style.overflowX).toBe('auto');
      expect(region.style.overflow).not.toBe('hidden');

      const table = within(region).getByRole('table', { name: 'Synthetic facilities' });
      expect(within(table).getByRole('columnheader', { name: 'Code' }).getAttribute('scope')).toBe(
        'col',
      );
      expect(
        within(table)
          .getAllByRole('columnheader')
          .map((node) => node.textContent),
      ).toEqual(['Code', 'Name', 'Action']);
      expect(within(table).getByRole('cell', { name: 'SYN-0001' })).toBeTruthy();
      expect(within(table).getByRole('cell', { name: LONG_ENGLISH })).toBeTruthy();
    });

    it('keeps every clinical column visible and preserves the keyboard order', () => {
      installMatchMedia(VIEWPORTS[breakpoint]);
      renderRegion('Synthetic facilities');

      const region = screen.getByRole('region', { name: 'Synthetic facilities' });
      const table = within(region).getByRole('table');
      for (const cell of within(table).getAllByRole('cell')) {
        expect(cell.style.display).not.toBe('none');
        expect(cell.tabIndex).toBe(-1);
      }
      for (const header of within(table).getAllByRole('columnheader')) {
        expect(header.style.display).not.toBe('none');
        expect(header.tabIndex).toBe(-1);
      }

      // The region is the first tab stop; the coarse-pointer row action keeps
      // its stylesheet target class and follows in DOM order.
      const action = within(region).getByRole('button', { name: 'Open SYN-0001' });
      expect(action.classList.contains('touch')).toBe(true);
      region.focus();
      expect(document.activeElement).toBe(region);
      action.focus();
      expect(document.activeElement).toBe(action);
    });

    it('uses only logical layout styles and no direction override', () => {
      installMatchMedia(VIEWPORTS[breakpoint]);
      renderRegion('Synthetic facilities');

      const region = screen.getByRole('region', { name: 'Synthetic facilities' });
      for (const element of [
        region,
        ...Array.from(region.querySelectorAll('*')),
      ] as HTMLElement[]) {
        const style = element.getAttribute('style') ?? '';
        expect(style).not.toMatch(/(?:margin|padding|border)-(?:left|right)\s*:/);
        expect(style).not.toMatch(/text-align\s*:\s*(?:left|right)/);
        expect(element.style.direction).toBe('');
      }
    });
  });

  it('follows the resolved band when a resize crosses a breakpoint', () => {
    const fire = installMatchMedia(VIEWPORTS.compact);
    renderRegion('Synthetic facilities');

    const region = screen.getByRole('region', { name: 'Synthetic facilities' });
    expect(region.getAttribute('data-breakpoint')).toBe('compact');

    act(() => fire(BREAKPOINT_MEDIA_QUERIES.medium, true));
    expect(region.getAttribute('data-breakpoint')).toBe('medium');
    act(() => fire(BREAKPOINT_MEDIA_QUERIES.expanded, true));
    expect(region.getAttribute('data-breakpoint')).toBe('expanded');

    // The table and its clinical values survive the resize in the same DOM.
    expect(within(region).getByRole('cell', { name: LONG_ENGLISH })).toBeTruthy();
    expect(within(region).getAllByRole('columnheader')).toHaveLength(3);
  });

  it('keeps the labelled structure and Arabic values under right-to-left', () => {
    document.documentElement.setAttribute('dir', 'rtl');
    document.documentElement.setAttribute('lang', 'ar');
    installMatchMedia(VIEWPORTS.compact);
    renderRegion('منشآت تجريبية', LONG_ARABIC, 'فتح SYN-0001');

    const region = screen.getByRole('region', { name: 'منشآت تجريبية' });
    expect(document.documentElement.dir).toBe('rtl');
    expect(region.classList.contains('table-region')).toBe(true);
    expect(region.getAttribute('data-breakpoint')).toBe('compact');
    expect(region.tabIndex).toBe(0);
    expect(region.getAttribute('style')).not.toMatch(
      /(?:padding|margin)-(?:left|right)\s*:|text-align\s*:\s*(?:left|right)/,
    );
    expect(region.style.direction).toBe('');

    const table = within(region).getByRole('table', { name: 'Synthetic facilities' });
    expect(within(table).getByRole('cell', { name: LONG_ARABIC })).toBeTruthy();
    const action = within(table).getByRole('button', { name: 'فتح SYN-0001' });
    expect(action).toBeTruthy();
    for (const header of within(table).getAllByRole('columnheader')) {
      expect(header.style.display).not.toBe('none');
    }

    // The labelled region leads the keyboard order in both directions; the
    // Arabic row action keeps its coarse-pointer target and DOM position.
    expect(action.classList.contains('touch')).toBe(true);
    region.focus();
    expect(document.activeElement).toBe(region);
    action.focus();
    expect(document.activeElement).toBe(action);
  });
});
