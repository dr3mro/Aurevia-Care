import type { DeliveryMode } from '../entry/domain';

/**
 * One status per clinical order, using the wire vocabulary of the order-status
 * API (ORDER-04): draft, placed, delivered, accepted, collected_or_scheduled,
 * resulted or cancelled. A post-acceptance cancellation is a separate pending
 * fact, not an eighth status (LAB-01, RAD-01, XCH-03).
 */
export type TrackedOrderStatus =
  | 'draft'
  | 'placed'
  | 'delivered'
  | 'accepted'
  | 'collected_or_scheduled'
  | 'resulted'
  | 'cancelled';

/** How and when the order reached the performing partner. Synthetic only. */
export interface DeliveryEvidence {
  /** Approved delivery vocabulary reused from the order-entry domain. */
  mode: DeliveryMode;
  /** UTC instant of successful transmission; null until the send completes. */
  deliveredAt: string | null;
  /** Partner-issued transmission reference; null before the partner replies. */
  partnerReference: string | null;
  /** IANA zone used only to render `deliveredAt`. */
  timeZone: string;
}

/** A released result linked back to its order. The id is opaque and synthetic. */
export interface OrderResultLink {
  resultId: string;
  label: string;
}

/** One order shown in the patient chart's Orders tab, owned by one tenant. */
export interface TrackedOrder {
  organizationId: string;
  /** Tenant-local order identifier; never a patient identifier. */
  id: string;
  /** Latin display name captured when the order was placed. */
  display: string;
  status: TrackedOrderStatus;
  /** Delivery proof; null while the order is still a local draft. */
  delivery: DeliveryEvidence | null;
  /** Linked result; null until a result is released against this order. */
  result: OrderResultLink | null;
  /** True while a post-acceptance cancellation awaits the partner. */
  cancelRequested: boolean;
}

/** Statuses at or beyond partner acceptance, including terminal ones. */
const AFTER_ACCEPTANCE: ReadonlySet<TrackedOrderStatus> = new Set([
  'accepted',
  'collected_or_scheduled',
  'resulted',
  'cancelled',
]);

/** Statuses after which no further workflow command applies. */
const TERMINAL: ReadonlySet<TrackedOrderStatus> = new Set(['resulted', 'cancelled']);

/** Whether the partner has accepted the order at this status. */
export function isAfterAcceptance(status: TrackedOrderStatus): boolean {
  return AFTER_ACCEPTANCE.has(status);
}

/** Whether the order has reached a terminal status. */
export function isTerminal(status: TrackedOrderStatus): boolean {
  return TERMINAL.has(status);
}

/** Cancel is refused for terminal orders and while a request is already pending. */
export function canCancel(order: TrackedOrder): boolean {
  return !isTerminal(order.status) && !order.cancelRequested;
}

/** Amend opens a linked revision only while the order is not terminal. */
export function canAmend(order: TrackedOrder): boolean {
  return !isTerminal(order.status);
}

/**
 * Decide what a cancel means for the clinician.
 *
 * Before acceptance the order is cancelled immediately; after acceptance a
 * cancellation request goes to the partner and the order stays accepted until
 * the partner answers (XCH-03).
 */
export function cancelOutcome(order: TrackedOrder): 'cancelled' | 'cancel-requested' {
  return isAfterAcceptance(order.status) ? 'cancel-requested' : 'cancelled';
}

/** Whether a typed reason is non-blank; both cancel and amend require one. */
export function hasReason(reason: string): boolean {
  return reason.trim().length > 0;
}

/** Select only the active tenant's orders; local ids are reused across tenants. */
export function ordersForTenant(
  orders: readonly TrackedOrder[],
  organizationId: string,
): TrackedOrder[] {
  return orders.filter((order) => order.organizationId === organizationId);
}
