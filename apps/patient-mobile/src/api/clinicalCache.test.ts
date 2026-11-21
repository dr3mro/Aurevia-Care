/**
 * Acceptance tests for the transient clinical cache (PAT-02).
 *
 * Clinical payloads (reports, messages, attachment bytes) must live only in
 * process memory and never reach a persistent store. Synthetic data only.
 */
import { jest } from '@jest/globals';

import {
  cacheClinical,
  clearClinicalCache,
  clinicalCacheSize,
  readClinical,
} from './clinicalCache';

/** Spy on the only persistent API the app uses so a clinical write is detectable. */
jest.mock('expo-secure-store', () => {
  const setItemAsync = jest.fn(async (_key: string, _value: string) => undefined);
  return {
    setItemAsync,
    getItemAsync: jest.fn(async () => null),
    deleteItemAsync: jest.fn(async () => undefined),
  };
});

const secureStore = jest.requireMock('expo-secure-store') as {
  setItemAsync: jest.Mock<(key: string, value: string) => Promise<void>>;
};

beforeEach(() => {
  clearClinicalCache();
  secureStore.setItemAsync.mockClear();
});

describe('clinicalCache (PAT-02)', () => {
  it('keeps reports, messages and attachment bytes in memory only', () => {
    const bytes = new Uint8Array([1, 2, 3]);
    cacheClinical('reports', 'report-1', { text: 'synthetic-report' });
    cacheClinical('messages', 'msg-1', { text: 'synthetic-message' });
    cacheClinical('attachments', 'att-1', bytes);

    expect(readClinical('reports', 'report-1')).toEqual({ text: 'synthetic-report' });
    expect(readClinical('messages', 'msg-1')).toEqual({ text: 'synthetic-message' });
    expect(readClinical('attachments', 'att-1')).toBe(bytes);
    expect(clinicalCacheSize()).toBe(3);
  });

  it('isolates values by namespace and key', () => {
    cacheClinical('reports', 'shared-key', 'report-value');
    cacheClinical('messages', 'shared-key', 'message-value');

    expect(readClinical('reports', 'shared-key')).toBe('report-value');
    expect(readClinical('messages', 'shared-key')).toBe('message-value');
  });

  it('returns undefined for a missing entry', () => {
    expect(readClinical('reports', 'missing')).toBeUndefined();
  });

  it('never writes clinical data to persistent storage', () => {
    cacheClinical('reports', 'report-1', { text: 'synthetic-report' });
    cacheClinical('attachments', 'att-1', new Uint8Array([9]));

    expect(secureStore.setItemAsync).not.toHaveBeenCalled();
  });

  it('clears all in-memory clinical data so nothing survives sign-out', () => {
    cacheClinical('reports', 'report-1', { text: 'synthetic-report' });
    cacheClinical('messages', 'msg-1', 'synthetic-message');
    expect(clinicalCacheSize()).toBe(2);

    clearClinicalCache();

    expect(clinicalCacheSize()).toBe(0);
    expect(readClinical('reports', 'report-1')).toBeUndefined();
    expect(readClinical('messages', 'msg-1')).toBeUndefined();
  });
});
