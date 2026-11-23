import type { PersonName } from '../../../i18n/format';

/**
 * Print-policy model for a chart summary or a signed report.
 *
 * The screen owns the raw clinical record and supplies a small, explicitly
 * scoped projection. This module decides only what may be printed and what must
 * be named as left out: unsigned drafts and withheld results never reach a
 * handout, and the omission is shown in words rather than silently dropped
 * (CLN-03, CLN-05, WEB-09). No clinical meaning is derived here.
 */

/** Sections a clinician may include in a chart-summary handout. */
export const chartPrintSections = [
  'problems',
  'allergies',
  'medications',
  'vitals',
  'notes',
  'results',
] as const;

/** One selectable chart-summary section. */
export type ChartPrintSection = (typeof chartPrintSections)[number];

/** The visit, or the inclusive civil-date range, a summary is drawn from. */
export type ChartPrintScope =
  { kind: 'visit'; visitId: string } | { kind: 'range'; from: string; to: string };

/** A selectable visit presented by the owning chart screen. */
export interface ChartPrintVisit {
  id: string;
  label: string;
}

/** Why a source item was not included in the handout. */
export type LeftOutReason = 'unsigned' | 'withheld';

/** One omitted source item, named so the clinician can see the omission. */
export interface LeftOutItem {
  id: string;
  title: string;
  reason: LeftOutReason;
}

/**
 * A note in print scope. Only a `state` of `signed` may be printed; a draft is
 * listed as left out with the `unsigned` reason.
 */
export interface PrintableNote {
  id: string;
  title: string;
  state: 'draft' | 'signed';
  encounterId: string;
  /** Civil date (YYYY-MM-DD) used by a date-range scope; absent means out of scope. */
  effectiveOn?: string;
}

/** A result in print scope; a withheld result is never released to a patient. */
export interface PrintableResult {
  id: string;
  title: string;
  withheld: boolean;
}

/** Everything the owning screen supplies for one chart-summary preview. */
export interface ChartPrintInput {
  patientName: PersonName;
  scope: ChartPrintScope;
  sections: readonly ChartPrintSection[];
  /** Counts of the four coded sections supplied by the chart screen. */
  counts: Readonly<Record<ChartPrintSection, number>>;
  notes: readonly PrintableNote[];
  results: readonly PrintableResult[];
}

/** One included section and the number of items it will print. */
export interface IncludedSection {
  section: ChartPrintSection;
  count: number;
}

/** A chart-summary preview: selected sections plus named omissions. */
export interface ChartPrintPreviewModel {
  kind: 'chart';
  patientName: PersonName;
  scope: ChartPrintScope;
  included: readonly IncludedSection[];
  leftOut: readonly LeftOutItem[];
}

/**
 * A report from its own screen. Signed reports carry provenance; a draft has no
 * printable variant and is listed as left out.
 */
export type SignedReport =
  | { id: string; title: string; state: 'signed'; signer: string; signedAt: string }
  | { id: string; title: string; state: 'draft' };

/** A signed-report preview rendered by the same button as a chart summary. */
export interface SignedReportPreviewModel {
  kind: 'report';
  reportId: string;
  title: string;
  printable: boolean;
  provenance?: string;
  leftOut: readonly LeftOutItem[];
}

/** Either preview the shared button can present. */
export type ChartPrintPreview = ChartPrintPreviewModel | SignedReportPreviewModel;

/** Whether a note belongs to the chosen visit or inclusive date range. */
export function noteInScope(note: PrintableNote, scope: ChartPrintScope): boolean {
  if (scope.kind === 'visit') {
    return note.encounterId === scope.visitId;
  }
  if (note.effectiveOn === undefined) {
    return false;
  }
  return note.effectiveOn >= scope.from && note.effectiveOn <= scope.to;
}

/** Resolve one section's printed count; notes and results are policy-derived. */
function sectionCount(
  input: ChartPrintInput,
  section: ChartPrintSection,
  signedNotes: readonly PrintableNote[],
  releasedResults: readonly PrintableResult[],
): number {
  if (section === 'notes') {
    return signedNotes.length;
  }
  if (section === 'results') {
    return releasedResults.length;
  }
  return input.counts[section];
}

/** Build the preview from the clinician's scope and section choices. */
export function buildChartPrintPreview(input: ChartPrintInput): ChartPrintPreviewModel {
  const scoped = input.notes.filter((note) => noteInScope(note, input.scope));
  const signed = scoped.filter((note) => note.state === 'signed');
  const unsigned = scoped.filter((note) => note.state === 'draft');
  const released = input.results.filter((result) => !result.withheld);
  const withheld = input.results.filter((result) => result.withheld);
  const included = chartPrintSections
    .filter((section) => input.sections.includes(section))
    .map((section) => ({ section, count: sectionCount(input, section, signed, released) }));
  // An item is only "left out" when its section was chosen; deselecting a
  // section is the clinician's explicit choice, not a silent omission.
  const leftOut: LeftOutItem[] = [
    ...(input.sections.includes('notes')
      ? unsigned.map((note) => ({ id: note.id, title: note.title, reason: 'unsigned' as const }))
      : []),
    ...(input.sections.includes('results')
      ? withheld.map((result) => ({
          id: result.id,
          title: result.title,
          reason: 'withheld' as const,
        }))
      : []),
  ];
  return {
    kind: 'chart',
    patientName: input.patientName,
    scope: input.scope,
    included,
    leftOut,
  };
}

/** Whether the preview may be handed to the print command at all. */
export function canPrintPreview(preview: ChartPrintPreview): boolean {
  return preview.kind === 'chart' || preview.printable;
}

/** Build a report preview; an unsigned draft is never printable. */
export function buildSignedReportPreview(report: SignedReport): SignedReportPreviewModel {
  if (report.state === 'signed') {
    return {
      kind: 'report',
      reportId: report.id,
      title: report.title,
      printable: true,
      provenance: `${report.signer} · ${report.signedAt}`,
      leftOut: [],
    };
  }
  return {
    kind: 'report',
    reportId: report.id,
    title: report.title,
    printable: false,
    leftOut: [{ id: report.id, title: report.title, reason: 'unsigned' }],
  };
}
