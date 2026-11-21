import type { Locale } from '../../i18n/messages';
import { FieldErrorSummary } from '../admin/FieldErrorSummary';
import type { FieldErrorLink } from '../admin/FieldErrorSummary';
import { Form } from '../../components/Form';
import { FormField } from '../../components/FormField';
import { Button } from '../../components/Button';
import { useTheme } from '../../theme/ThemeProvider';
import type {
  RegistrationDraft,
  RegistrationField,
  RegistrationFieldErrors,
} from './domain';
import { patientsText } from './messages';
import type { PatientsMessageKey } from './messages';

/** Props for the presentational patient registration form. */
export interface RegisterPatientFormProps {
  locale: Locale;
  draft: RegistrationDraft;
  errors: RegistrationFieldErrors;
  onFieldChange: (field: RegistrationField, value: string) => void;
  onSubmit: () => void;
  onCancel: () => void;
}

const FIELD_IDS: Record<RegistrationField, string> = {
  displayName: 'patient-displayName',
  mrn: 'patient-mrn',
  issuer: 'patient-issuer',
  dob: 'patient-dob',
  dobUnknownReason: 'patient-dobUnknownReason',
  language: 'patient-language',
};

const FIELD_LABEL_KEYS: Record<RegistrationField, PatientsMessageKey> = {
  displayName: 'fieldDisplayName',
  mrn: 'fieldMrn',
  issuer: 'fieldIssuer',
  dob: 'fieldDob',
  dobUnknownReason: 'fieldDobReason',
  language: 'fieldLanguage',
};

const FIELD_ORDER: readonly RegistrationField[] = [
  'displayName',
  'mrn',
  'issuer',
  'dob',
  'dobUnknownReason',
  'language',
];

/** Resolve the localized inline error for one field, when present. */
function errorText(
  field: RegistrationField,
  errors: RegistrationFieldErrors,
  locale: Locale,
): string | undefined {
  const key = errors[field];
  return key === undefined ? undefined : patientsText(locale, key);
}

/** Build one summary link per invalid field, in form order. */
export function registrationErrorLinks(
  errors: RegistrationFieldErrors,
  locale: Locale,
): FieldErrorLink[] {
  const links: FieldErrorLink[] = [];
  for (const field of FIELD_ORDER) {
    const key = errors[field];
    if (key !== undefined) {
      links.push({
        id: FIELD_IDS[field],
        label: patientsText(locale, FIELD_LABEL_KEYS[field]),
        message: patientsText(locale, key),
      });
    }
  }
  return links;
}

/**
 * Patient registration form. It never calls the network; the owning page
 * translates submissions into commands and maps server codes back to fields.
 */
export function RegisterPatientForm({
  locale,
  draft,
  errors,
  onFieldChange,
  onSubmit,
  onCancel,
}: RegisterPatientFormProps) {
  const { tokens } = useTheme();
  const displayNameError = errorText('displayName', errors, locale);
  const mrnError = errorText('mrn', errors, locale);
  const issuerError = errorText('issuer', errors, locale);
  const dobError = errorText('dob', errors, locale);
  const reasonError = errorText('dobUnknownReason', errors, locale);
  const languageError = errorText('language', errors, locale);

  return (
    <section
      role="dialog"
      aria-label={patientsText(locale, 'registerTitle')}
      style={{ display: 'grid', gap: tokens.spacing.md }}
    >
      <h3>{patientsText(locale, 'registerTitle')}</h3>
      <p>{patientsText(locale, 'registerIntro')}</p>
      <FieldErrorSummary
        title={patientsText(locale, 'errorSummary')}
        errors={registrationErrorLinks(errors, locale)}
      />
      <Form
        label={patientsText(locale, 'registerTitle')}
        submitLabel={patientsText(locale, 'registerSubmit')}
        onSubmit={onSubmit}
      >
        <div style={{ display: 'grid', gap: tokens.spacing.md }}>
          <FormField
            id={FIELD_IDS.displayName}
            label={patientsText(locale, 'fieldDisplayName')}
            value={draft.displayName}
            onChange={(value) => onFieldChange('displayName', value)}
            {...(displayNameError === undefined ? {} : { error: displayNameError })}
          />
          <FormField
            id={FIELD_IDS.mrn}
            label={patientsText(locale, 'fieldMrn')}
            value={draft.mrn}
            onChange={(value) => onFieldChange('mrn', value)}
            {...(mrnError === undefined ? {} : { error: mrnError })}
          />
          <FormField
            id={FIELD_IDS.issuer}
            label={patientsText(locale, 'fieldIssuer')}
            value={draft.issuer}
            onChange={(value) => onFieldChange('issuer', value)}
            {...(issuerError === undefined ? {} : { error: issuerError })}
          />
          <FormField
            id={FIELD_IDS.dob}
            label={patientsText(locale, 'fieldDob')}
            value={draft.dob}
            onChange={(value) => onFieldChange('dob', value)}
            {...(dobError === undefined ? {} : { error: dobError })}
          />
          <FormField
            id={FIELD_IDS.dobUnknownReason}
            label={patientsText(locale, 'fieldDobReason')}
            value={draft.dobUnknownReason}
            onChange={(value) => onFieldChange('dobUnknownReason', value)}
            {...(reasonError === undefined ? {} : { error: reasonError })}
          />
          <FormField
            id={FIELD_IDS.language}
            label={patientsText(locale, 'fieldLanguage')}
            value={draft.language}
            onChange={(value) => onFieldChange('language', value)}
            {...(languageError === undefined ? {} : { error: languageError })}
          />
        </div>
      </Form>
      <Button variant="secondary" onClick={onCancel}>
        {patientsText(locale, 'cancel')}
      </Button>
    </section>
  );
}
