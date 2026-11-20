import * as SecureStore from 'expo-secure-store';

import type { TokenStore } from './types';

/** Stable key for the single protected session credential. */
const TOKEN_KEY = 'aurevia.patient.session';

/**
 * The OS-backed storage surface used by {@link SecureCredentialStore}.
 *
 * Injecting this seam keeps the credential boundary testable with a synthetic adapter and makes the
 * reliance on native secure storage explicit at the call site.
 */
export interface SecureStoreAdapter {
  /** Read a protected value. */
  getItemAsync(key: string): Promise<string | null>;
  /** Write a protected value. */
  setItemAsync(key: string, value: string): Promise<void>;
  /** Remove a protected value. */
  deleteItemAsync(key: string): Promise<void>;
}

/**
 * OS-backed credential storage (PAT-02).
 *
 * The session token lives in `expo-secure-store`, never in app state, AsyncStorage or a clinical
 * cache. Callers read it immediately before a protected request so a revocation takes effect on the
 * next action.
 */
export class SecureCredentialStore implements TokenStore {
  /**
   * @param adapter native secure storage; defaults to the platform implementation
   */
  constructor(private readonly adapter: SecureStoreAdapter = SecureStore) {}

  /** Read the protected session credential. */
  async read(): Promise<string | null> {
    return this.adapter.getItemAsync(TOKEN_KEY);
  }

  /**
   * Store a non-blank credential.
   *
   * @throws Error with code `INVALID_CREDENTIAL` when the token is empty or blank
   */
  async save(token: string): Promise<void> {
    if (token.trim().length === 0) {
      throw new Error('INVALID_CREDENTIAL');
    }
    await this.adapter.setItemAsync(TOKEN_KEY, token);
  }

  /** Remove the protected credential, for example after a logout or device revocation. */
  async clear(): Promise<void> {
    await this.adapter.deleteItemAsync(TOKEN_KEY);
  }
}
