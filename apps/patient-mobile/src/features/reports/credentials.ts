import * as SecureStore from 'expo-secure-store';

import { createTokenStore } from '../../api/sessionCredentialStore';
import type { SecureStoreAdapter, TokenStoreFunctions } from '../../api/sessionCredentialStore';
import type { TokenStore } from './types';

export type { SecureStoreAdapter };

/**
 * Report-download credential store (PAT-02).
 *
 * This class is a thin adapter over the single canonical session store: the
 * key, the blank-credential rule and the fail-closed sign-out live in
 * {@link ../../api/sessionCredentialStore.createTokenStore}. Keeping only the `TokenStore`
 * seam here means a sign-out through the report path removes the same OS
 * credential and clinical memory as a sign-out through the transport path.
 */
export class SecureCredentialStore implements TokenStore {
  private readonly store: TokenStoreFunctions;

  /**
   * @param adapter native secure storage; defaults to the platform implementation
   */
  constructor(adapter: SecureStoreAdapter = SecureStore) {
    this.store = createTokenStore(adapter);
  }

  /** Read the protected session credential. */
  read(): Promise<string | null> {
    return this.store.loadToken();
  }

  /**
   * Store a non-blank credential.
   *
   * @throws Error with code `INVALID_CREDENTIAL` when the token is empty or blank
   */
  save(token: string): Promise<void> {
    return this.store.saveToken(token);
  }

  /** Remove the credential and all cached clinical data after logout or revocation. */
  clear(): Promise<void> {
    return this.store.clearToken();
  }
}
