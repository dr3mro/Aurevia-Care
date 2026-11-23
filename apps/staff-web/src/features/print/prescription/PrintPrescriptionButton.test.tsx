import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '../../../i18n/I18nProvider';
import type { Locale } from '../../../i18n/messages';
import { ThemeProvider } from '../../../theme/ThemeProvider';
import { PrintPrescriptionButton } from './PrintPrescriptionButton';
import type { PrescriptionForPrint, PrescriptionPrintRecord } from './types';

const HANDOUT_URL = 'data:application/pdf;base64,JVBERi0xLjQK';

/** Synthetic attested prescription through an approved electronic channel. */
const allowed: PrescriptionForPrint = {
  id: 'RX-SYN-1',
  version: 4,
  state: 'attested',
  attested: true,
  printChannel: 'E_PRESCRIBE',
  handoutVersion: null,
};

/** Render the button under the providers it needs, in a chosen locale. */
function renderButton(prescription: PrescriptionForPrint, locale: Locale = 'en') {
  const onRecord = vi.fn<(record: PrescriptionPrintRecord) => void>();
  const view = render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <PrintPrescriptionButton
          prescription={prescription}
          handoutUrl={HANDOUT_URL}
          onRecord={onRecord}
        />
      </I18nProvider>
    </ThemeProvider>,
  );
  return { ...view, onRecord };
}

describe('prescription print button eligibility', () => {
  it('is disabled with a worded reason for a draft', () => {
    renderButton({ ...allowed, state: 'draft', attested: false });
    const button = screen.getByRole<HTMLButtonElement>('button', { name: 'Print prescription' });
    expect(button.disabled).toBe(true);
    expect(button.getAttribute('aria-describedby')).toBeTruthy();
    expect(screen.getByText('A draft prescription cannot be printed.')).toBeTruthy();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('is disabled with a worded reason for an unattested prescription', () => {
    renderButton({ ...allowed, attested: false });
    expect(screen.getByText('An unsigned prescription cannot be printed.')).toBeTruthy();
  });

  it('is disabled with a worded reason when the channel is not approved', () => {
    renderButton({ ...allowed, printChannel: null });
    expect(screen.getByText('This prescription has no approved print channel.')).toBeTruthy();
  });

  it('is enabled for an attested prescription with an approved channel', () => {
    renderButton(allowed);
    const button = screen.getByRole<HTMLButtonElement>('button', { name: 'Print prescription' });
    expect(button.disabled).toBe(false);
    fireEvent.click(button);
    const dialog = screen.getByRole('dialog', { name: 'Print preview' });
    expect(dialog).toBeTruthy();
    const frame = screen.getByTitle<HTMLIFrameElement>('Patient copy');
    expect(frame.src).toBe(HANDOUT_URL);
  });

  it('is reachable with the keyboard and activates on click', () => {
    renderButton(allowed);
    const button = screen.getByRole('button', { name: 'Print prescription' });
    button.focus();
    expect(document.activeElement).toBe(button);
    fireEvent.click(button);
    expect(screen.getByRole('dialog')).toBeTruthy();
  });
});

describe('prescription handout copies', () => {
  it('offers only the patient copy for an electronic channel', () => {
    renderButton(allowed);
    expect(screen.queryByRole('group', { name: 'Choose the handout copy' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Print prescription' }));
    expect(screen.getByTitle('Patient copy')).toBeTruthy();
  });

  it('offers a patient and a pharmacy copy for a printable local channel', () => {
    renderButton({ ...allowed, printChannel: 'PRINTABLE_LOCAL' });
    expect(screen.getByRole('group', { name: 'Choose the handout copy' })).toBeTruthy();
    const pharmacy = screen.getByLabelText<HTMLInputElement>('Pharmacy copy');
    fireEvent.click(pharmacy);
    expect(pharmacy.checked).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: 'Print prescription' }));
    expect(screen.getByTitle('Pharmacy copy')).toBeTruthy();
  });
});

describe('prescription print recording', () => {
  it('records the reason, language and patient copy on print', () => {
    const { onRecord } = renderButton(allowed);
    fireEvent.click(screen.getByRole('button', { name: 'Print prescription' }));
    fireEvent.click(screen.getByLabelText('Referral'));
    fireEvent.change(screen.getByLabelText('Handout language'), { target: { value: 'ar' } });
    fireEvent.click(screen.getByRole('button', { name: 'Print' }));
    expect(onRecord).toHaveBeenCalledTimes(1);
    expect(onRecord).toHaveBeenCalledWith({
      prescriptionId: 'RX-SYN-1',
      sourceVersion: 4,
      copy: 'patient',
      language: 'ar',
      reprint: false,
      reason: 'referral',
      action: 'print',
    });
    expect(screen.getByRole('status').textContent).toContain('Print recorded.');
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('records the pharmacy copy when that channel needs it', () => {
    const { onRecord } = renderButton({ ...allowed, printChannel: 'PRINTABLE_LOCAL' });
    fireEvent.click(screen.getByLabelText('Pharmacy copy'));
    fireEvent.click(screen.getByRole('button', { name: 'Print prescription' }));
    fireEvent.click(screen.getByLabelText('Give to patient'));
    fireEvent.click(screen.getByRole('button', { name: 'Print' }));
    expect(onRecord).toHaveBeenCalledTimes(1);
    expect(onRecord).toHaveBeenCalledWith(expect.objectContaining({ copy: 'pharmacy' }));
  });

  it('records a download without a reason', () => {
    const { onRecord } = renderButton(allowed);
    fireEvent.click(screen.getByRole('button', { name: 'Print prescription' }));
    fireEvent.click(screen.getByRole('button', { name: 'Download' }));
    expect(onRecord).toHaveBeenCalledTimes(1);
    expect(onRecord).toHaveBeenCalledWith(
      expect.objectContaining({ action: 'download', reason: null, language: 'en' }),
    );
    expect(screen.getByRole('status').textContent).toContain('Download recorded.');
  });

  it('closes the dialog on the explicit action', () => {
    renderButton(allowed);
    fireEvent.click(screen.getByRole('button', { name: 'Print prescription' }));
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});

describe('prescription reprint', () => {
  it('marks the button and the recorded print as a reprint', () => {
    const { onRecord } = renderButton({ ...allowed, handoutVersion: 2 });
    expect(screen.getByRole('button', { name: 'Reprint prescription' }).textContent).toContain(
      'Reprint prescription',
    );
    expect(
      screen.getByText('This is a reprint — the same handout version will be reused.'),
    ).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Reprint prescription' }));
    fireEvent.click(screen.getByLabelText('Patient request'));
    fireEvent.click(screen.getByRole('button', { name: 'Print' }));
    expect(onRecord).toHaveBeenCalledWith(expect.objectContaining({ reprint: true }));
  });
});

describe('prescription print localization', () => {
  it('shows the disabled reason and the enabled action in Arabic', () => {
    renderButton({ ...allowed, state: 'draft', attested: false }, 'ar');
    expect(document.documentElement.lang).toBe('ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByText('لا يمكن طباعة وصفة مسودة.')).toBeTruthy();
  });

  it('opens the dialog with the Arabic handout title and translated controls', () => {
    renderButton(allowed, 'ar');
    fireEvent.click(screen.getByRole('button', { name: 'طباعة الوصفة' }));
    expect(screen.getByRole('dialog', { name: 'معاينة الطباعة' })).toBeTruthy();
    expect(screen.getByTitle('نسخة المريض')).toBeTruthy();
  });
});
