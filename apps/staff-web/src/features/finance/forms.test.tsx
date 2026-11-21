import type { ReactElement } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { financeText } from './messages';
import { PaymentForm } from './PaymentForm';
import type { PaymentDraft } from './PaymentForm';
import { ReversalForm } from './ReversalForm';
import type { ReversalDraft } from './ReversalForm';
import { VoidForm } from './VoidForm';
import type { VoidDraft } from './VoidForm';

function wrap(ui: ReactElement): void {
  render(
    <ThemeProvider>
      <I18nProvider initialLocale="en">{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

function fill(id: string, value: string): void {
  fireEvent.change(screen.getByLabelText(id), { target: { value } });
}

const label = (key: Parameters<typeof financeText>[1]): string => financeText('en', key);

beforeEach(() => {
  vi.spyOn(globalThis.crypto, 'randomUUID').mockReturnValue('00000000-0000-0000-0000-000000000003');
});

describe('PaymentForm', () => {
  it('refuses invalid money text before sending a command', () => {
    const onSubmit = vi.fn<(draft: PaymentDraft, key: string) => Promise<boolean>>();
    wrap(<PaymentForm currency="USD" onSubmit={onSubmit} />);

    fill(label('amountMinor'), 'not-money');
    fireEvent.click(screen.getByRole('button', { name: label('pay') }));

    expect(screen.getByTestId('finance-payment-error').textContent).toBe(label('invalidAmount'));
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('reports a currency-specific decimal error', () => {
    const onSubmit = vi.fn<(draft: PaymentDraft, key: string) => Promise<boolean>>();
    wrap(<PaymentForm currency="USD" onSubmit={onSubmit} />);

    fill(label('amountMinor'), '1.234');
    fireEvent.click(screen.getByRole('button', { name: label('pay') }));

    expect(screen.getByTestId('finance-payment-error').textContent).toBe(
      label('tooManyDecimals'),
    );
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('requires a payment reference', () => {
    const onSubmit = vi.fn<(draft: PaymentDraft, key: string) => Promise<boolean>>();
    wrap(<PaymentForm currency="USD" onSubmit={onSubmit} />);

    fill(label('amountMinor'), '12.34');
    fireEvent.click(screen.getByRole('button', { name: label('pay') }));

    expect(screen.getByTestId('finance-payment-error').textContent).toBe(
      label('requiredReference'),
    );
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('sends integer minor units, a trimmed reference and a null optional reason', async () => {
    const onSubmit = vi.fn<(draft: PaymentDraft, key: string) => Promise<boolean>>();
    onSubmit.mockResolvedValue(true);
    wrap(<PaymentForm currency="USD" onSubmit={onSubmit} />);

    fill(label('amountMinor'), '12.34');
    fill(label('paymentReference'), '  synthetic-ref  ');
    fireEvent.click(screen.getByRole('button', { name: label('pay') }));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit).toHaveBeenCalledWith(
      { amountMinor: 1234, reference: 'synthetic-ref', reason: null },
      '00000000-0000-0000-0000-000000000003',
    );
    expect(screen.queryByTestId('finance-payment-error')).toBeNull();
  });

  it('keeps the typed reason when it is supplied', async () => {
    const onSubmit = vi.fn<(draft: PaymentDraft, key: string) => Promise<boolean>>();
    onSubmit.mockResolvedValue(true);
    wrap(<PaymentForm currency="USD" onSubmit={onSubmit} />);

    fill(label('amountMinor'), '5');
    fill(label('paymentReference'), 'synthetic-ref');
    fill(label('paymentReasonHint'), '  Synthetic note  ');
    fireEvent.click(screen.getByRole('button', { name: label('pay') }));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit.mock.calls[0]![0].reason).toBe('Synthetic note');
  });

  it('reuses the same idempotency key on a failed retry and rotates it for the next draft', async () => {
    vi.spyOn(globalThis.crypto, 'randomUUID')
      .mockReturnValueOnce('00000000-0000-0000-0000-000000000004')
      .mockReturnValueOnce('00000000-0000-0000-0000-000000000005');
    const onSubmit = vi.fn<(draft: PaymentDraft, key: string) => Promise<boolean>>();
    onSubmit
      .mockResolvedValueOnce(false)
      .mockResolvedValueOnce(false)
      .mockResolvedValueOnce(true)
      .mockResolvedValue(false);
    wrap(<PaymentForm currency="USD" onSubmit={onSubmit} />);

    const amount = screen.getByLabelText(label('amountMinor'));
    const reference = screen.getByLabelText(label('paymentReference'));
    const submit = screen.getByRole('button', { name: label('pay') });
    fireEvent.change(amount, { target: { value: '1.00' } });
    fireEvent.change(reference, { target: { value: 'ref' } });

    fireEvent.click(submit);
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    fireEvent.click(submit);
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(2));
    fireEvent.click(submit);
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(3));

    expect(onSubmit.mock.calls.map((call) => call[1])).toEqual([
      '00000000-0000-0000-0000-000000000004',
      '00000000-0000-0000-0000-000000000004',
      '00000000-0000-0000-0000-000000000004',
    ]);

    // A stored payment clears the draft; the next payment gets a fresh key.
    expect((amount as HTMLInputElement).value).toBe('');
    expect((reference as HTMLInputElement).value).toBe('');
    fireEvent.change(amount, { target: { value: '2.00' } });
    fireEvent.change(reference, { target: { value: 'ref-2' } });
    fireEvent.click(submit);
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(4));
    expect(onSubmit.mock.calls[3]![1]).toBe('00000000-0000-0000-0000-000000000005');
  });
});

describe('ReversalForm', () => {
  function renderReversal(mode: 'refund' | 'credit') {
    const onSubmit = vi.fn<(draft: ReversalDraft, key: string) => Promise<boolean>>();
    wrap(<ReversalForm mode={mode} currency="USD" onSubmit={onSubmit} />);
    return onSubmit;
  }

  it('refuses invalid money before sending a command', () => {
    const onSubmit = renderReversal('refund');
    fireEvent.click(screen.getByRole('button', { name: label('confirmRefund') }));
    expect(screen.getByTestId('finance-refund-error').textContent).toBe(label('invalidAmount'));
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('reports too many decimals', () => {
    const onSubmit = renderReversal('refund');
    fill(label('reversalAmount'), '1.234');
    fireEvent.click(screen.getByRole('button', { name: label('confirmRefund') }));
    expect(screen.getByTestId('finance-refund-error').textContent).toBe(
      label('tooManyDecimals'),
    );
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('requires a reference before the reason or approver is checked', () => {
    const onSubmit = renderReversal('refund');
    fill(label('reversalAmount'), '1.00');
    fireEvent.click(screen.getByRole('button', { name: label('confirmRefund') }));
    expect(screen.getByTestId('finance-refund-error').textContent).toBe(
      label('requiredReference'),
    );
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('requires a reason for a refund', () => {
    const onSubmit = renderReversal('refund');
    fill(label('reversalAmount'), '1.00');
    fill(label('reference'), 'synthetic-ref');
    fireEvent.click(screen.getByRole('button', { name: label('confirmRefund') }));
    expect(screen.getByTestId('finance-refund-error').textContent).toBe(label('requiredReason'));
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('requires an independent approver for a credit', () => {
    const onSubmit = renderReversal('credit');
    fill(label('reversalAmount'), '1.00');
    fill(label('reference'), 'synthetic-ref');
    fill(label('reversalReason'), 'Synthetic adjustment');
    fireEvent.click(screen.getByRole('button', { name: label('confirmCredit') }));
    expect(screen.getByTestId('finance-credit-error').textContent).toBe(
      label('requiredApprover'),
    );
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it.each(['refund', 'credit'] as const)(
    'sends the approved %s with its reason and approver',
    async (mode) => {
      const onSubmit = renderReversal(mode);
      onSubmit.mockResolvedValue(true);
      fill(label('reversalAmount'), '2.50');
      fill(label('reference'), 'synthetic-ref');
      fill(label('reversalReason'), 'Synthetic reversal');
      fill(label('reversalApprover'), 'synthetic.approver');
      fireEvent.click(
        screen.getByRole('button', {
          name: mode === 'refund' ? label('confirmRefund') : label('confirmCredit'),
        }),
      );

      await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
      expect(onSubmit.mock.calls[0]![0]).toEqual({
        amountMinor: 250,
        reference: 'synthetic-ref',
        reason: 'Synthetic reversal',
        approverSubject: 'synthetic.approver',
      });
    },
  );

  it('keeps the reversal draft when the server does not store it', async () => {
    const onSubmit = renderReversal('refund');
    onSubmit.mockResolvedValue(false);
    fill(label('reversalAmount'), '2.50');
    fill(label('reference'), 'synthetic-ref');
    fill(label('reversalReason'), 'Synthetic reversal');
    fill(label('reversalApprover'), 'synthetic.approver');
    fireEvent.click(screen.getByRole('button', { name: label('confirmRefund') }));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect((screen.getByLabelText(label('reversalAmount')) as HTMLInputElement).value).toBe('2.50');
  });
});

describe('VoidForm', () => {
  function renderVoid() {
    const onSubmit = vi.fn<(draft: VoidDraft) => Promise<boolean>>();
    wrap(<VoidForm onSubmit={onSubmit} />);
    return onSubmit;
  }

  it('requires a reason', () => {
    const onSubmit = renderVoid();
    fireEvent.click(screen.getByRole('button', { name: label('confirmVoid') }));
    expect(screen.getByTestId('finance-void-error').textContent).toBe(label('requiredReason'));
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('requires an independent approver', () => {
    const onSubmit = renderVoid();
    fill(label('approvalReason'), 'Synthetic cancellation');
    fireEvent.click(screen.getByRole('button', { name: label('confirmVoid') }));
    expect(screen.getByTestId('finance-void-error').textContent).toBe(label('requiredApprover'));
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('sends the reason and approver, and clears the draft after success', async () => {
    const onSubmit = renderVoid();
    onSubmit.mockResolvedValue(true);
    fill(label('approvalReason'), '  Synthetic cancellation  ');
    fill(label('voidApprover'), '  synthetic.approver  ');
    fireEvent.click(screen.getByRole('button', { name: label('confirmVoid') }));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit).toHaveBeenCalledWith({
      reason: 'Synthetic cancellation',
      approverSubject: 'synthetic.approver',
    });
    expect((screen.getByLabelText(label('approvalReason')) as HTMLInputElement).value).toBe('');
  });

  it('keeps the draft when the void is not recorded', async () => {
    const onSubmit = renderVoid();
    onSubmit.mockResolvedValue(false);
    fill(label('approvalReason'), 'Synthetic cancellation');
    fill(label('voidApprover'), 'synthetic.approver');
    fireEvent.click(screen.getByRole('button', { name: label('confirmVoid') }));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect((screen.getByLabelText(label('voidApprover')) as HTMLInputElement).value).toBe(
      'synthetic.approver',
    );
  });
});
