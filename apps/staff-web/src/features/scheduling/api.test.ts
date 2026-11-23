import { describe, expect, it, vi } from 'vitest';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import type { AppointmentViewResponse } from './api';
import { book, cancel, checkIn, listAppointments, rangeWindow, toAppointment } from './api';
import { alphaOrganizationId } from './fixtures';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

/** Build one synthetic API view with a fully populated appointment. */
function apiView(
  overrides: Partial<AppointmentViewResponse['appointment']> = {},
): AppointmentViewResponse {
  return {
    appointment: {
      id: 'apt-001',
      version: 3,
      patientId: 'patient-001',
      facilityCode: 'MAIN',
      clinicianCode: 'clinician-7',
      roomCode: 'room-1',
      visitType: 'follow-up',
      startsAt: '2026-03-09T09:00:00Z',
      endsAt: '2026-03-09T09:30:00Z',
      state: 'booked',
      seriesId: null,
      expiresAt: null,
      overrideReason: null,
      ...overrides,
    },
    history: [],
  };
}

function descriptorAt(call: number): RequestDescriptor {
  return required(sendMock.mock.calls[call])[0];
}

describe('appointment API client', () => {
  it('lists a UTC day range on the contract path with the tenant header', async () => {
    const page = { items: [apiView()], nextCursor: null };
    sendMock.mockResolvedValueOnce(page);

    await expect(
      listAppointments(
        { from: '2026-03-09T00:00:00Z', to: '2026-03-10T00:00:00Z' },
        alphaOrganizationId,
      ),
    ).resolves.toEqual(page);

    expect(sendMock).toHaveBeenCalledTimes(1);
    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('GET');
    expect(descriptor.url).toBe(
      '/api/v1/appointments?from=2026-03-09T00%3A00%3A00Z&to=2026-03-10T00%3A00%3A00Z',
    );
    expect(descriptor.headers['X-Active-Organization']).toBe(alphaOrganizationId);
    expect(descriptor.headers['If-Match']).toBeUndefined();
  });

  it('includes optional cursor, resource, state and limit filters', async () => {
    sendMock.mockResolvedValueOnce({ items: [], nextCursor: null });

    await listAppointments(
      {
        from: 'from',
        to: 'to',
        cursor: 'cursor-2',
        resource: 'room-1',
        state: 'booked',
        limit: 25,
      },
      alphaOrganizationId,
    );

    const url = descriptorAt(0).url;
    expect(url).toContain('cursor=cursor-2');
    expect(url).toContain('resource=room-1');
    expect(url).toContain('state=booked');
    expect(url).toContain('limit=25');
  });

  it('propagates a typed transport failure unchanged', async () => {
    const failure = new TransportError('conflict', {
      correlationId: 'corr-1',
      code: 'STALE',
      retryAfterSeconds: undefined,
    });
    sendMock.mockRejectedValueOnce(failure);

    await expect(listAppointments({ from: 'from', to: 'to' }, alphaOrganizationId)).rejects.toBe(
      failure,
    );
  });

  it('books with a fresh idempotency key and the full command body', async () => {
    const created = apiView();
    sendMock.mockResolvedValueOnce(created);
    const body = {
      facilityCode: 'MAIN',
      patientId: 'patient-001',
      visitType: 'follow-up',
      clinicianCode: 'clinician-7',
      roomCode: null,
      startsAt: '2026-03-09T09:00:00Z',
      overrideReason: null,
    };

    await expect(book(body, alphaOrganizationId)).resolves.toEqual(created);

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('POST');
    expect(descriptor.url).toBe('/api/v1/appointments');
    expect(descriptor.body).toEqual(body);
    expect(descriptor.headers['X-Active-Organization']).toBe(alphaOrganizationId);
    expect(descriptor.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(descriptor.headers['If-Match']).toBeUndefined();
  });

  it('checks in with the version precondition and no body', async () => {
    sendMock.mockResolvedValueOnce(apiView({ state: 'checked_in' }));

    await expect(checkIn('apt-001', 3, alphaOrganizationId)).resolves.toEqual(
      apiView({ state: 'checked_in' }),
    );

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('POST');
    expect(descriptor.url).toBe('/api/v1/appointments/apt-001/check-in');
    expect(descriptor.headers['If-Match']).toBe('3');
    expect(descriptor.body).toBeUndefined();
  });

  it('cancels with a reason and the version precondition', async () => {
    sendMock.mockResolvedValueOnce(apiView({ state: 'cancelled' }));

    await cancel('apt-001', 3, 'synthetic conflict', alphaOrganizationId);

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('POST');
    expect(descriptor.url).toBe('/api/v1/appointments/apt-001/cancel');
    expect(descriptor.headers['If-Match']).toBe('3');
    expect(descriptor.body).toEqual({ reason: 'synthetic conflict' });
  });
});

describe('toAppointment', () => {
  it('maps an API view into the tenant-scoped domain appointment', () => {
    expect(toAppointment(apiView(), alphaOrganizationId)).toEqual({
      organizationId: alphaOrganizationId,
      id: 'apt-001',
      patientName: { latinName: 'patient-001' },
      scheduledAt: '2026-03-09T09:00:00Z',
      timeZone: 'UTC',
      facilityCode: 'MAIN',
      resourceId: 'clinician-7',
      visitType: 'follow-up',
      state: 'booked',
      version: 3,
    });
  });

  it.each([
    ['held', 'booked'],
    ['booked', 'booked'],
    ['checked_in', 'checked-in'],
    ['in_progress', 'in-progress'],
    ['completed', 'completed'],
    ['cancelled', 'cancelled'],
    ['no_show', 'no-show'],
    ['expired', 'cancelled'],
  ] as const)('maps API state %s to domain state %s', (apiState, expected) => {
    expect(toAppointment(apiView({ state: apiState }), alphaOrganizationId).state).toBe(expected);
  });

  it('falls back to the room and then an empty resource identifier', () => {
    expect(toAppointment(apiView({ clinicianCode: null }), alphaOrganizationId).resourceId).toBe(
      'room-1',
    );
    expect(
      toAppointment(apiView({ clinicianCode: null, roomCode: null }), alphaOrganizationId)
        .resourceId,
    ).toBe('');
  });
});

describe('rangeWindow', () => {
  it('returns one inclusive UTC day from the anchor', () => {
    expect(rangeWindow('day', '2026-03-09')).toEqual({
      from: '2026-03-09T00:00:00Z',
      to: '2026-03-10T00:00:00Z',
    });
  });

  it('returns a Monday-to-Monday week for a midweek anchor', () => {
    expect(rangeWindow('week', '2026-03-09')).toEqual({
      from: '2026-03-09T00:00:00Z',
      to: '2026-03-16T00:00:00Z',
    });
  });

  it('anchors the week on the preceding Monday for a Sunday date', () => {
    expect(rangeWindow('week', '2026-03-15')).toEqual({
      from: '2026-03-09T00:00:00Z',
      to: '2026-03-16T00:00:00Z',
    });
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
