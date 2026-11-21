import { useState } from 'react';
import type { ReactNode } from 'react';
import { useActiveOrganization } from '../../api/activeOrganization';
import { TransportError } from '../../api/transport';
import { useApi } from '../../api/useApi';
import { EmptyState, LoadingState, StateView } from '../../components/StateViews';
import {
  approveServiceProfile,
  getServiceProfile,
  proposeServiceProfile,
  setUnitEnabled,
  toServiceProfile,
} from './api';
import type { ServiceProfileResponse } from './api';
import { UnitsScreen } from './UnitsScreen';
import type { ProfileDraft } from './types';

/** Synthetic placeholder used until a real unit directory is published. */
const DEFAULT_UNIT_ID = 'synthetic-unit-001';

/** Props for the API-backed care-unit service profile page. */
export interface UnitsPageProps {
  unitId?: string;
  todayCivilDate?: string;
}

/** Normalise any unexpected command rejection to a typed transport failure. */
function asTransportError(failure: unknown): TransportError {
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
 * Load one tenant's service profile, render its seven resilient states, and
 * dispatch change, approval and enable commands through the typed client. A
 * failed command is shown above the editor so the person's draft is retained.
 */
export function UnitsPage({
  unitId = DEFAULT_UNIT_ID,
  // Derive "today" from the clock at the boundary; a fixed fixture date would
  // accept past effective dates in the running app.
  todayCivilDate = new Date().toISOString().slice(0, 10),
}: UnitsPageProps) {
  const organizationId = useActiveOrganization();
  const [commandError, setCommandError] = useState<TransportError | undefined>(undefined);
  const result = useApi<ServiceProfileResponse>(
    () => getServiceProfile(unitId, organizationId),
    [unitId, organizationId],
  );
  const { reload } = result;

  /**
   * Run one command, then reload the authoritative profile or show its error.
   *
   * @returns `true` only when the command succeeded, so the screen never
   * announces a success the page did not observe.
   */
  async function run(command: () => Promise<ServiceProfileResponse>): Promise<boolean> {
    try {
      await command();
      setCommandError(undefined);
      reload();
      return true;
    } catch (failure: unknown) {
      setCommandError(asTransportError(failure));
      return false;
    }
  }

  /** Build the body of the page from the single tenant-scoped load result. */
  function content(): ReactNode {
    if (result.status === 'loading') {
      return <LoadingState />;
    }
    if (result.status === 'error') {
      return StateView.forTransportError(result.error);
    }
    const profile = toServiceProfile(result.data);
    if (profile.version === 0) {
      return <EmptyState />;
    }
    return (
      <UnitsScreen
        profile={profile}
        todayCivilDate={todayCivilDate}
        onPropose={(draft: ProfileDraft) =>
          run(() => proposeServiceProfile(unitId, profile.version, draft, organizationId))
        }
        onApprove={() => run(() => approveServiceProfile(unitId, profile.version, organizationId))}
        onEnable={() =>
          run(() =>
            setUnitEnabled(
              unitId,
              profile.version,
              true,
              profile.details.queueOwner,
              organizationId,
            ),
          )
        }
        onDisable={() =>
          run(() =>
            setUnitEnabled(
              unitId,
              profile.version,
              false,
              profile.details.queueOwner,
              organizationId,
            ),
          )
        }
      />
    );
  }

  return (
    <div style={{ display: 'grid', gap: 0 }}>
      {commandError === undefined ? null : (
        <div data-testid="units-command-error">{StateView.forTransportError(commandError)}</div>
      )}
      {content()}
    </div>
  );
}
