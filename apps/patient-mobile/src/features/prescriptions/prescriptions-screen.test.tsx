/**
 * PAT-02 acceptance: a patient sees each released prescription in plain status
 * words, with no readiness claim unless dispensing evidence exists, in English
 * and Arabic, with no clinical cache to survive sign-out.
 */
import { render } from '@testing-library/react-native';

import { PrescriptionsScreen } from './PrescriptionsScreen';
import { isRtl } from './status';
import type { PrescriptionEvent, ReleasedPrescription } from './types';

function prescription(
  overrides: Partial<ReleasedPrescription> = {},
  ...kinds: PrescriptionEvent['kind'][]
): ReleasedPrescription {
  return {
    prescriptionId: 'rx-1',
    reference: 'SUB-1001',
    medicationLabel: 'Amoxicillin 500 mg',
    language: 'en',
    events: kinds.map((kind) => ({ kind, at: '2026-06-10T09:00:00Z', actor: 'synthetic-pharmacy' })),
    ...overrides,
  };
}

describe('PrescriptionsScreen (PAT-02)', () => {
  it('shows the load error and no empty state while an error is present', async () => {
    const view = await render(
      <PrescriptionsScreen prescriptions={[]} error="Unable to load prescriptions" locale="en" />,
    );
    expect(view.getByLabelText('prescription-error')).toHaveTextContent('Unable to load prescriptions');
    expect(view.queryByLabelText('no-prescriptions')).toBeNull();
  });

  it('shows a localized Arabic load error', async () => {
    const view = await render(
      <PrescriptionsScreen prescriptions={[]} error="تعذّر تحميل الوصفات" locale="ar" />,
    );
    expect(view.getByLabelText('prescription-error')).toHaveTextContent('تعذّر تحميل الوصفات');
  });

  it('shows an empty state when no prescriptions are released', async () => {
    const view = await render(<PrescriptionsScreen prescriptions={[]} error={null} locale="en" />);
    expect(view.getByLabelText('no-prescriptions')).toHaveTextContent('No released prescriptions');
    expect(view.queryByLabelText('prescription-error')).toBeNull();
  });

  it('shows the sent-to-pharmacy word for a submission', async () => {
    const view = await render(
      <PrescriptionsScreen prescriptions={[prescription({}, 'submitted')]} error={null} locale="en" />,
    );
    expect(view.getByTestId('prescriptions-title')).toHaveTextContent('Prescriptions');
    expect(view.getByLabelText('prescription-medication')).toHaveTextContent('Amoxicillin 500 mg');
    expect(view.getByLabelText('prescription-reference')).toHaveTextContent('Reference SUB-1001');
    expect(view.getByTestId('status-rx-1').props.accessibilityLabel).toBe('Sent to pharmacy');
    expect(view.getByTestId('status-rx-1')).toHaveTextContent(/Sent to pharmacy/);
    expect(view.getByLabelText('language')).toHaveTextContent('LTR');
  });

  it('shows the accepted word without implying the medicine is ready', async () => {
    const view = await render(
      <PrescriptionsScreen prescriptions={[prescription({}, 'submitted', 'accepted')]} error={null} locale="en" />,
    );
    expect(view.getByTestId('status-rx-1').props.accessibilityLabel).toBe('Pharmacy accepted');
    expect(view.queryByText(/ready/i)).toBeNull();
    expect(view.queryByText('Handed over')).toBeNull();
  });

  it('shows the needs-to-check word for a clarification', async () => {
    const view = await render(
      <PrescriptionsScreen
        prescriptions={[prescription({}, 'submitted', 'accepted', 'clarificationRequested')]}
        error={null}
        locale="en"
      />,
    );
    expect(view.getByTestId('status-rx-1').props.accessibilityLabel).toBe('Pharmacy needs to check with your doctor');
    expect(view.queryByText('Handed over')).toBeNull();
  });

  it('shows the handed-over word only with dispensing evidence and uses a distinct pill', async () => {
    const view = await render(
      <PrescriptionsScreen
        prescriptions={[prescription({}, 'submitted', 'accepted', 'dispensingEvidence')]}
        error={null}
        locale="en"
      />,
    );
    expect(view.getByTestId('status-rx-1').props.accessibilityLabel).toBe('Handed over');
    expect(view.getByTestId('status-rx-1')).toHaveTextContent(/✓/);
  });

  it('shows Arabic status words, title and right-to-left layout', async () => {
    const view = await render(
      <PrescriptionsScreen
        prescriptions={[prescription({ language: 'ar' }, 'submitted', 'accepted')]}
        error={null}
        locale="ar"
      />,
    );
    expect(view.getByTestId('prescriptions-title')).toHaveTextContent('الوصفات الطبية');
    expect(view.getByTestId('status-rx-1').props.accessibilityLabel).toBe('قبلتها الصيدلية');
    expect(view.getByLabelText('language')).toHaveTextContent('RTL');
    expect(view.getByLabelText('prescription-rx-1').props.style.direction).toBe('rtl');
  });

  it('defaults to the device locale when no locale prop is supplied', async () => {
    const view = await render(<PrescriptionsScreen prescriptions={[]} error={null} />);
    expect(view.getByTestId('prescriptions-title')).toHaveTextContent('Prescriptions');
  });

  it('lays an English prescription out left-to-right', async () => {
    const view = await render(
      <PrescriptionsScreen prescriptions={[prescription({}, 'submitted')]} error={null} locale="en" />,
    );
    expect(isRtl('en')).toBe(false);
    expect(view.getByLabelText('prescription-rx-1').props.style.direction).toBe('ltr');
  });
});
