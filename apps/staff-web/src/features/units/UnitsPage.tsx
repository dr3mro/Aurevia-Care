import { useState } from 'react';
import type { ReactNode } from 'react';
import { useActiveOrganization } from '../../api/activeOrganization';
import { TransportError } from '../../api/transport';
import { useApi } from '../../api/useApi';
import { EmptyState, LoadingState, StateView } from '../../components/StateViews';
import { alphaUnitId, todayCivilDate as defaultCivilDate } from './fixtures';
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
export function UnitsPage({ unitId = alphaUnitId, todayCivilDate = defaultCivilDate }: UnitsPageProps) {
  const organizationId = useActiveOrganization();
  const [commandError, setCommandError] = useState<TransportError | undefined>(undefined);
  const result = useApi<ServiceProfileResponse>(
    () => getServiceProfile(unitId, organizationId),
    [unitId, organizationId],
  );
  const { reload } = result;

  /** Run one command, then reload the authoritative profile or show its error. */
  async function run(command: () => Promise<ServiceProfileResponse>): Promise<void> {
    try {
      await command();
      setCommandError(undefined);
      reload();
    } catch (failure: unknown) {
      setCommandError(asTransportError(failure));
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
        onPropose={(draft: ProfileDraft) => {
          void run(() => proposeServiceProfile(unitId, profile.version, draft, organizationId));
        }}
        onApprove={() => {
          void run(() => approveServiceProfile(unitId, profile.version, organizationId));
        }}
        onEnable={() => {
          void run(() => setUnitEnabled(unitId, profile.version, true, organizationId));
        }}
        onDisable={() => {
          void run(() => setUnitEnabled(unitId, profile.version, false, organizationId));
        }}
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
