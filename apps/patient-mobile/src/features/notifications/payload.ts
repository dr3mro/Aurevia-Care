import { buildGenericNotice, detailAfterAuth } from '../appointments/notifications';
import type { DetailResult } from '../appointments/notifications';
import type { AuthState, GenericNotice } from '../appointments/types';

/** A generic push payload: fixed text plus an opaque reference resolved after sign-in. */
export interface PushPayload {
  readonly notice: GenericNotice;
  readonly detailRef: string;
}

/**
 * Build the notification payload from the appointments domain's single generic
 * template. No name, result, status or appointment field can reach the text.
 *
 * @param detailRef opaque tenant-scoped reference, not clinical content
 * @returns the generic payload
 */
export function buildPushPayload(detailRef: string): PushPayload {
  return { notice: buildGenericNotice(), detailRef };
}

/**
 * Prepare a notification without exposing clinical content before sign-in.
 *
 * The notice is always the fixed generic text. The detailed value is fetched
 * through the same authentication gate as a normal app read (PAT-01, ARC-07).
 *
 * @param auth current authentication state
 * @param detailRef opaque tenant-scoped reference for the detail fetch
 * @param fetcher authorized detail client
 * @returns the generic payload and the authentication-gated detail result
 */
export function notificationContent<T>(
  auth: AuthState,
  detailRef: string,
  fetcher: (ref: string) => Promise<T>,
): Promise<{ payload: PushPayload; detail: DetailResult<T> }> {
  return detailAfterAuth(auth, detailRef, fetcher).then((detail) => ({
    payload: buildPushPayload(detailRef),
    detail,
  }));
}
