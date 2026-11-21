import type { Conversation } from './types';

/**
 * Default response target, in hours, used until a care unit publishes its
 * MSG-06 response target. It is a presentation heuristic only: the server owns
 * escalation and the SLA breach word never substitutes for a clinical decision.
 */
export const RESPONSE_TARGET_HOURS = 24;

/** Milliseconds in one hour. */
const HOUR_MS = 3_600_000;

/**
 * Decide whether an unanswered conversation has passed the response target.
 * Only `awaiting_staff` can breach: a resolved, closed or patient-awaiting
 * thread is not waiting on a unit response.
 *
 * @param conversation conversation projection returned by the API
 * @param nowMs current instant as an epoch-millisecond number
 * @returns true when the thread is unanswered and older than the target
 */
export function isSlaBreached(conversation: Conversation, nowMs: number): boolean {
  if (conversation.state !== 'awaiting_staff') {
    return false;
  }
  return nowMs - Date.parse(conversation.createdAt) > RESPONSE_TARGET_HOURS * HOUR_MS;
}
