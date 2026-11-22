/**
 * Shared types for the S-G3-01 patient appointment slice (PAT-01, ARC-01).
 *
 * All identifiers are tenant-local strings and are always paired with the
 * active organization before use. These declarations carry no runtime code.
 */

/** Tenant-owned organization identifier. */
export type OrganizationId = string;
/** Identifier that is unique only inside one organization. */
export type LocalId = string;
/** Opaque key formed from an organization and a local identifier. */
export type TenantKey = string;

/** Authoritative, read-only appointment status from the scheduling domain. */
export type AppointmentStatus =
  | 'booked'
  | 'checked_in'
  | 'in_progress'
  | 'completed'
  | 'cancelled'
  | 'no_show';

/**
 * Statuses for which a patient confirmation is still meaningful (PAT-01).
 * Kept here as the single runtime definition so client and domain agree.
 */
export const CONFIRMABLE_STATUSES: readonly AppointmentStatus[] = ['booked'];

/** The appointment fields a verified patient may see. */
export interface Appointment {
  readonly id: LocalId;
  /**
   * Optimistic-lock version read from the server projection. Confirmation must
   * echo this exact value as `expectedVersion`; a later revision is rejected,
   * so a stale screen can never overwrite a newer state (PAT-01).
   */
  readonly version: number;
  readonly patientId: LocalId;
  readonly facilityCode: LocalId;
  readonly facilityTimeZone: string;
  readonly startsAt: string;
  readonly status: AppointmentStatus;
  readonly confirmationEnabled: boolean;
  readonly confirmedAt: string | null;
}

/** Confirmation affordance derived from site enablement and appointment state. */
export type ConfirmationState =
  | { readonly kind: 'unavailable'; readonly confirmedAt: null }
  | { readonly kind: 'pending'; readonly confirmedAt: null }
  | { readonly kind: 'confirmed'; readonly confirmedAt: string };

/** Result of a patient confirmation attempt. */
export type ConfirmResult =
  | { readonly kind: 'confirmed'; readonly confirmedAt: string }
  | { readonly kind: 'idempotent'; readonly confirmedAt: string }
  | { readonly kind: 'error'; readonly reason: 'NOT_ELIGIBLE' | 'UNAUTHENTICATED' };

/** Delivery state of one registered device. */
export type DeviceState = 'active' | 'invalid' | 'revoked';

/** A device bound to one verified patient in one organization. */
export interface DeviceToken {
  readonly id: LocalId;
  readonly patientId: LocalId;
  readonly state: DeviceState;
  /** Provider token: stored for delivery only, never placed in a log or notice. */
  readonly token: string;
}

/** Reason code written to the invalid-token log; never the provider token. */
export type InvalidTokenReason = 'provider_rejected' | 'invalid_token';

/** Non-sensitive log event for a rejected device token. */
export interface InvalidTokenEvent {
  readonly deviceId: LocalId;
  readonly patientId: LocalId;
  readonly reason: InvalidTokenReason;
  readonly at: string;
}

/** The only notification payload this slice may emit. */
export interface GenericNotice {
  readonly title: string;
  readonly body: string;
}

/** Authentication state checked before any detail retrieval. */
export interface AuthState {
  readonly authenticated: boolean;
}

/** Injectable clock so a confirmation timestamp is deterministic in tests. */
export interface Clock {
  now(): string;
}

/** Injectable time-zone formatter for the facility's IANA zone. */
export interface ZoneFormatter {
  format(instant: string, timeZone: string): string;
}
