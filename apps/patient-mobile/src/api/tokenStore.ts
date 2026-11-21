import * as SecureStore from 'expo-secure-store';

import { clearClinicalCache } from './clinicalCache';

/**
 * OS-backed session credential storage (PAT-01, ARC-03).
 *
 * The token lives only in `expo-secure-store`. It is read immediately before a
 * protected request so a revocation takes effect on the next action, and it is
 * never placed in AsyncStorage, application state or a clinical cache.
 */

/** Stable key for the single patient session credential. */
const TOKEN_KEY = 'session';

/** Biometric gating is deferred until the identity provider flow is approved. */
const STORE_OPTIONS = { requireAuthentication: false };

/** Persist the session credential in OS-backed storage. */
export async function saveToken(token: string): Promise<void> {
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
