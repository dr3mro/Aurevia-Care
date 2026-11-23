/**
 * Reusable print button for the prescription screen (RX-02, WEB-09).
 *
 * The button is presentational: it decides eligibility from the bounded
 * snapshot, opens the shared handout preview supplied with a document URL, and
 * reports the confirmed print or download through `onRecord`. The owning screen
 * performs the protected print request and records the audit reason; the server
 * re-checks permission and source state on every call.
 */

import { useId, useState } from 'react';
import { Button } from '../../../components/Button';
import { useI18n } from '../../../i18n/I18nProvider';
import { useTheme } from '../../../theme/ThemeProvider';
import { PrintPreviewDialog } from '../components/PrintPreviewDialog';
import type {
  HandoutLanguage,
  PrintPreviewState,
  PrintReason,
} from '../components/PrintPreviewDialog';
import { isReprint, offeredCopies, printEligibility } from './domain';
import { blockerKey, prescriptionText } from './labels';
import type {
  PrescriptionCopy,
  PrescriptionPrintRecord,
  PrintPrescriptionButtonProps,
} from './types';

export type {
  PrescriptionCopy,
  PrescriptionForPrint,
  PrescriptionPrintBlocker,
  PrescriptionPrintChannel,
  PrescriptionPrintRecord,
  PrintEligibility,
  PrintPrescriptionButtonProps,
} from './types';
export { PRINT_CHANNELS } from './types';
export { isReprint, offeredCopies, printEligibility } from './domain';

/** Print action for a prescription, gated by source state and channel. */
export function PrintPrescriptionButton({
  prescription,
  handoutUrl,
  onRecord,
}: PrintPrescriptionButtonProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const reasonId = useId();
  const [open, setOpen] = useState(false);
  const [copy, setCopy] = useState<PrescriptionCopy>('patient');
  const [status, setStatus] = useState('');

  const eligibility = printEligibility(prescription);
  const reprint = isReprint(prescription);
  const copies = offeredCopies(prescription);
  const actionLabel = reprint
    ? prescriptionText(locale, 'reprint')
    : prescriptionText(locale, 'print');

  const record = (
    action: 'print' | 'download',
    reason: PrintReason | null,
    language: HandoutLanguage,
  ) => {
    const entry: PrescriptionPrintRecord = {
      prescriptionId: prescription.id,
      sourceVersion: prescription.version,
      copy,
      language,
      reprint,
      reason,
      action,
    };
    onRecord(entry);
    setStatus(prescriptionText(locale, action === 'print' ? 'recorded' : 'downloaded'));
  };

  if (!eligibility.allowed) {
    return (
      <div style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <button
          type="button"
          disabled
          aria-describedby={reasonId}
          style={{
            minHeight: '32px',
            padding: `${tokens.spacing.sm} ${tokens.spacing.md}`,
            border: `1px solid ${tokens.border}`,
            background: 'transparent',
            color: tokens.foreground,
            cursor: 'not-allowed',
          }}
        >
          {actionLabel}
        </button>
        <p id={reasonId} role="status">
          {prescriptionText(locale, blockerKey[eligibility.blocker])}
        </p>
      </div>
    );
  }

  const previewState: PrintPreviewState = {
    kind: 'ready',
    documentUrl: handoutUrl,
    title: prescriptionText(locale, copy === 'patient' ? 'patientCopy' : 'pharmacyCopy'),
  };

  return (
    <div style={{ display: 'grid', gap: tokens.spacing.sm }}>
      {copies.length > 1 ? (
        <fieldset>
          <legend>{prescriptionText(locale, 'copyLegend')}</legend>
          {copies.map((option) => (
            <label key={option} style={{ display: 'block' }}>
              <input
                type="radio"
                name={`print-prescription-copy-${prescription.id}`}
                value={option}
                checked={copy === option}
                onChange={() => setCopy(option)}
              />{' '}
              {prescriptionText(locale, option === 'patient' ? 'patientCopy' : 'pharmacyCopy')}
            </label>
          ))}
        </fieldset>
      ) : null}
      {reprint ? <p data-print-reprint="true">{prescriptionText(locale, 'reprintNote')}</p> : null}
      <Button onClick={() => setOpen(true)}>{actionLabel}</Button>
      <p role="status">{status}</p>
      <PrintPreviewDialog
        open={open}
        state={previewState}
        onPrint={(reason, language) => {
          record('print', reason, language);
          setOpen(false);
        }}
        onDownload={(language) => record('download', null, language)}
        onClose={() => setOpen(false)}
      />
    </div>
  );
}
