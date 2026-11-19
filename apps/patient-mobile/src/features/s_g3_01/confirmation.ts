import { CONFIRMABLE_STATUSES } from './types';
import type { Appointment, ConfirmationState, ConfirmResult } from './types';

/**
 * Derive the confirmation affordance from server-owned data.
 *
 * Enablement and status come from the server projection; a client cannot
 * enable confirmation for a disabled site or a finished visit.
 *
 * @param appointment server appointment projection
 * @param enabled site confirmation enablement evaluated by the server
 * @returns the display state
 */
export function deriveConfirmation(
  appointment: Appointment,
  enabled: boolean,
): ConfirmationState {
  if (!enabled || !CONFIRMABLE_STATUSES.includes(appointment.status)) {
    return { kind: 'unavailable', confirmedAt: null };
  }
  if (appointment.confirmedAt !== null) {
    return { kind: 'confirmed', confirmedAt: appointment.confirmedAt };
  }
  return { kind: 'pending', confirmedAt: null };
}

/**
 * Apply a confirmation attempt to a derived state.
 *
 * Only an authenticated, pending confirmation is timestamped, and an existing
 * timestamp is returned unchanged so a replay cannot move it.
 *
 * @param state current derived confirmation state
 * @param authenticated whether the patient has re-authenticated
 * @param at server-supplied instant to record on a new confirmation
 * @returns the resulting state transition or a typed error
 */
export function confirm(
  state: ConfirmationState,
  authenticated: boolean,
  at: string,
): ConfirmResult {
  switch (state.kind) {
    case 'unavailable':
      return { kind: 'error', reason: 'NOT_ELIGIBLE' };
    case 'confirmed':
      return { kind: 'idempotent', confirmedAt: state.confirmedAt };
    case 'pending':
      return authenticated
        ? { kind: 'confirmed', confirmedAt: at }
        : { kind: 'error', reason: 'UNAUTHENTICATED' };
  }
}
