import { describe, expect, it } from 'vitest';
import { RESPONSE_TARGET_HOURS, isSlaBreached } from './domain';
import type { Conversation } from './types';

function conversation(overrides: Partial<Conversation> = {}): Conversation {
  return {
    id: 'conv-1',
    version: 1,
    patientId: 'SYN-PAT-0001',
    patientSubject: 'patient-1',
    unitKey: 'clinic',
    connectionId: 'conn-1',
    topic: null,
    state: 'awaiting_staff',
    assigneeSubject: null,
    patientReadSequence: 1,
    staffReadSequence: 0,
    createdAt: '2026-01-01T00:00:00.000Z',
    ...overrides,
  };
}

describe('conversation response target', () => {
  const now = Date.parse('2026-01-02T12:00:00.000Z');

  it('uses a bounded default target while the unit MSG-06 policy is unpublished', () => {
    expect(RESPONSE_TARGET_HOURS).toBe(24);
  });

  it('marks an unanswered conversation older than the target as breached', () => {
    expect(isSlaBreached(conversation(), now)).toBe(true);
  });

  it('does not mark a fresh unanswered conversation as breached', () => {
    const fresh = conversation({ createdAt: '2026-01-02T11:00:00.000Z' });
    expect(isSlaBreached(fresh, now)).toBe(false);
  });

  it.each(['awaiting_patient', 'resolved', 'closed'] as const)(
    'does not mark a %s conversation as breached',
    (state) => {
      expect(isSlaBreached(conversation({ state }), now)).toBe(false);
    },
  );
});
