import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppShell } from '../../src/app/AppShell';
import type { Role } from '../../src/app/navigation';
import { RouterProvider } from '../../src/app/router';
import {
  collectFeatureRoutes,
  createRoutes,
  routes,
  type FeatureRouteModule,
} from '../../src/app/routes';
import { I18nProvider } from '../../src/i18n/I18nProvider';
import { ThemeProvider } from '../../src/theme/ThemeProvider';

/** The feature modules collected exactly as the application collects them. */
const featureRouteModules = import.meta.glob<FeatureRouteModule>('../../src/features/*/routes.ts', {
  eager: true,
});
const featurePatterns = collectFeatureRoutes(featureRouteModules).map((route) => route.pattern);

const patterns = routes.map((route) => route.pattern);

afterEach(() => {
  window.history.replaceState(null, '', '/');
});

function renderShell(path = '/', role?: Role, onSignOut?: () => void) {
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

function rail() {
  return screen.getByRole('group', { name: 'Sections' });
}

describe('staff web application shell', () => {
  it('renders landmarks and a skip link before the header with the product name', () => {
    renderShell();
    const banner = screen.getByRole('banner');
    const skip = screen.getByRole('link', { name: /skip to main/i });
    expect(screen.getByRole('main')).toBeTruthy();
    expect(screen.getByRole('contentinfo')).toBeTruthy();
    expect(skip.getAttribute('href')).toBe('#main');
    expect(skip.compareDocumentPosition(banner) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(banner.textContent).toContain('Aurevia Care');
  });

  it('exposes theme and language toggles with pressed state', () => {
    renderShell();
    const theme = screen.getByRole('button', { name: 'Theme' });
    const language = screen.getByRole('button', { name: 'Language' });
    expect(theme.getAttribute('aria-pressed')).toBe('false');
    expect(language.getAttribute('aria-pressed')).toBe('false');
    fireEvent.click(theme);
    expect(theme.getAttribute('aria-pressed')).toBe('true');
    expect(document.documentElement.dataset.theme).toBe('dark');
    fireEvent.click(language);
    expect(language.getAttribute('aria-pressed')).toBe('true');
    expect(document.documentElement.dir).toBe('rtl');
  });

  it('shows the organization switcher and a status banner above the main landmark', () => {
    renderShell();
    expect(screen.getByLabelText('Organization')).toBeTruthy();
    const banner = screen.getByRole('status', { name: 'Notifications' });
    expect(banner).toBeTruthy();
    expect(
      banner.compareDocumentPosition(screen.getByRole('main')) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });

  it('calls onSignOut from the user menu', () => {
    const onSignOut = vi.fn();
    renderShell('/', undefined, onSignOut);
    const menu = screen.getByRole('group', { name: 'User menu' });
    fireEvent.click(within(menu).getByRole('button', { name: 'Sign out' }));
    expect(onSignOut).toHaveBeenCalledTimes(1);
  });

  it('keeps sign out inert until a session supplies a handler', () => {
    renderShell();
    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    expect(screen.getByRole('banner')).toBeTruthy();
  });

  it('renders the welcome page in the main landmark using an empty state', async () => {
    renderShell();
    expect(await screen.findByRole('heading', { name: 'Welcome' })).toBeTruthy();
    expect(
      screen.getAllByRole('status').some((node) => node.textContent?.includes('Nothing') === true),
    ).toBe(true);
  });

  it('shows the not-found page for an unknown path', async () => {
    renderShell('/no-such-page');
    expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeTruthy();
  });

  it('shows only the role items and marks the active one with aria-current', () => {
    renderShell('/', 'reception');
    const links = within(rail()).getAllByRole('link');
    expect(links.map((link) => link.textContent)).toContain('Check-in');
    expect(links.map((link) => link.textContent)).not.toContain('Facilities');
    expect(within(rail()).getByRole('link', { name: 'Today' }).getAttribute('aria-current')).toBe(
      'page',
    );
    expect(
      within(rail()).getByRole('link', { name: 'Schedule' }).getAttribute('aria-current'),
    ).toBe(null);
  });

  it('mirrors the rail in Arabic with logical positioning only', () => {
    renderShell('/', 'reception');
    const englishRail = rail();
    expect(englishRail.getAttribute('style')).toContain('inset-inline-start');
    expect(englishRail.getAttribute('style')).not.toMatch(/left:|right:/);
    fireEvent.click(screen.getByRole('button', { name: 'Language' }));
    expect(document.documentElement.dir).toBe('rtl');
    const arabicRail = screen.getByRole('group', { name: 'الأقسام' });
    expect(arabicRail.textContent).toContain('الجدول');
    expect(arabicRail.getAttribute('style')).toContain('inset-inline-start');
    expect(arabicRail.getAttribute('style')).not.toMatch(/left:|right:/);
  });

  it('moves focus to main after each navigation', async () => {
    renderShell();
    await screen.findByRole('heading', { name: 'Welcome' });
    fireEvent.click(within(rail()).getByRole('link', { name: 'Check-in' }));
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('main')));
  });
});

describe('route registry', () => {
  it('registers only welcome, the development preview and a catch-all', () => {
    const withoutPreview = createRoutes(false).map((route) => route.pattern);
    const withPreview = createRoutes(true).map((route) => route.pattern);
    // Only the leading welcome page and trailing catch-all are app-owned; the
    // rest come from self-registering feature modules, so adding one is safe.
    expect(withoutPreview).toEqual(['/', ...featurePatterns, '*']);
    expect(withPreview).toEqual(['/', ...featurePatterns, '/design-preview', '*']);
    // The app's five fixed pages keep this order (UX-01, WEB-09). They are
    // matched as an ordered subsequence, not by absolute index, because a
    // feature module may sort ahead of the system module.
    const fixedPages = ['/', '/signed-out', '/session-expired', '/forbidden', '/not-found'];
    expect(withoutPreview.filter((routePattern) => fixedPages.includes(routePattern))).toEqual(
      fixedPages,
    );
    // No route may shadow another one, with or without the preview page.
    expect(new Set(withoutPreview).size).toBe(withoutPreview.length);
    expect(new Set(withPreview).size).toBe(withPreview.length);
    const preview = routes.find((route) => route.pattern === '/design-preview');
    if (import.meta.env.DEV) {
      expect(preview).toBeDefined();
    } else {
      expect(preview).toBeUndefined();
    }
  });

  it('keeps the development preview interactive for visual review', async () => {
    if (!import.meta.env.DEV) {
      return;
    }
    renderShell('/design-preview');
    expect(await screen.findByText('ليلى Haddad · MAIN')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Show details' }));
    expect(screen.getByRole('dialog', { name: 'Facility details' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Close dialog' }));
    expect(screen.queryByRole('dialog')).toBeNull();
    fireEvent.change(screen.getByLabelText('Shift note'), {
      target: { value: 'synthetic handoff' },
    });
    fireEvent.submit(screen.getByRole('form', { name: 'Shift note form' }));
    expect(screen.getByRole('status', { name: 'Echo' }).textContent).toBe('synthetic handoff');
  });
});
