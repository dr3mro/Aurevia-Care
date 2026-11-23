import { act, render, screen, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { PatientOverview } from '../../../src/features/patients/domain';
import { PatientOverviewScreen } from '../../../src/features/patients/PatientOverviewScreen';
import { BREAKPOINT_MEDIA_QUERIES } from '../../../src/theme/breakpoints';
import type { Breakpoint } from '../../../src/theme/breakpoints';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';

type ChangeListener = (event: MediaQueryListEvent) => void;

interface QueryState {
  matches: boolean;
  listeners: Set<ChangeListener>;
}

/**
 * Deterministic `matchMedia` stand-in keyed by media query string. One state
 * object is reused per query so the hook's initializer and effect see the same
 * listeners. Every value below is synthetic; no real patient data is used.
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

/** Query states for the three documented layout bands. */
const LAYOUTS: Record<Breakpoint, Record<string, boolean>> = {
  compact: {
    [BREAKPOINT_MEDIA_QUERIES.medium]: false,
    [BREAKPOINT_MEDIA_QUERIES.expanded]: false,
  },
  medium: {
    [BREAKPOINT_MEDIA_QUERIES.medium]: true,
    [BREAKPOINT_MEDIA_QUERIES.expanded]: false,
  },
  expanded: {
    [BREAKPOINT_MEDIA_QUERIES.medium]: true,
    [BREAKPOINT_MEDIA_QUERIES.expanded]: true,
  },
};

const BREAKPOINTS: readonly Breakpoint[] = ['compact', 'medium', 'expanded'];

const LONG_ENGLISH_NAME =
  'A deliberately long synthetic patient name that must wrap on a narrow phone without clipping or overlap';
const LONG_ARABIC_NAME = 'اسم مريض تجريبي طويل جداً يجب أن يلتف على الشاشة الضيقة دون قص أو تداخل';
const LONG_ENGLISH_ISSUER = 'SYNTHETIC ISSUER WITH A DELIBERATELY LONG NAME THAT WRAPS';

/** Build a synthetic overview with optional duplicate warnings. */
function overview(displayName: string, language: string, withDuplicates: boolean): PatientOverview {
  return {
    patient: {
      organizationId: '00000000-0000-0000-0000-000000000001',
      id: 'patient-synthetic-1',
      version: 3,
      displayName,
      mrn: 'MRN-SYN-1001',
      mrnIssuer: LONG_ENGLISH_ISSUER,
      dob: '1990-05-12',
      dobUnknownReason: null,
      language,
    },
    duplicates: withDuplicates
      ? [{ id: 'patient-synthetic-2', displayName: LONG_ARABIC_NAME, mrn: 'MRN-SYN-1002' }]
      : [],
  };
}

function wrap(view: ReactNode) {
  return render(<ThemeProvider>{view}</ThemeProvider>);
}

/** Collect every rendered element so tests can forbid hidden clinical values. */
function allElements(root: HTMLElement): HTMLElement[] {
  return [root, ...Array.from(root.querySelectorAll('*'))] as HTMLElement[];
}

/** Assert no physical side, physical alignment or direction override is set. */
function expectLogicalOnly(elements: HTMLElement[]): void {
  for (const element of elements) {
    const style = element.getAttribute('style') ?? '';
    expect(style).not.toMatch(/(?:margin|padding|border|inset)-(?:left|right)\s*:/);
    expect(style).not.toMatch(/text-align\s*:\s*(?:left|right)/);
    expect(element.style.direction).toBe('');
    expect(element.style.display).not.toBe('none');
  }
}

afterEach(() => {
  vi.unstubAllGlobals();
  document.documentElement.removeAttribute('dir');
  document.documentElement.removeAttribute('lang');
});

describe('responsive PatientOverviewScreen (WEB-07, UX-01)', () => {
  it.each(BREAKPOINTS)(
    'uses the logical page wrapper and keeps every clinical field at %s',
    (breakpoint) => {
      installMatchMedia(LAYOUTS[breakpoint]);
      wrap(
        <PatientOverviewScreen overview={overview(LONG_ENGLISH_NAME, 'en', true)} locale="en" />,
      );

      const identity = screen.getByRole('region', { name: 'Identity' });
      const page = required(identity.closest<HTMLElement>('.page'));
      expect(page).not.toBeNull();
      expect(page.classList.contains('page')).toBe(true);
      expect(page.getAttribute('data-breakpoint')).toBe(breakpoint);

      // One DOM at every band: the identity fields stack on a phone through
      // .grid-2's single column and sit side by side from the medium breakpoint.
      const fields = required(identity.querySelector<HTMLElement>('[data-part="identity-fields"]'));
      expect(fields).not.toBeNull();
      expect(fields.classList.contains('grid-2')).toBe(true);
      for (const label of ['MRN', 'Issuer', 'Date of birth', 'Language', 'Version']) {
        expect(within(fields).getByText(new RegExp(`^${label}:`))).toBeTruthy();
      }

      // Long clinical values wrap; they are never hidden to make them fit.
      const name = screen.getByText(LONG_ENGLISH_NAME);
      expect(name.tagName).toBe('BDI');
      expect(name.getAttribute('dir')).toBe('auto');
      expect(name.getAttribute('data-breakpoint')).toBe(breakpoint);
      expect(name.style.whiteSpace).toBe('normal');
      expect(name.style.overflowWrap).toBe('anywhere');

      const elements = allElements(page);
      expectLogicalOnly(elements);
      for (const element of elements) {
        expect(element.classList.contains('hide-compact')).toBe(false);
        expect(element.classList.contains('only-compact')).toBe(false);
      }
    },
  );

  it('keeps the headings, warning and link with a single keyboard stop at every band', () => {
    for (const breakpoint of BREAKPOINTS) {
      installMatchMedia(LAYOUTS[breakpoint]);
      const view = wrap(
        <PatientOverviewScreen overview={overview(LONG_ENGLISH_NAME, 'en', true)} locale="en" />,
      );

      expect(screen.getByRole('heading', { level: 2, name: 'Patient overview' })).toBeTruthy();
      expect(screen.getByRole('heading', { level: 3, name: 'Identity' })).toBeTruthy();
      const alert = screen.getByRole('alert', { name: 'Likely duplicate records' });
      expect(within(alert).getByText(LONG_ARABIC_NAME)).toBeTruthy();
      expect(within(alert).getByText('MRN-SYN-1002')).toBeTruthy();

      const link = screen.getByRole<HTMLAnchorElement>('link', { name: 'Open chart' });
      expect(link.getAttribute('href')).toBe('/patients/patient-synthetic-1/chart');
      // The shared .touch class owns the 24px fine and 44px coarse target
      // floors; a wrapper span makes the anchor's clickable box the target.
      const target = required(link.querySelector<HTMLElement>('.touch'));
      expect(target).not.toBeNull();
      expect(target.textContent).toBe('Open chart');
      expect(target.style.display).toBe('inline-flex');

      const focusable = view.container.querySelectorAll(
        'a[href], button, input, select, textarea, [tabindex]',
      );
      expect(focusable).toHaveLength(1);
      expect(focusable[0]).toBe(link);
      expect(link.getAttribute('tabindex')).toBeNull();

      view.unmount();
      vi.unstubAllGlobals();
    }
  });

  it('keeps the no-duplicates message and the same fields at every band', () => {
    for (const breakpoint of BREAKPOINTS) {
      installMatchMedia(LAYOUTS[breakpoint]);
      const view = wrap(
        <PatientOverviewScreen overview={overview(LONG_ENGLISH_NAME, 'en', false)} locale="en" />,
      );

      expect(screen.getByText('No likely duplicate records.')).toBeTruthy();
      expect(screen.queryByRole('alert')).toBeNull();
      const identity = screen.getByRole('region', { name: 'Identity' });
      expect(identity.querySelector('.grid-2')).not.toBeNull();
      expect(screen.getByRole('link', { name: 'Open chart' })).toBeTruthy();
      view.unmount();
      vi.unstubAllGlobals();
    }
  });

  it('follows a viewport change without remounting or hiding clinical values', () => {
    const media = installMatchMedia(LAYOUTS.compact);
    wrap(<PatientOverviewScreen overview={overview(LONG_ENGLISH_NAME, 'en', true)} locale="en" />);

    const page = required(
      screen.getByRole('region', { name: 'Identity' }).closest<HTMLElement>('.page'),
    );
    const name = screen.getByText(LONG_ENGLISH_NAME);
    expect(page.getAttribute('data-breakpoint')).toBe('compact');

    act(() => media.fire(BREAKPOINT_MEDIA_QUERIES.medium, true));
    expect(page.getAttribute('data-breakpoint')).toBe('medium');
    expect(name.getAttribute('data-breakpoint')).toBe('medium');

    act(() => media.fire(BREAKPOINT_MEDIA_QUERIES.expanded, true));
    expect(page.getAttribute('data-breakpoint')).toBe('expanded');
    expect(name.textContent).toBe(LONG_ENGLISH_NAME);
    expect(name.style.overflowWrap).toBe('anywhere');
    expect(screen.getByRole('alert', { name: 'Likely duplicate records' })).toBeTruthy();
  });

  it('mirrors the layout and keeps Arabic runs wrapped under right-to-left', () => {
    document.documentElement.setAttribute('dir', 'rtl');
    document.documentElement.setAttribute('lang', 'ar');
    installMatchMedia(LAYOUTS.compact);
    wrap(<PatientOverviewScreen overview={overview(LONG_ARABIC_NAME, 'ar', true)} locale="ar" />);

    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByRole('heading', { level: 2, name: 'نظرة عامة على المريض' })).toBeTruthy();
    const identity = screen.getByRole('region', { name: 'الهوية' });
    const page = required(identity.closest<HTMLElement>('.page'));
    expect(page.getAttribute('data-breakpoint')).toBe('compact');
    expect(
      identity.querySelector('[data-part="identity-fields"]')?.classList.contains('grid-2'),
    ).toBe(true);

    // The same synthetic Arabic value is shown as the patient name and as a
    // duplicate candidate; either isolated run proves the wrapping contract.
    const arabicName = required(screen.getAllByText(LONG_ARABIC_NAME)[0]);
    expect(arabicName.tagName).toBe('BDI');
    expect(arabicName.getAttribute('dir')).toBe('auto');
    expect(arabicName.getAttribute('lang')).toBe('ar');
    expect(arabicName.style.whiteSpace).toBe('normal');
    expect(arabicName.style.overflowWrap).toBe('anywhere');

    const link = screen.getByRole<HTMLAnchorElement>('link', { name: 'فتح الملف الطبي' });
    expect(link.getAttribute('href')).toBe('/patients/patient-synthetic-1/chart');
    expect(link.querySelector('.touch')).not.toBeNull();

    expectLogicalOnly(allElements(page));
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
