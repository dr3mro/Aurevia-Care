import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { I18nProvider } from '../i18n/I18nProvider';
import { ThemeProvider } from '../theme/ThemeProvider';
import { AppShell } from './AppShell';
import {
  canAccessRoute,
  navigation,
  navigationFor,
  navigationLabels,
  routeAccessRoles,
} from './navigation';
import type { Role } from './navigation';
import { RouterProvider } from './router';
import { routes } from './routes';

/** Every registered pattern, bound exactly as the application binds them. */
const patterns = routes.map((candidate) => candidate.pattern);

afterEach(() => {
  window.history.replaceState(null, '', '/');
});

/** Render the shell at a concrete path under an explicit role. */
function renderAt(path: string, role: Role) {
  return render(
    <ThemeProvider>
      <I18nProvider>
        <RouterProvider patterns={patterns} initialPath={path}>
          <AppShell role={role} />
        </RouterProvider>
      </I18nProvider>
    </ThemeProvider>,
  );
}

/**
 * The shell's forbidden page alone renders the "Go home" action. A feature page
 * showing its own ForbiddenState has no such button, so this distinguishes a
 * shell-level denial from a page-level one.
 */
function showsShellForbidden(): boolean {
  return screen.queryByRole('button', { name: 'Go home' }) !== null;
}

describe('route access roles', () => {
  it('returns the roles declared by the navigation entry for a route', () => {
    expect(routeAccessRoles('/admin/facilities')).toEqual(['tenant_admin']);
    expect(routeAccessRoles('/operations/exports')).toEqual(['tenant_admin']);
    expect(routeAccessRoles('/operations/imports')).toEqual(['tenant_admin', 'privacy_auditor']);
  });

  it('returns null for a route that carries no role restriction', () => {
    expect(routeAccessRoles('/signed-out')).toBeNull();
    expect(routeAccessRoles('/no-such-route')).toBeNull();
  });

  it.each([
    ['tenant_admin', '/admin/facilities', true],
    ['tenant_admin', '/operations/exports', true],
    ['tenant_admin', '/operations/imports', true],
    ['privacy_auditor', '/operations/imports', true],
    ['reception', '/admin/facilities', false],
    ['reception', '/operations/exports', false],
    ['reception', '/operations/imports', false],
    ['staff', '/', true],
    // '/' declares only the base role, so this is true only through the
    // ALL_STAFF_ROLE fallback and would fail if that grant were removed.
    ['reception', '/', true],
    ['reception', '/signed-out', true],
  ] as const)('canAccessRoute(%s, %s) is %s', (role, pattern, expected) => {
    expect(canAccessRoute(role, pattern)).toBe(expected);
  });
});

describe('navigation entries for the new feature routes', () => {
  it.each([
    ['/operations/exports', ['tenant_admin'], 'exports'],
    ['/operations/imports', ['tenant_admin', 'privacy_auditor'], 'imports'],
  ] as const)('registers %s for %s only', (route, roles, labelKey) => {
    const item = navigation.find((candidate) => candidate.route === route);
    expect(item).toBeDefined();
    expect(item!.roles).toEqual([...roles]);
    expect(item!.labelKey).toBe(labelKey);
    for (const role of roles) {
      expect(navigationFor(role).map((entry) => entry.route)).toContain(route);
    }
    expect(navigationFor('reception').map((entry) => entry.route)).not.toContain(route);
  });

  it('labels the new entries in English and Arabic', () => {
    for (const route of ['/operations/exports', '/operations/imports']) {
      const item = navigation.find((candidate) => candidate.route === route)!;
      expect(navigationLabels.en[item.labelKey]).toBeTruthy();
      expect(navigationLabels.ar[item.labelKey]).toBeTruthy();
    }
  });
});

interface ScopedRoute {
  pattern: string;
  path: string;
  allowed: Role;
  denied: Role;
}

/** Representative route per role restriction covered by the navigation table. */
const SCOPED_ROUTES: readonly ScopedRoute[] = [
  { pattern: '/admin/facilities', path: '/admin/facilities', allowed: 'tenant_admin', denied: 'reception' },
  { pattern: '/operations/exports', path: '/operations/exports', allowed: 'tenant_admin', denied: 'reception' },
  { pattern: '/operations/imports', path: '/operations/imports', allowed: 'tenant_admin', denied: 'reception' },
  { pattern: '/patients/:id/chart', path: '/patients/patient-1/chart', allowed: 'clinician', denied: 'reception' },
  { pattern: '/schedule', path: '/schedule', allowed: 'reception', denied: 'tenant_admin' },
  { pattern: '/radiology', path: '/radiology', allowed: 'radiology', denied: 'reception' },
  { pattern: '/pharmacy', path: '/pharmacy', allowed: 'pharmacy', denied: 'reception' },
];

describe('role-scoped route rendering', () => {
  it.each(SCOPED_ROUTES)('$pattern is registered as a lazy route', ({ pattern }) => {
    expect(routes.some((candidate) => candidate.pattern === pattern)).toBe(true);
  });

  it.each(SCOPED_ROUTES)('$pattern renders for $allowed', async ({ path, allowed }) => {
    renderAt(path, allowed);
    // The lazy screen has resolved: the shell let the role through instead of
    // swapping in its own forbidden page. A feature may still show its own
    // state view, so the shell's "Go home" action is the distinguishing signal.
    await waitFor(() => expect(screen.queryByText('Loading', { exact: true })).toBeNull());
    expect(showsShellForbidden()).toBe(false);
  });

  it.each(SCOPED_ROUTES)('$pattern shows the forbidden state for $denied', async ({ path, denied }) => {
    renderAt(path, denied);
    expect(await screen.findByRole('heading', { name: 'You do not have access' })).toBeTruthy();
    expect(showsShellForbidden()).toBe(true);
  });

  it('renders the catch-all for an unregistered path without a role denial', async () => {
    // No route matched, so the left side of AppShell's `matched !== undefined &&`
    // is false: the catch-all must render rather than the shell's forbidden page.
    renderAt('/no-such-route', 'reception');
    await waitFor(() => expect(screen.queryByText('Loading', { exact: true })).toBeNull());
    expect(screen.getByRole('heading', { name: 'Page not found' })).toBeTruthy();
    expect(showsShellForbidden()).toBe(false);
  });
});
