import { CONFIRMABLE_STATUSES } from './types';
import type { Appointment, ConfirmationState, ConfirmResult } from './types';

/**
 * Derive the confirmation affordance from server-owned data.
 *
 * Enablement and status come from the server projection; a client cannot
 * enable confirmation for a disabled site or a finished visit.
 *
 * Both the site flag and the per-appointment server flag must permit
 * confirmation. The stricter value wins, so a caller cannot show a confirm
 * affordance for a visit the server marked non-confirmable.
 *
 * @param appointment server appointment projection
 * @param enabled site confirmation enablement evaluated by the server
 * @returns the display state
 */
export function deriveConfirmation(
  appointment: Appointment,
  enabled: boolean,
): ConfirmationState {
  if (
    !enabled ||
    !appointment.confirmationEnabled ||
    !CONFIRMABLE_STATUSES.includes(appointment.status)
  ) {
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
 * Authentication is required on every branch, so an unauthenticated caller
 * learns neither an eligibility decision nor a recorded timestamp. Only a
 * pending confirmation is timestamped, and an existing timestamp is returned
 * unchanged so an authenticated replay cannot move it.
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
  // No branch, including an idempotent replay, discloses a server-recorded
  // timestamp or an eligibility decision without authentication (PAT-01).
  if (!authenticated) {
    return { kind: 'error', reason: 'UNAUTHENTICATED' };
  }
  switch (state.kind) {
    case 'unavailable':
      return { kind: 'error', reason: 'NOT_ELIGIBLE' };
    case 'confirmed':
      return { kind: 'idempotent', confirmedAt: state.confirmedAt };
    case 'pending':
      return { kind: 'confirmed', confirmedAt: at };
  }
}
