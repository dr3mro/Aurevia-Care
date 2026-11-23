import { useState } from 'react';
import { useActiveOrganization } from '../../api/activeOrganization';
import { TransportError } from '../../api/transport';
import { ApiPage } from '../../components/ApiPage';
import { Button } from '../../components/Button';
import { defined } from '../../components/invariant';
import { StateView } from '../../components/StateViews';
import type { StateViewActions } from '../../components/StateViews';
import { useI18n } from '../../i18n/I18nProvider';
import { useBreakpoint } from '../../theme/useBreakpoint';
import { listQueues, recordQueueAction } from './api';
import { hasReason } from './domain';
import { operationsText } from './labels';
import { QueuesScreen } from './QueuesScreen';
import type { QueueDraft, QueuesView } from './types';

/** Normalise an unexpected command rejection to the typed transport failure. */
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
 * Tenant-scoped operations page (ADM-02, WEB-09). A reason draft and the draft
 * behind a failed command live here, not in the screen, so a 409/412 can show
 * the conflict state without discarding typed work. Recovery actions are wired
 * explicitly instead of relying on inert defaults.
 */
export function QueuesPage() {
  const organizationId = useActiveOrganization();
  const { locale } = useI18n();
  const breakpoint = useBreakpoint();
  const [draft, setDraft] = useState<QueueDraft | undefined>(undefined);
  const [reasonError, setReasonError] = useState<string | undefined>(undefined);
  const [commandError, setCommandError] = useState<TransportError | undefined>(undefined);
  const [failedDraft, setFailedDraft] = useState<QueueDraft | undefined>(undefined);
  const [recoveryNotice, setRecoveryNotice] = useState<string | undefined>(undefined);
  const [generation, setGeneration] = useState(0);
  const [draftOrganization, setDraftOrganization] = useState(organizationId);

  // A tenant switch clears every draft synchronously during render. The page
  // stays mounted while useApi refetches, so without this a reason typed for
  // one tenant could be reopened and sent under the next tenant.
  if (draftOrganization !== organizationId) {
    setDraftOrganization(organizationId);
    setDraft(undefined);
    setFailedDraft(undefined);
    setReasonError(undefined);
    setCommandError(undefined);
    setRecoveryNotice(undefined);
  }

  const reload = (): void => setGeneration((value) => value + 1);

  const resource = {
    label: operationsText(locale, 'title'),
    load: () => listQueues(organizationId),
    deps: [organizationId, generation],
  };

  async function submit(next: QueueDraft): Promise<void> {
    if (!hasReason(next.reason)) {
      setReasonError(operationsText(locale, 'reasonRequired'));
      return;
    }
    try {
      await recordQueueAction(next, organizationId);
      setDraft(undefined);
      setReasonError(undefined);
      setCommandError(undefined);
      setFailedDraft(undefined);
      reload();
    } catch (failure: unknown) {
      setFailedDraft(next);
      setCommandError(toTransportError(failure));
    }
  }

  const actions: StateViewActions = {
    onReload: () => {
      setCommandError(undefined);
      setReasonError(undefined);
      reload();
    },
    onRetry: () => {
      if (failedDraft !== undefined) {
        void submit(failedDraft);
      } else {
        reload();
      }
    },
    onCompare: () => {
      setRecoveryNotice(
        operationsText(locale, 'compareNotice').replace('{reason}', draft?.reason ?? ''),
      );
    },
    onCopy: () => {
      setRecoveryNotice(
        operationsText(locale, 'copyNotice').replace('{reason}', draft?.reason ?? ''),
      );
    },
  };

  return (
    <>
      {recoveryNotice !== undefined ? <p role="status">{recoveryNotice}</p> : null}
      <ApiPage<QueuesView>
        resource={resource}
        actions={actions}
        isEmpty={(view) => view.rows.length === 0}
      >
        {(view) => (
          <div className="page" data-operations-page data-breakpoint={breakpoint}>
            {commandError !== undefined ? (
              <div data-testid="operations-command-error">
                {StateView.forTransportError(commandError, actions)}
                <Button
                  onClick={() => {
                    void submit(defined(failedDraft, 'failed queue draft'));
                  }}
                >
                  {operationsText(locale, 'retryCommand')}
                </Button>
              </div>
            ) : null}
            <QueuesScreen
              view={view}
              draft={draft}
              reasonError={reasonError}
              onDraftChange={(next) => {
                setDraft(next);
                setReasonError(undefined);
              }}
              onSubmit={(next) => {
                void submit(next);
              }}
            />
          </div>
        )}
      </ApiPage>
    </>
  );
}
