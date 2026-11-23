import type { ApiLoader } from '../../api/useApi';
import { syntheticTimeZone } from '../../fixtures/synthetic';
import type {
  CriticalFinding,
  InboxRow,
  ReconciliationCase,
  ResultsSnapshot,
} from './types';

/** All identifiers and names below are invented for synthetic review. */

/** The clinician whose queue the default chips describe. */
export const resultsViewerId = 'staff-synthetic-results-owner';

/** The tenant whose fixtures are populated. */
export const resultsOrganizationId = '11111111-1111-1111-1111-111111111111';

/** A second tenant used to prove rows never cross organization boundaries. */
export const otherResultsOrganizationId = '22222222-2222-2222-2222-222222222222';

/** Facility zone used for every displayed instant. */
export const resultsTimeZone = syntheticTimeZone;

/** A fixed instant that makes the first finding overdue and the second on time. */
export const resultsNow = '2026-03-09T08:50:00Z';

/** An overdue potassium finding with no confirmed read-back recipient. */
export const overdueFinding: CriticalFinding = {
  id: 'finding-synthetic-01',
  organizationId: resultsOrganizationId,
  testName: 'Serum potassium',
  value: '6.4 mmol/L',
  patient: { latinName: 'Amira Bassam', localName: 'أميرة بسام' },
  mrn: 'MRN-SYN-4417',
  accession: 'ACC-SYN-90031',
  verifiedAt: '2026-03-09T08:05:00Z',
  dueAt: '2026-03-09T08:35:00Z',
  events: [
    {
      id: 'event-synthetic-01',
      at: '2026-03-09T08:05:00Z',
      descriptionKey: 'eventVerified',
      values: {},
    },
    {
      id: 'event-synthetic-02',
      at: '2026-03-09T08:07:00Z',
      descriptionKey: 'eventAlertSent',
      values: {},
    },
    {
      id: 'event-synthetic-03',
      at: '2026-03-09T08:22:00Z',
      descriptionKey: 'eventAttempt',
      values: {},
    },
    {
      id: 'event-synthetic-04',
      at: '2026-03-09T08:35:00Z',
      descriptionKey: 'eventEscalated',
      values: {},
    },
  ],
  readBackRecipient: undefined,
  owner: 'Dr Synthetic Oryx',
  coverage: 'Results backup queue',
};

/** A finding still inside its deadline with a confirmed read-back recipient. */
export const onTrackFinding: CriticalFinding = {
  id: 'finding-synthetic-02',
  organizationId: resultsOrganizationId,
  testName: 'Plasma glucose',
  value: '2.9 mmol/L',
  patient: { latinName: 'Tariq Nasser', localName: 'طارق ناصر' },
  mrn: 'MRN-SYN-6620',
  accession: 'ACC-SYN-90044',
  verifiedAt: '2026-03-09T08:40:00Z',
  dueAt: '2026-03-09T09:10:00Z',
  events: [
    {
      id: 'event-synthetic-05',
      at: '2026-03-09T08:40:00Z',
      descriptionKey: 'eventVerified',
      values: {},
    },
    {
      id: 'event-synthetic-06',
      at: '2026-03-09T08:43:00Z',
      descriptionKey: 'eventAlertSent',
      values: {},
    },
  ],
  readBackRecipient: 'Dr Synthetic Reviewer',
  owner: undefined,
  coverage: 'Escalation queue',
};

/** Inbox rows spanning every kind, review state and chip projection. */
export const sampleInboxRows: readonly InboxRow[] = [
  {
    id: 'result-synthetic-01',
    organizationId: resultsOrganizationId,
    patient: { latinName: 'Nadia Farouk', localName: 'نادية فاروق' },
    mrn: 'MRN-SYN-1138',
    reportName: 'HbA1c assay',
    reportDate: '2026-03-04',
    source: 'Synthetic North Lab',
    kind: 'final',
    reviewState: 'unreviewed',
    owner: resultsViewerId,
    coverageQueue: 'queue-alpha',
    unmatched: false,
  },
  {
    id: 'result-synthetic-02',
    organizationId: resultsOrganizationId,
    patient: { latinName: 'Yusuf Idris', localName: 'يوسف إدريس' },
    mrn: 'MRN-SYN-2274',
    reportName: 'Lipid profile',
    reportDate: '2026-03-06',
    source: 'Synthetic North Lab',
    kind: 'corrected',
    reviewState: 'action-pending',
    owner: 'staff-synthetic-other',
    coverageQueue: resultsViewerId,
    unmatched: false,
  },
  {
    id: 'result-synthetic-03',
    organizationId: resultsOrganizationId,
    patient: { latinName: 'Amira Bassam', localName: 'أميرة بسام' },
    mrn: 'MRN-SYN-4417',
    reportName: 'Serum potassium',
    reportDate: '2026-03-09',
    source: 'Synthetic Harbor Lab',
    kind: 'critical',
    reviewState: 'action-pending',
    owner: resultsViewerId,
    coverageQueue: resultsViewerId,
    unmatched: false,
  },
  {
    id: 'result-synthetic-04',
    organizationId: resultsOrganizationId,
    patient: { latinName: 'Layla Munir', localName: 'ليلى منير' },
    mrn: 'MRN-SYN-3391',
    reportName: 'Urinalysis',
    reportDate: '2026-03-08',
    source: 'Synthetic Harbor Lab',
    kind: 'final',
    reviewState: 'unreviewed',
    owner: 'staff-synthetic-other',
    coverageQueue: 'queue-alpha',
    unmatched: true,
  },
  {
    id: 'result-synthetic-05',
    organizationId: resultsOrganizationId,
    patient: { latinName: 'Karim Sayed', localName: 'كريم سيد' },
    mrn: 'MRN-SYN-5520',
    reportName: 'Haemoglobin',
    reportDate: '2026-03-07',
    source: 'Synthetic Valley Lab',
    kind: 'final',
    reviewState: 'acknowledged',
    owner: 'staff-synthetic-other',
    coverageQueue: 'queue-beta',
    unmatched: false,
  },
];

/** A report whose patient name does not agree with the closest order. */
export const mismatchedReconciliation: ReconciliationCase = {
  id: 'reconciliation-synthetic-01',
  organizationId: resultsOrganizationId,
  report: {
    patient: { latinName: 'Lina Farouk', localName: 'لينا فاروق' },
    dateOfBirth: '1991-04-12',
    accession: 'ACC-SYN-90071',
  },
  closestOrder: {
    patient: { latinName: 'Lina Farouq', localName: 'لينا فاروق' },
    dateOfBirth: '1991-04-12',
    accession: 'ACC-SYN-90071',
  },
  differingField: 'name',
};

/** The same case after the lab confirms the identifiers agree. */
export const matchedReconciliation: ReconciliationCase = {
  ...mismatchedReconciliation,
  differingField: 'none',
};

/** A snapshot with no data, used for an empty tenant or an empty filter. */
export function emptyResultsSnapshot(organizationId: string): ResultsSnapshot {
  return { organizationId, findings: [], rows: [], reconciliation: undefined };
}

/** The fully populated snapshot for the synthetic results tenant. */
export function resultsSnapshot(organizationId: string): ResultsSnapshot {
  return {
    organizationId,
    findings: [overdueFinding, onTrackFinding],
    rows: sampleInboxRows,
    reconciliation: mismatchedReconciliation,
  };
}

/**
 * A local loader for a screen with no published results-inbox endpoint.
 *
 * It resolves only synthetic fixtures and never constructs a URL. The
 * organization id scopes the answer so switching tenants cannot reveal rows
 * belonging to another organization.
 */
export function loadSyntheticResults(organizationId: string): ApiLoader<ResultsSnapshot> {
  return () =>
    Promise.resolve(
      organizationId === resultsOrganizationId
        ? resultsSnapshot(organizationId)
        : emptyResultsSnapshot(organizationId),
    );
}
