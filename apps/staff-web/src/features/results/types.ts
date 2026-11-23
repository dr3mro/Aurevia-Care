import type { PersonName } from '../../i18n/format';
import type { ResultsMessageKey } from './resultsMessages';

/** The three inbox views offered by the results screen chips. */
export type ResultsFilter = 'mine' | 'coverage' | 'unmatched';

/** The workflow kind that produced an inbox row (XCH-04). */
export type ResultKind = 'final' | 'corrected' | 'critical';

/** Clinical review state, deliberately separate from delivery state (XCH-02). */
export type ClinicalReviewState = 'unreviewed' | 'acknowledged' | 'action-pending' | 'resolved';

/** The identifier that differs between a report and its closest order, or none. */
export type DifferingField = 'name' | 'dateOfBirth' | 'accession' | 'none';

/**
 * One dated step in a critical finding's escalation trail.
 *
 * `descriptionKey` names a message in the feature bundle so the event is fully
 * bilingual. `values` carries only synthetic placeholder values; no clinical
 * payload is placed in a URL or a title.
 */
export interface FindingEvent {
  readonly id: string;
  readonly at: string;
  readonly descriptionKey: ResultsMessageKey;
  readonly values: Readonly<Record<string, string>>;
}

/**
 * A verified critical finding that still needs clinical action (XCH-05).
 *
 * `dueAt` and `verifiedAt` are UTC instants. `readBackRecipient` is supplied by
 * the authenticated record; it is absent until a recipient is confirmed, and a
 * read-back must never be recorded without one.
 */
export interface CriticalFinding {
  readonly id: string;
  readonly organizationId: string;
  readonly testName: string;
  readonly value: string;
  readonly patient: PersonName;
  readonly mrn: string;
  readonly accession: string;
  readonly verifiedAt: string;
  readonly dueAt: string;
  readonly events: readonly FindingEvent[];
  readonly readBackRecipient: string | undefined;
  readonly owner: string | undefined;
  readonly coverage: string;
}

/** One final, corrected or critical result in the inbox (XCH-04). */
export interface InboxRow {
  readonly id: string;
  readonly organizationId: string;
  readonly patient: PersonName;
  readonly mrn: string;
  readonly reportName: string;
  readonly reportDate: string;
  readonly source: string;
  readonly kind: ResultKind;
  readonly reviewState: ClinicalReviewState;
  readonly owner: string;
  readonly coverageQueue: string;
  readonly unmatched: boolean;
}

/** The identity values that must match before a report may be linked to an order. */
export interface ReconciliationIdentity {
  readonly patient: PersonName;
  readonly dateOfBirth: string;
  readonly accession: string;
}

/**
 * A report awaiting reconciliation against the closest order (XCH-04).
 *
 * `differingField` is computed by the exchange service from the report and the
 * order; `'none'` is the only state in which linking is permitted. It is carried
 * as data rather than recomputed in the view so the screen never second-guesses
 * the authoritative comparison.
 */
export interface ReconciliationCase {
  readonly id: string;
  readonly organizationId: string;
  readonly report: ReconciliationIdentity;
  readonly closestOrder: ReconciliationIdentity;
  readonly differingField: DifferingField;
}

/** All tenant-scoped data the results screen renders. */
export interface ResultsSnapshot {
  readonly organizationId: string;
  readonly findings: readonly CriticalFinding[];
  readonly rows: readonly InboxRow[];
  readonly reconciliation: ReconciliationCase | undefined;
}
