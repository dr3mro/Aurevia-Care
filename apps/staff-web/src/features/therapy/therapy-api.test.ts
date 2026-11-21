import { describe, expect, it, vi } from 'vitest';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import {
  acceptReferral,
  addTherapyAddendum,
  getEpisode,
  getEpisodeForReferral,
  listReferrals,
  signTherapyDischarge,
  signTherapyNote,
  toEpisode,
  toReferral,
} from './api';
import type { EpisodeResponse, ReferralResponse } from './api';
import { alphaOrganizationId } from './fixtures';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

function descriptorAt(call: number): RequestDescriptor {
  return sendMock.mock.calls[call]![0] as RequestDescriptor;
}

function referralResponse(overrides: Partial<ReferralResponse> = {}): ReferralResponse {
  return {
    id: 'ref-001',
    version: 2,
    patientId: 'patient-001',
    referrer: 'Synthetic Referrer A',
    reason: 'Synthetic knee rehabilitation',
    receivedAt: '2026-03-02T08:00:00Z',
    state: 'accepted',
    ...overrides,
  };
}

function episodeResponse(overrides: Partial<EpisodeResponse> = {}): EpisodeResponse {
  return {
    id: 'episode-001',
    referralId: 'ref-001',
    patientId: 'patient-001',
    timeZone: 'America/New_York',
    state: 'active',
    evaluation: 'Synthetic evaluation',
    problems: ['Synthetic problem'],
    baseline: [
      { id: 'baseline-1', name: 'Pain', value: 7, unit: '/10', measuredOn: '2026-03-02' },
    ],
    goals: [{ id: 'goal-1', description: 'Synthetic goal', targetOn: '2026-04-15', state: 'active' }],
    plannedFrequency: 'Twice weekly',
    plannedDuration: 'Six weeks',
    interventions: ['Synthetic exercise'],
    outcomeScales: [
      {
        id: 'scale-1',
        name: 'Synthetic scale',
        code: 'NPRS',
        scaleVersion: 2,
        unit: '/10',
        measurements: [{ id: 'm-1', recordedAt: '2026-03-05T09:00:00Z', value: 7, unit: '/10' }],
      },
    ],
    sessionNotes: [
      {
        id: 'note-signed',
        episodeId: 'episode-001',
        patientLocalId: 'patient-001',
        author: 'Synthetic Therapist',
        authoredAt: '2026-03-05T10:00:00Z',
        body: 'Synthetic note',
        state: 'signed',
        version: 2,
        signedBy: 'Synthetic Therapist',
        signedAt: '2026-03-05T10:30:00Z',
        addendumOf: undefined,
      },
      {
        id: 'note-draft',
        episodeId: 'episode-001',
        patientLocalId: 'patient-001',
        author: 'Synthetic Therapist',
        authoredAt: '2026-03-12T10:00:00Z',
        body: 'Synthetic draft',
        state: 'draft',
        version: 1,
        signedBy: undefined,
        signedAt: undefined,
        addendumOf: undefined,
      },
    ],
    progressNotes: [],
    dischargeSummary: null,
    version: 4,
    ...overrides,
  };
}

describe('therapy API client', () => {
  it('lists referrals on the tenant path with no query when no filters are supplied', async () => {
    const page = { items: [referralResponse()], nextCursor: null };
    sendMock.mockResolvedValueOnce(page);

    await expect(listReferrals(alphaOrganizationId)).resolves.toEqual(page);

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('GET');
    expect(descriptor.url).toBe('/api/v1/appointments');
    expect(descriptor.headers['X-Active-Organization']).toBe(alphaOrganizationId);
    expect(descriptor.headers['If-Match']).toBeUndefined();
  });

  it('includes an optional cursor and state filter when supplied', async () => {
    sendMock.mockResolvedValueOnce({ items: [], nextCursor: null });

    await listReferrals(alphaOrganizationId, 'cursor-2', 'pending');

    const url = descriptorAt(0).url;
    expect(url).toContain('cursor=cursor-2');
    expect(url).toContain('state=pending');
  });

  it('propagates a typed transport failure unchanged', async () => {
    const failure = new TransportError('conflict', {
      correlationId: 'corr-1',
      code: 'STALE',
      retryAfterSeconds: undefined,
    });
    sendMock.mockRejectedValueOnce(failure);
    await expect(listReferrals(alphaOrganizationId)).rejects.toBe(failure);
  });

  it('reads an episode by id and by referral id', async () => {
    sendMock.mockResolvedValueOnce(episodeResponse());
    sendMock.mockResolvedValueOnce(episodeResponse());

    await getEpisode('episode-001', alphaOrganizationId);
    await getEpisodeForReferral('ref-001', alphaOrganizationId);

    expect(descriptorAt(0).url).toBe('/api/v1/therapy/episodes/episode-001');
    expect(descriptorAt(1).url).toBe('/api/v1/therapy/referrals/ref-001/episode');
    expect(descriptorAt(1).headers['X-Active-Organization']).toBe(alphaOrganizationId);
  });

  it('accepts a referral with a version precondition and idempotency key', async () => {
    sendMock.mockResolvedValueOnce(referralResponse({ state: 'accepted' }));

    await acceptReferral('ref-001', 2, alphaOrganizationId);

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('POST');
    expect(descriptor.url).toBe('/api/v1/therapy/referrals/ref-001/accept');
    expect(descriptor.headers['If-Match']).toBe('2');
    expect(descriptor.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('signs a note with its body, attestation and version precondition', async () => {
    sendMock.mockResolvedValueOnce(episodeResponse().sessionNotes[0]);

    await signTherapyNote(
      'episode-001',
      'note-draft',
      1,
      'Synthetic body',
      '2026-03-15T12:00:00Z',
      true,
      alphaOrganizationId,
    );

    const descriptor = descriptorAt(0);
    expect(descriptor.url).toBe('/api/v1/therapy/episodes/episode-001/notes/note-draft/sign');
    expect(descriptor.headers['If-Match']).toBe('1');
    expect(descriptor.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(descriptor.body).toEqual({
      body: 'Synthetic body',
      signedAt: '2026-03-15T12:00:00Z',
      attested: true,
    });
  });

  it('records an addendum with a fresh idempotency key', async () => {
    sendMock.mockResolvedValueOnce(episodeResponse().sessionNotes[1]);

    await addTherapyAddendum('episode-001', 'note-signed', 'Synthetic addendum', alphaOrganizationId);

    const descriptor = descriptorAt(0);
    expect(descriptor.url).toBe('/api/v1/therapy/episodes/episode-001/notes/note-signed/addendum');
    expect(descriptor.body).toEqual({ body: 'Synthetic addendum' });
    expect(descriptor.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('signs a discharge with the referrer notification instant', async () => {
    sendMock.mockResolvedValueOnce(episodeResponse());

    await signTherapyDischarge(
      'episode-001',
      4,
      'Synthetic summary',
      'Synthetic follow-up',
      '2026-03-16T12:30:00Z',
      alphaOrganizationId,
    );

    const descriptor = descriptorAt(0);
    expect(descriptor.url).toBe('/api/v1/therapy/episodes/episode-001/discharge');
    expect(descriptor.headers['If-Match']).toBe('4');
    expect(descriptor.body).toEqual({
      body: 'Synthetic summary',
      followUp: 'Synthetic follow-up',
      sentToReferrerAt: '2026-03-16T12:30:00Z',
    });
  });
});

describe('therapy wire mapping', () => {
  it('maps a referral to the tenant-scoped display type', () => {
    expect(toReferral(referralResponse(), alphaOrganizationId)).toEqual({
      organizationId: alphaOrganizationId,
      id: 'ref-001',
      patientName: { latinName: 'patient-001' },
      patientLocalId: 'patient-001',
      referrer: 'Synthetic Referrer A',
      reason: 'Synthetic knee rehabilitation',
      receivedAt: '2026-03-02T08:00:00Z',
      state: 'accepted',
      version: 2,
    });
  });

  it('maps an open episode, preserving note provenance and an absent summary', () => {
    const mapped = toEpisode(episodeResponse(), alphaOrganizationId);
    expect(mapped.organizationId).toBe(alphaOrganizationId);
    expect(mapped.id).toBe('episode-001');
    expect(mapped.goals[0]?.description).toBe('Synthetic goal');
    expect(mapped.outcomeScales[0]?.measurements[0]?.unit).toBe('/10');
    expect(mapped.sessionNotes[0]?.signedBy).toBe('Synthetic Therapist');
    expect(mapped.sessionNotes[1]?.signedBy).toBeUndefined();
    expect(mapped.dischargeSummary).toBeUndefined();
  });

  it('maps a signed discharge summary when one exists', () => {
    const response = episodeResponse({
      state: 'discharged',
      dischargeSummary: {
        id: 'discharge-001',
        body: 'Synthetic summary',
        followUp: 'Synthetic follow-up',
        signedBy: 'Synthetic Therapist',
        signedAt: '2026-03-16T12:00:00Z',
        sentToReferrerAt: '2026-03-16T12:30:00Z',
        returnAcknowledgedAt: undefined,
      },
    });
    const mapped = toEpisode(response, alphaOrganizationId);
    expect(mapped.state).toBe('discharged');
    expect(mapped.dischargeSummary?.id).toBe('discharge-001');
    expect(mapped.dischargeSummary?.returnAcknowledgedAt).toBeUndefined();
  });
});
