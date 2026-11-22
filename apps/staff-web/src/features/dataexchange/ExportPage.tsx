/**
 * Tenant export screen (OPS-07).
 *
 * <p>Requests an export for the active organization and lists the jobs started
 * in this session with a verified download action. An artifact whose retention
 * instant has passed is presented as expired and its download action is
 * withheld. Only a tenant administrator may use the screen; both an
 * unpermitted role and a denied API response resolve to the shared forbidden
 * state. Nothing is persisted in the browser and no response text is shown.</p>
 */

import { useState } from 'react';
import { useActiveOrganization } from '../../api/activeOrganization';
import type { TransportError } from '../../api/transport';
import type { Role } from '../../app/navigation';
import { useRole } from '../../app/useRole';
import { Button } from '../../components/Button';
import { ForbiddenState, StateView } from '../../components/StateViews';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import { asDataExchangeError, downloadManifest, startExport } from './api';
import { canStartExport, toExportListItem } from './domain';
import type { ExportListItem } from './domain';
import { dataExchangeText } from './labels';
import type { DataExchangeMessageKey } from './labels';

/** Props for the tenant export screen. */
export interface ExportPageProps {
  /** Role override for tests and embedding; the session role is the default. */
  role?: Role;
  /** Fixed instant used for expiry decisions; the current time is the default. */
  now?: string;
}

/**
 * Render the export request and expiring download list for the active tenant.
 *
 * @param props optional role and clock overrides (synthetic values in tests)
 */
export function ExportPage({ role, now }: ExportPageProps) {
  const organizationId = useActiveOrganization();
  const sessionRole = useRole();
  const activeRole = role ?? sessionRole;
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const [jobs, setJobs] = useState<readonly ExportListItem[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<TransportError | undefined>(undefined);
  const [status, setStatus] = useState<DataExchangeMessageKey | undefined>(undefined);
  const clock = now ?? new Date().toISOString();

  if (!canStartExport(activeRole)) {
    return <ForbiddenState />;
  }

  async function handleStart() {
    setBusy(true);
    try {
      const result = await startExport(organizationId);
      setJobs((current) => [toExportListItem(result, clock), ...current]);
      setStatus('exportStarted');
      setError(undefined);
    } catch (failure: unknown) {
      setStatus(undefined);
      setError(asDataExchangeError(failure));
    } finally {
      setBusy(false);
    }
  }

  async function handleDownload(job: ExportListItem) {
    setBusy(true);
    try {
      await downloadManifest(job.jobId, organizationId);
      setStatus('manifestDownloaded');
      setError(undefined);
    } catch (failure: unknown) {
      setStatus(undefined);
      setError(asDataExchangeError(failure));
    } finally {
      setBusy(false);
    }
  }

  const text = (key: DataExchangeMessageKey) => dataExchangeText(locale, key);

  return (
    <div style={{ display: 'grid', gap: tokens.spacing.lg }}>
      <h2>{text('exportTitle')}</h2>
      <p>{text('syntheticNotice')}</p>
      <p>{text('exportIntro')}</p>
      <Button onClick={() => void handleStart()} disabled={busy}>
        {text('startExport')}
      </Button>
      {status === undefined ? null : <p role="status">{text(status)}</p>}
      {error === undefined ? null : (
        <div data-testid="export-command-error">{StateView.forTransportError(error)}</div>
      )}
      <section aria-label={text('downloadList')} style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <h3>{text('downloadList')}</h3>
        {jobs.length === 0 ? (
          <p>{text('noExports')}</p>
        ) : (
          <ul aria-label={text('downloadList')} style={{ display: 'grid', gap: tokens.spacing.md }}>
            {jobs.map((job) => (
              <li key={job.jobId} style={{ display: 'grid', gap: tokens.spacing.sm }}>
                <dl style={{ display: 'grid', gap: tokens.spacing.sm }}>
                  <dt>{text('jobId')}</dt>
                  <dd><code>{job.jobId}</code></dd>
                  <dt>{text('checksum')}</dt>
                  <dd><code>{job.checksum}</code></dd>
                  <dt>{text('recordCount')}</dt>
                  <dd>{job.recordCount}</dd>
                  <dt>{text('expiresAt')}</dt>
                  <dd><time dateTime={job.expiresAt}>{job.expiresAt}</time></dd>
                </dl>
                {job.state === 'expired' ? (
                  <p role="status" data-testid={`expired-${job.jobId}`}>{text('expired')}</p>
                ) : (
                  <Button onClick={() => void handleDownload(job)} disabled={busy}>
                    {text('download')}
                  </Button>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
