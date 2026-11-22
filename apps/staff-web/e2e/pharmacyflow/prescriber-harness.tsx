import { StrictMode, useCallback, useEffect, useState } from 'react';
import type { ReactElement } from 'react';
import { createRoot } from 'react-dom/client';
import { useActiveOrganization } from '../../src/api/activeOrganization';
import { getPharmacyStatus } from '../../src/features/pharmacyinbox/api';
import {
  answerClarification,
  approveSubstitution,
  getClarificationThread,
  getSubstitutionProposal,
} from '../../src/features/prescriberstatus/api';
import { ClarificationReply } from '../../src/features/prescriberstatus/ClarificationReply';
import { PharmacyStatusTimeline } from '../../src/features/prescriberstatus/PharmacyStatusTimeline';
import { SubstitutionReview } from '../../src/features/prescriberstatus/SubstitutionReview';
import type {
  AnswerDraft,
  DecisionDraft,
} from '../../src/features/prescriberstatus/types';
import type {
  ProposalViewResponse,
  ThreadViewResponse,
} from '../../src/features/prescriberstatus/types';
import type { StatusViewResponse } from '../../src/features/pharmacyinbox/types';
import { I18nProvider, useI18n } from '../../src/i18n/I18nProvider';
import { ThemeProvider } from '../../src/theme/ThemeProvider';

/**
 * Browser-only harness for the prescriber's pharmacy review (RX-02, WEB-09).
 *
 * The status timeline, clarification reply and substitution review are exported
 * but not routed in the staff portal yet, so this harness mounts them through
 * their real providers and real API client. A dev-only prescribe-and-route
 * control seeds the shared synthetic prescription; Vite dev serves the harness
 * and it is never part of the production build. Every value is synthetic.
 */

/** Opaque identifier of the one synthetic prescription in this journey. */
const prescriptionId = 'rx-synthetic-001';

/** Prescriber view backed by the real client and the shared synthetic API. */
function PrescriberView(): ReactElement {
  const organizationId = useActiveOrganization();
  const { locale, toggleLocale } = useI18n();
  const [status, setStatus] = useState<StatusViewResponse | undefined>(undefined);
  const [thread, setThread] = useState<ThreadViewResponse | undefined>(undefined);
  const [proposal, setProposal] = useState<ProposalViewResponse | undefined>(undefined);
  const [routeStatus, setRouteStatus] = useState('Not routed');

  /** Reload all three projections; absent data simply leaves a view unmounted. */
  const load = useCallback(async (): Promise<void> => {
    const [nextStatus, nextThread, nextProposal] = await Promise.allSettled([
      getPharmacyStatus(prescriptionId, organizationId),
      getClarificationThread('thread-synthetic-001', organizationId),
      getSubstitutionProposal('proposal-synthetic-001', organizationId),
    ]);
    setStatus(nextStatus.status === 'fulfilled' ? nextStatus.value : undefined);
    setThread(nextThread.status === 'fulfilled' ? nextThread.value : undefined);
    setProposal(nextProposal.status === 'fulfilled' ? nextProposal.value : undefined);
  }, [organizationId]);

  useEffect(() => {
    void load();
  }, [load]);

  /** Attest and route through the same intercepted endpoints the API exposes. */
  async function prescribeAndRoute(): Promise<void> {
    const common = {
      'Content-Type': 'application/json',
      'X-Active-Organization': organizationId,
      'X-XSRF-TOKEN': 'synthetic-csrf-token',
    };
    await fetch(`/api/v1/prescriptions/${prescriptionId}/transmit`, {
      method: 'POST',
      headers: { ...common, 'If-Match': '1', 'Idempotency-Key': 'synthetic-route-1' },
      body: JSON.stringify({ channel: 'synthetic-pharmacy' }),
    });
    await fetch(`/api/v1/prescriptions/${prescriptionId}/confirm-delivery`, {
      method: 'POST',
      headers: { ...common, 'If-Match': '2', 'Idempotency-Key': 'synthetic-route-2' },
      body: JSON.stringify({ submissionId: 'submission-synthetic-001' }),
    });
    setRouteStatus('Delivery confirmed');
    await load();
  }

  async function onAnswer(draft: AnswerDraft): Promise<void> {
    if (thread === undefined) {
      return;
    }
    await answerClarification(
      thread.thread.id,
      thread.thread.version,
      draft.answerBody,
      draft.amendedRevisionId,
      organizationId,
    );
    await load();
  }

  async function onApprove(draft: DecisionDraft): Promise<void> {
    if (proposal === undefined) {
      return;
    }
    await approveSubstitution(
      proposal.proposal.id,
      proposal.proposal.version,
      draft.attestation,
      draft.note,
      organizationId,
    );
    await load();
  }

  return (
    <div style={{ display: 'grid', gap: '1rem' }}>
      <button type="button" onClick={toggleLocale}>
        {locale === 'en' ? 'العربية' : 'English'}
      </button>
      <button type="button" data-testid="prescribe-route" onClick={() => void prescribeAndRoute()}>
        Prescribe and route to the synthetic pharmacy
      </button>
      <button type="button" data-testid="refresh" onClick={() => void load()}>
        Refresh pharmacy status
      </button>
      <output data-testid="route-status">{routeStatus}</output>
      {status === undefined ? null : (
        <PharmacyStatusTimeline status={status} locale={locale} timeZone="UTC" />
      )}
      {thread === undefined ? null : (
        <ClarificationReply
          thread={thread}
          locale={locale}
          timeZone="UTC"
          onSend={(draft) => void onAnswer(draft)}
        />
      )}
      {proposal === undefined ? null : (
        <SubstitutionReview
          view={proposal}
          locale={locale}
          timeZone="UTC"
          onApprove={(draft) => void onApprove(draft)}
          onDecline={() => undefined}
        />
      )}
    </div>
  );
}

/** Mount the harness tree with the same providers the staff portal uses. */
function Harness(): ReactElement {
  return (
    <StrictMode>
      <ThemeProvider>
        <I18nProvider>
          <PrescriberView />
        </I18nProvider>
      </ThemeProvider>
    </StrictMode>
  );
}

const container = document.getElementById('pharmacy-prescriber-harness');
if (container === null) {
  throw new Error('pharmacy-prescriber-harness container is required');
}
createRoot(container).render(<Harness />);
