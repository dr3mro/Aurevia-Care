import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import type { ReactElement } from 'react';
import { ActiveOrganizationProvider } from '../../../src/api/activeOrganization';
import { send } from '../../../src/api/transport';
import type * as TransportModule from '../../../src/api/transport';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import type { Locale } from '../../../src/i18n/messages';
import { BREAKPOINT_MEDIA_QUERIES, BREAKPOINT_WIDTHS } from '../../../src/theme/breakpoints';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';
import { alphaOrganizationId } from '../../../src/features/admin/fixtures';
import { adminText } from '../../../src/features/admin/messages';
import { ConfigPage } from '../../../src/features/admin/ConfigPage';

vi.mock('../../../src/api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);
const key = 'facility.MAIN.booking';
const longEnglish = 'A deliberately long synthetic configuration value that must wrap without clipping or overlap on a narrow phone viewport';
const longArabic = 'قيمة configuring تجريبية طويلة جداً يجب أن تلتف دون قص أو تداخل على شاشة الهاتف';

type ChangeListener = (event: MediaQueryListEvent) => void;
type Band = 'compact' | 'medium' | 'expanded';

interface WidthCase {
  width: number;
  band: Band;
  coarse: boolean;
}

/** The seven widths named in the story, mapped to their layout band and pointer. */
const WIDTH_CASES: readonly WidthCase[] = [
  { width: 320, band: 'compact', coarse: true },
  { width: 360, band: 'compact', coarse: true },
  { width: 390, band: 'compact', coarse: true },
  { width: 768, band: 'medium', coarse: true },
  { width: 820, band: 'medium', coarse: true },
  { width: 1024, band: 'medium', coarse: true },
  { width: 1280, band: 'expanded', coarse: false },
];

/**
 * A deterministic matchMedia stand-in keyed by media query. One state object is
 * reused per query so the hook's initializer and effect agree. jsdom cannot lay
 * out pixels, so the assertions read classes, data attributes and DOM order.
 */
function installMatchMedia(width: number, coarse: boolean): void {
  const answers: Record<string, boolean> = {
    [BREAKPOINT_MEDIA_QUERIES.medium]: width >= BREAKPOINT_WIDTHS.medium,
    [BREAKPOINT_MEDIA_QUERIES.expanded]: width >= BREAKPOINT_WIDTHS.expanded,
    '(pointer: coarse)': coarse,
    '(orientation: portrait)': width < BREAKPOINT_WIDTHS.medium,
  };
  const states = new Map<string, { matches: boolean; listeners: Set<ChangeListener> }>();
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => {
      let state = states.get(query);
      if (!state) {
        state = { matches: answers[query] ?? false, listeners: new Set<ChangeListener>() };
        states.set(query, state);
      }
      const current = state;
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
      } as unknown as MediaQueryList;
    }),
  );
}

function element(ui: ReactElement, locale: Locale = 'en') {
  return (
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ActiveOrganizationProvider value={{ organizationId: alphaOrganizationId }}>
          {ui}
        </ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>
  );
}

function wrap(ui: ReactElement, locale: Locale = 'en') {
  return render(element(ui, locale));
}

function history(entries: Record<string, unknown>[] = []) {
  return { key, entries };
}

function entry(overrides: Record<string, unknown> = {}) {
  return {
    key,
    version: 2,
    value: longEnglish,
    status: 'approved',
    effectiveFrom: '2026-03-01T08:00:00Z',
    approvedBy: 'synthetic.approver',
    ...overrides,
  };
}

function catalogEntry(code: string) {
  return { family: 'diagnosis', code, version: 1, value: longEnglish };
}

function lookup(locale: Locale = 'en') {
  fireEvent.change(screen.getByLabelText(adminText(locale, 'configurationKeyLookup')), {
    target: { value: key },
  });
  fireEvent.click(screen.getByRole('button', { name: adminText(locale, 'configLookup') }));
}

/** Select the keyboard-scrollable table region from the other same-named landmarks. */
function tableRegion(name: string): HTMLElement {
  const region = screen
    .getAllByRole('region', { name })
    .find((node) => node.getAttribute('tabindex') === '0');
  if (!region) {
    throw new Error(`labelled table region "${name}" was not rendered`);
  }
  return region as HTMLElement;
}

/** Assert that no element uses a physical side, an alignment override or a local direction. */
function assertLogicalOnly(root: HTMLElement): void {
  const styled = Array.from(root.querySelectorAll<HTMLElement>('[style]')).concat(root);
  for (const node of styled) {
    expect(node.style.direction).toBe('');
    expect(node.style.getPropertyValue('margin-left')).toBe('');
    expect(node.style.getPropertyValue('margin-right')).toBe('');
    expect(node.style.getPropertyValue('padding-left')).toBe('');
    expect(node.style.getPropertyValue('padding-right')).toBe('');
    expect(node.style.getPropertyValue('left')).toBe('');
    expect(node.style.getPropertyValue('right')).toBe('');
    expect(node.style.textAlign).not.toBe('left');
    expect(node.style.textAlign).not.toBe('right');
  }
}

beforeEach(() => {
  sendMock.mockReset();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('responsive ConfigPage (WEB-07, UX-01)', () => {
  it.each(WIDTH_CASES)(
    'exposes the layout class and keeps the page logical at $width px ($band)',
    async ({ width, band, coarse }) => {
      installMatchMedia(width, coarse);
      sendMock.mockResolvedValueOnce(history([entry()]));
      const { container } = wrap(<ConfigPage />);

      const root = container.firstElementChild as HTMLElement;
      expect(root.classList.contains('page')).toBe(true);
      expect(root.getAttribute('data-breakpoint')).toBe(band);
      expect(root.getAttribute('data-coarse-pointer')).toBe(String(coarse));

      // The lookup section stacks its heading, prose and form on every band.
      const lookupSection = root.querySelector('section.stack');
      expect(lookupSection).not.toBeNull();
      expect(within(lookupSection as HTMLElement).getByRole('heading', { level: 2 })).toBeTruthy();

      // Labels and roles stay intact and DOM order gives a predictable keyboard path.
      const input = screen.getByLabelText(adminText('en', 'configurationKeyLookup')) as HTMLInputElement;
      expect(input.classList.contains('field')).toBe(true);
      expect(input.classList.contains('touch')).toBe(true);
      const buttons = screen.getAllByRole('button');
      expect(buttons[0]).toBe(screen.getByRole('button', { name: adminText('en', 'configLookup') }));
      expect(input.compareDocumentPosition(buttons[0] as Node) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();

      // Loaded content keeps the full, unhidden clinical table and logical styling.
      lookup();
      await screen.findByText(longEnglish);
      const region = tableRegion(adminText('en', 'configurationVersions'));
      expect(region.getAttribute('tabindex')).toBe('0');
      const table = within(region).getByRole('table', { name: adminText('en', 'configurationVersions') });
      const headers = within(table).getAllByRole('columnheader').map((node) => node.textContent);
      expect(headers).toEqual([
        adminText('en', 'version'),
        adminText('en', 'configuredValue'),
        adminText('en', 'effectiveFrom'),
        adminText('en', 'approvalState'),
        adminText('en', 'approver'),
      ]);
      expect(root.querySelector('.hide-compact')).toBeNull();
      expect(root.querySelector('.only-compact')).toBeNull();
      expect(root.querySelector('[style*="display: none"]')).toBeNull();
      assertLogicalOnly(root);
    },
  );

  it('stacks the catalog definition fields on phones and uses the shared two-column grid from medium up', async () => {
    installMatchMedia(320, true);
    sendMock.mockResolvedValueOnce(history([entry()]));
    sendMock.mockResolvedValue([catalogEntry('SYN')]);
    wrap(<ConfigPage />);
    lookup();
    await screen.findByText(longEnglish);

    fireEvent.change(screen.getByLabelText(adminText('en', 'catalogFamily')), {
      target: { value: 'diagnosis' },
    });
    fireEvent.click(screen.getByRole('button', { name: adminText('en', 'catalogFamilyLookup') }));

    const form = await screen.findByRole('form', { name: adminText('en', 'defineCatalogEntry') });
    const fields = form.querySelector('[data-part="fields"] > .grid-2');
    expect(fields).not.toBeNull();
    expect(form.querySelector('[data-part="fields"]')?.getAttribute('data-layout')).toBe('single-column');
  });

  it.each([320, 768, 1280])('keeps the catalogue table caption and columns at %ipx', (width) => {
    installMatchMedia(width, width < 768);
    sendMock.mockResolvedValueOnce(history([entry()]));
    sendMock.mockResolvedValue([catalogEntry('SYN')]);
    wrap(<ConfigPage />);
    lookup();
    return screen.findByText(longEnglish).then(async () => {
      fireEvent.change(screen.getByLabelText(adminText('en', 'catalogFamily')), {
        target: { value: 'diagnosis' },
      });
      fireEvent.click(screen.getByRole('button', { name: adminText('en', 'catalogFamilyLookup') }));
      const table = await screen.findByRole('table', { name: adminText('en', 'catalogEntries') });
      expect(within(table).getAllByRole('columnheader').map((node) => node.textContent)).toEqual([
        adminText('en', 'catalogCode'),
        adminText('en', 'catalogValue'),
        adminText('en', 'version'),
      ]);
      expect(table.querySelector('caption')?.textContent).toBe(adminText('en', 'catalogEntries'));
      for (const cell of within(table).getAllByRole('cell')) {
        expect((cell as HTMLElement).style.display).not.toBe('none');
      }
    });
  });

  it('mirrors under right-to-left without a direction override or a hidden column', async () => {
    installMatchMedia(360, true);
    sendMock.mockResolvedValueOnce(
      history([entry({ value: longArabic, approvedBy: 'مُعتمد تجريبي' })]),
    );
    const { container } = wrap(<ConfigPage />, 'ar');

    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    const root = container.firstElementChild as HTMLElement;
    expect(root.getAttribute('data-breakpoint')).toBe('compact');

    lookup('ar');
    await screen.findByText(longArabic);
    const region = tableRegion(adminText('ar', 'configurationVersions'));
    const table = within(region).getByRole('table', { name: adminText('ar', 'configurationVersions') });
    expect(within(table).getAllByRole('columnheader')).toHaveLength(5);
    const arabicValue = screen.getByText(longArabic) as HTMLElement;
    expect(arabicValue.style.overflowWrap).toBe('anywhere');
    expect(root.querySelector('[style*="display: none"]')).toBeNull();
    assertLogicalOnly(root);
  });

  it('keeps the approval dialog stacked with touch-sized, ordered actions on a phone', async () => {
    installMatchMedia(320, true);
    sendMock.mockResolvedValueOnce(
      history([entry({ version: 3, status: 'pending', approvedBy: null, value: longEnglish })]),
    );
    wrap(<ConfigPage />);
    lookup();
    await screen.findByText(longEnglish);

    fireEvent.click(screen.getByRole('button', { name: adminText('en', 'approve') }));
    const dialog = screen.getByRole('dialog') as HTMLElement;
    expect(dialog.classList.contains('stack')).toBe(true);
    const actions = within(dialog).getAllByRole('button');
    expect(actions.map((node) => node.textContent)).toEqual([
      adminText('en', 'cancel'),
      adminText('en', 'approveConfirm'),
    ]);
    for (const action of actions) {
      expect(action.classList.contains('touch')).toBe(true);
    }
    expect(document.activeElement).toBe(actions[0]);
  });
});
