import { useState } from 'react';
import { Link } from '../../app/router';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import { AccessionActionForm } from './AccessionActionForm';
import { AccessionActions } from './AccessionActions';
import { AccessionStateBadge } from './AccessionStateBadge';
import { labText } from './messages';
import type { AccessionAction, AccessionView, ActionPayload, LabAccession } from './types';

/** Props for the accession detail screen. */
export interface AccessionDetailScreenProps {
  view: AccessionView;
  onAction: (payload: ActionPayload) => void;
  /** New specimen returned by a successful recollect, when one exists. */
  linked: LabAccession | undefined;
}

/** Render one labelled value with an explicit unknown marker and LTR isolation. */
export function AccessionField({ label, value }: { label: string; value: string | null }) {
  return (
    <div>
      <dt>{label}</dt>
      <dd dir="ltr">{value ?? '\u2014'}</dd>
    </div>
  );
}

/**
 * Present the current accession, its immutable history and the state-scoped
 * action set. The action form is opened only for a command the state machine
 * allows; rejection cannot be submitted without a reason.
 */
export function AccessionDetailScreen({ view, onAction, linked }: AccessionDetailScreenProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const [openAction, setOpenAction] = useState<AccessionAction | undefined>(undefined);
  const { accession, events } = view;

  return (
    <section aria-label={labText(locale, 'accessionNo')} style={{ display: 'grid', gap: tokens.spacing.lg }}>
      <h2>
        {accession.accessionNo} <span dir="ltr">({accession.accessionIssuer})</span>
      </h2>
      <p>
        <Link to="/lab/accessions">{labText(locale, 'backToList')}</Link>
      </p>
      <p>
        {labText(locale, 'state')}: <AccessionStateBadge state={accession.state} />
      </p>

      {linked !== undefined && (
        <p role="status">
          {labText(locale, 'linkedSpecimen')}:{' '}
          <Link to={`/lab/accessions/${linked.id}`}>
            {linked.accessionNo} ({linked.accessionIssuer})
          </Link>
        </p>
      )}

      <dl>
        <AccessionField label={labText(locale, 'patientId')} value={accession.patientId} />
        <AccessionField label={labText(locale, 'sourceOrderRef')} value={accession.sourceOrderRef} />
        <AccessionField label={labText(locale, 'specimenType')} value={accession.specimenType} />
        <AccessionField label={labText(locale, 'siteCode')} value={accession.siteCode} />
        <AccessionField label={labText(locale, 'collector')} value={accession.collector} />
        <AccessionField label={labText(locale, 'collectedAt')} value={accession.collectedAt} />
        <AccessionField label={labText(locale, 'receivedAt')} value={accession.receivedAt} />
        <AccessionField label={labText(locale, 'quality')} value={accession.quality} />
        <AccessionField label={labText(locale, 'rejectionReason')} value={accession.rejectionReason} />
        <AccessionField
          label={labText(locale, 'rejectionNotification')}
          value={accession.rejectionNotification}
        />
        <AccessionField
          label={labText(locale, 'replacesAccessionId')}
          value={accession.replacesAccessionId}
        />
      </dl>

      <h3>{labText(locale, 'events')}</h3>
      {events.length === 0 ? (
        <p>{labText(locale, 'noEvents')}</p>
      ) : (
        <ol>
          {events.map((event) => (
            <li key={event.id}>
              {event.action}: {event.fromState ?? '\u2014'} → {event.toState}
              {event.reason !== null && ` (${event.reason})`} — <span dir="ltr">{event.occurredAt}</span>
            </li>
          ))}
        </ol>
      )}

      <h3>{labText(locale, 'actions')}</h3>
      <AccessionActions
        state={accession.state}
        onAction={(action) => setOpenAction(action)}
      />
      {openAction !== undefined && (
        <AccessionActionForm
          action={openAction}
          accession={accession}
          onSubmit={(payload) => {
            onAction(payload);
            setOpenAction(undefined);
          }}
          onCancel={() => setOpenAction(undefined)}
        />
      )}
    </section>
  );
}
