import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '../../../i18n/I18nProvider';
import type { Locale } from '../../../i18n/messages';
import { ThemeProvider } from '../../../theme/ThemeProvider';
import { PrintSettingsScreen } from './PrintSettingsScreen';
import type { PrintSettingsScreenProps } from './PrintSettingsScreen';
import type { PrintProfileVersion } from './domain';
import {
  anchorCivilDate,
  approvedLogoAssets,
  settingsFacilityCode,
  settingsOrganizationId,
  syntheticVersions,
  unapprovedLogoReference,
} from './fixtures';

const adminRole: PrintSettingsScreenProps['role'] = 'tenant_admin';

/** Render the screen with synthetic fixtures under the providers it needs. */
function renderSettings(overrides: Partial<PrintSettingsScreenProps> = {}, locale: Locale = 'en') {
  const onPropose = vi.fn<(version: PrintProfileVersion) => void>();
  const onApprove = vi.fn<(version: number) => void>();
  const view = render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <PrintSettingsScreen
          organizationId={settingsOrganizationId}
          facilityCode={settingsFacilityCode}
          role={adminRole}
          approvedAssets={approvedLogoAssets}
          versions={syntheticVersions}
          anchorCivilDate={anchorCivilDate}
          onPropose={onPropose}
          onApprove={onApprove}
          {...overrides}
        />
      </I18nProvider>
    </ThemeProvider>,
  );
  return { ...view, onPropose, onApprove };
}

/** Fill the whole form with a valid draft using the first approved logo. */
function fillValidDraft(): void {
  fireEvent.change(screen.getByLabelText('Facility name'), {
    target: { value: 'Synthetic New Clinic' },
  });
  fireEvent.change(screen.getByLabelText('Facility address'), {
    target: { value: '9 Synthetic Way' },
  });
  fireEvent.change(screen.getByLabelText('Approved logo reference'), {
    target: { value: required(approvedLogoAssets[0]).reference },
  });
  fireEvent.change(screen.getByLabelText('Footer text'), { target: { value: 'Synthetic footer' } });
  fireEvent.change(screen.getByLabelText('Effective from (YYYY-MM-DD)'), {
    target: { value: '2027-01-01' },
  });
}

describe('print profile settings for an administrator', () => {
  it('shows the editor, the effective profile and the append-only history', () => {
    renderSettings();
    expect(screen.getByRole('heading', { name: 'Print profile settings' })).toBeTruthy();
    expect(screen.getByLabelText('Facility name')).toBeTruthy();
    expect(screen.getByLabelText('Facility address')).toBeTruthy();
    expect(screen.getByLabelText('Approved logo reference')).toBeTruthy();
    expect(screen.getByLabelText('Footer text')).toBeTruthy();
    expect(screen.getByLabelText('Effective from (YYYY-MM-DD)')).toBeTruthy();
    expect(screen.getByRole('combobox', { name: 'Default handout language' })).toBeTruthy();
    expect(screen.getByRole('combobox', { name: 'Default paper size' })).toBeTruthy();
    expect(screen.getByText('Synthetic Main Clinic v1')).toBeTruthy();
    const table = screen.getByRole('table', { name: 'Version history' });
    expect(within(table).getAllByRole('row')).toHaveLength(3);
  });

  it('conceals other tenants and shows explicit empty states', () => {
    const populated = renderSettings();
    expect(screen.queryByText('Other tenant clinic')).toBeNull();
    populated.unmount();
    renderSettings({ versions: [] });
    expect(
      within(screen.getByRole('table', { name: 'Version history' })).getAllByRole('row'),
    ).toHaveLength(2);
    expect(screen.getByText('No saved versions yet.')).toBeTruthy();
    expect(screen.getByText('No effective profile is in force yet.')).toBeTruthy();
  });

  it('refuses a logo that is not an approved asset', () => {
    const { onPropose } = renderSettings();
    fillValidDraft();
    fireEvent.change(screen.getByLabelText('Approved logo reference'), {
      target: { value: unapprovedLogoReference },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Propose version' }));
    expect(screen.getByTestId('settings-error-summary').textContent).toContain(
      'Choose an approved logo asset.',
    );
    expect(onPropose).not.toHaveBeenCalled();
    expect(
      within(screen.getByRole('table', { name: 'Version history' })).getAllByRole('row'),
    ).toHaveLength(3);
  });

  it('names every missing field without sending a command', () => {
    const { onPropose } = renderSettings();
    fireEvent.click(screen.getByRole('button', { name: 'Propose version' }));
    const summary = screen.getByTestId('settings-error-summary').textContent ?? '';
    for (const message of [
      'Facility name is required.',
      'Facility address is required.',
      'Choose an approved logo asset.',
      'Footer text is required.',
      'Enter a real YYYY-MM-DD date.',
    ]) {
      expect(summary).toContain(message);
    }
    expect(onPropose).not.toHaveBeenCalled();
  });

  it('proposes a version, keeps the prior history and does not make a future version effective', () => {
    const { onPropose } = renderSettings();
    fillValidDraft();
    fireEvent.click(screen.getByRole('button', { name: 'Propose version' }));
    expect(onPropose).toHaveBeenCalledTimes(1);
    expect(onPropose).toHaveBeenCalledWith(
      expect.objectContaining({
        version: 3,
        organizationId: settingsOrganizationId,
        facilityCode: settingsFacilityCode,
        facilityName: 'Synthetic New Clinic',
        approved: false,
      }),
    );
    expect(
      within(screen.getByRole('table', { name: 'Version history' })).getAllByRole('row'),
    ).toHaveLength(4);
    expect(screen.getByTestId('print-settings-status').textContent).toContain(
      'Proposed print profile version 3.',
    );
    expect(screen.getByText('Synthetic Main Clinic v1')).toBeTruthy();
  });

  it('approves a pending version and makes it effective', () => {
    const { onApprove } = renderSettings();
    fireEvent.click(screen.getByRole('button', { name: 'Approve' }));
    expect(onApprove).toHaveBeenCalledWith(2);
    expect(screen.getByTestId('print-settings-status').textContent).toContain(
      'Approved version 2.',
    );
    expect(screen.getByText('Synthetic Main Clinic v2')).toBeTruthy();
    expect(screen.getAllByText('Synthetic administrator').length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: 'Approve' })).toBeNull();
  });

  it('saves the chosen handout language and paper size', () => {
    const { onPropose } = renderSettings();
    fillValidDraft();
    fireEvent.change(screen.getByRole('combobox', { name: 'Default handout language' }), {
      target: { value: 'ar' },
    });
    fireEvent.change(screen.getByRole('combobox', { name: 'Default paper size' }), {
      target: { value: 'LETTER' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Propose version' }));
    expect(onPropose).toHaveBeenCalledWith(
      expect.objectContaining({ defaultLanguage: 'ar', paperSize: 'LETTER' }),
    );
  });

  it('submits through the form so the primary action works with the keyboard', () => {
    const { onPropose } = renderSettings();
    fillValidDraft();
    fireEvent.submit(screen.getByRole('form', { name: 'Edit print profile' }));
    expect(onPropose).toHaveBeenCalledTimes(1);
  });
});

describe('print profile settings access control', () => {
  it.each(['reception', 'privacy_auditor'] as const)('forbids the %s role', (role) => {
    const { onPropose, onApprove } = renderSettings({ role });
    expect(screen.getByRole('alert').textContent).toContain('You do not have access');
    expect(screen.queryByLabelText('Facility name')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Approve' })).toBeNull();
    expect(onPropose).not.toHaveBeenCalled();
    expect(onApprove).not.toHaveBeenCalled();
  });
});

describe('print profile settings localization', () => {
  it('renders in Arabic right-to-left and translates the validation', () => {
    const { onPropose } = renderSettings({}, 'ar');
    expect(document.documentElement.lang).toBe('ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByRole('heading', { name: 'إعدادات طباعة المنشآت' })).toBeTruthy();
    expect(screen.getByLabelText('اسم المنشأة')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'اقتراح إصدار' }));
    expect(screen.getByTestId('settings-error-summary').textContent).toContain(
      'اختر شعاراً من الأصول المعتمدة.',
    );
    expect(onPropose).not.toHaveBeenCalled();
  });

  it('accepts a valid Arabic draft and announces the proposal in Arabic', () => {
    const { onPropose } = renderSettings({}, 'ar');
    fireEvent.change(screen.getByLabelText('اسم المنشأة'), { target: { value: 'عيادة تركيبية' } });
    fireEvent.change(screen.getByLabelText('عنوان المنشأة'), {
      target: { value: 'طريق تركيبي ٩' },
    });
    fireEvent.change(screen.getByLabelText('مرجع الشعار المعتمد'), {
      target: { value: required(approvedLogoAssets[1]).reference },
    });
    fireEvent.change(screen.getByLabelText('نص التذييل'), { target: { value: 'تذييل تركيبي' } });
    fireEvent.change(screen.getByLabelText('سريان من (YYYY-MM-DD)'), {
      target: { value: '2027-01-01' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'اقتراح إصدار' }));
    expect(onPropose).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('print-settings-status').textContent).toContain('3');
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
