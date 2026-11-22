/**
 * READY-48 acceptance tests for the transport-backed screen providers (PAT-01,
 * PAT-02, PAT-03, ARC-03). Payloads are synthetic projections from the three
 * patient endpoints; malformed responses must fail at this trust boundary.
 */
import { jest } from '@jest/globals';

import type { HttpResponse, Transport } from '../api/transport';
import {
  createPatientTransport,
  loadAppointments,
  loadConversations,
  loadReports,
} from './providers';

jest.mock('../api/tokenStore', () => ({
  loadToken: jest.fn(async () => 'synthetic-session-token'),
  saveToken: jest.fn(async () => undefined),
  clearToken: jest.fn(async () => undefined),
}));

function fakeTransport(get: () => Promise<unknown>): Transport {
  return {
    get: jest.fn(get) as Transport['get'],
    post: jest.fn(async () => undefined) as Transport['post'],
    put: jest.fn(async () => undefined) as Transport['put'],
  };
}

const organizationId = 'synthetic-organization';
const patientId = 'synthetic-patient';

describe('loadAppointments', () => {
  const payload = [{
    id: 'apt-1',
    version: 3,
    patientId,
    facilityCode: 'synthetic-facility',
    facilityTimeZone: 'Asia/Riyadh',
    startsAt: '2026-11-21T10:00:00Z',
    status: 'booked',
    confirmationEnabled: true,
    confirmedAt: null,
  }];

  it('maps a patient appointment to the existing display type', async () => {
    const rows = await loadAppointments(fakeTransport(async () => payload), patientId, organizationId);
    expect(rows).toHaveLength(1);
    expect(rows[0]!.key).toBe(`${organizationId}\u0000apt-1`);
    expect(rows[0]!.timeZone).toBe('Asia/Riyadh');
    expect(rows[0]!.status).toBe('booked');
    expect(rows[0]!.version).toBe(3);
    expect(rows[0]!.confirmation).toEqual({ kind: 'pending', confirmedAt: null });
  });

  it('accepts the server-serialized uppercase status name', async () => {
    const rows = await loadAppointments(
      fakeTransport(async () => [{ ...payload[0]!, status: 'BOOKED' }]),
      patientId,
      organizationId,
    );
    expect(rows[0]!.status).toBe('booked');
    expect(rows[0]!.confirmation).toEqual({ kind: 'pending', confirmedAt: null });
  });

  it('marks a server-confirmed appointment as confirmed', async () => {
    const confirmed = [{ ...payload[0]!, confirmedAt: '2026-11-21T10:05:00Z' }];
    const rows = await loadAppointments(fakeTransport(async () => confirmed), patientId, organizationId);
    expect(rows[0]!.confirmation).toEqual({
      kind: 'confirmed',
      confirmedAt: '2026-11-21T10:05:00Z',
    });
  });

  it('rejects a payload that is not a list', async () => {
    await expect(
      loadAppointments(fakeTransport(async () => ({ entries: [] })), patientId, organizationId),
    ).rejects.toThrow('MALFORMED_APPOINTMENTS');
  });

  it('rejects a row that is not an object', async () => {
    await expect(
      loadAppointments(fakeTransport(async () => ['not-a-row']), patientId, organizationId),
    ).rejects.toThrow('MALFORMED_APPOINTMENT');
  });

  it.each([
    ['missing id', { ...payload[0]!, id: undefined }],
    ['blank id', { ...payload[0]!, id: '   ' }],
    ['invalid version', { ...payload[0]!, version: 'three' }],
    ['missing patient id', { ...payload[0]!, patientId: undefined }],
    ['missing facility code', { ...payload[0]!, facilityCode: undefined }],
    ['missing facility zone', { ...payload[0]!, facilityTimeZone: undefined }],
    ['missing start instant', { ...payload[0]!, startsAt: undefined }],
    ['non-string status', { ...payload[0]!, status: 7 }],
    ['unknown status', { ...payload[0]!, status: 'unknown' }],
    ['invalid confirmation flag', { ...payload[0]!, confirmationEnabled: 'yes' }],
    ['numeric confirmed instant', { ...payload[0]!, confirmedAt: 42 }],
    ['blank confirmed instant', { ...payload[0]!, confirmedAt: '' }],
  ])('rejects %s', async (_name, row) => {
    await expect(
      loadAppointments(fakeTransport(async () => [row]), patientId, organizationId),
    ).rejects.toThrow('MALFORMED_APPOINTMENT');
  });

  it('falls back to the UTC instant for an unknown facility time zone', async () => {
    const rows = await loadAppointments(
      fakeTransport(async () => [{ ...payload[0]!, facilityTimeZone: 'Not/AZone' }]),
      patientId,
      organizationId,
    );
    expect(rows[0]!.localStart).toBe('2026-11-21T10:00:00Z');
  });
});

describe('loadReports', () => {
  const payload = [{
    reportId: 'report-1',
    revision: 2,
    issuingOrganization: 'Synthetic clinic',
    effectiveDate: '2026-11-20',
    language: 'en',
    correctionReason: 'corrected value',
    supersedesRevision: 1,
  }];

  it('maps a released revision to the existing report type', async () => {
    const reports = await loadReports(fakeTransport(async () => payload));
    expect(reports).toEqual(payload);
    expect(reports[0]!.correctionReason).toBe('corrected value');
    expect(reports[0]!.supersedesRevision).toBe(1);
  });

  it('maps a report without correction context to null fields', async () => {
    const plain = [{ ...payload[0]!, correctionReason: null, supersedesRevision: null }];
    const reports = await loadReports(fakeTransport(async () => plain));
    expect(reports[0]!.correctionReason).toBeNull();
    expect(reports[0]!.supersedesRevision).toBeNull();
  });

  it('rejects a non-list report payload', async () => {
    await expect(loadReports(fakeTransport(async () => ({ reports: [] })))).rejects.toThrow(
      'MALFORMED_REPORTS',
    );
  });

  it('rejects a malformed report projection', async () => {
    await expect(
      loadReports(fakeTransport(async () => [{ ...payload[0]!, language: 'de' }])),
    ).rejects.toThrow();
  });
});

describe('loadConversations', () => {
  const conversation = {
    id: 'conv-1',
    version: 2,
    patientId,
    patientSubject: 'synthetic-subject',
    unitKey: 'synthetic-lab',
    connectionId: 'conn-1',
    topic: 'follow up',
    state: 'resolved',
    assigneeSubject: null,
    patientReadSequence: 3,
    staffReadSequence: 4,
    createdAt: '2026-11-21T10:00:00Z',
  };

  it('maps an inbox entry to the existing conversation type and server unread count', async () => {
    const result = await loadConversations(
      fakeTransport(async () => ({ entries: [{ conversation, unreadCount: 2 }] })),
      organizationId,
    );
    expect(result.conversations).toHaveLength(1);
    expect(result.conversations[0]!.organizationId).toBe(organizationId);
    expect(result.conversations[0]!.unitName).toBe('synthetic-lab');
    expect(result.conversations[0]!.state).toBe('resolved');
    expect(result.unreadByConversation['conv-1']).toBe(2);
  });

  it('treats a closed thread as read-only', async () => {
    const result = await loadConversations(
      fakeTransport(async () => ({
        entries: [{ conversation: { ...conversation, state: 'closed' }, unreadCount: 0 }],
      })),
      organizationId,
    );
    expect(result.conversations[0]!.messagingPermitted).toBe(false);
  });

  it('rejects an inbox with an unknown lifecycle state', async () => {
    await expect(
      loadConversations(
        fakeTransport(async () => ({
          entries: [{ conversation: { ...conversation, state: 'paused' }, unreadCount: 0 }],
        })),
        organizationId,
      ),
    ).rejects.toThrow('MALFORMED_CONVERSATIONS');
  });

  it.each([
    ['non-object body', ['not-an-inbox']],
    ['missing entries', {}],
    ['non-array entries', { entries: 'none' }],
    ['non-object entry', { entries: ['bad'] }],
    ['missing conversation', { entries: [{}] }],
    ['non-object conversation', { entries: [{ conversation: 'bad' }] }],
  ])('rejects %s', async (_name, body) => {
    await expect(
      loadConversations(fakeTransport(async () => body), organizationId),
    ).rejects.toThrow('MALFORMED_CONVERSATIONS');
  });

  it.each([
    ['missing id', { ...conversation, id: undefined }],
    ['missing unit key', { ...conversation, unitKey: undefined }],
    ['non-string state', { ...conversation, state: 1 }],
    ['missing read sequence', { ...conversation, patientReadSequence: undefined }],
    ['fractional read sequence', { ...conversation, patientReadSequence: 1.5 }],
    ['negative read sequence', { ...conversation, patientReadSequence: -1 }],
    ['missing unread count', undefined],
  ])('rejects an entry with %s', async (_name, changed) => {
    const entry = changed === undefined
      ? { conversation }
      : { conversation: changed, unreadCount: 1 };
    await expect(
      loadConversations(
        fakeTransport(async () => ({ entries: [entry] })),
        organizationId,
      ),
    ).rejects.toThrow('MALFORMED_CONVERSATIONS');
  });

  it('rejects a negative server unread count', async () => {
    await expect(
      loadConversations(
        fakeTransport(async () => ({ entries: [{ conversation, unreadCount: -1 }] })),
        organizationId,
      ),
    ).rejects.toThrow('MALFORMED_CONVERSATIONS');
  });

  it('uses an empty topic when the server omits it', async () => {
    const result = await loadConversations(
      fakeTransport(async () => ({
        entries: [{ conversation: { ...conversation, topic: undefined }, unreadCount: 0 }],
      })),
      organizationId,
    );
    expect(result.conversations[0]!.topic).toBe('');
  });
});

describe('createPatientTransport', () => {
  it('uses OS-backed credentials and the configured language', async () => {
    const seen: RequestInit[] = [];
    const fetchImpl = async (_url: string, init: RequestInit): Promise<HttpResponse> => {
      seen.push(init);
      return { ok: true, status: 200, headers: { get: () => null }, json: async () => [] };
    };
    const transport = createPatientTransport('https://api.synthetic.example', 'ar', fetchImpl);
    await transport.get('/api/v1/patient/reports');
    expect((seen[0]!.headers as Record<string, string>)['Accept-Language']).toBe('ar');
  });

  it('fails closed on an insecure origin', () => {
    expect(() =>
      createPatientTransport('http://api.synthetic.example', 'en', jest.fn() as never),
    ).toThrow(/HTTPS/);
  });
});
