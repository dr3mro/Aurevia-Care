import { useEffect, useRef, useState } from 'react';
import { Form } from '../../components/Form';
import { FormField } from '../../components/FormField';
import { useI18n } from '../../i18n/I18nProvider';
import { financeText } from './messages';
import { parseMoney } from './money';

/** A validated payment command expressed entirely in integer minor units. */
export type PaymentDraft = {
  amountMinor: number;
  reference: string;
  reason: string | null;
};

/** Props for the payment form. */
export interface PaymentFormProps {
  /** Invoice currency, which fixes the accepted fraction digits. */
  currency: string;
  /**
   * Submit the payment with the draft's idempotency key. Return `true` only
   * when the server stored the payment, so a failed retry keeps the same key.
   */
  onSubmit: (draft: PaymentDraft, idempotencyKey: string) => Promise<boolean>;
}

/**
 * Take a payment without ever holding a floating point amount. The idempotency
 * key is generated once per draft and reused on a retry of that same payment;
 * only a stored payment starts a new draft with a new key (FIN-01).
 */
export function PaymentForm({ currency, onSubmit }: PaymentFormProps) {
  const { locale } = useI18n();
  const [amount, setAmount] = useState('');
  const [reference, setReference] = useState('');
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | undefined>(undefined);
  // A lazy initializer runs once per mounted draft. A failed retry keeps this
  // key; only a stored payment replaces it with a fresh one.
  const [idempotencyKey, setIdempotencyKey] = useState(() => crypto.randomUUID());
  const errorRef = useRef<HTMLParagraphElement>(null);

  // One live announcement per failed submit: move focus to the summary that
  // states what to correct, rather than to each field (WCAG 3.3.1).
  useEffect(() => {
    if (error !== undefined) {
      errorRef.current?.focus();
    }
  }, [error]);

  async function submit(): Promise<void> {
    const parsed = parseMoney(amount, currency);
    if (!parsed.ok) {
      setError(
        financeText(
          locale,
          parsed.error === 'too-many-decimals' ? 'tooManyDecimals' : 'invalidAmount',
        ),
      );
      return;
    }
    const cleanReference = reference.trim();
    if (cleanReference === '') {
      setError(financeText(locale, 'requiredReference'));
      return;
    }
    const cleanReason = reason.trim();
    const stored = await onSubmit(
      {
        amountMinor: parsed.minor,
        reference: cleanReference,
        reason: cleanReason === '' ? null : cleanReason,
      },
      idempotencyKey,
    );
    if (stored) {
      setIdempotencyKey(crypto.randomUUID());
      setAmount('');
      setReference('');
      setReason('');
      setError(undefined);
    }
  }

  return (
    <Form
      label={financeText(locale, 'paymentForm')}
      submitLabel={financeText(locale, 'pay')}
      onSubmit={submit}
    >
      {error !== undefined && (
        <p ref={errorRef} tabIndex={-1} role="alert" data-testid="finance-payment-error">
          {error}
        </p>
      )}
      <FormField
        id="finance-payment-amount"
        label={financeText(locale, 'amountMinor')}
        value={amount}
        onChange={setAmount}
      />
      <FormField
        id="finance-payment-reference"
        label={financeText(locale, 'paymentReference')}
        value={reference}
        onChange={setReference}
      />
      <FormField
        id="finance-payment-reason"
        label={financeText(locale, 'paymentReasonHint')}
        value={reason}
        onChange={setReason}
      />
    </Form>
  );
}
