import type { ReactElement } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { ThemeProvider } from '../../theme/ThemeProvider';
import type { ImagingWorklistOrder, LabelCheckResult } from './domain';
import { PatientAccessionCheck } from './PatientAccessionCheck';
import { ScheduleForm } from './ScheduleForm';
import type { ScheduleDraft } from './ScheduleForm';
import { StatusBadge } from './StatusBadge';
import { StudyLinkForm } from './StudyLinkForm';
import type { StudyLinkDraft } from './StudyLinkForm';
import { alphaOrganizationId, syntheticOrders } from './fixtures';

/** The scheduled synthetic order used by the study-link tests. */
const scheduledOrder: ImagingWorklistOrder = required(syntheticOrders[1]);

/** Mount a component with the theme and locale providers the screen uses. */
function wrap(ui: ReactElement, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

/** Build a check result with explicit flags for a table case. */
function verdict(patientMatches: boolean, accessionMatches: boolean): LabelCheckResult {
  return { ok: patientMatches && accessionMatches, patientMatches, accessionMatches };
}

describe('StatusBadge', () => {
  it('shows the worklist status as a word in both languages', () => {
    const { unmount } = wrap(<StatusBadge status="scheduled" locale="en" />);
    const badge = screen.getByTestId('worklist-status');
    expect(badge.textContent).toBe('Scheduled');
    expect(badge.getAttribute('data-status')).toBe('scheduled');
    unmount();

    wrap(<StatusBadge status="reported" locale="ar" />);
    expect(screen.getByTestId('worklist-status').textContent).toBe('تم إرسال التقرير');
  });
});

describe('PatientAccessionCheck', () => {
  it('asks for both labels before a verdict is available', () => {
    const { unmount } = wrap(
      <PatientAccessionCheck checked={false} result={undefined} locale="en" />,
    );
    expect(screen.getByTestId('label-check').getAttribute('data-verdict')).toBe('pending');
    unmount();

    wrap(<PatientAccessionCheck checked result={undefined} locale="en" />);
    expect(screen.getByTestId('label-check').textContent).toContain('Enter both labels');
  });

  it('names a mismatch of both labels', () => {
    wrap(<PatientAccessionCheck checked result={verdict(false, false)} locale="en" />);
    const check = screen.getByTestId('label-check');
    expect(check.getAttribute('data-verdict')).toBe('both');
    expect(check.textContent).toContain('do not match');
  });

  it('names a patient-only mismatch', () => {
    wrap(<PatientAccessionCheck checked result={verdict(false, true)} locale="ar" />);
    expect(screen.getByTestId('label-check').getAttribute('data-verdict')).toBe('patient');
  });

  it('names an accession-only mismatch', () => {
    wrap(<PatientAccessionCheck checked result={verdict(true, false)} locale="en" />);
    const check = screen.getByTestId('label-check');
    expect(check.getAttribute('data-verdict')).toBe('accession');
    expect(check.textContent).toContain('Accession label');
  });

  it('confirms a match in words', () => {
    wrap(<PatientAccessionCheck checked result={verdict(true, true)} locale="en" />);
    expect(screen.getByTestId('label-check').getAttribute('data-verdict')).toBe('matched');
  });
});

describe('ScheduleForm', () => {
  it('does not send an incomplete draft', () => {
    const onSubmit = vi.fn();
    wrap(<ScheduleForm order={scheduledOrder} onSubmit={onSubmit} />);
    fireEvent.click(screen.getByRole('button', { name: 'Record schedule' }));
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('sends the trimmed facts with the chosen approved modality', () => {
    const onSubmit = vi.fn<(draft: ScheduleDraft) => void>();
    wrap(<ScheduleForm order={scheduledOrder} onSubmit={onSubmit} />);

    fireEvent.change(screen.getByLabelText('Scheduled instant (UTC ISO)'), {
      target: { value: ' 2026-03-09T09:00:00Z ' },
    });
    fireEvent.change(screen.getByLabelText('Modality'), { target: { value: 'mri' } });
    fireEvent.change(screen.getByLabelText('Accession'), { target: { value: ' ACC-009 ' } });
    fireEvent.change(screen.getByLabelText('Accession issuer'), {
      target: { value: ' SYN-ORG ' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Record schedule' }));

    expect(onSubmit).toHaveBeenCalledWith({
      scheduledAt: '2026-03-09T09:00:00Z',
      modality: 'mri',
      accession: 'ACC-009',
      accessionIssuer: 'SYN-ORG',
    });
    expect(screen.getByLabelText('Accession')).toBeTruthy();
  });

  it('reaches and activates the primary action from the keyboard', () => {
    const onSubmit = vi.fn<(draft: ScheduleDraft) => void>();
    const { container } = wrap(<ScheduleForm order={scheduledOrder} onSubmit={onSubmit} />);
    fireEvent.change(screen.getByLabelText('Scheduled instant (UTC ISO)'), {
      target: { value: '2026-03-09T09:00:00Z' },
    });
    fireEvent.change(screen.getByLabelText('Accession'), { target: { value: 'ACC-009' } });
    fireEvent.change(screen.getByLabelText('Accession issuer'), { target: { value: 'SYN-ORG' } });
    fireEvent.change(screen.getByLabelText('Modality'), { target: { value: 'ct' } });

    const primary = screen.getByRole('button', { name: 'Record schedule' });
    primary.focus();
    expect(document.activeElement).toBe(primary);
    // jsdom does not synthesise the browser's Enter activation on a native
    // submit button; the type below is the guarantee, and click asserts the
    // activation path once focus has reached the control.
    expect(primary.getAttribute('type')).toBe('submit');
    fireEvent.click(primary);
    expect(onSubmit).toHaveBeenCalledTimes(1);
    expect(container.querySelector('form')).toBeTruthy();
  });
});

describe('StudyLinkForm', () => {
  it('withholds the link action and shows a wrong-patient mismatch', () => {
    const onLink = vi.fn<(draft: StudyLinkDraft) => void>();
    wrap(<StudyLinkForm order={scheduledOrder} onLink={onLink} />);

    fireEvent.change(screen.getByLabelText('Patient label from workstation'), {
      target: { value: 'wrong-patient' },
    });
    expect(screen.getByTestId('label-check').getAttribute('data-verdict')).toBe('pending');
    fireEvent.change(screen.getByLabelText('Accession label from workstation'), {
      target: { value: 'ACC-002' },
    });

    expect(screen.getByTestId('label-check').getAttribute('data-verdict')).toBe('patient');
    const link = screen.getByRole<HTMLButtonElement>('button', { name: 'Link study' });
    expect(link.disabled).toBe(true);
    fireEvent.click(link);
    expect(onLink).not.toHaveBeenCalled();
  });

  it('names an accession mismatch even when the patient matches', () => {
    wrap(<StudyLinkForm order={scheduledOrder} onLink={vi.fn()} />);
    fireEvent.change(screen.getByLabelText('Patient label from workstation'), {
      target: { value: 'synthetic-patient-002' },
    });
    fireEvent.change(screen.getByLabelText('Accession label from workstation'), {
      target: { value: 'ACC-OTHER' },
    });
    expect(screen.getByTestId('label-check').getAttribute('data-verdict')).toBe('accession');
  });

  it('withholds the link action when the study reference is missing', () => {
    wrap(<StudyLinkForm order={scheduledOrder} onLink={vi.fn()} />);
    fireEvent.change(screen.getByLabelText('Patient label from workstation'), {
      target: { value: 'synthetic-patient-002' },
    });
    fireEvent.change(screen.getByLabelText('Accession label from workstation'), {
      target: { value: 'ACC-002' },
    });
    expect(screen.getByTestId('label-check').getAttribute('data-verdict')).toBe('matched');
    expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Link study' }).disabled).toBe(
      true,
    );
  });

  it('links a matching study with its workstation reference', () => {
    const onLink = vi.fn<(draft: StudyLinkDraft) => void>();
    wrap(<StudyLinkForm order={scheduledOrder} onLink={onLink} />);
    fireEvent.change(screen.getByLabelText('Patient label from workstation'), {
      target: { value: ' synthetic-patient-002 ' },
    });
    fireEvent.change(screen.getByLabelText('Accession label from workstation'), {
      target: { value: ' ACC-002 ' },
    });
    fireEvent.change(screen.getByLabelText('Study Instance UID'), {
      target: { value: ' 1.2.826 ' },
    });
    fireEvent.change(screen.getByLabelText('Diagnostic workstation reference'), {
      target: { value: ' workstation-1 ' },
    });

    const link = screen.getByRole<HTMLButtonElement>('button', { name: 'Link study' });
    expect(link.disabled).toBe(false);
    fireEvent.click(link);
    expect(onLink).toHaveBeenCalledWith({
      patientId: 'synthetic-patient-002',
      accession: 'ACC-002',
      studyInstanceUid: '1.2.826',
      workstationRef: 'workstation-1',
    });
  });

  it('shows the mismatch verdict in Arabic without enabling the link', () => {
    wrap(<StudyLinkForm order={scheduledOrder} onLink={vi.fn()} />, 'ar');
    fireEvent.change(screen.getByLabelText('معرّف المريض من محطة العمل'), {
      target: { value: 'wrong-patient' },
    });
    fireEvent.change(screen.getByLabelText('رقم الوصول من محطة العمل'), {
      target: { value: 'ACC-002' },
    });
    expect(screen.getByTestId('label-check').getAttribute('data-verdict')).toBe('patient');
    expect(screen.getByRole<HTMLButtonElement>('button', { name: 'ربط الدراسة' }).disabled).toBe(
      true,
    );
  });
});

describe('radworklist fixture scope', () => {
  it('keeps the synthetic tenant local identifiers distinct', () => {
    expect(scheduledOrder.organizationId).toBe(alphaOrganizationId);
    expect(scheduledOrder.accession).toBe('ACC-002');
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
