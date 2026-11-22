/**
 * PAT-01 command boundary: the confirm request is versioned and scoped to the
 * verified patient, and every transport failure maps to one typed outcome.
 * All identifiers and instants are synthetic.
 */
import { jest } from '@jest/globals';

import { TransportError } from '../../api/transport';
import type { Transport } from '../../api/transport';

import {
  claimPendingCommand,
  confirmAppointment,
  releasePendingCommand,
} from './confirmClient';

const command = {
  appointmentId: 'apt-1',
  patientId: 'synthetic-patient',
  expectedVersion: 3,
};

/** Build a transport whose POST is controlled by one synthetic reply. */
function transportFor(post: () => Promise<unknown>): {
  transport: Transport;
  post: jest.Mock<(path: string, body?: unknown) => Promise<unknown>>;
} {
  const postMock = jest.fn(post) as jest.Mock<(path: string, body?: unknown) => Promise<unknown>>;
  return {
    transport: {
      get: jest.fn(async () => undefined) as Transport['get'],
      post: postMock as Transport['post'],
      put: jest.fn(async () => undefined) as Transport['put'],
    },
    post: postMock,
  };
}

describe('pending command guard (PAT-01)', () => {
  it('claims a free slot for one command and rejects a second claim', () => {
    const slot = { current: null };

    expect(claimPendingCommand(slot, 'apt-1')).toBe(true);
    expect(slot.current).toBe('apt-1');
    expect(claimPendingCommand(slot, 'apt-2')).toBe(false);
    expect(slot.current).toBe('apt-1');
  });

  it('frees the slot so the same row can be confirmed again after a failure', () => {
    const slot = { current: 'apt-1' };

    releasePendingCommand(slot);

    expect(slot.current).toBeNull();
    expect(claimPendingCommand(slot, 'apt-1')).toBe(true);
  });
});

describe('confirmAppointment', () => {
  it('posts the patient identifier and the exact version the row was read with', async () => {
    const { transport, post } = transportFor(async () => ({
      confirmedAt: '2026-11-21T10:05:00Z',
      version: 4,
    }));

    const result = await confirmAppointment(transport, command);

    expect(post).toHaveBeenCalledWith('/api/v1/patient/appointments/apt-1/confirm', {
      patientId: 'synthetic-patient',
      expectedVersion: 3,
    });
    expect(result).toEqual({ kind: 'confirmed', confirmedAt: '2026-11-21T10:05:00Z', version: 4 });
  });

  it('encodes an appointment identifier that contains a path separator', async () => {
    const { transport, post } = transportFor(async () => ({
      confirmedAt: '2026-11-21T10:05:00Z',
      version: 4,
    }));

    await confirmAppointment(transport, { ...command, appointmentId: 'apt/1' });

    expect(post).toHaveBeenCalledWith('/api/v1/patient/appointments/apt%2F1/confirm', expect.any(Object));
  });

  it('maps a stale version (409 or 412) to a reload outcome', async () => {
    for (const kind of ['conflict'] as const) {
      const { transport } = transportFor(async () => {
        throw new TransportError(kind, { correlationId: 'corr-stale' });
      });
      expect(await confirmAppointment(transport, command)).toEqual({ kind: 'stale' });
    }
  });

  it('maps an unauthenticated rejection to a session-expiry outcome', async () => {
    const { transport } = transportFor(async () => {
      throw new TransportError('unauthenticated', { correlationId: 'corr-expired' });
    });

    expect(await confirmAppointment(transport, command)).toEqual({ kind: 'unauthenticated' });
  });

  it.each(['server', 'network', 'timeout', 'validation', 'forbidden'] as const)(
    'returns the typed error for a %s failure',
    async (kind) => {
      const { transport } = transportFor(async () => {
        throw new TransportError(kind, { correlationId: 'corr-failure' });
      });

      const result = await confirmAppointment(transport, command);

      expect(result.kind).toBe('failure');
      if (result.kind === 'failure') {
        expect(result.error.kind).toBe(kind);
        expect(result.error.correlationId).toBe('corr-failure');
      }
    },
  );

  it('wraps an unexpected throw as a typed server failure', async () => {
    const { transport } = transportFor(async () => {
      throw new Error('unexpected');
    });

    const result = await confirmAppointment(transport, command);

    expect(result.kind).toBe('failure');
  });

  it.each([
    ['not an object', undefined],
    ['an array', []],
    ['a missing instant', { version: 4 }],
    ['a blank instant', { confirmedAt: '   ', version: 4 }],
    ['a missing version', { confirmedAt: '2026-11-21T10:05:00Z' }],
    ['a fractional version', { confirmedAt: '2026-11-21T10:05:00Z', version: 1.5 }],
    ['a negative version', { confirmedAt: '2026-11-21T10:05:00Z', version: -1 }],
  ])('rejects a malformed reply with %s', async (_name, reply) => {
    const { transport } = transportFor(async () => reply);

    expect((await confirmAppointment(transport, command)).kind).toBe('failure');
  });
});
