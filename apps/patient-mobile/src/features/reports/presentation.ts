import type { ReleasedReport } from './types';

/**
 * Build the correction context shown beside a released revision (PAT-02, XCH-06).
 *
 * Both parts must be present: a reason without the replaced revision, or a revision without a
 * reason, would be ambiguous for a patient reading their chart.
 */
export function correctionLabel(report: ReleasedReport): string | null {
  if (report.correctionReason === null || report.supersedesRevision === null) {
    return null;
  }
  return `Corrected revision ${report.supersedesRevision} — ${report.correctionReason}`;
}

/** Whether the report must be laid out right-to-left (UX-01). */
export function isRtl(report: ReleasedReport): boolean {
  return report.language === 'ar';
}
