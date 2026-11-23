import { useCallback, useMemo, useState } from 'react';
import { useActiveOrganization } from '../../api/activeOrganization';
import { TransportError } from '../../api/transport';
import { useApi } from '../../api/useApi';
import { Button } from '../../components/Button';
import { EmptyState, LoadingState, StateView } from '../../components/StateViews';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import { CollectionDeskScreen } from './CollectionDeskScreen';
import {
  collectSpecimen,
  listAccessions,
  readLabel,
  rejectSpecimen,
  toCollectionOrder,
} from './api';
import type { AccessionPageResponse, AccessionViewResponse, CollectBody, RejectBody } from './api';
import type { CollectionOrder, LabelData } from './domain';
import { labText } from './messages';

/** Accumulated pages together with the organization that owns them. */
interface AccumulatedPages {
  organizationId: string;
  items: readonly AccessionViewResponse[];
}

/** An empty owner cannot match any active organization. */
const emptyPages: AccumulatedPages = { organizationId: '', items: [] };

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

/**
 * Load lab accessions for the active organization, let the collector open one,
 * and dispatch collection, label and rejection commands through the presentational
 * screen. A failed command keeps the typed drafts on screen so nothing is lost.
 */
export function CollectionDeskPage() {
  const organizationId = useActiveOrganization();
  // A tenant switch remounts the desk, which invalidates every accumulated
  // page and every local draft.
  return <CollectionDeskContent key={organizationId} organizationId={organizationId} />;
}

/** Preserve the success announcement when the list screen is absent during reload. */
function DetachedCollectionStatus({ hasRows, message }: { hasRows: boolean; message: string }) {
  if (hasRows || message === '') {
    return null;
  }
  return (
    <p role="status" data-testid="collection-status">
      {message}
    </p>
  );
}

function CollectionDeskContent({ organizationId }: { organizationId: string }) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const [cursor, setCursor] = useState<string | undefined>(undefined);
  const [accumulated, setAccumulated] = useState<AccumulatedPages>(emptyPages);
  const [nextCursor, setNextCursor] = useState<string | undefined>(undefined);
  const [selectedId, setSelectedId] = useState<string | undefined>(undefined);
  const [label, setLabel] = useState<LabelData | undefined>(undefined);
  const [commandError, setCommandError] = useState<TransportError | undefined>(undefined);
  const [statusMessage, setStatusMessage] = useState('');
  const [appliedPage, setAppliedPage] = useState<AccessionPageResponse | undefined>(undefined);

  const result = useApi<AccessionPageResponse>(
    () => listAccessions({ cursor }, organizationId),
    [cursor],
  );

  if (result.status === 'ready' && result.data !== appliedPage) {
    const page = result.data;
    setAppliedPage(page);
    setAccumulated((current) => ({
      organizationId,
      items: [...(current.organizationId === organizationId ? current.items : []), ...page.items],
    }));
    setNextCursor(page.nextCursor ?? undefined);
  }

  // Derive the visible rows during render, not in the reset effect: a tenant
  // switch must never paint the previous tenant's rows under the new tenant id.
  const loaded = useMemo<CollectionOrder[]>(() => {
    const ownedItems = accumulated.organizationId === organizationId ? accumulated.items : [];
    return ownedItems.map((view) => toCollectionOrder(view, organizationId));
  }, [accumulated, organizationId]);
  const selected = loaded.find((order) => order.id === selectedId);
  const visibleCommandError = loaded.length === 0 ? undefined : commandError;

  const { reload: refetch } = result;
  const reload = useCallback(() => {
    setCursor(undefined);
    setAccumulated(emptyPages);
    setNextCursor(undefined);
    setLabel(undefined);
    setCommandError(undefined);
    setStatusMessage('');
    refetch();
  }, [refetch]);

  async function handleCollect(order: CollectionOrder, body: CollectBody) {
    try {
      await collectSpecimen(order.id, body, organizationId);
      setCommandError(undefined);
      reload();
      setStatusMessage(labText(locale, 'collectionRecorded'));
    } catch (failure: unknown) {
      setStatusMessage('');
      setCommandError(toTransportError(failure));
    }
  }

  async function handlePrint(order: CollectionOrder) {
    try {
      setLabel(await readLabel(order.id, organizationId));
      setCommandError(undefined);
    } catch (failure: unknown) {
      setStatusMessage('');
      setCommandError(toTransportError(failure));
    }
  }

  async function handleReject(order: CollectionOrder, body: RejectBody) {
    try {
      await rejectSpecimen(order.id, body, organizationId);
      setCommandError(undefined);
      reload();
      setStatusMessage(labText(locale, 'specimenRejected'));
    } catch (failure: unknown) {
      setStatusMessage('');
      setCommandError(toTransportError(failure));
    }
  }

  function handleSelect(id: string) {
    setSelectedId(id);
    setLabel(undefined);
    setCommandError(undefined);
    setStatusMessage('');
  }

  function handleLoadMore() {
    setCursor(nextCursor);
  }

  return (
    <div style={{ display: 'grid', gap: tokens.spacing.lg }}>
      {visibleCommandError !== undefined ? (
        <div data-testid="collection-command-error">
          {StateView.forTransportError(visibleCommandError)}
        </div>
      ) : null}
      <DetachedCollectionStatus hasRows={loaded.length > 0} message={statusMessage} />
      {result.status === 'loading' && loaded.length === 0 ? (
        <LoadingState />
      ) : result.status === 'error' && loaded.length === 0 ? (
        StateView.forTransportError(result.error)
      ) : loaded.length === 0 ? (
        <EmptyState />
      ) : (
        <>
          <CollectionDeskScreen
            orders={loaded}
            selected={selected}
            onSelect={handleSelect}
            label={label}
            statusMessage={statusMessage}
            organizationId={organizationId}
            onCollect={(order, body) => {
              void handleCollect(order, body);
            }}
            onPrint={(order) => {
              void handlePrint(order);
            }}
            onReject={(order, body) => {
              void handleReject(order, body);
            }}
          />
          {nextCursor !== undefined ? (
            <Button onClick={handleLoadMore} disabled={result.status === 'loading'}>
              {labText(locale, 'loadMore')}
            </Button>
          ) : null}
        </>
      )}
    </div>
  );
}
