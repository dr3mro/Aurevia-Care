import { act, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ActiveOrganizationProvider } from '../../../src/api/activeOrganization';
import { send } from '../../../src/api/transport';
import type * as TransportModule from '../../../src/api/transport';
import { RouterProvider } from '../../../src/app/router';
import { ChartPage } from '../../../src/features/chart/ChartPage';
import type {
  ChartEntryResponse,
  ChartViewResponse,
  PatientViewResponse,
} from '../../../src/features/chart/api';
import { chartText } from '../../../src/features/chart/messages';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import type { Locale } from '../../../src/i18n/messages';
import { BREAKPOINT_MEDIA_QUERIES } from '../../../src/theme/breakpoints';
import type { Breakpoint } from '../../../src/theme/breakpoints';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';
import { must } from '../../must';

vi.mock('../../../src/api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

type ChangeListener = (event: MediaQueryListEvent) => void;

interface QueryState {
  matches: boolean;
  listeners: Set<ChangeListener>;
}

/** Deterministic, stateful `matchMedia` stand-in keyed by media query string. */
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
    fire(query: string, matches: boolean) {
      const current = state(query);
      current.matches = matches;
      for (const listener of current.listeners) {
        listener({ matches } as MediaQueryListEvent);
      }
    },
  };
}

function layoutQueries(breakpoint: Breakpoint): Record<string, boolean> {
  return {
    [BREAKPOINT_MEDIA_QUERIES.medium]: breakpoint !== 'compact',
    [BREAKPOINT_MEDIA_QUERIES.expanded]: breakpoint === 'expanded',
    '(pointer: coarse)': true,
  };
}

const BREAKPOINTS: readonly Breakpoint[] = ['compact', 'medium', 'expanded'];

const LONG_ENGLISH =
  'A deliberately long synthetic patient name that must wrap without clipping, overlap or a horizontal page scroll at 320 pixels';
const LONG_ARABIC = 'اسم مريض تجريبي طويل جداً يجب أن يلتف دون قص أو تداخل أو تمرير أفقي للصفحة';

function entry(overrides: Partial<ChartEntryResponse> = {}): ChartEntryResponse {
  return {
    id: 'entry-1',
    patientId: 'patient-1',
    encounterId: null,
    type: 'PROBLEM',
    originalText: 'A synthetic clinical observation long enough to wrap on a phone',
    originalCode: 'SYN-0001',
    codeSystem: 'local',
    mappedCode: null,
    mappedText: null,
    catalogVersion: null,
    source: 'CLINICIAN_OBSERVED',
    presence: 'PRESENT',
    unknownReason: null,
    verification: 'VERIFIED',
    verifiedBy: 'dr-1',
    verifiedAt: null,
    effectiveFrom: '2026-03-09',
    effectiveTo: null,
    measuredAt: null,
    value: null,
    unit: null,
    clinicalAuthor: 'dr-1',
    dataEntryActor: 'reception-1',
    version: 1,
    recordedAt: '2026-03-09T09:00:00Z',
    ...overrides,
  };
}

function chartView(nextCursor?: string): ChartViewResponse {
  return { entries: [entry()], nextCursor: nextCursor ?? null };
}

function patientView(displayName: string): PatientViewResponse {
  return { patient: { id: 'patient-1', displayName }, duplicates: [] };
}

interface RenderOptions {
  breakpoint: Breakpoint;
  locale?: Locale;
  displayName?: string;
  nextCursor?: string;
}

/** Render the real chart page through its normal providers with synthetic data. */
function renderPage(options: RenderOptions) {
  const media = installMatchMedia(layoutQueries(options.breakpoint));
  sendMock.mockImplementation((request) => {
    const url = (request as { url: string }).url;
    return Promise.resolve(
      url.startsWith('/api/v1/patients/')
        ? patientView(options.displayName ?? 'Layla Haddad')
        : chartView(options.nextCursor),
    );
  });
  const view = render(
    <ThemeProvider>
      <I18nProvider initialLocale={options.locale ?? 'en'}>
        <ActiveOrganizationProvider value={{ organizationId: 'org-a' }}>
          <RouterProvider
            patterns={['/patients/:id/chart']}
            initialPath="/patients/patient-1/chart"
          >
            <ChartPage />
          </RouterProvider>
        </ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>,
  );
  return { ...view, media };
}

/** The four clinical tables are the same at every breakpoint; none is hidden. */
const CLINICAL_TABLES = {
  problems: [
    'code',
    'originalText',
    'display',
    'verification',
    'source',
    'effectiveOn',
    'clinicalAuthor',
    'enteredBy',
  ],
  allergies: [
    'code',
    'originalText',
    'display',
    'verification',
    'source',
    'effectiveOn',
    'clinicalAuthor',
    'enteredBy',
  ],
  medications: [
    'code',
    'originalText',
    'display',
    'verification',
    'source',
    'effectiveOn',
    'clinicalAuthor',
    'enteredBy',
  ],
  vitals: ['kind', 'measurement', 'measuredAt'],
} as const satisfies Record<string, readonly Parameters<typeof chartText>[1][]>;

/** Every inline style below stays logical and never fixes a block size. */
function expectLogicalWrapping(element: HTMLElement): void {
  const style = element.getAttribute('style') ?? '';
  expect(style).toContain('overflow-wrap: anywhere');
  expect(style).toContain('min-inline-size: 0');
  expect(style).not.toMatch(/(?:margin|padding|border)-(?:left|right)\s*:/);
  expect(style).not.toMatch(/text-align\s*:\s*(?:left|right)/);
  expect(style).not.toMatch(/(?:\bheight|(?:min-|max-)?block-size)\s*:/);
  expect(element.style.direction).toBe('');
}

beforeEach(() => {
  sendMock.mockReset();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('responsive ChartPage (WEB-07, UX-01)', () => {
  it.each(BREAKPOINTS)(
    'keeps a labelled single-column page and every clinical table at %s',
    async (breakpoint) => {
      const { container } = renderPage({ breakpoint, displayName: LONG_ENGLISH });
      const problems = await screen.findByRole('table', {
        name: chartText('en', 'problems'),
      });

      const page = problems.closest<HTMLElement>('.page');
      expect(page).not.toBeNull();
      expect(page?.getAttribute('data-breakpoint')).toBe(breakpoint);
      expect(page?.classList.contains('page')).toBe(true);
      expectLogicalWrapping(must(page));

      expect(
        screen.getByRole('heading', { level: 2, name: chartText('en', 'chart') }),
      ).toBeTruthy();
      for (const [key, headerKeys] of Object.entries(CLINICAL_TABLES)) {
        if (breakpoint === 'compact' && key === 'vitals') {
          const list = screen.getByRole('list', { name: chartText('en', 'vitals') });
          expect(within(list).getByRole('listitem').textContent).toBe(chartText('en', 'noEntries'));
          continue;
        }
        const table = screen.getByRole('table', {
          name: chartText('en', key as Parameters<typeof chartText>[1]),
        });
        expect(table.style.display).not.toBe('none');
        const headers = within(table).getAllByRole('columnheader');
        expect(headers.map((header) => header.textContent)).toEqual(
          headerKeys.map((headerKey) => chartText('en', headerKey)),
        );
        for (const header of headers) {
          expect(header.style.display).not.toBe('none');
        }
      }

      expect(screen.getAllByText(LONG_ENGLISH).length).toBeGreaterThan(0);
      const identifier = screen.getByText('patient-1');
      expect(identifier.tagName).toBe('CODE');
      expect(identifier.getAttribute('dir')).toBe('ltr');
      expect(container.querySelector('.hide-compact')).toBeNull();
    },
  );

  it('adds a labelled, keyboard-scrollable chart region only on compact', async () => {
    const compact = renderPage({ breakpoint: 'compact' });
    await screen.findByRole('table', { name: chartText('en', 'problems') });
    const region = screen.getByRole('region', { name: chartText('en', 'chart') });
    expect(region.className).toContain('table-region');
    expect(region.tabIndex).toBe(0);
    expect(within(region).getByRole('table', { name: chartText('en', 'problems') })).toBeTruthy();
    compact.unmount();

    for (const breakpoint of ['medium', 'expanded'] as const) {
      const view = renderPage({ breakpoint });
      await screen.findByRole('table', { name: chartText('en', 'problems') });
      expect(screen.queryByRole('region', { name: chartText('en', 'chart') })).toBeNull();
      expect(screen.getByRole('table', { name: chartText('en', 'problems') })).toBeTruthy();
      view.unmount();
    }
  });

  it('switches the chart region off at the medium edge without losing clinical data', async () => {
    const { media } = renderPage({ breakpoint: 'compact' });
    await screen.findByRole('table', { name: chartText('en', 'problems') });
    expect(screen.getByRole('region', { name: chartText('en', 'chart') })).toBeTruthy();

    act(() => media.fire(BREAKPOINT_MEDIA_QUERIES.medium, true));

    expect(screen.queryByRole('region', { name: chartText('en', 'chart') })).toBeNull();
    expect(screen.getByRole('table', { name: chartText('en', 'problems') })).toBeTruthy();
  });

  it.each(BREAKPOINTS)(
    'keeps Load more a native, labelled touch target in keyboard order at %s',
    async (breakpoint) => {
      const { container } = renderPage({ breakpoint, nextCursor: 'cursor-2' });
      const more = await screen.findByRole('button', {
        name: chartText('en', 'loadMore'),
      });

      expect(more.tagName).toBe('BUTTON');
      expect(more.className).toContain('touch');
      expect(more.getAttribute('data-breakpoint')).toBe(breakpoint);
      expect((more as HTMLButtonElement).disabled).toBe(false);

      // The table region is a tab stop only on compact; Load more stays last in
      // DOM order so keyboard users reach the same action at every width.
      const focusable = Array.from(
        container.querySelectorAll<HTMLElement>('button, [tabindex="0"]'),
      );
      expect(focusable[focusable.length - 1]).toBe(more);
      if (breakpoint === 'compact') {
        expect(focusable[0]?.className).toContain('table-region');
      } else {
        expect(focusable).toHaveLength(1);
      }
      more.focus();
      expect(document.activeElement).toBe(more);
    },
  );

  it('wraps long English identity text without physical styling or a fixed height', async () => {
    renderPage({ breakpoint: 'compact', displayName: LONG_ENGLISH });
    await screen.findByRole('table', { name: chartText('en', 'problems') });
    const paragraph = screen.getAllByText(LONG_ENGLISH)[0]?.closest('p') as HTMLElement;
    expect(paragraph).not.toBeNull();
    expectLogicalWrapping(paragraph);
  });

  it('mirrors an Arabic RTL chart with isolated identifiers and no direction override', async () => {
    const { container } = renderPage({
      breakpoint: 'expanded',
      locale: 'ar',
      displayName: LONG_ARABIC,
    });
    const problems = await screen.findByRole('table', {
      name: chartText('ar', 'problems'),
    });

    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    expect(screen.getAllByText(LONG_ARABIC).length).toBeGreaterThan(0);

    const identifier = screen.getByText('patient-1');
    expect(identifier.tagName).toBe('CODE');
    expect(identifier.getAttribute('dir')).toBe('ltr');

    const page = must(problems.closest<HTMLElement>('.page'));
    expect(page.getAttribute('data-breakpoint')).toBe('expanded');
    for (const element of [page, ...Array.from(page.querySelectorAll<HTMLElement>('*'))]) {
      const style = element.getAttribute('style') ?? '';
      expect(style).not.toMatch(/(?:margin|padding|border)-(?:left|right)\s*:/);
      expect(style).not.toMatch(/text-align\s*:\s*(?:left|right)/);
      expect(element.style.direction).toBe('');
    }
    expect(container.querySelector('.hide-compact')).toBeNull();
  });
});
