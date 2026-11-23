/**
 * Clause-only acceptance proof for PAT-02: "Avoid persistent clinical caches."
 *
 * Released reports, message content and attachment bytes may be held for the
 * lifetime of one app process so a screen can render them, but no clinical
 * payload may reach a persistent store. This suite proves that rule against the
 * real in-memory clinical cache and watches the one persistent API the app uses.
 * Synthetic fixtures only; no patient data or credentials appear here.
 */
import { jest } from '@jest/globals';

import {
  cacheClinical,
  clearClinicalCache,
  clinicalCacheSize,
  readClinical,
} from '../../api/clinicalCache';

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

describe('avoid persistent clinical caches (PAT-02 clause 4)', () => {
  it('keepsClinicalValuesInProcessMemoryOnly', () => {
    const report = { id: 'rpt-synthetic-1', text: 'synthetic released report' };
    expect(clinicalCacheSize()).toBe(0);

    cacheClinical('reports', 'report-1', report);

    expect(readClinical('reports', 'report-1')).toEqual(report);
    expect(clinicalCacheSize()).toBe(1);
  });

  it('neverWritesClinicalDataToPersistentStorage', () => {
    const attachmentBytes = new Uint8Array([1, 2, 3, 4]);

    cacheClinical('reports', 'report-1', { id: 'rpt-synthetic-1', text: 'synthetic report' });
    cacheClinical('attachments', 'att-1', attachmentBytes);

    expect(clinicalCacheSize()).toBe(2);
    expect(secureStore.setItemAsync).not.toHaveBeenCalled();
  });

  it('refusesToReturnAClinicalValueAfterSignOut', () => {
    const staleReport = { id: 'rpt-stale', text: 'must not be returned after sign-out' };
    cacheClinical('reports', 'report-1', staleReport);
    expect(readClinical('reports', 'report-1')).toEqual(staleReport);

    clearClinicalCache();

    expect(readClinical('reports', 'report-1')).toBeUndefined();
    expect(clinicalCacheSize()).toBe(0);
  });
});
