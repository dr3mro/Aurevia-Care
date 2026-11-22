import { act, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Patient } from '../../../src/features/patients/domain';
import { patientsText } from '../../../src/features/patients/messages';
import { PatientsScreen } from '../../../src/features/patients/PatientsScreen';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import type { Locale } from '../../../src/i18n/messages';
import { BREAKPOINT_MEDIA_QUERIES, breakpointFor } from '../../../src/theme/breakpoints';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';

type ChangeListener = (event: MediaQueryListEvent) => void;

interface QueryState {
  matches: boolean;
  listeners: Set<ChangeListener>;
}

/**
 * Deterministic matchMedia stand-in keyed by media query string. One state
 * object is reused per query so the hook's initializer and effect see the same
 * listeners. Every value below is synthetic; jsdom asserts structure, labels
 * and classes, not painted pixels (WEB-07, UX-01).
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
    vi.fn((query: string) => ({
      get matches() {
        return state(query).matches;
      },
      addEventListener: (_type: string, listener: ChangeListener) => {
        state(query).listeners.add(listener);
      },
      removeEventListener: (_type: string, listener: ChangeListener) => {
        state(query).listeners.delete(listener);
      },
    })),
  );
  return {
    fire(query: string, matches: boolean) {
      const current = state(query);
      current.matches = matches;
      for (const listener of current.listeners) {
        listener({ matches } as MediaQueryListEvent);
      }
    },
  };
}

/** Resolve the two min-width media-query states for a viewport width. */
function queriesForWidth(width: number): Record<string, boolean> {
  const band = breakpointFor(width);
  return {
    [BREAKPOINT_MEDIA_QUERIES.medium]: band !== 'compact',
    [BREAKPOINT_MEDIA_QUERIES.expanded]: band === 'expanded',
  };
}

const CAPTION_EN = patientsText('en', 'resultsCaption');
const CAPTION_AR = patientsText('ar', 'resultsCaption');

const LONG_ENGLISH =
  'A deliberately long synthetic patient name that must wrap without clipping or overlap at 320 px and at 200 percent zoom';
const LONG_ARABIC = 'اسم مريض تجريبي طويل جداً يجب أن يلتف دون قص أو تداخل عند العرض الضيق';

/** Build one synthetic patient row with optional overrides. */
function patient(overrides: Partial<Patient> = {}): Patient {
  return {
    organizationId: '00000000-0000-0000-0000-000000000001',
    id: 'patient-responsive-1',
    version: 1,
    displayName: LONG_ENGLISH,
    mrn: 'MRN-R1001',
    mrnIssuer: 'SYNTHETIC',
    dob: '1990-05-12',
    dobUnknownReason: null,
    language: 'en',
    ...overrides,
  };
}

/** Render the screen under providers; matchMedia is installed by each test. */
function renderScreen({
  locale = 'en',
  rows = [patient()],
}: { locale?: Locale; rows?: readonly Patient[] } = {}) {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <PatientsScreen patients={rows} locale={locale} />
      </I18nProvider>
    </ThemeProvider>,
  );
}

/** Assert every header and value is present, unhidden and logically wrapping. */
function expectFullUnhiddenTable(table: HTMLElement, caption: string, headers: string[]) {
  expect(within(table).getAllByRole('columnheader').map((node) => node.textContent)).toEqual(headers);
  for (const header of within(table).getAllByRole('columnheader')) {
    expect(header.getAttribute('scope')).toBe('col');
    expect((header as HTMLElement).tabIndex).toBe(-1);
  }
  for (const cell of within(table).getAllByRole('cell')) {
    const element = cell as HTMLElement;
    expect(element.tabIndex).toBe(-1);
    expect(element.style.display).not.toBe('none');
    expect(element.style.overflowWrap).toBe('anywhere');
    expect(element.style.whiteSpace).toBe('normal');
    expect(element.getAttribute('style')).not.toContain('display: none');
  }
  expect(table.querySelector('caption')?.textContent).toBe(caption);
}

afterEach(() => {
  vi.unstubAllGlobals();
  document.documentElement.removeAttribute('dir');
  document.documentElement.removeAttribute('lang');
});

describe('responsive PatientsScreen (WEB-07, UX-01)', () => {
  it.each([320, 360, 390] as const)(
    'keeps every clinical column in a labelled, keyboard-scrollable region at %ipx',
    (width) => {
      installMatchMedia(queriesForWidth(width));
      renderScreen();

      const region = screen.getByRole('region', { name: CAPTION_EN }) as HTMLElement;
      expect(region.className).toContain('table-region');
      expect(region.getAttribute('tabindex')).toBe('0');
      expect(region.tabIndex).toBe(0);

      const table = within(region).getByRole('table', { name: CAPTION_EN });
      expectFullUnhiddenTable(table, CAPTION_EN, ['Name', 'MRN', 'Issuer', 'Date of birth']);
      expect(within(table).getByRole('cell', { name: 'MRN-R1001' })).toBeTruthy();
      expect(within(table).getByRole('cell', { name: 'SYNTHETIC' })).toBeTruthy();
      expect(within(table).getByRole('cell', { name: 'May 12, 1990' })).toBeTruthy();

      const rowHeader = within(table).getByRole('rowheader', { name: LONG_ENGLISH });
      expect(rowHeader.getAttribute('scope')).toBe('row');
      const link = within(rowHeader).getByRole('link', { name: LONG_ENGLISH }) as HTMLElement;
      expect(link.getAttribute('href')).toBe('/patients/patient-responsive-1');
      // The shared Link primitive does not take a className; a grid wrapper
      // carries the stylesheet's coarse-pointer target floor and stretches the
      // anchor to fill it (the StateAction pattern).
      const touchWrapper = link.parentElement as HTMLElement;
      expect(touchWrapper.className).toContain('touch');
      expect(touchWrapper.style.display).toBe('grid');
      expect(link.tabIndex).toBe(0);
      expect(screen.getByText(LONG_ENGLISH).getAttribute('data-breakpoint')).toBe('compact');

      // Keyboard order is the labelled scroll region followed by the row's
      // link in DOM order; headers and cells add no tab stops.
      const stops = [
        region,
        ...Array.from(
          region.querySelectorAll<HTMLElement>('a[href], button, input, select, textarea, [tabindex="0"]'),
        ),
      ];
      expect(stops).toEqual([region, link]);
    },
  );

  it.each([768, 820, 1024] as const)('keeps the full table without a scroll region at %ipx', (width) => {
    installMatchMedia(queriesForWidth(width));
    renderScreen();

    expect(screen.queryByRole('region', { name: CAPTION_EN })).toBeNull();
    const table = screen.getByRole('table', { name: CAPTION_EN });
    expectFullUnhiddenTable(table, CAPTION_EN, ['Name', 'MRN', 'Issuer', 'Date of birth']);
    expect(screen.getByText(LONG_ENGLISH).getAttribute('data-breakpoint')).toBe('medium');
  });

  it('keeps the full table on expanded desktops', () => {
    installMatchMedia(queriesForWidth(1280));
    renderScreen();

    expect(screen.queryByRole('region', { name: CAPTION_EN })).toBeNull();
    const table = screen.getByRole('table', { name: CAPTION_EN });
    expectFullUnhiddenTable(table, CAPTION_EN, ['Name', 'MRN', 'Issuer', 'Date of birth']);
    expect(screen.getByText(LONG_ENGLISH).getAttribute('data-breakpoint')).toBe('expanded');
  });

  it('drops the scroll region after a resize without losing the row', () => {
    const media = installMatchMedia(queriesForWidth(320));
    renderScreen();
    expect(screen.getByRole('region', { name: CAPTION_EN })).toBeTruthy();

    act(() => media.fire(BREAKPOINT_MEDIA_QUERIES.medium, true));
    expect(screen.queryByRole('region', { name: CAPTION_EN })).toBeNull();
    expect(screen.getByRole('table', { name: CAPTION_EN })).toBeTruthy();
    expect(screen.getByText(LONG_ENGLISH).getAttribute('data-breakpoint')).toBe('medium');

    act(() => media.fire(BREAKPOINT_MEDIA_QUERIES.expanded, true));
    expect(screen.getByText(LONG_ENGLISH).getAttribute('data-breakpoint')).toBe('expanded');
    expect(screen.getByRole('cell', { name: 'MRN-R1001' })).toBeTruthy();
  });

  it('keeps logical wrapping and a full, unhidden column set under right-to-left', () => {
    document.documentElement.setAttribute('dir', 'rtl');
    document.documentElement.setAttribute('lang', 'ar');
    installMatchMedia(queriesForWidth(360));
    renderScreen({
      locale: 'ar',
      rows: [patient({ displayName: LONG_ARABIC, mrn: 'MRN-2002', language: 'ar' })],
    });

    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    const region = screen.getByRole('region', { name: CAPTION_AR }) as HTMLElement;
    const table = within(region).getByRole('table', { name: CAPTION_AR });
    expectFullUnhiddenTable(table, CAPTION_AR, ['الاسم', 'رقم الملف', 'الجهة المُصدرة', 'تاريخ الميلاد']);

    const run = screen.getByText(LONG_ARABIC) as HTMLElement;
    expect(run.tagName).toBe('BDI');
    expect(run.getAttribute('dir')).toBe('auto');
    expect(run.getAttribute('lang')).toBe('ar');
    expect(run.getAttribute('data-breakpoint')).toBe('compact');
    expect(run.style.whiteSpace).toBe('normal');
    expect(run.style.overflowWrap).toBe('anywhere');

    // Direction comes from the document. No component sets a direction
    // override, a physical side or a hidden clinical value; the caption and
    // table use logical properties so one rule set mirrors under RTL.
    for (const element of [region, ...Array.from(region.querySelectorAll('*'))] as HTMLElement[]) {
      const style = element.getAttribute('style') ?? '';
      expect(style).not.toMatch(/(?:margin|padding|border|inset)-(?:left|right)\s*:/);
      expect(style).not.toMatch(/text-align\s*:\s*(?:left|right)/);
      expect(style).not.toContain('display: none');
      expect(element.style.direction).toBe('');
    }
    expect((table.querySelector('caption') as HTMLElement).style.textAlign).toBe('start');
    expect((table as HTMLElement).style.inlineSize).toBe('100%');
  });
});
