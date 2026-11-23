import { describe, expect, it } from 'vitest';
import {
  buildChartPrintPreview,
  buildSignedReportPreview,
  canPrintPreview,
  chartPrintSections,
  noteInScope,
} from './domain';
import type { ChartPrintScope, PrintableNote, PrintableResult } from './domain';
import {
  syntheticChartPatient,
  syntheticCounts,
  syntheticDraftReport,
  syntheticNotes,
  syntheticResults,
  syntheticSignedReport,
} from './fixtures';

const visitScope: ChartPrintScope = { kind: 'visit', visitId: 'visit-001' };
const rangeScope: ChartPrintScope = { kind: 'range', from: '2026-03-01', to: '2026-03-31' };

describe('chart print section contract', () => {
  it('offers a stable, complete section order', () => {
    expect([...chartPrintSections]).toEqual([
      'problems',
      'allergies',
      'medications',
      'vitals',
      'notes',
      'results',
    ]);
  });
});

describe('note scope predicate', () => {
  const note: PrintableNote = required(syntheticNotes[0]);

  it('matches the visit identifier exactly', () => {
    expect(noteInScope(note, visitScope)).toBe(true);
    expect(noteInScope(note, { kind: 'visit', visitId: 'visit-999' })).toBe(false);
  });

  it('excludes a note with no effective date from a date range', () => {
    expect(
      noteInScope(
        { id: note.id, title: note.title, state: note.state, encounterId: note.encounterId },
        rangeScope,
      ),
    ).toBe(false);
  });

  it('excludes a note before the start of the range', () => {
    expect(noteInScope({ ...note, effectiveOn: '2026-02-28' }, rangeScope)).toBe(false);
  });

  it('excludes a note after the end of the range', () => {
    expect(noteInScope({ ...note, effectiveOn: '2026-04-01' }, rangeScope)).toBe(false);
  });

  it('includes a note inside the range, including its boundaries', () => {
    expect(noteInScope({ ...note, effectiveOn: '2026-03-01' }, rangeScope)).toBe(true);
    expect(noteInScope({ ...note, effectiveOn: '2026-03-31' }, rangeScope)).toBe(true);
  });
});

describe('chart summary print preview', () => {
  it('includes every selected section and counts only signed, released content', () => {
    const preview = buildChartPrintPreview({
      patientName: syntheticChartPatient,
      scope: visitScope,
      sections: chartPrintSections,
      counts: syntheticCounts,
      notes: syntheticNotes,
      results: syntheticResults,
    });
    expect(preview.kind).toBe('chart');
    expect(preview.included).toEqual([
      { section: 'problems', count: 2 },
      { section: 'allergies', count: 1 },
      { section: 'medications', count: 1 },
      { section: 'vitals', count: 3 },
      { section: 'notes', count: 1 },
      { section: 'results', count: 1 },
    ]);
  });

  it('lists unsigned drafts and withheld results with distinct reasons', () => {
    const preview = buildChartPrintPreview({
      patientName: syntheticChartPatient,
      scope: visitScope,
      sections: chartPrintSections,
      counts: syntheticCounts,
      notes: syntheticNotes,
      results: syntheticResults,
    });
    expect(preview.leftOut).toEqual([
      { id: 'note-002', title: 'Synthetic unsigned draft note', reason: 'unsigned' },
      { id: 'result-002', title: 'Synthetic withheld result', reason: 'withheld' },
    ]);
  });

  it('keeps only the sections the clinician chose', () => {
    const preview = buildChartPrintPreview({
      patientName: syntheticChartPatient,
      scope: visitScope,
      sections: ['vitals', 'notes'],
      counts: syntheticCounts,
      notes: syntheticNotes,
      results: syntheticResults,
    });
    expect(preview.included.map((item) => item.section)).toEqual(['vitals', 'notes']);
    expect(preview.included.find((item) => item.section === 'notes')?.count).toBe(1);
  });

  it('returns no included sections when the clinician deselects every section', () => {
    const preview = buildChartPrintPreview({
      patientName: syntheticChartPatient,
      scope: visitScope,
      sections: [],
      counts: syntheticCounts,
      notes: syntheticNotes,
      results: syntheticResults,
    });
    expect(preview.included).toEqual([]);
  });

  it('excludes notes outside the chosen date range', () => {
    const preview = buildChartPrintPreview({
      patientName: syntheticChartPatient,
      scope: rangeScope,
      sections: ['notes'],
      counts: syntheticCounts,
      notes: syntheticNotes,
      results: syntheticResults,
    });
    expect(preview.included).toEqual([{ section: 'notes', count: 1 }]);
    expect(preview.leftOut.map((item) => item.id)).toEqual(['note-002']);
    expect(preview.scope).toEqual(rangeScope);
  });

  it('counts released results when the results section is selected', () => {
    const releasedOnly: readonly PrintableResult[] = [
      { id: 'r1', title: 'Synthetic released result', withheld: false },
    ];
    const preview = buildChartPrintPreview({
      patientName: syntheticChartPatient,
      scope: visitScope,
      sections: ['results'],
      counts: syntheticCounts,
      notes: [],
      results: releasedOnly,
    });
    expect(preview.included).toEqual([{ section: 'results', count: 1 }]);
    expect(preview.leftOut).toEqual([]);
  });
});

describe('print permission on a preview', () => {
  it('allows a chart summary and a signed report, but never a draft report', () => {
    const chart = buildChartPrintPreview({
      patientName: syntheticChartPatient,
      scope: visitScope,
      sections: chartPrintSections,
      counts: syntheticCounts,
      notes: syntheticNotes,
      results: syntheticResults,
    });
    expect(canPrintPreview(chart)).toBe(true);
    expect(canPrintPreview(buildSignedReportPreview(syntheticSignedReport))).toBe(true);
    expect(canPrintPreview(buildSignedReportPreview(syntheticDraftReport))).toBe(false);
  });
});

describe('signed report print preview', () => {
  it('prints a signed report with its provenance and no omissions', () => {
    const preview = buildSignedReportPreview(syntheticSignedReport);
    expect(preview).toEqual({
      kind: 'report',
      reportId: 'report-001',
      title: 'Synthetic signed report',
      printable: true,
      provenance: 'synthetic.clinician · 2026-03-09T12:00:00Z',
      leftOut: [],
    });
  });

  it('refuses a draft report and lists it as unsigned', () => {
    const preview = buildSignedReportPreview(syntheticDraftReport);
    expect(preview).toEqual({
      kind: 'report',
      reportId: 'report-002',
      title: 'Synthetic draft report',
      printable: false,
      leftOut: [{ id: 'report-002', title: 'Synthetic draft report', reason: 'unsigned' }],
    });
    expect('provenance' in preview).toBe(false);
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
