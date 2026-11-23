import { fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppShell } from '../../src/app/AppShell';
import { NavigationDrawer } from '../../src/app/NavigationDrawer';
import { groupLabelKey, navigationGroupsFor, navigationLabel } from '../../src/app/navigation';
import type { Role } from '../../src/app/navigation';
import { RouterProvider } from '../../src/app/router';
import { routes } from '../../src/app/routes';
import { I18nProvider } from '../../src/i18n/I18nProvider';
import type { Locale } from '../../src/i18n/messages';
import { ThemeProvider } from '../../src/theme/ThemeProvider';
import { must } from '../must';

const DRAWER_ID = 'staff-navigation-drawer';
const EN_LABEL = 'Sections';
const AR_LABEL = 'الأقسام';

function renderDrawer(
  role: Role = 'reception',
  onNavigate?: (route: string) => void,
  locale: Locale = 'en',
) {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <NavigationDrawer role={role} onNavigate={onNavigate} />
      </I18nProvider>
    </ThemeProvider>,
  );
}

function openWith(
  role: Role = 'reception',
  onNavigate?: (route: string) => void,
  locale: Locale = 'en',
) {
  const view = renderDrawer(role, onNavigate, locale);
  const label = locale === 'ar' ? AR_LABEL : EN_LABEL;
  fireEvent.click(screen.getByRole('button', { name: label }));
  return { ...view, label };
}

function closeButton(label: string) {
  const name = label === AR_LABEL ? 'إغلاق النافذة' : 'Close dialog';
  return screen.getByRole('button', { name });
}

function drawerLinks(): HTMLAnchorElement[] {
  return within(screen.getByRole('dialog')).getAllByRole<HTMLAnchorElement>('link');
}

afterEach(() => {
  vi.unstubAllGlobals();
  document.body.style.overflow = '';
  window.history.replaceState(null, '', '/');
});

/** Props shared by the shell rail and the drawer in the contract test. */
const receptionProps = { role: 'reception' } as const;

describe('NavigationDrawer — menu button and modal lifecycle', () => {
  it('opens from the labelled menu button with aria state and locks page scroll', () => {
    renderDrawer();
    const button = screen.getByRole('button', { name: EN_LABEL });
    expect(button.getAttribute('aria-expanded')).toBe('false');
    expect(button.getAttribute('aria-controls')).toBe(DRAWER_ID);
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(document.body.style.overflow).toBe('');

    fireEvent.click(button);

    expect(button.getAttribute('aria-expanded')).toBe('true');
    const dialog = screen.getByRole('dialog', { name: EN_LABEL });
    expect(dialog.id).toBe(DRAWER_ID);
    expect(document.body.style.overflow).toBe('hidden');
    // Focus enters on the first meaningful control (the close button).
    expect(document.activeElement).toBe(closeButton(EN_LABEL));
  });

  it('closes with the close button, returns focus and unlocks page scroll', () => {
    openWith();
    const button = screen.getByRole('button', { name: EN_LABEL });
    fireEvent.click(closeButton(EN_LABEL));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(button.getAttribute('aria-expanded')).toBe('false');
    expect(document.body.style.overflow).toBe('');
    expect(document.activeElement).toBe(button);
  });

  it('closes on Escape and restores focus to the menu button', () => {
    openWith();
    const button = screen.getByRole('button', { name: EN_LABEL });
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(document.activeElement).toBe(button);
    expect(document.body.style.overflow).toBe('');
  });

  it('closes on a backdrop tap', () => {
    openWith();
    const backdrop = screen.getByTestId('nav-drawer-backdrop');
    fireEvent.click(backdrop);
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: EN_LABEL }));
  });

  it('restores a pre-existing body overflow value on close', () => {
    document.body.style.overflow = 'auto';
    openWith();
    expect(document.body.style.overflow).toBe('hidden');
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(document.body.style.overflow).toBe('auto');
  });

  it('can be reopened after it closes', () => {
    openWith();
    fireEvent.keyDown(document, { key: 'Escape' });
    const button = screen.getByRole('button', { name: EN_LABEL });
    fireEvent.click(button);
    expect(screen.getByRole('dialog')).toBeTruthy();
    expect(button.getAttribute('aria-expanded')).toBe('true');
  });
});

describe('NavigationDrawer — link selection', () => {
  it('closes on a link choice and reports the chosen route to the callback', () => {
    const onNavigate = vi.fn();
    openWith('reception', onNavigate);
    const link = drawerLinks().find((item) => item.textContent === 'Check-in');
    if (!link) {
      throw new Error('expected the Check-in link');
    }
    fireEvent.click(link);
    expect(onNavigate).toHaveBeenCalledTimes(1);
    expect(onNavigate).toHaveBeenCalledWith('/checkin');
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: EN_LABEL }));
  });

  it('uses the in-application router when no navigation callback is supplied', () => {
    const pushState = vi.spyOn(window.history, 'pushState');
    openWith();
    const link = drawerLinks().find((item) => item.textContent === 'Today');
    if (!link) {
      throw new Error('expected the Today link');
    }
    fireEvent.click(link);
    expect(pushState).toHaveBeenCalledWith(null, '', '/');
    expect(screen.queryByRole('dialog')).toBeNull();
    pushState.mockRestore();
  });
});

describe('NavigationDrawer — role-scoped navigation', () => {
  it('renders exactly the groups and links of navigationGroupsFor(role)', () => {
    const role: Role = 'clinician';
    openWith(role);
    const expected = navigationGroupsFor(role);
    const dialog = screen.getByRole('dialog');
    const regions = within(dialog).getAllByRole('region');
    expect(regions).toHaveLength(expected.length);

    expected.forEach((group, index) => {
      const heading = navigationLabel('en', groupLabelKey(group.group));
      const region = must(regions[index]);
      expect(within(region).getByRole('heading', { name: heading })).toBeTruthy();
      const links = within(region).getAllByRole<HTMLAnchorElement>('link');
      expect(links.map((link) => link.textContent)).toEqual(
        group.items.map((item) => navigationLabel('en', item.labelKey)),
      );
      expect(links.map((link) => link.getAttribute('href'))).toEqual(
        group.items.map((item) => item.route),
      );
    });

    const rendered = drawerLinks().map((link) => link.textContent);
    expect(rendered).toContain('Patients');
    expect(rendered).not.toContain('Check-in');
    expect(rendered).not.toContain('Facilities');
    expect(rendered).not.toContain('Operations queues');
    expect(drawerLinks()).toHaveLength(
      expected.reduce((total, group) => total + group.items.length, 0),
    );
  });
});

describe('NavigationDrawer — focus containment', () => {
  it('keeps Tab focus inside the drawer and wraps at both ends', () => {
    openWith('reception');
    const focusables = [closeButton(EN_LABEL), ...drawerLinks()] as HTMLElement[];
    const first = must(focusables[0]);
    const last = must(focusables[focusables.length - 1]);
    const middle = must(focusables[1]);

    // Initial focus is the first meaningful control.
    expect(document.activeElement).toBe(first);

    // Forward wrap: Tab from the last control returns to the first.
    last.focus();
    fireEvent.keyDown(document, { key: 'Tab' });
    expect(document.activeElement).toBe(first);

    // Backward wrap: Shift+Tab from the first control moves to the last.
    fireEvent.keyDown(document, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(last);

    // A Shift+Tab from a middle control is left to the browser (not trapped).
    middle.focus();
    expect(fireEvent.keyDown(document, { key: 'Tab', shiftKey: true })).toBe(true);
    expect(document.activeElement).toBe(middle);

    // A forward Tab from a middle control is also left to the browser.
    expect(fireEvent.keyDown(document, { key: 'Tab' })).toBe(true);
    expect(document.activeElement).toBe(middle);

    // Keys other than Tab and Escape do not alter focus or close the drawer.
    expect(fireEvent.keyDown(document, { key: 'a' })).toBe(true);
    expect(document.activeElement).toBe(middle);
    expect(screen.getByRole('dialog')).toBeTruthy();
  });
});

describe('NavigationDrawer — direction and motion', () => {
  it('mirrors from the inline-start edge under right-to-left using the dir attribute', () => {
    const { label } = openWith('reception', undefined, 'ar');
    const dialog = screen.getByRole('dialog', { name: label });
    expect(document.documentElement.dir).toBe('rtl');
    expect(dialog.getAttribute('dir')).toBe('rtl');

    const style = dialog.getAttribute('style') ?? '';
    expect(style).toContain('inset-inline-start');
    expect(style).not.toMatch(/left:|right:/);

    const closeStyle = closeButton(label).getAttribute('style') ?? '';
    expect(closeStyle).toContain('margin-inline-start');
    expect(closeStyle).not.toMatch(/left:|right:/);

    const region = must(within(dialog).getAllByRole('region')[0]);
    expect(within(region).getByRole('heading', { name: 'اليوم' })).toBeTruthy();
  });

  it('removes the slide transition when reduced motion is preferred', () => {
    vi.stubGlobal('matchMedia', () => ({
      matches: true,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }));
    openWith();
    const dialog = screen.getByRole('dialog');
    expect(dialog.getAttribute('style')).toContain('transition: none');
  });

  it('keeps the slide transition when reduced motion is not preferred', () => {
    vi.stubGlobal('matchMedia', () => ({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }));
    openWith();
    const dialog = screen.getByRole('dialog');
    expect(dialog.getAttribute('style')).toContain('transition: inset-inline-start');
  });
});

describe('NavigationDrawer — shared contracts with the shell', () => {
  it('uses the shared touch target class on the menu and close controls', () => {
    const { label } = openWith();
    expect(screen.getByRole('button', { name: label }).classList.contains('touch')).toBe(true);
    expect(closeButton(label).classList.contains('touch')).toBe(true);
  });

  it('keeps heading ids unique when the shell rail and the drawer both mount', () => {
    render(
      <ThemeProvider>
        <I18nProvider>
          <RouterProvider patterns={routes.map((route) => route.pattern)} initialPath="/">
            <AppShell {...receptionProps} />
            <NavigationDrawer {...receptionProps} />
          </RouterProvider>
        </I18nProvider>
      </ThemeProvider>,
    );
    fireEvent.click(screen.getByRole('button', { name: EN_LABEL }));

    const railHeading = document.getElementById('nav-heading-Today');
    const drawerHeading = document.getElementById('nav-drawer-heading-Today');
    expect(railHeading).toBeTruthy();
    expect(drawerHeading).toBeTruthy();
    expect(railHeading).not.toBe(drawerHeading);
    expect(document.querySelectorAll('[id="nav-heading-Today"]')).toHaveLength(1);

    const section = must(within(screen.getByRole('dialog')).getAllByRole('region')[0]);
    const labelledBy = section.getAttribute('aria-labelledby');
    expect(labelledBy).toBe('nav-drawer-heading-Today');
    expect(document.getElementById(must(labelledBy))).toBe(drawerHeading);
  });
});
