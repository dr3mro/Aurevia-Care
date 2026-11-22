import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { EnteredInErrorBanner } from '../../../src/features/chart/EnteredInErrorBanner';
import type { EnteredInError } from '../../../src/features/chart/domain';
import { chartText } from '../../../src/features/chart/messages';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import type { Locale } from '../../../src/i18n/messages';
import { BREAKPOINT_MEDIA_QUERIES } from '../../../src/theme/breakpoints';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';

type Layout = 'compact' | 'medium' | 'expanded';

/**
 * Synthetic viewport answers for the three layout bands. jsdom has no real
 * media queries, so the responsive hook is driven through a deterministic
 * `matchMedia` stand-in; no pixel measurement is claimed (design guide §13).
 */
const VIEWPORTS: Record<Layout, Record<string, boolean>> = {
  compact: {
    [BREAKPOINT_MEDIA_QUERIES.medium]: false,
    [BREAKPOINT_MEDIA_QUERIES.expanded]: false,
    '(pointer: coarse)': true,
  },
  medium: {
    [BREAKPOINT_MEDIA_QUERIES.medium]: true,
    [BREAKPOINT_MEDIA_QUERIES.expanded]: false,
    '(pointer: coarse)': true,
  },
  expanded: {
    [BREAKPOINT_MEDIA_QUERIES.medium]: true,
    [BREAKPOINT_MEDIA_QUERIES.expanded]: true,
    '(pointer: coarse)': false,
  },
};

/** Install a query-keyed `matchMedia` stand-in for one synthetic viewport. */
function installMatchMedia(answers: Record<string, boolean>): void {
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({
      matches: answers[query] ?? false,
      media: query,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
    })),
  );
}

/** Long, obviously synthetic values that exercise wrapping at 320 px and RTL. */
const ENGLISH_DETAIL: EnteredInError = {
  actor: 'synthetic.clinician.with.a.long.name.for.wrapping',
  reason:
    'A deliberately long synthetic entered-in-error reason that has to wrap on a 320 px phone and at 200 percent zoom without clipping, truncation or overlap.',
  affectedRecipients: [
    'synthetic.referrer.one.long.name.for.wrapping',
    'synthetic.referrer.two.long.name.for.wrapping',
  ],
  recordedAt: '2026-03-09T13:00:00Z',
};

/** Synthetic Arabic free text of the same shape as the English detail. */
const ARABIC_DETAIL: EnteredInError = {
  actor: 'مستخدم سريري تجريبي اسمه الطويل للالتفاف',
  reason:
    'سبب تجريبي طويل جداً لتسجيل خطأ الإدخال يجب أن يلتف على هاتف بعرض 320 بكسل وعند تكبير النص إلى 200 بالمئة دون قص أو اقتطاع أو تداخل.',
  affectedRecipients: ['جهة إحالة تجريبية أولى', 'جهة إحالة تجريبية ثانية'],
  recordedAt: '2026-03-09T13:00:00Z',
};

/** Render the banner inside the real providers at a given locale and band. */
function renderBanner(locale: Locale, layout: Layout, detail: EnteredInError = ENGLISH_DETAIL) {
  installMatchMedia(VIEWPORTS[layout]);
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <EnteredInErrorBanner detail={detail} locale={locale} />
      </I18nProvider>
    </ThemeProvider>,
  );
}

/** The four clinical fields every band must expose, in DOM and reading order. */
function expectedTerms(locale: Locale): string[] {
  return [
    chartText(locale, 'signer'),
    chartText(locale, 'voidReason'),
    chartText(locale, 'affectedRecipients'),
    chartText(locale, 'recordedAt'),
  ];
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('responsive EnteredInErrorBanner (WEB-07, UX-01)', () => {
  describe.each(['compact', 'medium', 'expanded'] as const)('at %s width', (layout) => {
    it('keeps the alert role, every clinical field and the responsive layout classes', () => {
      const { unmount } = renderBanner('en', layout);

      const alert = screen.getByRole('alert');
      expect(alert.getAttribute('data-testid')).toBe('entered-in-error');
      expect(alert.getAttribute('data-breakpoint')).toBe(layout);
      // `page` carries the safe-area-aware logical padding; `grid-2` stacks
      // the fields on a phone and places them side by side from 768 px up.
      expect(alert.classList.contains('page')).toBe(true);
      const list = alert.querySelector('dl');
      expect(list?.classList.contains('grid-2')).toBe(true);

      expect(screen.getByText(chartText('en', 'enteredInError')).tagName).toBe('STRONG');
      expect(screen.getByText(chartText('en', 'enteredInErrorDetail')).tagName).toBe('P');
      expect(screen.getAllByRole('term').map((term) => term.textContent)).toEqual(
        expectedTerms('en'),
      );

      const definitions = screen.getAllByRole('definition');
      expect(definitions).toHaveLength(4);
      expect(definitions[0]?.textContent).toBe(ENGLISH_DETAIL.actor);
      expect(definitions[1]?.textContent).toBe(ENGLISH_DETAIL.reason);
      expect(definitions[2]?.textContent).toBe(ENGLISH_DETAIL.affectedRecipients.join(', '));
      const time = alert.querySelector('time');
      expect(time?.getAttribute('datetime')).toBe(ENGLISH_DETAIL.recordedAt);
      expect(definitions[3]?.textContent).toBe(time?.textContent);

      // No clinical field is hidden, inline or by a stylesheet hide utility
      // (jsdom does not load responsive.css, so assert the classes directly).
      for (const element of [alert, ...Array.from(alert.querySelectorAll('*'))]) {
        expect((element as HTMLElement).style.display).not.toBe('none');
      }
      expect(alert.querySelector('.hide-compact')).toBeNull();
      expect(alert.querySelector('.only-compact')).toBeNull();
      expect(alert.textContent).toContain(ENGLISH_DETAIL.reason);
      expect(alert.textContent).toContain(ENGLISH_DETAIL.actor);
      unmount();
    });

    it('wraps long values with logical styles and no fixed block size', () => {
      renderBanner('en', layout);
      const alert = screen.getByRole('alert');
      const style = alert.getAttribute('style') ?? '';

      expect(alert.style.overflowWrap).toBe('anywhere');
      expect(alert.style.whiteSpace).toBe('normal');
      expect(alert.style.textAlign).toBe('start');
      // Assert the serialized declaration, not a cssstyle-normalized value.
      expect(style).toMatch(/min-inline-size:\s*0(?:px)?/);
      // Safe-area padding comes from the `page` class, never a physical side.
      expect(alert.style.paddingLeft).toBe('');
      expect(alert.style.paddingRight).toBe('');
      expect(alert.style.paddingTop).toBe('');
      expect(alert.style.paddingBottom).toBe('');
      expect(style).not.toMatch(/(?:padding|margin|border|inset)-(?:left|right)\s*:/);
      expect(style).not.toContain('display: none');
      expect(style).not.toMatch(/height:\s*\d+px/);
    });

    it('adds no tab stop, input or action, so keyboard order is unchanged', () => {
      const { container, unmount } = renderBanner('en', layout);
      expect(
        container.querySelectorAll('a[href], button, input, select, textarea, [tabindex]'),
      ).toHaveLength(0);
      const order = Array.from(container.querySelectorAll('dl > *')).map((field) => field.tagName);
      expect(order).toEqual(['DT', 'DD', 'DT', 'DD', 'DT', 'DD', 'DT', 'DD']);
      unmount();
    });

    it('mirrors Arabic copy under right-to-left without physical properties', () => {
      const { unmount } = renderBanner('ar', layout, ARABIC_DETAIL);
      expect(document.documentElement.dir).toBe('rtl');
      expect(document.documentElement.lang).toBe('ar');

      const alert = screen.getByRole('alert');
      expect(alert.getAttribute('data-breakpoint')).toBe(layout);
      expect(screen.getByText(chartText('ar', 'enteredInError'))).toBeTruthy();
      expect(screen.getAllByRole('term').map((term) => term.textContent)).toEqual(
        expectedTerms('ar'),
      );
      expect(screen.getByText(ARABIC_DETAIL.reason).textContent).toBe(ARABIC_DETAIL.reason);
      expect(screen.getByText(ARABIC_DETAIL.affectedRecipients.join(', '))).toBeTruthy();
      expect(alert.querySelector('time')?.getAttribute('datetime')).toBe(ARABIC_DETAIL.recordedAt);

      // Direction and mirroring come from the document and logical properties;
      // the component never sets a direction override or a physical side.
      expect(alert.style.direction).toBe('');
      expect(alert.style.textAlign).toBe('start');
      const style = alert.getAttribute('style') ?? '';
      expect(style).not.toMatch(/text-align\s*:\s*(?:left|right)/);
      expect(style).not.toMatch(/(?:padding|margin|border|inset)-(?:left|right)\s*:/);
      expect(style).not.toContain('display: none');
      unmount();
    });
  });
});
