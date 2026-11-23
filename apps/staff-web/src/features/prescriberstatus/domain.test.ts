import { describe, expect, it } from 'vitest';
import {
  syntheticDispensedEvent,
  syntheticOriginalEntry,
  syntheticPendingProposalView,
  syntheticProposal,
  syntheticSignedRevision,
  syntheticStatus,
  syntheticSubmittedEvent,
} from './fixtures';
import { statusRowLabel } from './labels';
import {
  activeEntry,
  catalogLabel,
  hasAnswer,
  hasText,
  isAnswered,
  revisionLink,
  timelineRows,
} from './domain';

describe('timelineRows', () => {
  it('keeps one row per fact and preserves the source id and fields', () => {
    const rows = timelineRows(syntheticStatus.events);

    expect(rows).toHaveLength(5);
    expect(rows.map((row) => row.id)).toEqual(syntheticStatus.events.map((event) => event.id));
    expect(rows[0]?.labelKey).toBe('eventSubmitted');
    expect(rows[1]?.labelKey).toBe('eventDeliveryConfirmed');
    expect(rows[1]?.detail).toBe('Synthetic partner confirmed receipt');
    expect(rows[0]?.detail).toBeNull();
    expect(rows[4]?.dispensedBy).toBe('synthetic.pharmacist');
    expect(rows[3]?.dispensedBy).toBeNull();
  });

  it('maps accepted, rejected and clarification facts to distinct labels', () => {
    const rows = timelineRows([
      required(syntheticStatus.events[3]),
      required(syntheticStatus.events[2]),
      { ...syntheticSubmittedEvent, type: 'rejected' },
    ]);

    expect(rows.map((row) => row.labelKey)).toEqual([
      'eventAccepted',
      'eventClarification',
      'eventRejected',
    ]);
  });

  it('preserves dispensing evidence as its own row and label', () => {
    const [dispensing] = timelineRows([syntheticDispensedEvent]);

    expect(dispensing?.labelKey).toBe('eventDispensed');
    expect(dispensing?.dispensedAt).toBe('2026-11-21T10:30:00Z');
    expect(dispensing?.handedOver).toBe('30 synthetic tablets');
  });

  it('returns no rows when the status has no facts', () => {
    expect(timelineRows([])).toEqual([]);
  });
});

describe('statusRowLabel', () => {
  it('uses a specific label for every tracked fact type', () => {
    expect(statusRowLabel('submitted')).toBe('eventSubmitted');
    expect(statusRowLabel('delivery_confirmed')).toBe('eventDeliveryConfirmed');
    expect(statusRowLabel('accepted')).toBe('eventAccepted');
    expect(statusRowLabel('rejected')).toBe('eventRejected');
    expect(statusRowLabel('clarification')).toBe('eventClarification');
    expect(statusRowLabel('dispensed')).toBe('eventDispensed');
  });

  it('falls back to the generic label for an unknown fact type', () => {
    expect(statusRowLabel('renewal')).toBe('eventOther');
  });
});

describe('answer and decision guards', () => {
  it('accepts non-blank text and rejects whitespace-only text', () => {
    expect(hasText('a synthetic answer')).toBe(true);
    expect(hasText('   ')).toBe(false);
  });

  it('accepts an answer with text, a revision, or both, but not neither', () => {
    expect(hasAnswer('text only', '')).toBe(true);
    expect(hasAnswer('', 'revision only')).toBe(true);
    expect(hasAnswer('both', 'revision')).toBe(true);
    expect(hasAnswer('  ', '')).toBe(false);
  });

  it('distinguishes an answered thread from an open one', () => {
    expect(isAnswered('answered')).toBe(true);
    expect(isAnswered('open')).toBe(false);
  });
});

describe('proposal projection helpers', () => {
  it('returns a revision only after approval', () => {
    expect(revisionLink(syntheticPendingProposalView)).toBeNull();
    expect(
      revisionLink({ ...syntheticPendingProposalView, appliedRevision: syntheticSignedRevision }),
    ).toBe(syntheticSignedRevision);
  });

  it('keeps the original active until approval supplies a replacement', () => {
    expect(activeEntry(syntheticPendingProposalView)).toBe(syntheticOriginalEntry);
    expect(
      activeEntry({ ...syntheticPendingProposalView, appliedRevision: syntheticSignedRevision }),
    ).toBe(syntheticSignedRevision.replacement);
  });

  it('renders the catalog entry as one bounded clinical label', () => {
    expect(catalogLabel(syntheticProposal.original)).toBe(
      'Syntheticmox (Synthetic Amoxicillin), 500 mg, tablet',
    );
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
