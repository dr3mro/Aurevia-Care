import { useRef, useState } from 'react';
import { useActiveOrganization } from '../../api/activeOrganization';
import type { TransportError } from '../../api/transport';
import { ApiPage } from '../../components/ApiPage';
import { Button } from '../../components/Button';
import { Form } from '../../components/Form';
import { FormField } from '../../components/FormField';
import { MixedScriptText } from '../../components/MixedScriptText';
import { StateView } from '../../components/StateViews';
import { useI18n } from '../../i18n/I18nProvider';
import { patientsMessage, patientsText } from './messages';
import { useTheme } from '../../theme/ThemeProvider';
import {
  asPatientsTransportError,
  registerPatient,
  searchPatients,
} from './api';
import type { PatientResponse } from './domain';
import {
  EMPTY_REGISTRATION,
  searchError,
  serverFieldForCode,
  toPatient,
  validateRegistration,
  validationKeyForField,
} from './domain';
import type {
  RegistrationDraft,
  RegistrationField,
  RegistrationFieldErrors,
  SearchErrorKey,
} from './domain';
import { PatientsScreen } from './PatientsScreen';
import { RegisterPatientForm } from './RegisterPatientForm';

/** Props for the tenant-keyed patient content. */
interface PatientsContentProps {
  organizationId: string;
}

/** Form, commands and results for one active organization. */
function PatientsContent({ organizationId }: PatientsContentProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const [searchInput, setSearchInput] = useState('');
  const [searchIssue, setSearchIssue] = useState<SearchErrorKey | undefined>(undefined);
  const [submitted, setSubmitted] = useState<string | undefined>(undefined);
  const [registerOpen, setRegisterOpen] = useState(false);
  const [draft, setDraft] = useState<RegistrationDraft>(EMPTY_REGISTRATION);
  const [fieldErrors, setFieldErrors] = useState<RegistrationFieldErrors>({});
  const [registerError, setRegisterError] = useState<TransportError | undefined>(undefined);
  const [registeredName, setRegisteredName] = useState<string | undefined>(undefined);
  const [reloadToken, setReloadToken] = useState(0);
  // A synchronous guard: a second rapid submit must not mint a second
  // idempotency key and register a duplicate patient (IAM-03).
  const registering = useRef(false);

  function handleSearch() {
    const issue = searchError(searchInput);
    setSearchIssue(issue);
    if (issue !== undefined) {
      return;
    }
    setSubmitted(searchInput.trim());
  }

  function handleFieldChange(field: RegistrationField, value: string) {
    setDraft((current) => ({ ...current, [field]: value }));
  }

  function handleOpenRegister() {
    setRegisterOpen(true);
    setFieldErrors({});
    setRegisterError(undefined);
    setRegisteredName(undefined);
  }

  function handleCancelRegister() {
    setRegisterOpen(false);
    setFieldErrors({});
    setRegisterError(undefined);
  }

  async function handleRegister() {
    if (registering.current) {
      return;
    }
    registering.current = true;
    try {
      setRegisterError(undefined);
      setRegisteredName(undefined);
      const errors = validateRegistration(draft);
      setFieldErrors(errors);
      if (Object.keys(errors).length > 0) {
        return;
      }
      try {
        const created = await registerPatient(draft, organizationId);
        setDraft(EMPTY_REGISTRATION);
        setFieldErrors({});
        setRegisteredName(created.displayName);
        setRegisterOpen(false);
        setReloadToken((value) => value + 1);
      } catch (failure: unknown) {
        const error = asPatientsTransportError(failure);
        if (error.kind === 'invalid') {
          const field = serverFieldForCode(error.code);
          if (field !== undefined) {
            setFieldErrors({ [field]: validationKeyForField(field) });
          } else {
            setRegisterError(error);
          }
        } else {
          setRegisterError(error);
        }
      }
    } finally {
      registering.current = false;
    }
  }

  const searchErrorText = searchIssue === undefined ? undefined : patientsText(locale, searchIssue);

  return (
    <div style={{ display: 'grid', gap: tokens.spacing.lg }}>
      <header style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <h2>{patientsText(locale, 'patients')}</h2>
        <p>{patientsText(locale, 'syntheticNotice')}</p>
      </header>
      <Form
        label={patientsText(locale, 'searchLabel')}
        submitLabel={patientsText(locale, 'search')}
        onSubmit={handleSearch}
      >
        <FormField
          id="patient-search"
          label={patientsText(locale, 'searchLabel')}
          value={searchInput}
          onChange={setSearchInput}
          {...(searchErrorText === undefined ? {} : { error: searchErrorText })}
        />
      </Form>
      <div>
        <Button onClick={handleOpenRegister}>{patientsText(locale, 'register')}</Button>
      </div>
      <p role="status" data-testid="patients-registration-status">
        <MixedScriptText
          text={
            registeredName === undefined
              ? ''
              : patientsMessage(locale, 'registered', { name: registeredName })
          }
          lang={locale}
        />
      </p>
      {registerError !== undefined && (
        <div data-testid="patients-command-error">{StateView.forTransportError(registerError)}</div>
      )}
      {registerOpen && (
        <RegisterPatientForm
          locale={locale}
          draft={draft}
          errors={fieldErrors}
          onFieldChange={handleFieldChange}
          onSubmit={handleRegister}
          onCancel={handleCancelRegister}
        />
      )}
      <ApiPage
        resource={{
          label: patientsText(locale, 'resultsCaption'),
          // No search has been submitted yet: resolve an empty list locally so
          // no request is sent and no result is written to browser storage.
          load: () =>
            submitted === undefined
              ? Promise.resolve([] as readonly PatientResponse[])
              : searchPatients(submitted, organizationId),
          deps: [submitted, organizationId, reloadToken],
        }}
        isEmpty={(rows) => rows.length === 0}
      >
        {(rows) => (
          <PatientsScreen
            patients={rows.map((row) => toPatient(row, organizationId))}
            locale={locale}
          />
        )}
      </ApiPage>
    </div>
  );
}

/** API-backed patients page; the tenant key prevents cross-tenant state. */
export function PatientsPage() {
  const organizationId = useActiveOrganization();
  return <PatientsContent key={organizationId} organizationId={organizationId} />;
}
