import type { Locale } from '../../i18n/messages';
import type { OperationsMessageKey } from './labels';
import type { QueueId, QueueRow, QueueTone } from './types';

/**
 * Pure queue rules. Tone is a presentation role, never the source of truth: the
 * word beside each role is what a keyboard or screen-reader user reads, and a
 * zero count is calm even when the queue type is urgent.
 */

/** The configured urgency of each queue type. */
const TONE_BY_QUEUE: Record<QueueId, QueueTone> = {
  'unsigned-encounters': 'waiting',
  'unreconciled-reports': 'waiting',
  'unacknowledged-critical-findings': 'urgent',
  'rejected-exchanges': 'waiting',
  'document-failures': 'calm',
  'missing-coverage': 'urgent',
  'revoked-accounts': 'calm',
};

/** The bilingual word key that names a tone. */
const TONE_WORD: Record<QueueTone, OperationsMessageKey> = {
  urgent: 'toneUrgent',
  waiting: 'toneWaiting',
  calm: 'toneCalm',
};

/** Resolve the configured tone of one queue type. */
export function toneForQueue(id: QueueId): QueueTone {
  return TONE_BY_QUEUE[id];
}

/** Resolve the tone actually shown: an empty queue is calm. */
export function queueTone(row: QueueRow): QueueTone {
  return row.count === 0 ? 'calm' : toneForQueue(row.id);
}

/** Resolve the word key that must accompany a tone. */
export function toneWordKey(tone: QueueTone): OperationsMessageKey {
  return TONE_WORD[tone];
}

/** True when a reason contains non-whitespace content. */
export function hasReason(value: string): boolean {
  return value.trim().length > 0;
}

/** Format a count with Latin digits in both locales (UX-01 decision D4). */
export function formatQueueCount(count: number, locale: Locale): string {
  return new Intl.NumberFormat(locale, { numberingSystem: 'latn' }).format(count);
}
