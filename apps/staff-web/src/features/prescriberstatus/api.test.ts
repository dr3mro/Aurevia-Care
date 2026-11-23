import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import {
  answerClarification,
  approveSubstitution,
  asPrescriberTransportError,
  declineSubstitution,
  getClarificationThread,
  getSubstitutionProposal,
} from './api';
import {
  prescriberOrganizationId,
  syntheticApprovedProposalView,
  syntheticAnsweredThreadView,
  syntheticPendingProposalView,
} from './fixtures';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

function descriptorAt(call: number): RequestDescriptor {
  return required(sendMock.mock.calls[call])[0];
}

beforeEach(() => {
  sendMock.mockReset();
});

describe('prescriber read commands', () => {
  it('reads a clarification thread with the active tenant', async () => {
    sendMock.mockResolvedValueOnce(syntheticAnsweredThreadView);

    expect(await getClarificationThread('thread-synthetic-001', prescriberOrganizationId)).toBe(
      syntheticAnsweredThreadView,
    );
    expect(descriptorAt(0).method).toBe('GET');
    expect(descriptorAt(0).url).toBe('/api/v1/pharmacy/clarifications/thread-synthetic-001');
    expect(descriptorAt(0).headers['X-Active-Organization']).toBe(prescriberOrganizationId);
  });

  it('reads a substitution proposal with the active tenant', async () => {
    sendMock.mockResolvedValueOnce(syntheticPendingProposalView);

    expect(await getSubstitutionProposal('proposal-synthetic-001', prescriberOrganizationId)).toBe(
      syntheticPendingProposalView,
    );
    expect(descriptorAt(0).method).toBe('GET');
    expect(descriptorAt(0).url).toBe('/api/v1/pharmacy/substitutions/proposal-synthetic-001');
  });
});

describe('prescriber write commands', () => {
  it('answers a clarification with the version, both body fields and a fresh retry key', async () => {
    sendMock.mockResolvedValueOnce(syntheticAnsweredThreadView);

    await answerClarification(
      'thread-synthetic-001',
      1,
      { answerBody: 'Dispense the 500 mg tablet.', amendedRevisionId: 'revision-synthetic-002' },
      prescriberOrganizationId,
    );

    expect(descriptorAt(0).method).toBe('POST');
    expect(descriptorAt(0).url).toBe('/api/v1/pharmacy/clarifications/thread-synthetic-001/answer');
    expect(descriptorAt(0).body).toEqual({
      answerBody: 'Dispense the 500 mg tablet.',
      amendedRevisionId: 'revision-synthetic-002',
    });
    expect(descriptorAt(0).headers['If-Match']).toBe('1');
    expect(descriptorAt(0).headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('approves a substitution with an attestation and the proposal version', async () => {
    sendMock.mockResolvedValueOnce(syntheticApprovedProposalView);

    await approveSubstitution(
      'proposal-synthetic-001',
      1,
      { attestation: 'I reviewed the alternative for this patient.', note: 'synthetic note' },
      prescriberOrganizationId,
    );

    expect(descriptorAt(0).url).toBe(
      '/api/v1/pharmacy/substitutions/proposal-synthetic-001/approve',
    );
    expect(descriptorAt(0).body).toEqual({
      attestation: 'I reviewed the alternative for this patient.',
      note: 'synthetic note',
    });
    expect(descriptorAt(0).headers['If-Match']).toBe('1');
    expect(descriptorAt(0).headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('declines a substitution without creating an attested revision', async () => {
    sendMock.mockResolvedValueOnce(syntheticPendingProposalView);

    await declineSubstitution(
      'proposal-synthetic-001',
      1,
      'synthetic decline note',
      prescriberOrganizationId,
    );

    expect(descriptorAt(0).url).toBe(
      '/api/v1/pharmacy/substitutions/proposal-synthetic-001/decline',
    );
    expect(descriptorAt(0).body).toEqual({ attestation: '', note: 'synthetic decline note' });
    expect(descriptorAt(0).headers['If-Match']).toBe('1');
    expect(descriptorAt(0).headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
  });
});

describe('asPrescriberTransportError', () => {
  it('returns an existing transport error unchanged', () => {
    const error = new TransportError('conflict', {
      correlationId: 'corr-synthetic',
      code: undefined,
      retryAfterSeconds: undefined,
    });

    expect(asPrescriberTransportError(error)).toBe(error);
  });

  it('normalizes an unexpected rejection without carrying response text', () => {
    const normalized = asPrescriberTransportError('secret response body');

    expect(normalized).toBeInstanceOf(TransportError);
    expect(normalized.kind).toBe('server');
    expect(normalized.correlationId).toBeUndefined();
    expect(normalized.code).toBeUndefined();
    expect(normalized.retryAfterSeconds).toBeUndefined();
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
