import { useEffect, useState } from 'react';
import { useActiveOrganization } from '../../api/activeOrganization';
import { useApi } from '../../api/useApi';
import { useRoute } from '../../app/router';
import { Button } from '../../components/Button';
import { MixedScriptText } from '../../components/MixedScriptText';
import { EmptyState, LoadingState, StateView } from '../../components/StateViews';
import { displayLocalName } from '../../i18n/format';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import { getPatient, listChartEntries, toChart } from './api';
import type { ChartEntryResponse, ChartViewResponse, PatientViewResponse } from './api';
import { LongitudinalChart } from './LongitudinalChart';
import { chartText } from './messages';

/** One resolved chart page together with the patient that owns it. */
type ChartPageData = { patient: PatientViewResponse; chart: ChartViewResponse };

/**
 * API-backed longitudinal chart for `/patients/:id/chart`.
 *
 * Entries are accumulated across cursor pages; an organization or patient
 * switch clears everything first so a previous tenant's rows are never painted.
 * The patient's display name is always shown next to the opaque local id.
 */
export function ChartPage() {
  const organizationId = useActiveOrganization();
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const { params } = useRoute();
  const patientId = params.id ?? '';
  const [cursor, setCursor] = useState<string | undefined>(undefined);
  const [accumulated, setAccumulated] = useState<readonly ChartEntryResponse[]>([]);
  const [nextCursor, setNextCursor] = useState<string | undefined>(undefined);
  const [patientName, setPatientName] = useState<string | undefined>(undefined);

  const result = useApi<ChartPageData>(
    () =>
      Promise.all([
        getPatient(patientId, organizationId),
        listChartEntries(patientId, organizationId, cursor),
      ]).then(([patient, chart]) => ({ patient, chart })),
    [patientId, organizationId, cursor],
  );

  // Append each resolved page. The dependency is the page object, not the
  // result wrapper, so an unrelated render never duplicates entries.
  useEffect(() => {
    if (result.status !== 'ready') {
      return;
    }
    setAccumulated((current) => [...current, ...result.data.chart.entries]);
    setNextCursor(result.data.chart.nextCursor ?? undefined);
    setPatientName(result.data.patient.patient.displayName);
  }, [result.status, result.data]);

  // Switching organization or patient invalidates every accumulated page.
  useEffect(() => {
    setCursor(undefined);
    setAccumulated([]);
    setNextCursor(undefined);
    setPatientName(undefined);
  }, [patientId, organizationId]);

  const chart = toChart(
    { entries: accumulated },
    organizationId,
    patientId,
    { latinName: patientName ?? patientId },
  );

  if (result.status === 'loading' && accumulated.length === 0) {
    return <LoadingState />;
  }
  if (result.status === 'error' && accumulated.length === 0) {
    return <>{StateView.forTransportError(result.error)}</>;
  }

  return (
    <div style={{ display: 'grid', gap: tokens.spacing.lg }}>
      {result.status === 'error' ? (
        <div data-testid="chart-page-error">{StateView.forTransportError(result.error)}</div>
      ) : null}
      <header style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <h2>{chartText(locale, 'chart')}</h2>
        <p style={{ marginBlock: 0 }}>
          <b>{chartText(locale, 'patient')}:</b>{' '}
          <MixedScriptText text={displayLocalName(chart.patientName, locale)} lang={locale} />
          {' · '}
          <b>{chartText(locale, 'patientId')}:</b>{' '}
          <code dir="ltr">{patientId}</code>
        </p>
      </header>
      {accumulated.length === 0 ? (
        <EmptyState />
      ) : (
        <LongitudinalChart chart={chart} locale={locale} />
      )}
      {nextCursor !== undefined ? (
        <Button onClick={() => setCursor(nextCursor)} disabled={result.status === 'loading'}>
          {chartText(locale, 'loadMore')}
        </Button>
      ) : null}
    </div>
  );
}
