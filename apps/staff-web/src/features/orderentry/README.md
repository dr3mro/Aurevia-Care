# Order entry parts

The order entry feature owns three reusable, bilingual parts of the clinician
order flow (LAB-01, RAD-01, WEB-09): placing an order, choosing how it is sent
and tracking it with results, cancel and amend. It owns **no screen**: the chart
screen is owned by the chart feature, and that screen mounts each part and
supplies the tenant-scoped data and callbacks. This module exports the three
parts from one barrel so there is a single import path for the owning screen.

Import every part from the single barrel:

```ts
import { DeliveryChoice, OrderEntryPanel, OrdersTab } from '../orderentry';
```

That path is for a screen directly under `src/features/<feature>/`, such as
`src/features/chart/ChartScreen.tsx`. From a nested screen use the relative
path to `features/orderentry/index.ts` instead. Only the three components are
exported from the barrel; if the chart screen needs a prop type or a domain
type, import it from the part's own sub-module:

```ts
import type { OrderEntryPanelProps } from '../orderentry/entry/OrderEntryPanel';
import type { DeliveryChoiceProps } from '../orderentry/delivery/DeliveryChoice';
import type { OrdersTabProps } from '../orderentry/tracking/OrdersTab';
import type { CatalogItem, ActiveOrder, DeliveryMode } from '../orderentry/entry/domain';
import type { SendResult } from '../orderentry/delivery/domain';
import type { TrackedOrder, OrderResultLink } from '../orderentry/tracking/domain';
```

## Chart screen placement

Owner: the chart feature. The chart feature is a separate task and is **not**
edited here; the lines below are the exact import and placement the owning task
uses. All three parts are embedded in the existing chart screen, which already
knows the active patient, encounter, facility and role. They render in the order
below — entry where the encounter is known, delivery as the confirm step of the
draft flow, and the orders tab as the tracking section beside the documents.

Exact import line (at the top of `src/features/chart/ChartScreen.tsx`):

```ts
import { DeliveryChoice, OrderEntryPanel, OrdersTab } from '../orderentry';
```

### 1. Order entry panel — in the chart, after the longitudinal chart

Mount the panel inside the chart, where the active encounter is known, before
the note editor and document list. It shows allergies and active orders so a
duplicate is noticed, and a stale version keeps the typed work with compare,
reload or copy.

```tsx
<OrderEntryPanel
  organizationId={organizationId}
  patientLocalId={activeChart.localId}
  encounterLocalId={syntheticEncounterLocalId}
  facilityId={facilityId}
  role={role}
  catalog={orderableCatalog}
  allergies={activeAllergies}
  activeOrders={activeOrders}
  savedDraft={savedDraft}
  onSaveDraft={saveOrderDraft}
  onReload={reloadOrderDraft}
/>
```

- `organizationId`, `facilityId` and `role`: the active tenant, facility and
  signed-in clinician; ordering privilege is checked inside the panel.
- `patientLocalId`, `encounterLocalId`: the chart's local identifiers. Local IDs
  are shared across tenants, so they are always paired with `organizationId`.
- `catalog`, `allergies`, `activeOrders`: synthetic, tenant-scoped inputs for
  the versioned catalog, the duplicate warning and the allergy banner.
- `savedDraft`: the draft opened from the chart, the baseline version for
  `If-Match`; omit it for a new order.
- `onSaveDraft(content, expectedVersion)` and `onReload()`: supplied by the
  owning screen from the order API; a stale version is surfaced by the panel.

### 2. Delivery choice — the confirm step of the draft flow

Render the choice while the selected draft is being sent, before the order
moves to the tracked list. The screen supplies the partner name and the send
outcome and performs the place/retry/reconcile calls; a failed send offers
print instead, and an ambiguous send is reconciled before any resend. The print
preview is opened through an injected callback, never by a direct import.

```tsx
<DeliveryChoice
  organizationId={organizationId}
  partnerName={partnerName}
  mode={deliveryMode}
  result={sendResult}
  onConfirm={confirmDelivery}
  onChangeMode={changeDeliveryMode}
  onRetry={retryDelivery}
  onReconcile={reconcileDelivery}
  onOpenPrintPreview={openPrintPreview}
/>
```

- `partnerName`: destination partner display name, isolated by script.
- `mode`: the mode captured by the draft (`electronic`, `print` or `both`).
- `result`: the send outcome supplied by the owning screen from the delivery
  API; it drives the timeline or the worded recovery choice.
- `onConfirm(mode)`: the screen places the order with that mode.
- `onRetry()` is offered only when the screen marks the failure safe to resend;
  `onReconcile()` handles an ambiguous send instead of a blind resend.
- `onOpenPrintPreview()`: injected by the screen (wired to the print feature's
  `PrintOrderButton`) so this step never imports the print feature.

### 3. Orders tab — the tracking section beside the documents

Mount the tab as the order-tracking section of the chart, beside the document
list. Each order shows its status, delivery evidence, a link to the result when
one exists, and reason-backed cancel and amend actions.

```tsx
<OrdersTab
  organizationId={organizationId}
  orders={trackedOrders}
  role={role}
  onCancel={cancelOrder}
  onAmend={amendOrder}
  onOpenResult={openResult}
/>
```

- `orders`: the tenant's tracked orders from the status API.
- `role`: only an ordering clinician may cancel or amend.
- `onCancel(order, reason)` and `onAmend(order, reason)`: require a reason;
  cancel after partner acceptance sends a request instead of cancelling.
- `onOpenResult(result)`: opens the linked result when one exists.

## What this feature does not do

It does not add routes, edit the chart or print features, call an order or
print API itself, or expose real patient data. Every example above uses
synthetic identifiers. The owning chart task wires these parts to its own API
and supplies the callbacks; adding them to that screen is a separate task.
