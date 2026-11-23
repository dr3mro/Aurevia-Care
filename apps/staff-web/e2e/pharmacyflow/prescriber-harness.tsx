import { StrictMode, useCallback, useEffect, useState } from 'react';
import type { ReactElement } from 'react';
import { createRoot } from 'react-dom/client';
import { useActiveOrganization } from '../../src/api/activeOrganization';
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
 * (PHARM-08, PHARM-06, RX-02, WEB-09). They are not routed in the staff portal
 * yet, so this mounts them with their real providers and drives the real typed
 * API clients. Vite dev serves it; it is never part of the production build.
 * Every subject, identifier and medicine is synthetic and every command goes
 * through the same protected boundary the screens use.
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

/** The three prescriber-facing projections read together. */
interface Projections {
  status: StatusViewResponse | null;
  thread: ThreadViewResponse | null;
  proposal: ProposalViewResponse | null;
}

/** Read all three projections; a missing one is `null`. */
async function readProjections(organizationId: string): Promise<Projections> {
  const [status, thread, proposal] = await Promise.all([
    maybeGet(() => getPharmacyStatus(prescriptionId, organizationId)),
    maybeGet(() => getClarificationThread('thread-synthetic-001', organizationId)),
    maybeGet(() => getSubstitutionProposal('proposal-synthetic-001', organizationId)),
  ]);
  return { status, thread, proposal };
}

/** The prescriber's workstation: route, review facts, answer and decide. */
function PrescriberView(): ReactElement {
  // The active tenant defaults to the synthetic facility, so this matches the
  // tenant used to build every fixture in the shared API stand-in.
  const organizationId = useActiveOrganization();
  const { locale, toggleLocale } = useI18n();
  const labels = harnessLabels[locale];
  const [status, setStatus] = useState<StatusViewResponse | null>(null);
  const [thread, setThread] = useState<ThreadViewResponse | null>(null);
  const [proposal, setProposal] = useState<ProposalViewResponse | null>(null);
  const [routeStatus, setRouteStatus] = useState('Not routed');

  /** Publish one read of the three projections into component state. */
  const apply = useCallback((next: Projections): void => {
    setStatus(next.status);
    setThread(next.thread);
    setProposal(next.proposal);
  }, []);

  /** Re-read all three projections through the real API client. */
  const refresh = useCallback(async (): Promise<void> => {
    apply(await readProjections(organizationId));
  }, [apply, organizationId]);

  // Load any already-routed facts on mount; before routing the API returns 404
  // for each projection, which `maybeGet` turns into the empty state below.
  useEffect(() => {
    let active = true;
    void readProjections(organizationId).then((next) => {
      if (active) {
        apply(next);
      }
    });
    return () => {
      active = false;
    };
  }, [apply, organizationId]);

  /** Route a synthetic prescription to the pharmacy through the boundary. */
  async function route(): Promise<void> {
    await fetch('/api/v1/pharmacy/inbox/route', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Active-Organization': organizationId,
        'X-XSRF-TOKEN': 'synthetic-csrf-token',
      },
      body: JSON.stringify({ prescriptionId, patientId: 'SYN-PATIENT-001' }),
    });
    await refresh();
  }

  /** Attest and route, then show the confirmed delivery to the prescriber. */
  async function prescribeAndRoute(): Promise<void> {
    await route();
    // The harness API records the transmit and delivery-confirm commands behind
    // the single route command; the visible status is the confirmed delivery.
    setRouteStatus('Delivery confirmed');
  }

  /** Answer the pharmacy's question on its current thread version. */
  function sendAnswer(draft: AnswerDraft): void {
    if (thread === null) {
      return;
    }
    void answerClarification(thread.thread.id, thread.thread.version, draft, organizationId).then(
      () => refresh(),
    );
  }

  /** Approve the substitution only with the prescriber's explicit attestation. */
  function approve(draft: DecisionDraft): void {
    if (proposal === null) {
      return;
    }
    void approveSubstitution(
      proposal.proposal.id,
      proposal.proposal.version,
      draft,
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
    <div style={{ display: 'grid', gap: '1rem' }}>
      <button type="button" onClick={toggleLocale}>
        {locale === 'en' ? 'العربية' : 'English'}
      </button>
      <button type="button" data-testid="prescribe-route" onClick={() => void prescribeAndRoute()}>
        {labels.route}
      </button>
      <button type="button" data-testid="refresh" onClick={() => void refresh()}>
        {labels.refresh}
      </button>
      <output data-testid="route-status">{routeStatus}</output>
      {status === null ? (
        <p data-testid="no-status">{labels.waiting}</p>
      ) : (
        <PharmacyStatusTimeline status={status} locale={locale} timeZone="UTC" />
      )}
      {thread === null ? null : (
        <ClarificationReply thread={thread} locale={locale} timeZone="UTC" onSend={sendAnswer} />
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

const wrapper = document.getElementById('pharmacy-prescriber-harness');
const container = document.getElementById('prescriber-harness');
if (wrapper === null || container === null) {
  throw new Error('prescriber-harness container is required');
}
createRoot(container).render(<Harness />);
