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
import { ExportPage } from './ExportPage';
import { dataExchangeText } from './labels';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);
const organizationId = '11111111-1111-1111-1111-111111111111';
const now = '2026-11-22T09:00:00Z';

/** Export result used by the synthetic tests. */
const result = {
  job_id: 'job-1',
  checksum: 'sha256:abc',
  record_count: 3,
  expires_at: '2026-11-22T10:00:00Z',
};

/** Descriptor sent to the transport at a given call index. */
function descriptorAt(index: number): RequestDescriptor {
  return sendMock.mock.calls[index]![0] as RequestDescriptor;
}

/** Typed failure for a denied call. */
function denied(): TransportError {
  return new TransportError('forbidden', {
    correlationId: 'corr-1',
    code: undefined,
    retryAfterSeconds: undefined,
  });
}

/** Render the page under the shared providers. */
function wrap(ui: ReactElement = <ExportPage role="tenant_admin" now={now} />, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ActiveOrganizationProvider value={{ organizationId }}>
          {ui}
        </ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>,
  );
}

beforeEach(() => {
  sendMock.mockResolvedValue(result);
});

describe('tenant export screen', () => {
  it('conceals the screen from a role without export permission', () => {
    wrap(<ExportPage role="reception" now={now} />);
    expect(screen.getByRole('heading', { name: stateViewText('en', 'forbidden') })).toBeTruthy();
    expect(screen.queryByRole('button', { name: dataExchangeText('en', 'startExport') })).toBeNull();
    expect(sendMock).not.toHaveBeenCalled();
  });

  it('uses the session role and current clock when no story overrides are supplied', () => {
    wrap(<ExportPage />);
    expect(screen.getByRole('heading', { name: stateViewText('en', 'forbidden') })).toBeTruthy();
  });

  it('starts an export and lists the job with a download action', async () => {
    wrap();
    fireEvent.click(screen.getByRole('button', { name: dataExchangeText('en', 'startExport') }));
    expect(await screen.findByText(dataExchangeText('en', 'exportStarted'))).toBeTruthy();
    expect(descriptorAt(0).method).toBe('POST');
    expect(descriptorAt(0).url).toBe('/api/v1/tenant/exports');
    expect(descriptorAt(0).headers['X-Active-Organization']).toBe(organizationId);
    expect(screen.getByText('job-1')).toBeTruthy();
    expect(screen.getByText('sha256:abc')).toBeTruthy();
    expect(screen.getByText('3')).toBeTruthy();
    expect(screen.getByText(result.expires_at)).toBeTruthy();

    sendMock.mockResolvedValueOnce({ schema_version: '1.0' });
    fireEvent.click(screen.getByRole('button', { name: dataExchangeText('en', 'download') }));
    expect(await screen.findByText(dataExchangeText('en', 'manifestDownloaded'))).toBeTruthy();
    expect(descriptorAt(1).method).toBe('GET');
    expect(descriptorAt(1).url).toBe('/api/v1/tenant/exports/job-1');
  });

  it('shows an expired export as expired and withholds its download action', async () => {
    wrap(<ExportPage role="tenant_admin" now="2026-11-22T10:05:00Z" />);
    fireEvent.click(screen.getByRole('button', { name: dataExchangeText('en', 'startExport') }));
    expect(await screen.findByTestId('expired-job-1')).toBeTruthy();
    expect(screen.getByText(dataExchangeText('en', 'expired'))).toBeTruthy();
    expect(screen.queryByRole('button', { name: dataExchangeText('en', 'download') })).toBeNull();
  });

  it('shows the empty list before any export is started', () => {
    wrap();
    expect(screen.getByText(dataExchangeText('en', 'noExports'))).toBeTruthy();
  });

  it('shows the forbidden state when the export API denies the request', async () => {
    sendMock.mockRejectedValueOnce(denied());
    wrap();
    fireEvent.click(screen.getByRole('button', { name: dataExchangeText('en', 'startExport') }));
    expect(await screen.findByText(stateViewText('en', 'forbidden'))).toBeTruthy();
    expect(screen.getByTestId('export-command-error')).toBeTruthy();
  });

  it('shows the error state when the export API fails unexpectedly', async () => {
    sendMock.mockRejectedValueOnce(new Error('unexpected'));
    wrap();
    fireEvent.click(screen.getByRole('button', { name: dataExchangeText('en', 'startExport') }));
    expect(await screen.findByRole('heading', { name: stateViewText('en', 'error') })).toBeTruthy();
  });

  it('shows the error state when a manifest download fails', async () => {
    wrap();
    fireEvent.click(screen.getByRole('button', { name: dataExchangeText('en', 'startExport') }));
    await screen.findByText(dataExchangeText('en', 'exportStarted'));
    sendMock.mockRejectedValueOnce(new Error('offline'));
    fireEvent.click(screen.getByRole('button', { name: dataExchangeText('en', 'download') }));
    expect(await screen.findByRole('heading', { name: stateViewText('en', 'error') })).toBeTruthy();
  });

  it('renders the Arabic labels for a permitted role', () => {
    wrap(<ExportPage role="tenant_admin" now={now} />, 'ar');
    expect(screen.getByRole('heading', { name: dataExchangeText('ar', 'exportTitle') })).toBeTruthy();
    expect(screen.getByRole('button', { name: dataExchangeText('ar', 'startExport') })).toBeTruthy();
  });
});
