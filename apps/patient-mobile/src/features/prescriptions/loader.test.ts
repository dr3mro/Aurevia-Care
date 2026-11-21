/**
 * PAT-02 acceptance: the authenticated loader holds no partial clinical data
 * and leaves nothing cached after a failed load or sign-out.
 */
import { clearClinicalCache } from '../../api/clinicalCache';

import { loadReleasedPrescriptions } from './loader';
import type { ReleasedPrescriptionClient } from './loader';
import { visiblePrescriptions } from './store';
import type { ReleasedPrescription } from './types';

const item: ReleasedPrescription = {
  prescriptionId: 'rx-1',
  reference: 'SUB-1001',
  medicationLabel: 'Amoxicillin 500 mg',
  language: 'en',
  events: [{ kind: 'submitted', at: '2026-06-10T09:00:00Z', actor: 'synthetic-prescriber' }],
};

function clientReturning(items: readonly ReleasedPrescription[]): ReleasedPrescriptionClient {
  return { listReleasedPrescriptions: async () => items };
}

describe('loadReleasedPrescriptions (PAT-02)', () => {
  beforeEach(() => {
    clearClinicalCache();
  });

  it('holds the loaded prescriptions for the session', async () => {
    const result = await loadReleasedPrescriptions(clientReturning([item]));
    expect(result).toEqual({ prescriptions: [item], error: null });
    expect(visiblePrescriptions()).toEqual([item]);
  });

  it('excludes cancelled prescriptions so no terminal item shows an in-progress or handed-over word', async () => {
    const cancelled: ReleasedPrescription = {
      ...item,
      prescriptionId: 'rx-2',
      events: [
        { kind: 'submitted', at: '2026-06-10T09:00:00Z', actor: 'synthetic-prescriber' },
        { kind: 'accepted', at: '2026-06-10T10:00:00Z', actor: 'synthetic-pharmacy' },
        { kind: 'cancelled', at: '2026-06-11T10:00:00Z', actor: 'synthetic-prescriber' },
      ],
    };
    const result = await loadReleasedPrescriptions(clientReturning([item, cancelled]));
    expect(result.prescriptions).toEqual([item]);
    expect(visiblePrescriptions()).toEqual([item]);
  });

  it('leaves no partial cache and reports a generic error on failure', async () => {
    const failing: ReleasedPrescriptionClient = {
      listReleasedPrescriptions: async () => {
        throw new Error('network down');
      },
    };
    const result = await loadReleasedPrescriptions(failing);
    expect(result).toEqual({ prescriptions: [], error: 'Unable to load prescriptions' });
    expect(visiblePrescriptions()).toEqual([]);
  });

  it('localizes the generic load error to Arabic', async () => {
    const failing: ReleasedPrescriptionClient = {
      listReleasedPrescriptions: async () => {
        throw new Error('network down');
      },
    };
    const result = await loadReleasedPrescriptions(failing, 'ar');
    expect(result.error).toBe('تعذّر تحميل الوصفات');
    expect(visiblePrescriptions()).toEqual([]);
  });
});
