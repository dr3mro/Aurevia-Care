import type { ReactElement } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ActiveOrganizationProvider } from '../../api/activeOrganization';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import { RouterProvider } from '../../app/router';
import { stateViewText } from '../../components/stateViewMessages';
import { I18nProvider } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { ThemeProvider } from '../../theme/ThemeProvider';
import type { Invoice, InvoiceState } from './domain';
import { alphaOrganizationId, syntheticInvoice, syntheticInvoiceView } from './fixtures';
import { financeText } from './messages';
import { formatMoney } from './money';
import { InvoiceDetailPage } from './InvoiceDetailPage';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

function element(locale: Locale = 'en'): ReactElement {
  return (
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ActiveOrganizationProvider value={{ organizationId: alphaOrganizationId }}>
          <RouterProvider patterns={['/finance/:id']} initialPath="/finance/invoice-001">
            <InvoiceDetailPage />
          </RouterProvider>
        </ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>
  );
}

function descriptorAt(call: number): RequestDescriptor {
  return sendMock.mock.calls[call]![0] as RequestDescriptor;
}

const view = () => syntheticInvoiceView();

/** An unposted invoice: no ledger trail, so void is still permitted. */
function emptyView(overrides: Partial<Invoice> = {}) {
  return { invoice: syntheticInvoice(overrides), items: [], entries: [] };
}

function failure(kind: ConstructorParameters<typeof TransportError>[0], correlationId = 'corr-1') {
  return new TransportError(kind, {
    correlationId,
    code: undefined,
    retryAfterSeconds: undefined,
  });
}

const label = (key: Parameters<typeof financeText>[1]): string => financeText('en', key);

function fill(text: string, value: string): void {
  fireEvent.change(screen.getByLabelText(text), { target: { value } });
}

beforeEach(() => {
  sendMock.mockReset();
  sendMock.mockResolvedValue(view());
  vi.spyOn(globalThis.crypto, 'randomUUID').mockReturnValue('00000000-0000-0000-0000-000000000002');
});

describe('InvoiceDetailPage states and content', () => {
  it('shows the loading state until the invoice resolves', async () => {
    let resolve!: (value: ReturnType<typeof view>) => void;
    sendMock.mockReturnValueOnce(new Promise<ReturnType<typeof view>>((done) => { resolve = done; }));

    render(element());
    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();

    resolve(view());
    expect(await screen.findByRole('heading', { name: 'Invoice invoice-001' })).toBeTruthy();
  });

  it('shows the forbidden state when the read is denied', async () => {
    sendMock.mockRejectedValueOnce(failure('forbidden'));

    render(element());
    expect(await screen.findByText(stateViewText('en', 'forbidden'))).toBeTruthy();
  });

  it('renders itemized lines, balance and the linked payment and refund ledger', async () => {
    render(element());

    expect(await screen.findByRole('table', { name: label('lines') })).toBeTruthy();
    expect(screen.getByRole('table', { name: label('ledger') })).toBeTruthy();
    expect(screen.getByText('Synthetic consultation')).toBeTruthy();
    expect(screen.getAllByText(formatMoney(5000, 'USD', 'en')).length).toBeGreaterThan(0);
    expect(screen.getAllByText(formatMoney(2000, 'USD', 'en')).length).toBeGreaterThan(0);
    expect(screen.getByText('Synthetic overpayment')).toBeTruthy();
    expect(screen.getAllByText('synthetic.approver').length).toBeGreaterThan(0);
    expect(descriptorAt(0).url).toBe('/api/v1/finance/invoices/invoice-001');
  });

  it.each([
    ['draft', ['issueInvoice', 'voidForm'], ['paymentForm', 'refundForm', 'creditForm']],
    ['issued', ['paymentForm', 'creditForm', 'voidForm'], ['issueInvoice', 'refundForm']],
    [
      'partially_paid',
      ['paymentForm', 'refundForm', 'creditForm'],
      ['issueInvoice', 'voidForm'],
    ],
    ['paid', ['refundForm'], ['issueInvoice', 'paymentForm', 'creditForm', 'voidForm']],
    ['void', [], ['issueInvoice', 'paymentForm', 'refundForm', 'creditForm', 'voidForm']],
  ] as const)('offers only the %s commands', async (state, present, absent) => {
    sendMock.mockResolvedValueOnce(emptyView({ state: state as InvoiceState, paidMinor: 100 }));

    render(element());
    await screen.findByRole('heading', { name: 'Invoice invoice-001' });

    for (const key of present) {
      expect(
        screen.getByRole(key === 'issueInvoice' ? 'button' : 'form', { name: label(key) }),
      ).toBeTruthy();
    }
    for (const key of absent) {
      expect(
        screen.queryByRole(key === 'issueInvoice' ? 'button' : 'form', { name: label(key) }),
      ).toBeNull();
    }
  });

  it('renders Arabic right to left with localized state text', async () => {
    render(element('ar'));
    expect(await screen.findByRole('table', { name: financeText('ar', 'lines') })).toBeTruthy();
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByTestId('finance-state').textContent).toBe(financeText('ar', 'stateIssued'));
  });
});

describe('InvoiceDetailPage commands', () => {
  it('issues a draft with the current version and reloads after success', async () => {
    sendMock.mockResolvedValueOnce(syntheticInvoiceView({ state: 'draft' }));

    render(element());
    fireEvent.click(await screen.findByRole('button', { name: label('issueInvoice') }));

    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(2));
    expect(descriptorAt(1).method).toBe('POST');
    expect(descriptorAt(1).url).toBe('/api/v1/finance/invoices/invoice-001/issue');
    expect(descriptorAt(1).headers['If-Match']).toBe('1');
    expect(descriptorAt(2).method).toBe('GET');
  });

  it('reuses the payment idempotency key on retries and sends integer minor units', async () => {
    sendMock.mockResolvedValueOnce(view());
    sendMock.mockRejectedValueOnce(failure('offline'));
    sendMock.mockRejectedValueOnce(failure('offline'));
    sendMock.mockResolvedValueOnce(view());
    sendMock.mockResolvedValue(view());

    render(element());
    const form = await screen.findByRole('form', { name: label('paymentForm') });
    fill(label('amountMinor'), '12.34');
    fill(label('paymentReference'), 'synthetic-ref');
    const submit = withinForm(form, label('pay'));

    fireEvent.click(submit);
    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(2));
    fireEvent.click(submit);
    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(3));
    fireEvent.click(submit);
    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(4));

    const posts = [descriptorAt(1), descriptorAt(2), descriptorAt(3)];
    expect(posts.map((descriptor) => descriptor.headers['Idempotency-Key'])).toEqual([
      '00000000-0000-0000-0000-000000000002',
      '00000000-0000-0000-0000-000000000002',
      '00000000-0000-0000-0000-000000000002',
    ]);
    expect(descriptorAt(1).body).toEqual({
      amountMinor: 1234,
      reference: 'synthetic-ref',
      reason: null,
      approverSubject: null,
    });
  });

  it('refuses a refund without a reason before any command is sent', async () => {
    sendMock.mockResolvedValueOnce(syntheticInvoiceView({ state: 'paid' }));

    render(element());
    const form = await screen.findByRole('form', { name: label('refundForm') });
    fill(label('reversalAmount'), '5.00');
    fill(label('reference'), 'synthetic-ref');
    fill(label('reversalApprover'), 'synthetic.approver');
    fireEvent.click(withinForm(form, label('confirmRefund')));

    expect(screen.getByTestId('finance-refund-error').textContent).toBe(label('requiredReason'));
    expect(sendMock).toHaveBeenCalledTimes(1);
  });

  it('does not offer void on an issued invoice that already has a ledger trail', async () => {
    sendMock.mockResolvedValueOnce(syntheticInvoiceView({ state: 'issued' }));

    render(element());
    await screen.findByRole('heading', { name: 'Invoice invoice-001' });

    expect(screen.queryByRole('form', { name: label('voidForm') })).toBeNull();
    expect(screen.getByRole('form', { name: label('paymentForm') })).toBeTruthy();
  });

  it('refuses a void without an independent approver before any command is sent', async () => {
    sendMock.mockResolvedValueOnce(emptyView({ state: 'draft' }));

    render(element());
    const form = await screen.findByRole('form', { name: label('voidForm') });
    fill(label('approvalReason'), 'Synthetic cancellation');
    fireEvent.click(withinForm(form, label('confirmVoid')));

    expect(screen.getByTestId('finance-void-error').textContent).toBe(label('requiredApprover'));
    expect(sendMock).toHaveBeenCalledTimes(1);
  });

  it('posts an approved refund with its reason and approver', async () => {
    sendMock.mockResolvedValueOnce(syntheticInvoiceView({ state: 'paid' }));

    render(element());
    const form = await screen.findByRole('form', { name: label('refundForm') });
    fill(label('reversalAmount'), '5.00');
    fill(label('reference'), 'synthetic-ref');
    fill(label('reversalReason'), 'Synthetic overpayment');
    fill(label('reversalApprover'), 'synthetic.approver');
    fireEvent.click(withinForm(form, label('confirmRefund')));

    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(2));
    expect(descriptorAt(1).url).toBe('/api/v1/finance/invoices/invoice-001/refunds');
    expect(descriptorAt(1).body).toEqual({
      amountMinor: 500,
      reference: 'synthetic-ref',
      reason: 'Synthetic overpayment',
      approverSubject: 'synthetic.approver',
    });
  });

  it('posts an approved credit with its reason and approver', async () => {
    sendMock.mockResolvedValueOnce(syntheticInvoiceView({ state: 'issued' }));

    render(element());
    const form = await screen.findByRole('form', { name: label('creditForm') });
    fill(label('reversalAmount'), '2.50');
    fill(label('reference'), 'synthetic-ref');
    fill(label('reversalReason'), 'Synthetic adjustment');
    fill(label('reversalApprover'), 'synthetic.approver');
    fireEvent.click(withinForm(form, label('confirmCredit')));

    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(2));
    expect(descriptorAt(1).url).toBe('/api/v1/finance/invoices/invoice-001/credits');
    expect(descriptorAt(1).body).toEqual({
      amountMinor: 250,
      reference: 'synthetic-ref',
      reason: 'Synthetic adjustment',
      approverSubject: 'synthetic.approver',
    });
  });

  it('posts a void with its reason and approver', async () => {
    sendMock.mockResolvedValueOnce(emptyView({ state: 'draft' }));

    render(element());
    const form = await screen.findByRole('form', { name: label('voidForm') });
    fill(label('approvalReason'), 'Synthetic cancellation');
    fill(label('voidApprover'), 'synthetic.approver');
    fireEvent.click(withinForm(form, label('confirmVoid')));

    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(2));
    expect(descriptorAt(1).url).toBe('/api/v1/finance/invoices/invoice-001/void');
    expect(descriptorAt(1).body).toEqual({
      reason: 'Synthetic cancellation',
      approverSubject: 'synthetic.approver',
    });
  });

  it('shows the conflict state and keeps the draft when a command is stale', async () => {
    sendMock.mockResolvedValueOnce(syntheticInvoiceView({ state: 'draft' }));
    sendMock.mockRejectedValueOnce(failure('conflict'));

    render(element());
    fireEvent.click(await screen.findByRole('button', { name: label('issueInvoice') }));

    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
    expect(screen.getByTestId('finance-command-error')).toBeTruthy();
  });

  it('normalizes an unexpected rejection to a server error', async () => {
    sendMock.mockResolvedValueOnce(syntheticInvoiceView({ state: 'draft' }));
    sendMock.mockRejectedValueOnce(new Error('unexpected'));

    render(element());
    fireEvent.click(await screen.findByRole('button', { name: label('issueInvoice') }));

    expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
  });
});

/** Find a named button inside one of the finance forms. */
function withinForm(form: HTMLElement, name: string): HTMLElement {
  const button = Array.from(form.querySelectorAll('button')).find(
    (candidate) => candidate.textContent === name,
  );
  if (!button) {
    throw new Error(`button ${name} not found in finance form`);
  }
  return button;
}
