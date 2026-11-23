import { render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ReactNode } from 'react';
import { ApiPage } from '../../src/components/ApiPage';
import { SessionContext } from '../../src/components/SessionContext';
import { I18nProvider } from '../../src/i18n/I18nProvider';
import type { Locale } from '../../src/i18n/messages';
import { BREAKPOINT_MEDIA_QUERIES } from '../../src/theme/breakpoints';
import type { Breakpoint } from '../../src/theme/breakpoints';
import { ThemeProvider } from '../../src/theme/ThemeProvider';

const BREAKPOINTS: readonly Breakpoint[] = ['compact', 'medium', 'expanded'];

/**
 * Install a deterministic `matchMedia` stand-in for one layout class. The hook
 * only reads these two `min-width` queries, so an absent query never matches.
 */
function installMatchMedia(breakpoint: Breakpoint): void {
  const matches: Record<string, boolean> = {
    [BREAKPOINT_MEDIA_QUERIES.medium]: breakpoint !== 'compact',
    [BREAKPOINT_MEDIA_QUERIES.expanded]: breakpoint === 'expanded',
  };
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({
      matches: matches[query] ?? false,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
    })),
  );
}

function resource(label: string, value: readonly string[]) {
  return { label, load: () => Promise.resolve(value), deps: [] };
}

function wrap(view: ReactNode, options: { locale?: Locale; sessionExpired?: () => void } = {}) {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={options.locale ?? 'en'}>
        <SessionContext.Provider
          value={{ onSessionExpired: options.sessionExpired ?? (() => undefined) }}
        >
          {view}
        </SessionContext.Provider>
      </I18nProvider>
    </ThemeProvider>,
  );
}

function rows(value: readonly string[]) {
  return <p data-testid="rows">{value.join(', ')}</p>;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('responsive ApiPage', () => {
  it.each(BREAKPOINTS)(
    'uses the page wrapper and keeps the labelled region at %s',
    async (breakpoint) => {
      installMatchMedia(breakpoint);

      wrap(
        <ApiPage resource={resource('Synthetic rows', ['SYN-0001', 'SYN-0002'])}>{rows}</ApiPage>,
      );

      const region = await screen.findByRole('region', { name: 'Synthetic rows' });
      expect(region.classList.contains('page')).toBe(true);
      expect(region.getAttribute('data-breakpoint')).toBe(breakpoint);
      expect(region.getAttribute('aria-label')).toBe('Synthetic rows');
      // The region also exists while loading; wait for the resolved rows before
      // asserting their text.
      await within(region).findByText('SYN-0001, SYN-0002');
      expect(region.textContent).toContain('SYN-0001, SYN-0002');
      expect(region.classList.contains('hide-compact')).toBe(false);
      expect(region.getAttribute('style')).toBeNull();
    },
  );

  it.each(BREAKPOINTS)('keeps every state inside the page wrapper at %s', async (breakpoint) => {
    installMatchMedia(breakpoint);

    const empty = wrap(
      <ApiPage
        resource={resource('Synthetic rows', [] as readonly string[])}
        isEmpty={(value) => value.length === 0}
      >
        {rows}
      </ApiPage>,
    );
    const emptyMessage = await screen.findByText('Nothing to show');
    const emptyRegion = emptyMessage.closest('section.page');
    expect(emptyRegion).not.toBeNull();
    expect(emptyRegion?.getAttribute('data-breakpoint')).toBe(breakpoint);
    expect(screen.queryByTestId('rows')).toBeNull();
    empty.unmount();

    const failing = {
      label: 'Synthetic rows',
      load: () => Promise.reject(new Error('synthetic offline')),
      deps: [],
    };
    const error = wrap(<ApiPage resource={failing}>{rows}</ApiPage>);
    const errorMessage = await screen.findByText('Something went wrong');
    const errorRegion = errorMessage.closest('section.page');
    expect(errorRegion).not.toBeNull();
    expect(errorRegion?.getAttribute('data-breakpoint')).toBe(breakpoint);
    error.unmount();

    let resolveRows!: (value: readonly string[]) => void;
    const pending = new Promise<readonly string[]>((onResolve) => {
      resolveRows = onResolve;
    });
    const loading = wrap(
      <ApiPage resource={{ label: 'Synthetic rows', load: async () => pending, deps: [] }}>
        {rows}
      </ApiPage>,
    );
    const loadingMessage = await screen.findByText('Loading…');
    const loadingRegion = loadingMessage.closest('section.page');
    expect(loadingRegion).not.toBeNull();
    expect(loadingRegion?.getAttribute('data-breakpoint')).toBe(breakpoint);
    resolveRows(['SYN-0003']);
    await pending;
    loading.unmount();
  });

  it('keeps child controls in DOM order for keyboard users at every breakpoint', async () => {
    for (const breakpoint of BREAKPOINTS) {
      installMatchMedia(breakpoint);
      const view = wrap(
        <ApiPage resource={resource('Synthetic rows', ['SYN-0004'])}>
          {(data) => (
            <>
              <button type="button">{`Open ${data[0]}`}</button>
              <button type="button">Retry</button>
            </>
          )}
        </ApiPage>,
      );

      const region = await screen.findByRole('region', { name: 'Synthetic rows' });
      const buttons = screen.getAllByRole('button');
      expect(buttons.map((button) => button.textContent)).toEqual(['Open SYN-0004', 'Retry']);
      for (const button of buttons) {
        expect(region.contains(button)).toBe(true);
        expect(button.getAttribute('tabindex')).toBeNull();
      }
      view.unmount();
      vi.unstubAllGlobals();
    }
  });

  it('keeps the logical page region and Arabic runs in right-to-left direction', async () => {
    installMatchMedia('compact');

    wrap(
      <ApiPage resource={resource('صفوف تجريبية', ['SYN-0005', 'اسم طويل جدًا باللغة العربية'])}>
        {rows}
      </ApiPage>,
      { locale: 'ar' },
    );

    expect(document.documentElement.dir).toBe('rtl');
    const region = await screen.findByRole('region', { name: 'صفوف تجريبية' });
    expect(region.classList.contains('page')).toBe(true);
    expect(region.getAttribute('data-breakpoint')).toBe('compact');
    expect(region.getAttribute('style')).toBeNull();
    expect(region.textContent).toContain('اسم طويل جدًا باللغة العربية');
  });
});
