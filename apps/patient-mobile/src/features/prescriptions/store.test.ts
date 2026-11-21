/**
 * PAT-02 acceptance: no prescription projection may survive sign-out.
 *
 * Prescriptions are held only in the shared transient clinical cache. The app's
 * existing sign-out path calls `clearClinicalCache`, so these tests use that
 * exact mechanism to prove a sign-out leaves no prescription data behind.
 */
import { cacheClinical, clearClinicalCache, clinicalCacheSize } from '../../api/clinicalCache';

import { clearSessionClinicalCache, rememberPrescriptions, visiblePrescriptions } from './store';
import type { ReleasedPrescription } from './types';

const item: ReleasedPrescription = {
  prescriptionId: 'rx-1',
  reference: 'SUB-1001',
  medicationLabel: 'Amoxicillin 500 mg',
  language: 'en',
  events: [{ kind: 'submitted', at: '2026-06-10T09:00:00Z', actor: 'synthetic-prescriber' }],
};

describe('prescription store (PAT-02)', () => {
  beforeEach(() => {
    clearClinicalCache();
  });

  it('returns an empty list when nothing was remembered', () => {
    expect(visiblePrescriptions()).toEqual([]);
  });

  it('returns the exact items remembered for the session', () => {
    rememberPrescriptions([item]);
    expect(visiblePrescriptions()).toEqual([item]);
  });

  it('clears prescription data on the sign-out clinical-cache clear', () => {
    rememberPrescriptions([item]);
    expect(visiblePrescriptions()).toEqual([item]);
    clearClinicalCache();
    expect(visiblePrescriptions()).toEqual([]);
  });

  it('clears prescription data through the session sign-out hook', () => {
    rememberPrescriptions([item]);
    clearSessionClinicalCache();
    expect(visiblePrescriptions()).toEqual([]);
  });

  it('keeps prescription entries distinct from other clinical projections', () => {
    cacheClinical('reports', 'report-1', { text: 'synthetic-report' });
    rememberPrescriptions([item]);
    expect(clinicalCacheSize()).toBe(2);
    clearClinicalCache();
    expect(clinicalCacheSize()).toBe(0);
  });
});
