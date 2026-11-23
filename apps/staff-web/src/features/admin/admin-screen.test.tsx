import { useLayoutEffect } from 'react';
import type { ReactElement, ReactNode } from 'react';
import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import type { Locale } from '../../i18n/messages';
import { AdminScreen } from './AdminScreen';
import {
  adminAnchorCivilDate,
  adminConfigurationKey,
  alphaOrganizationId,
  betaOrganizationId,
  syntheticAccessProbes,
  syntheticAuditEvents,
  syntheticConfigurations,
  syntheticStaffEvents,
} from './fixtures';
import { adminText, proposalAnnouncement } from './messages';
import { must } from '../../../tests/must';

function wrap(ui: ReactElement, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

function screenProps(organizationId: string) {
  return {
    organizationId,
    configurations: syntheticConfigurations,
    staffEvents: syntheticStaffEvents,
    auditEvents: syntheticAuditEvents,
    accessProbes: syntheticAccessProbes,
    configKey: adminConfigurationKey,
    subject: 'synthetic.reception',
    facilityCode: 'MAIN',
    anchorCivilDate: adminAnchorCivilDate,
  };
}

function alphaScreen(locale: Locale = 'en') {
  return wrap(<AdminScreen {...screenProps(alphaOrganizationId)} />, locale);
}

describe('AdminScreen', () => {
  it('shows only the active tenant and all three access states', () => {
    alphaScreen();
    expect(screen.getByRole('heading', { level: 2, name: adminText('en', 'admin') })).toBeTruthy();
    expect(screen.getByText('Synthetic alpha revised booking rule')).toBeTruthy();
    expect(screen.queryByText('Synthetic beta revised booking rule')).toBeNull();
    expect(screen.getAllByText('Synthetic alpha admin A').length).toBeGreaterThan(0);
    expect(screen.queryByText('Synthetic beta admin B')).toBeNull();
    expect(screen.getByText('synthetic-correlation-alpha-1')).toBeTruthy();
    expect(screen.queryByText('synthetic-correlation-beta-1')).toBeNull();
    expect(screen.getByText(adminText('en', 'accessPermitted'))).toBeTruthy();
    expect(screen.getByText(adminText('en', 'accessDenied'))).toBeTruthy();
    expect(screen.getByText(adminText('en', 'accessConcealed'))).toBeTruthy();
    expect(screen.getByTestId('admin-status').textContent).toBe('');
  });

  it('keeps each wide table in a labelled, keyboard-scrollable region', () => {
    alphaScreen();
    for (const label of [
      adminText('en', 'configurationVersions'),
      adminText('en', 'staffHistory'),
      adminText('en', 'auditEvents'),
    ]) {
      const region = screen
        .getAllByRole('region', { name: label })
        .find((element) => element.getAttribute('tabindex') === '0');
      expect(region).toBeDefined();
      expect(must(region).style.overflowX).toBe('auto');
      expect(within(must(region)).getByRole('table')).toBeTruthy();
    }
  });

  it('announces a proposal through visible text without a competing static label', () => {
    alphaScreen();
    const status = screen.getByTestId('admin-status');
    expect(status.getAttribute('role')).toBe('status');
    // A static accessible name could mask the dynamic result for screen readers.
    expect(status.hasAttribute('aria-label')).toBe(false);
  });

  it('proposes a local configuration version and announces it', () => {
    alphaScreen();
    fireEvent.change(screen.getByLabelText(adminText('en', 'proposedValue')), {
      target: { value: 'Synthetic proposed value' },
    });
    fireEvent.change(screen.getByLabelText(adminText('en', 'effectiveFrom')), {
      target: { value: '2026-07-01' },
    });
    fireEvent.click(screen.getByRole('button', { name: adminText('en', 'propose') }));
    expect(screen.getByText('Synthetic proposed value')).toBeTruthy();
    expect(screen.getByTestId('admin-status').textContent).toBe(
      proposalAnnouncement('en', 'MAIN', 5),
    );
  });

  it('drops prior-tenant local drafts when the active organization switches', () => {
    const { rerender } = alphaScreen();
    fireEvent.change(screen.getByLabelText(adminText('en', 'proposedValue')), {
      target: { value: 'Synthetic proposed value' },
    });
    fireEvent.change(screen.getByLabelText(adminText('en', 'effectiveFrom')), {
      target: { value: '2026-07-01' },
    });
    fireEvent.click(screen.getByRole('button', { name: adminText('en', 'propose') }));
    expect(screen.getByText('Synthetic proposed value')).toBeTruthy();

    rerender(
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <AdminScreen {...screenProps(betaOrganizationId)} />
        </I18nProvider>
      </ThemeProvider>,
    );
    expect(screen.queryByText('Synthetic proposed value')).toBeNull();
    expect(screen.queryByText('Synthetic alpha revised booking rule')).toBeNull();
    expect(screen.getByText('Synthetic beta revised booking rule')).toBeTruthy();
    expect(screen.getByText(adminText('en', 'chainNotVerified'))).toBeTruthy();
    expect(screen.getByTestId('admin-status').textContent).toBe('');
  });

  it('renders bilingual RTL copy under the Arabic locale', () => {
    alphaScreen('ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByRole('heading', { level: 2, name: adminText('ar', 'admin') })).toBeTruthy();
    expect(screen.getByRole('table', { name: adminText('ar', 'auditEvents') })).toBeTruthy();
    expect(screen.getByText(adminText('ar', 'chainVerified'))).toBeTruthy();
  });

  it('does not carry an unsubmitted configuration draft across an active-organization switch', () => {
    // A draft typed but not proposed is held in the editor's own local state.
    // It must not survive an active-organization switch: the same text could
    // otherwise be proposed into a different tenant, whose local identifiers
    // intentionally collide with the previous tenant's (IAM-01, ARC-01).
    const { rerender } = alphaScreen();
    const value = screen.getByLabelText<HTMLInputElement>(adminText('en', 'proposedValue'));
    const effectiveFrom = screen.getByLabelText<HTMLInputElement>(adminText('en', 'effectiveFrom'));
    fireEvent.change(value, { target: { value: 'Synthetic alpha unsubmitted draft' } });
    fireEvent.change(effectiveFrom, { target: { value: '2026-08-01' } });
    expect(value.value).toBe('Synthetic alpha unsubmitted draft');
    expect(effectiveFrom.value).toBe('2026-08-01');

    rerender(
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <AdminScreen {...screenProps(betaOrganizationId)} />
        </I18nProvider>
      </ThemeProvider>,
    );

    expect(screen.getByLabelText<HTMLInputElement>(adminText('en', 'proposedValue')).value).toBe(
      '',
    );
    expect(screen.getByLabelText<HTMLInputElement>(adminText('en', 'effectiveFrom')).value).toBe(
      '',
    );
    expect(screen.getByTestId('admin-status').textContent).toBe('');
  });

  it('never paints the previous tenant proposal after an active-organization switch', () => {
    // A layout effect reads the committed DOM during the commit phase, before
    // passive effects flush. It therefore observes the first painted frame of
    // a tenant switch, which is where a stale cross-tenant announcement leaks.
    const statusAtCommit: string[] = [];
    function CommitObserver({ children }: { children: ReactNode }) {
      useLayoutEffect(() => {
        statusAtCommit.push(
          document.querySelector('[data-testid="admin-status"]')?.textContent ?? '',
        );
      });
      return children;
    }
    function frame(organizationId: string) {
      return (
        <ThemeProvider>
          <I18nProvider initialLocale="en">
            <CommitObserver>
              <AdminScreen {...screenProps(organizationId)} />
            </CommitObserver>
          </I18nProvider>
        </ThemeProvider>
      );
    }

    const { rerender } = render(frame(alphaOrganizationId));
    fireEvent.change(screen.getByLabelText(adminText('en', 'proposedValue')), {
      target: { value: 'Synthetic proposed value' },
    });
    fireEvent.change(screen.getByLabelText(adminText('en', 'effectiveFrom')), {
      target: { value: '2026-07-01' },
    });
    fireEvent.click(screen.getByRole('button', { name: adminText('en', 'propose') }));
    expect(screen.getByTestId('admin-status').textContent).toBe(
      proposalAnnouncement('en', 'MAIN', 5),
    );

    statusAtCommit.length = 0;
    rerender(frame(betaOrganizationId));

    // The first committed frame under the new tenant must not carry the old
    // tenant's facility code/version in the status live region.
    expect(statusAtCommit[0]).toBe('');
    expect(screen.getByTestId('admin-status').textContent).toBe('');
  });
});
