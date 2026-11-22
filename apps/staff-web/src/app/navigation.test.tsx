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
} from './navigation';
import type { Role } from './navigation';
import { I18nProvider } from '../i18n/I18nProvider';
import { ThemeProvider } from '../theme/ThemeProvider';

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

  it('gives the privacy auditor the audit and onboarding import items plus the all-staff items', () => {
    // "Today" and "Account" are the table's "all staff" rows, visible to
    // every role; among role-restricted items the auditor sees audit and the
    // onboarding import screen (OPS-08), never the tenant export screen.
    expect(idsFor('privacy_auditor')).toEqual([
      'home',
      'admin-audit',
      'operations-imports',
      'account-sessions',
    ]);
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

  it('renders only the audit, import and account links for a privacy auditor', () => {
    renderShell('privacy_auditor');
    const rail = screen.getByRole('group', { name: 'Sections' });
    const links = within(rail).getAllByRole('link').map((link) => link.textContent);
    expect(links).toEqual(['Today', 'Audit', 'Onboarding import', 'Sessions']);
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
