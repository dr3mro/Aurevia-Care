import { render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppShell } from './AppShell';
import type { Role } from './navigation';
import { RouterProvider } from './router';
import { routes } from './routes';
import { stateViewText } from '../components/stateViewMessages';
import { I18nProvider } from '../i18n/I18nProvider';
import { ThemeProvider } from '../theme/ThemeProvider';

/**
 * One synthetic session role shared by the shell and by feature pages. The
 * shell guard and a feature's own forbidden state must observe the same role,
 * so the source they both read is replaced rather than passing a shell-only
 * prop.
 */
const session = vi.hoisted(() => ({ role: 'reception' as Role }));
vi.mock('./useRole', () => ({ useRole: () => session.role }));

const patterns = routes.map((route) => route.pattern);
const forbiddenTitle = stateViewText('en', 'forbidden');

/** Reset the synthetic session and location between isolated renders. */
afterEach(() => {
  session.role = 'reception';
  window.history.replaceState(null, '', '/');
});

/** Render the shell at a direct-link path with one synthetic session role. */
function renderAt(path: string, role: Role) {
  session.role = role;
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale="en">
        <RouterProvider patterns={patterns} initialPath={path}>
          <AppShell />
        </RouterProvider>
      </I18nProvider>
    </ThemeProvider>,
  );
}

/** A shipped route, the role allowed to open it and an unrelated role. */
interface AccessCase {
  path: string;
  allowed: Role;
  denied: Role;
  label: string;
}

const ACCESS_CASES: readonly AccessCase[] = [
  { path: '/lab/results/entry', allowed: 'lab', denied: 'reception', label: 'Result entry' },
  { path: '/lab/release', allowed: 'lab', denied: 'reception', label: 'Release results' },
  {
    path: '/lab/turnaround-board',
    allowed: 'lab',
    denied: 'reception',
    label: 'Turnaround board',
  },
  { path: '/operations/exports', allowed: 'tenant_admin', denied: 'reception', label: 'Exports' },
  { path: '/operations/imports', allowed: 'tenant_admin', denied: 'reception', label: 'Imports' },
  {
    path: '/operations/partner-board',
    allowed: 'operator',
    denied: 'reception',
    label: 'Partner board',
  },
  { path: '/pharmacy/item-1', allowed: 'pharmacy', denied: 'reception', label: '' },
];

describe('shell role guard on direct links', () => {
  for (const accessCase of ACCESS_CASES) {
    it(`renders ${accessCase.path} for ${accessCase.allowed} without the forbidden state`, async () => {
      renderAt(accessCase.path, accessCase.allowed);
      // A lazy page resolves after a tick; the shell error boundary must not
      // replace it and no forbidden state may appear for an authorized role.
      await screen.findByRole('main');
      await vi.waitFor(() => {
        expect(screen.queryByRole('heading', { name: forbiddenTitle })).toBeNull();
      });
      expect(screen.queryByRole('button', { name: 'Go home' })).toBeNull();
    });

    it(`shows the shell forbidden page for ${accessCase.denied} on ${accessCase.path}`, async () => {
      renderAt(accessCase.path, accessCase.denied);
      expect(await screen.findByRole('heading', { name: forbiddenTitle })).toBeTruthy();
      // "Go home" belongs to the shell's /forbidden page, so this proves the
      // guard substituted it instead of a feature's own forbidden state.
      expect(screen.getByRole('button', { name: 'Go home' })).toBeTruthy();
    });
  }

  it('lets an auditor open a direct import link that the feature authorizes', async () => {
    renderAt('/operations/imports', 'privacy_auditor');
    await screen.findByRole('main');
    await vi.waitFor(() => {
      expect(screen.queryByRole('heading', { name: forbiddenTitle })).toBeNull();
    });
    expect(screen.queryByRole('button', { name: 'Go home' })).toBeNull();
  });

  it('hides the new rail entries from a role that cannot open them', () => {
    renderAt('/', 'reception');
    const links = within(screen.getByRole('group', { name: 'Sections' }))
      .getAllByRole('link')
      .map((link) => link.textContent);
    for (const accessCase of ACCESS_CASES) {
      if (accessCase.label.length > 0) {
        expect(links).not.toContain(accessCase.label);
      }
    }
  });

  it('shows the lab entries in the rail for the lab role', () => {
    renderAt('/', 'lab');
    const links = within(screen.getByRole('group', { name: 'Sections' }))
      .getAllByRole('link')
      .map((link) => link.textContent);
    expect(links).toContain('Result entry');
    expect(links).toContain('Release results');
    expect(links).toContain('Turnaround board');
    expect(links).not.toContain('Exports');
  });
});

describe('unguarded routes', () => {
  it('still renders the welcome page for every role', async () => {
    renderAt('/', 'reception');
    expect(await screen.findByRole('heading', { name: 'Welcome' })).toBeTruthy();
  });

  it('still renders the system forbidden page', async () => {
    renderAt('/forbidden', 'reception');
    expect(await screen.findByRole('heading', { name: forbiddenTitle })).toBeTruthy();
  });

  it('still renders the catch-all for an unknown path', async () => {
    renderAt('/no-such-page', 'reception');
    expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeTruthy();
  });

  it('still renders the development preview when the flag is on', async () => {
    if (!import.meta.env.DEV) {
      return;
    }
    renderAt('/design-preview', 'reception');
    expect(await screen.findByText('ليلى Haddad · MAIN')).toBeTruthy();
  });
});
