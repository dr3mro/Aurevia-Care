import { defined } from '../../../components/invariant';
import { syntheticOrganizations } from '../../../fixtures/synthetic';
import type { DeliveryEvidence, OrderResultLink, TrackedOrder } from './domain';

/** Two synthetic tenants; the same local order ids are reused across them. */
export const alphaOrganizationId = defined(syntheticOrganizations[0], 'alpha organization').id;
export const betaOrganizationId = defined(syntheticOrganizations[1], 'beta organization').id;

/** Deterministic, clearly synthetic delivery proof in UTC. */
const deliveredEvidence: DeliveryEvidence = {
  mode: 'electronic',
  deliveredAt: '2026-03-09T08:15:00Z',
  partnerReference: 'SYN-LAB-4821',
  timeZone: 'UTC',
};

/** A synthetic released result linked to a lab order. */
export const syntheticResult: OrderResultLink = {
  resultId: 'synthetic-result-4821',
  label: 'CBC result',
};

/** A local draft with no partner interaction yet. */
export const draftOrder: TrackedOrder = {
  organizationId: alphaOrganizationId,
  id: 'order-draft',
  display: 'Complete blood count',
  status: 'draft',
  delivery: null,
  result: null,
  cancelRequested: false,
};

/** A placed order whose electronic send has not completed. */
export const placedOrder: TrackedOrder = {
  organizationId: alphaOrganizationId,
  id: 'order-placed',
  display: 'Fasting glucose',
  status: 'placed',
  delivery: { mode: 'electronic', deliveredAt: null, partnerReference: null, timeZone: 'UTC' },
  result: null,
  cancelRequested: false,
};

/** A delivered lab order with partner evidence. */
export const deliveredOrder: TrackedOrder = {
  organizationId: alphaOrganizationId,
  id: 'order-delivered',
  display: 'Complete blood count',
  status: 'delivered',
  delivery: deliveredEvidence,
  result: null,
  cancelRequested: false,
};

/** An accepted order: cancelling it now sends a request to the partner. */
export const acceptedOrder: TrackedOrder = {
  organizationId: alphaOrganizationId,
  id: 'order-accepted',
  display: 'CT abdomen with contrast',
  status: 'accepted',
  delivery: { ...deliveredEvidence, mode: 'both', partnerReference: 'SYN-RAD-7710' },
  result: null,
  cancelRequested: false,
};

/** A collected specimen, still before any result. */
export const collectedOrder: TrackedOrder = {
  organizationId: alphaOrganizationId,
  id: 'order-collected',
  display: 'Fasting glucose',
  status: 'collected_or_scheduled',
  delivery: deliveredEvidence,
  result: null,
  cancelRequested: false,
};

/** A resulted order carrying a link to its result. */
export const resultedOrder: TrackedOrder = {
  organizationId: alphaOrganizationId,
  id: 'order-resulted',
  display: 'Complete blood count',
  status: 'resulted',
  delivery: deliveredEvidence,
  result: syntheticResult,
  cancelRequested: false,
};

/** An accepted order with a cancellation request already awaiting the partner. */
export const cancelRequestedOrder: TrackedOrder = {
  ...acceptedOrder,
  id: 'order-cancel-requested',
  cancelRequested: true,
};

/** A cancelled order; no further command applies. */
export const cancelledOrder: TrackedOrder = {
  organizationId: alphaOrganizationId,
  id: 'order-cancelled',
  display: 'Chest X-ray',
  status: 'cancelled',
  delivery: deliveredEvidence,
  result: null,
  cancelRequested: false,
};
