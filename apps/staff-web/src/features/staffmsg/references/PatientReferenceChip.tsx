import { Link } from '../../../app/router';
import { useI18n } from '../../../i18n/I18nProvider';
import { useTheme } from '../../../theme/ThemeProvider';
import { referenceText } from './labels';
import type { AccessOutcome } from './screening';

/** Props for a non-identifying reference chip shown in a staff message. */
export interface PatientReferenceChipProps {
  /** Fixed, non-identifying label supplied by the reference view. */
  readonly label: string;
  /** Opaque patient handle used only to build the in-app chart route. */
  readonly patientId: string;
  /** Freshly derived access decision; anything but ALLOWED conceals the target. */
  readonly access: AccessOutcome;
}

/**
 * Present a message reference as a link to the normal patient chart when the
 * viewer is allowed, and as an unambiguous blocked state otherwise (MSG-07,
 * WEB-09). The blocked branch renders neither the label nor the patient id, so
 * a denied viewer learns nothing about the concealed target. The label is an
 * isolated `bdi` run because it may be written in either script.
 */
export function PatientReferenceChip({ label, patientId, access }: PatientReferenceChipProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();

  if (access !== 'ALLOWED') {
    return (
      <span
        role="alert"
        data-testid="patient-reference-blocked"
        style={{
          color: tokens.foreground,
          borderInlineStart: `2px solid ${tokens.border}`,
          paddingInlineStart: tokens.spacing.sm,
        }}
      >
        <span aria-hidden="true">⛔</span> <strong>{referenceText(locale, 'blocked')}</strong>
        <span style={{ display: 'block' }}>{referenceText(locale, 'blockedDetail')}</span>
      </span>
    );
  }

  return (
    <Link to={`/patients/${patientId}`}>
      <bdi dir="auto" style={{ color: tokens.accent }}>
        {label}
      </bdi>
    </Link>
  );
}
