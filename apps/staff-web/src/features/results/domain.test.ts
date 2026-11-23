import { describe, expect, it } from 'vitest';

import {
  canLink,
  filterInbox,
  isEmptySnapshot,
  isOverdue,
  overdueMinutes,
  readBackError,
  shouldAnnounceCritical,
} from './domain';
import {
  emptyResultsSnapshot,
  matchedReconciliation,
  mismatchedReconciliation,
  onTrackFinding,
  overdueFinding,
  resultsViewerId,
  sampleInboxRows,
} from './fixtures';

/** A fixed instant used so every overdue assertion is deterministic. */
const NOW = '2026-03-09T08:50:00Z';

describe('isOverdue', () => {
  it('is false before the due instant', () => {
    const finding = { ...overdueFinding, dueAt: '2026-03-09T08:51:00Z' };
    expect(isOverdue(finding, NOW)).toBe(false);
  });

  it('is true exactly at the due instant', () => {
    const finding = { ...overdueFinding, dueAt: NOW };
    expect(isOverdue(finding, NOW)).toBe(true);
  });

  it('is true after the due instant', () => {
    expect(isOverdue(overdueFinding, NOW)).toBe(true);
    expect(isOverdue(onTrackFinding, NOW)).toBe(false);
  });

  it('counts whole minutes beyond the deadline', () => {
    expect(overdueMinutes(overdueFinding, NOW)).toBe(15);
    expect(overdueMinutes(onTrackFinding, NOW)).toBe(0);
  });
});

describe('filterInbox', () => {
  it('keeps only rows owned by the viewer for Mine', () => {
    const rows = filterInbox(sampleInboxRows, 'mine', resultsViewerId);
    expect(rows.map((row) => row.id)).toEqual(['result-synthetic-01', 'result-synthetic-03']);
  });

  it('keeps a coverage queue row only when someone else owns it', () => {
    const rows = filterInbox(sampleInboxRows, 'coverage', resultsViewerId);
    expect(rows.map((row) => row.id)).toEqual(['result-synthetic-02']);
  });

  it('keeps only unmatched rows for Unmatched', () => {
    const rows = filterInbox(sampleInboxRows, 'unmatched', resultsViewerId);
    expect(rows.map((row) => row.id)).toEqual(['result-synthetic-04']);
  });
});

describe('readBackError', () => {
  it('requires a recipient when none was supplied', () => {
    expect(readBackError(undefined)).toBe('readBackRecipientRequired');
  });

  it('requires a recipient when the value is empty or whitespace only', () => {
    expect(readBackError('')).toBe('readBackRecipientRequired');
    expect(readBackError('   ')).toBe('readBackRecipientRequired');
  });

  it('accepts a named recipient', () => {
    expect(readBackError('Dr Synthetic Reviewer')).toBeUndefined();
  });
});

describe('shouldAnnounceCritical', () => {
  it('announces when focus is outside a text-entry control', () => {
    expect(shouldAnnounceCritical(null)).toBe(true);
    expect(shouldAnnounceCritical(document.createElement('button'))).toBe(true);
  });

  it('stays silent while the user is typing', () => {
    expect(shouldAnnounceCritical(document.createElement('input'))).toBe(false);
    expect(shouldAnnounceCritical(document.createElement('textarea'))).toBe(false);
    const editable = document.createElement('div');
    Object.defineProperty(editable, 'isContentEditable', { value: true });
    expect(shouldAnnounceCritical(editable)).toBe(false);
  });
});

describe('isEmptySnapshot', () => {
  it('is empty only when findings, rows and reconciliation are all absent', () => {
    expect(isEmptySnapshot(emptyResultsSnapshot(resultsViewerId))).toBe(true);
  });

  it('is not empty when any part still has content', () => {
    const empty = emptyResultsSnapshot(resultsViewerId);
    expect(isEmptySnapshot({ ...empty, findings: [overdueFinding] })).toBe(false);
    expect(isEmptySnapshot({ ...empty, rows: sampleInboxRows })).toBe(false);
    expect(isEmptySnapshot({ ...empty, reconciliation: mismatchedReconciliation })).toBe(false);
  });
});

describe('canLink', () => {
  it('allows a link only when every identifier matched', () => {
    expect(canLink(matchedReconciliation)).toBe(true);
  });

  it('refuses a link when the report and order differ', () => {
    expect(canLink(mismatchedReconciliation)).toBe(false);
    expect(canLink({ ...mismatchedReconciliation, differingField: 'dateOfBirth' })).toBe(false);
    expect(canLink({ ...mismatchedReconciliation, differingField: 'accession' })).toBe(false);
  });
});
