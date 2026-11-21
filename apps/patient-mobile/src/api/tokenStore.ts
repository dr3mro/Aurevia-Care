import * as SecureStore from 'expo-secure-store';

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

/** Remove the stored credential, for example after logout or revocation. */
export async function clearToken(): Promise<void> {
  await SecureStore.deleteItemAsync(TOKEN_KEY);
}
