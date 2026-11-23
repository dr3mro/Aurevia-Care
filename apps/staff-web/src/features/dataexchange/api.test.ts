import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import {
  asDataExchangeError,
  downloadManifest,
  manifestFromDraft,
  runDryRun,
  signOffRequest,
  startExport,
  submitSignOff,
} from './api';
import type { DryRunDraft, ImportManifestRequest } from './api';
import { must } from '../../../tests/must';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);
const organizationId = '11111111-1111-1111-1111-111111111111';

/** Minimal draft used to build a synthetic manifest in the tests. */
const draft: DryRunDraft = {
  source: 'SYNTHETIC-CSV',
  sourceVersion: '1.0',
  manifestVersion: 7,
  unsupportedFieldNames: 'legacy_ssn, free_text_notes',
};

/** Return the descriptor passed to send at the given call index. */
function descriptorAt(index: number): RequestDescriptor {
  return must(sendMock.mock.calls[index])[0];
}

beforeEach(() => {
  sendMock.mockResolvedValue({});
});

describe('synthetic manifest building', () => {
  it('trims and keeps named unsupported fields as synthetic records', () => {
    const manifest = manifestFromDraft(draft);
    expect(manifest.schema_version).toBe('1.0');
    expect(manifest.requirement).toBe('OPS-08');
    expect(manifest.manifest_version).toBe(7);
    expect(manifest.synthetic_only).toBe(true);
    expect(manifest.records).toEqual([
      { source_id: 'synthetic-record-1', legacy_ssn: 'synthetic-value' },
      { source_id: 'synthetic-record-2', free_text_notes: 'synthetic-value' },
    ]);
    expect(manifest.mapping).toEqual({
      deterministic: true,
      strategy: 'canonical_sha256',
      source_to_target: {},
      identity_fields: ['source_id'],
      ignored_fields: [],
    });
    expect(manifest.provenance).toEqual({
      source_system: 'SYNTHETIC-CSV',
      source_version: '1.0',
      preserve_historical_signatures: true,
      creates_new_attestation: false,
    });
    expect(manifest.batches).toEqual([]);
  });

  it('drops blank field names so an empty entry cannot become a record', () => {
    expect(manifestFromDraft({ ...draft, unsupportedFieldNames: ' ,  , ' }).records).toEqual([]);
  });

  it('binds a sign-off to the reviewed manifest version with an affirmative decision', () => {
    const manifest: ImportManifestRequest = manifestFromDraft(draft);
    expect(
      signOffRequest(manifest, {
        approver: 'Synthetic Reviewer',
        role: 'privacy_auditor',
        contentSha256: 'sha256:digest',
        approvedAt: '2026-11-22T10:00:00Z',
      }),
    ).toEqual({
      manifest,
      progress: null,
      sign_off: {
        approver: 'Synthetic Reviewer',
        role: 'privacy_auditor',
        decision: 'approved',
        content_sha256: 'sha256:digest',
        manifest_version: 7,
        batches: [],
        approved_at: '2026-11-22T10:00:00Z',
      },
    });
  });
});

describe('data exchange API calls', () => {
  it('starts an export with the active organization and no body', async () => {
    const expected = {
      job_id: 'job-9',
      checksum: 'sha256:abc',
      record_count: 2,
      expires_at: '2026-11-22T10:00:00Z',
    };
    sendMock.mockResolvedValueOnce(expected);
    await expect(startExport(organizationId)).resolves.toEqual(expected);
    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('POST');
    expect(descriptor.url).toBe('/api/v1/tenant/exports');
    expect(descriptor.headers['X-Active-Organization']).toBe(organizationId);
    expect(descriptor.headers['Idempotency-Key']).toBeTruthy();
    expect(descriptor.body).toBeUndefined();
  });

  it('downloads a manifest using an encoded job identifier', async () => {
    await downloadManifest('job/with space', organizationId);
    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('GET');
    expect(descriptor.url).toBe('/api/v1/tenant/exports/job%2Fwith%20space');
    expect(descriptor.headers['Idempotency-Key']).toBeUndefined();
  });

  it('runs a dry run by posting the synthetic manifest', async () => {
    const manifest = manifestFromDraft(draft);
    await runDryRun(manifest, organizationId);
    const descriptor = descriptorAt(0);
    expect(descriptor.url).toBe('/api/v1/tenant/imports/dry-run');
    expect(descriptor.method).toBe('POST');
    expect(descriptor.headers['Content-Type']).toBe('application/json');
    expect(descriptor.body).toEqual(manifest);
  });

  it('submits a sign-off to the controlled import endpoint', async () => {
    const request = signOffRequest(manifestFromDraft(draft), {
      approver: 'Synthetic Reviewer',
      role: 'tenant_admin',
      contentSha256: 'sha256:digest',
      approvedAt: '2026-11-22T10:00:00Z',
    });
    await submitSignOff(request, organizationId);
    const descriptor = descriptorAt(0);
    expect(descriptor.url).toBe('/api/v1/tenant/imports/sign-off');
    expect(descriptor.body).toEqual(request);
  });
});

describe('asDataExchangeError', () => {
  it('returns a transport error unchanged', () => {
    const failure = new TransportError('forbidden', {
      correlationId: 'corr-9',
      code: undefined,
      retryAfterSeconds: undefined,
    });
    expect(asDataExchangeError(failure)).toBe(failure);
  });

  it('wraps an unexpected rejection as a server error', () => {
    const error = asDataExchangeError(new Error('unexpected'));
    expect(error.kind).toBe('server');
    expect(error.correlationId).toBeUndefined();
  });
});
