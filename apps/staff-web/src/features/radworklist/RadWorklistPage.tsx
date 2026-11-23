import { useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { useActiveOrganization } from '../../api/activeOrganization';
import { TransportError } from '../../api/transport';
import { useApi } from '../../api/useApi';
import {
  ConflictState,
  EmptyState,
  ErrorState,
  ForbiddenState,
  LoadingState,
  SessionExpiredState,
} from '../../components/StateViews';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import { linkStudy, listWorklist, reportOrder, scheduleOrder, toOrder } from './api';
import type { ApiModality, WorklistEntryResponse } from './api';
import type { ImagingWorklistOrder, StatusFilter } from './domain';
import { filterOrders, ordersForSite } from './domain';
import { syntheticPerformingSite } from './fixtures';
import { radworklistText } from './labels';
import { RadWorklistScreen } from './RadWorklistScreen';
import type { ScheduleDraft } from './ScheduleForm';
import type { StudyLinkDraft } from './StudyLinkForm';

/** Normalise an unexpected command rejection to a typed transport failure. */
function toTransportError(failure: unknown): TransportError {
  if (failure instanceof TransportError) {
    return failure;
  }
  return new TransportError('server', {
    correlationId: undefined,
    code: undefined,
    retryAfterSeconds: undefined,
  });
}

/** Recovery actions a failed request offers without leaving the screen. */
interface FailureActions {
  retry: () => void;
  compare: () => void;
  reload: () => void;
  copy: () => void;
}

/**
 * Map a typed failure to its state view with live recovery handlers. Every
 * control does something: retry and reload refetch, compare and copy reveal the
 * typed draft that a conflict must never discard (WEB-09).
 */
function failureView(error: TransportError, actions: FailureActions): ReactNode {
  if (error.kind === 'session-expired') {
    return <SessionExpiredState onSignIn={actions.reload} />;
  }
  if (error.kind === 'forbidden') {
    return <ForbiddenState />;
  }
  if (error.kind === 'conflict' || error.kind === 'precondition-required') {
    return (
      <ConflictState onCompare={actions.compare} onReload={actions.reload} onCopy={actions.copy} />
    );
  }
  return <ErrorState correlationId={error.correlationId} onRetry={actions.retry} />;
}

/** Request body for scheduling one order at its performing site. */
function scheduleBody(order: ImagingWorklistOrder, draft: ScheduleDraft) {
  return {
    expectedVersion: order.version,
    performingSiteId: order.performingSiteId,
    scheduledAt: draft.scheduledAt,
    modality: draft.modality.toUpperCase() as ApiModality,
    accession: draft.accession,
    accessionIssuer: draft.accessionIssuer,
  };
}

/** Typed draft kept on screen so a failed schedule can be compared or copied. */
function scheduleDraftText(order: ImagingWorklistOrder, draft: ScheduleDraft): string {
  return JSON.stringify({
    orderId: order.orderId,
    scheduledAt: draft.scheduledAt,
    modality: draft.modality,
    accession: draft.accession,
    accessionIssuer: draft.accessionIssuer,
  });
}

/** Request body for linking a study to one order. */
function linkBody(order: ImagingWorklistOrder, draft: StudyLinkDraft) {
  return {
    expectedVersion: order.version,
    performingSiteId: order.performingSiteId,
    studyInstanceUid: draft.studyInstanceUid,
    patientId: draft.patientId,
    accession: draft.accession,
    workstationRef: draft.workstationRef,
  };
}

/** Typed draft kept on screen so a failed link can be compared or copied. */
function linkDraftText(order: ImagingWorklistOrder, draft: StudyLinkDraft): string {
  return JSON.stringify({
    orderId: order.orderId,
    patientId: draft.patientId,
    accession: draft.accession,
    studyInstanceUid: draft.studyInstanceUid,
    workstationRef: draft.workstationRef,
  });
}

/**
 * Load one performing site's worklist from the API and dispatch scheduling,
 * study-link and handoff commands. A failed command is shown above the screen so
 * the user's typed draft and the current rows are never discarded.
 */
export function RadWorklistPage() {
  const organizationId = useActiveOrganization();
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const [site, setSite] = useState(syntheticPerformingSite);
  const [filter, setFilter] = useState<StatusFilter>('all');
  const [commandError, setCommandError] = useState<TransportError | undefined>(undefined);
  const [announcement, setAnnouncement] = useState('');
  const [lastDraft, setLastDraft] = useState('');
  const [draftView, setDraftView] = useState<'none' | 'compare' | 'copy'>('none');

  const result = useApi<WorklistEntryResponse[]>(
    () => listWorklist(site, filter, organizationId),
    [site, filter],
  );

  // The API is the primary boundary, but the screen repeats both scopes before
  // painting or acting on a row: identical local ids in another tenant or site
  // must never become an actionable row here.
  const rows = useMemo<ImagingWorklistOrder[]>(() => {
    const projected = (result.status === 'ready' ? result.data : []).map((entry) =>
      toOrder(entry, organizationId),
    );
    return filterOrders(ordersForSite(projected, organizationId, site), filter);
  }, [result.status, result.data, organizationId, site, filter]);

  const [scope, setScope] = useState({ organizationId, site });
  if (scope.organizationId !== organizationId || scope.site !== site) {
    setScope({ organizationId, site });
    setCommandError(undefined);
    setAnnouncement('');
    setDraftView('none');
  }

  const { reload } = result;
  const actions: FailureActions = {
    retry: reload,
    reload,
    compare: () => setDraftView('compare'),
    copy: () => setDraftView('copy'),
  };

  async function runCommand(command: () => Promise<unknown>, draft: string) {
    setLastDraft(draft);
    try {
      await command();
      setCommandError(undefined);
      setAnnouncement(radworklistText(locale, 'commandSent'));
      setDraftView('none');
      reload();
    } catch (failure: unknown) {
      setCommandError(toTransportError(failure));
    }
  }

  function handleSchedule(order: ImagingWorklistOrder, draft: ScheduleDraft) {
    return runCommand(
      () => scheduleOrder(order.orderId, scheduleBody(order, draft), organizationId),
      scheduleDraftText(order, draft),
    );
  }

  function handleLink(order: ImagingWorklistOrder, draft: StudyLinkDraft) {
    return runCommand(
      () => linkStudy(order.orderId, linkBody(order, draft), organizationId),
      linkDraftText(order, draft),
    );
  }

  function handleHandoff(order: ImagingWorklistOrder) {
    return runCommand(
      () =>
        reportOrder(
          order.orderId,
          { expectedVersion: order.version, performingSiteId: order.performingSiteId },
          organizationId,
        ),
      JSON.stringify({ orderId: order.orderId, action: 'handoff' }),
    );
  }

  return (
    <div style={{ display: 'grid', gap: tokens.spacing.lg }}>
      <p role="status" data-testid="radworklist-announcement">
        {announcement}
      </p>
      {commandError !== undefined ? (
        <div data-testid="radworklist-command-error">{failureView(commandError, actions)}</div>
      ) : null}
      {draftView !== 'none' ? (
        <label style={{ display: 'grid', gap: tokens.spacing.sm }}>
          {draftView === 'compare'
            ? radworklistText(locale, 'compareDraft')
            : radworklistText(locale, 'copyDraft')}
          <textarea
            data-testid="draft-disclosure"
            aria-label={radworklistText(
              locale,
              draftView === 'compare' ? 'compareDraft' : 'copyDraft',
            )}
            readOnly
            value={lastDraft}
            rows={3}
          />
        </label>
      ) : null}
      {result.status === 'loading' ? (
        <LoadingState />
      ) : result.status === 'error' ? (
        failureView(result.error, actions)
      ) : (
        <>
          {rows.length === 0 ? <EmptyState /> : null}
          <RadWorklistScreen
            orders={rows}
            organizationId={organizationId}
            site={site}
            filter={filter}
            onSiteChange={setSite}
            onFilterChange={setFilter}
            onSchedule={(order, draft) => {
              void handleSchedule(order, draft);
            }}
            onLink={(order, draft) => {
              void handleLink(order, draft);
            }}
            onHandoff={(order) => {
              void handleHandoff(order);
            }}
          />
        </>
      )}
    </div>
  );
}
