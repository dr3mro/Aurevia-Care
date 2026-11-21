import { useState } from 'react';
import { useActiveOrganization } from '../../api/activeOrganization';
import type { TransportError } from '../../api/transport';
import { ApiPage } from '../../components/ApiPage';
import { Button } from '../../components/Button';
import { MixedScriptText } from '../../components/MixedScriptText';
import { ConflictState, StateView } from '../../components/StateViews';
import { formatInstant } from '../../i18n/format';
import { useI18n } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import { asResultTransportError, getCriticalReport, getCriticalResult } from './api';
import { CriticalActions } from './CriticalActions';
import { countdownText, isOverdue, sortByDueAt } from './domain';
import type { CriticalEvent, CriticalResult, CriticalView } from './domain';
import { resultMessage, resultText } from './messages';
import { useCountdownNow } from './useCountdownNow';

/** Props for one row of the critical worklist. */
interface CriticalRowProps {
  item: CriticalResult;
  now: number;
  locale: Locale;
  onOpen: (id: string) => void;
}

/** One critical result row: the deadline is shown as text as well as colour. */
function CriticalRow({ item, now, locale, onOpen }: CriticalRowProps) {
  const { tokens } = useTheme();
  const overdue = isOverdue(item.dueAt, now);
  return (
    <tr data-overdue={overdue ? 'true' : 'false'}>
      <th scope="row">
        <MixedScriptText text={item.patientId} lang="en" />
      </th>
      <td>
        <MixedScriptText text={item.resultSummary} lang={locale} />
      </td>
      <td>
        <MixedScriptText text={`${item.observedValue} ${item.unit}`} lang="en" />
      </td>
      <td>
        <MixedScriptText
          text={item.dueAt === null ? resultText(locale, 'noDueTime') : formatInstant(item.dueAt, 'UTC', locale)}
          lang={locale}
        />
      </td>
      <td>
        <MixedScriptText text={countdownText(item.dueAt, now, locale)} lang={locale} />
      </td>
      <td>
        {overdue ? (
          <strong
            style={{
              background: tokens.foreground,
              color: tokens.background,
              paddingInline: tokens.spacing.sm,
            }}
          >
            {resultText(locale, 'overdue')}
          </strong>
        ) : null}
      </td>
      <td>
        <Button onClick={() => onOpen(item.id)}>
          {`${resultText(locale, 'openResult')} ${item.patientId}`}
        </Button>
      </td>
    </tr>
  );
}

/** Props for the ordered critical table. */
interface CriticalTableProps {
  items: readonly CriticalResult[];
  now: number;
  locale: Locale;
  onOpen: (id: string) => void;
}

/** Render the critical results ordered by their absolute due time. */
function CriticalTable({ items, now, locale, onOpen }: CriticalTableProps) {
  const { tokens } = useTheme();
  return (
    <table style={{ borderCollapse: 'collapse', width: '100%' }}>
      <caption style={{ textAlign: 'start', paddingBlock: tokens.spacing.sm }}>
        {resultText(locale, 'criticalQueue')}
      </caption>
      <thead>
        <tr>
          <th scope="col">{resultText(locale, 'patient')}</th>
          <th scope="col">{resultText(locale, 'finding')}</th>
          <th scope="col">{resultText(locale, 'observedValue')}</th>
          <th scope="col">{resultText(locale, 'dueTime')}</th>
          <th scope="col">{resultText(locale, 'countdown')}</th>
          <th scope="col">{resultText(locale, 'status')}</th>
          <th scope="col">{resultText(locale, 'actions')}</th>
        </tr>
      </thead>
      <tbody>
        {sortByDueAt(items).map((item) => (
          <CriticalRow key={item.id} item={item} now={now} locale={locale} onOpen={onOpen} />
        ))}
      </tbody>
    </table>
  );
}

/** Props for a labelled list of operational events. */
interface EventListProps {
  events: readonly CriticalEvent[];
  locale: Locale;
  emptyLabel: string;
}

/** Render a short, labelled list of event details with an explicit empty state. */
function EventList({ events, locale, emptyLabel }: EventListProps) {
  const { tokens } = useTheme();
  return (
    <section style={{ display: 'grid', gap: tokens.spacing.sm }}>
      {events.length === 0 ? (
        <p>{emptyLabel}</p>
      ) : (
        <ul>
          {events.map((event) => (
            <li key={event.id}>
              <MixedScriptText text={event.detail} lang={locale} />
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/** Props for the loaded single-result panel. */
interface DetailPanelProps {
  id: string;
  organizationId: string;
  locale: Locale;
  now: number;
  reloadToken: number;
  onDone: () => void;
  onError: (error: TransportError) => void;
}

/** Load one critical result with its event trail and offer the allowed commands. */
function DetailPanel({
  id,
  organizationId,
  locale,
  now,
  reloadToken,
  onDone,
  onError,
}: DetailPanelProps) {
  const { tokens } = useTheme();
  return (
    <ApiPage
      resource={{
        label: resultText(locale, 'resultDetail'),
        load: () => getCriticalResult(id, organizationId),
        deps: [id, organizationId, reloadToken],
      }}
      isEmpty={() => false}
    >
      {(view: CriticalView) => (
        <section style={{ display: 'grid', gap: tokens.spacing.md }}>
          <h3>{resultText(locale, 'resultDetail')}</h3>
          <p>
            {resultText(locale, 'finding')}:{' '}
            <MixedScriptText text={view.result.resultSummary} lang={locale} />
          </p>
          <p>
            {resultText(locale, 'state')}:{' '}
            <MixedScriptText text={view.result.state} lang="en" />
          </p>
          <section aria-label={resultText(locale, 'eventTrail')}>
            <h4>{resultText(locale, 'eventTrail')}</h4>
            <EventList
              events={view.events}
              locale={locale}
              emptyLabel={resultText(locale, 'noEvents')}
            />
          </section>
          <CriticalActions
            result={view.result}
            organizationId={organizationId}
            locale={locale}
            now={now}
            onDone={onDone}
            onError={onError}
          />
        </section>
      )}
    </ApiPage>
  );
}

/** The tenant-scoped queue, report sections and command state. */
function CriticalQueueContent({ organizationId }: { organizationId: string }) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const now = useCountdownNow();
  const [selectedId, setSelectedId] = useState<string | undefined>(undefined);
  const [commandError, setCommandError] = useState<TransportError | undefined>(undefined);
  const [statusText, setStatusText] = useState<string | undefined>(undefined);
  const [reloadToken, setReloadToken] = useState(0);
  const [comparing, setComparing] = useState(false);
  const [copied, setCopied] = useState(false);

  function handleDone() {
    setStatusText(resultText(locale, 'commandSent'));
    setCommandError(undefined);
    setReloadToken((value) => value + 1);
  }

  function handleError(failure: unknown) {
    setCommandError(asResultTransportError(failure));
    setStatusText(undefined);
  }

  function handleReload() {
    setCommandError(undefined);
    setStatusText(undefined);
    setComparing(false);
    setCopied(false);
    setReloadToken((value) => value + 1);
  }

  function handleCopy(id: string) {
    void navigator.clipboard?.writeText(id);
    setCopied(true);
  }

  return (
    <div style={{ display: 'grid', gap: tokens.spacing.lg }}>
      <header>
        <h1>{resultText(locale, 'criticalQueue')}</h1>
        <p>{resultText(locale, 'criticalIntro')}</p>
      </header>
      <ApiPage
        resource={{
          label: resultText(locale, 'criticalQueue'),
          load: () => getCriticalReport(organizationId),
          deps: [organizationId, reloadToken],
        }}
        isEmpty={(report) =>
          report.overdue.length === 0 &&
          report.rosterGaps.length === 0 &&
          report.failedNotifications.length === 0
        }
      >
        {(report) => (
          <div style={{ display: 'grid', gap: tokens.spacing.lg }}>
            <p role="status">{resultMessage(locale, 'criticalResultsCount', { count: report.overdue.length })}</p>
            <CriticalTable items={report.overdue} now={now} locale={locale} onOpen={setSelectedId} />
            <section aria-label={resultText(locale, 'rosterGaps')}>
              <h2>{resultText(locale, 'rosterGaps')}</h2>
              <EventList
                events={report.rosterGaps}
                locale={locale}
                emptyLabel={resultText(locale, 'noRosterGaps')}
              />
            </section>
            <section aria-label={resultText(locale, 'failedNotifications')}>
              <h2>{resultText(locale, 'failedNotifications')}</h2>
              <EventList
                events={report.failedNotifications}
                locale={locale}
                emptyLabel={resultText(locale, 'noFailedNotifications')}
              />
            </section>
          </div>
        )}
      </ApiPage>
      {statusText !== undefined && <p role="status">{statusText}</p>}
      {selectedId !== undefined && (
        <>
          <DetailPanel
            id={selectedId}
            organizationId={organizationId}
            locale={locale}
            now={now}
            reloadToken={reloadToken}
            onDone={handleDone}
            onError={handleError}
          />
          {commandError !== undefined ? (
            commandError.kind === 'conflict' ? (
              <ConflictState
                onCompare={() => setComparing(true)}
                onReload={handleReload}
                onCopy={() => handleCopy(selectedId)}
              />
            ) : (
              StateView.forTransportError(commandError)
            )
          ) : null}
          {copied && <p role="status">{resultText(locale, 'copiedNotice')}</p>}
          {comparing && (
            <section data-testid="critical-compare" style={{ display: 'grid', gap: tokens.spacing.sm }}>
              <h3>{resultText(locale, 'compareTitle')}</h3>
              <p>{resultText(locale, 'compareHint')}</p>
            </section>
          )}
        </>
      )}
    </div>
  );
}

/** `/results/critical`: remounting on an organization switch clears all state. */
export function CriticalQueuePage() {
  const organizationId = useActiveOrganization();
  return <CriticalQueueContent key={organizationId} organizationId={organizationId} />;
}
