import { useState } from 'react';
import { Button } from '../../components/Button';
import { FormField } from '../../components/FormField';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import type { RejectionDraft } from './domain';
import { rejectionReasonCodes, validateRejection } from './domain';
import { labText, reasonMessageKey } from './messages';

/** Props for the specimen rejection form. */
export interface RejectionFormProps {
  value: RejectionDraft;
  onChange: (next: RejectionDraft) => void;
  onReject: () => void;
  disabled: boolean;
}

/**
 * Take a bounded rejection reason and, for `other`, its required detail. A
 * missing reason or detail is explained and blocks the command locally; the
 * API remains the authority on the reason vocabulary.
 */
export function RejectionForm({ value, onChange, onReject, disabled }: RejectionFormProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const [touched, setTouched] = useState(false);
  const validation = validateRejection(value.reason, value.detail);
  const validationError = validation.valid ? undefined : validation.error;
  const detailError =
    validationError === 'detail-required' ? labText(locale, 'detailRequired') : undefined;
  const blocked = disabled || !validation.valid;

  return (
    <section
      aria-label={labText(locale, 'rejection')}
      style={{ display: 'grid', gap: tokens.spacing.md }}
    >
      <h3>{labText(locale, 'rejection')}</h3>
      <label htmlFor="lab-rejection-reason">{labText(locale, 'rejectionReason')}</label>
      <select
        id="lab-rejection-reason"
        value={value.reason}
        aria-invalid={touched && validationError === 'reason-required' ? true : undefined}
        onChange={(event) => {
          setTouched(true);
          onChange({ ...value, reason: event.target.value });
        }}
      >
        <option value="">—</option>
        {rejectionReasonCodes.map((reason) => (
          <option key={reason} value={reason}>
            {labText(locale, reasonMessageKey[reason])}
          </option>
        ))}
      </select>
      {value.reason === 'other' ? (
        <FormField
          id="lab-rejection-detail"
          label={labText(locale, 'otherDetail')}
          value={value.detail}
          {...(detailError !== undefined ? { error: detailError } : {})}
          onChange={(detail) => {
            setTouched(true);
            onChange({ ...value, detail });
          }}
        />
      ) : null}
      <label>
        <input
          type="checkbox"
          checked={value.notifyPatient}
          onChange={(event) => onChange({ ...value, notifyPatient: event.target.checked })}
        />{' '}
        {labText(locale, 'notifyPatient')}
      </label>
      {touched && validationError === 'reason-required' ? (
        <p role="alert">{labText(locale, 'reasonRequired')}</p>
      ) : null}
      <Button variant="secondary" onClick={onReject} disabled={blocked}>
        {labText(locale, 'rejectSpecimen')}
      </Button>
    </section>
  );
}
