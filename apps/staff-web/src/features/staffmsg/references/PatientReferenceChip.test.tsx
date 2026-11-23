import type { ReactElement } from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { Locale } from '../../../i18n/messages';
import { I18nProvider } from '../../../i18n/I18nProvider';
import { ThemeProvider } from '../../../theme/ThemeProvider';
import { PatientReferenceChip, referenceText } from './index';

const syntheticLabel = 'Synthetic review label';
const syntheticPatientId = 'synthetic-patient-1';

/** Mount the chip with the providers the staff shell uses. */
function wrap(ui: ReactElement, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

describe('PatientReferenceChip', () => {
  it('renders an allowed chip as an in-app link with an isolated label', () => {
    wrap(
      <PatientReferenceChip
        label={syntheticLabel}
        patientId={syntheticPatientId}
        access="ALLOWED"
      />,
    );

    const link = screen.getByRole('link');
    expect(link.getAttribute('href')).toBe('/patients/synthetic-patient-1');
    const label = screen.getByText(syntheticLabel);
    expect(label.tagName).toBe('BDI');
    expect(label.getAttribute('dir')).toBe('auto');
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it.each(['NO_RELATIONSHIP', 'REVOKED_OR_STALE', 'UNKNOWN_TARGET', 'WRONG_TENANT'] as const)(
    'conceals a %s target',
    (access) => {
      wrap(
        <PatientReferenceChip
          label={syntheticLabel}
          patientId={syntheticPatientId}
          access={access}
        />,
      );

      const alert = screen.getByRole('alert');
      expect(alert.textContent).toContain(referenceText('en', 'blocked'));
      expect(alert.textContent).toContain(referenceText('en', 'blockedDetail'));
      expect(alert.querySelector('[aria-hidden="true"]')).not.toBeNull();
      expect(screen.queryByRole('link')).toBeNull();
      expect(document.querySelector('a[href]')).toBeNull();
      expect(document.body.textContent).not.toContain(syntheticLabel);
      expect(document.body.textContent).not.toContain(syntheticPatientId);
      expect(alert.innerHTML).not.toContain(syntheticPatientId);
    },
  );

  it('renders the blocked state in Arabic under RTL', () => {
    wrap(
      <PatientReferenceChip
        label={syntheticLabel}
        patientId={syntheticPatientId}
        access="NO_RELATIONSHIP"
      />,
      'ar',
    );

    expect(document.documentElement.lang).toBe('ar');
    expect(document.documentElement.dir).toBe('rtl');
    const alert = screen.getByRole('alert');
    expect(alert.textContent).toContain(referenceText('ar', 'blocked'));
    expect(alert.textContent).toContain(referenceText('ar', 'blockedDetail'));
    expect(alert.textContent).not.toContain(referenceText('en', 'blocked'));
    expect(screen.queryByRole('link')).toBeNull();
    expect(document.body.textContent).not.toContain(syntheticLabel);
    expect(document.body.textContent).not.toContain(syntheticPatientId);
  });

  it('isolates a mixed-script allowed label in Arabic', () => {
    const mixedLabel = 'ليلى Haddad';
    wrap(
      <PatientReferenceChip label={mixedLabel} patientId={syntheticPatientId} access="ALLOWED" />,
      'ar',
    );

    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByRole('link').getAttribute('href')).toBe('/patients/synthetic-patient-1');
    const label = screen.getByText(mixedLabel);
    expect(label.tagName).toBe('BDI');
    expect(label.getAttribute('dir')).toBe('auto');
  });
});
