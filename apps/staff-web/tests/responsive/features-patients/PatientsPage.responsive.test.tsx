/**
 * Responsive structure checks for `PatientsPage` (WEB-07, UX-01). jsdom cannot
 * paint pixels or evaluate media queries, so these tests assert the classes,
 * attributes, roles, keyboard order and direction contract that the real
 * browser projects measure. All records are synthetic.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { ActiveOrganizationProvider } from '../../../src/api/activeOrganization';
import { send } from '../../../src/api/transport';
import type * as TransportModule from '../../../src/api/transport';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import type { Locale } from '../../../src/i18n/messages';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';
import {
  BREAKPOINT_MEDIA_QUERIES,
  BREAKPOINT_WIDTHS,
  breakpointFor,
} from '../../../src/theme/breakpoints';
import {
  alphaOrganizationId,
  syntheticAlphaPatients,
} from '../../../src/features/patients/fixtures';
import { PatientsPage } from '../../../src/features/patients/PatientsPage';

vi.mock('../../../src/api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

type Band = 'compact' | 'medium' | 'expanded';

/** Synthetic viewport states mirroring the responsive Playwright matrix. */
interface ViewportState {
  medium: boolean;
  expanded: boolean;
  coarse: boolean;
  portrait: boolean;
}

const VIEWPORTS: Record<Band, ViewportState> = {
  compact: { medium: false, expanded: false, coarse: true, portrait: true },
  medium: { medium: true, expanded: false, coarse: true, portrait: false },
  expanded: { medium: true, expanded: true, coarse: false, portrait: false },
};

/** Physical style keys that must never appear in a direction-neutral layout. */
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
];

/**
 * Install a deterministic `matchMedia` stand-in whose answers follow the
 * synthetic viewport state, so the real breakpoint hook resolves at each band.
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

/** Render the page against a synthetic tenant and the requested locale. */
function renderPage(locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ActiveOrganizationProvider value={{ organizationId: alphaOrganizationId }}>
          <PatientsPage />
        </ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>,
  );
}

/** Locate the responsive page root by its stable test hook. */
function pageRoot(): HTMLElement {
  const root = document.querySelector('[data-part="patients-page"]');
  if (!root) {
    throw new Error('responsive patients page root was not rendered');
  }
  return root as HTMLElement;
}

/** Assert that no inline style introduces a physical side or hides content. */
function assertLogicalOnly(root: HTMLElement): void {
  for (const element of Array.from(root.querySelectorAll<HTMLElement>('[style]')).concat(root)) {
    expect(element.style.direction).toBe('');
    expect(element.style.display).not.toBe('none');
    for (const key of physicalStyleKeys) {
      expect(element.style.getPropertyValue(key)).toBe('');
    }
  }
}

/** Search for a synthetic patient and return the results table. */
async function searchAndGetTable(locale: Locale = 'en') {
  const caption = locale === 'ar' ? 'نتائج البحث عن المرضى' : 'Patient search results';
  const inputName = locale === 'ar' ? 'ابحث بالاسم' : 'Search by name';
  const buttonName = locale === 'ar' ? 'بحث' : 'Search';
  const query = locale === 'ar' ? 'ليلى' : 'Layla';
  renderPage(locale);
  sendMock.mockResolvedValueOnce(syntheticAlphaPatients);
  fireEvent.change(screen.getByRole('textbox', { name: inputName }), {
    target: { value: query },
  });
  fireEvent.click(screen.getByRole('button', { name: buttonName }));
  const table = await screen.findByRole('table', { name: caption });
  return { table, caption };
}

beforeEach(() => {
  sendMock.mockReset();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe.each(['compact', 'medium', 'expanded'] as const)(
  'responsive PatientsPage at %s width',
  (band) => {
    it('resolves the band and exposes the page grid and toolbar structure', () => {
      installMatchMedia(VIEWPORTS[band]);
      renderPage();

      const root = pageRoot();
      expect(root.getAttribute('data-breakpoint')).toBe(band);
      expect(root.classList.contains('page')).toBe(true);
      expect(screen.getByRole('heading', { name: 'Patients' })).toBeTruthy();
      expect(
        screen.getByText(
          'Synthetic data only. Patients are read from and written to the connected API.',
        ),
      ).toBeTruthy();

      // The search and register actions stack on a phone and sit side by side
      // from the medium breakpoint; no field or action is removed or hidden.
      const toolbar = root.querySelector('[data-part="toolbar"]');
      expect(toolbar).not.toBeNull();
      expect(toolbar?.classList.contains(band === 'compact' ? 'stack' : 'grid-2')).toBe(true);
      expect(toolbar?.getAttribute('data-layout')).toBe(
        band === 'compact' ? 'stacked' : 'side-by-side',
      );
      assertLogicalOnly(root);
    });

    it('keeps labelled controls, the status region and the DOM keyboard order', () => {
      installMatchMedia(VIEWPORTS[band]);
      renderPage();

      const root = pageRoot();
      const form = screen.getByRole('form', { name: 'Search by name' });
      const search = screen.getByRole('textbox', { name: 'Search by name' });
      const submit = screen.getByRole('button', { name: 'Search' });
      const register = screen.getByRole('button', { name: 'Register' });

      expect(form.getAttribute('data-breakpoint')).toBe(band);
      expect(search.classList.contains('field')).toBe(true);
      expect(search.classList.contains('touch')).toBe(true);
      expect(submit.getAttribute('type')).toBe('submit');
      // The shared Form puts the submit action in a `.touch` wrapper, so the
      // stylesheet supplies the 44px coarse-pointer floor without an inline size.
      expect(form.querySelector('[data-part="actions"]')?.classList.contains('touch')).toBe(true);
      expect(register.classList.contains('touch')).toBe(true);
      const status = screen.getByTestId('patients-registration-status');
      expect(status.getAttribute('role')).toBe('status');

      const focusables = Array.from(root.querySelectorAll('input, button'));
      expect(focusables).toEqual([search, submit, register]);
      expect(
        Array.from(root.querySelectorAll('input, button')).map((node) => node.tagName),
      ).toEqual(['INPUT', 'BUTTON', 'BUTTON']);
    });

    it('keeps the full clinical table and adapts only its scroll treatment', async () => {
      installMatchMedia(VIEWPORTS[band]);
      const { table } = await searchAndGetTable();

      const results = pageRoot().querySelector('[data-part="results"]');
      expect(results).not.toBeNull();
      if (band === 'compact') {
        expect(results?.classList.contains('table-region')).toBe(true);
        expect(results?.getAttribute('role')).toBe('region');
        expect(results?.getAttribute('tabindex')).toBe('0');
        expect(results?.getAttribute('aria-label')).toBe('Patient search results');
        expect(results?.getAttribute('data-layout')).toBe('scroll-region');
        // The region is a real tab stop after the controls and before the row
        // links, so every column is keyboard-reachable without a page scroll.
        const tabStops = Array.from(
          pageRoot().querySelectorAll('input, button, a, [tabindex="0"]'),
        );
        expect(tabStops.slice(0, 5).map((node) => node.tagName)).toEqual([
          'INPUT',
          'BUTTON',
          'BUTTON',
          'DIV',
          'A',
        ]);
        expect(tabStops[3]).toBe(results);
        (results as HTMLElement).focus();
        expect(document.activeElement).toBe(results);
      } else {
        expect(results?.classList.contains('table-region')).toBe(false);
        expect(results?.getAttribute('data-layout')).toBe('table');
      }

      // Every API column stays reachable; none is hidden to make room.
      expect(
        within(table)
          .getAllByRole('columnheader')
          .map((node) => node.textContent),
      ).toEqual(['Name', 'MRN', 'Issuer', 'Date of birth']);
      for (const cell of within(table).getAllByRole('cell')) {
        expect(cell.style.display).not.toBe('none');
      }
      // Include the row-header name cell: no clinical header, row header or
      // value is hidden by a display utility class at any band.
      const clinical = [
        ...within(table).getAllByRole('columnheader'),
        ...within(table).getAllByRole('rowheader'),
        ...within(table).getAllByRole('cell'),
      ];
      for (const node of clinical) {
        expect(node.classList.contains('hide-compact')).toBe(false);
        expect(node.classList.contains('only-compact')).toBe(false);
        expect(node.style.display).not.toBe('none');
      }
    });

    it('keeps every registration field visible with logical-only styles', () => {
      installMatchMedia(VIEWPORTS[band]);
      renderPage();
      fireEvent.click(screen.getByRole('button', { name: 'Register' }));

      const dialog = screen.getByRole('dialog', { name: 'Register patient' });
      for (const label of [
        'Display name',
        'MRN',
        'MRN issuer',
        'Date of birth (YYYY-MM-DD)',
        'Unknown-date reason (code)',
        'Language code',
      ]) {
        expect(within(dialog).getByLabelText(label)).toBeTruthy();
      }
      assertLogicalOnly(pageRoot());
    });
  },
);

describe('responsive PatientsPage viewport matrix', () => {
  const widths = [320, 360, 390, 768, 820, 1024, 1280] as const;

  for (const width of widths) {
    it(`resolves ${width}px to the correct layout band`, () => {
      installMatchMedia({
        medium: width >= BREAKPOINT_WIDTHS.medium,
        expanded: width >= BREAKPOINT_WIDTHS.expanded,
        coarse: width < BREAKPOINT_WIDTHS.expanded,
        portrait: width < BREAKPOINT_WIDTHS.medium,
      });
      renderPage();

      const root = pageRoot();
      const expected = breakpointFor(width);
      expect(root.getAttribute('data-breakpoint')).toBe(expected);
      expect(root.classList.contains('page')).toBe(true);
      expect(root.querySelector('[data-part="toolbar"]')?.getAttribute('data-layout')).toBe(
        expected === 'compact' ? 'stacked' : 'side-by-side',
      );
    });
  }
});

describe('responsive PatientsPage right-to-left', () => {
  it('mirrors at compact with a long Arabic table and no local direction override', async () => {
    installMatchMedia(VIEWPORTS.compact);
    const { table, caption } = await searchAndGetTable('ar');

    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    const root = pageRoot();
    expect(root.getAttribute('data-breakpoint')).toBe('compact');
    expect(root.querySelector('[data-part="toolbar"]')?.classList.contains('stack')).toBe(true);

    const name = within(table).getByText('ليلى حداد');
    expect(name.tagName).toBe('BDI');
    expect(name.getAttribute('dir')).toBe('auto');
    expect(name.getAttribute('lang')).toBe('ar');

    const results = root.querySelector('[data-part="results"]');
    expect(results?.getAttribute('role')).toBe('region');
    expect(results?.getAttribute('aria-label')).toBe(caption);
    expect(results?.classList.contains('table-region')).toBe(true);
    assertLogicalOnly(root);
  });

  it('keeps Arabic registration fields labelled at an expanded width', () => {
    installMatchMedia(VIEWPORTS.expanded);
    renderPage('ar');
    fireEvent.click(screen.getByRole('button', { name: 'تسجيل' }));

    const dialog = screen.getByRole('dialog', { name: 'تسجيل مريض' });
    for (const label of [
      'الاسم المعروض',
      'رقم الملف',
      'جهة إصدار رقم الملف',
      'تاريخ الميلاد (YYYY-MM-DD)',
      'سبب تاريخ الميلاد غير المعروف (رمز)',
      'رمز اللغة',
    ]) {
      expect(within(dialog).getByLabelText(label)).toBeTruthy();
    }
    expect(pageRoot().querySelector('[data-part="toolbar"]')?.classList.contains('grid-2')).toBe(
      true,
    );
    assertLogicalOnly(pageRoot());
  });
});
