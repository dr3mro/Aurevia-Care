/**
 * Acceptance tests for the idle-timeout session guard (PAT-02, PAT-03).
 *
 * After a period of inactivity the patient is signed out: the OS credential is
 * removed and all transient clinical memory is cleared. Synthetic data only.
 */
import { jest } from '@jest/globals';

import { cacheClinical, clinicalCacheSize, readClinical } from './clinicalCache';

import { createIdleSessionGuard, createSignOutOnIdle } from './idleSession';
import { loadToken, saveToken } from './tokenStore';

jest.mock('expo-secure-store', () => {
  const store = new Map<string, string>();
  return {
    setItemAsync: jest.fn(async (key: string, value: string) => {
      store.set(key, value);
    }),
    getItemAsync: jest.fn(async (key: string) => store.get(key) ?? null),
    deleteItemAsync: jest.fn(async (key: string) => {
      store.delete(key);
    }),
  };
});

const secureStore = jest.requireMock('expo-secure-store') as {
  deleteItemAsync: jest.Mock<(key: string) => Promise<void>>;
};

const IDLE_MS = 60_000;
const TOKEN = 'synthetic-session-token';

beforeEach(() => {
  jest.useFakeTimers();
});

afterEach(() => {
  jest.clearAllTimers();
  jest.useRealTimers();
});

describe('createIdleSessionGuard (PAT-02)', () => {
  it('calls onIdle only after the full idle interval elapses', async () => {
    const onIdle = jest.fn<() => void>();
    createIdleSessionGuard({ idleMs: IDLE_MS, onIdle });

    expect(onIdle).not.toHaveBeenCalled();
    await jest.advanceTimersByTimeAsync(IDLE_MS - 1);
    expect(onIdle).not.toHaveBeenCalled();
    await jest.advanceTimersByTimeAsync(1);
    expect(onIdle).toHaveBeenCalledTimes(1);
  });

  it('resets the deadline when touch is called after activity', async () => {
    const onIdle = jest.fn<() => void>();
    const guard = createIdleSessionGuard({ idleMs: IDLE_MS, onIdle });

    await jest.advanceTimersByTimeAsync(IDLE_MS - 5_000);
    guard.touch();
    await jest.advanceTimersByTimeAsync(5_000);
    expect(onIdle).not.toHaveBeenCalled();
    await jest.advanceTimersByTimeAsync(IDLE_MS - 5_000);
    expect(onIdle).toHaveBeenCalledTimes(1);
  });

  it('does not fire onIdle again after the deadline has elapsed', async () => {
    const onIdle = jest.fn<() => void>();
    createIdleSessionGuard({ idleMs: IDLE_MS, onIdle });

    await jest.advanceTimersByTimeAsync(IDLE_MS);
    await jest.advanceTimersByTimeAsync(IDLE_MS);

    expect(onIdle).toHaveBeenCalledTimes(1);
  });

  it('cancels the deadline on dispose', async () => {
    const onIdle = jest.fn<() => void>();
    const guard = createIdleSessionGuard({ idleMs: IDLE_MS, onIdle });

    guard.dispose();
    await jest.advanceTimersByTimeAsync(IDLE_MS * 2);

    expect(onIdle).not.toHaveBeenCalled();
  });

  it('allows dispose after the deadline has already fired', async () => {
    const onIdle = jest.fn<() => void>();
    const guard = createIdleSessionGuard({ idleMs: IDLE_MS, onIdle });

    await jest.advanceTimersByTimeAsync(IDLE_MS);
    expect(() => guard.dispose()).not.toThrow();
    expect(onIdle).toHaveBeenCalledTimes(1);
  });
});

describe('createSignOutOnIdle (PAT-02)', () => {
  beforeEach(async () => {
    await saveToken(TOKEN);
    cacheClinical('reports', 'report-1', { text: 'synthetic-report' });
  });

  it('signs out and clears memory when the idle deadline expires', async () => {
    const guard = createSignOutOnIdle(IDLE_MS);
    expect(await loadToken()).toBe(TOKEN);
    expect(clinicalCacheSize()).toBe(1);

    await jest.advanceTimersByTimeAsync(IDLE_MS);

    await expect(loadToken()).resolves.toBeNull();
    expect(clinicalCacheSize()).toBe(0);
    expect(readClinical('reports', 'report-1')).toBeUndefined();
    guard.dispose();
  });

  it('keeps the session when activity resets the deadline', async () => {
    const guard = createSignOutOnIdle(IDLE_MS);

    await jest.advanceTimersByTimeAsync(IDLE_MS - 1_000);
    guard.touch();
    await jest.advanceTimersByTimeAsync(1_000);

    await expect(loadToken()).resolves.toBe(TOKEN);
    expect(clinicalCacheSize()).toBe(1);
    guard.dispose();
  });

  it('clears memory without an unhandled rejection when the keychain delete fails', async () => {
    const unhandled = jest.fn<() => void>();
    const listener = (): void => {
      unhandled();
    };
    process.on('unhandledRejection', listener);
    try {
      secureStore.deleteItemAsync.mockRejectedValueOnce(new Error('keychain unavailable'));
      const guard = createSignOutOnIdle(IDLE_MS);

      await jest.advanceTimersByTimeAsync(IDLE_MS);

      expect(clinicalCacheSize()).toBe(0);
      expect(unhandled).not.toHaveBeenCalled();
      guard.dispose();
    } finally {
      process.off('unhandledRejection', listener);
    }
  });
});
