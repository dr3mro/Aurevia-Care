import { useEffect, useRef, useState } from 'react';
import { Form } from '../../components/Form';
import { FormField } from '../../components/FormField';
import { useI18n } from '../../i18n/I18nProvider';
import { financeText } from './messages';
import { parseMoney } from './money';

/** A validated refund or credit command. */
export type ReversalDraft = {
  amountMinor: number;
  reference: string;
  reason: string;
  approverSubject: string;
};

/** Props for the refund/credit form. */
export interface ReversalFormProps {
  /** Which append-only ledger entry this form records. */
  mode: 'refund' | 'credit';
  /** Invoice currency, which fixes the accepted fraction digits. */
  currency: string;
  /** Return `true` only when the server stored the entry. */
  onSubmit: (draft: ReversalDraft, idempotencyKey: string) => Promise<boolean>;
}

/**
 * Record an approved refund or credit. Unlike a payment, both a reason and an
 * independent approver id are mandatory, matching the API's approval gate. The
 * idempotency key is stable for the lifetime of one draft (FIN-01).
 */
export function ReversalForm({ mode, currency, onSubmit }: ReversalFormProps) {
  const { locale } = useI18n();
  const [amount, setAmount] = useState('');
  const [reference, setReference] = useState('');
  const [reason, setReason] = useState('');
  const [approver, setApprover] = useState('');
  const [error, setError] = useState<string | undefined>(undefined);
  const [idempotencyKey, setIdempotencyKey] = useState(() => crypto.randomUUID());
  const errorRef = useRef<HTMLParagraphElement>(null);

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
    if (cleanReason === '') {
      setError(financeText(locale, 'requiredReason'));
      return;
    }
    const cleanApprover = approver.trim();
    if (cleanApprover === '') {
      setError(financeText(locale, 'requiredApprover'));
      return;
    }
    const stored = await onSubmit(
      {
        amountMinor: parsed.minor,
        reference: cleanReference,
        reason: cleanReason,
        approverSubject: cleanApprover,
      },
      idempotencyKey,
    );
    if (stored) {
      setIdempotencyKey(crypto.randomUUID());
      setAmount('');
      setReference('');
      setReason('');
      setApprover('');
      setError(undefined);
    }
  }

  const titleKey = mode === 'refund' ? 'refundForm' : 'creditForm';
  const submitKey = mode === 'refund' ? 'confirmRefund' : 'confirmCredit';

  return (
    <Form
      label={financeText(locale, titleKey)}
      submitLabel={financeText(locale, submitKey)}
      onSubmit={submit}
    >
      {error !== undefined && (
        <p ref={errorRef} tabIndex={-1} role="alert" data-testid={`finance-${mode}-error`}>
          {error}
        </p>
      )}
      <FormField
        id={`finance-${mode}-amount`}
        label={financeText(locale, 'reversalAmount')}
        value={amount}
        onChange={setAmount}
      />
      <FormField
        id={`finance-${mode}-reference`}
        label={financeText(locale, 'reference')}
        value={reference}
        onChange={setReference}
      />
      <FormField
        id={`finance-${mode}-reason`}
        label={financeText(locale, 'reversalReason')}
        value={reason}
        onChange={setReason}
      />
      <FormField
        id={`finance-${mode}-approver`}
        label={financeText(locale, 'reversalApprover')}
        value={approver}
        onChange={setApprover}
      />
    </Form>
  );
}
