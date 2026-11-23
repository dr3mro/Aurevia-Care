import { useState } from 'react';
import { Button } from '../../components/Button';
import { MixedScriptText } from '../../components/MixedScriptText';
import { displayLocalName, formatCivilDate, formatInstant } from '../../i18n/format';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import type { Appointment, CheckInRecord, PatientIdentity } from './domain';
import { receptionNow } from './fixtures';
import { schedulingText } from './messages';

/** Props for the reception check-in panel. */
export interface CheckInPanelProps {
  appointment: Appointment | undefined;
  identity: PatientIdentity | undefined;
  checker: string;
  onCheckIn: (record: CheckInRecord) => void;
}

/** The three synthetic identity checks; the consent item starts unchecked. */
const initialChecks: readonly boolean[] = [true, true, false];

/**
 * Present the identity and balance context for one booked row and record a
 * local check-in with the checker, a fixed synthetic instant and the facility.
 * No live clock is read: the instant is a fixture so the record is testable and
 * reproducible. The panel never invents patient identity and keeps every
 * visible label in the bilingual feature dictionary (CLN-02, UX-01).
 */
export function CheckInPanel({ appointment, identity, checker, onCheckIn }: CheckInPanelProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const [checks, setChecks] = useState<readonly boolean[]>(initialChecks);

  if (appointment === undefined || identity === undefined) {
    return (
      <section
        data-testid="check-in-panel"
        style={{ paddingBlock: tokens.spacing.lg, paddingInline: tokens.spacing.lg, border: `1px solid ${tokens.border}`, background: tokens.background, borderRadius: tokens.spacing.sm }}
      >
        <h3>{schedulingText(locale, 'checkIn')}</h3>
        <p>{schedulingText(locale, 'selectRow')}</p>
      </section>
    );
  }

  const name = displayLocalName(appointment.patientName, locale);
  const balance = new Intl.NumberFormat(locale, {
    style: 'currency',
    currency: 'USD',
    numberingSystem: 'latn',
  }).format(identity.selfPayBalance);
  const languageKey = identity.language === 'ar' ? 'languageArabic' : 'languageEnglish';
  const checkLabels = ['idChecked', 'phoneConfirmed', 'consentCurrent'] as const;

  function toggle(index: number) {
    setChecks((current) => current.map((value, at) => (at === index ? !value : value)));
  }

  return (
    <section
      data-testid="check-in-panel"
      aria-label={`${schedulingText(locale, 'checkIn')}: ${name}`}
      style={{
        display: 'grid',
        gap: tokens.spacing.md,
        paddingBlock: tokens.spacing.lg, paddingInline: tokens.spacing.lg,
        border: `1px solid ${tokens.border}`,
        borderRadius: tokens.spacing.sm,
        background: tokens.background,
        color: tokens.foreground,
        minInlineSize: 0,
        overflowWrap: 'anywhere',
      }}
    >
      <h3 style={{ marginBlock: 0 }}>
        <MixedScriptText text={`${schedulingText(locale, 'checkIn')}: ${name}`} lang={locale} />
      </h3>
      <p style={{ marginBlock: 0 }}>
        {formatInstant(appointment.scheduledAt, appointment.timeZone, locale)}
        {' · '}
        {appointment.visitType}
        {' · '}
        {identity.clinician}
        {' · '}
        {identity.room}
      </p>
      <dl style={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: tokens.spacing.sm, marginBlock: 0 }}>
        <dt>{schedulingText(locale, 'dateOfBirth')}</dt>
        <dd>{formatCivilDate(identity.dateOfBirth, locale)}</dd>
        <dt>{schedulingText(locale, 'language')}</dt>
        <dd>{schedulingText(locale, languageKey)}</dd>
        <dt>{schedulingText(locale, 'identifier')}</dt>
        <dd>
          {identity.identifier} · {identity.issuer}
        </dd>
        <dt>{schedulingText(locale, 'verified')}</dt>
        <dd>{schedulingText(locale, identity.verified ? 'issuerVerified' : 'notVerified')}</dd>
        <dt>{schedulingText(locale, 'selfPayBalance')}</dt>
        <dd>{balance}</dd>
      </dl>
      <h4 style={{ marginBlock: 0 }}>{schedulingText(locale, 'identityCheck')}</h4>
      <div style={{ display: 'grid', gap: tokens.spacing.sm }}>
        {checkLabels.map((labelKey, index) => (
          <label key={labelKey} style={{ display: 'flex', alignItems: 'center', gap: tokens.spacing.sm }}>
            <input
              type="checkbox"
              className="touch"
              checked={checks[index] === true}
              onChange={() => toggle(index)}
            />
            <span>{schedulingText(locale, labelKey)}</span>
          </label>
        ))}
      </div>
      <Button
        onClick={() =>
          onCheckIn({ checker, at: receptionNow, facilityCode: appointment.facilityCode })
        }
      >
        {schedulingText(locale, 'checkInNotify')}
      </Button>
      <p style={{ marginBlock: 0 }}>{schedulingText(locale, 'checkInHint')}</p>
    </section>
  );
}
