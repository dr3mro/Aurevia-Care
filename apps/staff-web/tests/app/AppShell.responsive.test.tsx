import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppShell } from '../../src/app/AppShell';
import type { Role } from '../../src/app/navigation';
import { RouterProvider } from '../../src/app/router';
import { routes } from '../../src/app/routes';
import { I18nProvider } from '../../src/i18n/I18nProvider';
import { ThemeProvider } from '../../src/theme/ThemeProvider';
import { BREAKPOINT_MEDIA_QUERIES } from '../../src/theme/breakpoints';

/** Media-query listener used by the deterministic viewport stand-in. */
type ChangeListener = (event: MediaQueryListEvent) => void;

/** Synthetic answers for the media queries the shell consults. */
interface ViewportState {
  medium: boolean;
  expanded: boolean;
  coarse: boolean;
  portrait: boolean;
}

/** The three layout bands from decision 0004, mirroring the Playwright matrix. */
const VIEWPORTS: Record<'compact' | 'medium' | 'expanded', ViewportState> = {
  compact: { medium: false, expanded: false, coarse: true, portrait: true },
  medium: { medium: true, expanded: false, coarse: true, portrait: false },
  expanded: { medium: true, expanded: true, coarse: false, portrait: false },
};

/** Registered patterns are collected exactly as the application collects them. */
const patterns = routes.map((route) => route.pattern);

/**
 * jsdom cannot evaluate media queries. Install a deterministic stand-in whose
 * answers follow the synthetic viewport state so the real hook resolves at each
 * width (WEB-07, UX-01).
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

/** Render the shell in English inside the providers the real entry point uses. */
function renderShell(path = '/', role: Role = 'reception', onSignOut?: () => void) {
  return render(
    <ThemeProvider>
      <I18nProvider>
        <RouterProvider patterns={patterns} initialPath={path}>
          <AppShell role={role} onSignOut={onSignOut} />
        </RouterProvider>
      </I18nProvider>
    </ThemeProvider>,
  );
}

/** Render the shell in Arabic so the provider sets `dir="rtl"` and `lang="ar"`. */
function renderRtlShell(path = '/', role: Role = 'reception') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale="ar">
        <RouterProvider patterns={patterns} initialPath={path}>
          <AppShell role={role} />
        </RouterProvider>
      </I18nProvider>
    </ThemeProvider>,
  );
}

/**
 * The visible tooltip for an icon-rail link. The span carries `hidden` until a
 * focus or a sustained press reveals it; an accessible name is on the anchor.
 */
function tooltipFor(link: HTMLElement): Element | null {
  return link.querySelector('[role="tooltip"]');
}

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  window.history.replaceState(null, '', '/');
});

describe('responsive application shell structure', () => {
  it('compact shows a slim header, one collapsed user menu and the off-canvas drawer', () => {
    const onSignOut = vi.fn();
    installMatchMedia(VIEWPORTS.compact);
    renderShell('/', 'reception', onSignOut);

    const root = screen.getByRole('main').closest('.app-shell');
    expect(root?.getAttribute('data-breakpoint')).toBe('compact');
    expect(screen.getByRole('heading', { level: 1 }).textContent).toContain('Aurevia Care');
    expect(screen.getByLabelText('Organization')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Sections' })).toBeTruthy();
    // The full rail is replaced by the drawer, so there is no rail group.
    expect(screen.queryByRole('group', { name: 'Sections' })).toBeNull();
    // On a phone the header controls collapse into one button.
    expect(screen.queryByRole('button', { name: 'Theme' })).toBeNull();
    expect(screen.getByRole('button', { name: 'User menu' })).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'User menu' }));
    expect(screen.getByRole('group', { name: 'User menu' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Theme' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Language' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    expect(onSignOut).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole('button', { name: 'Sections' }));
    const dialog = screen.getByRole('dialog', { name: 'Sections' });
    expect(dialog.getAttribute('style')).toContain('inset-inline-start');
    expect(within(dialog).getByRole('link', { name: 'Check-in' })).toBeTruthy();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('medium shows an icon rail with accessible names and no drawer button', () => {
    installMatchMedia(VIEWPORTS.medium);
    renderShell('/', 'reception');

    const rail = screen.getByRole('group', { name: 'Sections' });
    expect(rail.getAttribute('data-breakpoint')).toBe('medium');
    expect(rail.getAttribute('data-rail')).toBe('icon');
    expect(rail.getAttribute('style')).toContain('inset-inline-start');
    expect(rail.getAttribute('style')).not.toMatch(/left:|right:/);
    expect(screen.queryByRole('button', { name: 'Sections' })).toBeNull();

    const active = within(rail).getByRole('link', { name: 'Today' });
    const inactive = within(rail).getByRole('link', { name: 'Schedule' });
    expect(active.getAttribute('href')).toBe('/');
    expect(active.getAttribute('aria-current')).toBe('page');
    expect(inactive.getAttribute('aria-current')).toBeNull();
    expect(tooltipFor(active)?.hasAttribute('hidden')).toBe(true);
    expect(tooltipFor(active)?.textContent).toBe('Today');
  });

  it('expanded shows a full labelled rail and direct header controls', () => {
    installMatchMedia(VIEWPORTS.expanded);
    renderShell('/', 'reception');

    const rail = screen.getByRole('group', { name: 'Sections' });
    expect(rail.getAttribute('data-breakpoint')).toBe('expanded');
    expect(rail.getAttribute('data-rail')).toBe('full');
    expect(within(rail).getByRole('link', { name: 'Check-in' }).textContent).toBe('Check-in');
    expect(screen.getByRole('button', { name: 'Theme' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Language' })).toBeTruthy();
    expect(screen.getByRole('group', { name: 'User menu' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeTruthy();
  });
});

describe('responsive landmarks and keyboard behaviour', () => {
  it.each(['compact', 'medium', 'expanded'] as const)(
    '%s exposes the navigation, banner, main and contentinfo landmarks and a leading skip link',
    (viewport) => {
      installMatchMedia(VIEWPORTS[viewport]);
      renderShell();

      const skip = screen.getByRole('link', { name: /skip to main/i });
      const banner = screen.getByRole('banner');
      expect(skip.getAttribute('href')).toBe('#main');
      expect(skip.compareDocumentPosition(banner) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      expect(screen.getByRole('navigation')).toBeTruthy();
      expect(screen.getByRole('main')).toBeTruthy();
      expect(screen.getByRole('contentinfo')).toBeTruthy();
    },
  );

  it.each(['compact', 'medium', 'expanded'] as const)(
    '%s moves focus to main after following a navigation link',
    async (viewport) => {
      installMatchMedia(VIEWPORTS[viewport]);
      renderShell('/', 'reception');
      await screen.findByRole('heading', { name: 'Welcome' });

      if (viewport === 'compact') {
        fireEvent.click(screen.getByRole('button', { name: 'Sections' }));
        fireEvent.click(
          within(screen.getByRole('dialog', { name: 'Sections' })).getByRole('link', {
            name: 'Check-in',
          }),
        );
      } else {
        fireEvent.click(
          within(screen.getByRole('group', { name: 'Sections' })).getByRole('link', {
            name: 'Check-in',
          }),
        );
      }
      await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('main')));
    },
  );

  it('mirrors the medium icon rail under right-to-left without physical insets', () => {
    installMatchMedia(VIEWPORTS.medium);
    renderRtlShell('/', 'reception');

    expect(document.documentElement.dir).toBe('rtl');
    const rail = screen.getByRole('group', { name: 'الأقسام' });
    expect(within(rail).getByRole('link', { name: 'اليوم' })).toBeTruthy();
    expect(rail.getAttribute('style')).toContain('inset-inline-start');
    expect(rail.getAttribute('style')).not.toMatch(/left:|right:/);
    expect(screen.getByRole('button', { name: 'اللغة' })).toBeTruthy();
  });

  it('opens the compact drawer from the inline start under right-to-left', () => {
    installMatchMedia(VIEWPORTS.compact);
    renderRtlShell('/', 'reception');

    fireEvent.click(screen.getByRole('button', { name: 'الأقسام' }));
    const dialog = screen.getByRole('dialog', { name: 'الأقسام' });
    expect(dialog.getAttribute('style')).toContain('inset-inline-start');
    expect(dialog.getAttribute('style')).not.toMatch(/left:|right:/);
    expect(within(dialog).getByRole('link', { name: 'الجدول' })).toBeTruthy();
  });
});

describe('medium icon rail tooltips', () => {
  it('reveals the accessible label on focus and hides it on blur', () => {
    installMatchMedia(VIEWPORTS.medium);
    renderShell('/', 'reception');

    const today = within(screen.getByRole('group', { name: 'Sections' })).getByRole('link', {
      name: 'Today',
    });
    const tip = tooltipFor(today);
    expect(tip?.hasAttribute('hidden')).toBe(true);
    fireEvent.focus(today);
    expect(tip?.hasAttribute('hidden')).toBe(false);
    expect(tip?.textContent).toBe('Today');
    fireEvent.blur(today);
    expect(tip?.hasAttribute('hidden')).toBe(true);
  });

  it('reveals the tooltip after a sustained press and clears it on release', () => {
    vi.useFakeTimers();
    installMatchMedia(VIEWPORTS.medium);
    renderShell('/', 'reception');

    const today = within(screen.getByRole('group', { name: 'Sections' })).getByRole('link', {
      name: 'Today',
    });
    const tip = tooltipFor(today);
    fireEvent.pointerDown(today);
    // A tap must not reveal the tooltip; it appears only after the hold.
    expect(tip?.hasAttribute('hidden')).toBe(true);
    act(() => {
      vi.runAllTimers();
    });
    expect(tip?.hasAttribute('hidden')).toBe(false);
    fireEvent.pointerUp(today);
    expect(tip?.hasAttribute('hidden')).toBe(true);
    // Releasing without a pending press is harmless.
    fireEvent.pointerUp(today);
  });

  it('cancels a pending press when the pointer is cancelled or leaves', () => {
    vi.useFakeTimers();
    installMatchMedia(VIEWPORTS.medium);
    renderShell('/', 'reception');

    const schedule = within(screen.getByRole('group', { name: 'Sections' })).getByRole('link', {
      name: 'Schedule',
    });
    const tip = tooltipFor(schedule);
    fireEvent.pointerDown(schedule);
    fireEvent.pointerCancel(schedule);
    act(() => {
      vi.runAllTimers();
    });
    expect(tip?.hasAttribute('hidden')).toBe(true);

    fireEvent.pointerDown(schedule);
    fireEvent.pointerDown(schedule);
    fireEvent.pointerLeave(schedule);
    act(() => {
      vi.runAllTimers();
    });
    expect(tip?.hasAttribute('hidden')).toBe(true);
  });

  it('navigates from an icon link without a document reload', async () => {
    installMatchMedia(VIEWPORTS.medium);
    renderShell('/', 'reception');

    const link = within(screen.getByRole('group', { name: 'Sections' })).getByRole('link', {
      name: 'Check-in',
    });
    fireEvent.click(link);
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('main')));
    expect(link.getAttribute('href')).toBe('/checkin');
  });
});

describe('responsive content and safe-area contract', () => {
  it('uses the page class on main and logical safe-area padding on the header and footer', () => {
    installMatchMedia(VIEWPORTS.compact);
    renderShell();

    const root = screen.getByRole('main').closest('.app-shell');
    expect(root?.classList.contains('app-shell')).toBe(true);
    const main = screen.getByRole('main');
    expect(main.classList.contains('page')).toBe(true);
    expect(main.style.overflowX).toBe('');

    const header = screen.getByRole('banner');
    const footer = screen.getByRole('contentinfo');
    expect(header.style.paddingBlockStart).toContain('safe-block-start');
    expect(footer.style.paddingBlockEnd).toContain('safe-block-end');
    expect(header.style.paddingLeft).toBe('');
    expect(header.style.paddingRight).toBe('');
    expect(footer.style.paddingLeft).toBe('');
    expect(footer.style.paddingRight).toBe('');
  });
});

describe('medium icon rail link click semantics', () => {
  // Modified and non-primary clicks are deliberately left to the browser, so
  // jsdom logs "Not implemented: navigation" when it cannot open a tab. The
  // assertions below verify that the shell itself did not change the in-app path.
  it.each([
    ['ctrlKey', { ctrlKey: true }],
    ['metaKey', { metaKey: true }],
    ['shiftKey', { shiftKey: true }],
    ['altKey', { altKey: true }],
    ['a non-primary button', { button: 1 }],
  ] as const)('does not navigate with %s held', (_name, init) => {
    installMatchMedia(VIEWPORTS.medium);
    renderShell('/', 'reception');
    const link = within(screen.getByRole('group', { name: 'Sections' })).getByRole('link', {
      name: 'Check-in',
    });
    fireEvent.click(link, init);
    expect(window.location.pathname).toBe('/');
  });

  it('does not navigate when the click was already prevented', () => {
    installMatchMedia(VIEWPORTS.medium);
    renderShell('/', 'reception');
    const link = within(screen.getByRole('group', { name: 'Sections' })).getByRole('link', {
      name: 'Check-in',
    });
    link.addEventListener('click', (event) => event.preventDefault(), { capture: true });
    fireEvent.click(link);
    expect(window.location.pathname).toBe('/');
  });
});

describe('medium icon rail tooltip persistence', () => {
  it('keeps a focus-shown tooltip visible while the pointer leaves, until focus moves', () => {
    installMatchMedia(VIEWPORTS.medium);
    renderShell('/', 'reception');
    const today = within(screen.getByRole('group', { name: 'Sections' })).getByRole('link', {
      name: 'Today',
    });
    const tip = tooltipFor(today);
    fireEvent.focus(today);
    expect(tip?.hasAttribute('hidden')).toBe(false);
    fireEvent.pointerLeave(today);
    expect(tip?.hasAttribute('hidden')).toBe(false);
    fireEvent.pointerCancel(today);
    expect(tip?.hasAttribute('hidden')).toBe(false);
    fireEvent.blur(today);
    expect(tip?.hasAttribute('hidden')).toBe(true);
  });
});
