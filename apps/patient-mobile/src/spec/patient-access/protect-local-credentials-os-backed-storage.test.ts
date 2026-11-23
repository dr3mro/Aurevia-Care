import { jest } from '@jest/globals';

import {
  cacheClinical,
  clearClinicalCache,
  clinicalCacheSize,
  readClinical,
} from '../../api/clinicalCache';
import { clearToken, loadToken, saveToken } from '../../api/tokenStore';
import { SecureCredentialStore } from '../../features/reports/credentials';
import type { SecureStoreAdapter } from '../../features/reports/credentials';
import { ReleasedReportClient } from '../../features/reports/reportClient';
import type { ReleasedReport, ReportTransport } from '../../features/reports/types';

/**
 * Replace the native adapter with a synthetic in-memory key/value store so the
 * proof observes exactly what reaches OS-backed storage. The store is shared
 * only within this test file; Jest isolates each test file's module registry.
 */
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

/** Stable OS-store key shared by both production credential stores. */
const TOKEN_KEY = 'aurevia.patient.session';
/** Synthetic session credential: never a real token or patient value. */
const TOKEN = 'synthetic-session-token';
/** Synthetic released report that lets a protected read reach a transport call. */
const report: ReleasedReport = {
  reportId: 'report-1',
  revision: 2,
  issuingOrganization: 'Synthetic Clinic',
  effectiveDate: '2026-06-10',
  language: 'en',
  correctionReason: null,
  supersedesRevision: null,
};

/** Record real boundary calls so assertions inspect outcomes, not just invocation. */
function recorder<A extends unknown[], R>(impl: (...args: A) => R) {
  const calls: A[] = [];
  const fn = (...args: A): R => {
    calls.push(args);
    return impl(...args);
  };
  return { fn, calls };
}

/** Synthetic injected secure store that records every credential boundary call. */
function secureAdapter(initial: string | null = null) {
  let stored = initial;
  const get = recorder(async (key: string): Promise<string | null> => {
    expect(key).toBe(TOKEN_KEY);
    return stored;
  });
  const set = recorder(async (key: string, value: string): Promise<void> => {
    expect(key).toBe(TOKEN_KEY);
    stored = value;
  });
  const del = recorder(async (key: string): Promise<void> => {
    expect(key).toBe(TOKEN_KEY);
    stored = null;
  });
  return {
    adapter: {
      getItemAsync: get.fn,
      setItemAsync: set.fn,
      deleteItemAsync: del.fn,
    } satisfies SecureStoreAdapter,
    getCalls: get.calls,
    setCalls: set.calls,
    deleteCalls: del.calls,
  };
}

/** Synthetic protected transport: records requests instead of calling a server. */
function transport() {
  const get = recorder(async (_path: string, _token: string): Promise<unknown> => [report]);
  const post = recorder(
    async (_path: string, _token: string, _body: unknown): Promise<unknown> => undefined,
  );
  return {
    api: { getJson: get.fn, postJson: post.fn } satisfies ReportTransport,
    getCalls: get.calls,
    postCalls: post.calls,
  };
}

/** Bind a protected client to the OS-backed credential seam under test. */
function clientWith(adapter: SecureStoreAdapter, api: ReportTransport): ReleasedReportClient {
  return new ReleasedReportClient(api, new SecureCredentialStore(adapter), 'patient-1');
}

beforeEach(() => {
  secureStore.__store.clear();
  secureStore.setItemAsync.mockClear();
  secureStore.getItemAsync.mockClear();
  secureStore.deleteItemAsync.mockClear();
  clearClinicalCache();
});

describe('Protect local credentials with OS-backed storage (PAT-02 clause 3)', () => {
  it('stores the session credential in the OS-backed store and reads it back', async () => {
    await saveToken(TOKEN);

    expect(secureStore.setItemAsync).toHaveBeenCalledWith(TOKEN_KEY, TOKEN, {
      requireAuthentication: false,
    });
    expect(secureStore.__store.get(TOKEN_KEY)).toBe(TOKEN);
    await expect(loadToken()).resolves.toBe(TOKEN);

    const fake = secureAdapter();
    const store = new SecureCredentialStore(fake.adapter);
    await store.save(TOKEN);
    await expect(store.read()).resolves.toBe(TOKEN);
    expect(fake.setCalls[0]).toEqual([TOKEN_KEY, TOKEN]);
    expect(fake.getCalls[0]).toEqual([TOKEN_KEY]);
  });

  it('refuses a blank credential without writing to the OS store', async () => {
    await expect(saveToken('   ')).rejects.toThrow('INVALID_CREDENTIAL');
    expect(secureStore.setItemAsync).not.toHaveBeenCalled();

    const fake = secureAdapter();
    const store = new SecureCredentialStore(fake.adapter);
    await expect(store.save('')).rejects.toThrow('INVALID_CREDENTIAL');
    await expect(store.save('   ')).rejects.toThrow('INVALID_CREDENTIAL');
    expect(fake.setCalls.length).toBe(0);
  });

  it('removes the credential from the OS store on sign-out', async () => {
    await saveToken(TOKEN);
    await expect(loadToken()).resolves.toBe(TOKEN);

    await clearToken();

    expect(secureStore.deleteItemAsync).toHaveBeenCalledWith(TOKEN_KEY);
    await expect(loadToken()).resolves.toBeNull();

    const fake = secureAdapter(TOKEN);
    const store = new SecureCredentialStore(fake.adapter);
    await store.clear();
    expect(fake.deleteCalls[0]).toEqual([TOKEN_KEY]);
    await expect(store.read()).resolves.toBeNull();
  });

  it('still clears clinical memory when the OS store delete fails', async () => {
    cacheClinical('messages', 'msg-1', 'synthetic-message');
    secureStore.deleteItemAsync.mockRejectedValueOnce(new Error('keychain unavailable'));

    await expect(clearToken()).rejects.toThrow('keychain unavailable');

    expect(secureStore.deleteItemAsync).toHaveBeenCalledWith(TOKEN_KEY);
    expect(clinicalCacheSize()).toBe(0);
    expect(readClinical('messages', 'msg-1')).toBeUndefined();
  });

  it('refuses a protected request when the OS-backed credential is missing', async () => {
    const fake = secureAdapter();
    const api = transport();
    const client = clientWith(fake.adapter, api.api);

    await expect(client.listReleasedReports()).rejects.toThrow('NOT_AUTHENTICATED');

    expect(fake.getCalls.length).toBe(1);
    expect(api.getCalls.length).toBe(0);
    expect(api.postCalls.length).toBe(0);
  });

  it('refuses a protected request when the OS-backed credential is blank', async () => {
    const fake = secureAdapter('   ');
    const api = transport();
    const client = clientWith(fake.adapter, api.api);

    await expect(client.listReleasedReports()).rejects.toThrow('NOT_AUTHENTICATED');

    expect(fake.getCalls.length).toBe(1);
    expect(api.getCalls.length).toBe(0);
    expect(api.postCalls.length).toBe(0);
  });

  it('re-reads the OS credential before each protected request', async () => {
    const fake = secureAdapter(TOKEN);
    const api = transport();
    const client = clientWith(fake.adapter, api.api);

    await expect(client.listReleasedReports()).resolves.toEqual([report]);
    await expect(client.listReleasedReports()).resolves.toEqual([report]);

    expect(fake.getCalls.length).toBe(2);
    expect(api.getCalls.length).toBe(2);
  });
});
