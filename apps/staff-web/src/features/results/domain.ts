import type { ResultsMessageKey } from './resultsMessages';
import type {
  CriticalFinding,
  InboxRow,
  ReconciliationCase,
  ResultsFilter,
  ResultsSnapshot,
} from './types';

/**
 * Decide whether a page-load announcement may interrupt the user.
 *
 * A screen reader user typing into a field must not have their input broken by
 * a critical-result announcement; the durable region stays on screen either
 * way (accessibility-patterns.md section 6.4).
 */
export function shouldAnnounceCritical(active: Element | null): boolean {
  if (!(active instanceof HTMLElement)) {
    return true;
  }
  return active.tagName !== 'INPUT' && active.tagName !== 'TEXTAREA' && !active.isContentEditable;
}

/** Decide whether a loaded snapshot has nothing at all to display. */
export function isEmptySnapshot(snapshot: ResultsSnapshot): boolean {
  return (
    snapshot.findings.length === 0 &&
    snapshot.rows.length === 0 &&
    snapshot.reconciliation === undefined
  );
}

/**
 * Decide whether a critical finding has passed its clinical deadline.
 *
 * The clock is always injected: a fixed instant keeps the overdue state
 * deterministic in tests, and the comparison is inclusive at the due instant.
 */
export function isOverdue(finding: CriticalFinding, now: string): boolean {
  return Date.parse(finding.dueAt) <= Date.parse(now);
}

/**
 * Whole minutes a finding is past its deadline, never negative.
 *
 * Used only to word the overdue banner; the deadline itself carries the
 * authoritative instant.
 */
export function overdueMinutes(finding: CriticalFinding, now: string): number {
  const beyond = Date.parse(now) - Date.parse(finding.dueAt);
  return beyond > 0 ? Math.floor(beyond / 60_000) : 0;
}

/**
 * Project an inbox for one chip without changing any row's clinical state.
 *
 * `mine` means the viewer owns the row. `coverage` means the viewer is the
 * backup queue for a row somebody else owns. `unmatched` means reconciliation
 * has not found an order that agrees with the report.
 */
export function filterInbox(
  rows: readonly InboxRow[],
  filter: ResultsFilter,
  viewerId: string,
): readonly InboxRow[] {
  if (filter === 'mine') {
    return rows.filter((row) => row.owner === viewerId);
  }
  if (filter === 'coverage') {
    return rows.filter((row) => row.coverageQueue === viewerId && row.owner !== viewerId);
  }
  return rows.filter((row) => row.unmatched);
}

/** Chip counts derived from the same rows the filters use, never hard-coded. */
export function inboxCounts(
  rows: readonly InboxRow[],
  viewerId: string,
): Record<ResultsFilter, number> {
  return {
    mine: filterInbox(rows, 'mine', viewerId).length,
    coverage: filterInbox(rows, 'coverage', viewerId).length,
    unmatched: filterInbox(rows, 'unmatched', viewerId).length,
  };
}

/**
 * Validate a read-back recipient before the action is dispatched.
 *
 * Read-back is a separate clinical record (XCH-05): a missing recipient must
 * fail closed with a worded error rather than recording an anonymous contact.
 */
export function readBackError(recipient: string | undefined): ResultsMessageKey | undefined {
  return recipient === undefined || recipient.trim() === '' ? 'readBackRecipientRequired' : undefined;
}

/**
 * Decide whether a report may be linked to its closest order.
 *
 * Only an authoritative `none` differing field permits a link; every mismatch
 * fails closed so a wrong-patient association cannot be created by a click.
 */
export function canLink(reconciliation: ReconciliationCase): boolean {
  return reconciliation.differingField === 'none';
}
