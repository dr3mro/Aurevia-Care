import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import {
  amendNote,
  contentToSoap,
  createNote,
  getChartEntry,
  getNote,
  getPatient,
  listChartEntries,
  markEnteredInError,
  parseRecipients,
  signAddendum,
  signNote,
  soapToContent,
  toChart,
  toCodedEntry,
  toNoteRevision,
  toNoteState,
  toVerification,
  updateNote,
  toVital,
} from './api';
import type {
  ChartEntryResponse,
  ChartViewResponse,
  ClinicalNoteRevisionResponse,
  NoteViewResponse,
} from './api';
import { must } from '../../../tests/must';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

function descriptorAt(call: number): RequestDescriptor {
  return must(sendMock.mock.calls[call])[0];
}

function chartEntry(overrides: Partial<ChartEntryResponse> = {}): ChartEntryResponse {
  return {
    id: 'entry-1',
    patientId: 'patient-1',
    encounterId: 'encounter-1',
    type: 'PROBLEM',
    originalText: 'original problem',
    originalCode: 'RAW-1',
    codeSystem: 'local',
    mappedCode: 'MAPPED-1',
    mappedText: 'mapped problem',
    catalogVersion: 2,
    source: 'CLINICIAN_OBSERVED',
    presence: 'PRESENT',
    unknownReason: null,
    verification: 'VERIFIED',
    verifiedBy: 'clinician-1',
    verifiedAt: '2026-03-09T10:00:00Z',
    effectiveFrom: '2026-03-09T00:00:00Z',
    effectiveTo: null,
    measuredAt: null,
    value: null,
    unit: null,
    clinicalAuthor: 'clinician-1',
    dataEntryActor: 'reception-1',
    version: 1,
    recordedAt: '2026-03-09T09:00:00Z',
    ...overrides,
  };
}

function noteRevision(
  overrides: Partial<ClinicalNoteRevisionResponse> = {},
): ClinicalNoteRevisionResponse {
  return {
    id: 'note-1',
    version: 1,
    state: 'draft',
    patientId: 'patient-1',
    encounterId: 'encounter-1',
    title: 'Follow-up note',
    content: 'Chief concern text',
    language: 'en',
    templateCode: 'soap',
    templateVersion: 3,
    clinicalAuthorSubject: 'clinician-1',
    dataEntrySubject: 'clinician-1',
    effectiveAt: '2026-03-09T00:00:00Z',
    signedAt: null,
    signerSubject: null,
    attestation: null,
    contentChecksum: null,
    supersedesVersion: null,
    enteredErrorReason: null,
    enteredErrorActor: null,
    affectedRecipients: null,
    createdAt: '2026-03-09T09:00:00Z',
    ...overrides,
  };
}

beforeEach(() => {
  sendMock.mockReset();
});

describe('chart API requests', () => {
  it('reads a patient with the active tenant header', async () => {
    sendMock.mockResolvedValueOnce({
      patient: { id: 'patient-1', displayName: 'Layla Haddad' },
      duplicates: [],
    });
    const view = await getPatient('patient/1', 'org-a');
    expect(view.patient.displayName).toBe('Layla Haddad');
    const request = descriptorAt(0);
    expect(request.method).toBe('GET');
    expect(request.url).toBe('/api/v1/patients/patient%2F1');
    expect(request.headers['X-Active-Organization']).toBe('org-a');
  });

  it('reads chart entries without a cursor by default', async () => {
    sendMock.mockResolvedValueOnce({ entries: [] });
    await listChartEntries('patient-1', 'org-a');
    expect(descriptorAt(0).url).toBe('/api/v1/chart/records/patients/patient-1/entries');
  });

  it('appends a cursor when paging is requested', async () => {
    sendMock.mockResolvedValueOnce({ entries: [], nextCursor: null });
    await listChartEntries('patient-1', 'org-a', 'cursor-9');
    expect(descriptorAt(0).url).toBe(
      '/api/v1/chart/records/patients/patient-1/entries?cursor=cursor-9',
    );
  });

  it('reads one chart entry', async () => {
    sendMock.mockResolvedValueOnce(chartEntry());
    await getChartEntry('entry-1', 'org-a');
    expect(descriptorAt(0).url).toBe('/api/v1/chart/records/entries/entry-1');
  });

  it('creates a note with a fresh idempotency key and the full clinical context', async () => {
    sendMock.mockResolvedValueOnce(noteRevision());
    await createNote(
      {
        patientId: 'patient-1',
        encounterId: null,
        title: 'Intake',
        content: 'text',
        language: 'en',
        templateCode: 'soap',
        templateVersion: 3,
        clinicalAuthorSubject: 'clinician-1',
        effectiveAt: null,
      },
      'org-a',
    );
    const request = descriptorAt(0);
    expect(request.method).toBe('POST');
    expect(request.url).toBe('/api/v1/clinical-notes');
    expect(request.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(request.body).toMatchObject({ title: 'Intake', content: 'text' });
    expect(request.headers['Content-Type']).toBe('application/json');
  });

  it('reads a note view', async () => {
    sendMock.mockResolvedValueOnce({ current: noteRevision(), history: [] });
    await getNote('note-1', 'org-a');
    expect(descriptorAt(0).url).toBe('/api/v1/clinical-notes/note-1');
    expect(descriptorAt(0).method).toBe('GET');
  });

  it('saves a draft with a version precondition', async () => {
    sendMock.mockResolvedValueOnce(noteRevision({ version: 2 }));
    await updateNote('note-1', 1, { title: 'Follow-up', content: 'body' }, 'org-a');
    const request = descriptorAt(0);
    expect(request.method).toBe('PUT');
    expect(request.url).toBe('/api/v1/clinical-notes/note-1');
    expect(request.headers['If-Match']).toBe('1');
    expect(request.body).toEqual({ title: 'Follow-up', content: 'body' });
  });

  it('signs a draft with the attestation and version precondition', async () => {
    sendMock.mockResolvedValueOnce(noteRevision({ state: 'signed' }));
    await signNote('note-1', 1, 'I attest', 'org-a');
    const request = descriptorAt(0);
    expect(request.method).toBe('POST');
    expect(request.url).toBe('/api/v1/clinical-notes/note-1/sign');
    expect(request.headers['If-Match']).toBe('1');
    expect(request.body).toEqual({ attestation: 'I attest' });
  });

  it('signs an addendum with its own command path', async () => {
    sendMock.mockResolvedValueOnce(noteRevision({ state: 'addendum' }));
    await signAddendum('note-1', 2, 'I attest', 'org-a');
    expect(descriptorAt(0).url).toBe('/api/v1/clinical-notes/note-1/sign-addendum');
    expect(descriptorAt(0).headers['If-Match']).toBe('2');
  });

  it('opens an amendment draft without a body', async () => {
    sendMock.mockResolvedValueOnce(noteRevision({ state: 'amendment_draft' }));
    await amendNote('note-1', 1, 'org-a');
    const request = descriptorAt(0);
    expect(request.url).toBe('/api/v1/clinical-notes/note-1/amend');
    expect(request.body).toBeUndefined();
    expect(request.headers['If-Match']).toBe('1');
  });

  it('records entered-in-error with a reason and JSON recipients', async () => {
    sendMock.mockResolvedValueOnce(noteRevision({ state: 'entered_in_error' }));
    await markEnteredInError(
      'note-1',
      1,
      { reason: 'wrong patient', affectedRecipients: ['referrer'] },
      'org-a',
    );
    const request = descriptorAt(0);
    expect(request.url).toBe('/api/v1/clinical-notes/note-1/entered-in-error');
    expect(request.headers['If-Match']).toBe('1');
    expect(request.body).toEqual({ reason: 'wrong patient', affectedRecipients: '["referrer"]' });
  });

  it('propagates a typed transport error to the caller', async () => {
    sendMock.mockRejectedValueOnce(
      new TransportError('conflict', {
        correlationId: 'corr-1',
        code: undefined,
        retryAfterSeconds: undefined,
      }),
    );
    await expect(getNote('note-1', 'org-a')).rejects.toBeInstanceOf(TransportError);
  });
});

describe('chart domain mapping', () => {
  it('maps each chart family to its domain collection', () => {
    const view: ChartViewResponse = {
      entries: [
        chartEntry({ id: 'p', type: 'PROBLEM' }),
        chartEntry({ id: 'a', type: 'ALLERGY' }),
        chartEntry({ id: 'm', type: 'MEDICATION' }),
        chartEntry({ id: 'v', type: 'VITAL' }),
      ],
    };
    const chart = toChart(view, 'org-a', 'patient-1', { latinName: 'Layla Haddad' });
    expect(chart.problems.map((entry) => entry.id)).toEqual(['p']);
    expect(chart.allergies.map((entry) => entry.id)).toEqual(['a']);
    expect(chart.medications.map((entry) => entry.id)).toEqual(['m']);
    expect(chart.vitals.map((entry) => entry.id)).toEqual(['v']);
    expect(chart.revisions).toEqual([]);
    expect(chart.documents).toEqual([]);
    expect(chart.localId).toBe('patient-1');
    expect(chart.patientName.latinName).toBe('Layla Haddad');
  });

  it('maps presence and verification into explicit verification states', () => {
    expect(toVerification(chartEntry({ presence: 'UNKNOWN' }))).toBe('unknown');
    expect(toVerification(chartEntry({ presence: 'ABSENT' }))).toBe('none');
    expect(toVerification(chartEntry({ presence: 'PRESENT', verification: 'VERIFIED' }))).toBe(
      'verified',
    );
    expect(toVerification(chartEntry({ presence: 'PRESENT', verification: 'UNVERIFIED' }))).toBe(
      'unverified',
    );
  });

  it('prefers mapped code and text, falling back to originals and then empty', () => {
    expect(toCodedEntry(chartEntry(), 'org-a')).toMatchObject({
      code: 'MAPPED-1',
      display: 'mapped problem',
      effectiveOn: '2026-03-09T00:00:00Z',
      clinicalAuthor: 'clinician-1',
      verification: 'verified',
    });
    expect(
      toCodedEntry(
        chartEntry({
          mappedCode: null,
          mappedText: null,
          effectiveFrom: null,
          clinicalAuthor: null,
        }),
        'org-a',
      ),
    ).toMatchObject({
      code: 'RAW-1',
      display: 'original problem',
      effectiveOn: '2026-03-09T09:00:00Z',
      clinicalAuthor: '',
    });
    expect(toCodedEntry(chartEntry({ mappedCode: null, originalCode: null }), 'org-a').code).toBe(
      '',
    );
  });

  it('maps a vital with fallbacks for missing value, unit and measurement time', () => {
    expect(
      toVital(
        chartEntry({ value: '37.5', unit: '°C', measuredAt: '2026-03-09T08:00:00Z' }),
        'org-a',
      ),
    ).toMatchObject({
      value: 37.5,
      unit: '°C',
      measuredAt: '2026-03-09T08:00:00Z',
      timeZone: 'UTC',
    });
    expect(
      toVital(
        chartEntry({
          value: null,
          unit: null,
          measuredAt: null,
          effectiveFrom: '2026-03-08T00:00:00Z',
        }),
        'org-a',
      ),
    ).toMatchObject({
      value: 0,
      unit: '',
      measuredAt: '2026-03-08T00:00:00Z',
    });
    expect(
      toVital(
        chartEntry({ value: null, unit: null, measuredAt: null, effectiveFrom: null }),
        'org-a',
      ).measuredAt,
    ).toBe('2026-03-09T09:00:00Z');
  });
});

describe('note domain mapping', () => {
  it('maps all five wire states onto the domain state', () => {
    expect(toNoteState('draft')).toBe('draft');
    expect(toNoteState('amendment_draft')).toBe('draft');
    expect(toNoteState('signed')).toBe('signed');
    expect(toNoteState('addendum')).toBe('signed');
    expect(toNoteState('entered_in_error')).toBe('signed');
  });

  it('maps a draft without optional provenance', () => {
    const revision = toNoteRevision(noteRevision({ effectiveAt: null }), 'org-a');
    expect(revision).toMatchObject({
      organizationId: 'org-a',
      id: 'note-1',
      version: 1,
      kind: 'original',
      state: 'draft',
      content: { subjective: 'Chief concern text', objective: '', assessment: '', plan: '' },
      templateVersion: '3',
      patientLocalId: 'patient-1',
      encounterLocalId: 'encounter-1',
    });
    expect(revision.signer).toBeUndefined();
    expect(revision.signedAt).toBeUndefined();
    expect(revision.effectiveOn).toBeUndefined();
    expect(revision.checksum).toBeUndefined();
    expect(revision.amends).toBeUndefined();
    expect(revision.enteredInError).toBeUndefined();
  });

  it('maps a signed amendment with provenance and an amends link', () => {
    const revision = toNoteRevision(
      noteRevision({
        state: 'addendum',
        version: 2,
        supersedesVersion: 1,
        signerSubject: 'clinician-2',
        signedAt: '2026-03-10T10:00:00Z',
        effectiveAt: '2026-03-10T00:00:00Z',
        contentChecksum: 'sha:abc',
        encounterId: null,
      }),
      'org-a',
    );
    expect(revision).toMatchObject({
      kind: 'amendment',
      state: 'signed',
      signer: 'clinician-2',
      signedAt: '2026-03-10T10:00:00Z',
      effectiveOn: '2026-03-10T00:00:00Z',
      checksum: 'sha:abc',
      amends: '1',
      encounterLocalId: '',
    });
  });

  it('maps entered-in-error evidence and parses recipients', () => {
    const revision = toNoteRevision(
      noteRevision({
        state: 'entered_in_error',
        enteredErrorReason: 'wrong patient',
        enteredErrorActor: 'auditor-1',
        affectedRecipients: '["referrer","lab"]',
      }),
      'org-a',
    );
    expect(revision.enteredInError).toEqual({
      actor: 'auditor-1',
      reason: 'wrong patient',
      affectedRecipients: ['referrer', 'lab'],
      recordedAt: '2026-03-09T09:00:00Z',
    });
  });

  it('uses an empty actor when entered-in-error has no actor', () => {
    const revision = toNoteRevision(
      noteRevision({
        enteredErrorReason: 'typo',
        enteredErrorActor: null,
      }),
      'org-a',
    );
    expect(revision.enteredInError?.actor).toBe('');
  });

  it('parses recipients defensively for absent, malformed and non-array JSON', () => {
    expect(parseRecipients(null)).toEqual([]);
    expect(parseRecipients('{not json')).toEqual([]);
    expect(parseRecipients('{"a":1}')).toEqual([]);
    expect(parseRecipients('["lab", 7, "referrer"]')).toEqual(['lab', 'referrer']);
  });

  it('round-trips SOAP content to and from the API string', () => {
    const soap = contentToSoap('first field');
    expect(soap).toEqual({ subjective: 'first field', objective: '', assessment: '', plan: '' });
    expect(soapToContent(soap)).toBe('first field');
    expect(soapToContent({ subjective: 'a', objective: '  ', assessment: 'c', plan: 'd' })).toBe(
      'a\n\nc\n\nd',
    );
  });

  it('maps a whole note view with its history', () => {
    const view: NoteViewResponse = {
      current: noteRevision({ id: 'note-1', version: 2, state: 'signed' }),
      history: [noteRevision({ id: 'note-1', version: 1, state: 'draft' })],
    };
    expect(toNoteRevision(view.current, 'org-a').state).toBe('signed');
    expect(toNoteRevision(must(view.history[0]), 'org-a').state).toBe('draft');
  });
});
