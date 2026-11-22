import type { ReactElement } from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { send } from '../../../src/api/transport';
import type * as TransportModule from '../../../src/api/transport';
import { ActiveOrganizationProvider } from '../../../src/api/activeOrganization';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import type { Locale } from '../../../src/i18n/messages';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';
import {
  BREAKPOINT_MEDIA_QUERIES,
  BREAKPOINT_WIDTHS,
  breakpointFor,
} from '../../../src/theme/breakpoints';
import { DuplicatesPage } from '../../../src/features/duplicates/DuplicatesPage';
import {
  alphaOrganizationId,
  syntheticDetail,
  syntheticPair,
} from '../../../src/features/duplicates/fixtures';

vi.mock('../../../src/api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

type ChangeListener = (event: MediaQueryListEvent) => void;

/** Synthetic viewport band answers keyed by the real media query strings. */
interface ViewportState {
  medium: boolean;
  expanded: boolean;
  coarse: boolean;
}

const VIEWPORTS: Record<'compact' | 'medium' | 'expanded', ViewportState> = {
  compact: { medium: false, expanded: false, coarse: true },
  medium: { medium: true, expanded: false, coarse: true },
  expanded: { medium: true, expanded: true, coarse: false },
};

/**
 * jsdom cannot evaluate media queries, so install a deterministic stand-in
 * whose answers follow the synthetic viewport state. The real breakpoint hook
 * then resolves the same band a browser would at that width (WEB-07, UX-01).
 */
function installMatchMedia(state: ViewportState) {
  const answers: Record<string, boolean> = {
    [BREAKPOINT_MEDIA_QUERIES.medium]: state.medium,
    [BREAKPOINT_MEDIA_QUERIES.expanded]: state.expanded,
    '(pointer: coarse)': state.coarse,
  };
  const listeners = new Map<string, Set<ChangeListener>>();
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => {
      const current = {
        matches: answers[query] ?? false,
        listeners: listeners.get(query) ?? new Set<ChangeListener>(),
      };
      listeners.set(query, current.listeners);
      return {
        matches: current.matches,
        media: query,
        onchange: null,
        addEventListener: (_type: string, listener: ChangeListener) => {
          current.listeners.add(listener);
        },
        removeEventListener: (_type: string, listener: ChangeListener) => {
          current.listeners.delete(listener);
        },
      } as unknown as MediaQueryList;
    }),
  );
  return {
    fire(query: string, matches: boolean) {
      answers[query] = matches;
      for (const listener of listeners.get(query) ?? []) {
        listener({ matches } as MediaQueryListEvent);
      }
    },
  };
}

function wrap(ui: ReactElement, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ActiveOrganizationProvider value={{ organizationId: alphaOrganizationId }}>
          {ui}
        </ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>,
  );
}

/** The page column is the band-bearing `.stack` ancestor of the title. */
function pageRoot(): HTMLElement {
  const heading = screen.getByRole('heading', { level: 2 });
  const root = heading.closest('.stack[data-breakpoint]');
  if (!root) {
    throw new Error('responsive page container with data-breakpoint was not rendered');
  }
  return root as HTMLElement;
}

const physicalStyleKeys = [
  'marginLeft',
  'marginRight',
  'paddingLeft',
  'paddingRight',
  'borderLeft',
  'borderRight',
  'left',
  'right',
];

const LONG_ARABIC_NOTICE =
  'بيانات توضيحية طويلة جداً للاختبار والتحقق من التخطيط دون قص أو تداخل';

beforeEach(() => {
  sendMock.mockReset();
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.documentElement.removeAttribute('dir');
  document.documentElement.removeAttribute('lang');
});

describe.each(['compact', 'medium', 'expanded'] as const)(
  'responsive DuplicatesPage at %s width (WEB-07, UX-01)',
  (breakpoint) => {
    it('renders a single labelled page column at the resolved breakpoint', async () => {
      installMatchMedia(VIEWPORTS[breakpoint]);
      sendMock.mockResolvedValueOnce([syntheticPair]);
      wrap(<DuplicatesPage />);
      await screen.findByRole('table', { name: 'Duplicate review worklist' });

      const root = pageRoot();
      expect(root.classList.contains('stack')).toBe(true);
      expect(root.getAttribute('data-breakpoint')).toBe(breakpoint);
      // The header keeps a vertical stack: no clinical text is hidden and the
      // synthetic notice can wrap at every width.
      const header = root.querySelector('header');
      expect(header?.classList.contains('stack')).toBe(true);

      // The ApiPage region owns the `.page` safe-area/reading-width class.
      const region = screen.getByRole('region', { name: 'Duplicate review worklist' }) as HTMLElement;
      expect(region.classList.contains('page')).toBe(true);
      expect(region.getAttribute('data-breakpoint')).toBe(breakpoint);
      expect(region.style.display).not.toBe('none');
    });

    it('keeps every worklist column, accessible name and keyboard order', async () => {
      installMatchMedia(VIEWPORTS[breakpoint]);
      sendMock.mockResolvedValueOnce([syntheticPair]);
      wrap(<DuplicatesPage />);
      const table = await screen.findByRole('table', { name: 'Duplicate review worklist' });
      const caption = table.querySelector('caption');
      expect(caption?.textContent).toBe('Duplicate review worklist');

      const headers = screen.getAllByRole('columnheader').map((node) => node.textContent);
      expect(headers).toEqual(['Surviving identity', 'Duplicate identity', 'Action']);
      for (const header of screen.getAllByRole('columnheader')) {
        expect(header.getAttribute('scope')).toBe('col');
        expect((header as HTMLElement).style.display).not.toBe('none');
      }

      // The compare action keeps its accessible name and remains keyboard
      // reachable; the raw worklist button itself is owned by the screen task.
      const action = screen.getByRole('button', {
        name: 'Review pair patient-survivor and patient-duplicate',
      }) as HTMLButtonElement;
      expect(action.tagName).toBe('BUTTON');
      expect(action.tabIndex).toBe(0);
      action.focus();
      expect(document.activeElement).toBe(action);
    });

    it('uses logical inline styles only and hides no clinical content', async () => {
      installMatchMedia(VIEWPORTS[breakpoint]);
      sendMock.mockResolvedValueOnce([syntheticPair]);
      wrap(<DuplicatesPage />);
      await screen.findByRole('table', { name: 'Duplicate review worklist' });

      const root = pageRoot();
      for (const element of Array.from(root.querySelectorAll<HTMLElement>('[style]')).concat(root)) {
        expect(element.style.direction).toBe('');
        expect(element.style.display).not.toBe('none');
        expect(element.style.whiteSpace).not.toBe('nowrap');
        for (const key of physicalStyleKeys) {
          expect(element.style.getPropertyValue(key)).toBe('');
        }
      }
      expect(root.querySelector('.hide-compact')).toBeNull();
      expect(root.querySelector('.only-compact')).toBeNull();
    });
  },
);

describe('responsive DuplicatesPage width matrix', () => {
  const widths = [320, 360, 390, 768, 820, 1024, 1280] as const;

  it.each(widths)('resolves %ipx to the correct layout band', async (width) => {
    installMatchMedia({
      medium: width >= BREAKPOINT_WIDTHS.medium,
      expanded: width >= BREAKPOINT_WIDTHS.expanded,
      coarse: width < BREAKPOINT_WIDTHS.expanded,
    });
    sendMock.mockResolvedValueOnce([syntheticPair]);
    wrap(<DuplicatesPage />);
    await screen.findByRole('table', { name: 'Duplicate review worklist' });

    expect(pageRoot().getAttribute('data-breakpoint')).toBe(breakpointFor(width));
  });

  it('follows a viewport change without remounting the worklist', async () => {
    const media = installMatchMedia(VIEWPORTS.compact);
    sendMock.mockResolvedValueOnce([syntheticPair]);
    wrap(<DuplicatesPage />);
    await screen.findByRole('table', { name: 'Duplicate review worklist' });
    expect(pageRoot().getAttribute('data-breakpoint')).toBe('compact');

    act(() => media.fire(BREAKPOINT_MEDIA_QUERIES.medium, true));
    expect(pageRoot().getAttribute('data-breakpoint')).toBe('medium');
    act(() => media.fire(BREAKPOINT_MEDIA_QUERIES.expanded, true));
    expect(pageRoot().getAttribute('data-breakpoint')).toBe('expanded');
  });
});

describe('responsive DuplicatesPage review detail', () => {
  async function reachDetail(breakpoint: 'compact' | 'medium' | 'expanded') {
    installMatchMedia(VIEWPORTS[breakpoint]);
    sendMock.mockResolvedValueOnce([syntheticPair]);
    sendMock.mockResolvedValueOnce(syntheticDetail);
    wrap(<DuplicatesPage />);
    fireEvent.click(
      await screen.findByRole('button', {
        name: 'Review pair patient-survivor and patient-duplicate',
      }),
    );
    await screen.findByRole('region', { name: 'Surviving record' });
  }

  it.each(['compact', 'medium', 'expanded'] as const)(
    'keeps the back action and stacked review regions reachable at %s',
    async (breakpoint) => {
      await reachDetail(breakpoint);

      const back = screen.getByRole('button', { name: 'Back to worklist' }) as HTMLButtonElement;
      expect(back.classList.contains('touch')).toBe(true);
      expect(back.getAttribute('data-breakpoint')).toBe(breakpoint);
      expect(back.tabIndex).toBe(0);
      back.focus();
      expect(document.activeElement).toBe(back);

      const detailColumn = back.closest('.stack');
      expect(detailColumn?.classList.contains('stack')).toBe(true);

      const approval = screen.getByRole('region', { name: 'Second-reviewer approval' });
      const workflow = approval.parentElement;
      expect(workflow?.classList.contains('stack')).toBe(true);
      expect(workflow?.getAttribute('data-breakpoint')).toBe(breakpoint);

      // The two identities and the dry run remain in the DOM at every width.
      expect(screen.getByRole('region', { name: 'Surviving record' })).toBeTruthy();
      expect(screen.getByRole('region', { name: 'Duplicate record' })).toBeTruthy();
      expect(screen.getByText(/Dry run: move 1 chart visibility association/)).toBeTruthy();
    },
  );
});

describe('responsive DuplicatesPage right-to-left', () => {
  it('mirrors the document and keeps logical structure with long Arabic text', async () => {
    installMatchMedia(VIEWPORTS.compact);
    sendMock.mockResolvedValueOnce([syntheticPair]);
    wrap(<DuplicatesPage />, 'ar');
    await screen.findByRole('table', { name: 'قائمة مراجعة التكرارات' });

    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    const root = pageRoot();
    expect(root.getAttribute('data-breakpoint')).toBe('compact');
    expect(screen.getByRole('heading', { level: 2 }).textContent).toBe('مراجعة المرضى المكررين');

    const notice = root.querySelector('header p') as HTMLElement;
    // The header's `.stack` class gives every child wrapping room through the
    // stylesheet's logical `overflow-wrap: anywhere`; jsdom cannot paint it.
    expect(notice.parentElement?.classList.contains('stack')).toBe(true);
    expect(notice.style.direction).toBe('');
    expect(notice.style.whiteSpace).not.toBe('nowrap');

    for (const element of Array.from(root.querySelectorAll<HTMLElement>('[style]')).concat(root)) {
      expect(element.style.direction).toBe('');
      expect(element.style.display).not.toBe('none');
      for (const key of physicalStyleKeys) {
        expect(element.style.getPropertyValue(key)).toBe('');
      }
    }
  });

  it('keeps an injected long Arabic notice readable without a direction override', async () => {
    installMatchMedia(VIEWPORTS.expanded);
    sendMock.mockResolvedValueOnce([syntheticPair]);
    wrap(<DuplicatesPage />, 'ar');
    await screen.findByRole('table', { name: 'قائمة مراجعة التكرارات' });

    const notice = pageRoot().querySelector('header p') as HTMLElement;
    expect(notice.textContent).not.toBe(LONG_ARABIC_NOTICE);
    expect(notice.style.direction).toBe('');
    expect(notice.style.whiteSpace).not.toBe('nowrap');
    expect(document.querySelector('[dir="rtl"]')).toBe(document.documentElement);
  });
});

/** Compile-time guard that the page renders as a React element. */
const pageElement: ReactElement = <DuplicatesPage />;
void pageElement;
