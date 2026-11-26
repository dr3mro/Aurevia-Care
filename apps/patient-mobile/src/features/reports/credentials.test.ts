import { jest } from '@jest/globals';

import {
  cacheClinical,
  clearClinicalCache,
  clinicalCacheSize,
  readClinical,
} from '../../api/clinicalCache';
import { SecureCredentialStore } from './credentials';
import type { SecureStoreAdapter } from './credentials';
import { ReleasedReportClient } from './reportClient';
import type { ReportTransport } from './types';

/** Synthetic native store so every credential boundary call is observable. */
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
const TOKEN_KEY = 'aurevia.patient.session';
const TOKEN = 'synthetic-session-token';

/** Record boundary arguments so assertions inspect outcomes, not just invocation. */
function recorder<A extends unknown[], R>(impl: (...args: A) => R) {
  const calls: A[] = [];
  const fn = (...args: A): R => {
    calls.push(args);
    return impl(...args);
  };
  return { fn, calls };
}

/** Synthetic adapter that stores the invented credential and records every call. */
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

/** Native deletion that fails, modelling a temporarily unavailable keychain. */
function rejectingAdapter(): SecureStoreAdapter {
  return {
    getItemAsync: async () => null,
    setItemAsync: async () => undefined,
    deleteItemAsync: async () => {
      throw new Error('keychain unavailable');
    },
  };
}

/** Synthetic protected transport that records whether a request escaped. */
function transport() {
  const get = recorder(async (_path: string, _token: string): Promise<unknown> => []);
  const post = recorder(
    async (_path: string, _token: string, _body: unknown): Promise<unknown> => undefined,
  );
  return {
    api: { getJson: get.fn, postJson: post.fn } satisfies ReportTransport,
    getCalls: get.calls,
    postCalls: post.calls,
  };
}

beforeEach(() => {
  secureStore.__store.clear();
  secureStore.setItemAsync.mockClear();
  secureStore.getItemAsync.mockClear();
  secureStore.deleteItemAsync.mockClear();
  clearClinicalCache();
});

describe('SecureCredentialStore sign-out (PAT-02)', () => {
  it('discards cached clinical data when the credential is removed', async () => {
    const fake = secureAdapter(TOKEN);
    cacheClinical('reports', 'report-1', { text: 'synthetic-report' });
    const store = new SecureCredentialStore(fake.adapter);

    await expect(store.clear()).resolves.toBeUndefined();
    await expect(store.read()).resolves.toBeNull();
    expect(fake.deleteCalls).toEqual([[TOKEN_KEY]]);
    expect(clinicalCacheSize()).toBe(0);
    expect(readClinical('reports', 'report-1')).toBeUndefined();
  });

  it('discards cached clinical data even when the native delete rejects', async () => {
    cacheClinical('messages', 'msg-1', 'synthetic-message');
    const store = new SecureCredentialStore(rejectingAdapter());

    await expect(store.clear()).rejects.toThrow('keychain unavailable');

    expect(clinicalCacheSize()).toBe(0);
    expect(readClinical('messages', 'msg-1')).toBeUndefined();
  });

  it('refuses a blank credential through the shared guard without writing', async () => {
    const fake = secureAdapter();
    const store = new SecureCredentialStore(fake.adapter);

    await expect(store.save('  ')).rejects.toThrow('INVALID_CREDENTIAL');

    expect(fake.setCalls.length).toBe(0);
    expect(secureStore.setItemAsync).not.toHaveBeenCalled();
  });

  it('refuses a blank stored token at the report boundary before any request', async () => {
    const store = new SecureCredentialStore(secureAdapter('   ').adapter);
    const api = transport();
    const client = new ReleasedReportClient(api.api, store, 'patient-1');

    await expect(client.listReleasedReports()).rejects.toThrow('NOT_AUTHENTICATED');

    expect(api.getCalls.length).toBe(0);
    expect(api.postCalls.length).toBe(0);
  });
});
