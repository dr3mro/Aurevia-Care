import { describe, expect, it, vi } from 'vitest';
import type { RequestDescriptor } from '../../api/client';
import { send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import { linkStudy, listWorklist, reportOrder, scheduleOrder, toOrder } from './api';
import type { WorklistEntryResponse } from './api';
import { alphaOrganizationId } from './fixtures';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

/** Build one synthetic API row with overridable wire fields. */
function entry(overrides: Partial<WorklistEntryResponse> = {}): WorklistEntryResponse {
  return {
    orderId: 'rad-001',
    performingSiteId: 'site-rad-main',
    patientId: 'synthetic-patient-001',
    orderCode: 'ORD-001',
    urgency: 'ROUTINE',
    status: 'RECEIVED',
    version: 1,
    scheduledAt: null,
    modality: null,
    accession: null,
    accessionIssuer: null,
    studyInstanceUid: null,
    ...overrides,
  };
}

/** Read the descriptor handed to the transport at a given call index. */
function descriptorAt(call: number): RequestDescriptor {
  return required(sendMock.mock.calls[call])[0];
}

describe('radworklist API requests', () => {
  it('lists one performing site in the active tenant without a status filter', async () => {
    sendMock.mockResolvedValueOnce([]);
    await listWorklist('site-rad-main', 'all', alphaOrganizationId);

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('GET');
    expect(descriptor.url).toContain('site=site-rad-main');
    expect(descriptor.url).not.toContain('status=');
    expect(descriptor.headers['X-Active-Organization']).toBe(alphaOrganizationId);
  });

  it('maps a selected status onto the wire enum name', async () => {
    sendMock.mockResolvedValueOnce([]);
    await listWorklist('site-rad-main', 'scheduled', alphaOrganizationId);
    expect(descriptorAt(0).url).toContain('status=SCHEDULED');
  });

  it('sends a versioned, idempotent schedule command', async () => {
    sendMock.mockResolvedValueOnce({});
    await scheduleOrder(
      'rad-001',
      {
        expectedVersion: 2,
        performingSiteId: 'site-rad-main',
        scheduledAt: '2026-03-09T09:00:00Z',
        modality: 'CT',
        accession: 'ACC-001',
        accessionIssuer: 'SYN-ORG',
      },
      alphaOrganizationId,
    );

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('POST');
    expect(descriptor.url).toBe('/api/v1/radiology/worklist/orders/rad-001/schedule');
    expect(descriptor.headers['If-Match']).toBe('2');
    expect(descriptor.headers['Idempotency-Key']).toBeTruthy();
    expect(descriptor.headers['X-Active-Organization']).toBe(alphaOrganizationId);
    expect(descriptor.body).toMatchObject({ modality: 'CT', accession: 'ACC-001' });
  });

  it('sends a versioned, idempotent study link command', async () => {
    sendMock.mockResolvedValueOnce({});
    await linkStudy(
      'rad-002',
      {
        expectedVersion: 3,
        performingSiteId: 'site-rad-main',
        studyInstanceUid: '1.2.826',
        patientId: 'synthetic-patient-002',
        accession: 'ACC-002',
        workstationRef: 'workstation-1',
      },
      alphaOrganizationId,
    );

    const descriptor = descriptorAt(0);
    expect(descriptor.url).toBe('/api/v1/radiology/worklist/orders/rad-002/studies');
    expect(descriptor.headers['If-Match']).toBe('3');
    expect(descriptor.body).toMatchObject({
      studyInstanceUid: '1.2.826',
      patientId: 'synthetic-patient-002',
    });
  });

  it('sends a versioned handoff command', async () => {
    sendMock.mockResolvedValueOnce({});
    await reportOrder(
      'rad-003',
      { expectedVersion: 4, performingSiteId: 'site-rad-main' },
      alphaOrganizationId,
    );

    const descriptor = descriptorAt(0);
    expect(descriptor.url).toBe('/api/v1/radiology/worklist/orders/rad-003/report');
    expect(descriptor.method).toBe('POST');
    expect(descriptor.headers['If-Match']).toBe('4');
    expect(descriptor.headers['Idempotency-Key']).toBeTruthy();
  });
});

describe('radworklist wire projection', () => {
  it('lowercases enum names and keeps the tenant scope', () => {
    const projected = toOrder(
      entry({
        urgency: 'STAT',
        status: 'PERFORMED',
        modality: 'MRI',
        accession: 'ACC-002',
        accessionIssuer: 'SYN-ORG',
        scheduledAt: '2026-03-09T09:00:00Z',
        studyInstanceUid: '1.2.826',
      }),
      alphaOrganizationId,
    );
    expect(projected).toMatchObject({
      organizationId: alphaOrganizationId,
      urgency: 'stat',
      status: 'performed',
      modality: 'mri',
      accession: 'ACC-002',
      scheduledAt: '2026-03-09T09:00:00Z',
      studyInstanceUid: '1.2.826',
    });
  });

  it('keeps an unscheduled row without a modality', () => {
    const projected = toOrder(entry(), alphaOrganizationId);
    expect(projected.modality).toBeNull();
    expect(projected.scheduledAt).toBeNull();
    expect(projected.status).toBe('received');
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
