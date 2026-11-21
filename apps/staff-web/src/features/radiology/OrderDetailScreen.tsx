/** Presentational order detail: schedule, study link, signed report and referrer view. */

import { useState } from 'react';
import type { FormEvent } from 'react';
import { Link } from '../../app/router';
import { Button } from '../../components/Button';
import { FormField } from '../../components/FormField';
import { MixedScriptText } from '../../components/MixedScriptText';
import { displayLocalName, formatInstant } from '../../i18n/format';
import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import {
  buildReferrerView,
  canInterpret,
  canLinkStudy,
  canSchedule,
  isModality,
} from './domain';
import type {
  ImagingOrder,
  InterpretationInput,
  ScheduleInput,
  StudyLink,
  StudyLinkCheck,
} from './domain';
import { radiologyText, stateMessageKey, urgencyMessageKey } from './messages';

/** Props for the image order detail screen. */
export interface OrderDetailScreenProps {
  order: ImagingOrder;
  locale: Locale;
  mismatch: StudyLinkCheck | undefined;
  onSchedule: (input: ScheduleInput) => void;
  onLinkStudy: (study: StudyLink) => void;
  onSign: (input: InterpretationInput) => void;
  onAddendum: (text: string) => void;
}

function submit(event: FormEvent<HTMLFormElement>, action: () => void): void {
  event.preventDefault();
  action();
}

/** Section that records the accession and modality for an unreceived order. */
function ScheduleForm({
  locale,
  enabled,
  onSchedule,
}: {
  locale: Locale;
  enabled: boolean;
  onSchedule: (input: ScheduleInput) => void;
}) {
  const [accessionNo, setAccessionNo] = useState('');
  const [modality, setModality] = useState('');
  const [scheduledAt, setScheduledAt] = useState('');
  const [error, setError] = useState<string | undefined>(undefined);
  return (
    <form
      aria-label={radiologyText(locale, 'scheduleStep')}
      onSubmit={(event) =>
        submit(event, () => {
          if (!isModality(modality)) {
            setError(radiologyText(locale, 'invalidModality'));
            return;
          }
          setError(undefined);
          onSchedule({ accessionNo, modality, scheduledAt });
        })
      }
    >
      <h3>{radiologyText(locale, 'scheduleStep')}</h3>
      {error !== undefined && <p role="alert">{error}</p>}
      <FormField
        id="radiology-accession"
        label={radiologyText(locale, 'accessionNo')}
        value={accessionNo}
        onChange={setAccessionNo}
      />
      <FormField
        id="radiology-modality"
        label={radiologyText(locale, 'modality')}
        value={modality}
        onChange={setModality}
      />
      <FormField
        id="radiology-scheduled"
        label={radiologyText(locale, 'scheduledAt')}
        value={scheduledAt}
        onChange={setScheduledAt}
      />
      <Button type="submit" disabled={!enabled}>
        {radiologyText(locale, 'schedule')}
      </Button>
    </form>
  );
}

/** Section that associates an external study after the patient/accession check. */
function StudyLinkForm({
  locale,
  enabled,
  mismatch,
  onLinkStudy,
}: {
  locale: Locale;
  enabled: boolean;
  mismatch: StudyLinkCheck | undefined;
  onLinkStudy: (study: StudyLink) => void;
}) {
  const [studyInstanceUid, setStudyInstanceUid] = useState('');
  const [patientId, setPatientId] = useState('');
  const [accessionNo, setAccessionNo] = useState('');
  return (
    <form
      aria-label={radiologyText(locale, 'studyStep')}
      onSubmit={(event) =>
        submit(event, () => onLinkStudy({ studyInstanceUid, patientId, accessionNo }))
      }
    >
      <h3>{radiologyText(locale, 'studyStep')}</h3>
      {mismatch !== undefined && !mismatch.ok && (
        <section role="alert" aria-label={radiologyText(locale, 'mismatchTitle')}>
          <h4>{radiologyText(locale, 'mismatchTitle')}</h4>
          {mismatch.patientMismatch && <p>{radiologyText(locale, 'patientMismatch')}</p>}
          {mismatch.accessionMismatch && <p>{radiologyText(locale, 'accessionMismatch')}</p>}
          <p>{radiologyText(locale, 'mismatchHint')}</p>
        </section>
      )}
      <FormField
        id="radiology-study-uid"
        label={radiologyText(locale, 'studyInstanceUid')}
        value={studyInstanceUid}
        onChange={setStudyInstanceUid}
      />
      <FormField
        id="radiology-study-patient"
        label={radiologyText(locale, 'studyPatientId')}
        value={patientId}
        onChange={setPatientId}
      />
      <FormField
        id="radiology-study-accession"
        label={radiologyText(locale, 'studyAccessionNo')}
        value={accessionNo}
        onChange={setAccessionNo}
      />
      <Button type="submit" disabled={!enabled}>
        {radiologyText(locale, 'linkStudy')}
      </Button>
    </form>
  );
}

/** Section that signs the five required interpretation sections. */
function InterpretationForm({
  locale,
  enabled,
  onSign,
}: {
  locale: Locale;
  enabled: boolean;
  onSign: (input: InterpretationInput) => void;
}) {
  const [technique, setTechnique] = useState('');
  const [comparison, setComparison] = useState('');
  const [findings, setFindings] = useState('');
  const [impression, setImpression] = useState('');
  const [followUp, setFollowUp] = useState('');
  return (
    <form
      aria-label={radiologyText(locale, 'interpretationStep')}
      onSubmit={(event) =>
        submit(event, () => onSign({ technique, comparison, findings, impression, followUp }))
      }
    >
      <h3>{radiologyText(locale, 'interpretationStep')}</h3>
      <FormField
        id="radiology-technique"
        label={radiologyText(locale, 'technique')}
        value={technique}
        onChange={setTechnique}
      />
      <FormField
        id="radiology-comparison"
        label={radiologyText(locale, 'comparison')}
        value={comparison}
        onChange={setComparison}
      />
      <FormField
        id="radiology-findings"
        label={radiologyText(locale, 'findings')}
        value={findings}
        onChange={setFindings}
      />
      <FormField
        id="radiology-impression"
        label={radiologyText(locale, 'impression')}
        value={impression}
        onChange={setImpression}
      />
      <FormField
        id="radiology-followup"
        label={radiologyText(locale, 'followUp')}
        value={followUp}
        onChange={setFollowUp}
      />
      <Button type="submit" disabled={!enabled}>
        {radiologyText(locale, 'signInterpretation')}
      </Button>
    </form>
  );
}

/** The signed, read-only report plus the addendum form. */
function SignedPanel({
  order,
  locale,
  onAddendum,
}: {
  order: ImagingOrder;
  locale: Locale;
  onAddendum: (text: string) => void;
}) {
  const [addendumText, setAddendumText] = useState('');
  const interpretation = order.interpretation!;
  return (
    <section aria-label={radiologyText(locale, 'signedInterpretation')}>
      <h3>{radiologyText(locale, 'signedInterpretation')}</h3>
      <dl>
        <dt>{radiologyText(locale, 'technique')}</dt>
        <dd>{interpretation.technique}</dd>
        <dt>{radiologyText(locale, 'comparison')}</dt>
        <dd>{interpretation.comparison}</dd>
        <dt>{radiologyText(locale, 'findings')}</dt>
        <dd>{interpretation.findings}</dd>
        <dt>{radiologyText(locale, 'impression')}</dt>
        <dd>{interpretation.impression}</dd>
        <dt>{radiologyText(locale, 'followUp')}</dt>
        <dd>{interpretation.followUp}</dd>
        <dt>{radiologyText(locale, 'signedBy')}</dt>
        <dd>{interpretation.signedBy}</dd>
        <dt>{radiologyText(locale, 'signedAt')}</dt>
        <dd>{formatInstant(interpretation.signedAt, 'UTC', locale)}</dd>
      </dl>
      <form
        aria-label={radiologyText(locale, 'addAddendum')}
        onSubmit={(event) => submit(event, () => onAddendum(addendumText))}
      >
        <h4>{radiologyText(locale, 'addenda')}</h4>
        <ul>
          {interpretation.addenda.map((addendum) => (
            <li key={addendum.id} dir="auto">
              {addendum.text}
            </li>
          ))}
        </ul>
        <FormField
          id="radiology-addendum"
          label={radiologyText(locale, 'addendumText')}
          value={addendumText}
          onChange={setAddendumText}
        />
        <Button type="submit">{radiologyText(locale, 'addAddendum')}</Button>
      </form>
    </section>
  );
}

/** The immutable projection handed to the referring clinician. */
function ReferrerViewPanel({ order, locale }: { order: ImagingOrder; locale: Locale }) {
  const view = buildReferrerView(order);
  return (
    <section aria-label={radiologyText(locale, 'referrerView')}>
      <h3>{radiologyText(locale, 'referrerView')}</h3>
      {view === null ? (
        <p>{radiologyText(locale, 'noReferrerView')}</p>
      ) : (
        <>
          <p>{view.findings}</p>
          <p>{view.impression}</p>
          <ul>
            {view.addenda.map((addendum) => (
              <li key={addendum.id} dir="auto">
                {addendum.text}
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}

/** Compose the whole order detail workflow from presentational sections. */
export function OrderDetailScreen({
  order,
  locale,
  mismatch,
  onSchedule,
  onLinkStudy,
  onSign,
  onAddendum,
}: OrderDetailScreenProps) {
  const { tokens } = useTheme();
  return (
    <div style={{ display: 'grid', gap: tokens.spacing.lg }}>
      <header>
        <h2>{radiologyText(locale, 'orderDetail')}</h2>
        <p>
          <MixedScriptText text={displayLocalName(order.patientName, locale)} lang={locale} />{' '}
          <code dir="ltr">{order.patientId}</code>
        </p>
        <p>{radiologyText(locale, urgencyMessageKey[order.urgency])}</p>
        <p>{radiologyText(locale, stateMessageKey[order.state])}</p>
        <p dir="auto">{order.clinicalQuestion}</p>
        <Link to="/imaging/orders">{radiologyText(locale, 'backToOrders')}</Link>
      </header>
      <ScheduleForm locale={locale} enabled={canSchedule(order)} onSchedule={onSchedule} />
      <StudyLinkForm
        locale={locale}
        enabled={canLinkStudy(order)}
        mismatch={mismatch}
        onLinkStudy={onLinkStudy}
      />
      <InterpretationForm locale={locale} enabled={canInterpret(order)} onSign={onSign} />
      {order.interpretation !== null && (
        <SignedPanel order={order} locale={locale} onAddendum={onAddendum} />
      )}
      <ReferrerViewPanel order={order} locale={locale} />
    </div>
  );
}
