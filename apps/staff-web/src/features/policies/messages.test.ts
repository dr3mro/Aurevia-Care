import { describe, expect, it } from 'vitest';
import {
  approvalMessageKey,
  policyKeys,
  policyMessage,
  policyMessages,
  policyText,
  releaseAccessMessageKey,
  releaseModeMessageKey,
} from './messages';

describe('policy message bundles', () => {
  it('carries every declared key in both languages', () => {
    expect(Object.keys(policyMessages.en).sort()).toEqual([...policyKeys].sort());
    expect(Object.keys(policyMessages.ar).sort()).toEqual([...policyKeys].sort());
  });

  it('keeps every label non-empty in both languages', () => {
    for (const key of policyKeys) {
      expect(policyMessages.en[key].length).toBeGreaterThan(0);
      expect(policyMessages.ar[key].length).toBeGreaterThan(0);
    }
  });

  it('looks up a label for the active locale', () => {
    expect(policyText('en', 'pageTitle')).toBe('Templates, dictionaries and release policies');
    expect(policyText('ar', 'pageTitle')).toBe('القوالب ومعاجم الحقول وسياسات إتاحة المريض');
  });
});

describe('policyMessage', () => {
  it('fills named placeholders without depending on their order', () => {
    expect(policyMessage('en', 'approveTitle', { version: '2', key: 'release.hold' })).toBe(
      'Approve version 2 of release.hold?',
    );
  });

  it('leaves an unknown placeholder visible', () => {
    expect(policyMessage('en', 'approveTitle', { version: '2' })).toContain('{key}');
  });

  it('uses the empty value map when none is supplied', () => {
    expect(policyMessage('ar', 'approveTitle')).toContain('{version}');
  });
});

describe('policy state label maps', () => {
  it('maps every release mode, approval state and access state', () => {
    expect(releaseModeMessageKey.immediate).toBe('modeImmediate');
    expect(releaseModeMessageKey.timed).toBe('modeTimed');
    expect(releaseModeMessageKey.hold).toBe('modeHold');
    expect(approvalMessageKey.approved).toBe('approved');
    expect(approvalMessageKey.pending).toBe('pending');
    expect(releaseAccessMessageKey.released).toBe('accessReleased');
    expect(releaseAccessMessageKey.scheduled).toBe('accessScheduled');
    expect(releaseAccessMessageKey.held).toBe('accessHeld');
    expect(releaseAccessMessageKey.review).toBe('accessReview');
  });
});
