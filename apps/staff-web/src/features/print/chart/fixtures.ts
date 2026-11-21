import type { PersonName } from '../../../i18n/format';
import type {
  ChartPrintSection,
  ChartPrintVisit,
  PrintableNote,
  PrintableResult,
  SignedReport,
} from './domain';

/** Clearly synthetic patient used only by print-preview examples and tests. */
export const syntheticChartPatient: PersonName = {
  latinName: 'Synthetic Layla',
  localName: 'ليلى تجريبية',
};

/** Synthetic visits with colliding local labels to exercise scope selection. */
export const syntheticVisits: readonly ChartPrintVisit[] = [
  { id: 'visit-001', label: 'Synthetic visit 1' },
  { id: 'visit-002', label: 'Synthetic visit 2' },
];

/** One signed in-scope note, one unsigned draft and one out-of-range note. */
export const syntheticNotes: readonly PrintableNote[] = [
  {
    id: 'note-001',
    title: 'Synthetic signed visit note',
    state: 'signed',
    encounterId: 'visit-001',
    effectiveOn: '2026-03-02',
  },
  {
    id: 'note-002',
    title: 'Synthetic unsigned draft note',
    state: 'draft',
    encounterId: 'visit-001',
    effectiveOn: '2026-03-02',
  },
  {
    id: 'note-003',
    title: 'Synthetic out-of-range signed note',
    state: 'signed',
    encounterId: 'visit-002',
    effectiveOn: '2026-04-10',
  },
];

/** A released and a withheld synthetic result. */
export const syntheticResults: readonly PrintableResult[] = [
  { id: 'result-001', title: 'Synthetic released result', withheld: false },
  { id: 'result-002', title: 'Synthetic withheld result', withheld: true },
];

/** Counts of coded chart content for the four non-derived sections. */
export const syntheticCounts: Readonly<Record<ChartPrintSection, number>> = {
  problems: 2,
  allergies: 1,
  medications: 1,
  vitals: 3,
  notes: 0,
  results: 0,
};

/** A signed synthetic report whose provenance can be shown in the preview. */
export const syntheticSignedReport: SignedReport = {
  id: 'report-001',
  title: 'Synthetic signed report',
  state: 'signed',
  signer: 'synthetic.clinician',
  signedAt: '2026-03-09T12:00:00Z',
};

/** A draft synthetic report that must never be printable. */
export const syntheticDraftReport: SignedReport = {
  id: 'report-002',
  title: 'Synthetic draft report',
  state: 'draft',
};
