import { StrictMode, useState } from 'react';
import type { ReactElement } from 'react';
import { createRoot } from 'react-dom/client';
import type { Role } from '../../src/app/navigation';
import { I18nProvider, useI18n } from '../../src/i18n/I18nProvider';
import { ThemeProvider } from '../../src/theme/ThemeProvider';
import { DeliveryChoice, OrderEntryPanel, OrdersTab } from '../../src/features/orderentry';
import type { DeliveryMode, OrderDraftContent, SavedOrderDraft } from '../../src/features/orderentry/entry/domain';
import {
  entryAlphaOrganizationId,
  syntheticAllergies,
  syntheticCatalog,
  syntheticEncounterLocalId,
  syntheticFacilityId,
  syntheticPatientLocalId,
} from '../../src/features/orderentry/entry/fixtures';
import type { DeliveryMilestone, SendResult } from '../../src/features/orderentry/delivery/domain';
import {
  syntheticArabicPartnerName,
  syntheticPartnerName,
  syntheticPrintTimeline,
  syntheticTimeline,
} from '../../src/features/orderentry/delivery/fixtures';
import type {
  OrderResultLink,
  TrackedOrder,
  TrackedOrderStatus,
} from '../../src/features/orderentry/tracking/domain';

/**
 * Dev-only browser harness for the exported order-entry parts (ORDER-09).
 *
 * The chart screen that owns these parts in production belongs to another
 * feature, so this harness mounts them through their real providers with
 * synthetic fixtures. It is served by the Vite dev server and never enters the
 * production build. Every command goes through the real transport to the
 * browser test's stub; no patient record, partner or credential is contacted.
 */

/** The signed-in clinician role that alone may place and amend orders. */
const clinicianRole: Role = 'clinician';

/** Stable synthetic identifiers used only inside this harness. */
const deliveredAt = '2026-03-09T08:15:00Z';
const partnerReference = 'SYN-REF-1';

/** Post one JSON command through the real transport to the stubbed boundary. */
async function post(path: string, body: Record<string, unknown>): Promise<Response> {
  return fetch(path, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-XSRF-TOKEN': 'synthetic-csrf-token',
    },
    body: JSON.stringify(body),
  });
}

/** The workstation that owns entry, delivery confirmation and order tracking. */
function OrderWorkstation(): ReactElement {
  const { locale, toggleLocale } = useI18n();
  const [mode, setMode] = useState<DeliveryMode>('electronic');
  const [result, setResult] = useState<SendResult>({ state: 'idle' });
  const [orders, setOrders] = useState<readonly TrackedOrder[]>([]);
  const [draft, setDraft] = useState<SavedOrderDraft | null>(null);
  const [printOpened, setPrintOpened] = useState(false);
  const [lastAction, setLastAction] = useState('');
  const [openedResult, setOpenedResult] = useState('');

  /** Commit a placed order from the saved draft as tenant-scoped tracking data. */
  async function place(
    saved: SavedOrderDraft,
    sentMode: DeliveryMode,
    status: TrackedOrderStatus,
  ): Promise<void> {
    const order: TrackedOrder = {
      organizationId: saved.organizationId,
      id: saved.id,
      display: saved.content.items.map((item) => item.name).join(', '),
      status,
      // A printed order has no partner transmission proof; an electronic one has
      // a copyable reference. The distinction is the delivery-status wording.
      delivery: {
        mode: sentMode,
        deliveredAt: sentMode === 'print' ? null : deliveredAt,
        partnerReference: sentMode === 'print' ? null : partnerReference,
        timeZone: 'UTC',
      },
      result: null,
      cancelRequested: false,
    };
    await post(`/api/v1/orders/${order.id}/place`, { mode: sentMode });
    setOrders((current) => [...current, order]);
  }

  /** Save a draft and reset the delivery step to the mode captured in it. */
  async function saveDraft(
    content: OrderDraftContent,
    expectedVersion: number,
  ): Promise<SavedOrderDraft> {
    const response = await post('/api/v1/orders/drafts', { content, expectedVersion });
    const payload = (await response.json()) as { id: string; version: number };
    const saved: SavedOrderDraft = {
      organizationId: entryAlphaOrganizationId,
      id: payload.id,
      version: payload.version,
      content,
    };
    setDraft(saved);
    setMode(content.deliveryMode);
    setResult({ state: 'idle' });
    setPrintOpened(false);
    return saved;
  }

  /** Reload the saved draft behind the version guard, keeping local work visible. */
  async function reload(): Promise<SavedOrderDraft> {
    if (draft === null) {
      throw new Error('There is no saved draft to reload in this harness');
    }
    const response = await fetch(`/api/v1/orders/drafts/${draft.id}`);
    const payload = (await response.json()) as { id: string; version: number };
    const saved: SavedOrderDraft = {
      organizationId: entryAlphaOrganizationId,
      id: payload.id,
      version: payload.version,
      content: draft.content,
    };
    setDraft(saved);
    return saved;
  }

  /** Send the active draft; a refusal is shown as a worded recovery choice. */
  async function send(sentMode: DeliveryMode): Promise<void> {
    if (draft === null) {
      return;
    }
    setResult({ state: 'sending' });
    const response = await post(`/api/v1/orders/${draft.id}/deliver`, { mode: sentMode });
    const payload = (await response.json()) as {
      timeline?: DeliveryMilestone[];
      retrySafe?: boolean;
      correlationId?: string;
    };
    if (!response.ok) {
      setResult({
        state: 'failed',
        retrySafe: payload.retrySafe === true,
        ...(payload.correlationId === undefined ? {} : { correlationId: payload.correlationId }),
      });
      return;
    }
    setResult({ state: 'placed', timeline: payload.timeline ?? syntheticTimeline });
    await place(draft, sentMode, 'delivered');
  }

  /** Reconcile an ambiguous send without placing a second order. */
  async function reconcile(): Promise<void> {
    if (draft === null) {
      return;
    }
    const response = await post(`/api/v1/orders/${draft.id}/reconcile`, {});
    const payload = (await response.json()) as { timeline?: DeliveryMilestone[] };
    setResult({ state: 'placed', timeline: payload.timeline ?? syntheticTimeline });
  }

  /** Switch to print after a failed send: place once, with no electronic proof. */
  async function openPrintPreview(): Promise<void> {
    if (draft === null) {
      return;
    }
    setMode('print');
    setPrintOpened(true);
    await place(draft, 'print', 'placed');
    setResult({ state: 'placed', timeline: syntheticPrintTimeline });
  }

  /** Send a reason-backed cancellation and mark the tracked order cancelled. */
  async function cancelOrder(order: TrackedOrder, reason: string): Promise<void> {
    await post(`/api/v1/orders/${order.id}/cancel`, { reason });
    setOrders((current) =>
      current.map((item) =>
        item.id === order.id ? { ...item, status: 'cancelled', cancelRequested: false } : item,
      ),
    );
    setLastAction(`cancel:${order.id}`);
  }

  /** Send a reason-backed amendment without changing the workflow status. */
  async function amendOrder(order: TrackedOrder, reason: string): Promise<void> {
    await post(`/api/v1/orders/${order.id}/amend`, { reason });
    setLastAction(`amend:${order.id}`);
  }

  /** Open a linked result through the injected callback, never a direct import. */
  function openResult(result: OrderResultLink): void {
    setOpenedResult(result.resultId);
  }

  return (
    <>
      <button type="button" onClick={toggleLocale}>
        {locale === 'en' ? 'العربية' : 'English'}
      </button>
      <OrderEntryPanel
        organizationId={entryAlphaOrganizationId}
        patientLocalId={syntheticPatientLocalId}
        encounterLocalId={syntheticEncounterLocalId}
        facilityId={syntheticFacilityId}
        role={clinicianRole}
        catalog={syntheticCatalog}
        allergies={syntheticAllergies}
        activeOrders={[]}
        savedDraft={draft ?? undefined}
        onSaveDraft={saveDraft}
        onReload={reload}
      />
      <DeliveryChoice
        organizationId={entryAlphaOrganizationId}
        partnerName={locale === 'ar' ? syntheticArabicPartnerName : syntheticPartnerName}
        mode={mode}
        result={result}
        onConfirm={(confirmed) => void send(confirmed)}
        onChangeMode={setMode}
        onRetry={() => void send(mode)}
        onReconcile={() => void reconcile()}
        onOpenPrintPreview={() => void openPrintPreview()}
      />
      <OrdersTab
        organizationId={entryAlphaOrganizationId}
        orders={orders}
        role={clinicianRole}
        onCancel={cancelOrder}
        onAmend={amendOrder}
        onOpenResult={openResult}
      />
      <output data-testid="orderentry-print">{printOpened ? `print:${draft?.id ?? ''}` : ''}</output>
      <output data-testid="orderentry-last-action">{lastAction}</output>
      <output data-testid="orderentry-result">{openedResult}</output>
    </>
  );
}

/** Mount the harness tree with the same providers the staff portal uses. */
function Harness(): ReactElement {
  return (
    <StrictMode>
      <ThemeProvider>
        <I18nProvider>
          <OrderWorkstation />
        </I18nProvider>
      </ThemeProvider>
    </StrictMode>
  );
}

const container = document.getElementById('orderentry-harness');
if (container === null) {
  throw new Error('orderentry-harness container is required');
}
createRoot(container).render(<Harness />);
