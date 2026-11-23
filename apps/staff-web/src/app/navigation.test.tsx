import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { AppShell } from './AppShell';
import {
  navigation,
  navigationFor,
  navigationGroupsFor,
  navigationLabel,
  navigationLabels,
  groupLabelKey,
  rolesForRoute,
  UNRESTRICTED_ROUTE,
} from './navigation';
import type { NavigationGroup, Role } from './navigation';
import { collectFeatureRoutes, type FeatureRouteModule } from './routes';
import { IMPORT_PERMITTED_ROLES } from '../features/dataexchange/domain';
import { I18nProvider } from '../i18n/I18nProvider';
import { ThemeProvider } from '../theme/ThemeProvider';

/** Feature modules collected exactly as the application collects them. */
const featureRouteModules = import.meta.glob<FeatureRouteModule>(
  '../features/*/routes.ts',
  { eager: true },
);

const ALL_ROLES: readonly Role[] = [
  'reception',
  'clinician',
  'lab',
  'radiology',
  'therapist',
  'pharmacy',
  'finance',
  'operator',
  'unit_staff',
  'tenant_admin',
  'privacy_auditor',
  'staff',
];

function idsFor(role: Role): string[] {
  return navigationFor(role).map((item) => item.id);
}

function renderShell(role?: Role) {
  return render(
    <ThemeProvider>
      <I18nProvider>
        {role === undefined ? <AppShell /> : <AppShell role={role} />}
      </I18nProvider>
    </ThemeProvider>,
  );
}

describe('staff web navigation model (screen map section 2)', () => {
  it('gives reception Scheduling, Clinic and Patients but no Administration item', () => {
    const items = navigationFor('reception');
    const groups = items.map((item) => item.group);
    expect(groups).toContain('Scheduling');
    expect(groups).toContain('Clinic');
    expect(groups).toContain('Patients');
    expect(groups).not.toContain('Administration');
    expect(idsFor('reception')).toContain('schedule');
    expect(idsFor('reception')).toContain('check-in');
    expect(idsFor('reception')).toContain('patients');
  });

  it('gives the tenant admin the Administration items', () => {
    const adminItems = navigationFor('tenant_admin').filter(
      (item) => item.group === 'Administration',
    );
    expect(adminItems.map((item) => item.id)).toEqual([
      'admin-facilities',
      'admin-staff',
      'admin-config',
      'admin-audit',
    ]);
  });

  it('gives the privacy auditor only the audit item plus the two all-staff items', () => {
    // "Today" and "Account" are the table's "all staff" rows, visible to
    // every role; among role-restricted items the auditor sees only audit.
    expect(idsFor('privacy_auditor')).toEqual(['home', 'admin-audit', 'account-sessions']);
  });

  it('never shows the same item twice, for any role', () => {
    for (const role of ALL_ROLES) {
      const ids = idsFor(role);
      expect(new Set(ids).size).toBe(ids.length);
    }
    expect(new Set(navigation.map((item) => item.id)).size).toBe(navigation.length);
  });

  it('has a label for every item and group in both languages', () => {
    for (const item of navigation) {
      expect(navigationLabels.en[item.labelKey]).toBeTruthy();
      expect(navigationLabels.ar[item.labelKey]).toBeTruthy();
    }
    const groups = new Set(navigation.map((item) => item.group));
    for (const group of groups) {
      const key = groupLabelKey(group);
      expect(navigationLabels.en[key]).toBeTruthy();
      expect(navigationLabels.ar[key]).toBeTruthy();
    }
  });

  it('keeps identical English and Arabic key sets', () => {
    expect(Object.keys(navigationLabels.en).sort()).toEqual(Object.keys(navigationLabels.ar).sort());
  });

  it('groups items in the table order', () => {
    expect(navigationGroupsFor('reception').map((group) => group.group)).toEqual([
      'Today',
      'Scheduling',
      'Clinic',
      'Patients',
      'Account',
    ]);
  });

  it('resolves a navigation label for the active locale', () => {
    expect(navigationLabel('en', 'schedule')).toBe('Schedule');
    expect(navigationLabel('ar', 'schedule')).toBe('الجدول');
  });

  it('adds no System route and no route outside the screen map', () => {
    const systemRoutes = [
      '/signed-out',
      '/session-expired',
      '/forbidden',
      '/not-found',
      '/unsupported-browser',
    ];
    for (const item of navigation) {
      expect(item.route.startsWith('/')).toBe(true);
      expect(systemRoutes).not.toContain(item.route);
    }
  });
});

describe('staff web navigation rail', () => {
  it('renders the reception rail from navigationFor(role) and hides Administration', () => {
    renderShell('reception');
    const rail = screen.getByRole('group', { name: 'Sections' });
    const links = within(rail).getAllByRole('link').map((link) => link.textContent);
    expect(links).toContain('Schedule');
    expect(links).toContain('Check-in');
    expect(links).toContain('Patients');
    expect(links).not.toContain('Facilities');
    expect(within(rail).queryByRole('region', { name: 'Administration' })).toBeNull();
  });

  it('renders the Administration region for a tenant admin', () => {
    renderShell('tenant_admin');
    const rail = screen.getByRole('group', { name: 'Sections' });
    const admin = within(rail).getByRole('region', { name: 'Administration' });
    expect(within(admin).getAllByRole('link').map((link) => link.textContent)).toEqual([
      'Facilities',
      'Staff',
      'Configuration',
      'Audit',
    ]);
  });

  it('renders only the audit and account links for a privacy auditor', () => {
    renderShell('privacy_auditor');
    const rail = screen.getByRole('group', { name: 'Sections' });
    const links = within(rail).getAllByRole('link').map((link) => link.textContent);
    expect(links).toEqual(['Today', 'Audit', 'Sessions']);
  });

  it('mirrors the rail in Arabic using logical layout only', () => {
    renderShell('reception');
    const rail = screen.getByRole('group', { name: 'Sections' });
    expect(rail.getAttribute('style')).not.toMatch(/left:|right:/);
    fireEvent.click(screen.getByRole('button', { name: 'Language' }));
    expect(document.documentElement.dir).toBe('rtl');
    const arabicRail = screen.getByRole('group', { name: 'الأقسام' });
    expect(arabicRail.textContent).toContain('الجدول');
    expect(arabicRail.getAttribute('style')).not.toMatch(/left:|right:/);
  });

  it('defaults to the synthetic reception role when no role is supplied', () => {
    renderShell();
    const rail = screen.getByRole('group', { name: 'Sections' });
    expect(within(rail).getByRole('link', { name: 'Check-in' })).toBeTruthy();
  });
});

/** The six shipped screens that had no screen-map row before this story. */
const FEATURE_NAV_ITEMS: ReadonlyArray<{
  id: string;
  route: string;
  group: NavigationGroup;
  roles: Role[];
}> = [
  { id: 'lab-result-entry', route: '/lab/results/entry', group: 'Specialty', roles: ['lab'] },
  { id: 'lab-release', route: '/lab/release', group: 'Specialty', roles: ['lab'] },
  { id: 'lab-turnaround-board', route: '/lab/turnaround-board', group: 'Specialty', roles: ['lab'] },
  {
    id: 'operations-exports',
    route: '/operations/exports',
    group: 'Operations',
    roles: ['tenant_admin'],
  },
  {
    id: 'operations-imports',
    route: '/operations/imports',
    group: 'Operations',
    roles: ['tenant_admin'],
  },
  {
    id: 'operations-partner-board',
    route: '/operations/partner-board',
    group: 'Operations',
    roles: ['operator'],
  },
];

describe('new feature navigation entries', () => {
  it('registers each shipped screen in its group for its role only', () => {
    for (const expected of FEATURE_NAV_ITEMS) {
      const item = navigation.find((candidate) => candidate.id === expected.id);
      expect(item).toBeDefined();
      expect(item!.route).toBe(expected.route);
      expect(item!.group).toBe(expected.group);
      expect(item!.roles).toEqual(expected.roles);
      for (const role of ALL_ROLES) {
        const visible = navigationFor(role).some((candidate) => candidate.id === expected.id);
        expect(visible).toBe(expected.roles.includes(role));
      }
    }
  });

  it('never makes a new entry all-staff or visible to the privacy auditor on the rail', () => {
    for (const expected of FEATURE_NAV_ITEMS) {
      const item = navigation.find((candidate) => candidate.id === expected.id)!;
      expect(item.roles).not.toContain('staff');
      expect(item.roles).not.toContain('privacy_auditor');
    }
  });
});

describe('rolesForRoute', () => {
  it('returns the navigation roles for an exact pattern without parameters', () => {
    expect(rolesForRoute('/pharmacy')).toEqual(['clinician', 'pharmacy']);
  });

  it('matches an exact pattern that itself contains parameters', () => {
    expect(rolesForRoute('/patients/:id/notes/:noteId')).toEqual(['clinician']);
  });

  it('strips one trailing parameter to reach the parent navigation item', () => {
    expect(rolesForRoute('/pharmacy/:id')).toEqual(['clinician', 'pharmacy']);
  });

  it('strips several trailing parameters to reach the parent navigation item', () => {
    expect(rolesForRoute('/patients/:id/:section/:entryId')).toEqual(['clinician']);
  });

  it('returns the unrestricted sentinel when no navigation item matches', () => {
    expect(rolesForRoute('/no-such/screen')).toBe(UNRESTRICTED_ROUTE);
  });

  it('returns the unrestricted sentinel when a stripped pattern still has no match', () => {
    expect(rolesForRoute('/no-such/screen/:id')).toBe(UNRESTRICTED_ROUTE);
  });

  it('treats an all-staff navigation item as unrestricted', () => {
    expect(rolesForRoute('/')).toBe(UNRESTRICTED_ROUTE);
    expect(rolesForRoute('/account/sessions')).toBe(UNRESTRICTED_ROUTE);
  });

  it('uses the access override for a direct import link without changing the rail', () => {
    expect(rolesForRoute('/operations/imports')).toEqual(['tenant_admin', 'privacy_auditor']);
    expect(navigationFor('privacy_auditor').map((item) => item.id)).not.toContain(
      'operations-imports',
    );
  });

  it('keeps the import override in step with the feature authorization table', () => {
    // A test may import feature code even though navigation.ts must not: the
    // shell grant must never be broader than the import service's own roles.
    expect(rolesForRoute('/operations/imports')).toEqual(IMPORT_PERMITTED_ROLES);
  });

  it('resolves every registered feature route except the fixed system pages', () => {
    // The four system pages are intentionally public; every other shipped
    // screen must resolve to a role set so a new route cannot silently become
    // reachable by everyone when it forgets a navigation row.
    const systemPatterns = new Set(['/signed-out', '/session-expired', '/forbidden', '/not-found']);
    for (const route of collectFeatureRoutes(featureRouteModules)) {
      if (systemPatterns.has(route.pattern)) {
        continue;
      }
      expect(rolesForRoute(route.pattern), route.pattern).not.toBe(UNRESTRICTED_ROUTE);
    }
  });
});
