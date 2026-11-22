/**
 * Responsive structure contract for the API-backed scheduling page (WEB-07,
 * UX-01). jsdom cannot paint, so these assertions cover classes, attributes,
 * roles, labels and DOM keyboard order at every layout band. Painted pixels,
 * touch-target boxes and zoom belong to the Playwright tasks.
 *
 * All records below are synthetic; no real patient or tenant identifier is used.
 */
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { ActiveOrganizationProvider } from '../../../src/api/activeOrganization';
import { TransportError, send } from '../../../src/api/transport';
import type * as TransportModule from '../../../src/api/transport';
import { stateViewText } from '../../../src/components/stateViewMessages';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import type { Locale } from '../../../src/i18n/messages';
import { BREAKPOINT_WIDTHS } from '../../../src/theme/breakpoints';
import type { Breakpoint } from '../../../src/theme/breakpoints';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';
import { SchedulingPage } from '../../../src/features/scheduling/SchedulingPage';
import type { AppointmentViewResponse } from '../../../src/features/scheduling/api';
import { alphaOrganizationId } from '../../../src/features/scheduling/fixtures';
import { BREAKPOINT_MEDIA_QUERIES } from '../../../src/theme/breakpoints';

vi.mock('../../../src/api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

const BREAKPOINTS: readonly Breakpoint[] = ['compact', 'medium', 'expanded'];
const WIDTHS = [320, 360, 390, 768, 820, 1024, 1280] as const;

/** Long synthetic values prove text wraps instead of forcing a page scroll. */
const LONG_ENGLISH = 'Synthetic North West Regional Diagnostic And Treatment Clinic patient';
const LONG_ARABIC = 'مريض تجريبي باسم طويل جداً في العيادة الشمالية الغربية للتشخيص والعلاج';

/**
 * Physical-only style keys; logical equivalents never appear in this list.
 * The symmetric `border` shorthand is direction-neutral, so its longhands are
 * intentionally absent even though jsdom expands them on the style object.
 */
const physicalStyleKeys = [
  'margin-left',
  'margin-right',
  'padding-left',
  'padding-right',
  'left',
  'right',
  'top',
  'bottom',
] as const;

interface ViewportState {
  medium: boolean;
  expanded: boolean;
  coarse: boolean;
  portrait: boolean;
}

/** Deterministic matchMedia answers for one synthetic viewport state. */
function stateFor(breakpoint: Breakpoint, coarse = true): ViewportState {
  return {
    medium: breakpoint !== 'compact',
    expanded: breakpoint === 'expanded',
    coarse,
    portrait: breakpoint === 'compact',
  };
}

/**
 * jsdom cannot evaluate media queries. Install a deterministic stand-in so the
 * real breakpoint hook resolves at each band and its descendants agree.
 */
function installViewport(state: ViewportState): void {
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
    })),
  );
}

/** Install the synthetic viewport answers for one layout band. */
function installMatchMedia(breakpoint: Breakpoint, coarse = true): void {
  installViewport(stateFor(breakpoint, coarse));
}

function apiView(patientId: string): AppointmentViewResponse {
  return {
    appointment: {
      id: 'apt-001',
      version: 3,
      patientId,
      facilityCode: 'MAIN',
      clinicianCode: 'clinician-7',
      roomCode: null,
      visitType: 'follow-up',
      startsAt: '2026-03-09T09:00:00Z',
      endsAt: '2026-03-09T09:30:00Z',
      state: 'booked',
      seriesId: null,
      expiresAt: null,
      overrideReason: null,
    },
    history: [],
  };
}

function element(locale: Locale): ReactElement {
  return (
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ActiveOrganizationProvider value={{ organizationId: alphaOrganizationId }}>
          <SchedulingPage anchorCivilDate="2026-03-09" />
        </ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>
  );
}

/** Render one synthetic page with a single appointment row in the given locale. */
function renderPage(locale: Locale = 'en', patientId = LONG_ENGLISH) {
  sendMock.mockResolvedValue({ items: [apiView(patientId)], nextCursor: null });
  return render(element(locale));
}

/** Locate the page wrapper that owns the safe-area and reading-width classes. */
function pageRoot(container: HTMLElement): HTMLElement {
  const root = container.querySelector<HTMLElement>('[data-scheduling-page]');
  if (!root) {
    throw new Error('responsive scheduling page wrapper was not rendered');
  }
  return root;
}

/** Assert that no subtree element takes a physical side or a local direction. */
function expectLogicalOnly(root: HTMLElement): void {
  const styled = Array.from(root.querySelectorAll<HTMLElement>('[style]')).concat(root);
  for (const node of styled) {
    expect(node.style.direction).toBe('');
    expect(node.style.display).not.toBe('none');
    expect(node.style.textAlign).not.toBe('left');
    expect(node.style.textAlign).not.toBe('right');
    for (const key of physicalStyleKeys) {
      expect(node.style.getPropertyValue(key)).toBe('');
    }
  }
}

beforeEach(() => {
  sendMock.mockReset();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe.each(BREAKPOINTS)('responsive SchedulingPage at %s width', (breakpoint) => {
  it('keeps the labelled page wrapper and every clinical column', async () => {
    installMatchMedia(breakpoint);
    const { container } = renderPage();
    const rowHeader = await screen.findByRole('rowheader', { name: LONG_ENGLISH });

    const root = pageRoot(container);
    expect(root.classList.contains('page')).toBe(true);
    expect(root.getAttribute('data-breakpoint')).toBe(breakpoint);
    // The page class owns safe-area padding and the 16px gutter. The only
    // inline override removes its reading-width cap so the worklist table can
    // use the full inline space; no physical inset or local gap is introduced.
    expect(root.style.maxInlineSize).toBe('none');
    expect(root.style.paddingInlineStart).toBe('');
    expect(root.style.paddingInlineEnd).toBe('');
    expect(root.style.gap).toBe('');

    const table = screen.getByRole('table', { name: 'Appointments by scheduled time' });
    expect(root.contains(table)).toBe(true);
    expect(
      within(table)
        .getAllByRole('columnheader')
        .map((header) => header.textContent),
    ).toEqual(['Patient', 'Time', 'Protected resource', 'Visit state', 'Assign to', 'Actions']);
    for (const cell of within(table).getAllByRole('cell')) {
      expect((cell as HTMLElement).style.display).not.toBe('none');
    }
    expect(rowHeader).toBeTruthy();
  });

  it('keeps the same labels, roles and DOM keyboard order', async () => {
    installMatchMedia(breakpoint);
    const { container } = renderPage();
    await screen.findByText(LONG_ENGLISH);

    const root = pageRoot(container);
    expect(screen.getByRole('heading', { name: 'Scheduling' })).toBeTruthy();
    expect(screen.getByRole('status', { name: 'Scheduling status' })).toBeTruthy();
    expect(screen.getByRole('form', { name: 'Book an appointment' })).toBeTruthy();

    // DOM order is the keyboard order at every band; responsive CSS only
    // reflows presentation and never reorders or removes a control.
    const buttons = screen.getAllByRole('button');
    expect(buttons.map((button) => button.textContent)).toEqual([
      'Day',
      'Week',
      'Check in',
      'Book',
    ]);
    for (const button of buttons) {
      expect(button.classList.contains('touch')).toBe(true);
      expect((button as HTMLElement).style.minBlockSize).toBe('');
      expect((button as HTMLElement).style.minInlineSize).toBe('');
      expect(button.getAttribute('tabindex')).toBeNull();
      expect(button.getAttribute('data-breakpoint')).toBe(breakpoint);
    }

    const inputs = screen.getAllByRole('textbox') as HTMLInputElement[];
    expect(inputs.map((input) => input.id)).toEqual([
      'booking-facility',
      'booking-patient',
      'booking-visit',
      'booking-resource',
      'booking-start',
    ]);
    for (const input of inputs) {
      // .field supplies the 16px text that prevents iOS focus zoom; no inline
      // font-size may override it. .touch supplies the 44px coarse-pointer floor.
      expect(input.classList.contains('field')).toBe(true);
      expect(input.classList.contains('touch')).toBe(true);
      expect(input.style.fontSize).toBe('');
      expect(input.style.minBlockSize).toBe('');
      expect(input.style.minInlineSize).toBe('');
      // FormField exposes the band on its label/control wrapper, not the input.
      expect(input.closest('[data-breakpoint]')?.getAttribute('data-breakpoint')).toBe(breakpoint);
    }

    const assign = screen.getByLabelText('Assign to') as HTMLSelectElement;
    expect(root.contains(assign)).toBe(true);
    expect(assign.tabIndex).toBe(0);
  });

  it('wraps long text with logical-only styles and hides no field', async () => {
    installMatchMedia(breakpoint);
    const { container } = renderPage();
    await screen.findByText(LONG_ENGLISH);

    const root = pageRoot(container);
    expectLogicalOnly(root);
    expect(root.querySelector('.hide-compact')).toBeNull();
    expect(root.querySelector('.only-compact')).toBeNull();

    const clinicalValue = screen.getByText(LONG_ENGLISH);
    expect(clinicalValue.tagName).toBe('BDI');
    expect(clinicalValue.style.overflowWrap).toBe('anywhere');
    expect(clinicalValue.style.whiteSpace).toBe('normal');
    expect(clinicalValue.getAttribute('dir')).toBe('auto');
  });

  it('keeps a rejected command banner in the page without hiding the table', async () => {
    installMatchMedia(breakpoint);
    renderPage();
    const row = (await screen.findByRole('rowheader', { name: LONG_ENGLISH })).closest('tr')!;

    sendMock.mockRejectedValueOnce(
      new TransportError('conflict', {
        correlationId: 'corr-conflict',
        code: undefined,
        retryAfterSeconds: undefined,
      }),
    );
    fireEvent.click(within(row).getByRole('button', { name: 'Check in' }));

    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
    const banner = screen.getByTestId('scheduling-command-error');
    expect(banner.closest('[data-scheduling-page]')).not.toBeNull();
    expect(screen.getByRole('table', { name: 'Appointments by scheduled time' })).toBeTruthy();
    expectLogicalOnly(banner as HTMLElement);
  });
});

describe('responsive SchedulingPage width matrix', () => {
  it.each(WIDTHS)('maps %ipx to the correct layout band', async (width) => {
    // Feed the width directly into the two min-width media queries instead of
    // asking the same helper for the expected band, so the assertion tests the
    // hook's threshold resolution rather than restating the answer.
    const medium = width >= BREAKPOINT_WIDTHS.medium;
    const expanded = width >= BREAKPOINT_WIDTHS.expanded;
    const expected: Breakpoint = expanded ? 'expanded' : medium ? 'medium' : 'compact';
    installViewport({
      medium,
      expanded,
      coarse: width < BREAKPOINT_WIDTHS.expanded,
      portrait: width < BREAKPOINT_WIDTHS.medium,
    });
    const { container } = renderPage();
    await screen.findByText(LONG_ENGLISH);

    expect(pageRoot(container).getAttribute('data-breakpoint')).toBe(expected);
    for (const button of screen.getAllByRole('button')) {
      expect(button.getAttribute('data-breakpoint')).toBe(expected);
    }
    for (const input of screen.getAllByRole('textbox')) {
      expect(input.closest('[data-breakpoint]')?.getAttribute('data-breakpoint')).toBe(expected);
    }
  });
});

describe.each(['compact', 'expanded'] as const)(
  'responsive SchedulingPage right-to-left at %s width',
  (breakpoint) => {
    it('mirrors through the document with a long Arabic value and no direction override', async () => {
      installMatchMedia(breakpoint, breakpoint !== 'expanded');
      const { container } = renderPage('ar', LONG_ARABIC);
      await screen.findByText(LONG_ARABIC);

      expect(document.documentElement.dir).toBe('rtl');
      expect(document.documentElement.lang).toBe('ar');
      const root = pageRoot(container);
      expect(root.getAttribute('data-breakpoint')).toBe(breakpoint);
      expect(screen.getByRole('heading', { name: 'جدول المواعيد' })).toBeTruthy();
      expect(screen.getByRole('form', { name: 'حجز موعد' })).toBeTruthy();

      const clinicalValue = screen.getByText(LONG_ARABIC);
      expect(clinicalValue.tagName).toBe('BDI');
      expect(clinicalValue.getAttribute('dir')).toBe('auto');
      expect(clinicalValue.getAttribute('lang')).toBe('ar');
      expect(clinicalValue.style.overflowWrap).toBe('anywhere');
      expect(clinicalValue.style.whiteSpace).toBe('normal');

      expectLogicalOnly(root);
    });
  },
);
