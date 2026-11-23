/**
 * PAT-01 clause 1 acceptance (patient app): the verified patient's appointment
 * list carries the facility IANA zone and the authoritative status, a confirm
 * affordance appears only where the server enables it, and the recorded
 * instant comes from the server reply rather than the device clock.
 *
 * All payloads below are synthetic. A mocked Transport is the only network
 * seam, matching the installed page's command boundary. No production file is
 * changed and no real patient datum or credential appears in this test.
 */
import { jest } from '@jest/globals';

import { TransportError } from '../../api/transport';
import type { Transport } from '../../api/transport';
import {
  appointmentView,
  confirmedAppointmentView,
  sortByScheduled,
} from '../../features/appointments/appointments';
import { confirm, deriveConfirmation } from '../../features/appointments/confirmation';
import { confirmAppointment } from '../../features/appointments/confirmClient';
import { lookup, tenantKey } from '../../features/appointments/tenancy';
import type { Appointment, ZoneFormatter } from '../../features/appointments/types';

/**
 * Synthetic booked appointment in one facility's IANA zone, mirroring the wire
 * shape the authenticated loader validates before projecting a display row.
 */
const booked: Appointment = {
  id: 'apt-1',
  version: 3,
  patientId: 'synthetic-patient',
  facilityCode: 'synthetic-facility',
  facilityTimeZone: 'Asia/Riyadh',
  startsAt: '2026-12-01T07:00:00Z',
  status: 'booked',
  confirmationEnabled: true,
  confirmedAt: null,
};

/** Deterministic formatter that keeps both the UTC instant and the zone visible. */
const zoneFormatter: ZoneFormatter = {
  format: (instant, zone) => `${instant}@${zone}`,
};

/** Command inputs the installed page sends for the row the patient read. */
const command = {
  appointmentId: booked.id,
  patientId: booked.patientId,
  expectedVersion: booked.version,
};

/**
 * Build an authenticated Transport whose POST is governed by one synthetic
 * reply. GET and PUT stay inert because this clause issues only the confirm
 * command; the typed interface keeps the fixture at the real command boundary.
 */
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

describe('PAT-01 clause 1 — facility zone, status, gated confirmation and server instant', () => {
  it('listsFacilityZoneAndStatusAndRecordsTheServerInstant', async () => {
    // Two tenants hold the same local id: only the active tenant row is read.
    const later: Appointment = {
      ...booked,
      id: 'apt-2',
      startsAt: '2026-12-02T07:00:00Z',
    };
    const rows = new Map([
      [tenantKey('org-a', booked.id), booked],
      [tenantKey('org-b', booked.id), later],
    ]);
    expect(lookup(rows, 'org-a', booked.id)).toBe(booked);
    expect(lookup(rows, 'org-b', booked.id)).toBe(later);
    expect(lookup(rows, 'org-c', booked.id)).toBeUndefined();

    const views = sortByScheduled([booked, later]).map((appointment) =>
      appointmentView(
        appointment,
        'org-a',
        zoneFormatter,
        deriveConfirmation(appointment, appointment.confirmationEnabled),
      ),
    );
    expect(views.map((view) => view.id)).toEqual(['apt-1', 'apt-2']);
    const first = views[0];
    if (first === undefined) {
      throw new Error('expected the first appointment to be projected');
    }
    expect(first.key).toBe(tenantKey('org-a', 'apt-1'));
    expect(first.timeZone).toBe('Asia/Riyadh');
    expect(first.status).toBe('booked');
    expect(first.localStart).toBe('2026-12-01T07:00:00Z@Asia/Riyadh');
    expect(first.confirmation).toEqual({ kind: 'pending', confirmedAt: null });
    expect(first.confirmedLocal).toBeNull();

    // The affordance is derived from server-owned enablement, then the domain
    // records the supplied instant when the patient is authenticated.
    const recorded = confirm(first.confirmation, true, '2026-12-01T06:55:00Z');
    expect(recorded).toEqual({ kind: 'confirmed', confirmedAt: '2026-12-01T06:55:00Z' });

    // The installed page posts the exact read version and renders the instant
    // the server returns, never a value produced on the device.
    const { transport, post } = transportFor(async () => ({
      confirmedAt: '2026-12-01T06:55:00Z',
      version: 4,
    }));
    const result = await confirmAppointment(transport, command);
    expect(post).toHaveBeenCalledWith('/api/v1/patient/appointments/apt-1/confirm', {
      patientId: 'synthetic-patient',
      expectedVersion: 3,
    });
    expect(result).toEqual({
      kind: 'confirmed',
      confirmedAt: '2026-12-01T06:55:00Z',
      version: 4,
    });
    if (result.kind !== 'confirmed') {
      throw new Error('expected a confirmed outcome from the server reply');
    }
    const confirmed = confirmedAppointmentView(
      first,
      result.confirmedAt,
      result.version,
      zoneFormatter,
    );
    expect(confirmed.confirmation).toEqual({
      kind: 'confirmed',
      confirmedAt: '2026-12-01T06:55:00Z',
    });
    expect(confirmed.confirmedLocal).toBe('2026-12-01T06:55:00Z@Asia/Riyadh');
    expect(confirmed.version).toBe(4);
    expect(first.confirmation).toEqual({ kind: 'pending', confirmedAt: null });
  });

  it('refusesConfirmationWhenSiteFlagOrStatusDisallowsIt', () => {
    // The site flag and the per-appointment server flag are both required; the
    // stricter value wins so a finished visit never shows a confirm control.
    expect(deriveConfirmation(booked, false)).toEqual({ kind: 'unavailable', confirmedAt: null });
    expect(deriveConfirmation({ ...booked, confirmationEnabled: false }, true)).toEqual({
      kind: 'unavailable',
      confirmedAt: null,
    });
    for (const status of [
      'checked_in',
      'in_progress',
      'completed',
      'cancelled',
      'no_show',
    ] as const) {
      expect(deriveConfirmation({ ...booked, status }, true)).toEqual({
        kind: 'unavailable',
        confirmedAt: null,
      });
    }
    const unavailable = deriveConfirmation(booked, false);
    expect(confirm(unavailable, true, '2026-12-01T06:55:00Z')).toEqual({
      kind: 'error',
      reason: 'NOT_ELIGIBLE',
    });
  });

  it('refusesAStaleVersionWithAReloadOutcome', async () => {
    // The real transport maps HTTP 409 and 412 to the typed 'conflict' kind in
    // transport.ts; that status mapping is asserted by the HTTP-level test.
    // Here the typed outcome is injected for each status (the status only picks
    // a distinct synthetic correlation id) and the client must return only a
    // reload signal, never clinical text or a claimed confirmation.
    for (const status of [409, 412]) {
      const { transport } = transportFor(async () => {
        throw new TransportError('conflict', { correlationId: `corr-stale-${status}` });
      });
      expect(await confirmAppointment(transport, command)).toEqual({ kind: 'stale' });
    }
  });

  it('refusesConfirmationWithoutAuthentication', async () => {
    const pending = deriveConfirmation(booked, true);
    expect(confirm(pending, false, '2026-12-01T06:55:00Z')).toEqual({
      kind: 'error',
      reason: 'UNAUTHENTICATED',
    });
    const { transport } = transportFor(async () => {
      throw new TransportError('unauthenticated', { correlationId: 'corr-expired' });
    });
    expect(await confirmAppointment(transport, command)).toEqual({ kind: 'unauthenticated' });
  });

  it('keepsTheFirstRecordedInstantWhenAnAlreadyConfirmedRowIsReplayed', () => {
    // A retry with a reloaded row must not move the server's original instant.
    const first = confirm(deriveConfirmation(booked, true), true, '2026-12-01T06:55:00Z');
    if (first.kind !== 'confirmed') {
      throw new Error('expected the first confirmation to be recorded');
    }
    const replay = deriveConfirmation({ ...booked, confirmedAt: first.confirmedAt }, true);
    expect(replay).toEqual({ kind: 'confirmed', confirmedAt: first.confirmedAt });
    expect(confirm(replay, true, '2026-12-01T09:00:00Z')).toEqual({
      kind: 'idempotent',
      confirmedAt: '2026-12-01T06:55:00Z',
    });
  });
});
