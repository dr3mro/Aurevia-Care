import { FormField } from '../../components/FormField';
import { MixedScriptText } from '../../components/MixedScriptText';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import type { CollectionOrder, MatchCheck, PresentedIdentity } from './domain';
import { labText, mismatchMessageKey, stateMessageKey } from './messages';

/** Props for the bedside identity and accession verification panel. */
export interface IdentityVerificationProps {
  order: CollectionOrder;
  match: MatchCheck;
  presented: PresentedIdentity;
  onPresentedChange: (next: PresentedIdentity) => void;
  namesConfirmed: boolean;
  accessionConfirmed: boolean;
  onNamesConfirmed: (value: boolean) => void;
  onAccessionConfirmed: (value: boolean) => void;
}

/**
 * Show both recorded patient names and the issuer-scoped accession, then take
 * the identity the collector read from the wristband and tube. A mismatch is
 * explained here; whether the collector may continue is decided by the parent.
 */
export function IdentityVerification({
  order,
  match,
  presented,
  onPresentedChange,
  namesConfirmed,
  accessionConfirmed,
  onNamesConfirmed,
  onAccessionConfirmed,
}: IdentityVerificationProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();

  return (
    <section
      aria-label={labText(locale, 'patientIdentity')}
      style={{ display: 'grid', gap: tokens.spacing.md }}
    >
      <h3>{labText(locale, 'patientIdentity')}</h3>
      <div style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <div>
          {labText(locale, 'latinNameLabel')}:{' '}
          <MixedScriptText text={order.patientName.latinName} lang="en" />
        </div>
        {order.patientName.localName !== undefined ? (
          <div>
            {labText(locale, 'localNameLabel')}:{' '}
            <MixedScriptText text={order.patientName.localName} lang="ar" />
          </div>
        ) : null}
        <div>
          {labText(locale, 'patientIdentifier')}: <code dir="ltr">{order.patientMrn}</code> (
          <MixedScriptText text={order.patientMrnIssuer} lang="en" />)
        </div>
        <div>
          {labText(locale, 'accessionNumber')}: <code dir="ltr">{order.accessionNo}</code> ·{' '}
          {labText(locale, 'accessionIssuer')}:{' '}
          <MixedScriptText text={order.accessionIssuer} lang="en" />
        </div>
        <div>
          {labText(locale, 'sourceOrder')}: <code dir="ltr">{order.sourceOrderRef}</code> ·{' '}
          {labText(locale, 'orderState')}: {labText(locale, stateMessageKey[order.state])}
        </div>
      </div>
      <h4>{labText(locale, 'presentedIdentity')}</h4>
      <FormField
        id="lab-presented-patient"
        label={labText(locale, 'presentedPatientId')}
        value={presented.patientId}
        onChange={(value) => onPresentedChange({ ...presented, patientId: value })}
      />
      <FormField
        id="lab-presented-accession"
        label={labText(locale, 'presentedAccessionNo')}
        value={presented.accessionNo}
        onChange={(value) => onPresentedChange({ ...presented, accessionNo: value })}
      />
      <FormField
        id="lab-presented-issuer"
        label={labText(locale, 'presentedAccessionIssuer')}
        value={presented.accessionIssuer}
        onChange={(value) => onPresentedChange({ ...presented, accessionIssuer: value })}
      />
      <label>
        <input
          type="checkbox"
          checked={namesConfirmed}
          onChange={(event) => onNamesConfirmed(event.target.checked)}
        />{' '}
        {labText(locale, 'confirmNames')}
      </label>
      <label>
        <input
          type="checkbox"
          checked={accessionConfirmed}
          onChange={(event) => onAccessionConfirmed(event.target.checked)}
        />{' '}
        {labText(locale, 'confirmAccession')}
      </label>
      <div role="status" data-testid="match-status">
        {match.matched ? (
          <p>{labText(locale, 'matchOk')}</p>
        ) : (
          <div>
            <p>{labText(locale, 'mismatchTitle')}</p>
            <ul>
              {match.reasons.map((reason) => (
                <li key={reason}>{labText(locale, mismatchMessageKey[reason])}</li>
              ))}
            </ul>
          </div>
        )}
      </div>
    </section>
  );
}
