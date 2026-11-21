import type { FormEvent } from 'react';
import { Button } from '../../components/Button';
import { FormField } from '../../components/FormField';
import { MixedScriptText } from '../../components/MixedScriptText';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import type { AllergyFinding, DraftFields } from './domain';
import { refillsSupported } from './domain';
import type { PrescriptionMessageKey } from './messages';
import { prescriptionText } from './messages';

/** The string-valued draft fields rendered as labeled text inputs. */
type TextField =
  | 'patientId'
  | 'prescriber'
  | 'strength'
  | 'formulation'
  | 'dose'
  | 'route'
  | 'frequency'
  | 'quantity'
  | 'instructions'
  | 'destination';

/** Props for the prescriber-owned draft form. */
export interface DraftFormProps {
  draft: DraftFields;
  findings: readonly AllergyFinding[];
  submitDisabled: boolean;
  onChange: (patch: Partial<DraftFields>) => void;
  onSubmit: () => void;
}

/** Stable field order shared by the form and its tests. */
export const draftTextFields: readonly { id: string; key: PrescriptionMessageKey; field: TextField }[] = [
  { id: 'rx-patient', key: 'patientId', field: 'patientId' },
  { id: 'rx-prescriber', key: 'prescriber', field: 'prescriber' },
  { id: 'rx-strength', key: 'strength', field: 'strength' },
  { id: 'rx-formulation', key: 'formulation', field: 'formulation' },
  { id: 'rx-dose', key: 'dose', field: 'dose' },
  { id: 'rx-route', key: 'route', field: 'route' },
  { id: 'rx-frequency', key: 'frequency', field: 'frequency' },
  { id: 'rx-quantity', key: 'quantity', field: 'quantity' },
  { id: 'rx-instructions', key: 'instructions', field: 'instructions' },
  { id: 'rx-destination', key: 'destination', field: 'destination' },
];

/**
 * Render the prescription draft. The selected medication is shown as record
 * data, not an editable field, because it comes from the approved catalog.
 */
export function DraftForm({
  draft,
  findings,
  submitDisabled,
  onChange,
  onSubmit,
}: DraftFormProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  return (
    <form
      aria-label={prescriptionText(locale, 'draftForm')}
      style={{ display: 'grid', gap: tokens.spacing.md }}
      onSubmit={(event: FormEvent) => {
        event.preventDefault();
        if (!submitDisabled) {
          onSubmit();
        }
      }}
    >
      <h3>{prescriptionText(locale, 'draftForm')}</h3>
      <p>
        {prescriptionText(locale, 'medication')}:{' '}
        <MixedScriptText text={draft.drugCode || prescriptionText(locale, 'notSelected')} lang={locale} />
      </p>
      {draftTextFields.map(({ id, key, field }) => (
        <FormField
          key={id}
          id={id}
          label={prescriptionText(locale, key)}
          value={draft[field]}
          onChange={(value) => onChange({ [field]: value } as Partial<DraftFields>)}
        />
      ))}
      {refillsSupported(draft.channel) ? (
        <FormField
          id="rx-refills"
          label={prescriptionText(locale, 'refills')}
          value={draft.refills}
          onChange={(value) => onChange({ refills: value })}
        />
      ) : (
        <p role="status">{prescriptionText(locale, 'refillsNotSupported')}</p>
      )}
      <fieldset style={{ border: `1px solid ${tokens.border}` }}>
        <legend>{prescriptionText(locale, 'channel')}</legend>
        <label style={{ display: 'flex', gap: tokens.spacing.sm }}>
          <input
            type="radio"
            name="rx-channel"
            value="e-prescribing"
            checked={draft.channel === 'e-prescribing'}
            onChange={() => onChange({ channel: 'e-prescribing' })}
          />
          <span>{prescriptionText(locale, 'channelEPrescribing')}</span>
        </label>
        <label style={{ display: 'flex', gap: tokens.spacing.sm }}>
          <input
            type="radio"
            name="rx-channel"
            value="printable"
            checked={draft.channel === 'printable'}
            onChange={() => onChange({ channel: 'printable' })}
          />
          <span>{prescriptionText(locale, 'channelPrintable')}</span>
        </label>
      </fieldset>
      <label style={{ display: 'flex', gap: tokens.spacing.sm }}>
        <input
          type="checkbox"
          checked={draft.attested}
          onChange={(event) => onChange({ attested: event.target.checked })}
        />
        <span>{prescriptionText(locale, 'attest')}</span>
      </label>
      {findings.length > 0 ? (
        <FormField
          id="rx-override"
          label={prescriptionText(locale, 'overrideReason')}
          value={draft.overrideReason}
          onChange={(value) => onChange({ overrideReason: value })}
        />
      ) : null}
      <Button type="submit" disabled={submitDisabled}>
        {prescriptionText(locale, 'submit')}
      </Button>
    </form>
  );
}
