import { useEffect, useRef, useState } from 'react';
import { Form } from '../../components/Form';
import { FormField } from '../../components/FormField';
import { useI18n } from '../../i18n/I18nProvider';
import { financeText } from './messages';

/** A validated void command with an independent approver. */
export type VoidDraft = { reason: string; approverSubject: string };

/** Props for the void form. */
export interface VoidFormProps {
  /** Return `true` only when the server recorded the void. */
  onSubmit: (draft: VoidDraft) => Promise<boolean>;
}

/**
 * Void an unposted invoice. Destructive actions name the consequence and
 * require both a reason and a different approver subject, matching the API's
 * approval invariant (FIN-01, WCAG 3.3.4).
 */
export function VoidForm({ onSubmit }: VoidFormProps) {
  const { locale } = useI18n();
  const [reason, setReason] = useState('');
  const [approver, setApprover] = useState('');
  const [error, setError] = useState<string | undefined>(undefined);
  const errorRef = useRef<HTMLParagraphElement>(null);

  useEffect(() => {
    if (error !== undefined) {
      errorRef.current?.focus();
    }
  }, [error]);

  async function submit(): Promise<void> {
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
    const stored = await onSubmit({ reason: cleanReason, approverSubject: cleanApprover });
    if (stored) {
      setReason('');
      setApprover('');
      setError(undefined);
    }
  }

  return (
    <Form
      label={financeText(locale, 'voidForm')}
      submitLabel={financeText(locale, 'confirmVoid')}
      onSubmit={submit}
    >
      {error !== undefined && (
        <p ref={errorRef} tabIndex={-1} role="alert" data-testid="finance-void-error">
          {error}
        </p>
      )}
      <FormField
        id="finance-void-reason"
        label={financeText(locale, 'approvalReason')}
        value={reason}
        onChange={setReason}
      />
      <FormField
        id="finance-void-approver"
        label={financeText(locale, 'voidApprover')}
        value={approver}
        onChange={setApprover}
      />
    </Form>
  );
}
