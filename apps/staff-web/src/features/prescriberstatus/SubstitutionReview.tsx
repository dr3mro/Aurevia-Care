import { useState } from 'react';
import type { TransportError } from '../../api/transport';
import { Button } from '../../components/Button';
import { FormField } from '../../components/FormField';
import { MixedScriptText } from '../../components/MixedScriptText';
import { StateView } from '../../components/StateViews';
import { formatInstant } from '../../i18n/format';
import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import { activeEntry, catalogLabel, hasText, revisionLink } from './domain';
import { prescriberText, reasonMessageKey, stateMessageKey } from './labels';
import type { DecisionDraft, ProposalViewResponse } from './types';

/** Props for the prescriber's substitution decision view. */
export interface SubstitutionReviewProps {
  view: ProposalViewResponse;
  locale: Locale;
  timeZone: string;
  error?: TransportError;
  onApprove: (draft: DecisionDraft) => void;
  onDecline: (draft: DecisionDraft) => void;
}

/**
 * Shows the originally prescribed and the proposed catalog entry side by side.
 * Nothing changes until the prescriber approves with an explicit attestation,
 * which surfaces the signed revision returned by the server. Declining keeps
 * the original medication and creates no revision.
 */
export function SubstitutionReview({
  view,
  locale,
  timeZone,
  error,
  onApprove,
  onDecline,
}: SubstitutionReviewProps) {
  const { tokens } = useTheme();
  const [attestation, setAttestation] = useState('');
  const [note, setNote] = useState('');
  const [validationError, setValidationError] = useState(false);
  const revision = revisionLink(view);
  const active = activeEntry(view);

  function handleApprove() {
    if (!hasText(attestation)) {
      setValidationError(true);
      return;
    }
    setValidationError(false);
    onApprove({ attestation, note });
  }

  function handleDecline() {
    onDecline({ attestation: '', note });
  }

  return (
    <section
      aria-label={prescriberText(locale, 'substitutionTitle')}
      style={{ display: 'grid', gap: tokens.spacing.md }}
    >
      <h2>{prescriberText(locale, 'substitutionTitle')}</h2>
      <p data-testid="proposal-state">
        {prescriberText(locale, stateMessageKey(view.proposal.state))}
      </p>
      {view.proposal.note === null ? null : (
        <p data-testid="proposal-note">
          <MixedScriptText text={view.proposal.note} lang="en" />
        </p>
      )}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: tokens.spacing.md }}>
        <div data-testid="original-entry" aria-label={prescriberText(locale, 'original')}>
          <h3>{prescriberText(locale, 'original')}</h3>
          <MixedScriptText text={catalogLabel(view.proposal.original)} lang="en" />
        </div>
        <div data-testid="proposed-entry" aria-label={prescriberText(locale, 'proposed')}>
          <h3>{prescriberText(locale, 'proposed')}</h3>
          <MixedScriptText text={catalogLabel(view.proposal.proposed)} lang="en" />
        </div>
      </div>
      <p>
        {prescriberText(locale, 'reason')}:{' '}
        <span>{prescriberText(locale, reasonMessageKey(view.proposal.reason))}</span>
      </p>
      <section data-testid="active-entry" aria-label={prescriberText(locale, 'activeMedication')}>
        <h3>{prescriberText(locale, 'activeMedication')}</h3>
        <MixedScriptText text={catalogLabel(active)} lang="en" />
      </section>
      {revision === null ? (
        <p>{prescriberText(locale, 'originalRetained')}</p>
      ) : (
        <a data-testid="revision-link" href={`/pharmacy/${view.proposal.prescriptionId}`}>
          {prescriberText(locale, 'revisionLink')}: <code dir="ltr">{revision.id}</code>{' '}
          <span>{revision.revisionNumber}</span>{' '}
          <time dateTime={revision.signedAt}>
            {formatInstant(revision.signedAt, timeZone, locale)}
          </time>
        </a>
      )}
      <FormField
        id="substitution-attestation"
        label={prescriberText(locale, 'attestation')}
        value={attestation}
        onChange={(value) => {
          setAttestation(value);
          setValidationError(false);
        }}
        {...(validationError ? { error: prescriberText(locale, 'attestationRequired') } : {})}
      />
      <FormField
        id="substitution-note"
        label={prescriberText(locale, 'note')}
        value={note}
        onChange={setNote}
      />
      <Button onClick={handleApprove}>{prescriberText(locale, 'approve')}</Button>{' '}
      <Button variant="secondary" onClick={handleDecline}>
        {prescriberText(locale, 'decline')}
      </Button>
      {error === undefined ? null : <div>{StateView.forTransportError(error)}</div>}
    </section>
  );
}
