import { useEffect, useState } from 'react';
import { useActiveOrganization } from '../../api/activeOrganization';
import type { TransportError } from '../../api/transport';
import { useApi } from '../../api/useApi';
import { useRoute } from '../../app/router';
import { Button } from '../../components/Button';
import { FormField } from '../../components/FormField';
import { MixedScriptText } from '../../components/MixedScriptText';
import { LoadingState, StateView } from '../../components/StateViews';
import { formatInstant } from '../../i18n/format';
import { useI18n } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import {
  acceptPrescription,
  askClarification,
  asPharmacyTransportError,
  getPharmacyStatus,
  getPrescription,
  recordDispensing,
  rejectPrescription,
} from './api';
import { dosingInstructions, hasReason } from './domain';
import { pharmacyText, stateMessageKey } from './labels';
import type { InboxItemResponse, PharmacyDetail, StatusEventResponse } from './types';

/** Normalize a command rejection to the typed error the state views present. */
function toTransportError(failure: unknown): TransportError {
  return asPharmacyTransportError(failure);
}

/** Presentational card with the bounded clinical snapshot the pharmacy may see. */
function PrescriptionCard({ item, locale }: { item: InboxItemResponse; locale: Locale }) {
  const { tokens } = useTheme();
  return (
    <section
      aria-label={pharmacyText(locale, 'detailTitle')}
      style={{ display: 'grid', gap: tokens.spacing.md }}
    >
      <h3>{pharmacyText(locale, 'detailTitle')}</h3>
      <dl
        style={{ display: 'grid', gridTemplateColumns: 'max-content 1fr', gap: tokens.spacing.sm }}
      >
        <dt>{pharmacyText(locale, 'patient')}</dt>
        <dd>
          <MixedScriptText text={item.patientId} lang="en" />
        </dd>
        <dt>{pharmacyText(locale, 'medication')}</dt>
        <dd>
          <MixedScriptText text={item.medication} lang="en" />
        </dd>
        <dt>{pharmacyText(locale, 'dose')}</dt>
        <dd>{item.dose}</dd>
        <dt>{pharmacyText(locale, 'quantity')}</dt>
        <dd>{item.quantity}</dd>
        <dt>{pharmacyText(locale, 'instructions')}</dt>
        <dd>
          <MixedScriptText text={dosingInstructions(item)} lang="en" />
        </dd>
        <dt>{pharmacyText(locale, 'prescriber')}</dt>
        <dd>
          <MixedScriptText text={item.prescriberSubject} lang="en" />
        </dd>
        <dt>{pharmacyText(locale, 'submission')}</dt>
        <dd>
          <code dir="ltr">{item.submissionId}</code>
        </dd>
        <dt>{pharmacyText(locale, 'state')}</dt>
        <dd>{pharmacyText(locale, stateMessageKey(item.state))}</dd>
      </dl>
    </section>
  );
}

/** Props for the command workflow on one loaded prescription. */
interface PharmacyWorkflowProps {
  detail: PharmacyDetail;
  organizationId: string;
  onDone: (message: string) => void;
  onCommitted: () => void;
}

/** Append-only status events of one prescription. */
function EventsSection({
  events,
  locale,
}: {
  events: readonly StatusEventResponse[];
  locale: Locale;
}) {
  return (
    <section aria-label={pharmacyText(locale, 'eventsTitle')}>
      <h3>{pharmacyText(locale, 'eventsTitle')}</h3>
      {events.length === 0 ? (
        <p>{pharmacyText(locale, 'noEvents')}</p>
      ) : (
        <ul>
          {events.map((event) => (
            <li key={event.id}>
              <code dir="ltr">{event.type}</code>{' '}
              <MixedScriptText text={event.detail ?? ''} lang="en" />{' '}
              <time dateTime={event.occurredAt}>
                {formatInstant(event.occurredAt, 'UTC', locale)}
              </time>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/**
 * Owns the four pharmacy commands. Reject and clarify validate a reason before
 * sending; dispensing evidence lives on its own step and never sets acceptance.
 * A failed command keeps every field the user typed, including on a conflict.
 */
function PharmacyWorkflow({ detail, organizationId, onDone, onCommitted }: PharmacyWorkflowProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const { item, status } = detail;
  const [step, setStep] = useState<'review' | 'dispensing'>('review');
  const [reason, setReason] = useState('');
  const [question, setQuestion] = useState('');
  const [dispensedBy, setDispensedBy] = useState('');
  const [dispensedAt, setDispensedAt] = useState('');
  const [handedOver, setHandedOver] = useState('');
  const [reasonError, setReasonError] = useState(false);
  const [questionError, setQuestionError] = useState(false);
  const [commandError, setCommandError] = useState<TransportError | undefined>(undefined);

  // Any typed command text is unsaved work: ask before the browser leaves.
  const dirty = [reason, question, dispensedBy, dispensedAt, handedOver].some(
    (value) => value.length > 0,
  );
  useEffect(() => {
    if (!dirty) {
      return undefined;
    }
    const warn = (event: BeforeUnloadEvent): void => {
      event.preventDefault();
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty]);

  async function run(command: () => Promise<unknown>) {
    try {
      await command();
      setCommandError(undefined);
      onDone(pharmacyText(locale, 'commandDone'));
      onCommitted();
    } catch (failure: unknown) {
      setCommandError(toTransportError(failure));
    }
  }

  function handleAccept() {
    void run(() => acceptPrescription(item.id, status.version, organizationId));
  }

  function handleReject() {
    if (!hasReason(reason)) {
      setReasonError(true);
      return;
    }
    setReasonError(false);
    void run(() => rejectPrescription(item.id, status.version, reason, organizationId));
  }

  function handleClarify() {
    if (!hasReason(question)) {
      setQuestionError(true);
      return;
    }
    setQuestionError(false);
    void run(() => askClarification(item.id, status.version, question, organizationId));
  }

  function handleDispense() {
    void run(() =>
      recordDispensing(
        item.id,
        status.version,
        { dispensedBy, dispensedAt, handedOver },
        organizationId,
      ),
    );
  }

  return (
    <div style={{ display: 'grid', gap: tokens.spacing.lg }}>
      <header style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <h2>{pharmacyText(locale, 'detailTitle')}</h2>
        <p>{pharmacyText(locale, 'syntheticNotice')}</p>
      </header>
      <PrescriptionCard item={item} locale={locale} />
      <section aria-label={pharmacyText(locale, 'acceptedFlag')}>
        <p data-testid="accepted-flag">
          {pharmacyText(locale, 'acceptedFlag')}:{' '}
          {pharmacyText(locale, status.accepted ? 'flagYes' : 'flagNo')}
        </p>
        <p data-testid="dispensed-flag">
          {pharmacyText(locale, 'dispensedFlag')}:{' '}
          {pharmacyText(locale, status.dispensed ? 'flagYes' : 'flagNo')}
        </p>
      </section>
      <EventsSection events={status.events} locale={locale} />
      {commandError !== undefined ? <div>{StateView.forTransportError(commandError)}</div> : null}
      {step === 'dispensing' ? (
        <section aria-label={pharmacyText(locale, 'dispensingTitle')}>
          <h3>{pharmacyText(locale, 'dispensingTitle')}</h3>
          <p>{pharmacyText(locale, 'dispensingHint')}</p>
          <FormField
            id="dispensed-by"
            label={pharmacyText(locale, 'dispensedBy')}
            value={dispensedBy}
            onChange={setDispensedBy}
          />
          <FormField
            id="dispensed-at"
            label={pharmacyText(locale, 'dispensedAt')}
            value={dispensedAt}
            onChange={setDispensedAt}
          />
          <FormField
            id="handed-over"
            label={pharmacyText(locale, 'handedOver')}
            value={handedOver}
            onChange={setHandedOver}
          />
          <Button onClick={handleDispense}>{pharmacyText(locale, 'recordDispensing')}</Button>{' '}
          <Button variant="secondary" onClick={() => setStep('review')}>
            {pharmacyText(locale, 'backToReview')}
          </Button>
        </section>
      ) : (
        <section aria-label={pharmacyText(locale, 'reviewStep')}>
          <h3>{pharmacyText(locale, 'reviewStep')}</h3>
          <Button onClick={handleAccept}>{pharmacyText(locale, 'accept')}</Button>
          <FormField
            id="reject-reason"
            label={pharmacyText(locale, 'reasonLabel')}
            value={reason}
            onChange={(value) => {
              setReason(value);
              setReasonError(false);
            }}
            {...(reasonError ? { error: pharmacyText(locale, 'reasonRequired') } : {})}
          />
          <Button onClick={handleReject}>{pharmacyText(locale, 'submitReject')}</Button>
          <FormField
            id="clarify-question"
            label={pharmacyText(locale, 'questionLabel')}
            value={question}
            onChange={(value) => {
              setQuestion(value);
              setQuestionError(false);
            }}
            {...(questionError ? { error: pharmacyText(locale, 'questionRequired') } : {})}
          />
          <Button onClick={handleClarify}>{pharmacyText(locale, 'submitClarify')}</Button>{' '}
          <Button variant="secondary" onClick={() => setStep('dispensing')}>
            {pharmacyText(locale, 'beginDispensing')}
          </Button>
        </section>
      )}
    </div>
  );
}

/** Detail for `/pharmacy/:id`; only the opaque prescription id comes from the route. */
export function PharmacyItemPage() {
  const organizationId = useActiveOrganization();
  const { params } = useRoute();
  const prescriptionId = params.id ?? '';
  const [reloadToken, setReloadToken] = useState(0);
  const [done, setDone] = useState<string | undefined>(undefined);

  const result = useApi<PharmacyDetail>(
    () =>
      Promise.all([
        getPrescription(prescriptionId, organizationId),
        getPharmacyStatus(prescriptionId, organizationId),
      ]).then(([item, status]) => ({ item, status })),
    [prescriptionId, organizationId, reloadToken],
  );

  return (
    <>
      {done !== undefined ? <p role="status">{done}</p> : null}
      {result.status === 'loading' ? (
        <LoadingState />
      ) : result.status === 'error' ? (
        <div>{StateView.forTransportError(result.error)}</div>
      ) : (
        <PharmacyWorkflow
          key={`${organizationId}:${prescriptionId}`}
          detail={result.data}
          organizationId={organizationId}
          onDone={setDone}
          onCommitted={() => setReloadToken((value) => value + 1)}
        />
      )}
    </>
  );
}
