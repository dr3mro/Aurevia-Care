import { useCallback, useMemo, useState } from 'react';
import { useActiveOrganization } from '../../api/activeOrganization';
import { useApi } from '../../api/useApi';
import { LoadingState, StateView } from '../../components/StateViews';
import type { QueueQuery } from './api';
import { loadQueues, runQueueAction } from './api';
import { QueuesScreen } from './QueuesScreen';
import type { QueueAction, QueueCollection, QueueId, QueueRow } from './types';

/** Props for the API-backed operational queues page. */
export interface QueuesPageProps {
  /** Injected clock; tests and previews pin it so ages are deterministic. */
  nowMs?: number;
}

/**
 * Load all five queues for the active tenant and hand them to the presentational
 * screen. Commands are sent by `runQueueAction` exactly once per user action;
 * a failure becomes the screen's error state and is never retried here.
 */
export function QueuesPage({ nowMs = Date.now() }: QueuesPageProps) {
  const organizationId = useActiveOrganization();
  const query = useMemo<QueueQuery>(
    () => ({ unitId: 'synthetic-unit', facilityCode: 'MAIN' }),
    [],
  );
  const [active, setActive] = useState<QueueId>('work-items');
  const result = useApi<QueueCollection>(
    () => loadQueues(organizationId, query),
    [organizationId, query],
  );
  const reload = result.reload;

  const onAction = useCallback(
    async (queue: QueueId, row: QueueRow, action: QueueAction, reason: string): Promise<void> => {
      await runQueueAction(queue, row, action, reason, organizationId);
      reload();
    },
    [organizationId, reload],
  );

  if (result.status === 'loading') {
    return <LoadingState />;
  }
  if (result.status === 'error') {
    return <div>{StateView.forTransportError(result.error)}</div>;
  }
  return (
    <QueuesScreen
      collection={result.data}
      active={active}
      onActiveChange={setActive}
      nowMs={nowMs}
      onAction={onAction}
      onReload={reload}
    />
  );
}
