import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { I18nProvider } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { ThemeProvider } from '../../theme/ThemeProvider';
import type { StudyLinkCheck } from './domain';
import {
  addendedOrder,
  receivedOrder,
  scheduledOrder,
  syntheticAddendum,
} from './fixtures';
import { OrderDetailScreen } from './OrderDetailScreen';
import { OrdersScreen } from './OrdersScreen';
import { radiologyText } from './messages';

function wrap(ui: React.ReactNode, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

const noop = () => undefined;

function renderDetail(
  order: typeof receivedOrder,
  locale: Locale = 'en',
  mismatch: StudyLinkCheck | undefined = undefined,
  overrides: {
    onSchedule?: (input: unknown) => void;
    onLinkStudy?: (study: unknown) => void;
    onSign?: (input: unknown) => void;
    onAddendum?: (text: string) => void;
  } = {},
) {
  wrap(
    <OrderDetailScreen
      order={order}
      locale={locale}
      mismatch={mismatch}
      onSchedule={overrides.onSchedule ?? noop}
      onLinkStudy={overrides.onLinkStudy ?? noop}
      onSign={overrides.onSign ?? noop}
      onAddendum={overrides.onAddendum ?? noop}
    />,
    locale,
  );
}

describe('OrdersScreen', () => {
  it('renders values for a scheduled order and dashes for a received order', () => {
    wrap(<OrdersScreen orders={[receivedOrder, scheduledOrder]} locale="en" />);

    expect(screen.getByRole('table')).toBeTruthy();
    expect(screen.getAllByText('Layla Haddad')).toHaveLength(2);
    expect(screen.getAllByText('—').length).toBeGreaterThan(0);
    expect(screen.getByText('ACC-2026-0001')).toBeTruthy();
    expect(screen.getByText('March 8, 2026')).toBeTruthy();
    expect(screen.getAllByRole('link', { name: 'Order detail' })).toHaveLength(2);
  });

  it('renders Arabic labels and a real detail link under RTL', () => {
    wrap(<OrdersScreen orders={[receivedOrder]} locale="ar" />, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByText('طلبات التصوير')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'تفاصيل الطلب' }).getAttribute('href')).toBe(
      '/imaging/orders/imaging-order-a1',
    );
  });
});

describe('OrderDetailScreen workflow forms', () => {
  it('submits the typed schedule input through the callback', () => {
    const onSchedule = vi.fn();
    renderDetail(receivedOrder, 'en', undefined, { onSchedule });

    fireEvent.change(screen.getByLabelText('Accession number'), {
      target: { value: 'ACC-9' },
    });
    fireEvent.change(screen.getByLabelText('Modality'), { target: { value: 'MRI' } });
    fireEvent.change(screen.getByLabelText('Scheduled instant (UTC ISO)'), {
      target: { value: '2026-03-09T08:00:00Z' },
    });
    fireEvent.submit(screen.getByRole('form', { name: 'Schedule the examination' }));

    expect(onSchedule).toHaveBeenCalledWith({
      accessionNo: 'ACC-9',
      modality: 'MRI',
      scheduledAt: '2026-03-09T08:00:00Z',
    });
  });

  it('refuses an unsupported modality before reaching the API', () => {
    const onSchedule = vi.fn();
    renderDetail(receivedOrder, 'en', undefined, { onSchedule });

    fireEvent.change(screen.getByLabelText('Modality'), {
      target: { value: 'ULTRASOUND' },
    });
    fireEvent.submit(screen.getByRole('form', { name: 'Schedule the examination' }));

    expect(screen.getByRole('alert').textContent).toContain('Enter a supported modality');
    expect(onSchedule).not.toHaveBeenCalled();
  });

  it('submits the typed study link and interpretation inputs through their callbacks', () => {
    const onLinkStudy = vi.fn();
    const onSign = vi.fn();
    renderDetail(scheduledOrder, 'en', undefined, { onLinkStudy, onSign });

    fireEvent.change(screen.getByLabelText('Study Instance UID'), {
      target: { value: '1.2.3' },
    });
    fireEvent.change(screen.getByLabelText('Study patient identifier'), {
      target: { value: 'patient-a1' },
    });
    fireEvent.change(screen.getByLabelText('Study accession number'), {
      target: { value: 'ACC-2026-0001' },
    });
    fireEvent.submit(screen.getByRole('form', { name: 'Link the external study' }));
    expect(onLinkStudy).toHaveBeenCalledWith({
      studyInstanceUid: '1.2.3',
      patientId: 'patient-a1',
      accessionNo: 'ACC-2026-0001',
    });

    for (const label of ['Technique', 'Comparison', 'Findings', 'Impression', 'Follow-up']) {
      fireEvent.change(screen.getByLabelText(label), { target: { value: label } });
    }
    fireEvent.submit(screen.getByRole('form', { name: 'Sign the interpretation' }));
    expect(onSign).toHaveBeenCalledWith({
      technique: 'Technique',
      comparison: 'Comparison',
      findings: 'Findings',
      impression: 'Impression',
      followUp: 'Follow-up',
    });
  });

  it('disables commands that the current state forbids', () => {
    renderDetail(receivedOrder);
    expect(
      (screen.getByRole('button', { name: 'Link study' }) as HTMLButtonElement).disabled,
    ).toBe(true);
    expect(
      (screen.getByRole('button', { name: 'Sign interpretation' }) as HTMLButtonElement).disabled,
    ).toBe(true);
    expect(
      (screen.getByRole('button', { name: 'Schedule' }) as HTMLButtonElement).disabled,
    ).toBe(false);
  });
});

describe('OrderDetailScreen study mismatch', () => {
  const mismatchCheck = (patientMismatch: boolean, accessionMismatch: boolean): StudyLinkCheck => ({
    ok: false,
    patientMismatch,
    accessionMismatch,
  });

  it('shows no alert when there is no mismatch result', () => {
    renderDetail(scheduledOrder);
    expect(screen.queryByRole('alert', { name: 'Study association refused' })).toBeNull();
  });

  it('shows no alert for a successful check', () => {
    renderDetail(scheduledOrder, 'en', { ok: true });
    expect(screen.queryByRole('alert', { name: 'Study association refused' })).toBeNull();
  });

  it('shows the patient-mismatch explanation', () => {
    renderDetail(scheduledOrder, 'en', mismatchCheck(true, false));
    const alert = screen.getByRole('alert', { name: 'Study association refused' });
    expect(within(alert).getByText('The study patient does not match this order.')).toBeTruthy();
    expect(within(alert).queryByText('The study accession does not match this order.')).toBeNull();
  });

  it('shows the accession-mismatch explanation', () => {
    renderDetail(scheduledOrder, 'en', mismatchCheck(false, true));
    const alert = screen.getByRole('alert', { name: 'Study association refused' });
    expect(within(alert).getByText('The study accession does not match this order.')).toBeTruthy();
    expect(within(alert).queryByText('The study patient does not match this order.')).toBeNull();
  });
});

describe('OrderDetailScreen signed report and referrer view', () => {
  it('shows no referrer view before an interpretation is signed', () => {
    renderDetail(receivedOrder);
    expect(screen.getByText('No signed interpretation is available for the referrer yet.')).toBeTruthy();
    expect(screen.queryByRole('region', { name: 'Signed interpretation' })).toBeNull();
  });

  it('carries the addendum into both the signed panel and the referrer view', () => {
    renderDetail(addendedOrder);
    const signed = screen.getByRole('region', { name: 'Signed interpretation' });
    expect(within(signed).getAllByText(syntheticAddendum.text)).toHaveLength(1);
    const referrer = screen.getByRole('region', { name: 'Referrer view' });
    expect(within(referrer).getByText('No acute cardiopulmonary abnormality.')).toBeTruthy();
    expect(within(referrer).getByText(syntheticAddendum.text)).toBeTruthy();
  });

  it('submits an addendum through the callback', () => {
    const onAddendum = vi.fn();
    renderDetail(addendedOrder, 'en', undefined, { onAddendum });
    fireEvent.change(screen.getByLabelText('Addendum text'), {
      target: { value: 'Synthetic second addendum' },
    });
    fireEvent.submit(screen.getByRole('form', { name: 'Add addendum' }));
    expect(onAddendum).toHaveBeenCalledWith('Synthetic second addendum');
  });

  it('renders the signed report and referrer view in Arabic under RTL', () => {
    renderDetail(addendedOrder, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByRole('region', { name: radiologyText('ar', 'referrerView') })).toBeTruthy();
    expect(screen.getAllByText(syntheticAddendum.text)).toHaveLength(2);
  });
});
