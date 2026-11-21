import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import type { LabelCheckResult } from './domain';
import type { RadworklistMessageKey } from './labels';
import { radworklistText } from './labels';

/** Props for the patient-and-accession verdict shown before a link is offered. */
export interface PatientAccessionCheckProps {
  /** True once both workstation labels have been entered. */
  checked: boolean;
  /** Comparison outcome; undefined while the labels are incomplete. */
  result: LabelCheckResult | undefined;
  locale: Locale;
}

/**
 * Show the label comparison as a polite status message. A mismatch is named in
 * words, never only by the disabled button, so the wrong-patient guard is
 * visible to a screen reader before the link action is offered.
 */
export function PatientAccessionCheck({ checked, result, locale }: PatientAccessionCheckProps) {
  const { tokens } = useTheme();
  let key: RadworklistMessageKey = 'checkPending';
  let verdict = 'pending';
  if (checked && result !== undefined) {
    if (!result.patientMatches && !result.accessionMatches) {
      key = 'checkBothMismatch';
      verdict = 'both';
    } else if (!result.patientMatches) {
      key = 'checkPatientMismatch';
      verdict = 'patient';
    } else if (!result.accessionMatches) {
      key = 'checkAccessionMismatch';
      verdict = 'accession';
    } else {
      key = 'checkMatched';
      verdict = 'matched';
    }
  }
  return (
    <p
      role="status"
      aria-label={radworklistText(locale, 'checkHeading')}
      data-testid="label-check"
      data-verdict={verdict}
      style={{ color: tokens.foreground }}
    >
      {radworklistText(locale, key)}
    </p>
  );
}
