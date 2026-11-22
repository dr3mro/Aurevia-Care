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
import { useBreakpoint } from '../../theme/useBreakpoint';
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
 *
 * The `page` utility owns the safe-area-aware logical gutter and the single
 * reading column (WEB-07, UX-01). On compact phones the chart tables sit in a
 * labelled, keyboard-scrollable region so all clinical columns stay reachable
 * without a page-level horizontal scroll; from the medium band up the same
 * tables render directly. No field is hidden and direction stays with the
 * document, so the identical markup mirrors under right-to-left.
 */
export function ChartPage() {
  const organizationId = useActiveOrganization();
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
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
    <div
      className="page"
      data-breakpoint={breakpoint}
      style={{
        display: 'grid',
        gap: tokens.spacing.lg,
        // Flex/grid children shrink so a long clinical value wraps instead of
        // forcing a viewport-wide scroll (UX-01, WCAG 1.4.10).
        minInlineSize: 0,
        overflowWrap: 'anywhere',
      }}
    >
      {result.status === 'error' ? (
        <div data-testid="chart-page-error">{StateView.forTransportError(result.error)}</div>
      ) : null}
      <header style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <h2>{chartText(locale, 'chart')}</h2>
        <p
          style={{
            marginBlock: 0,
            minInlineSize: 0,
            overflowWrap: 'anywhere',
            whiteSpace: 'normal',
            textAlign: 'start',
          }}
        >
          <b>{chartText(locale, 'patient')}:</b>{' '}
          <MixedScriptText text={displayLocalName(chart.patientName, locale)} lang={locale} />
          {' · '}
          <b>{chartText(locale, 'patientId')}:</b>{' '}
          <code dir="ltr">{patientId}</code>
        </p>
      </header>
      {accumulated.length === 0 ? (
        <EmptyState />
      ) : breakpoint === 'compact' ? (
        // One labelled scroll region keeps the wide coded tables reachable by
        // keyboard on a phone; the region disappears when space allows.
        <div
          className="table-region"
          role="region"
          aria-label={chartText(locale, 'chart')}
          tabIndex={0}
        >
          <LongitudinalChart chart={chart} locale={locale} />
        </div>
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
