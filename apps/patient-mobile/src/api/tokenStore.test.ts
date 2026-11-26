import { jest } from '@jest/globals';

import {
  cacheClinical,
  clearClinicalCache,
  clinicalCacheSize,
  readClinical,
} from './clinicalCache';
import { SecureCredentialStore } from '../features/reports/credentials';
import { clearToken, loadToken, saveToken } from './tokenStore';

/** The native adapter is replaced with a synthetic, in-memory SecureStore. */
jest.mock('expo-secure-store', () => {
  const store = new Map<string, string>();
  return {
    __store: store,
    setItemAsync: jest.fn(async (key: string, value: string) => {
      store.set(key, value);
    }),
    getItemAsync: jest.fn(async (key: string) => store.get(key) ?? null),
    deleteItemAsync: jest.fn(async (key: string) => {
      store.delete(key);
    }),
  };
});

interface SecureStoreMock {
  __store: Map<string, string>;
  setItemAsync: jest.Mock<(key: string, value: string, options?: unknown) => Promise<void>>;
  getItemAsync: jest.Mock<(key: string) => Promise<string | null>>;
  deleteItemAsync: jest.Mock<(key: string) => Promise<void>>;
}

const secureStore = jest.requireMock('expo-secure-store') as unknown as SecureStoreMock;
const TOKEN = 'synthetic-session-token';
/** Matches the reports credential store key, so sign-out removes the real token too. */
const TOKEN_KEY = 'aurevia.patient.session';

beforeEach(() => {
  secureStore.__store.clear();
  secureStore.setItemAsync.mockClear();
  secureStore.getItemAsync.mockClear();
  secureStore.deleteItemAsync.mockClear();
  clearClinicalCache();
});

describe('tokenStore (PAT-02, ARC-03)', () => {
  it('saves the token under the session key without requiring authentication', async () => {
    await saveToken(TOKEN);

    expect(secureStore.setItemAsync).toHaveBeenCalledWith(TOKEN_KEY, TOKEN, {
      requireAuthentication: false,
    });
    expect(secureStore.__store.get(TOKEN_KEY)).toBe(TOKEN);
  });

  it('refuses to store a blank credential', async () => {
    await expect(saveToken('   ')).rejects.toThrow('INVALID_CREDENTIAL');
    expect(secureStore.setItemAsync).not.toHaveBeenCalled();
    await expect(loadToken()).resolves.toBeNull();
  });

  it('loads the token from the session key', async () => {
    await saveToken(TOKEN);

    await expect(loadToken()).resolves.toBe(TOKEN);
    expect(secureStore.getItemAsync).toHaveBeenCalledWith(TOKEN_KEY);
  });

  it('returns null when no token has been stored', async () => {
    await expect(loadToken()).resolves.toBeNull();
  });

  it('removes the token from the session key', async () => {
    await saveToken(TOKEN);
    await expect(loadToken()).resolves.toBe(TOKEN);

    await clearToken();

    expect(secureStore.deleteItemAsync).toHaveBeenCalledWith(TOKEN_KEY);
    await expect(loadToken()).resolves.toBeNull();
  });

  it('clears all in-memory clinical data at sign-out', async () => {
    await saveToken(TOKEN);
    cacheClinical('reports', 'report-1', { text: 'synthetic-report' });
    cacheClinical('messages', 'msg-1', 'synthetic-message');
    expect(clinicalCacheSize()).toBe(2);

    await clearToken();

    expect(clinicalCacheSize()).toBe(0);
    expect(readClinical('reports', 'report-1')).toBeUndefined();
    expect(readClinical('messages', 'msg-1')).toBeUndefined();
  });

  it('still clears clinical memory when secure storage removal fails', async () => {
    cacheClinical('messages', 'msg-1', 'synthetic-message');
    secureStore.deleteItemAsync.mockRejectedValueOnce(new Error('keychain unavailable'));

    await expect(clearToken()).rejects.toThrow('keychain unavailable');

    expect(clinicalCacheSize()).toBe(0);
    expect(readClinical('messages', 'msg-1')).toBeUndefined();
  });

  it('clears the shared credential and clinical cache through the report store even when native delete rejects', async () => {
    await saveToken(TOKEN);
    cacheClinical('reports', 'report-1', { text: 'synthetic-report' });
    secureStore.deleteItemAsync.mockImplementationOnce(async (key: string) => {
      secureStore.__store.delete(key);
      throw new Error('keychain unavailable');
    });

    const store = new SecureCredentialStore();
    await expect(store.clear()).rejects.toThrow('keychain unavailable');

    await expect(loadToken()).resolves.toBeNull();
    expect(clinicalCacheSize()).toBe(0);
    expect(readClinical('reports', 'report-1')).toBeUndefined();
  });

  it('writes only the canonical session key from both credential stores', async () => {
    await saveToken(TOKEN);
    await new SecureCredentialStore().save('synthetic-report-token');

    expect([...secureStore.__store.keys()]).toEqual([TOKEN_KEY]);
    expect(secureStore.__store.size).toBe(1);
  });
});
