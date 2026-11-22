/**
 * Versioned confirmation command for one patient appointment (PAT-01, ARC-03).
 *
 * The command carries the exact version read with the appointment plus the
 * verified patient identifier; the server rejects a revision that changed
 * since the read. Only a typed outcome leaves this boundary: response text and
 * tokens never become error messages, and every failure keeps its safe
 * correlation id on the returned transport error.
 */
import { asTransportError } from '../../api/transport';
import type { Transport, TransportError } from '../../api/transport';

/** Inputs to one confirmation attempt. */
export interface ConfirmAppointmentCommand {
  /** Appointment to confirm, tenant-local. */
  readonly appointmentId: string;
  /** Verified linked patient the appointment belongs to. */
  readonly patientId: string;
  /** Version the patient screen actually read. */
  readonly expectedVersion: number;
}

/** Mutable slot holding the identifier of the command currently in flight. */
export interface PendingCommandSlot {
  current: string | null;
}

/**
 * Claim the slot for one command, rejecting a second claimant until released.
 *
 * React state updates are asynchronous, so a very fast second activation can
 * reach the handler before the control renders as disabled. The slot is checked
 * synchronously to drop that duplicate before any request is issued.
 *
 * @param slot the page's in-flight slot
 * @param id identifier of the command being claimed
 * @returns true when the slot was free and is now held by this command
 */
export function claimPendingCommand(slot: PendingCommandSlot, id: string): boolean {
  if (slot.current !== null) {
    return false;
  }
  slot.current = id;
  return true;
}

/** Free the slot once its command has settled. */
export function releasePendingCommand(slot: PendingCommandSlot): void {
  slot.current = null;
}

/** Outcome of one confirmation attempt; no branch discloses clinical data. */
export type ConfirmAppointmentResult =
  | { readonly kind: 'confirmed'; readonly confirmedAt: string; readonly version: number }
  | { readonly kind: 'stale' }
  | { readonly kind: 'unauthenticated' }
  | { readonly kind: 'failure'; readonly error: TransportError };

/** Validate the minimal server reply to a confirmation. */
function parseConfirmation(body: unknown): { confirmedAt: string; version: number } {
  if (typeof body !== 'object' || body === null || Array.isArray(body)) {
    throw new Error('MALFORMED_CONFIRMATION');
  }
  const { confirmedAt, version } = body as Record<string, unknown>;
  if (
    typeof confirmedAt !== 'string'
    || confirmedAt.trim().length === 0
    || typeof version !== 'number'
    || !Number.isInteger(version)
    || version < 0
  ) {
    throw new Error('MALFORMED_CONFIRMATION');
  }
  return { confirmedAt, version };
}

/**
 * Confirm one appointment against the version the patient read.
 *
 * A 401 ends the session, a 409/412 means the row changed and must be
 * reloaded, and every other failure is returned as a typed transport error so
 * the caller can offer a retry without changing the displayed record.
 *
 * @param transport authenticated patient transport
 * @param command appointment, patient and read version
 * @returns the typed outcome of the attempt
 */
export async function confirmAppointment(
  transport: Transport,
  command: ConfirmAppointmentCommand,
): Promise<ConfirmAppointmentResult> {
  try {
    const body = await transport.post<unknown>(
      `/api/v1/patient/appointments/${encodeURIComponent(command.appointmentId)}/confirm`,
      { patientId: command.patientId, expectedVersion: command.expectedVersion },
    );
    const { confirmedAt, version } = parseConfirmation(body);
    return { kind: 'confirmed', confirmedAt, version };
  } catch (error) {
    const failure = asTransportError(error, 'server');
    if (failure.kind === 'conflict') {
      return { kind: 'stale' };
    }
    if (failure.kind === 'unauthenticated') {
      return { kind: 'unauthenticated' };
    }
    return { kind: 'failure', error: failure };
  }
}
