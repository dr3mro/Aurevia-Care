import { useCallback, useEffect, useState } from 'react';
import { useActiveOrganization } from '../../api/activeOrganization';
import { useRoute } from '../../app/router';
import { TransportError } from '../../api/transport';
import { useApi } from '../../api/useApi';
import { LoadingState, StateView } from '../../components/StateViews';
import { AccessionDetailScreen } from './AccessionDetailScreen';
import { collect, getAccession, recollect, receive, reject } from './api';
import type { ActionPayload, AccessionView, LabAccession } from './types';
import { useTheme } from '../../theme/ThemeProvider';

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

/** API-backed detail for `/lab/accessions/:id`, including state-scoped actions. */
export function AccessionDetailPage() {
  const { params } = useRoute();
  const id = params.id ?? '';
  const organizationId = useActiveOrganization();
  const { tokens } = useTheme();
  const [commandError, setCommandError] = useState<TransportError | undefined>(undefined);
  const [linked, setLinked] = useState<LabAccession | undefined>(undefined);

  // A command result belongs to the record it was issued against. Navigating to
  // another accession (for example the linked recollection) must not keep a
  // stale "Linked recollection" banner or a stale command error on screen.
  useEffect(() => {
    setLinked(undefined);
    setCommandError(undefined);
  }, [id, organizationId]);

  const result = useApi<AccessionView>(() => getAccession(id, organizationId), [id, organizationId]);
  const { reload } = result;

  const handleAction = useCallback(
    async (payload: ActionPayload) => {
      try {
        let created: LabAccession | undefined;
        if (payload.action === 'collect') {
          await collect(id, payload.body, organizationId);
        } else if (payload.action === 'receive') {
          await receive(id, payload.body, organizationId);
        } else if (payload.action === 'reject') {
          await reject(id, payload.body, organizationId);
        } else {
          created = await recollect(id, payload.body, organizationId);
        }
        setCommandError(undefined);
        if (created !== undefined) {
          setLinked(created);
        }
        reload();
      } catch (failure: unknown) {
        setCommandError(toTransportError(failure));
      }
    },
    [id, organizationId, reload],
  );

  return (
    <div style={{ display: 'grid', gap: tokens.spacing.lg }}>
      {commandError !== undefined && (
        <div data-testid="lab-detail-command-error">{StateView.forTransportError(commandError)}</div>
      )}
      {result.status === 'loading' ? (
        <LoadingState />
      ) : result.status === 'error' ? (
        StateView.forTransportError(result.error)
      ) : (
        <AccessionDetailScreen view={result.data} onAction={(payload) => void handleAction(payload)} linked={linked} />
      )}
    </div>
  );
}
