/**
 * RX-02 / PAT-02 acceptance: plain prescription status words.
 *
 * A pharmacy acceptance is deliberately NOT dispensing evidence. These tests
 * pin the four patient-visible words and the rule that only dispensing evidence
 * may imply the medicine has been handed over.
 */
import { STATUS_WORDS } from './messages';
import { isCancelled, isRtl, pillVariantFor, plainStatusFor, statusLabel } from './status';
import type { PrescriptionEvent, ReleasedPrescription, PrescriptionStatus } from './types';

function prescription(...kinds: PrescriptionEvent['kind'][]): ReleasedPrescription {
  return {
    prescriptionId: 'rx-1',
    reference: 'SUB-1001',
    medicationLabel: 'Amoxicillin 500 mg',
    language: 'en',
    events: kinds.map((kind) => ({ kind, at: '2026-06-10T09:00:00Z', actor: 'synthetic-pharmacy' })),
  };
}

describe('plainStatusFor (RX-02)', () => {
  it('reports a submitted prescription as sent to the pharmacy', () => {
    expect(plainStatusFor(prescription('submitted'))).toBe('sent');
  });

  it('does not treat delivery confirmation as acceptance or readiness', () => {
    expect(plainStatusFor(prescription('submitted', 'deliveryConfirmed'))).toBe('sent');
  });

  it('reports pharmacy acceptance without implying dispensing', () => {
    expect(plainStatusFor(prescription('submitted', 'accepted'))).toBe('accepted');
  });

  it('reports a clarification request as needing a check with the doctor', () => {
    expect(plainStatusFor(prescription('submitted', 'accepted', 'clarificationRequested'))).toBe('needsCheck');
  });

  it('reports a rejection with a reason as needing a check with the doctor', () => {
    expect(plainStatusFor(prescription('submitted', 'rejected'))).toBe('needsCheck');
  });

  it('reports dispensing evidence as handed over even when earlier events exist', () => {
    expect(plainStatusFor(prescription('submitted', 'accepted', 'dispensingEvidence'))).toBe('handedOver');
  });

  it('treats a prescription with no events as merely sent', () => {
    expect(plainStatusFor(prescription())).toBe('sent');
  });

  it('treats a renewal as a fresh submission rather than a stale acceptance', () => {
    expect(plainStatusFor(prescription('submitted', 'accepted', 'renewal'))).toBe('sent');
    expect(plainStatusFor(prescription('renewal'))).toBe('sent');
  });
});

describe('isCancelled (RX-02)', () => {
  it('identifies a cancelled prescription even when earlier events exist', () => {
    expect(isCancelled(prescription('submitted', 'accepted', 'cancelled'))).toBe(true);
  });

  it('does not identify an active prescription as cancelled', () => {
    expect(isCancelled(prescription('submitted', 'accepted', 'dispensingEvidence'))).toBe(false);
    expect(isCancelled(prescription())).toBe(false);
  });
});

describe('no ready claim without dispensing evidence (RX-02)', () => {
  const allStatuses: PrescriptionStatus[] = ['sent', 'accepted', 'needsCheck', 'handedOver'];

  it('never uses the words "ready" or "Handed over" for a non-dispensing status', () => {
    for (const status of ['sent', 'accepted', 'needsCheck'] as const) {
      const label = statusLabel('en', status);
      expect(label.toLowerCase()).not.toContain('ready');
      expect(label).not.toBe('Handed over');
    }
  });

  it('reserves the success pill for handed over and keeps the rest non-success', () => {
    expect(pillVariantFor('handedOver')).toBe('success');
    expect(pillVariantFor('accepted')).toBe('info');
    expect(pillVariantFor('needsCheck')).toBe('warning');
    expect(pillVariantFor('sent')).toBe('neutral');
  });

  it('only returns handed over when dispensing evidence is present', () => {
    expect(plainStatusFor(prescription('submitted', 'accepted', 'clarificationRequested'))).not.toBe('handedOver');
    expect(plainStatusFor(prescription('dispensingEvidence'))).toBe('handedOver');
  });

  it('has an English and an Arabic word for every status', () => {
    for (const status of allStatuses) {
      expect(STATUS_WORDS[status].en.length).toBeGreaterThan(0);
      expect(STATUS_WORDS[status].ar.length).toBeGreaterThan(0);
    }
  });
});

describe('status labels and direction (PAT-02, UX-01)', () => {
  it('shows the exact four plain English words', () => {
    expect(statusLabel('en', 'sent')).toBe('Sent to pharmacy');
    expect(statusLabel('en', 'accepted')).toBe('Pharmacy accepted');
    expect(statusLabel('en', 'needsCheck')).toBe('Pharmacy needs to check with your doctor');
    expect(statusLabel('en', 'handedOver')).toBe('Handed over');
  });

  it('shows Arabic plain words', () => {
    expect(statusLabel('ar', 'sent')).toBe('أُرسلت إلى الصيدلية');
    expect(statusLabel('ar', 'accepted')).toBe('قبلتها الصيدلية');
    expect(statusLabel('ar', 'needsCheck')).toBe('تحتاج الصيدلية إلى مراجعة طبيبك');
    expect(statusLabel('ar', 'handedOver')).toBe('تم تسليم الدواء');
  });

  it('lays out Arabic right-to-left and English left-to-right', () => {
    expect(isRtl('ar')).toBe(true);
    expect(isRtl('en')).toBe(false);
  });
});
