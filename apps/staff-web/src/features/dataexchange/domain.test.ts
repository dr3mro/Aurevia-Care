import { describe, expect, it } from 'vitest';
import {
  canReviewImport,
  canStartExport,
  exportState,
  signOffRejection,
  toDryRunView,
  toExportListItem,
} from './domain';
import type { DryRunReportResponse, ExportResultResponse } from './domain';

/** Export result as the API projects it. */
const result: ExportResultResponse = {
  job_id: 'job-1',
  checksum: 'sha256:abc',
  record_count: 3,
  expires_at: '2026-11-22T10:00:00Z',
};

/** Full dry-run report as the API projects it. */
const report: DryRunReportResponse = {
  counts: {
    total_records: 4,
    batch_count: 2,
    unsupported_fields: 2,
    duplicate_risk: 1,
    missing_attachments: 1,
    errors: 1,
  },
  unsupported_fields: ['legacy_ssn', 'free_text_notes'],
  duplicate_risk: ['record-2'],
  missing_attachments: [{ record: 'record-3', key: 'scans/attachment-9' }],
  errors: ['invalid provenance'],
};

describe('data exchange permissions', () => {
  it('permits only a tenant admin to start an export', () => {
    expect(canStartExport('tenant_admin')).toBe(true);
    expect(canStartExport('privacy_auditor')).toBe(false);
    expect(canStartExport('reception')).toBe(false);
  });

  it('permits an admin and a privacy auditor to review and sign off an import', () => {
    expect(canReviewImport('tenant_admin')).toBe(true);
    expect(canReviewImport('privacy_auditor')).toBe(true);
    expect(canReviewImport('reception')).toBe(false);
  });
});

describe('export lifecycle', () => {
  it('returns expired when the expiry instant has passed', () => {
    expect(exportState('2026-11-22T10:00:00Z', '2026-11-22T10:00:01Z')).toBe('expired');
  });

  it('returns active when the expiry instant is in the future', () => {
    expect(exportState('2026-11-22T10:00:00Z', '2026-11-22T09:59:59Z')).toBe('active');
  });

  it('fails closed on an unreadable expiry instant', () => {
    expect(exportState('not-a-date', '2026-11-22T09:00:00Z')).toBe('expired');
  });

  it('fails closed on an unreadable current instant', () => {
    expect(exportState('2026-11-22T10:00:00Z', 'not-a-date')).toBe('expired');
  });

  it('projects an API result into a list row with a derived state', () => {
    expect(toExportListItem(result, '2026-11-22T10:00:01Z')).toEqual({
      jobId: 'job-1',
      checksum: 'sha256:abc',
      recordCount: 3,
      expiresAt: '2026-11-22T10:00:00Z',
      state: 'expired',
    });
    expect(toExportListItem(result, '2026-11-22T09:00:00Z').state).toBe('active');
  });
});

describe('dry run view', () => {
  it('keeps the unsupported fields and the other findings in order', () => {
    expect(toDryRunView(report)).toEqual({
      totalRecords: 4,
      batchCount: 2,
      unsupportedFields: ['legacy_ssn', 'free_text_notes'],
      duplicateRisk: ['record-2'],
      missingAttachments: ['record-3: scans/attachment-9'],
      errors: ['invalid provenance'],
    });
  });

  it('defaults every missing finding to an empty value at the boundary', () => {
    expect(
      toDryRunView({
        counts: undefined,
        missing_attachments: undefined,
      }),
    ).toEqual({
      totalRecords: 0,
      batchCount: 0,
      unsupportedFields: [],
      duplicateRisk: [],
      missingAttachments: [],
      errors: [],
    });
  });
});

describe('sign-off validation', () => {
  it('refuses an empty approver before any other check', () => {
    expect(signOffRejection('  ', 'reception', 'rejected')).toBe('missingApprover');
  });

  it('refuses a role that is not authorised to sign off', () => {
    expect(signOffRejection('Synthetic Reviewer', 'reception', 'approved')).toBe(
      'roleNotPermitted',
    );
  });

  it('refuses a decision other than approved', () => {
    expect(signOffRejection('Synthetic Reviewer', 'tenant_admin', 'rejected')).toBe(
      'decisionNotApproved',
    );
  });

  it('accepts a named, authorised, affirmative sign-off', () => {
    expect(signOffRejection('Synthetic Reviewer', 'privacy_auditor', 'approved')).toBeUndefined();
  });
});
