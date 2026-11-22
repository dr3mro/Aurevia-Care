import { StrictMode, useState } from 'react';
import type { ReactElement } from 'react';
import { createRoot } from 'react-dom/client';
import {
  PrintChartButton,
  PrintOrderButton,
  PrintPrescriptionButton,
} from '../../src/features/print';
import type { HandoutLanguage, PrintReason } from '../../src/features/print/components/messages';
import type {
  ChartPrintPreviewModel,
  PrintChartTarget,
} from '../../src/features/print/chart/PrintChartButton';
import { canPrintPreview } from '../../src/features/print/chart/domain';
import {
  syntheticChartPatient,
  syntheticCounts,
  syntheticNotes,
  syntheticResults,
  syntheticVisits,
} from '../../src/features/print/chart/fixtures';
import type { PrintableOrder } from '../../src/features/print/orders/types';
import type {
  PrescriptionForPrint,
  PrescriptionPrintRecord,
} from '../../src/features/print/prescription/types';
import { I18nProvider, useI18n } from '../../src/i18n/I18nProvider';
import { ThemeProvider } from '../../src/theme/ThemeProvider';

/**
 * Dev-only browser harness for the exported print buttons (PRINT-10). The
 * buttons are not yet mounted on the prescription, order and chart screens, so
 * this mounts them with their real providers and synthetic fixtures to give the
 * end-to-end journey a genuine product entry point. Vite dev serves it; it is
 * never part of the production build. Every print is recorded through the
 * protected audit boundary that the browser test stubs.
 */

/** A handout that is a PDF document, with no real patient content. */
const HANDOUT_URL = 'data:application/pdf;base64,JVBERi0xLjQK';

/** Synthetic prescription before and after the clinician attests it. */
const draftPrescription: PrescriptionForPrint = {
  id: 'RX-SYN-1',
  version: 1,
  state: 'draft',
  attested: false,
  printChannel: 'E_PRESCRIBE',
  handoutVersion: null,
};
const signedPrescription: PrescriptionForPrint = {
  ...draftPrescription,
  version: 2,
  state: 'attested',
  attested: true,
};

/** Synthetic lab order before and after it is placed. */
const draftOrder: PrintableOrder = {
  id: 'ORD-SYN-1',
  category: 'lab',
  status: 'draft',
  tests: [{ code: 'LAB-GLU', name: 'Fasting glucose', nameLocal: 'جلوكوم صيامي' }],
  clinicalQuestion: 'Synthetic polyuria workup',
  priority: 'routine',
  preparationInstructions: 'Fast for eight hours before collection.',
  whereToGo: 'Laboratory, level two',
};
const placedOrder: PrintableOrder = { ...draftOrder, status: 'placed' };

/** Synthetic chart summary with one signed note and one released result. */
const chartTarget: PrintChartTarget = {
  kind: 'chart',
  patientName: syntheticChartPatient,
  counts: syntheticCounts,
  notes: syntheticNotes,
  results: syntheticResults,
  visits: syntheticVisits,
};

/** Bilingual harness actions; every value is synthetic. */
const harnessLabels = {
  en: { sign: 'Sign prescription', place: 'Place order' },
  ar: { sign: 'توقيع الوصفة', place: 'تقديم الطلب' },
} as const;

/** Post one JSON command through the real transport to the stubbed boundary. */
function post(path: string, body: Record<string, unknown>): void {
  void fetch(path, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-XSRF-TOKEN': 'synthetic-csrf-token',
    },
    body: JSON.stringify(body),
  });
}

/** The clinician workstation that owns the three print entry points. */
function PrintWorkstation(): ReactElement {
  const { locale, toggleLocale } = useI18n();
  const [signed, setSigned] = useState(false);
  const [placed, setPlaced] = useState(false);
  const labels = harnessLabels[locale];

  function sign(): void {
    setSigned(true);
    post('/api/v1/prescriptions/RX-SYN-1/sign', {});
  }

  function place(): void {
    setPlaced(true);
    post('/api/v1/orders/ORD-SYN-1/place', {});
  }

  function recordPrescription(record: PrescriptionPrintRecord): void {
    post('/api/v1/printing/audit', {
      kind: 'prescription',
      reason: record.reason,
      language: record.language,
      action: record.action,
      copy: record.copy,
      reprint: record.reprint,
      version: record.sourceVersion,
    });
  }

  function printOrders(
    sheets: readonly (readonly PrintableOrder[])[],
    reason: PrintReason,
    language: HandoutLanguage,
  ): void {
    post('/api/v1/printing/audit', {
      kind: 'lab-order',
      reason,
      language,
      sheetCount: sheets.length,
      orderIds: sheets.flat().map((order) => order.id),
    });
  }

  function printChart(model: ChartPrintPreviewModel, reason: PrintReason): void {
    post('/api/v1/printing/audit', {
      kind: 'chart-summary',
      reason,
      language: locale,
      printable: canPrintPreview(model),
      leftOutCount: model.leftOut.length,
    });
  }

  return (
    <>
      <button type="button" onClick={toggleLocale}>
        {locale === 'en' ? 'العربية' : 'English'}
      </button>
      <button type="button" onClick={sign}>
        {labels.sign}
      </button>
      <PrintPrescriptionButton
        prescription={signed ? signedPrescription : draftPrescription}
        handoutUrl={HANDOUT_URL}
        onRecord={recordPrescription}
      />
      <button type="button" onClick={place}>
        {labels.place}
      </button>
      <PrintOrderButton orders={placed ? [placedOrder] : [draftOrder]} onPrint={printOrders} />
      <PrintChartButton locale={locale} target={chartTarget} onPrint={printChart} />
    </>
  );
}

/** Mount the harness tree with the same providers the staff portal uses. */
function Harness(): ReactElement {
  return (
    <StrictMode>
      <ThemeProvider>
        <I18nProvider>
          <PrintWorkstation />
        </I18nProvider>
      </ThemeProvider>
    </StrictMode>
  );
}

const container = document.getElementById('print-harness');
if (container === null) {
  throw new Error('print-harness container is required');
}
createRoot(container).render(<Harness />);
