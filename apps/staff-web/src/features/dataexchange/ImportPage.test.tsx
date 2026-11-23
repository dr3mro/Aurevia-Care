import type { ReactElement } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ActiveOrganizationProvider } from '../../api/activeOrganization';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import { stateViewText } from '../../components/stateViewMessages';
import type { Locale } from '../../i18n/messages';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { ImportPage } from './ImportPage';
import { dataExchangeText } from './labels';
import type { DryRunReportResponse } from './domain';
import { must } from '../../../tests/must';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);
const organizationId = '11111111-1111-1111-1111-111111111111';
const text = (key: Parameters<typeof dataExchangeText>[1]) => dataExchangeText('en', key);

/** Dry-run report with every finding present. */
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

/** Dry-run report with no findings at all. */
const emptyReport: DryRunReportResponse = {
  counts: {
    total_records: 0,
    batch_count: 0,
    unsupported_fields: 0,
    duplicate_risk: 0,
    missing_attachments: 0,
    errors: 0,
  },
  unsupported_fields: [],
  duplicate_risk: [],
  missing_attachments: [],
  errors: [],
};

/** Descriptor sent to the transport at a given call index. */
function descriptorAt(index: number): RequestDescriptor {
  return must(sendMock.mock.calls[index])[0];
}

/** Typed failure for a denied call. */
function denied(): TransportError {
  return new TransportError('forbidden', {
    correlationId: 'corr-2',
    code: undefined,
    retryAfterSeconds: undefined,
  });
}

/** Render the page under the shared providers. */
function wrap(
  ui: ReactElement = <ImportPage roleOverride="tenant_admin" />,
  locale: Locale = 'en',
) {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ActiveOrganizationProvider value={{ organizationId }}>{ui}</ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>,
  );
}

/** Submit the dry-run form. */
function runDryRun() {
  fireEvent.submit(screen.getByRole('form', { name: text('dryRunForm') }));
}

/** Complete the sign-off form with an authorized approver. */
function fillSignOff() {
  fireEvent.change(screen.getByLabelText(text('approver')), {
    target: { value: 'Synthetic Reviewer' },
  });
  fireEvent.change(screen.getByLabelText(text('contentSha256')), {
    target: { value: 'sha256:digest' },
  });
  fireEvent.change(screen.getByLabelText(text('approvedAt')), {
    target: { value: '2026-11-22T10:00:00Z' },
  });
}

/** Submit the sign-off action. */
function submitSignOff() {
  fireEvent.click(screen.getByRole('button', { name: text('signOffButton') }));
}

beforeEach(() => {
  sendMock.mockResolvedValue(report);
});

describe('onboarding import screen', () => {
  it('conceals the screen from a role without import permission', () => {
    wrap(<ImportPage roleOverride="reception" />);
    expect(screen.getByRole('heading', { name: stateViewText('en', 'forbidden') })).toBeTruthy();
    expect(screen.queryByRole('heading', { name: text('importTitle') })).toBeNull();
    expect(sendMock).not.toHaveBeenCalled();
  });

  it('uses the session role when no override is supplied', () => {
    wrap(<ImportPage />);
    expect(screen.getByRole('heading', { name: stateViewText('en', 'forbidden') })).toBeTruthy();
  });

  it('lists unsupported fields and the other findings in the dry-run report', async () => {
    wrap();
    runDryRun();
    expect(await screen.findByText(text('dryRunDone'))).toBeTruthy();
    const unsupported = screen.getByRole('list', { name: text('unsupportedFields') });
    expect(unsupported.textContent).toContain('legacy_ssn');
    expect(unsupported.textContent).toContain('free_text_notes');
    expect(screen.getByRole('list', { name: text('duplicateRisk') }).textContent).toContain(
      'record-2',
    );
    expect(screen.getByRole('list', { name: text('missingAttachments') }).textContent).toContain(
      'record-3: scans/attachment-9',
    );
    expect(screen.getByRole('list', { name: text('errors') }).textContent).toContain(
      'invalid provenance',
    );
    expect(screen.getByText(`${text('totalRecords')}: 4`)).toBeTruthy();
    expect(screen.getByText(`${text('batchCount')}: 2`)).toBeTruthy();
    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('POST');
    expect(descriptor.url).toBe('/api/v1/tenant/imports/dry-run');
    expect(descriptor.headers['X-Active-Organization']).toBe(organizationId);
    const body = descriptor.body as { manifest_version: number };
    expect(body.manifest_version).toBe(1);
  });

  it('shows an explicit empty message for every finding when the report is clean', async () => {
    sendMock.mockResolvedValueOnce(emptyReport);
    wrap();
    runDryRun();
    expect(await screen.findByText(text('noUnsupportedFields'))).toBeTruthy();
    expect(screen.getByText(text('noDuplicateRisk'))).toBeTruthy();
    expect(screen.getByText(text('noMissingAttachments'))).toBeTruthy();
    expect(screen.getByText(text('noErrors'))).toBeTruthy();
  });

  it('carries edited synthetic dataset fields into the dry-run manifest', async () => {
    wrap();
    fireEvent.change(screen.getByLabelText(text('source')), {
      target: { value: 'SYNTHETIC-FHIR' },
    });
    fireEvent.change(screen.getByLabelText(text('sourceVersion')), { target: { value: '2.3' } });
    fireEvent.change(screen.getByLabelText(text('manifestVersion')), { target: { value: '5' } });
    fireEvent.change(screen.getByLabelText(text('unsupportedFieldNames')), {
      target: { value: 'legacy_note' },
    });
    runDryRun();
    await screen.findByText(text('dryRunDone'));
    const body = descriptorAt(0).body as {
      source: string;
      manifest_version: number;
      provenance: { source_system: string; source_version: string };
      records: unknown[];
    };
    expect(body).toMatchObject({
      source: 'SYNTHETIC-FHIR',
      manifest_version: 5,
      provenance: { source_system: 'SYNTHETIC-FHIR', source_version: '2.3' },
    });
    expect(body.records).toHaveLength(1);
  });
});

describe('onboarding import screen commands', () => {
  it('refuses a zero or non-numeric manifest version without calling the API', async () => {
    wrap();
    fireEvent.change(screen.getByLabelText(text('manifestVersion')), { target: { value: '0' } });
    runDryRun();
    expect(await screen.findByText(text('invalidManifestVersion'))).toBeTruthy();
    expect(sendMock).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText(text('manifestVersion')), { target: { value: 'abc' } });
    runDryRun();
    expect(screen.getByText(text('invalidManifestVersion'))).toBeTruthy();
    expect(sendMock).not.toHaveBeenCalled();
  });

  it('shows the forbidden state when the dry-run API denies the request', async () => {
    sendMock.mockRejectedValueOnce(denied());
    wrap();
    runDryRun();
    expect(await screen.findByText(stateViewText('en', 'forbidden'))).toBeTruthy();
    expect(screen.getByTestId('import-command-error')).toBeTruthy();
  });

  it('requires a named approver before a sign-off can be sent', async () => {
    wrap();
    submitSignOff();
    expect(await screen.findByText(text('reasonMissingApprover'))).toBeTruthy();
    expect(sendMock).not.toHaveBeenCalled();
  });

  it('requires a prior dry run before a sign-off can be sent', async () => {
    wrap();
    fillSignOff();
    submitSignOff();
    expect(await screen.findByText(text('runDryRunFirst'))).toBeTruthy();
    expect(sendMock).not.toHaveBeenCalled();
  });

  it('refuses a non-affirmative decision locally', async () => {
    wrap();
    runDryRun();
    await screen.findByText(text('dryRunDone'));
    fillSignOff();
    fireEvent.change(screen.getByLabelText(text('decision')), { target: { value: 'rejected' } });
    submitSignOff();
    expect(await screen.findByText(text('reasonDecisionNotApproved'))).toBeTruthy();
    expect(sendMock).toHaveBeenCalledTimes(1);
  });

  it('accepts an authorized sign-off after a reviewed dry run', async () => {
    wrap();
    runDryRun();
    await screen.findByText(text('dryRunDone'));
    fillSignOff();
    fireEvent.change(screen.getByLabelText(text('decision')), { target: { value: 'rejected' } });
    fireEvent.change(screen.getByLabelText(text('decision')), { target: { value: 'approved' } });
    sendMock.mockResolvedValueOnce({ accepted: true, reason: '', progress: null });
    submitSignOff();
    expect(await screen.findByText(text('signOffDone'))).toBeTruthy();
    const descriptor = descriptorAt(1);
    expect(descriptor.url).toBe('/api/v1/tenant/imports/sign-off');
    const body = descriptor.body as {
      sign_off: { approver: string; role: string; decision: string; content_sha256: string };
    };
    expect(body.sign_off).toMatchObject({
      approver: 'Synthetic Reviewer',
      role: 'tenant_admin',
      decision: 'approved',
      content_sha256: 'sha256:digest',
    });
  });

  it('surfaces a sign-off refused by the import service', async () => {
    wrap();
    runDryRun();
    await screen.findByText(text('dryRunDone'));
    fillSignOff();
    sendMock.mockResolvedValueOnce({ accepted: false, reason: 'stale', progress: null });
    submitSignOff();
    expect(await screen.findByText(text('signOffRefused'))).toBeTruthy();
  });

  it('shows the error state when the sign-off call fails unexpectedly', async () => {
    wrap();
    runDryRun();
    await screen.findByText(text('dryRunDone'));
    fillSignOff();
    sendMock.mockRejectedValueOnce(new Error('offline'));
    submitSignOff();
    expect(await screen.findByRole('heading', { name: stateViewText('en', 'error') })).toBeTruthy();
  });

  it('renders the Arabic labels for a permitted role', () => {
    wrap(<ImportPage roleOverride="privacy_auditor" />, 'ar');
    expect(
      screen.getByRole('heading', { name: dataExchangeText('ar', 'importTitle') }),
    ).toBeTruthy();
    expect(
      screen.getByRole('button', { name: dataExchangeText('ar', 'signOffButton') }),
    ).toBeTruthy();
  });
});
