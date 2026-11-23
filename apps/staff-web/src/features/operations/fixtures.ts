import { defined } from '../../components/invariant';
import { syntheticOrganizations } from '../../fixtures/synthetic';
import type { MissingCoverage, QueuesView, QueueRow } from './types';

/**
 * Clearly synthetic queue data for two tenants whose local row identifiers are
 * deliberately identical. Real operational counts are never copied here.
 */

/** Synthetic alpha tenant identifier. */
export const alphaOrganizationId = defined(
  syntheticOrganizations[0],
  'alpha organization',
).id;

/** Synthetic beta tenant identifier. */
export const betaOrganizationId = defined(syntheticOrganizations[1], 'beta organization').id;

/** Seven synthetic alpha queue rows. */
const alphaRows: readonly QueueRow[] = [
  { id: 'unsigned-encounters', count: 6, noteKey: 'noteOldest', version: 1 },
  { id: 'unreconciled-reports', count: 2, noteKey: 'noteWaitingLab', version: 1 },
  { id: 'unacknowledged-critical-findings', count: 2, noteKey: 'noteOverdue', version: 3 },
  { id: 'rejected-exchanges', count: 1, noteKey: 'noteNeedsReason', version: 2 },
  { id: 'document-failures', count: 0, noteKey: 'noteScanFailed', version: 1 },
  { id: 'missing-coverage', count: 1, noteKey: 'noteSunday', version: 2 },
  { id: 'revoked-accounts', count: 3, noteKey: 'noteReviewLinks', version: 1 },
];

/** Synthetic beta coverage gap; the local shape is the same as alpha's. */
const betaCoverage: MissingCoverage = {
  unit: 'Synthetic night desk',
  window: 'Sunday 18:00 to 22:00',
  version: 1,
};

/** Synthetic alpha queue view, matching the approved picture. */
export const alphaQueuesView: QueuesView = {
  rows: alphaRows,
  missingCoverage: {
    unit: 'Imaging desk',
    window: 'Sunday 8:00 to 14:00',
    version: 2,
  },
};

/** Synthetic beta queue view with different counts and the same local ids. */
export const betaQueuesView: QueuesView = {
  rows: alphaRows.map((row) => ({ ...row, count: row.count + 10, version: 1 })),
  missingCoverage: betaCoverage,
};
