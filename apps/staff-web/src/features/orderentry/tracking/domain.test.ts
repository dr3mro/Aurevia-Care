import { describe, expect, it } from 'vitest';
import {
  canAmend,
  canCancel,
  cancelOutcome,
  hasReason,
  isAfterAcceptance,
  isTerminal,
  ordersForTenant,
} from './domain';
import type { TrackedOrder } from './domain';
import {
  acceptedOrder,
  alphaOrganizationId,
  betaOrganizationId,
  cancelRequestedOrder,
  cancelledOrder,
  collectedOrder,
  deliveredOrder,
  draftOrder,
  placedOrder,
  resultedOrder,
} from './fixtures';

const everyStatusOrder: readonly TrackedOrder[] = [
  draftOrder,
  placedOrder,
  deliveredOrder,
  acceptedOrder,
  collectedOrder,
  resultedOrder,
  cancelledOrder,
];

describe('tracked order status rules', () => {
  it('separates workflow milestones that follow partner acceptance', () => {
    for (const order of [acceptedOrder, collectedOrder, resultedOrder, cancelledOrder]) {
      expect(isAfterAcceptance(order.status)).toBe(true);
    }
    for (const order of [draftOrder, placedOrder, deliveredOrder]) {
      expect(isAfterAcceptance(order.status)).toBe(false);
    }
  });

  it('marks only resulted and cancelled orders as terminal', () => {
    expect(isTerminal(resultedOrder.status)).toBe(true);
    expect(isTerminal(cancelledOrder.status)).toBe(true);
    for (const order of [draftOrder, placedOrder, deliveredOrder, acceptedOrder, collectedOrder]) {
      expect(isTerminal(order.status)).toBe(false);
    }
  });

  it('allows cancel while the order is active and blocks terminal or requested cancellations', () => {
    for (const order of [draftOrder, placedOrder, deliveredOrder, acceptedOrder, collectedOrder]) {
      expect(canCancel(order)).toBe(true);
    }
    expect(canCancel(resultedOrder)).toBe(false);
    expect(canCancel(cancelledOrder)).toBe(false);
    expect(canCancel(cancelRequestedOrder)).toBe(false);
  });

  it('allows amend on any non-terminal order', () => {
    for (const order of [
      draftOrder,
      placedOrder,
      deliveredOrder,
      acceptedOrder,
      collectedOrder,
      cancelRequestedOrder,
    ]) {
      expect(canAmend(order)).toBe(true);
    }
    expect(canAmend(resultedOrder)).toBe(false);
    expect(canAmend(cancelledOrder)).toBe(false);
  });

  it('turns a cancel into a partner request only after acceptance', () => {
    for (const order of [draftOrder, placedOrder, deliveredOrder]) {
      expect(cancelOutcome(order)).toBe('cancelled');
    }
    for (const order of [acceptedOrder, collectedOrder]) {
      expect(cancelOutcome(order)).toBe('cancel-requested');
    }
  });

  it('requires a non-blank reason for cancel and amend', () => {
    expect(hasReason('Specimen rejected by collector')).toBe(true);
    expect(hasReason('')).toBe(false);
    expect(hasReason('   ')).toBe(false);
  });

  it('lists every status with a stable wire value', () => {
    expect(everyStatusOrder.map((order) => order.status)).toEqual([
      'draft',
      'placed',
      'delivered',
      'accepted',
      'collected_or_scheduled',
      'resulted',
      'cancelled',
    ]);
  });

  it('exposes only the active tenant’s orders', () => {
    const mixed: readonly TrackedOrder[] = [
      draftOrder,
      { ...draftOrder, id: 'beta-order', organizationId: betaOrganizationId },
      resultedOrder,
    ];
    expect(ordersForTenant(mixed, alphaOrganizationId).map((order) => order.id)).toEqual([
      draftOrder.id,
      resultedOrder.id,
    ]);
    expect(ordersForTenant(mixed, betaOrganizationId).map((order) => order.id)).toEqual([
      'beta-order',
    ]);
    expect(ordersForTenant(mixed, 'unknown-tenant')).toEqual([]);
  });
});
