import { jest } from '@jest/globals';

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

beforeEach(() => {
  secureStore.__store.clear();
  secureStore.setItemAsync.mockClear();
  secureStore.getItemAsync.mockClear();
  secureStore.deleteItemAsync.mockClear();
});

describe('tokenStore (PAT-02, ARC-03)', () => {
  it('saves the token under the session key without requiring authentication', async () => {
    await saveToken(TOKEN);

    expect(secureStore.setItemAsync).toHaveBeenCalledWith('session', TOKEN, {
      requireAuthentication: false,
    });
    expect(secureStore.__store.get('session')).toBe(TOKEN);
  });

  it('loads the token from the session key', async () => {
    await saveToken(TOKEN);

    await expect(loadToken()).resolves.toBe(TOKEN);
    expect(secureStore.getItemAsync).toHaveBeenCalledWith('session');
  });

  it('returns null when no token has been stored', async () => {
    await expect(loadToken()).resolves.toBeNull();
  });

  it('removes the token from the session key', async () => {
    await saveToken(TOKEN);
    await expect(loadToken()).resolves.toBe(TOKEN);

    await clearToken();

    expect(secureStore.deleteItemAsync).toHaveBeenCalledWith('session');
    await expect(loadToken()).resolves.toBeNull();
  });
});
