import type { TransportError } from '../../api/transport';

/**
 * The five operational queues an operator can inspect (ADM-02, OPS-05).
 * Identifiers are business-domain names, never story or task IDs.
 */
export type QueueId =
  | 'work-items'
  | 'dead-letters'
  | 'overdue-follow-ups'
  | 'duplicate-reviews'
  | 'clinic-worklist';

/** All queues in the order their tabs are shown. */
export const queueIds: readonly QueueId[] = [
  'work-items',
  'dead-letters',
  'overdue-follow-ups',
  'duplicate-reviews',
  'clinic-worklist',
];

/** Row actions that the connected API actually exposes. */
export type QueueAction = 'assign' | 'retry' | 'escalate' | 'review';

/**
 * A queue row projected from a server response. `primary` and `detail` contain
 * opaque identifiers and non-sensitive domain text only; no patient name or
 * free-text clinical payload is carried into the view.
 */
export interface QueueRow {
  /** Stable domain key used as the table row identity. */
  id: string;
  /** First visible column: an opaque identifier. */
  primary: string;
  /** Second visible column: a non-sensitive discriminator. */
  detail: string;
  /** Textual state, never colour alone. */
  state: string;
  /** Server instant used to compute age, absent when the endpoint has none. */
  occurredAt: string | undefined;
}

/** The loaded state of one queue. A failed queue does not hide its siblings. */
export type QueueLoad =
  | { status: 'ready'; rows: readonly QueueRow[] }
  | { status: 'error'; error: TransportError };

/** One observation of every queue, assembled from independent requests. */
export type QueueCollection = Record<QueueId, QueueLoad>;
