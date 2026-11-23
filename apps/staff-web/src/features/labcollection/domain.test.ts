import { describe, expect, it } from 'vitest';
import {
  checkMatch,
  findOrders,
  labelRows,
  rejectionReasonCodes,
  validateRejection,
} from './domain';
import type { CollectionOrder, PresentedIdentity } from './domain';
import { alphaOrder, betaOrder, labelAlpha, presentedAlpha } from './fixtures';

describe('patient and accession match check', () => {
  it('accepts an exactly presented identity', () => {
    expect(checkMatch(alphaOrder, presentedAlpha)).toEqual({ matched: true, reasons: [] });
  });

  it('explains a mismatched patient identifier', () => {
    expect(checkMatch(alphaOrder, { ...presentedAlpha, patientId: 'wrong-patient' })).toEqual({
      matched: false,
      reasons: ['patient'],
    });
  });

  it('explains a mismatched accession number', () => {
    expect(checkMatch(alphaOrder, { ...presentedAlpha, accessionNo: 'ACC-999' })).toEqual({
      matched: false,
      reasons: ['accession-number'],
    });
  });

  it('explains a mismatched accession issuer', () => {
    expect(checkMatch(alphaOrder, { ...presentedAlpha, accessionIssuer: 'OTHER-ISSUER' })).toEqual({
      matched: false,
      reasons: ['accession-issuer'],
    });
  });

  it('reports every mismatch together', () => {
    const presented: PresentedIdentity = {
      patientId: 'wrong-patient',
      accessionNo: 'ACC-999',
      accessionIssuer: 'OTHER-ISSUER',
    };
    expect(checkMatch(alphaOrder, presented)).toEqual({
      matched: false,
      reasons: ['patient', 'accession-number', 'accession-issuer'],
    });
  });
});

describe('finding an order by accession or patient', () => {
  it('returns nothing for an empty or whitespace-only query', () => {
    expect(findOrders([alphaOrder, betaOrder], 'accession', '')).toEqual([]);
    expect(findOrders([alphaOrder, betaOrder], 'patient', '   ')).toEqual([]);
  });

  it('matches an accession number case-insensitively', () => {
    expect(findOrders([alphaOrder, betaOrder], 'accession', 'acc-100').map((o) => o.id)).toEqual([
      alphaOrder.id,
    ]);
    expect(findOrders([alphaOrder, betaOrder], 'accession', 'acc-200').map((o) => o.id)).toEqual([
      betaOrder.id,
    ]);
  });

  it('matches the patient by identifier, Latin name or local name', () => {
    expect(findOrders([alphaOrder, betaOrder], 'patient', 'patient-100').map((o) => o.id)).toEqual([
      alphaOrder.id,
    ]);
    expect(
      findOrders([alphaOrder, betaOrder], 'patient', 'synthetic layla').map((o) => o.id),
    ).toEqual([alphaOrder.id]);
    expect(findOrders([alphaOrder, betaOrder], 'patient', 'ريم').map((o) => o.id)).toEqual([
      betaOrder.id,
    ]);
    // A defined local name that does not contain the query is not a match.
    expect(findOrders([alphaOrder], 'patient', 'ريم')).toEqual([]);
  });

  it('does not fail when a patient has no local-script name', () => {
    const withoutLocal: CollectionOrder = {
      ...alphaOrder,
      patientName: { latinName: 'Synthetic Layla' },
    };
    expect(findOrders([withoutLocal], 'patient', 'layla').map((o) => o.id)).toEqual([
      alphaOrder.id,
    ]);
    expect(findOrders([withoutLocal], 'patient', 'ليلى')).toEqual([]);
  });

  it('returns nothing when no order matches', () => {
    expect(findOrders([alphaOrder, betaOrder], 'accession', 'ACC-NONE')).toEqual([]);
  });
});

describe('rejection reason validation', () => {
  it('requires a reason', () => {
    expect(validateRejection('', 'details')).toEqual({ valid: false, error: 'reason-required' });
  });

  it('accepts any bounded reason that is not "other" without detail', () => {
    for (const reason of rejectionReasonCodes.filter((code) => code !== 'other')) {
      expect(validateRejection(reason, '')).toEqual({ valid: true });
    }
  });

  it('requires detail for the "other" reason', () => {
    expect(validateRejection('other', '')).toEqual({ valid: false, error: 'detail-required' });
    expect(validateRejection('other', '   ')).toEqual({ valid: false, error: 'detail-required' });
  });

  it('accepts the "other" reason when detail is present', () => {
    expect(validateRejection('other', 'synthetic reason detail')).toEqual({ valid: true });
  });

  it('exposes exactly the five bounded backend reason codes', () => {
    expect(rejectionReasonCodes).toEqual([
      'haemolysed',
      'wrong_tube',
      'unlabelled',
      'insufficient_volume',
      'other',
    ]);
  });
});

describe('label sheet fields', () => {
  it('lists every required print field with its value', () => {
    expect(labelRows(labelAlpha)).toEqual([
      { key: 'accessionNo', value: 'ACC-100' },
      { key: 'accessionIssuer', value: 'SYN-ISSUER' },
      { key: 'patientName', value: 'Synthetic Layla' },
      { key: 'patientMrn', value: 'MRN-100' },
      { key: 'patientMrnIssuer', value: 'SYN-ISSUER' },
      { key: 'specimenType', value: 'SERUM' },
      { key: 'collectedAt', value: '2026-03-09T09:00:00Z' },
    ]);
  });
});
