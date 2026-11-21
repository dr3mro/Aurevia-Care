/**
 * Shared types for the patient push-notification feature (PAT-01, MSG-05).
 *
 * This module owns no clinical data. It reuses the tenant-scoped device and
 * generic-notice types from the appointments domain so notification behaviour
 * stays consistent with the slice that owns push registration state.
 */

import type {
  DeviceToken,
  GenericNotice,
  InvalidTokenEvent,
  InvalidTokenReason,
  LocalId,
  OrganizationId,
} from '../appointments/types';

export type {
  DeviceToken,
  GenericNotice,
  InvalidTokenEvent,
  InvalidTokenReason,
  LocalId,
  OrganizationId,
};

/** A verified session transition that can change push registration. */
export type SessionEvent =
  | {
      readonly kind: 'signed_in';
      readonly organizationId: OrganizationId;
      readonly device: DeviceToken;
    }
  | {
      readonly kind: 'signed_out';
      readonly organizationId: OrganizationId;
      readonly device: DeviceToken;
    };

/** The registration side effect the app must perform for a session transition. */
export type RegistrationCommand =
  | {
      readonly kind: 'register';
      readonly organizationId: OrganizationId;
      readonly device: DeviceToken;
    }
  | {
      readonly kind: 'unregister';
      readonly organizationId: OrganizationId;
      readonly device: DeviceToken;
    };

/** Provider disposition for one delivery attempt, without the provider token. */
export type DeliveryDisposition =
  | { readonly kind: 'accepted' }
  | { readonly kind: 'invalid'; readonly reason: InvalidTokenReason };

/** Injected push boundary; expected failures are dispositions, never throws. */
export interface PushGateway {
  send(token: string, notice: GenericNotice): Promise<DeliveryDisposition>;
}

/** Non-sensitive sink for invalid-token log events. */
export interface NoticeLogger {
  invalidToken(event: InvalidTokenEvent): void;
}

/** An item kept in the authenticated inbox until the patient opens it. */
export interface InboxItem {
  readonly id: LocalId;
  /** Opaque tenant-scoped reference resolved only after authentication. */
  readonly detailRef: string;
}

/** One delivery attempt and its outcome; carries no clinical payload. */
export interface DeliveryOutcome {
  readonly deviceId: LocalId;
  readonly disposition: DeliveryDisposition;
}

/** Result of dispatching one generic notice to a patient's devices. */
export interface DispatchResult {
  readonly devices: Map<string, DeviceToken>;
  /** The inbox is unchanged by delivery, successful or failed (ARC-07). */
  readonly inbox: readonly InboxItem[];
  readonly outcomes: readonly DeliveryOutcome[];
}
