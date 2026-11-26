import { createTokenStore, platformSecureStore } from './sessionCredentialStore';

export type { SecureStoreAdapter, TokenStoreFunctions } from './sessionCredentialStore';

/**
 * Public session-credential API for the patient app (PAT-02, ARC-03).
 *
 * These bound functions are used by the authenticated transport, idle timeout
 * and sign-out. They share the single canonical implementation in
 * {@link ./sessionCredentialStore}, so revocation through any entry point
 * removes the same OS-backed token and every transient clinical payload.
 */

const sessionStore = createTokenStore(platformSecureStore);

/** Persist a non-blank session credential in OS-backed storage. */
export const saveToken = sessionStore.saveToken;

/** Read the current session credential, or null when none is stored. */
export const loadToken = sessionStore.loadToken;

/**
 * End the local session: remove the OS credential and discard all transient
 * clinical memory. The native error still propagates after the cache is clear.
 */
export const clearToken = sessionStore.clearToken;
