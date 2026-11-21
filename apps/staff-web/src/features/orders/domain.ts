import type { TransportErrorKind } from '../../api/transport';
import type { Role } from '../../app/navigation';

/** Recipient-side wire states of an exchanged order (XCH-02, XCH-03). */
export type OrderState =
  | 'requested'
  | 'accepted'
  | 'rejected'
  | 'clarification_requested'
  | 'change_pending'
  | 'cancellation_pending'
  | 'cancelled'
  | 'revoked';

/** Released priority carried in the order snapshot (XCH-01). */
export type Priority = 'routine' | 'urgent' | 'stat';

/** Recipient responses surfaced by this screen. */
export type ActionKind = 'accept' | 'reject' | 'clarify';

/** The two responses that carry a durable typed reason. */
export type ReasonAction = 'reject' | 'clarify';

/** One issued idempotency key bound to the exact action and payload it created. */
export interface ActionKey {
  action: ActionKind;
  /** Verbatim reason/question (empty for accept); a changed payload needs a new key. */
  payload: string;
  key: string;
}

/** One row of the order worklist. Identifiers are opaque; no clinical payload. */
export interface OrderRow {
  id: string;
  orderCode: string;
  patientId: string;
  priority: Priority;
  destinationOrg: string;
  createdAt: string;
  version: number;
}

/** The order fields shown on the detail screen plus its current task state. */
export interface OrderDetail extends OrderRow {
  organizationId: string;
  encounterRef: string;
  clinicalQuestion: string;
  sourceActor: string;
  responsibleClinician: string;
  workQueue: string;
  state: OrderState;
}

/** One append-only response-history entry rendered in the task-state timeline. */
export interface TaskEvent {
  id: string;
  /** The XCH-03 order version after this transition; drives `If-Match`. */
  orderVersion: number;
  action: string;
  fromState: string;
  toState: string;
  reason: string | null;
  actorId: string;
  occurredAt: string;
}

/** Every wire state, in the order shown by the status filter. */
export const ORDER_STATES: readonly OrderState[] = [
  'requested',
  'accepted',
  'rejected',
  'clarification_requested',
  'change_pending',
  'cancellation_pending',
  'cancelled',
  'revoked',
];

/** Staff roles that may answer a recipient order, from the screen map. */
const RESPONDER_ROLES: readonly Role[] = ['clinician', 'lab', 'radiology'];

/**
 * Return the recipient responses the signed-in role may take in this state.
 *
 * Only a `requested` work item awaits a first decision; later states need the
 * separate change/cancellation responses, which are outside this screen. The
 * role is the session role, never a value read from the URL or browser storage.
 *
 * @param state current recipient state of the order
 * @param role role of the signed-in user in the active organization
 * @returns the response kinds to render, empty when none is permitted
 */
export function availableActions(state: OrderState, role: Role): readonly ActionKind[] {
  if (state !== 'requested') {
    return [];
  }
  return RESPONDER_ROLES.includes(role) ? ['accept', 'reject', 'clarify'] : [];
}

/**
 * Bind an idempotency key to one exact command. Retrying the same action with
 * the same payload returns the same key (so the server can deduplicate a retry);
 * changing either the action or the typed reason/question issues a fresh key.
 * Reusing a key with a different payload would make the server answer 409 for
 * the original request (ARC-03), leaving no recoverable path from a conflict.
 *
 * @param previous key issued for the previous submission, if any
 * @param action action about to be submitted
 * @param payload verbatim reason/question sent with the command
 * @param generate source of a fresh opaque key
 * @returns the key to send with this submission
 */
export function idempotencyKeyFor(
  previous: ActionKey | undefined,
  action: ActionKind,
  payload: string,
  generate: () => string,
): ActionKey {
  if (previous !== undefined && previous.action === action && previous.payload === payload) {
    return previous;
  }
  return { action, payload, key: generate() };
}

/**
 * Validate a required reason/question at the trust boundary.
 *
 * @param value text typed by the user
 * @returns `true` when a non-blank value remains after trimming
 */
export function validateReason(value: string): boolean {
  return value.trim().length > 0;
}

/**
 * Whether a failed command means the caller read a stale order version.
 * ARC-04 uses both 409 (invalid transition / reused key) and 412 (stale
 * `If-Match`); both must preserve the typed reason and show the conflict state.
 */
export function isConflict(kind: TransportErrorKind): boolean {
  return kind === 'conflict' || kind === 'precondition-required';
}

/**
 * Derive the current task state from the newest response-history transition.
 * A newly released order has no response history yet and is still `requested`.
 * The API emits only known wire states, so the cast is bounded by that contract.
 */
export function currentState(history: readonly TaskEvent[]): OrderState {
  const newest = history[history.length - 1];
  return newest === undefined ? 'requested' : (newest.toState as OrderState);
}

/**
 * Resolve the version a recipient command must send as `If-Match`. XCH-03
 * appends each event with the order version after the transition, so the newest
 * event is authoritative when present; a newly released order has no history
 * and uses the released version from the order projection.
 */
export function versionFor(history: readonly TaskEvent[], releasedVersion: number): number {
  const newest = history[history.length - 1];
  return newest === undefined ? releasedVersion : newest.orderVersion;
}
