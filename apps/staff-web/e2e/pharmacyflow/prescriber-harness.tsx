import { StrictMode, useState } from 'react';
import type { ReactElement } from 'react';
import { createRoot } from 'react-dom/client';
import { syntheticFacility } from '../../src/fixtures/synthetic';
import { getPharmacyStatus } from '../../src/features/pharmacyinbox/api';
import type { StatusViewResponse } from '../../src/features/pharmacyinbox/types';
import { ClarificationReply } from '../../src/features/prescriberstatus/ClarificationReply';
import { PharmacyStatusTimeline } from '../../src/features/prescriberstatus/PharmacyStatusTimeline';
import { SubstitutionReview } from '../../src/features/prescriberstatus/SubstitutionReview';
import {
  answerClarification,
  approveSubstitution,
  declineSubstitution,
  getClarificationThread,
  getSubstitutionProposal,
} from '../../src/features/prescriberstatus/api';
import type {
  AnswerDraft,
  DecisionDraft,
  ProposalViewResponse,
  ThreadViewResponse,
} from '../../src/features/prescriberstatus/types';
import { I18nProvider, useI18n } from '../../src/i18n/I18nProvider';
import { ThemeProvider } from '../../src/theme/ThemeProvider';

/**
 * Dev-only browser harness for the exported prescriber status components
 * (PHARM-06, RX-02). They are not routed in the staff portal yet, so this mounts
 * them with their real providers and drives the real typed API clients. Vite dev
 * serves it; it is never part of the production build. Every value is synthetic
 * and every command goes through the same protected boundary the screens use.
 */

/** Opaque identifiers shared with the synthetic pharmacy API harness. */
const prescriptionId = 'rx-synthetic-001';

/** Harness controls in both locales; no clinical wording is hard-coded here. */
const harnessLabels = {
  en: {
    route: 'Prescribe and route',
    refresh: 'Refresh status',
    waiting: 'No prescription has been routed yet.',
  },
  ar: {
    route: 'وصف وتوجيه',
    refresh: 'تحديث الحالة',
    waiting: 'لم يتم توجيه أي وصفة بعد.',
  },
} as const;

/** Read one optional view; a synthetic 404 before creation simply hides it. */
async function maybeGet<T>(read: () => Promise<T>): Promise<T | null> {
  try {
    return await read();
  } catch {
    return null;
  }
}

/** The prescriber's workstation: route, review facts, answer and decide. */
function PrescriberView(): ReactElement {
  const { locale, toggleLocale } = useI18n();
  const labels = harnessLabels[locale];
  const organizationId = syntheticFacility.organizationId;
  const [status, setStatus] = useState<StatusViewResponse | null>(null);
  const [thread, setThread] = useState<ThreadViewResponse | null>(null);
  const [proposal, setProposal] = useState<ProposalViewResponse | null>(null);

  /** Re-read all three projections through the real API client. */
  async function refresh(): Promise<void> {
    const [nextStatus, nextThread, nextProposal] = await Promise.all([
      maybeGet(() => getPharmacyStatus(prescriptionId, organizationId)),
      maybeGet(() => getClarificationThread('thread-synthetic-001', organizationId)),
      maybeGet(() => getSubstitutionProposal('proposal-synthetic-001', organizationId)),
    ]);
    setStatus(nextStatus);
    setThread(nextThread);
    setProposal(nextProposal);
  }

  /** Route a synthetic prescription to the pharmacy through the boundary. */
  async function route(): Promise<void> {
    await fetch('/api/v1/pharmacy/inbox/route', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-XSRF-TOKEN': 'synthetic-csrf-token',
      },
      body: JSON.stringify({ prescriptionId, patientId: 'SYN-PATIENT-001' }),
    });
    await refresh();
  }

  /** Answer the pharmacy's question on its current thread version. */
  function sendAnswer(draft: AnswerDraft): void {
    if (thread === null) {
      return;
    }
    void answerClarification(
      thread.thread.id,
      thread.thread.version,
      draft.answerBody,
      draft.amendedRevisionId,
      organizationId,
    ).then(() => refresh());
  }

  /** Approve the substitution only with the prescriber's explicit attestation. */
  function approve(draft: DecisionDraft): void {
    if (proposal === null) {
      return;
    }
    void approveSubstitution(
      proposal.proposal.id,
      proposal.proposal.version,
      draft.attestation,
      draft.note,
      organizationId,
    ).then(() => refresh());
  }

  /** Decline keeps the original medication and creates no signed revision. */
  function decline(draft: DecisionDraft): void {
    if (proposal === null) {
      return;
    }
    void declineSubstitution(
      proposal.proposal.id,
      proposal.proposal.version,
      draft.note,
      organizationId,
    ).then(() => refresh());
  }

  return (
    <>
      <button type="button" onClick={() => void route()}>
        {labels.route}
      </button>
      <button type="button" onClick={() => void refresh()}>
        {labels.refresh}
      </button>
      <button type="button" onClick={toggleLocale}>
        {locale === 'en' ? 'العربية' : 'English'}
      </button>
      {status === null ? (
        <p data-testid="no-status">{labels.waiting}</p>
      ) : (
        <PharmacyStatusTimeline status={status} locale={locale} timeZone="UTC" />
      )}
      {thread === null ? null : (
        <ClarificationReply
          thread={thread}
          locale={locale}
          timeZone="UTC"
          onSend={sendAnswer}
        />
      )}
      {proposal === null ? null : (
        <SubstitutionReview
          view={proposal}
          locale={locale}
          timeZone="UTC"
          onApprove={approve}
          onDecline={decline}
        />
      )}
    </>
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

const container = document.getElementById('prescriber-harness');
if (container === null) {
  throw new Error('prescriber-harness container is required');
}
createRoot(container).render(<Harness />);
