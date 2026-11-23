import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import type { Locale } from '../../../src/i18n/messages';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';
import { BREAKPOINT_MEDIA_QUERIES, BREAKPOINT_WIDTHS } from '../../../src/theme/breakpoints';
import {
  alphaOrganizationId,
  syntheticAppointments,
} from '../../../src/features/scheduling/fixtures';
import { schedulingText } from '../../../src/features/scheduling/messages';
import { SchedulingScreen } from '../../../src/features/scheduling/SchedulingScreen';

type ChangeListener = (event: MediaQueryListEvent) => void;

/** Synthetic viewport states mirroring the responsive Playwright matrix. */
interface ViewportState {
  medium: boolean;
  expanded: boolean;
  coarse: boolean;
  portrait: boolean;
}

/** The three layout bands used by the real breakpoint hook. */
const VIEWPORTS: Record<'compact' | 'medium' | 'expanded', ViewportState> = {
  compact: { medium: false, expanded: false, coarse: true, portrait: true },
  medium: { medium: true, expanded: false, coarse: true, portrait: false },
  expanded: { medium: true, expanded: true, coarse: false, portrait: false },
};

/**
 * Direction-specific physical declarations are forbidden; symmetric shorthands
 * such as `border: 1px solid …` are direction-neutral and stay allowed. The
 * raw style attribute is inspected so logical properties cannot hide behind a
 * camelCase property name.
 */
const physicalStylePattern =
  /(?:margin|padding|border)-(?:left|right|top|bottom)\s*:|(?:^|;)\s*(?:left|right|top|bottom)\s*:|text-align\s*:\s*(?:left|right)/;

/**
 * jsdom cannot evaluate media queries, so install a deterministic stand-in
 * whose answers follow the synthetic viewport state. The real breakpoint hook
 * then resolves exactly as it does at each real width (WEB-07, UX-01).
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

/** Render the real screen with synthetic, tenant-scoped appointment fixtures. */
function renderScreen(locale: Locale = 'en'): void {
  render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <SchedulingScreen
          appointments={syntheticAppointments}
          organizationId={alphaOrganizationId}
          range="day"
          onRangeChange={vi.fn()}
          onCheckIn={vi.fn()}
          onBook={vi.fn()}
        />
      </I18nProvider>
    </ThemeProvider>,
  );
}

/** Find the screen root through its responsive layout contract attribute. */
function screenRoot(): HTMLElement {
  const root = document.querySelector('[data-scheduling-view]');
  if (!root) {
    throw new Error('responsive scheduling screen root was not rendered');
  }
  return root as HTMLElement;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe.each(['compact', 'medium', 'expanded'] as const)(
  'responsive SchedulingScreen at %s width',
  (breakpoint) => {
    it('exposes the breakpoint and stacks or pairs the worklist and booking panels', () => {
      installMatchMedia(VIEWPORTS[breakpoint]);
      renderScreen();

      const root = screenRoot();
      expect(root.getAttribute('data-breakpoint')).toBe(breakpoint);
      expect(root.classList.contains('page')).toBe(true);
      expect(root.getAttribute('data-scheduling-view')).toBe(
        breakpoint === 'compact' ? 'stacked-panels' : 'side-by-side-panels',
      );

      const panels = root.querySelector('[data-part="panels"]');
      expect(panels).not.toBeNull();
      expect(panels?.classList.contains('grid-2')).toBe(true);
      expect(panels?.getAttribute('data-layout')).toBe(
        breakpoint === 'compact' ? 'stacked' : 'side-by-side',
      );
      expect(panels?.children).toHaveLength(2);
    });

    it('keeps every worklist column, booking field, label and role at this band', () => {
      installMatchMedia(VIEWPORTS[breakpoint]);
      renderScreen();

      expect(
        screen.getByRole('heading', { level: 2, name: schedulingText('en', 'scheduling') }),
      ).toBeTruthy();
      expect(screen.getByRole('status', { name: schedulingText('en', 'status') })).toBeTruthy();
      expect(
        screen.getByRole('group', { name: schedulingText('en', 'worklistRange') }),
      ).toBeTruthy();

      const table = screen.getByRole('table', { name: schedulingText('en', 'appointments') });
      for (const key of [
        'colTime',
        'colPatient',
        'colVisit',
        'colClinicianRoom',
        'colState',
        'colAssigned',
        'colActions',
      ] as const) {
        expect(
          within(table).getByRole('columnheader', { name: schedulingText('en', key) }),
        ).toBeTruthy();
      }
      expect(screen.getByRole('form', { name: schedulingText('en', 'bookingForm') })).toBeTruthy();
      for (const key of ['facilityCode', 'patientId', 'visitType', 'resource', 'start'] as const) {
        expect(screen.getByLabelText(schedulingText('en', key))).toBeTruthy();
      }
    });

    it('preserves DOM keyboard order and touch-sized, 16px-text inputs', () => {
      installMatchMedia(VIEWPORTS[breakpoint]);
      renderScreen();

      const root = screenRoot();
      const focusable = Array.from(
        root.querySelectorAll<HTMLElement>('button, input, select, [tabindex="0"]'),
      );
      expect(focusable.length).toBeGreaterThan(0);
      for (const element of focusable) {
        expect(element.tabIndex).toBeGreaterThanOrEqual(0);
      }

      // The range controls precede the booking fields in DOM order, so Tab
      // walks the worklist before the form at every width.
      const dayButton = screen.getByRole('button', { name: schedulingText('en', 'day') });
      const patientInput = screen.getByLabelText(schedulingText('en', 'patientId'));
      expect(
        dayButton.compareDocumentPosition(patientInput) & Node.DOCUMENT_POSITION_FOLLOWING,
      ).toBeTruthy();
      dayButton.focus();
      expect(document.activeElement).toBe(dayButton);

      // The shared FormField/Button own .field (16px text) and .touch (44px
      // coarse target). No inline size or font override may outrank them.
      for (const input of Array.from(root.querySelectorAll<HTMLElement>('input.field'))) {
        expect(input.classList.contains('touch')).toBe(true);
        expect(input.style.fontSize).toBe('');
        expect(input.style.minBlockSize).toBe('');
        expect(input.style.minInlineSize).toBe('');
      }
    });

    it('uses logical properties only, wraps long text and hides no clinical field', () => {
      installMatchMedia(VIEWPORTS[breakpoint]);
      renderScreen();

      const root = screenRoot();
      const styled = Array.from(root.querySelectorAll<HTMLElement>('[style]')).concat(root);
      for (const element of styled) {
        expect(element.style.direction).toBe('');
        expect(element.style.display).not.toBe('none');
        expect(element.getAttribute('style') ?? '').not.toMatch(physicalStylePattern);
      }
      expect(root.querySelector('.hide-compact')).toBeNull();
      expect(root.querySelector('.only-compact')).toBeNull();

      const notice = screen.getByText(schedulingText('en', 'syntheticNotice'));
      expect(notice.style.overflowWrap).toBe('anywhere');
      const status = screen.getByRole('status', { name: schedulingText('en', 'status') });
      expect(status.style.overflowWrap).toBe('anywhere');

      const panels = required(root.querySelector<HTMLElement>('[data-part="panels"]'));
      expect(panels.style.whiteSpace).not.toBe('nowrap');
      expect(panels.style.blockSize).toBe('');
    });
  },
);

describe('responsive SchedulingScreen viewport matrix', () => {
  const widths = [320, 360, 390, 768, 820, 1024, 1280] as const;

  it.each(widths)('resolves %ipx to the correct layout band', (width) => {
    installMatchMedia({
      medium: width >= BREAKPOINT_WIDTHS.medium,
      expanded: width >= BREAKPOINT_WIDTHS.expanded,
      coarse: width < BREAKPOINT_WIDTHS.expanded,
      portrait: width < BREAKPOINT_WIDTHS.medium,
    });
    renderScreen();

    const expected =
      width >= BREAKPOINT_WIDTHS.expanded
        ? 'expanded'
        : width >= BREAKPOINT_WIDTHS.medium
          ? 'medium'
          : 'compact';
    const root = screenRoot();
    expect(root.getAttribute('data-breakpoint')).toBe(expected);
    expect(root.getAttribute('data-scheduling-view')).toBe(
      expected === 'compact' ? 'stacked-panels' : 'side-by-side-panels',
    );
  });
});

describe('responsive SchedulingScreen right-to-left', () => {
  it('mirrors under RTL with Arabic copy and no local direction override', () => {
    installMatchMedia(VIEWPORTS.compact);
    renderScreen('ar');

    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    const root = screenRoot();
    expect(root.getAttribute('data-breakpoint')).toBe('compact');
    expect(root.getAttribute('data-scheduling-view')).toBe('stacked-panels');
    expect(
      screen.getByRole('heading', { level: 2, name: schedulingText('ar', 'scheduling') }),
    ).toBeTruthy();
    expect(screen.getByRole('button', { name: schedulingText('ar', 'week') })).toBeTruthy();
    expect(screen.getByLabelText(schedulingText('ar', 'patientId'))).toBeTruthy();

    for (const element of Array.from(root.querySelectorAll<HTMLElement>('[style]')).concat(root)) {
      expect(element.style.direction).toBe('');
      expect(element.style.display).not.toBe('none');
      expect(element.getAttribute('style') ?? '').not.toMatch(physicalStylePattern);
    }
    const notice = screen.getByText(schedulingText('ar', 'syntheticNotice'));
    expect(notice.style.overflowWrap).toBe('anywhere');
    const status = screen.getByRole('status', { name: schedulingText('ar', 'status') });
    expect(status.style.overflowWrap).toBe('anywhere');
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
