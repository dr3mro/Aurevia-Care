import { describe, expect, it } from 'vitest';
import {
  labKeys,
  labText,
  labelFieldMessageKey,
  mismatchMessageKey,
  reasonMessageKey,
  stateMessageKey,
} from './messages';
import type { AccessionState, LabelFieldKey, MismatchReason, RejectionReasonCode } from './domain';

describe('collection desk message bundles', () => {
  it('provides a non-empty label in both bundles for every key', () => {
    for (const locale of ['en', 'ar'] as const) {
      for (const key of labKeys) {
        expect(labText(locale, key).length).toBeGreaterThan(0);
      }
    }
  });

  it('translates the safety-critical labels into Arabic', () => {
    expect(labText('en', 'mismatchTitle')).not.toBe(labText('ar', 'mismatchTitle'));
    expect(labText('ar', 'mismatchTitle')).toMatch(/[\u0600-\u06FF]/);
    expect(labText('ar', 'reasonRequired')).toMatch(/[\u0600-\u06FF]/);
  });

  it('maps every accession state to a defined message key', () => {
    for (const state of Object.keys(stateMessageKey) as AccessionState[]) {
      expect(labKeys).toContain(stateMessageKey[state]);
    }
    expect(Object.keys(stateMessageKey)).toHaveLength(5);
  });

  it('maps every mismatch reason to a defined explanation key', () => {
    for (const reason of Object.keys(mismatchMessageKey) as MismatchReason[]) {
      expect(labKeys).toContain(mismatchMessageKey[reason]);
    }
    expect(Object.keys(mismatchMessageKey)).toHaveLength(3);
  });

  it('maps every rejection reason to a defined message key', () => {
    for (const reason of Object.keys(reasonMessageKey) as RejectionReasonCode[]) {
      expect(labKeys).toContain(reasonMessageKey[reason]);
    }
    expect(Object.keys(reasonMessageKey)).toHaveLength(5);
  });

  it('maps every label field to a defined message key', () => {
    for (const field of Object.keys(labelFieldMessageKey) as LabelFieldKey[]) {
      expect(labKeys).toContain(labelFieldMessageKey[field]);
    }
    expect(Object.keys(labelFieldMessageKey)).toHaveLength(7);
  });
});
