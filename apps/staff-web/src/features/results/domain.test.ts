import { describe, expect, it } from 'vitest';
import {
  COUNTDOWN_INTERVAL_MS,
  countdownText,
  escalationTarget,
  isNonBlank,
  isOverdue,
  outcomeFrom,
  sortByDueAt,
} from './domain';
import type { CriticalResult } from './domain';

/** Fixed instant used by every countdown assertion (synthetic, no patient data). */
const NOW = Date.parse('2026-03-08T12:00:00Z');

/** Build a synthetic critical result with the fields the domain helpers read. */
function result(overrides: Partial<CriticalResult> = {}): CriticalResult {
  return {
    id: 'critical-1',
    version: 2,
    patientId: 'SYN-0001',
    sourceSystem: 'SYNTHETIC-LAB',
    sourceId: 'SYN-SOURCE-1',
    orderReference: null,
    accession: null,
    resultSummary: 'Synthetic critical finding',
    observedValue: '2.1',
    unit: 'mg/L',
    readBackRequired: false,
    state: 'awaiting_ack',
    verifiedBySubject: null,
    verifiedAt: null,
    ownerSubject: 'clinician.a',
    dueAt: '2026-03-08T13:00:00Z',
    primaryChannel: 'PRIMARY_CHANNEL',
    backupChannel: 'BACKUP_CHANNEL',
    backupRecipient: 'clinician.b',
    createdAt: '2026-03-08T11:00:00Z',
    ...overrides,
  };
}

describe('sortByDueAt', () => {
  it('orders results by due time ascending without mutating the input', () => {
    const first = result({ id: 'first', dueAt: '2026-03-08T09:00:00Z' });
    const second = result({ id: 'second', dueAt: '2026-03-08T14:00:00Z' });
    const input = [second, first];
    expect(sortByDueAt(input).map((item) => item.id)).toEqual(['first', 'second']);
    expect(input.map((item) => item.id)).toEqual(['second', 'first']);
  });

  it('places results without a due time after dated ones', () => {
    const dated = result({ id: 'dated' });
    const undated = result({ id: 'undated', dueAt: null });
    expect(sortByDueAt([undated, dated]).map((item) => item.id)).toEqual(['dated', 'undated']);
  });

  it('keeps two undated results in their original order', () => {
    const one = result({ id: 'one', dueAt: null });
    const two = result({ id: 'two', dueAt: null });
    expect(sortByDueAt([one, two]).map((item) => item.id)).toEqual(['one', 'two']);
  });

  it('moves an undated result behind a dated one regardless of input order', () => {
    const dated = result({ id: 'dated' });
    const undated = result({ id: 'undated', dueAt: null });
    expect(sortByDueAt([dated, undated]).map((item) => item.id)).toEqual(['dated', 'undated']);
  });

  it('keeps equal due times in their original order', () => {
    const one = result({ id: 'one' });
    const two = result({ id: 'two' });
    expect(sortByDueAt([one, two]).map((item) => item.id)).toEqual(['one', 'two']);
  });
});

describe('isOverdue', () => {
  it('is false when no due time was supplied', () => {
    expect(isOverdue(null, NOW)).toBe(false);
  });

  it('is true when the deadline has passed', () => {
    expect(isOverdue('2026-03-08T11:59:59Z', NOW)).toBe(true);
  });

  it('is false while the deadline is still ahead', () => {
    expect(isOverdue('2026-03-08T12:00:01Z', NOW)).toBe(false);
  });
});

describe('countdownText', () => {
  it('explains that no due time is available', () => {
    expect(countdownText(null, NOW, 'en')).toBe('No due time');
    expect(countdownText(null, NOW, 'ar')).toBe('لا يوجد موعد استحقاق');
  });

  it.each([
    ['2026-03-08T11:59:30Z', '30 seconds ago'],
    ['2026-03-08T12:00:30Z', 'in 30 seconds'],
    ['2026-03-08T12:03:00Z', 'in 3 minutes'],
    ['2026-03-08T14:00:00Z', 'in 2 hours'],
    ['2026-03-10T12:00:00Z', 'in 2 days'],
  ])('formats a countdown for %s', (dueAt, expected) => {
    expect(countdownText(dueAt, NOW, 'en')).toBe(expected);
  });

  it('formats overdue hours and days as past relative time', () => {
    expect(countdownText('2026-03-08T09:00:00Z', NOW, 'en')).toBe('3 hours ago');
    expect(countdownText('2026-03-06T12:00:00Z', NOW, 'en')).toBe('2 days ago');
  });

  it('uses Arabic relative-time words without Arabic-Indic digits', () => {
    const arabic = countdownText('2026-03-08T14:00:00Z', NOW, 'ar');
    expect(arabic).toMatch(/[\u0600-\u06FF]/);
    expect(arabic).not.toMatch(/[\u0660-\u0669]/);
  });
});

describe('escalationTarget', () => {
  it('uses the approved backup channel and recipient when the alert is overdue', () => {
    expect(escalationTarget(result({ dueAt: '2026-03-08T11:00:00Z' }), NOW)).toEqual({
      channel: 'BACKUP_CHANNEL',
      recipient: 'clinician.b',
    });
  });

  it('uses the primary channel and owner before the deadline', () => {
    expect(escalationTarget(result({ dueAt: '2026-03-08T13:00:00Z' }), NOW - 7_200_000)).toEqual({
      channel: 'PRIMARY_CHANNEL',
      recipient: 'clinician.a',
    });
  });

  it('falls back to an empty string when the primary policy values are absent', () => {
    expect(
      escalationTarget(
        result({ primaryChannel: null, backupChannel: null, ownerSubject: null, backupRecipient: null }),
        NOW,
      ),
    ).toEqual({ channel: '', recipient: '' });
  });

  it('falls back to an empty string when the overdue policy values are absent', () => {
    expect(
      escalationTarget(
        result({
          dueAt: '2026-03-08T11:00:00Z',
          primaryChannel: null,
          backupChannel: null,
          ownerSubject: null,
          backupRecipient: null,
        }),
        NOW,
      ),
    ).toEqual({ channel: '', recipient: '' });
  });
});

describe('isNonBlank', () => {
  it('rejects empty, whitespace-only and missing-like text', () => {
    expect(isNonBlank('')).toBe(false);
    expect(isNonBlank('   ')).toBe(false);
  });

  it('accepts text with content', () => {
    expect(isNonBlank(' reason ')).toBe(true);
  });
});

describe('outcomeFrom', () => {
  it('reads the bounded selector values without accepting other text', () => {
    expect(outcomeFrom('delivered')).toBe('delivered');
    expect(outcomeFrom('failed')).toBe('failed');
    expect(outcomeFrom('unexpected')).toBe('delivered');
  });
});

it('ticks the critical countdown every thirty seconds', () => {
  expect(COUNTDOWN_INTERVAL_MS).toBe(30_000);
});
