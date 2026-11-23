import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { I18nProvider } from '../../src/i18n/I18nProvider';
import { ThemeProvider } from '../../src/theme/ThemeProvider';
import { BREAKPOINT_MEDIA_QUERIES, BREAKPOINT_WIDTHS } from '../../src/theme/breakpoints';
import { OrgSwitcher } from '../../src/app/OrgSwitcher';
import type { SyntheticOrganization } from '../../src/fixtures/synthetic';

type ChangeListener = (event: MediaQueryListEvent) => void;

interface ViewportState {
  medium: boolean;
  expanded: boolean;
  coarse: boolean;
  portrait: boolean;
}

/** Synthetic viewport states mirroring the responsive Playwright matrix. */
const VIEWPORTS: Record<'compact' | 'medium' | 'expanded', ViewportState> = {
  compact: { medium: false, expanded: false, coarse: true, portrait: true },
  medium: { medium: true, expanded: false, coarse: true, portrait: false },
  expanded: { medium: true, expanded: true, coarse: false, portrait: false },
};

/** Long synthetic labels prove text wraps instead of forcing horizontal scroll. */
const longEnglish: readonly SyntheticOrganization[] = [
  { id: 'syn-en-1', name: 'Synthetic North West Regional Diagnostic And Treatment Clinic' },
  { id: 'syn-en-2', name: 'Synthetic Very Long Beta Laboratory And Imaging Services Center' },
];

const arabicOrganizations: readonly SyntheticOrganization[] = [
  { id: 'syn-ar-1', name: 'العيادة التجريبية الطويلة الاسم للاختبار والتحقق' },
  { id: 'syn-ar-2', name: 'مختبر تجريبي ثانٍ للفحص والتصوير الطبي' },
];

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

function renderSwitcher(organizations: readonly SyntheticOrganization[] = longEnglish) {
  return render(
    <ThemeProvider>
      <I18nProvider>
        <OrgSwitcher organizations={organizations} />
      </I18nProvider>
    </ThemeProvider>,
  );
}

/** Render in Arabic so I18nProvider sets `dir="rtl"` and `lang="ar"`. */
function renderRtlSwitcher(organizations: readonly SyntheticOrganization[]) {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale="ar">
        <OrgSwitcher organizations={organizations} />
      </I18nProvider>
    </ThemeProvider>,
  );
}

function switcherRoot(): HTMLElement {
  const root = document.querySelector('[data-breakpoint]');
  if (!root) {
    throw new Error('responsive switcher root with data-breakpoint was not rendered');
  }
  return root as HTMLElement;
}

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

afterEach(() => {
  vi.unstubAllGlobals();
  document.documentElement.removeAttribute('dir');
  document.documentElement.removeAttribute('lang');
});

describe.each(['compact', 'medium', 'expanded'] as const)(
  'responsive OrgSwitcher at %s width',
  (breakpoint) => {
    it('resolves the breakpoint and renders the stacked or inline field structure', () => {
      installMatchMedia(VIEWPORTS[breakpoint]);
      renderSwitcher();

      const root = switcherRoot();
      expect(root.getAttribute('data-breakpoint')).toBe(breakpoint);
      expect(root.classList.contains('page')).toBe(true);
      expect(root.getAttribute('data-org-switcher-view')).toBe(
        breakpoint === 'compact' ? 'full-width-select' : 'inline-fields',
      );
      const fields = root.querySelector('[data-org-switcher-fields]');
      expect(fields).not.toBeNull();
      expect(fields?.classList.contains('grid-2')).toBe(true);
    });

    it('keeps the labelled, full-width, touch-sized native select and confirmation', () => {
      installMatchMedia(VIEWPORTS[breakpoint]);
      renderSwitcher();

      const select = screen.getByLabelText<HTMLSelectElement>('Organization');
      expect(select.tagName).toBe('SELECT');
      expect(select.id).toBe('active-organization');
      expect(select.classList.contains('field')).toBe(true);
      expect(select.classList.contains('touch')).toBe(true);
      expect(select.hasAttribute('hidden')).toBe(false);
      // No inline size may outrank the .touch 44px coarse-pointer floor.
      expect(select.style.minBlockSize).toBe('');
      expect(select.style.minInlineSize).toBe('');
      expect(screen.getAllByRole('option')).toHaveLength(longEnglish.length);

      const status = screen.getByRole('status');
      expect(status.tagName).toBe('OUTPUT');
      expect(status.getAttribute('aria-live')).toBe('polite');
      expect(status.textContent).toBe(longEnglish[0]?.name);
    });

    it('keeps DOM keyboard order and the explicit switch confirmation on change', () => {
      installMatchMedia(VIEWPORTS[breakpoint]);
      renderSwitcher();

      const root = switcherRoot();
      const label = root.querySelector('label');
      const select = root.querySelector<HTMLSelectElement>('select');
      const output = root.querySelector('output');
      if (!select) {
        throw new Error('native select was not rendered');
      }
      expect(Array.from(root.querySelectorAll('label, select, output'))).toEqual([
        label,
        select,
        output,
      ]);
      expect(select.tabIndex).toBe(0);
      select.focus();
      expect(document.activeElement).toBe(select);

      const second = longEnglish[1];
      if (!second) {
        throw new Error('fixture needs a second organization');
      }
      fireEvent.change(select, { target: { value: second.id } });
      expect(select.value).toBe(second.id);
      expect(output?.textContent).toBe(second.name);
    });

    it('uses logical properties only and hides no field with display:none', () => {
      installMatchMedia(VIEWPORTS[breakpoint]);
      renderSwitcher();

      const root = switcherRoot();
      const styled = Array.from(root.querySelectorAll<HTMLElement>('[style]')).concat(root);
      for (const element of styled) {
        expect(element.style.direction).toBe('');
        expect(element.style.display).not.toBe('none');
        for (const key of physicalStyleKeys) {
          expect(element.style.getPropertyValue(key)).toBe('');
        }
      }
      expect(root.querySelector('.hide-compact')).toBeNull();
      expect(root.querySelector('.only-compact')).toBeNull();
      expect(screen.getByRole('status').style.overflowWrap).toBe('anywhere');
      const select = screen.getByLabelText('Organization');
      expect(select.getAttribute('style') ?? '').not.toMatch(
        /(?:padding|margin)-(?:left|right|top|bottom)\s*:|(?:^|;)\s*(?:left|right|top|bottom)\s*:/,
      );
    });

    it('keeps long English labels readable without nowrap or a fixed block size', () => {
      installMatchMedia(VIEWPORTS[breakpoint]);
      renderSwitcher();

      const select = screen.getByLabelText('Organization');
      expect(select.textContent).toBe(
        longEnglish.map((organization) => organization.name).join(''),
      );
      expect(select.style.whiteSpace).not.toBe('nowrap');
      expect(select.style.display).not.toBe('none');
      expect(screen.getByRole('status').textContent).toBe(longEnglish[0]?.name);
    });
  },
);

describe('responsive OrgSwitcher viewport matrix', () => {
  const widths = [320, 360, 390, 768, 820, 1024, 1280] as const;

  it.each(widths)('resolves %ipx to the correct layout band', (width) => {
    installMatchMedia({
      medium: width >= BREAKPOINT_WIDTHS.medium,
      expanded: width >= BREAKPOINT_WIDTHS.expanded,
      coarse: width < BREAKPOINT_WIDTHS.expanded,
      portrait: width < BREAKPOINT_WIDTHS.medium,
    });
    renderSwitcher();

    const expected =
      width >= BREAKPOINT_WIDTHS.expanded
        ? 'expanded'
        : width >= BREAKPOINT_WIDTHS.medium
          ? 'medium'
          : 'compact';
    const root = switcherRoot();
    expect(root.getAttribute('data-breakpoint')).toBe(expected);
    expect(root.getAttribute('data-org-switcher-view')).toBe(
      expected === 'compact' ? 'full-width-select' : 'inline-fields',
    );
  });
});

describe('responsive OrgSwitcher right-to-left and compact sheet', () => {
  it('mirrors under RTL with a long Arabic name and no local direction override', () => {
    installMatchMedia(VIEWPORTS.compact);
    renderRtlSwitcher(arabicOrganizations);

    const root = switcherRoot();
    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    expect(root.getAttribute('data-breakpoint')).toBe('compact');
    expect(root.getAttribute('data-org-switcher-view')).toBe('full-width-select');

    const select = screen.getByLabelText('المؤسسة');
    expect(select.getAttribute('dir')).toBeNull();
    expect(select.style.direction).toBe('');
    expect(select.classList.contains('field')).toBe(true);
    expect(select.classList.contains('touch')).toBe(true);
    for (const element of Array.from(root.querySelectorAll<HTMLElement>('[style]')).concat(root)) {
      expect(element.style.direction).toBe('');
      expect(element.style.display).not.toBe('none');
      for (const key of physicalStyleKeys) {
        expect(element.style.getPropertyValue(key)).toBe('');
      }
    }
    const status = screen.getByRole('status');
    expect(status.textContent).toBe(arabicOrganizations[0]?.name);
    expect(status.style.overflowWrap).toBe('anywhere');
    expect(screen.getAllByRole('option').map((option) => option.textContent)).toEqual(
      arabicOrganizations.map((organization) => organization.name),
    );
  });

  it('uses the page class for safe-area padding on compact without physical inline insets', () => {
    installMatchMedia(VIEWPORTS.compact);
    renderSwitcher();

    const root = switcherRoot();
    // The `page` class maps the platform safe-area env() values once using
    // logical padding; jsdom cannot paint, so assert the class contract only.
    expect(root.classList.contains('page')).toBe(true);
    expect(root.style.paddingLeft).toBe('');
    expect(root.style.paddingRight).toBe('');
    expect(root.style.paddingBlockStart).toBe('');
  });
});
