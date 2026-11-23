import { useEffect, useId, useRef, useState } from 'react';
import { Button } from '../../../components/Button';
import type { PersonName } from '../../../i18n/format';
import type { Locale } from '../../../i18n/messages';
import { useTheme } from '../../../theme/ThemeProvider';
import { printReasonKey, printText } from '../components/messages';
import type { PrintReason } from '../components/messages';
import { ChartPrintPreview } from './ChartPrintPreview';
import { ChartSectionPicker, DEFAULT_FROM, DEFAULT_TO } from './ChartSectionPicker';
import {
  buildChartPrintPreview,
  buildSignedReportPreview,
  canPrintPreview,
  chartPrintSections,
} from './domain';
import type {
  ChartPrintPreview as ChartPrintPreviewModel,
  ChartPrintScope,
  ChartPrintSection,
  ChartPrintVisit,
  PrintableNote,
  PrintableResult,
  SignedReport,
} from './domain';
import type { ChartPrintInput } from './domain';
import { chartPrintText } from './labels';

export type {
  ChartPrintPreview as ChartPrintPreviewModel,
  ChartPrintScope,
  ChartPrintSection,
  ChartPrintVisit,
  LeftOutItem,
  LeftOutReason,
  PrintableNote,
  PrintableResult,
  SignedReport,
} from './domain';

/** Chart data for a summary, supplied by the owning chart screen. */
export interface ChartPrintChartTarget {
  kind: 'chart';
  patientName: PersonName;
  counts: Readonly<Record<ChartPrintSection, number>>;
  notes: readonly PrintableNote[];
  results: readonly PrintableResult[];
  visits: readonly ChartPrintVisit[];
}

/** A signed-report screen supplies only its report; the same button prints it. */
export interface ChartPrintReportTarget {
  kind: 'report';
  report: SignedReport;
}

/** Either source a clinician may print from the chart area. */
export type PrintChartTarget = ChartPrintChartTarget | ChartPrintReportTarget;

/** Props for the shared chart/signed-report print entry point. */
export interface PrintChartButtonProps {
  locale: Locale;
  target: PrintChartTarget;
  onPrint: (model: ChartPrintPreviewModel, reason: PrintReason) => void;
}

/** Ordered reason choices, matching the audit reasons the API records (WEB-09). */
const printReasons: readonly PrintReason[] = ['give-to-patient', 'referral', 'patient-request'];

/**
 * Disclosure button that previews a chart summary or a signed report before
 * printing. Scope, section and reason choices live here so closing a preview
 * never discards them; no handout is produced until Print is confirmed.
 */
export function PrintChartButton({ locale, target, onPrint }: PrintChartButtonProps) {
  const { tokens } = useTheme();
  const controlId = useId();
  const panelId = `${controlId}-panel`;
  const triggerRef = useRef<HTMLButtonElement>(null);
  const errorRef = useRef<HTMLParagraphElement>(null);
  const [open, setOpen] = useState(false);
  const [scope, setScope] = useState<ChartPrintScope>(() =>
    target.kind === 'chart'
      ? { kind: 'visit', visitId: target.visits[0]?.id ?? '' }
      : { kind: 'range', from: DEFAULT_FROM, to: DEFAULT_TO },
  );
  const [sections, setSections] = useState<readonly ChartPrintSection[]>(chartPrintSections);
  const [reason, setReason] = useState<PrintReason | null>(null);
  const [showReasonError, setShowReasonError] = useState(false);

  useEffect(() => {
    if (showReasonError) {
      errorRef.current?.focus();
    }
  }, [showReasonError]);

  const preview: ChartPrintPreviewModel =
    target.kind === 'chart'
      ? buildChartPrintPreview({
          patientName: target.patientName,
          scope,
          sections,
          counts: target.counts,
          notes: target.notes,
          results: target.results,
        } satisfies ChartPrintInput)
      : buildSignedReportPreview(target.report);

  function toggleSection(section: ChartPrintSection): void {
    setSections((current) =>
      current.includes(section)
        ? current.filter((item) => item !== section)
        : [...current, section],
    );
  }

  function closePanel(): void {
    setOpen(false);
    triggerRef.current?.focus();
  }

  function print(): void {
    if (reason === null) {
      setShowReasonError(true);
      return;
    }
    setShowReasonError(false);
    setOpen(false);
    onPrint(preview, reason);
  }

  const label = chartPrintText(locale, target.kind === 'chart' ? 'chartPrint' : 'reportPrint');
  return (
    <div style={{ display: 'grid', gap: tokens.spacing.sm }}>
      <button
        ref={triggerRef}
        type="button"
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => setOpen(true)}
        style={{
          minHeight: '32px',
          padding: `${tokens.spacing.sm} ${tokens.spacing.md}`,
          border: `1px solid ${tokens.border}`,
          background: tokens.accent,
          color: tokens.background,
          cursor: 'pointer',
        }}
      >
        {label}
      </button>
      {open ? (
        <section
          id={panelId}
          aria-label={chartPrintText(locale, 'preview')}
          style={{
            display: 'grid',
            gap: tokens.spacing.md,
            border: `1px solid ${tokens.border}`,
            padding: tokens.spacing.md,
          }}
        >
          {target.kind === 'chart' ? (
            <ChartSectionPicker
              controlId={controlId}
              scope={scope}
              sections={sections}
              visits={target.visits}
              locale={locale}
              onScopeChange={setScope}
              onSectionToggle={toggleSection}
            />
          ) : null}
          <ChartPrintPreview locale={locale} model={preview} />
          <fieldset style={{ display: 'grid', gap: tokens.spacing.sm }}>
            <legend>{printText(locale, 'reason')}</legend>
            {printReasons.map((option) => (
              <label key={option}>
                <input
                  type="radio"
                  name={`${controlId}-reason`}
                  value={option}
                  checked={reason === option}
                  onChange={() => {
                    setReason(option);
                    setShowReasonError(false);
                  }}
                />{' '}
                {printText(locale, printReasonKey[option])}
              </label>
            ))}
          </fieldset>
          {showReasonError ? (
            <p ref={errorRef} role="alert" tabIndex={-1} style={{ color: tokens.foreground }}>
              {printText(locale, 'reasonRequired')}
            </p>
          ) : null}
          <div style={{ display: 'flex', gap: tokens.spacing.sm }}>
            {canPrintPreview(preview) ? (
              <Button onClick={print}>{chartPrintText(locale, 'print')}</Button>
            ) : null}
            <Button variant="secondary" onClick={closePanel}>
              {chartPrintText(locale, 'close')}
            </Button>
          </div>
        </section>
      ) : null}
    </div>
  );
}
