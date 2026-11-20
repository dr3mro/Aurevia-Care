import type { AuthState, GenericNotice } from './types';

/** The single allowed push template; no patient or appointment field is accepted. */
export const GENERIC_NOTICE: GenericNotice = Object.freeze({
  title: 'Appointment update',
  body: 'You have an update about your appointment. Open the app to view details.',
});

/**
 * Return the fixed generic notification text.
 *
 * @returns the generic notice; identical for every status change
 */
export function buildGenericNotice(): GenericNotice {
  return GENERIC_NOTICE;
}

/** Result of an authentication-gated detail request. */
export type DetailResult<T> =
  | { readonly kind: 'auth_required' }
  | { readonly kind: 'detail'; readonly value: T };

/**
 * Retrieve appointment detail only after authentication.
 *
 * The reference is tenant-scoped and the fetcher applies the same server-side
 * authorization as a normal read. Unauthenticated calls never reach it.
 *
 * @param auth current authentication state
 * @param ref opaque tenant-scoped detail reference
 * @param fetcher authorized detail client
 * @returns an auth-required marker or the fetched detail
 */
export function detailAfterAuth<T>(
  auth: AuthState,
  ref: string,
  fetcher: (ref: string) => Promise<T>,
): Promise<DetailResult<T>> {
  if (!auth.authenticated) {
    return Promise.resolve({ kind: 'auth_required' });
  }
  return fetcher(ref).then((value) => ({ kind: 'detail', value }));
}
