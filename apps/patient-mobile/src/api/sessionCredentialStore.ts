import * as SecureStore from 'expo-secure-store';

import { clearClinicalCache } from './clinicalCache';

/**
 * Canonical OS-backed session credential store (PAT-02, ARC-03).
 *
 * The token lives only in `expo-secure-store`. It is read immediately before a
 * protected request so a revocation takes effect on the next action, and it is
 * never placed in AsyncStorage, application state or a clinical cache. Both
 * entry points bind to this one implementation, so sign-out cannot clear only
 * half of the session.
 *
 * This module is deliberately separate from {@link ./tokenStore}: headless
 * tests replace that API module wholesale, while the report store still needs
 * the real implementation at construction time.
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
 * The narrow OS-backed surface the session needs. `setItemAsync` takes exactly
 * two arguments: injected adapters are called with that shape, while the
 * platform adapter below supplies native options inside its own method.
 */
export interface SecureStoreAdapter {
  /** Read a protected value. */
  getItemAsync(key: string): Promise<string | null>;
  /** Write a protected value. */
  setItemAsync(key: string, value: string): Promise<void>;
  /** Remove a protected value. */
  deleteItemAsync(key: string): Promise<void>;
}

/** The canonical session operations shared by every credential entry point. */
export interface TokenStoreFunctions {
  /**
   * Persist a non-blank credential.
   *
   * @throws Error with code `INVALID_CREDENTIAL` when the token is empty or blank
   */
  saveToken(token: string): Promise<void>;
  /** Read the current credential, or null when none is stored. */
  loadToken(): Promise<string | null>;
  /** Remove the credential and discard all transient clinical memory. */
  clearToken(): Promise<void>;
}

/**
 * Adapt the native module to the two-argument credential seam.
 *
 * Platform options stay here so an injected adapter is never given a third
 * argument and the native call still carries `requireAuthentication: false`.
 */
export const platformSecureStore: SecureStoreAdapter = {
  getItemAsync(key: string): Promise<string | null> {
    return SecureStore.getItemAsync(key);
  },
  setItemAsync(key: string, value: string): Promise<void> {
    return SecureStore.setItemAsync(key, value, STORE_OPTIONS);
  },
  deleteItemAsync(key: string): Promise<void> {
    return SecureStore.deleteItemAsync(key);
  },
};

/**
 * Build the one canonical credential store over an OS-backed adapter.
 *
 * @param adapter native secure storage, or a synthetic adapter in tests
 * @returns the save, load and clear operations used by both entry points
 */
export function createTokenStore(adapter: SecureStoreAdapter): TokenStoreFunctions {
  return {
    async saveToken(token: string): Promise<void> {
      if (token.trim().length === 0) {
        throw new Error('INVALID_CREDENTIAL');
      }
      await adapter.setItemAsync(TOKEN_KEY, token);
    },
    loadToken(): Promise<string | null> {
      return adapter.getItemAsync(TOKEN_KEY);
    },
    async clearToken(): Promise<void> {
      try {
        await adapter.deleteItemAsync(TOKEN_KEY);
      } finally {
        // The cache is in-memory only; clear it even when keychain deletion fails
        // so no clinical payload outlives a failed sign-out or revocation.
        clearClinicalCache();
      }
    },
  };
}
