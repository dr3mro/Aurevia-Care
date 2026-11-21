import * as SecureStore from 'expo-secure-store';

import { clearClinicalCache } from './clinicalCache';

/**
 * OS-backed session credential storage (PAT-02, ARC-03).
 *
 * The token lives only in `expo-secure-store`. It is read immediately before a
 * protected request so a revocation takes effect on the next action, and it is
 * never placed in AsyncStorage, application state or a clinical cache.
 */

/**
 * Key for the single patient session credential. It deliberately matches the
 * key used by the reports credential store so sign-out and idle timeout remove
 * the real download credential as well, leaving no token in the OS store.
 */
const TOKEN_KEY = 'aurevia.patient.session';

/** Biometric gating is deferred until the identity provider flow is approved. */
const STORE_OPTIONS = { requireAuthentication: false };

/**
 * Persist a non-blank session credential in OS-backed storage.
 *
 * @throws Error with code `INVALID_CREDENTIAL` when the token is empty or blank
 */
export async function saveToken(token: string): Promise<void> {
  if (token.trim().length === 0) {
    throw new Error('INVALID_CREDENTIAL');
  }
  await SecureStore.setItemAsync(TOKEN_KEY, token, STORE_OPTIONS);
}

/** Read the current session credential, or null when none is stored. */
export async function loadToken(): Promise<string | null> {
  return SecureStore.getItemAsync(TOKEN_KEY);
}

/**
 * End the local session: remove the OS credential and discard all transient
 * clinical memory. The cache is cleared in a `finally` block so it cannot
 * survive sign-out even when the native keychain is temporarily unavailable.
 */
export async function clearToken(): Promise<void> {
  try {
    await SecureStore.deleteItemAsync(TOKEN_KEY);
  } finally {
    clearClinicalCache();
  }
}
