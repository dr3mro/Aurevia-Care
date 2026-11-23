/**
 * Wire and view types for the ADM-02 operational queues (WEB-09).
 *
 * Field names follow the queue projection: a stable queue id, a count, a note
 * identifier and the version used as the compare-and-set token. The missing
 * coverage record carries its own version because assigning a backup changes a
 * different projection. No value here is patient data.
 */

/** The seven queue types an operator must be able to work. */
export const QUEUE_IDS = [
  'unsigned-encounters',
  'unreconciled-reports',
  'unacknowledged-critical-findings',
  'rejected-exchanges',
  'document-failures',
  'missing-coverage',
  'revoked-accounts',
] as const;

/** One operational queue type. */
export type QueueId = (typeof QUEUE_IDS)[number];

/** Visual role of a count pill; the word is always rendered beside the role. */
export type QueueTone = 'urgent' | 'waiting' | 'calm';

/** Reason-bearing commands an operator can record against a queue. */
export const QUEUE_ACTIONS = ['assign', 'resolve', 'retry', 'escalate'] as const;

/** One reason-bearing queue command. */
export type QueueAction = (typeof QUEUE_ACTIONS)[number];

/** Note identifiers resolved through the feature's bilingual bundle. */
export const QUEUE_NOTE_KEYS = [
  'noteOldest',
  'noteWaitingLab',
  'noteOverdue',
  'noteNeedsReason',
  'noteScanFailed',
  'noteSunday',
  'noteReviewLinks',
] as const;

/** One queue note identifier. */
export type QueueNoteKey = (typeof QUEUE_NOTE_KEYS)[number];

/** One queue row with its compare-and-set token. */
export interface QueueRow {
  readonly id: QueueId;
  readonly count: number;
  readonly noteKey: QueueNoteKey;
  readonly version: number;
}

/** The coverage gap shown below the queue card. */
export interface MissingCoverage {
  readonly unit: string;
  readonly window: string;
  readonly version: number;
}

/** One tenant's complete operational queue view. */
export interface QueuesView {
  readonly rows: readonly QueueRow[];
  readonly missingCoverage: MissingCoverage;
}

/** A draft reason with the row it targets; held by the page, never in storage. */
export interface QueueDraft {
  readonly queueId: QueueId;
  readonly action: QueueAction;
  readonly reason: string;
  readonly version: number;
}

/** A validated draft sent as one reason-bearing command. */
export type QueueCommand = QueueDraft;
