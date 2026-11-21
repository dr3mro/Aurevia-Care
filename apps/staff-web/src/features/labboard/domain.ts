/**
 * Wire shapes and pure rules for the laboratory turnaround board
 * (LAB-01, LAB-02, WEB-09).
 *
 * Targets and ages are supplied by the source as whole minutes; this module
 * only evaluates them. It never derives clinical meaning in the client. Every
 * value represented here is synthetic.
 */

import type { InterfaceHealth } from '../partnerboard/domain';

/** Partner interface health, reused from the partner/outage board domain. */
export type { InterfaceHealth } from '../partnerboard/domain';

/** The five workflow columns of the turnaround board, in reading order. */
export const TURNAROUND_STAGES = [
  'ordered',
  'collected',
  'in-analysis',
  'awaiting-verification',
  'released',
] as const;

/** One turnaround board column. */
export type TurnaroundStage = (typeof TURNAROUND_STAGES)[number];

/** One laboratory order card. All values are synthetic. */
export interface TurnaroundOrder {
  /** Stable, opaque domain key; never a patient identifier. */
  id: string;
  /** Opaque accession, rendered as an isolated left-to-right run. */
  accession: string;
  stage: TurnaroundStage;
  /** Whole minutes since the order was placed. */
  ageMinutes: number;
  /** Site-approved target for this stage, in whole minutes. */
  targetMinutes: number;
}

/** The synthetic board snapshot shown to staff. */
export interface TurnaroundBoard {
  interfaceHealth: InterfaceHealth;
  orders: readonly TurnaroundOrder[];
}

/** Whether an order has exceeded its stage target. Equality is on track. */
export function isOverdue(order: TurnaroundOrder): boolean {
  return order.ageMinutes > order.targetMinutes;
}

/** Whole minutes an order is beyond target, floored at zero. */
export function minutesBeyondTarget(order: TurnaroundOrder): number {
  return Math.max(0, order.ageMinutes - order.targetMinutes);
}

/** Whether the partner-interface outage banner must be shown. */
export function isOutage(health: InterfaceHealth): boolean {
  return health === 'down';
}

/**
 * Group orders under each of the five columns, preserving declared column
 * order. Every column is present, so an empty column still renders its empty
 * state.
 */
export function ordersByStage(
  orders: readonly TurnaroundOrder[],
): Readonly<Record<TurnaroundStage, readonly TurnaroundOrder[]>> {
  return orders.reduce<Record<TurnaroundStage, TurnaroundOrder[]>>(
    (grouped, order) => {
      grouped[order.stage].push(order);
      return grouped;
    },
    {
      ordered: [],
      collected: [],
      'in-analysis': [],
      'awaiting-verification': [],
      released: [],
    },
  );
}
