import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { DesignPreview } from '../../../src/app/DesignPreview';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';
import { BREAKPOINT_MEDIA_QUERIES, BREAKPOINT_WIDTHS } from '../../../src/theme/breakpoints';

type ChangeListener = (event: MediaQueryListEvent) => void;
type Band = 'compact' | 'medium' | 'expanded';
type Locale = 'en' | 'ar';

/** Synthetic viewport answers mirroring the responsive Playwright matrix. */
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

/**
 * Assert that authored inline styles are direction-neutral. Symmetric CSS
 * shorthands (`padding: 4px`, `border: 1px solid …`) expand to physical keys in
 * jsdom but mirror safely, so the check reads the authored declaration text
 * instead of the expanded style object.
 */
function expectLogicalInlineStyles(element: HTMLElement): void {
  const style = element.getAttribute('style') ?? '';
  expect(style).not.toMatch(/(?:margin|padding|border|inset)-(?:left|right|top|bottom)\s*:/);
  expect(style).not.toMatch(/(?:^|;)\s*(?:left|right|top|bottom)\s*:/);
  expect(style).not.toMatch(/text-align\s*:\s*(?:left|right)/);
  expect(style).not.toMatch(/(?:^|;)\s*direction\s*:/);
  expect(element.style.direction).toBe('');
}

/** English and Arabic labels the preview must keep at every width. */
const LABELS = {
  en: {
    heading: 'Clinical label: ليلى Haddad · MAIN',
    facilities: 'Facilities',
    code: 'Code',
    name: 'Name',
    showDetails: 'Show details',
    facilityDetails: 'Facility details',
    closeDialog: 'Close dialog',
    noteForm: 'Shift note form',
    shiftNote: 'Shift note',
    saveNote: 'Save note',
    echo: 'Echo',
    signIn: 'Sign in',
    protectedArea: 'Protected area',
  },
  ar: {
    heading: 'تسمية سريرية: ليلى Haddad · MAIN',
    facilities: 'المرافق',
    code: 'الرمز',
    name: 'الاسم',
    showDetails: 'عرض التفاصيل',
    facilityDetails: 'تفاصيل المرفق',
    closeDialog: 'إغلاق النافذة',
    noteForm: 'نموذج ملاحظة الوردية',
    shiftNote: 'ملاحظة الوردية',
    saveNote: 'حفظ الملاحظة',
    echo: 'صدى',
    signIn: 'تسجيل الدخول',
    protectedArea: 'منطقة محمية',
  },
} as const;

/**
 * jsdom cannot evaluate media queries. Stand in for `matchMedia` so the real
 * breakpoint, pointer and orientation hooks resolve for the chosen viewport.
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

function renderPreview(locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <DesignPreview />
      </I18nProvider>
    </ThemeProvider>,
  );
}

/** The outermost preview wrapper is the first `.page` div in the render tree. */
function previewRoot(): HTMLElement {
  const root = document.querySelector<HTMLElement>('div.page[data-breakpoint]');
  if (!root) {
    throw new Error('the design preview root was not rendered');
  }
  return root;
}

function allInlineStyledElements(root: HTMLElement): HTMLElement[] {
  return [root, ...Array.from(root.querySelectorAll<HTMLElement>('[style]'))];
}

afterEach(() => {
  vi.unstubAllGlobals();
  document.documentElement.removeAttribute('dir');
  document.documentElement.removeAttribute('lang');
});

describe.each(['compact', 'medium', 'expanded'] as const)(
  'responsive DesignPreview at %s width (WEB-07, UX-01)',
  (band) => {
    it('renders the page with responsive classes and exposes the active band', () => {
      installMatchMedia(VIEWPORTS[band]);
      renderPreview();

      const root = previewRoot();
      // `page` supplies logical safe-area padding, a single wrapping column and
      // the maximum reading width; no physical page-level scroll is introduced.
      expect(root.classList.contains('page')).toBe(true);
      expect(root.getAttribute('data-breakpoint')).toBe(band);
      expect(root.style.display).not.toBe('none');
      expect(screen.getByRole('heading', { name: LABELS.en.heading })).toBeTruthy();
      expect(screen.getByRole('button', { name: LABELS.en.showDetails })).toBeTruthy();
      expect(screen.getByRole('form', { name: LABELS.en.noteForm })).toBeTruthy();
      expect(screen.getByRole('status', { name: LABELS.en.echo })).toBeTruthy();
    });

    it('shows every component with the table, fields and dialog for the band', () => {
      installMatchMedia(VIEWPORTS[band]);
      renderPreview();

      // DataTable: a labelled keyboard-scrollable region on compact, a plain
      // table from medium upward. No clinical column is ever hidden.
      if (band === 'compact') {
        const region = screen.getByRole('region', { name: LABELS.en.facilities }) as HTMLElement;
        expect(region.className).toContain('table-region');
        expect(region.tabIndex).toBe(0);
      } else {
        expect(screen.queryByRole('region', { name: LABELS.en.facilities })).toBeNull();
      }
      const table = screen.getByRole('table', { name: LABELS.en.facilities });
      expect(table.querySelectorAll('th[scope="col"]')).toHaveLength(2);
      expect(table.textContent).toContain(LABELS.en.code);
      expect(table.textContent).toContain(LABELS.en.name);
      expect(table.textContent).toContain('MAIN');
      expect(table.textContent).toContain('Synthetic Main Facility');

      // Form and FormField keep their labels, 16px `.field` input, 44px `.touch`
      // target and the single-column stack (the preview carries one field).
      const form = screen.getByRole('form', { name: LABELS.en.noteForm });
      expect(form.getAttribute('data-breakpoint')).toBe(band);
      expect(form.querySelector('[data-part="fields"]')?.className).toBe('stack');
      const input = screen.getByLabelText(LABELS.en.shiftNote) as HTMLInputElement;
      expect(input.tagName).toBe('INPUT');
      expect(input.classList.contains('field')).toBe(true);
      expect(input.classList.contains('touch')).toBe(true);
      expect(input.closest('[data-breakpoint]')?.getAttribute('data-breakpoint')).toBe(band);
      expect(input.closest('[data-coarse-pointer]')?.getAttribute('data-coarse-pointer')).toBe(
        band === 'expanded' ? 'false' : 'true',
      );
      expect(screen.getByRole('button', { name: LABELS.en.saveNote })).toBeTruthy();

      // Mixed-script clinical values stay isolated and wrap at every width.
      const runs = Array.from(document.querySelectorAll('bdi'));
      // Two clinical labels and the measurement; the dialog run is closed.
      expect(runs).toHaveLength(3);
      for (const run of runs) {
        expect(run.getAttribute('dir')).toBe('auto');
        expect(run.getAttribute('lang')).toBe('en');
        expect(run.getAttribute('data-breakpoint')).toBe(band);
        expect((run as HTMLElement).style.overflowWrap).toBe('anywhere');
        expect((run as HTMLElement).style.whiteSpace).toBe('normal');
      }
    });

    it('keeps the labels, roles and DOM keyboard order stable', () => {
      installMatchMedia(VIEWPORTS[band]);
      renderPreview();

      expect(screen.getByRole('heading', { name: LABELS.en.signIn })).toBeTruthy();
      expect(screen.getByRole('region', { name: LABELS.en.protectedArea })).toBeTruthy();
      expect(screen.getByRole('status', { name: LABELS.en.echo }).getAttribute('aria-label')).toBe(
        LABELS.en.echo,
      );

      const root = previewRoot();
      const focusables = Array.from(
        root.querySelectorAll<HTMLElement>(
          'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex="0"]',
        ),
      );
      const names = focusables.map(
        (element) => element.getAttribute('aria-label') ?? element.textContent,
      );
      const expected = [
        ...(band === 'compact' ? [LABELS.en.facilities] : []),
        LABELS.en.showDetails,
        '',
        LABELS.en.saveNote,
      ];
      expect(names).toEqual(expected);

      const showDetails = screen.getByRole('button', { name: LABELS.en.showDetails });
      showDetails.focus();
      expect(document.activeElement).toBe(showDetails);
    });

    it('opens the dialog with the band-appropriate view and restores focus', () => {
      installMatchMedia(VIEWPORTS[band]);
      renderPreview();

      const opener = screen.getByRole('button', { name: LABELS.en.showDetails });
      // Keyboard activation focuses the opener; fireEvent.click alone does not,
      // and the dialog restores focus to whatever was active when it opened.
      opener.focus();
      fireEvent.click(opener);
      const dialog = screen.getByRole('dialog', { name: LABELS.en.facilityDetails });
      expect(dialog.getAttribute('data-breakpoint')).toBe(band);
      expect(dialog.className).toContain('sheet');
      expect(dialog.getAttribute('data-dialog-view')).toBe(
        band === 'compact' ? 'sheet' : 'centered',
      );
      const header = dialog.querySelector('[data-dialog-region="header"]') as HTMLElement;
      expect(header.style.position).toBe(band === 'compact' ? 'sticky' : '');

      fireEvent.click(screen.getByRole('button', { name: LABELS.en.closeDialog }));
      expect(screen.queryByRole('dialog')).toBeNull();
      expect(document.activeElement).toBe(opener);
    });

    it('wraps long text, uses logical properties and hides no clinical content', () => {
      installMatchMedia(VIEWPORTS[band]);
      renderPreview();

      const root = previewRoot();
      for (const element of allInlineStyledElements(root)) {
        expect(element.style.display).not.toBe('none');
        expect(element.style.whiteSpace).not.toBe('nowrap');
        expectLogicalInlineStyles(element);
      }
      expect(root.querySelector('.hide-compact')).toBeNull();
      expect(root.querySelector('.only-compact')).toBeNull();

      for (const cell of Array.from(document.querySelectorAll('td, th'))) {
        expect((cell as HTMLElement).style.overflowWrap).toBe('anywhere');
      }
      const showDetails = screen.getByRole('button', { name: LABELS.en.showDetails });
      expect(showDetails.style.whiteSpace).toBe('normal');
      expect(showDetails.style.overflowWrap).toBe('anywhere');
    });
  },
);

describe('responsive DesignPreview viewport matrix', () => {
  const widths = [320, 360, 390, 768, 820, 1024, 1280] as const;

  it.each(widths)('resolves %ipx to the correct layout band', (width) => {
    installMatchMedia({
      medium: width >= BREAKPOINT_WIDTHS.medium,
      expanded: width >= BREAKPOINT_WIDTHS.expanded,
      coarse: width < BREAKPOINT_WIDTHS.expanded,
      portrait: width < BREAKPOINT_WIDTHS.medium,
    });
    renderPreview();

    const expected: Band =
      width >= BREAKPOINT_WIDTHS.expanded
        ? 'expanded'
        : width >= BREAKPOINT_WIDTHS.medium
          ? 'medium'
          : 'compact';
    expect(previewRoot().getAttribute('data-breakpoint')).toBe(expected);
  });
});

describe.each(['compact', 'medium', 'expanded'] as const)(
  'responsive DesignPreview right-to-left at %s width',
  (band) => {
    it('mirrors the whole preview with Arabic labels and no direction override', () => {
      installMatchMedia(VIEWPORTS[band]);
      renderPreview('ar');

      expect(document.documentElement.dir).toBe('rtl');
      expect(document.documentElement.lang).toBe('ar');
      const root = previewRoot();
      expect(root.getAttribute('data-breakpoint')).toBe(band);
      expect(root.classList.contains('page')).toBe(true);

      expect(screen.getByRole('heading', { name: LABELS.ar.heading })).toBeTruthy();
      expect(screen.getByRole('table', { name: LABELS.ar.facilities }).textContent).toContain(
        LABELS.ar.code,
      );
      expect(screen.getByRole('button', { name: LABELS.ar.showDetails })).toBeTruthy();
      expect(screen.getByRole('form', { name: LABELS.ar.noteForm })).toBeTruthy();
      expect(screen.getByLabelText(LABELS.ar.shiftNote)).toBeTruthy();
      expect(screen.getByRole('button', { name: LABELS.ar.saveNote })).toBeTruthy();
      expect(screen.getByRole('status', { name: LABELS.ar.echo })).toBeTruthy();
      expect(screen.getByRole('heading', { name: LABELS.ar.signIn })).toBeTruthy();
      expect(screen.getByRole('region', { name: LABELS.ar.protectedArea })).toBeTruthy();

      for (const run of Array.from(document.querySelectorAll('bdi'))) {
        expect(run.getAttribute('lang')).toBe('ar');
        expect(run.getAttribute('dir')).toBe('auto');
        expect(run.style.overflowWrap).toBe('anywhere');
      }
      // The Latin digits and unit of the measurement stay an isolated LTR run
      // inside the RTL document instead of inheriting the RTL base direction.
      const measurement = screen.getByText('12.5 cm');
      expect(measurement.tagName).toBe('BDI');
      expect(measurement.getAttribute('dir')).toBe('auto');
      for (const element of allInlineStyledElements(root)) {
        expect(element.style.display).not.toBe('none');
        expectLogicalInlineStyles(element);
      }
    });
  },
);
