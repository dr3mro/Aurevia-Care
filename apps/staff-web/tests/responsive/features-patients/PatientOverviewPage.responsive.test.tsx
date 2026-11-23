import type { ReactElement } from 'react';
import { render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ActiveOrganizationProvider } from '../../../src/api/activeOrganization';
import { send } from '../../../src/api/transport';
import type * as TransportModule from '../../../src/api/transport';
import { RouterProvider } from '../../../src/app/router';
import {
  alphaOrganizationId,
  syntheticAlphaPatients,
  syntheticPatientView,
  syntheticPatientViewNoDuplicates,
} from '../../../src/features/patients/fixtures';
import type { PatientViewResponse } from '../../../src/features/patients/domain';
import { PatientOverviewPage } from '../../../src/features/patients/PatientOverviewPage';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import type { Locale } from '../../../src/i18n/messages';
import { BREAKPOINT_MEDIA_QUERIES, BREAKPOINT_WIDTHS } from '../../../src/theme/breakpoints';
import type { Breakpoint } from '../../../src/theme/breakpoints';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';

vi.mock('../../../src/api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);
const overviewPatterns = ['/patients/:id'];

type ChangeListener = (event: MediaQueryListEvent) => void;

interface ViewportState {
  medium: boolean;
  expanded: boolean;
  coarse: boolean;
  portrait: boolean;
}

/** Synthetic viewport states mirroring the responsive Playwright matrix. */
const VIEWPORTS: Record<Breakpoint, ViewportState> = {
  compact: { medium: false, expanded: false, coarse: true, portrait: true },
  medium: { medium: true, expanded: false, coarse: true, portrait: false },
  expanded: { medium: true, expanded: true, coarse: false, portrait: false },
};

/** Physical inline styles are forbidden; the page must use logical properties. */
const physicalStyleKeys = [
  'marginLeft',
  'marginRight',
  'marginTop',
  'marginBottom',
  'paddingLeft',
  'paddingRight',
  'paddingTop',
  'paddingBottom',
  'borderLeft',
  'borderRight',
  'borderTop',
  'borderBottom',
  'left',
  'right',
  'top',
  'bottom',
] as const;

/**
 * jsdom cannot evaluate media queries. Install a deterministic stand-in whose
 * answers follow the synthetic viewport state so the real breakpoint hook
 * resolves at each width (WEB-07, UX-01).
 */
function installMatchMedia(state: ViewportState): void {
  const answers: Record<string, boolean> = {
    [BREAKPOINT_MEDIA_QUERIES.medium]: state.medium,
    [BREAKPOINT_MEDIA_QUERIES.expanded]: state.expanded,
    '(pointer: coarse)': state.coarse,
    '(orientation: portrait)': state.portrait,
  };
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => {
      const current = {
        matches: answers[query] ?? false,
        listeners: new Set<ChangeListener>(),
      };
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
}

/**
 * Render the installed overview page at one synthetic width and locale. The
 * transport is synthetic; no application endpoint is contacted.
 */
function renderPage(options: { locale?: Locale; view?: PatientViewResponse } = {}) {
  sendMock.mockResolvedValue(options.view ?? syntheticPatientViewNoDuplicates);
  const ui: ReactElement = (
    <ThemeProvider>
      <I18nProvider initialLocale={options.locale ?? 'en'}>
        <ActiveOrganizationProvider value={{ organizationId: alphaOrganizationId }}>
          <RouterProvider patterns={overviewPatterns} initialPath="/patients/patient-a1">
            <PatientOverviewPage />
          </RouterProvider>
        </ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>
  );
  return render(ui);
}

/** The labelled ApiPage region is the single content column of the overview. */
function contentRegion(locale: Locale = 'en'): HTMLElement {
  const name = locale === 'ar' ? 'نظرة عامة على المريض' : 'Patient overview';
  return screen.getByRole('region', { name });
}

/** The page wrapper is the direct parent of the labelled content region. */
function pageWrapper(locale: Locale = 'en'): HTMLElement {
  const region = contentRegion(locale);
  const wrapper = region.parentElement;
  if (!wrapper) {
    throw new Error('responsive patient overview wrapper was not rendered');
  }
  return wrapper;
}

beforeEach(() => {
  sendMock.mockReset();
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.documentElement.removeAttribute('dir');
  document.documentElement.removeAttribute('lang');
});

describe.each(['compact', 'medium', 'expanded'] as const)(
  'responsive PatientOverviewPage at %s',
  (breakpoint) => {
    it('resolves the breakpoint with one page region and a stack wrapper', async () => {
      installMatchMedia(VIEWPORTS[breakpoint]);
      renderPage();
      await screen.findByText('Layla Haddad');

      const wrapper = pageWrapper();
      expect(wrapper.getAttribute('data-breakpoint')).toBe(breakpoint);
      expect(wrapper.classList.contains('stack')).toBe(true);
      // ApiPage is the single `.page` layout owner: a second one would double
      // the safe-area padding and narrow the reading column.
      expect(wrapper.classList.contains('page')).toBe(false);
      const region = contentRegion();
      expect(region.getAttribute('data-breakpoint')).toBe(breakpoint);
      expect(region.classList.contains('page')).toBe(true);
      expect(wrapper.contains(region)).toBe(true);
    });

    it('keeps every clinical label and role available at this width', async () => {
      installMatchMedia(VIEWPORTS[breakpoint]);
      renderPage({ view: syntheticPatientView });
      await screen.findByText('Layla Haddad');

      expect(screen.getByRole('heading', { name: 'Patient overview' })).toBeTruthy();
      expect(screen.getByRole('region', { name: 'Identity' })).toBeTruthy();
      expect(screen.getByRole('alert', { name: 'Likely duplicate records' })).toBeTruthy();
      expect(screen.getByRole('link', { name: 'Open chart' })).toBeTruthy();
      // Every clinical field remains present in the DOM at this width; real
      // width-dependent visibility belongs to the Playwright projects.
      expect(screen.getByText('MRN-1001')).toBeTruthy();
      expect(screen.getByText('May 12, 1990')).toBeTruthy();
      expect(screen.getByText('ليلى حداد')).toBeTruthy();
    });

    it('keeps DOM keyboard order and wraps long values with logical properties', async () => {
      installMatchMedia(VIEWPORTS[breakpoint]);
      renderPage({ view: syntheticPatientView });
      await screen.findByText('Layla Haddad');

      const wrapper = pageWrapper();
      const focusables = Array.from(
        wrapper.querySelectorAll<HTMLElement>('a[href], button, input, select, textarea'),
      );
      expect(focusables.map((element) => element.tagName)).toEqual(['A']);
      expect(focusables[0]?.tabIndex).toBe(0);
      // overflow-wrap is inherited: long English and Arabic values wrap in the
      // content column instead of forcing a horizontal page scroll (UX-01).
      expect(wrapper.style.overflowWrap).toBe('anywhere');
      expect(wrapper.style.whiteSpace).not.toBe('nowrap');

      const styled = Array.from(wrapper.querySelectorAll<HTMLElement>('[style]')).concat(wrapper);
      for (const element of styled) {
        expect(element.style.direction).toBe('');
        expect(element.style.display).not.toBe('none');
        for (const key of physicalStyleKeys) {
          expect(element.style.getPropertyValue(key)).toBe('');
        }
      }
      expect(wrapper.querySelector('.hide-compact')).toBeNull();
      expect(wrapper.querySelector('.only-compact')).toBeNull();
    });
  },
);

describe('responsive PatientOverviewPage viewport matrix', () => {
  const widths = [320, 360, 390, 768, 820, 1024, 1280] as const;

  it.each(widths)('resolves %ipx to the correct layout band', async (width) => {
    installMatchMedia({
      medium: width >= BREAKPOINT_WIDTHS.medium,
      expanded: width >= BREAKPOINT_WIDTHS.expanded,
      coarse: width < BREAKPOINT_WIDTHS.expanded,
      portrait: width < BREAKPOINT_WIDTHS.medium,
    });
    renderPage();
    await screen.findByText('Layla Haddad');

    const expected: Breakpoint =
      width >= BREAKPOINT_WIDTHS.expanded
        ? 'expanded'
        : width >= BREAKPOINT_WIDTHS.medium
          ? 'medium'
          : 'compact';
    expect(pageWrapper().getAttribute('data-breakpoint')).toBe(expected);
    expect(contentRegion().getAttribute('data-breakpoint')).toBe(expected);
  });
});

describe('responsive PatientOverviewPage right to left', () => {
  const widths = [320, 360, 390, 768, 820, 1024, 1280] as const;

  it.each(widths)('mirrors the flow but not the clinical content at %ipx', async (width) => {
    installMatchMedia({
      medium: width >= BREAKPOINT_WIDTHS.medium,
      expanded: width >= BREAKPOINT_WIDTHS.expanded,
      coarse: width < BREAKPOINT_WIDTHS.expanded,
      portrait: width < BREAKPOINT_WIDTHS.medium,
    });
    const arabicView: PatientViewResponse = {
      patient: required(syntheticAlphaPatients[1]),
      duplicates: [required(syntheticAlphaPatients[0])],
    };
    renderPage({ locale: 'ar', view: arabicView });
    await screen.findByText('ليلى حداد');

    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    const expected: Breakpoint =
      width >= BREAKPOINT_WIDTHS.expanded
        ? 'expanded'
        : width >= BREAKPOINT_WIDTHS.medium
          ? 'medium'
          : 'compact';
    const wrapper = pageWrapper('ar');
    expect(wrapper.getAttribute('data-breakpoint')).toBe(expected);
    expect(wrapper.classList.contains('stack')).toBe(true);
    expect(wrapper.classList.contains('page')).toBe(false);
    expect(screen.getByRole('heading', { name: 'نظرة عامة على المريض' })).toBeTruthy();
    expect(screen.getByRole('link', { name: 'فتح الملف الطبي' })).toBeTruthy();
    expect(screen.getByText('ليلى حداد').getAttribute('lang')).toBe('ar');
    expect(screen.getByRole('alert', { name: 'سجلات مرجّح تكرارها' })).toBeTruthy();

    for (const element of Array.from(wrapper.querySelectorAll<HTMLElement>('[style]')).concat(
      wrapper,
    )) {
      expect(element.style.direction).toBe('');
      expect(element.style.display).not.toBe('none');
      for (const key of physicalStyleKeys) {
        expect(element.style.getPropertyValue(key)).toBe('');
      }
    }
    expect(wrapper.style.overflowWrap).toBe('anywhere');
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
