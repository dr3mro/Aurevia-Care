import { describe, expect, it } from 'vitest';
import { isReprint, offeredCopies, printEligibility } from './domain';
import type { PrescriptionForPrint } from './types';

/** Synthetic attested prescription through an approved electronic channel. */
const allowed: PrescriptionForPrint = {
  id: 'RX-SYN-1',
  version: 4,
  state: 'attested',
  attested: true,
  printChannel: 'E_PRESCRIBE',
  handoutVersion: null,
};

describe('prescription print eligibility', () => {
  it('blocks a draft with a draft reason', () => {
    expect(printEligibility({ ...allowed, state: 'draft', attested: false })).toEqual({
      allowed: false,
      blocker: 'draft',
    });
  });

  it.each(['ambiguous', 'rejected', 'cancelled'] as const)(
    'blocks the non-printable state %s',
    (state) => {
      expect(printEligibility({ ...allowed, state })).toEqual({
        allowed: false,
        blocker: 'not-printable',
      });
    },
  );

  it('blocks an unattested prescription even in an allowed state', () => {
    expect(printEligibility({ ...allowed, attested: false })).toEqual({
      allowed: false,
      blocker: 'not-attested',
    });
  });

  it('blocks a prescription with no print channel', () => {
    expect(printEligibility({ ...allowed, printChannel: null })).toEqual({
      allowed: false,
      blocker: 'channel-not-approved',
    });
  });

  it('blocks a prescription with an unapproved print channel', () => {
    expect(printEligibility({ ...allowed, printChannel: 'UNKNOWN_CHANNEL' })).toEqual({
      allowed: false,
      blocker: 'channel-not-approved',
    });
  });

  it.each([
    'attested',
    'submitted',
    'delivery_confirmed',
    'accepted',
    'clarification',
    'dispensed',
    'printed',
  ] as const)('allows the printable state %s when attested with an approved channel', (state) => {
    expect(printEligibility({ ...allowed, state })).toEqual({ allowed: true });
  });
});

describe('prescription reprint detection', () => {
  it('is not a reprint when no handout version exists for the source revision', () => {
    expect(isReprint(allowed)).toBe(false);
  });

  it('is a reprint when a handout version already exists for the source revision', () => {
    expect(isReprint({ ...allowed, handoutVersion: 2 })).toBe(true);
  });
});

describe('prescription handout copies', () => {
  it('offers only the patient copy for an electronic channel', () => {
    expect(offeredCopies(allowed)).toEqual(['patient']);
  });

  it('offers a patient and a pharmacy copy for a printable local channel', () => {
    expect(offeredCopies({ ...allowed, printChannel: 'PRINTABLE_LOCAL' })).toEqual([
      'patient',
      'pharmacy',
    ]);
  });
});
