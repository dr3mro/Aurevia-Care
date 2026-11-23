import { describe, expect, it } from 'vitest';
import type { ImagingWorklistOrder } from './domain';
import {
  MODALITIES,
  STATUS_FILTERS,
  STATUS_FLOW,
  canHandoff,
  canLinkStudy,
  canSchedule,
  checkPatientAndAccession,
  filterOrders,
  nextStatus,
  ordersForSite,
} from './domain';
import {
  alphaOrganizationId,
  betaOrganizationId,
  syntheticOrders,
  syntheticPerformingSite,
} from './fixtures';

/** Build one synthetic order with overridable fields for a table case. */
function order(overrides: Partial<ImagingWorklistOrder> = {}): ImagingWorklistOrder {
  return {
    organizationId: alphaOrganizationId,
    orderId: 'rad-001',
    performingSiteId: 'site-main',
    patientId: 'synthetic-patient-001',
    orderCode: 'ORD-001',
    urgency: 'routine',
    status: 'received',
    version: 1,
    scheduledAt: null,
    modality: null,
    accession: null,
    accessionIssuer: null,
    studyInstanceUid: null,
    ...overrides,
  };
}

describe('radworklist status flow', () => {
  it('advances only to the immediate next status and stops at reported', () => {
    expect(STATUS_FLOW).toEqual(['received', 'scheduled', 'performed', 'reported']);
    expect(nextStatus('received')).toBe('scheduled');
    expect(nextStatus('scheduled')).toBe('performed');
    expect(nextStatus('performed')).toBe('reported');
    expect(nextStatus('reported')).toBeUndefined();
  });

  it('offers all four statuses plus the unfiltered option', () => {
    expect(STATUS_FILTERS).toEqual(['all', 'received', 'scheduled', 'performed', 'reported']);
  });

  it('exposes only the approved modalities', () => {
    expect(MODALITIES).toEqual(['ct', 'mri', 'xray', 'ultrasound', 'pet']);
  });

  it('allows scheduling only for a received order', () => {
    expect(canSchedule(order({ status: 'received' }))).toBe(true);
    expect(canSchedule(order({ status: 'scheduled' }))).toBe(false);
    expect(canSchedule(order({ status: 'performed' }))).toBe(false);
    expect(canSchedule(order({ status: 'reported' }))).toBe(false);
  });

  it('allows a study link only for a scheduled order that has an accession', () => {
    expect(canLinkStudy(order({ status: 'scheduled', accession: 'ACC-1' }))).toBe(true);
    expect(canLinkStudy(order({ status: 'received', accession: 'ACC-1' }))).toBe(false);
    expect(canLinkStudy(order({ status: 'scheduled', accession: null }))).toBe(false);
  });

  it('allows handoff only for a performed order', () => {
    expect(canHandoff(order({ status: 'performed' }))).toBe(true);
    expect(canHandoff(order({ status: 'scheduled' }))).toBe(false);
    expect(canHandoff(order({ status: 'reported' }))).toBe(false);
  });
});

describe('radworklist patient and accession check', () => {
  it('matches only when both labels agree with the order', () => {
    const target = order({ patientId: 'patient-1', accession: 'ACC-1' });
    expect(checkPatientAndAccession(target, 'patient-1', 'ACC-1')).toEqual({
      ok: true,
      patientMatches: true,
      accessionMatches: true,
    });
  });

  it('reports a patient-only mismatch', () => {
    const target = order({ patientId: 'patient-1', accession: 'ACC-1' });
    expect(checkPatientAndAccession(target, 'patient-2', 'ACC-1')).toEqual({
      ok: false,
      patientMatches: false,
      accessionMatches: true,
    });
  });

  it('reports an accession-only mismatch', () => {
    const target = order({ patientId: 'patient-1', accession: 'ACC-1' });
    expect(checkPatientAndAccession(target, 'patient-1', 'ACC-2')).toEqual({
      ok: false,
      patientMatches: true,
      accessionMatches: false,
    });
  });

  it('reports a mismatch of both labels without a short-circuit error', () => {
    const target = order({ patientId: 'patient-1', accession: 'ACC-1' });
    expect(checkPatientAndAccession(target, 'patient-2', 'ACC-2')).toEqual({
      ok: false,
      patientMatches: false,
      accessionMatches: false,
    });
  });
});

describe('radworklist scope and filter', () => {
  it('returns only the active tenant and performing site', () => {
    const scoped = ordersForSite(syntheticOrders, alphaOrganizationId, syntheticPerformingSite);
    expect(scoped.length).toBeGreaterThan(0);
    expect(
      scoped.every(
        (entry) =>
          entry.organizationId === alphaOrganizationId &&
          entry.performingSiteId === syntheticPerformingSite,
      ),
    ).toBe(true);
    expect(ordersForSite(syntheticOrders, alphaOrganizationId, 'site-other')).toEqual([]);
    expect(ordersForSite(syntheticOrders, betaOrganizationId, syntheticPerformingSite)).toEqual([
      syntheticOrders[4],
    ]);
  });

  it('keeps every order for the unfiltered option and narrows for a status', () => {
    expect(filterOrders(syntheticOrders, 'all')).toEqual(syntheticOrders);
    const received = filterOrders(syntheticOrders, 'received');
    expect(received.length).toBeGreaterThan(0);
    expect(received.every((entry) => entry.status === 'received')).toBe(true);
    expect(filterOrders(syntheticOrders, 'reported')).toEqual([syntheticOrders[3]]);
  });
});
